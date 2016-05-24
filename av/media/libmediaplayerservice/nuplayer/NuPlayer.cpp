/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "NuPlayer"
#include <utils/Log.h>

#include "NuPlayer.h"

#include "HTTPLiveSource.h"
#include "NuPlayerCCDecoder.h"
#include "NuPlayerDecoder.h"
#include "NuPlayerDecoderBase.h"
#include "NuPlayerDecoderPassThrough.h"
#include "NuPlayerDriver.h"
#include "NuPlayerRenderer.h"
#include "NuPlayerSource.h"
#include "RTSPSource.h"
#include "StreamingSource.h"
#include "GenericSource.h"
#include "TextDescriptions.h"

#include "ATSParser.h"

#include <cutils/properties.h>

#include <media/AudioResamplerPublic.h>
#include <media/AVSyncSettings.h>

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

#include <gui/IGraphicBufferProducer.h>
#include <gui/Surface.h>

#include "avc_utils.h"

#include "ESDS.h"
#include <media/stagefright/Utils.h>

#ifdef MTK_AOSP_ENHANCEMENT
#include <media/stagefright/OMXCodec.h>
#define ATRACE_TAG ATRACE_TAG_VIDEO
#include <utils/Trace.h>
#endif
#include <media/MtkMMLog.h>
namespace android {

struct NuPlayer::Action : public RefBase {
    Action() {}

    virtual void execute(NuPlayer *player) = 0;

private:
    DISALLOW_EVIL_CONSTRUCTORS(Action);
};

struct NuPlayer::SeekAction : public Action {
    SeekAction(int64_t seekTimeUs)
        : mSeekTimeUs(seekTimeUs) {
    }

    virtual void execute(NuPlayer *player) {
        player->performSeek(mSeekTimeUs);
    }

private:
    int64_t mSeekTimeUs;

    DISALLOW_EVIL_CONSTRUCTORS(SeekAction);
};

struct NuPlayer::ResumeDecoderAction : public Action {
    ResumeDecoderAction(bool needNotify)
        : mNeedNotify(needNotify) {
    }

    virtual void execute(NuPlayer *player) {
        player->performResumeDecoders(mNeedNotify);
    }

private:
    bool mNeedNotify;

    DISALLOW_EVIL_CONSTRUCTORS(ResumeDecoderAction);
};

struct NuPlayer::SetSurfaceAction : public Action {
    SetSurfaceAction(const sp<Surface> &surface)
        : mSurface(surface) {
    }

    virtual void execute(NuPlayer *player) {
        player->performSetSurface(mSurface);
    }

private:
    sp<Surface> mSurface;

    DISALLOW_EVIL_CONSTRUCTORS(SetSurfaceAction);
};

struct NuPlayer::FlushDecoderAction : public Action {
    FlushDecoderAction(FlushCommand audio, FlushCommand video)
        : mAudio(audio),
          mVideo(video) {
    }

    virtual void execute(NuPlayer *player) {
        player->performDecoderFlush(mAudio, mVideo);
    }

private:
    FlushCommand mAudio;
    FlushCommand mVideo;

    DISALLOW_EVIL_CONSTRUCTORS(FlushDecoderAction);
};

struct NuPlayer::PostMessageAction : public Action {
    PostMessageAction(const sp<AMessage> &msg)
        : mMessage(msg) {
    }

    virtual void execute(NuPlayer *) {
        mMessage->post();
    }

private:
    sp<AMessage> mMessage;

    DISALLOW_EVIL_CONSTRUCTORS(PostMessageAction);
};

// Use this if there's no state necessary to save in order to execute
// the action.
struct NuPlayer::SimpleAction : public Action {
    typedef void (NuPlayer::*ActionFunc)();

    SimpleAction(ActionFunc func)
        : mFunc(func) {
    }

    virtual void execute(NuPlayer *player) {
        (player->*mFunc)();
    }

private:
    ActionFunc mFunc;

    DISALLOW_EVIL_CONSTRUCTORS(SimpleAction);
};

////////////////////////////////////////////////////////////////////////////////

NuPlayer::NuPlayer(pid_t pid)
    : mUIDValid(false),
      mPID(pid),
      mSourceFlags(0),
      mOffloadAudio(false),
      mAudioDecoderGeneration(0),
      mVideoDecoderGeneration(0),
      mRendererGeneration(0),
      mPreviousSeekTimeUs(0),
      mAudioEOS(false),
      mVideoEOS(false),
      mScanSourcesPending(false),
      mScanSourcesGeneration(0),
      mPollDurationGeneration(0),
      mTimedTextGeneration(0),
      mFlushingAudio(NONE),
      mFlushingVideo(NONE),
      mResumePending(false),
      mVideoScalingMode(NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW),
      mPlaybackSettings(AUDIO_PLAYBACK_RATE_DEFAULT),
      mVideoFpsHint(-1.f),
      mStarted(false),
      mSourceStarted(false),
      mPaused(false),
      mPausedByClient(false),
      mPausedForBuffering(false) {
    clearFlushComplete();

#ifdef MTK_AOSP_ENHANCEMENT
  init_ext();
#endif
#ifdef MTK_SUBTITLE_SUPPORT
     mhandledSubtitleSeqNum = 0;
#endif
}

NuPlayer::~NuPlayer() {
    MM_LOGD("~NuPlayer");
}

void NuPlayer::setUID(uid_t uid) {
    mUIDValid = true;
    mUID = uid;
}

void NuPlayer::setDriver(const wp<NuPlayerDriver> &driver) {
    mDriver = driver;
}

void NuPlayer::setDataSourceAsync(const sp<IStreamSource> &source) {
#ifdef MTK_AOSP_ENHANCEMENT
    mIsStreamSource = true;
#endif
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, this);

    sp<AMessage> notify = new AMessage(kWhatSourceNotify, this);

    msg->setObject("source", new StreamingSource(notify, source));
    msg->post();
}

static bool IsHTTPLiveURL(const char *url) {
    if (!strncasecmp("http://", url, 7)
            || !strncasecmp("https://", url, 8)
            || !strncasecmp("file://", url, 7)) {
        size_t len = strlen(url);
        if (len >= 5 && !strcasecmp(".m3u8", &url[len - 5])) {
            return true;
        }

        if (strstr(url,"m3u8")) {
            return true;
        }
    }

    return false;
}


void NuPlayer::setDataSourceAsync(
        const sp<IMediaHTTPService> &httpService,
        const char *url,
        const KeyedVector<String8, String8> *headers) {

    sp<AMessage> msg = new AMessage(kWhatSetDataSource, this);
#ifndef MTK_AOSP_ENHANCEMENT
    size_t len = strlen(url);
#endif
    sp<AMessage> notify = new AMessage(kWhatSourceNotify, this);

    sp<Source> source;
    if (IsHTTPLiveURL(url)) {
        source = new HTTPLiveSource(notify, httpService, url, headers);
#ifdef MTK_AOSP_ENHANCEMENT
        mDataSourceType = SOURCE_HttpLive;
#endif
#ifdef MTK_AOSP_ENHANCEMENT
    } else if (IsRtspURL(url) || IsRtspSDP(url)) {
        ALOGI("Is RTSP Streaming");
        source = new RTSPSource(notify, httpService, url, headers, mUIDValid, mUID, IsRtspSDP(url));
#else
    } else if (!strncasecmp(url, "rtsp://", 7)) {
        source = new RTSPSource(
                notify, httpService, url, headers, mUIDValid, mUID);
    } else if ((!strncasecmp(url, "http://", 7)
                || !strncasecmp(url, "https://", 8))
                    && ((len >= 4 && !strcasecmp(".sdp", &url[len - 4]))
                    || strstr(url, ".sdp?"))) {
        source = new RTSPSource(
                notify, httpService, url, headers, mUIDValid, mUID, true);
#endif
    } else {
#ifdef MTK_AOSP_ENHANCEMENT
        if (!strncasecmp(url, "http://", 7)
                || !strncasecmp(url, "https://", 8)) {
            mDataSourceType = SOURCE_Http;
            ALOGI("Is http Streaming");
        } else {
            mDataSourceType = SOURCE_Local;
            ALOGI("local stream:%s", url);
        }
#endif
        sp<GenericSource> genericSource =
                new GenericSource(notify, mUIDValid, mUID);
        // Don't set FLAG_SECURE on mSourceFlags here for widevine.
        // The correct flags will be updated in Source::kWhatFlagsChanged
        // handler when  GenericSource is prepared.

        status_t err = genericSource->setDataSource(httpService, url, headers);

        if (err == OK) {
            source = genericSource;
        } else {
            ALOGE("Failed to set data source!");
        }
    }
    msg->setObject("source", source);
    msg->post();
}

void NuPlayer::setDataSourceAsync(int fd, int64_t offset, int64_t length) {
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, this);

    sp<AMessage> notify = new AMessage(kWhatSourceNotify, this);

    sp<GenericSource> source =
            new GenericSource(notify, mUIDValid, mUID);

    status_t err = source->setDataSource(fd, offset, length);

    if (err != OK) {
        ALOGE("Failed to set data source!");
        source = NULL;
    }

    msg->setObject("source", source);
#ifdef MTK_AOSP_ENHANCEMENT
    err = setDataSourceAsync_proCheck(msg,notify);
    if (err == OK) {
        msg->post();
    }
#else
    msg->post();
#endif
}

void NuPlayer::setDataSourceAsync(const sp<DataSource> &dataSource) {
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, this);
    sp<AMessage> notify = new AMessage(kWhatSourceNotify, this);

    sp<GenericSource> source = new GenericSource(notify, mUIDValid, mUID);
    status_t err = source->setDataSource(dataSource);

    if (err != OK) {
        ALOGE("Failed to set data source!");
        source = NULL;
    }

    msg->setObject("source", source);
    msg->post();
}

void NuPlayer::prepareAsync() {
    (new AMessage(kWhatPrepare, this))->post();
}

void NuPlayer::setVideoSurfaceTextureAsync(
        const sp<IGraphicBufferProducer> &bufferProducer) {
    sp<AMessage> msg = new AMessage(kWhatSetVideoSurface, this);

    if (bufferProducer == NULL) {
        MM_LOGI("Set null surface");
        msg->setObject("surface", NULL);
    } else {
        MM_LOGI("Set new surface");
        msg->setObject("surface", new Surface(bufferProducer, true /* controlledByApp */));
    }

    msg->post();
}

void NuPlayer::setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink) {
    sp<AMessage> msg = new AMessage(kWhatSetAudioSink, this);
    msg->setObject("sink", sink);
    msg->post();
}

void NuPlayer::start() {
    (new AMessage(kWhatStart, this))->post();
}

status_t NuPlayer::setPlaybackSettings(const AudioPlaybackRate &rate) {
    // do some cursory validation of the settings here. audio modes are
    // only validated when set on the audiosink.
     if ((rate.mSpeed != 0.f && rate.mSpeed < AUDIO_TIMESTRETCH_SPEED_MIN)
            || rate.mSpeed > AUDIO_TIMESTRETCH_SPEED_MAX
            || rate.mPitch < AUDIO_TIMESTRETCH_SPEED_MIN
            || rate.mPitch > AUDIO_TIMESTRETCH_SPEED_MAX) {
        return BAD_VALUE;
    }
    sp<AMessage> msg = new AMessage(kWhatConfigPlayback, this);
    writeToAMessage(msg, rate);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

status_t NuPlayer::getPlaybackSettings(AudioPlaybackRate *rate /* nonnull */) {
    sp<AMessage> msg = new AMessage(kWhatGetPlaybackSettings, this);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
        if (err == OK) {
            readFromAMessage(response, rate);
        }
    }
    return err;
}

status_t NuPlayer::setSyncSettings(const AVSyncSettings &sync, float videoFpsHint) {
    sp<AMessage> msg = new AMessage(kWhatConfigSync, this);
    writeToAMessage(msg, sync, videoFpsHint);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

status_t NuPlayer::getSyncSettings(
        AVSyncSettings *sync /* nonnull */, float *videoFps /* nonnull */) {
    sp<AMessage> msg = new AMessage(kWhatGetSyncSettings, this);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
        if (err == OK) {
            readFromAMessage(response, sync, videoFps);
        }
    }
    return err;
}

void NuPlayer::pause() {
    (new AMessage(kWhatPause, this))->post();
}

void NuPlayer::resetAsync() {
    MM_LOGI("mSource:%d", (mSource != NULL));
    if (mSource != NULL) {
        // During a reset, the data source might be unresponsive already, we need to
        // disconnect explicitly so that reads exit promptly.
        // We can't queue the disconnect request to the looper, as it might be
        // queued behind a stuck read and never gets processed.
        // Doing a disconnect outside the looper to allows the pending reads to exit
        // (either successfully or with error).
        mSource->disconnect();
    }

    (new AMessage(kWhatReset, this))->post();
}

void NuPlayer::seekToAsync(int64_t seekTimeUs, bool needNotify) {
    sp<AMessage> msg = new AMessage(kWhatSeek, this);
    msg->setInt64("seekTimeUs", seekTimeUs);
    msg->setInt32("needNotify", needNotify);
    msg->post();
#ifdef MTK_SUBTITLE_SUPPORT
     mhandledSubtitleSeqNum = 0;
#endif
}


void NuPlayer::writeTrackInfo(
        Parcel* reply, const sp<AMessage> format) const {
    int32_t trackType;
    CHECK(format->findInt32("type", &trackType));

    AString mime;
    if (!format->findString("mime", &mime)) {
        // Java MediaPlayer only uses mimetype for subtitle and timedtext tracks.
        // If we can't find the mimetype here it means that we wouldn't be needing
        // the mimetype on the Java end. We still write a placeholder mime to keep the
        // (de)serialization logic simple.
        if (trackType == MEDIA_TRACK_TYPE_AUDIO) {
            mime = "audio/";
        } else if (trackType == MEDIA_TRACK_TYPE_VIDEO) {
            mime = "video/";
        } else {
            TRESPASS();
        }
    }

    AString lang;
    CHECK(format->findString("language", &lang));

    reply->writeInt32(2); // write something non-zero
    reply->writeInt32(trackType);
    reply->writeString16(String16(mime.c_str()));
    reply->writeString16(String16(lang.c_str()));

    if (trackType == MEDIA_TRACK_TYPE_SUBTITLE) {
        int32_t isAuto, isDefault, isForced;
        CHECK(format->findInt32("auto", &isAuto));
        CHECK(format->findInt32("default", &isDefault));
        CHECK(format->findInt32("forced", &isForced));

        reply->writeInt32(isAuto);
        reply->writeInt32(isDefault);
        reply->writeInt32(isForced);
    }
}

void NuPlayer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSetDataSource:
        {
            ALOGD("kWhatSetDataSource");
 #ifdef MTK_AOSP_ENHANCEMENT
        ATRACE_ASYNC_BEGIN("setDataSource",mPlayerCnt);
        if(mSource == NULL) {
                int32_t result;
            if(msg->findInt32("result", &result)) {
                ALOGW("kWhatSetDataSource, notify driver result");
                sp<NuPlayerDriver> driver = mDriver.promote();
                driver->notifySetDataSourceCompleted(result);
                break;
            }
        }

#endif
            CHECK(mSource == NULL);

            status_t err = OK;
            sp<RefBase> obj;
            CHECK(msg->findObject("source", &obj));
            if (obj != NULL) {
                mSource = static_cast<Source *>(obj.get());
            } else {
                err = UNKNOWN_ERROR;
            }

            CHECK(mDriver != NULL);
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                driver->notifySetDataSourceCompleted(err);
            }
 #ifdef MTK_AOSP_ENHANCEMENT
        ATRACE_ASYNC_END("setDataSource",mPlayerCnt);
 #endif
            break;
        }

        case kWhatPrepare:
        {
#ifdef MTK_AOSP_ENHANCEMENT
           ATRACE_ASYNC_BEGIN("Prepare",mPlayerCnt);

            ALOGD("kWhatPrepare, source type = %d", (int)mDataSourceType);
            if (mPrepare == PREPARING)
                break;
            mPrepare = PREPARING;
            if (mSource == NULL) {
                ALOGW("prepare error: source is not ready");
                finishPrepare(UNKNOWN_ERROR);
                break;
            }
            if(mIsMtkPlayback){
                ALOGD("Turn on MTK music Enhancement = %d",mIsMtkPlayback);
                sp<MetaData> meta = new MetaData;
                meta->setInt32(kKeyIsMtkMusic,1);
                mSource->setParams(meta);
            }
#endif
            mSource->prepareAsync();
            break;
        }

        case kWhatGetTrackInfo:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            Parcel* reply;
            CHECK(msg->findPointer("reply", (void**)&reply));

            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }

            size_t ccTracks = 0;
            if (mCCDecoder != NULL) {
                ccTracks = mCCDecoder->getTrackCount();
            }

            // total track count
            reply->writeInt32(inbandTracks + ccTracks);

            // write inband tracks
            for (size_t i = 0; i < inbandTracks; ++i) {
                writeTrackInfo(reply, mSource->getTrackInfo(i));
            }

            // write CC track
            for (size_t i = 0; i < ccTracks; ++i) {
                writeTrackInfo(reply, mCCDecoder->getTrackInfo(i));
            }

            sp<AMessage> response = new AMessage;
            response->postReply(replyID);
            break;
        }

        case kWhatGetSelectedTrack:
        {
            status_t err = INVALID_OPERATION;
            if (mSource != NULL) {
                err = OK;

                int32_t type32;
                CHECK(msg->findInt32("type", (int32_t*)&type32));
                media_track_type type = (media_track_type)type32;
                ssize_t selectedTrack = mSource->getSelectedTrack(type);

                Parcel* reply;
                CHECK(msg->findPointer("reply", (void**)&reply));
                reply->writeInt32(selectedTrack);
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);

            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            response->postReply(replyID);
            break;
        }

        case kWhatSelectTrack:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            size_t trackIndex;
            int32_t select;
            int64_t timeUs;
            CHECK(msg->findSize("trackIndex", &trackIndex));
            CHECK(msg->findInt32("select", &select));
            CHECK(msg->findInt64("timeUs", &timeUs));

            status_t err = INVALID_OPERATION;

            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }
            size_t ccTracks = 0;
            if (mCCDecoder != NULL) {
                ccTracks = mCCDecoder->getTrackCount();
            }

            if (trackIndex < inbandTracks) {
                err = mSource->selectTrack(trackIndex, select, timeUs);
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
            if(mRenderer != NULL){
                mRenderer->changeAudio();
            }
#endif
                if (!select && err == OK) {
                    int32_t type;
                    sp<AMessage> info = mSource->getTrackInfo(trackIndex);
                    if (info != NULL
                            && info->findInt32("type", &type)
                            && type == MEDIA_TRACK_TYPE_TIMEDTEXT) {
                        ++mTimedTextGeneration;
                    }
                }
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
                if(mAudioEOS == true){
                    ALOGD("push performDecoderShutdown at kWhatSelectTrack");
                    mDeferredActions.push_back(
                        new FlushDecoderAction(
                            FLUSH_CMD_SHUTDOWN/* audio */,  FLUSH_CMD_NONE/* video */));

                    ALOGD("push performScanSources at kWhatSelectTrack");
                    mDeferredActions.push_back(
                        new SimpleAction(&NuPlayer::performScanSources));
                    processDeferredActions();
                }
#endif
            } else {
                trackIndex -= inbandTracks;

                if (trackIndex < ccTracks) {
                    err = mCCDecoder->selectTrack(trackIndex, select);
                }
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);

            response->postReply(replyID);
            break;
        }

        case kWhatPollDuration:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mPollDurationGeneration) {
                // stale
                break;
            }

            int64_t durationUs;
            if (mDriver != NULL && mSource->getDuration(&durationUs) == OK) {
                sp<NuPlayerDriver> driver = mDriver.promote();
                if (driver != NULL) {
                    driver->notifyDuration(durationUs);
                }
            }

            msg->post(1000000ll);  // poll again in a second.
            break;
        }

        case kWhatSetVideoSurface:
        {

            sp<RefBase> obj;
            CHECK(msg->findObject("surface", &obj));
            sp<Surface> surface = static_cast<Surface *>(obj.get());

            ALOGD("onSetVideoSurface(%p, %s video decoder)",
                    surface.get(),
                    (mSource != NULL && mStarted && mSource->getFormat(false /* audio */) != NULL
                            && mVideoDecoder != NULL) ? "have" : "no");

#ifdef MTK_AOSP_ENHANCEMENT
            // http Streaming should not getFormat, it would block by network
            if (mSource == NULL|| !mStarted || (mDataSourceType == SOURCE_Http && !(mSource->hasVideo()))
                    || (mDataSourceType != SOURCE_Http && mSource->getFormat(false /* audio */) == NULL)
                        || (mVideoDecoder != NULL && mVideoDecoder->setVideoSurface(surface) == OK)) {
                performSetSurface(surface);
                break;
            }
#else
            // Need to check mStarted before calling mSource->getFormat because NuPlayer might
            // be in preparing state and it could take long time.
            // When mStarted is true, mSource must have been set.
            if (mSource == NULL || !mStarted || mSource->getFormat(false /* audio */) == NULL
                    // NOTE: mVideoDecoder's mSurface is always non-null
                    || (mVideoDecoder != NULL && mVideoDecoder->setVideoSurface(surface) == OK)) {
                performSetSurface(surface);
                break;
            }
#endif

            mDeferredActions.push_back(
                    new FlushDecoderAction(FLUSH_CMD_FLUSH /* audio */,
                                           FLUSH_CMD_SHUTDOWN /* video */));

            mDeferredActions.push_back(new SetSurfaceAction(surface));

            if (obj != NULL || mAudioDecoder != NULL) {
                if (mStarted) {
                    // Issue a seek to refresh the video screen only if started otherwise
                    // the extractor may not yet be started and will assert.
                    // If the video decoder is not set (perhaps audio only in this case)
                    // do not perform a seek as it is not needed.
                    int64_t currentPositionUs = 0;
                    if (getCurrentPosition(&currentPositionUs) == OK) {
                        mDeferredActions.push_back(
                                new SeekAction(currentPositionUs));
                    }
                }

                // If there is a new surface texture, instantiate decoders
                // again if possible.
                mDeferredActions.push_back(
                        new SimpleAction(&NuPlayer::performScanSources));
            }

            // After a flush without shutdown, decoder is paused.
            // Don't resume it until source seek is done, otherwise it could
            // start pulling stale data too soon.
            mDeferredActions.push_back(
                    new ResumeDecoderAction(false /* needNotify */));

            processDeferredActions();
            break;
        }

        case kWhatSetAudioSink:
        {
            ALOGD("kWhatSetAudioSink");

            sp<RefBase> obj;
            CHECK(msg->findObject("sink", &obj));

            mAudioSink = static_cast<MediaPlayerBase::AudioSink *>(obj.get());
            ALOGD("\t\taudio sink: %p", mAudioSink.get());
            break;
        }

        case kWhatStart:
        {
            ALOGV("kWhatStart");
            MM_LOGI("kWhatStart:,mStarted:%d,mPausedForBuffering:%d,H:%d",
                    mStarted, mPausedForBuffering, mHaveSanSources);
            if (mStarted) {
                // do not resume yet if the source is still buffering
                if (!mPausedForBuffering) {
#ifdef MTK_AOSP_ENHANCEMENT
                    if(onResume_l())
                        break;
                    MM_LOGI("kWhatStart:mPlayState %d",mPlayState);
                    mPlayState = PLAYSENDING;
                    // ALPS02318704, when audio format is supported by parser, but audio codec config error,
                    // suspend/resume, video can not play. Because instantiate audio decoder return error when
                    // video decoder has not been instantiated, so nuplayer will notify 262 to AP.
                    if(mIsMtkPlayback && !mHaveSanSources) {
                if (mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http) {
                onScanSources();
            }
            }

#endif
                    onResume();
                }
            } else {
                onStart();
            }
            mPausedByClient = false;
            break;
        }

        case kWhatConfigPlayback:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            AudioPlaybackRate rate /* sanitized */;
            readFromAMessage(msg, &rate);
            status_t err = OK;
            if (mRenderer != NULL) {
                err = mRenderer->setPlaybackSettings(rate);
            }
            if (err == OK) {
                if (rate.mSpeed == 0.f) {
                    onPause();
                    // save all other settings (using non-paused speed)
                    // so we can restore them on start
                    AudioPlaybackRate newRate = rate;
                    newRate.mSpeed = mPlaybackSettings.mSpeed;
                    mPlaybackSettings = newRate;
                } else { /* rate.mSpeed != 0.f */
                    onResume();
                    mPlaybackSettings = rate;
#ifdef MTK_AOSP_ENHANCEMENT
                    if(mStarted){
                        mPlayState = PLAYING;
                    }
#endif
                }
            }

            if (mVideoDecoder != NULL) {
                float rate = getFrameRate();
                if (rate > 0) {
                    sp<AMessage> params = new AMessage();
                    params->setFloat("operating-rate", rate * mPlaybackSettings.mSpeed);
                    mVideoDecoder->setParameters(params);
                }
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatGetPlaybackSettings:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            AudioPlaybackRate rate = mPlaybackSettings;
            status_t err = OK;
            if (mRenderer != NULL) {
                err = mRenderer->getPlaybackSettings(&rate);
            }
            if (err == OK) {
                // get playback settings used by renderer, as it may be
                // slightly off due to audiosink not taking small changes.
                mPlaybackSettings = rate;
                if (mPaused) {
                    rate.mSpeed = 0.f;
                }
            }
            sp<AMessage> response = new AMessage;
            if (err == OK) {
                writeToAMessage(response, rate);
            }
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatConfigSync:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            ALOGV("kWhatConfigSync");
            AVSyncSettings sync;
            float videoFpsHint;
            readFromAMessage(msg, &sync, &videoFpsHint);
            status_t err = OK;
            if (mRenderer != NULL) {
                err = mRenderer->setSyncSettings(sync, videoFpsHint);
            }
            if (err == OK) {
                mSyncSettings = sync;
                mVideoFpsHint = videoFpsHint;
            }
            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatGetSyncSettings:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            AVSyncSettings sync = mSyncSettings;
            float videoFps = mVideoFpsHint;
            status_t err = OK;
            if (mRenderer != NULL) {
                err = mRenderer->getSyncSettings(&sync, &videoFps);
                if (err == OK) {
                    mSyncSettings = sync;
                    mVideoFpsHint = videoFps;
                }
            }
            sp<AMessage> response = new AMessage;
            if (err == OK) {
                writeToAMessage(response, sync, videoFps);
            }
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatScanSources:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));
            if (generation != mScanSourcesGeneration) {
                // Drop obsolete msg.
                break;
            }

            mScanSourcesPending = false;
#ifdef MTK_AOSP_ENHANCEMENT
            if (!mIsStreamSource && !mOffloadAudio) {       // StreamSource & AudioOffload should use google default, due to 3rd party usage
                scanSource_l(msg);
                if (mVideoDecoder != NULL && mAudioDecoder != NULL && mRenderer != NULL) {
                    ALOGI("has video and audio");
                    uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
                    mRenderer->setFlags(flag, true);
                }

                if (mVideoDecoder == NULL && mAudioDecoder != NULL && mRenderer != NULL){
                   if(mIsMtkPlayback && !mNotifyListenerVideodecoderIsNull)
                     {notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);}
                      mNotifyListenerVideodecoderIsNull=true;
                    }

                break;
            }
#endif
            ALOGD("scanning sources haveAudio=%d, haveVideo=%d",
                 mAudioDecoder != NULL, mVideoDecoder != NULL);

            bool mHadAnySourcesBefore =
                (mAudioDecoder != NULL) || (mVideoDecoder != NULL);

            // initialize video before audio because successful initialization of
            // video may change deep buffer mode of audio.
            if (mSurface != NULL) {
                instantiateDecoder(false, &mVideoDecoder);
            }

            // Don't try to re-open audio sink if there's an existing decoder.
            if (mAudioSink != NULL && mAudioDecoder == NULL) {
                instantiateDecoder(true, &mAudioDecoder);
            }

            if (!mHadAnySourcesBefore
                    && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
                // This is the first time we've found anything playable.

                if (mSourceFlags & Source::FLAG_DYNAMIC_DURATION) {
                    schedulePollDuration();
                }
            }

            status_t err;
            if ((err = mSource->feedMoreTSData()) != OK) {
                if (mAudioDecoder == NULL && mVideoDecoder == NULL) {
                    // We're not currently decoding anything (no audio or
                    // video tracks found) and we just ran out of input data.

                    if (err == ERROR_END_OF_STREAM) {
                        notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
                    } else {
                        notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
                    }
                }
                break;
            }

            if ((mAudioDecoder == NULL && mAudioSink != NULL)
                    || (mVideoDecoder == NULL && mSurface != NULL)) {
                msg->post(100000ll);
                mScanSourcesPending = true;
            }
            break;
        }

        case kWhatVideoNotify:
        case kWhatAudioNotify:
        {
            bool audio = (msg->what() == kWhatAudioNotify);

            int32_t currentDecoderGeneration =
                (audio? mAudioDecoderGeneration : mVideoDecoderGeneration);
            int32_t requesterGeneration = currentDecoderGeneration - 1;
            CHECK(msg->findInt32("generation", &requesterGeneration));

            if (requesterGeneration != currentDecoderGeneration) {
                ALOGD("got message from old %s decoder, generation(%d:%d)",
                        audio ? "audio" : "video", requesterGeneration,
                        currentDecoderGeneration);
                sp<AMessage> reply;
                if (!(msg->findMessage("reply", &reply))) {
                    return;
                }

                reply->setInt32("err", INFO_DISCONTINUITY);
                reply->post();
                return;
            }

            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == DecoderBase::kWhatInputDiscontinuity) {
                int32_t formatChange;
                CHECK(msg->findInt32("formatChange", &formatChange));

                ALOGD("%s discontinuity: formatChange %d",
                        audio ? "audio" : "video", formatChange);

                if (formatChange) {
                    mDeferredActions.push_back(
                            new FlushDecoderAction(
                                audio ? FLUSH_CMD_SHUTDOWN : FLUSH_CMD_NONE,
                                audio ? FLUSH_CMD_NONE : FLUSH_CMD_SHUTDOWN));
                }

                mDeferredActions.push_back(
                        new SimpleAction(
                                &NuPlayer::performScanSources));

                processDeferredActions();
            } else if (what == DecoderBase::kWhatEOS) {
                int32_t err;
                CHECK(msg->findInt32("err", &err));

                if (err == ERROR_END_OF_STREAM) {
                    ALOGD("got %s decoder EOS", audio ? "audio" : "video");
                }
                else {
                    ALOGD("got %s decoder EOS w/ error %d",
                         audio ? "audio" : "video",
                         err);
                }

                mRenderer->queueEOS(audio, err);
            } else if (what == DecoderBase::kWhatFlushCompleted) {
                MM_LOGD("decoder %s flush completed", audio ? "audio" : "video");
                ALOGV("decoder %s flush completed", audio ? "audio" : "video");

                handleFlushComplete(audio, true /* isDecoder */);
                finishFlushIfPossible();
            } else if (what == DecoderBase::kWhatVideoSizeChanged) {
                sp<AMessage> format;
                CHECK(msg->findMessage("format", &format));

                sp<AMessage> inputFormat =
                        mSource->getFormat(false /* audio */);

                updateVideoSize(inputFormat, format);
            } else if (what == DecoderBase::kWhatShutdownCompleted) {
                ALOGD("%s shutdown completed", audio ? "audio" : "video");
                if (audio) {
                    mAudioDecoder.clear();
                    ++mAudioDecoderGeneration;

                    CHECK_EQ((int)mFlushingAudio, (int)SHUTTING_DOWN_DECODER);
                    mFlushingAudio = SHUT_DOWN;
                } else {
                    mVideoDecoder.clear();
                    ++mVideoDecoderGeneration;

                    CHECK_EQ((int)mFlushingVideo, (int)SHUTTING_DOWN_DECODER);
                    mFlushingVideo = SHUT_DOWN;
                }

                finishFlushIfPossible();
            } else if (what == DecoderBase::kWhatResumeCompleted) {
                finishResume();
            } else if (what == DecoderBase::kWhatError) {
                status_t err;
                if (!msg->findInt32("err", &err) || err == OK) {
                    err = UNKNOWN_ERROR;
                }

                // Decoder errors can be due to Source (e.g. from streaming),
                // or from decoding corrupted bitstreams, or from other decoder
                // MediaCodec operations (e.g. from an ongoing reset or seek).
                // They may also be due to openAudioSink failure at
                // decoder start or after a format change.
                //
                // We try to gracefully shut down the affected decoder if possible,
                // rather than trying to force the shutdown with something
                // similar to performReset(). This method can lead to a hang
                // if MediaCodec functions block after an error, but they should
                // typically return INVALID_OPERATION instead of blocking.

                FlushStatus *flushing = audio ? &mFlushingAudio : &mFlushingVideo;
                ALOGE("received error(%#x) from %s decoder, flushing(%d), now shutting down",
                        err, audio ? "audio" : "video", *flushing);
#ifdef MTK_AOSP_ENHANCEMENT
                if (mRenderer != NULL) {
                    if (mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http){
                        uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
                        mRenderer->setFlags(flag, false);
            if (mSource->getFormat(true) != NULL) {
                            if(err != ERROR_END_OF_STREAM){
                                  err = ERROR_END_OF_STREAM;
                            }
                            mRenderer->queueEOS(audio, err);
            } // when audio is shorter than video, audio eos, then video decoder error, queueEOS: ALPS01933832
                    } else {
                        mRenderer->queueEOS(audio, err);
                    }
                }
#endif
                switch (*flushing) {
                    case NONE:
                        mDeferredActions.push_back(
                                new FlushDecoderAction(
                                    audio ? FLUSH_CMD_SHUTDOWN : FLUSH_CMD_NONE,
                                    audio ? FLUSH_CMD_NONE : FLUSH_CMD_SHUTDOWN));
                        processDeferredActions();
                        break;
                    case FLUSHING_DECODER:
                        *flushing = FLUSHING_DECODER_SHUTDOWN; // initiate shutdown after flush.
                        break; // Wait for flush to complete.
                    case FLUSHING_DECODER_SHUTDOWN:
                        break; // Wait for flush to complete.
                    case SHUTTING_DOWN_DECODER:
                        break; // Wait for shutdown to complete.
                    case FLUSHED:
                        // Widevine source reads must stop before releasing the video decoder.
                        if (!audio && mSource != NULL && mSourceFlags & Source::FLAG_SECURE) {
                            mSource->stop();
                            mSourceStarted = false;
                        }
                        getDecoder(audio)->initiateShutdown(); // In the middle of a seek.
                        *flushing = SHUTTING_DOWN_DECODER;     // Shut down.
                        break;
                    case SHUT_DOWN:
                        finishFlushIfPossible();  // Should not occur.
                        break;                    // Finish anyways.
                }
#ifdef MTK_AOSP_ENHANCEMENT
         handleForACodecError(audio,msg);
#else
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
#endif
            } else {
                ALOGV("Unhandled decoder notification %d '%c%c%c%c'.",
                      what,
                      what >> 24,
                      (what >> 16) & 0xff,
                      (what >> 8) & 0xff,
                      what & 0xff);
            }

            break;
        }

        case kWhatRendererNotify:
        {
            int32_t requesterGeneration = mRendererGeneration - 1;
            CHECK(msg->findInt32("generation", &requesterGeneration));
            if (requesterGeneration != mRendererGeneration) {
                ALOGV("got message from old renderer, generation(%d:%d)",
                        requesterGeneration, mRendererGeneration);
                return;
            }

            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == Renderer::kWhatEOS) {
                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));

                int32_t finalResult;
                CHECK(msg->findInt32("finalResult", &finalResult));

                if (audio) {
                    mAudioEOS = true;
                } else {
                    mVideoEOS = true;
                }

                if (finalResult == ERROR_END_OF_STREAM) {
                    ALOGD("reached %s EOS", audio ? "audio" : "video");
                } else {
                    ALOGE("%s track encountered an error (%d)",
                            audio ? "audio" : "video", finalResult);
#ifdef MTK_AOSP_ENHANCEMENT
                handleForRenderError1(finalResult,audio);
#else
                    notifyListener(
                            MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, finalResult);
#endif
                }

                if ((mAudioEOS || mAudioDecoder == NULL)
                 && (mVideoEOS || mVideoDecoder == NULL)) {
#ifdef MTK_AOSP_ENHANCEMENT
                    if (mIsMtkPlayback && finalResult == ERROR_END_OF_STREAM){
                        int64_t curPosition;
                        status_t err = getCurrentPosition(&curPosition);
                        if (err != OK) {
                            curPosition = 0;
                        }

                        if (mSource->notifyCanNotConnectServerIfPossible(curPosition)) {
                            ALOGI("For RTSP notify cannot connect server");
                            break;
                        }
                    }
#endif
                    notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
                }
            } else if (what == Renderer::kWhatFlushComplete) {
                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));

                MM_LOGD("renderer %s flush completed.", audio ? "audio" : "video");
                ALOGV("renderer %s flush completed.", audio ? "audio" : "video");
                if (audio && (mFlushingAudio == NONE || mFlushingAudio == FLUSHED
                        || mFlushingAudio == SHUT_DOWN)) {
                    // Flush has been handled by tear down.
                    break;
                }
                handleFlushComplete(audio, false /* isDecoder */);
                finishFlushIfPossible();
            } else if (what == Renderer::kWhatVideoRenderingStart) {
                notifyListener(MEDIA_INFO, MEDIA_INFO_RENDERING_START, 0);
            } else if (what == Renderer::kWhatMediaRenderingStart) {
                ALOGV("media rendering started");
                notifyListener(MEDIA_STARTED, 0, 0);
            } else if (what == Renderer::kWhatAudioTearDown) {
                ALOGD("Tear down audio offload, fall back to s/w path");
#ifdef MTK_AOSP_ENHANCEMENT
                if (mPlayState == PAUSING){
                    break;
                }
#endif
                int32_t reason;
                CHECK(msg->findInt32("reason", &reason));
                ALOGV("Tear down audio with reason %d.", reason);
                mAudioDecoder.clear();
                ++mAudioDecoderGeneration;
                bool needsToCreateAudioDecoder = true;
                if (mFlushingAudio == FLUSHING_DECODER) {
                    mFlushComplete[1 /* audio */][1 /* isDecoder */] = true;
                    mFlushingAudio = FLUSHED;
                    finishFlushIfPossible();
                } else if (mFlushingAudio == FLUSHING_DECODER_SHUTDOWN
                        || mFlushingAudio == SHUTTING_DOWN_DECODER) {
                    mFlushComplete[1 /* audio */][1 /* isDecoder */] = true;
                    mFlushingAudio = SHUT_DOWN;
                    finishFlushIfPossible();
                    needsToCreateAudioDecoder = false;
                }
                if (mRenderer == NULL) {
                    break;
                }
                closeAudioSink();
                mRenderer->flush(
                        true /* audio */, false /* notifyComplete */);
                if (mVideoDecoder != NULL) {
                    mRenderer->flush(
                            false /* audio */, false /* notifyComplete */);
                }

                int64_t positionUs;
                if (!msg->findInt64("positionUs", &positionUs)) {
                    positionUs = mPreviousSeekTimeUs;
                }
                performSeek(positionUs);

                if (reason == Renderer::kDueToError && needsToCreateAudioDecoder) {
                    instantiateDecoder(true /* audio */, &mAudioDecoder);
                }
            }
            break;
        }
        case kWhatMoreDataQueued:
        {
            break;
        }

        case kWhatReset:
        {
            ALOGD("kWhatReset");
#ifdef MTK_AOSP_ENHANCEMENT
            mIsReseting = true;
#endif
            mDeferredActions.push_back(
                    new FlushDecoderAction(
                        FLUSH_CMD_SHUTDOWN /* audio */,
                        FLUSH_CMD_SHUTDOWN /* video */));

            mDeferredActions.push_back(
                    new SimpleAction(&NuPlayer::performReset));

            processDeferredActions();
            break;
        }

        case kWhatSeek:
        {
            int64_t seekTimeUs;
            int32_t needNotify;
            CHECK(msg->findInt64("seekTimeUs", &seekTimeUs));
            CHECK(msg->findInt32("needNotify", &needNotify));

            ALOGV("kWhatSeek seekTimeUs=%lld us, needNotify=%d",
                    (long long)seekTimeUs, needNotify);

            MM_LOGI("kWhatSeek seekTimeUs=%lld us, needNotify=%d, Started:%d ",
                    (long long)seekTimeUs, needNotify, mStarted);
            if (!mStarted) {
                // Seek before the player is started. In order to preview video,
                // need to start the player and pause it. This branch is called
                // only once if needed. After the player is started, any seek
                // operation will go through normal path.
                // Audio-only cases are handled separately.
                onStart(seekTimeUs);
                if (mStarted) {
#ifdef MTK_AOSP_ENHANCEMENT
                    // set play state here
                    mPlayState = PAUSING;
#endif
                    onPause();
                    mPausedByClient = true;
                }
                if (needNotify) {
                    notifyDriverSeekComplete();
                }
                break;
            }
#ifdef MTK_AOSP_ENHANCEMENT
            mSeekTimeUs = seekTimeUs;
            if (mAudioDecoder != NULL || mVideoDecoder != NULL) {
                if (mRenderer != NULL) {
                    if (mPlayState == PLAYING || mPlayState == PLAYSENDING) {
                        ALOGI("When seek, do pause, mPlayState:%d", mPlayState);
                        mRenderer->pause();
                    }
                }
            }
#endif
            mDeferredActions.push_back(
                    new FlushDecoderAction(FLUSH_CMD_FLUSH /* audio */,
                                           FLUSH_CMD_FLUSH /* video */));

            mDeferredActions.push_back(
                    new SeekAction(seekTimeUs));

            // After a flush without shutdown, decoder is paused.
            // Don't resume it until source seek is done, otherwise it could
            // start pulling stale data too soon.
            mDeferredActions.push_back(
                    new ResumeDecoderAction(needNotify));

            processDeferredActions();
            break;
        }

        case kWhatPause:
        {
            MM_LOGI("kWhatPause,mPausedByClient:%d, mPaused:%d",
                     mPausedByClient, mPaused);
#ifdef MTK_AOSP_ENHANCEMENT
            if(isPausing())
                break;
            mPlayState = PAUSING;
#endif
            onPause();
            mPausedByClient = true;
            break;
        }

        case kWhatSourceNotify:
        {
            onSourceNotify(msg);
            break;
        }

        case kWhatClosedCaptionNotify:
        {
            onClosedCaptionNotify(msg);
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void NuPlayer::onResume() {
    if (!mPaused) {
        return;
    }
    mPaused = false;
    if (mSource != NULL) {
        mSource->resume();
    } else {
        ALOGW("resume called when source is gone or not set");
    }
    // |mAudioDecoder| may have been released due to the pause timeout, so re-create it if
    // needed.
    if (audioDecoderStillNeeded() && mAudioDecoder == NULL) {
        instantiateDecoder(true /* audio */, &mAudioDecoder);
    }
    if (mRenderer != NULL) {
        mRenderer->resume();
    } else {
        ALOGW("resume called when renderer is gone or not set");
    }
}

status_t NuPlayer::onInstantiateSecureDecoders() {
    status_t err;
    if (!(mSourceFlags & Source::FLAG_SECURE)) {
        return BAD_TYPE;
    }

    if (mRenderer != NULL) {
        ALOGE("renderer should not be set when instantiating secure decoders");
        return UNKNOWN_ERROR;
    }

    // TRICKY: We rely on mRenderer being null, so that decoder does not start requesting
    // data on instantiation.
    if (mSurface != NULL) {
        err = instantiateDecoder(false, &mVideoDecoder);
        if (err != OK) {
            return err;
        }
    }

    if (mAudioSink != NULL) {
        err = instantiateDecoder(true, &mAudioDecoder);
        if (err != OK) {
            return err;
        }
    }
    return OK;
}

void NuPlayer::onStart(int64_t startPositionUs) {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mPlayState == PLAYING) {
        ALOGI("onstart @ PLAYING State");
        return;
    }

    // because rtsp is async flow
    // we don't support show a frame in suspend resume
    if(isRTSPSource()){
        if(startPositionUs >= 0){
            ALOGD("[rtsp] onStart not to do in suspend resume");
                return;
        }
    }

    if (!mSourceStarted) {
        mSourceStarted = true;
        if (mSource != NULL) {
            mSource->start();
        }
    }
#else
    if (!mSourceStarted) {
        mSourceStarted = true;
        mSource->start();
    }
#endif
    if (startPositionUs > 0) {
        performSeek(startPositionUs);
        if (mSource->getFormat(false /* audio */) == NULL) {
            return;
        }
    }

    mOffloadAudio = false;
    mAudioEOS = false;
    mVideoEOS = false;
    mStarted = true;

    uint32_t flags = 0;

    if (mSource->isRealTime()) {
        flags |= Renderer::FLAG_REAL_TIME;
    }

    sp<MetaData> audioMeta = mSource->getFormatMeta(true /* audio */);
    audio_stream_type_t streamType = AUDIO_STREAM_MUSIC;
    if (mAudioSink != NULL) {
        streamType = mAudioSink->getAudioStreamType();
    }

    sp<AMessage> videoFormat = mSource->getFormat(false /* audio */);

    mOffloadAudio =
        canOffloadStream(audioMeta, (videoFormat != NULL), mSource->isStreaming(), streamType);
    if (mOffloadAudio) {
        flags |= Renderer::FLAG_OFFLOAD_AUDIO;
    }

    sp<AMessage> notify = new AMessage(kWhatRendererNotify, this);
    ++mRendererGeneration;
    notify->setInt32("generation", mRendererGeneration);
    mRenderer = new Renderer(mAudioSink, notify, flags);
#ifdef MTK_AOSP_ENHANCEMENT
    if (isRTSPSource()) {
        mRenderer->setUseSyncQueues(false);
    } else if(isHttpLiveSource()){
        mRenderer->setUseFlushAudioSyncQueues(true);
    }
    else{
        mRenderer->setUseSyncQueues(true);
    }
#endif
    mRendererLooper = new ALooper;
    mRendererLooper->setName("NuPlayerRenderer");
    mRendererLooper->start(false, false, ANDROID_PRIORITY_AUDIO);
    mRendererLooper->registerHandler(mRenderer);
    status_t err = mRenderer->setPlaybackSettings(mPlaybackSettings);
    if (err != OK) {
        mSource->stop();
        mSourceStarted = false;
        notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
        return;
    }
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
    if(mRenderer != NULL){
        mRenderer->setsmspeed(mslowmotion_speed);
    }
#endif

    float rate = getFrameRate();
    if (rate > 0) {
        mRenderer->setVideoFrameRate(rate);
    }

    if (mVideoDecoder != NULL) {
        mVideoDecoder->setRenderer(mRenderer);
    }
    if (mAudioDecoder != NULL) {
        mAudioDecoder->setRenderer(mRenderer);
    }

    postScanSources();
#ifdef MTK_AOSP_ENHANCEMENT
   if (mDataSourceType == SOURCE_HttpLive || isRTSPSource()){
       mRenderer->setLateVideoToDisplay(false);
   }
    mPlayState = PLAYING;
#endif

}

void NuPlayer::onPause() {
    if (mPaused) {
        return;
    }
    mPaused = true;
    if (mSource != NULL) {
        mSource->pause();
    } else {
        ALOGW("pause called when source is gone or not set");
    }
    if (mRenderer != NULL) {
        mRenderer->pause();
    } else {
        ALOGW("pause called when renderer is gone or not set");
    }
}

bool NuPlayer::audioDecoderStillNeeded() {
    // Audio decoder is no longer needed if it's in shut/shutting down status.
    return ((mFlushingAudio != SHUT_DOWN) && (mFlushingAudio != SHUTTING_DOWN_DECODER));
}

void NuPlayer::handleFlushComplete(bool audio, bool isDecoder) {
    // We wait for both the decoder flush and the renderer flush to complete
    // before entering either the FLUSHED or the SHUTTING_DOWN_DECODER state.

    mFlushComplete[audio][isDecoder] = true;
    if (!mFlushComplete[audio][!isDecoder]) {
        return;
    }

    FlushStatus *state = audio ? &mFlushingAudio : &mFlushingVideo;
    switch (*state) {
        case FLUSHING_DECODER:
        {
            *state = FLUSHED;
            break;
        }

        case FLUSHING_DECODER_SHUTDOWN:
        {
            *state = SHUTTING_DOWN_DECODER;

            ALOGV("initiating %s decoder shutdown", audio ? "audio" : "video");
            if (!audio) {
                // Widevine source reads must stop before releasing the video decoder.
                if (mSource != NULL && mSourceFlags & Source::FLAG_SECURE) {
                    mSource->stop();
                    mSourceStarted = false;
                }
            }
            getDecoder(audio)->initiateShutdown();
            break;
        }

        default:
            // decoder flush completes only occur in a flushing state.
            LOG_ALWAYS_FATAL_IF(isDecoder, "decoder flush in invalid state %d", *state);
            break;
    }
}

void NuPlayer::finishFlushIfPossible() {
    if (mFlushingAudio != NONE && mFlushingAudio != FLUSHED
            && mFlushingAudio != SHUT_DOWN) {

        MM_LOGD("not flushed, mFlushingAudio = %d", mFlushingAudio);

        return;
    }

    if (mFlushingVideo != NONE && mFlushingVideo != FLUSHED
            && mFlushingVideo != SHUT_DOWN) {
        MM_LOGD("not flushed, mFlushingVideo = %d", mFlushingVideo);
        return;
    }

    ALOGV("both audio and video are flushed now.");
    MM_LOGI("mFlushingAudio %d ,mFlushingVideo %d",mFlushingAudio,mFlushingVideo );
#ifdef MTK_AOSP_ENHANCEMENT
    uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
    if (mAudioDecoder != NULL && mFlushingAudio == FLUSHED &&
            mVideoDecoder != NULL && mFlushingVideo == FLUSHED) {
        ALOGI("has video and audio sync queue");
        mRenderer->setFlags(flag, true);
    }

    finishFlushIfPossible_l();
#endif

    mFlushingAudio = NONE;
    mFlushingVideo = NONE;

    clearFlushComplete();

    processDeferredActions();
}

void NuPlayer::postScanSources() {
    if (mScanSourcesPending) {
        return;
    }

    sp<AMessage> msg = new AMessage(kWhatScanSources, this);
    msg->setInt32("generation", mScanSourcesGeneration);
    msg->post();

    mScanSourcesPending = true;
}

void NuPlayer::tryOpenAudioSinkForOffload(const sp<AMessage> &format, bool hasVideo) {
    // Note: This is called early in NuPlayer to determine whether offloading
    // is possible; otherwise the decoders call the renderer openAudioSink directly.

    status_t err = mRenderer->openAudioSink(
            format, true /* offloadOnly */, hasVideo, AUDIO_OUTPUT_FLAG_NONE, &mOffloadAudio);
    if (err != OK) {
        // Any failure we turn off mOffloadAudio.
        mOffloadAudio = false;
    } else if (mOffloadAudio) {
        sp<MetaData> audioMeta =
                mSource->getFormatMeta(true /* audio */);
        sendMetaDataToHal(mAudioSink, audioMeta);
    }
}

void NuPlayer::closeAudioSink() {
    mRenderer->closeAudioSink();
}

void NuPlayer::determineAudioModeChange() {
    if (mSource == NULL || mAudioSink == NULL) {
        return;
    }

    if (mRenderer == NULL) {
        ALOGW("No renderer can be used to determine audio mode. Use non-offload for safety.");
        mOffloadAudio = false;
        return;
    }

    sp<MetaData> audioMeta = mSource->getFormatMeta(true /* audio */);
    sp<AMessage> videoFormat = mSource->getFormat(false /* audio */);
    audio_stream_type_t streamType = mAudioSink->getAudioStreamType();
    const bool hasVideo = (videoFormat != NULL);
    const bool canOffload = canOffloadStream(
            audioMeta, hasVideo, mSource->isStreaming(), streamType);
    if (canOffload) {
        if (!mOffloadAudio) {
            mRenderer->signalEnableOffloadAudio();
        }
        // open audio sink early under offload mode.
        sp<AMessage> format = mSource->getFormat(true /*audio*/);
        tryOpenAudioSinkForOffload(format, hasVideo);
    } else {
        if (mOffloadAudio) {
            mRenderer->signalDisableOffloadAudio();
            mOffloadAudio = false;
        }
    }
}

status_t NuPlayer::instantiateDecoder(bool audio, sp<DecoderBase> *decoder) {
#ifdef MTK_AOSP_ENHANCEMENT
      char tag[20];
      snprintf(tag, sizeof(tag), "init_%s_decoder", audio?"audio":"video");
      ATRACE_BEGIN(tag);
#endif
    // The audio decoder could be cleared by tear down. If still in shut down
    // process, no need to create a new audio decoder.
    if (*decoder != NULL || (audio && mFlushingAudio == SHUT_DOWN)) {
        MM_LOGD("%s decoder not NULL or audio decoder in shut down process!", audio?"audio":"video");
        return OK;
    }

    sp<AMessage> format = mSource->getFormat(audio);

    if (format == NULL) {
        return -EWOULDBLOCK;
    }

    format->setInt32("priority", 0 /* realtime */);

#ifdef MTK_AOSP_ENHANCEMENT
    if (mDebugDisableTrackId != 0) {        // only debug
        if (mDebugDisableTrackId == 1 && audio) {
            ALOGI("Only Debug  disable audio");
            return -EWOULDBLOCK;
        } else if (mDebugDisableTrackId == 2 && !audio) {
            ALOGI("Only Debug  disable video");
            return -EWOULDBLOCK;
        }
    }
    if(!audio) {
#ifdef MTK_CLEARMOTION_SUPPORT
        format->setInt32("use-clearmotion-mode", mEnClearMotion);
        ALOGD("mEnClearMotion(%d).", mEnClearMotion);
#endif
        ALOGD("instantiate Video decoder.");
    }
    else {
        ALOGD("instantiate Audio decoder.");
    }
#endif
    if (!audio) {
        AString mime;
        CHECK(format->findString("mime", &mime));

        sp<AMessage> ccNotify = new AMessage(kWhatClosedCaptionNotify, this);
        if (mCCDecoder == NULL) {
            mCCDecoder = new CCDecoder(ccNotify);
        }

        if (mSourceFlags & Source::FLAG_SECURE) {
            format->setInt32("secure", true);
        }

        if (mSourceFlags & Source::FLAG_PROTECTED) {
            format->setInt32("protected", true);
        }

        float rate = getFrameRate();
        if (rate > 0) {
            format->setFloat("operating-rate", rate * mPlaybackSettings.mSpeed);
        }
    }

    if (audio) {
        sp<AMessage> notify = new AMessage(kWhatAudioNotify, this);
        ++mAudioDecoderGeneration;
        notify->setInt32("generation", mAudioDecoderGeneration);

        determineAudioModeChange();
        if (mOffloadAudio) {
            const bool hasVideo = (mSource->getFormat(false /*audio */) != NULL);
            format->setInt32("has-video", hasVideo);
            *decoder = new DecoderPassThrough(notify, mSource, mRenderer);
        } else {
            *decoder = new Decoder(notify, mSource, mPID, mRenderer);
        }
    } else {
        sp<AMessage> notify = new AMessage(kWhatVideoNotify, this);
        ++mVideoDecoderGeneration;
        notify->setInt32("generation", mVideoDecoderGeneration);

        *decoder = new Decoder(
                notify, mSource, mPID, mRenderer, mSurface, mCCDecoder);

        // enable FRC if high-quality AV sync is requested, even if not
        // directly queuing to display, as this will even improve textureview
        // playback.
        {
            char value[PROPERTY_VALUE_MAX];
            if (property_get("persist.sys.media.avsync", value, NULL) &&
                    (!strcmp("1", value) || !strcasecmp("true", value))) {
                format->setInt32("auto-frc", 1);
            }
        }
    }
    (*decoder)->init();
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
    if (*decoder != NULL && !(mslowmotion_start == -1 && mslowmotion_end == -1)) {
        sp<AMessage> msg = new AMessage;
        format->setInt64("slowmotion-start", mslowmotion_start);
        format->setInt64("slowmotion-end", mslowmotion_end);
        format->setInt32("slowmotion-speed", mslowmotion_speed);
        ALOGD("(%d) instantiareDecoder-> set slowmotion start(%lld) ~ end(%lld), speed(%d)", __LINE__, mslowmotion_start, mslowmotion_start, mslowmotion_speed);
        msg->setInt64("slowmotion-start", mslowmotion_start);
        msg->setInt64("slowmotion-end", mslowmotion_end);
        msg->setInt32("slowmotion-speed", mslowmotion_speed);
        (*decoder) ->setParameters(msg);
    }
#endif

    (*decoder)->configure(format);

    // allocate buffers to decrypt widevine source buffers
    if (!audio && (mSourceFlags & Source::FLAG_SECURE)) {
        Vector<sp<ABuffer> > inputBufs;
        CHECK_EQ((*decoder)->getInputBuffers(&inputBufs), (status_t)OK);

        Vector<MediaBuffer *> mediaBufs;
        for (size_t i = 0; i < inputBufs.size(); i++) {
            const sp<ABuffer> &buffer = inputBufs[i];
            MediaBuffer *mbuf = new MediaBuffer(buffer->data(), buffer->size());
            mediaBufs.push(mbuf);
        }

        status_t err = mSource->setBuffers(audio, mediaBufs);
        if (err != OK) {
            for (size_t i = 0; i < mediaBufs.size(); ++i) {
                mediaBufs[i]->release();
            }
            mediaBufs.clear();
            ALOGE("Secure source didn't support secure mediaBufs.");
            return err;
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
      ATRACE_END();
#endif
    return OK;
}

void NuPlayer::updateVideoSize(
        const sp<AMessage> &inputFormat,
        const sp<AMessage> &outputFormat) {
    if (inputFormat == NULL) {
        ALOGW("Unknown video size, reporting 0x0!");
        notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
        return;
    }

    int32_t displayWidth, displayHeight;
    if (outputFormat != NULL) {
        int32_t width, height;
        CHECK(outputFormat->findInt32("width", &width));
        CHECK(outputFormat->findInt32("height", &height));

#ifdef MTK_AOSP_ENHANCEMENT
     updataVideoSize_ext(outputFormat,&displayWidth,&displayHeight);

#else
        // Use decoder's output info.
        int32_t cropLeft, cropTop, cropRight, cropBottom;
        CHECK(outputFormat->findRect(
                    "crop",
                    &cropLeft, &cropTop, &cropRight, &cropBottom));

        displayWidth = cropRight - cropLeft + 1;
        displayHeight = cropBottom - cropTop + 1;

        ALOGI("Video output format changed to %d x %d "
             "(crop: %d x %d @ (%d, %d))",
             width, height,
             displayWidth,
             displayHeight,
             cropLeft, cropTop);
#endif // AOSP ENHANCEMENT

#ifdef MTK_AOSP_ENHANCEMENT
        int32_t WRatio, HRatio;
        if (!outputFormat->findInt32("width-ratio", &WRatio)) {
            WRatio = 1;
        }
        if (!outputFormat->findInt32("height-ratio", &HRatio)) {
            HRatio = 1;
        }
        displayWidth *= WRatio;
        displayHeight *= HRatio;
#endif
    } else {
        CHECK(inputFormat->findInt32("width", &displayWidth));
        CHECK(inputFormat->findInt32("height", &displayHeight));

#ifdef MTK_AOSP_ENHANCEMENT
        m_i4ContainerWidth = displayWidth;
        m_i4ContainerHeight = displayHeight;
#endif

        ALOGV("Video input format %d x %d", displayWidth, displayHeight);
    }

    // Take into account sample aspect ratio if necessary:
    int32_t sarWidth, sarHeight;
    if (inputFormat->findInt32("sar-width", &sarWidth)
            && inputFormat->findInt32("sar-height", &sarHeight)) {
        ALOGV("Sample aspect ratio %d : %d", sarWidth, sarHeight);

        displayWidth = (displayWidth * sarWidth) / sarHeight;

        ALOGV("display dimensions %d x %d", displayWidth, displayHeight);
    }

    int32_t rotationDegrees;
    if (!inputFormat->findInt32("rotation-degrees", &rotationDegrees)) {
        rotationDegrees = 0;
    }

    if (rotationDegrees == 90 || rotationDegrees == 270) {
        int32_t tmp = displayWidth;
        displayWidth = displayHeight;
        displayHeight = tmp;
    }

    notifyListener(
            MEDIA_SET_VIDEO_SIZE,
            displayWidth,
            displayHeight);
}

void NuPlayer::notifyListener(int msg, int ext1, int ext2, const Parcel *in) {
    if (mDriver == NULL) {
        return;
    }

    sp<NuPlayerDriver> driver = mDriver.promote();

    if (driver == NULL) {
        return;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    reviseNotifyErrorCode(msg,&ext1,&ext2);
#endif
    driver->notifyListener(msg, ext1, ext2, in);
}

void NuPlayer::flushDecoder(bool audio, bool needShutdown) {
    ALOGD("[%s] flushDecoder needShutdown=%d",
          audio ? "audio" : "video", needShutdown);

    const sp<DecoderBase> &decoder = getDecoder(audio);
    if (decoder == NULL) {
        ALOGI("flushDecoder %s without decoder present",
             audio ? "audio" : "video");
        return;
    }

    // Make sure we don't continue to scan sources until we finish flushing.
    ++mScanSourcesGeneration;
    if (mScanSourcesPending) {
        mDeferredActions.push_back(
                new SimpleAction(&NuPlayer::performScanSources));
        mScanSourcesPending = false;
    }

    decoder->signalFlush();

    FlushStatus newStatus =
        needShutdown ? FLUSHING_DECODER_SHUTDOWN : FLUSHING_DECODER;

    mFlushComplete[audio][false /* isDecoder */] = (mRenderer == NULL);
    mFlushComplete[audio][true /* isDecoder */] = false;
    if (audio) {
        ALOGE_IF(mFlushingAudio != NONE,
                "audio flushDecoder() is called in state %d", mFlushingAudio);
        mFlushingAudio = newStatus;
    } else {
        ALOGE_IF(mFlushingVideo != NONE,
                "video flushDecoder() is called in state %d", mFlushingVideo);
        mFlushingVideo = newStatus;
    }
}

void NuPlayer::queueDecoderShutdown(
        bool audio, bool video, const sp<AMessage> &reply) {
    ALOGI("queueDecoderShutdown audio=%d, video=%d", audio, video);

    mDeferredActions.push_back(
            new FlushDecoderAction(
                audio ? FLUSH_CMD_SHUTDOWN : FLUSH_CMD_NONE,
                video ? FLUSH_CMD_SHUTDOWN : FLUSH_CMD_NONE));

    mDeferredActions.push_back(
            new SimpleAction(&NuPlayer::performScanSources));

    mDeferredActions.push_back(new PostMessageAction(reply));

    processDeferredActions();
}

status_t NuPlayer::setVideoScalingMode(int32_t mode) {
    mVideoScalingMode = mode;
    if (mSurface != NULL) {
        status_t ret = native_window_set_scaling_mode(mSurface.get(), mVideoScalingMode);
        if (ret != OK) {
            ALOGE("Failed to set scaling mode (%d): %s",
                -ret, strerror(-ret));
            return ret;
        }
    }
    return OK;
}

status_t NuPlayer::getTrackInfo(Parcel* reply) const {
    sp<AMessage> msg = new AMessage(kWhatGetTrackInfo, this);
    msg->setPointer("reply", reply);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    return err;
}

status_t NuPlayer::getSelectedTrack(int32_t type, Parcel* reply) const {
    sp<AMessage> msg = new AMessage(kWhatGetSelectedTrack, this);
    msg->setPointer("reply", reply);
    msg->setInt32("type", type);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

status_t NuPlayer::selectTrack(size_t trackIndex, bool select, int64_t timeUs) {
    sp<AMessage> msg = new AMessage(kWhatSelectTrack, this);
    msg->setSize("trackIndex", trackIndex);
    msg->setInt32("select", select);
    MM_LOGI("[select track] selectTrack: trackIndex = %d and select=%d, timeUs:%lld", trackIndex, select, timeUs);
    msg->setInt64("timeUs", timeUs);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);

    if (err != OK) {
        return err;
    }

    if (!response->findInt32("err", &err)) {
        err = OK;
    }

    return err;
}

status_t NuPlayer::getCurrentPosition(int64_t *mediaUs) {
    sp<Renderer> renderer = mRenderer;
    if (renderer == NULL) {
        return NO_INIT;
    }

    return renderer->getCurrentPosition(mediaUs);
}

void NuPlayer::getStats(Vector<sp<AMessage> > *mTrackStats) {
    CHECK(mTrackStats != NULL);

    mTrackStats->clear();
    if (mVideoDecoder != NULL) {
        mTrackStats->push_back(mVideoDecoder->getStats());
    }
    if (mAudioDecoder != NULL) {
        mTrackStats->push_back(mAudioDecoder->getStats());
    }
}

sp<MetaData> NuPlayer::getFileMeta() {
    return mSource->getFileFormatMeta();
}

float NuPlayer::getFrameRate() {
    sp<MetaData> meta = mSource->getFormatMeta(false /* audio */);
    if (meta == NULL) {
        return 0;
    }
    int32_t rate;
    if (!meta->findInt32(kKeyFrameRate, &rate)) {
        // fall back to try file meta
        sp<MetaData> fileMeta = getFileMeta();
        if (fileMeta == NULL) {
            ALOGW("source has video meta but not file meta");
            return -1;
        }
        int32_t fileMetaRate;
        if (!fileMeta->findInt32(kKeyFrameRate, &fileMetaRate)) {
            return -1;
        }
        return fileMetaRate;
    }
    return rate;
}

void NuPlayer::schedulePollDuration() {
    sp<AMessage> msg = new AMessage(kWhatPollDuration, this);
    msg->setInt32("generation", mPollDurationGeneration);
    msg->post();
}

void NuPlayer::cancelPollDuration() {
    ++mPollDurationGeneration;
}

void NuPlayer::processDeferredActions() {
    while (!mDeferredActions.empty()) {
        // We won't execute any deferred actions until we're no longer in
        // an intermediate state, i.e. one more more decoders are currently
        // flushing or shutting down.

        if (mFlushingAudio != NONE || mFlushingVideo != NONE) {
            // We're currently flushing, postpone the reset until that's
            // completed.

            ALOGV("postponing action mFlushingAudio=%d, mFlushingVideo=%d",
                  mFlushingAudio, mFlushingVideo);

            break;
        }

        sp<Action> action = *mDeferredActions.begin();
        mDeferredActions.erase(mDeferredActions.begin());

        action->execute(this);
    }
}

void NuPlayer::performSeek(int64_t seekTimeUs) {
    ALOGI("performSeek seekTimeUs=%lld us (%.2f secs)",
          (long long)seekTimeUs,
          seekTimeUs / 1E6);

    if (mSource == NULL) {
        // This happens when reset occurs right before the loop mode
        // asynchronously seeks to the start of the stream.
        LOG_ALWAYS_FATAL_IF(mAudioDecoder != NULL || mVideoDecoder != NULL,
                "mSource is NULL and decoders not NULL audio(%p) video(%p)",
                mAudioDecoder.get(), mVideoDecoder.get());
        return;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    performSeek_l(seekTimeUs);
#else
    mPreviousSeekTimeUs = seekTimeUs;
    mSource->seekTo(seekTimeUs);
#endif
    ++mTimedTextGeneration;

    // everything's flushed, continue playback.
}

void NuPlayer::performDecoderFlush(FlushCommand audio, FlushCommand video) {
    MM_LOGD("performDecoderFlush audio=%d, video=%d", audio, video);
    ALOGV("performDecoderFlush audio=%d, video=%d", audio, video);

    if ((audio == FLUSH_CMD_NONE || mAudioDecoder == NULL)
            && (video == FLUSH_CMD_NONE || mVideoDecoder == NULL)) {
        return;
    }

    if (audio != FLUSH_CMD_NONE && mAudioDecoder != NULL) {
        flushDecoder(true /* audio */, (audio == FLUSH_CMD_SHUTDOWN));
    }

    if (video != FLUSH_CMD_NONE && mVideoDecoder != NULL) {
        flushDecoder(false /* audio */, (video == FLUSH_CMD_SHUTDOWN));
    }
}

void NuPlayer::performReset() {
    ALOGD("performReset");

    CHECK(mAudioDecoder == NULL);
    CHECK(mVideoDecoder == NULL);

    cancelPollDuration();

    ++mScanSourcesGeneration;
    mScanSourcesPending = false;

    if (mRendererLooper != NULL) {
        if (mRenderer != NULL) {
            mRendererLooper->unregisterHandler(mRenderer->id());
        }
        mRendererLooper->stop();
        mRendererLooper.clear();
    }
    mRenderer.clear();
    ++mRendererGeneration;

#ifdef MTK_AOSP_ENHANCEMENT
    mPlayState = STOPPED;
    mIsReseting = false;
#endif
    if (mSource != NULL) {
        mSource->stop();

        mSource.clear();
    }

    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifyResetComplete();
        }
    }

    mStarted = false;
    mSourceStarted = false;
}

void NuPlayer::performScanSources() {
    ALOGD("performScanSources");

    if (!mStarted) {
        return;
    }

    if (mAudioDecoder == NULL || mVideoDecoder == NULL) {
        postScanSources();
    }
}

void NuPlayer::performSetSurface(const sp<Surface> &surface) {
    ALOGV("performSetSurface");

    mSurface = surface;

    // XXX - ignore error from setVideoScalingMode for now
#ifdef MTK_AOSP_ENHANCEMENT
    if (mSurface != NULL && mSurface.get() != NULL)
#endif
    setVideoScalingMode(mVideoScalingMode);

    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifySetSurfaceComplete();
        }
    }
}

void NuPlayer::performResumeDecoders(bool needNotify) {
    ALOGI("performResumeDecoders needNotify = %d mVideoDecoder = %p mAudioDecoder = %p", needNotify, mVideoDecoder.get(), mAudioDecoder.get());

    if (needNotify) {
        mResumePending = true;
        if (mVideoDecoder == NULL) {
            // if audio-only, we can notify seek complete now,
            // as the resume operation will be relatively fast.
            finishResume();
        }
    }

    if (mVideoDecoder != NULL) {
        // When there is continuous seek, MediaPlayer will cache the seek
        // position, and send down new seek request when previous seek is
        // complete. Let's wait for at least one video output frame before
        // notifying seek complete, so that the video thumbnail gets updated
        // when seekbar is dragged.
        mVideoDecoder->signalResume(needNotify);
    }

    if (mAudioDecoder != NULL) {
        mAudioDecoder->signalResume(false /* needNotify */);
    }
}

void NuPlayer::finishResume() {
    if (mResumePending) {
        mResumePending = false;
        notifyDriverSeekComplete();
    }
}

void NuPlayer::notifyDriverSeekComplete() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mDataSourceType == SOURCE_Http) {
        if(!mSourceSeekDone) {
            ALOGI("Http streaming Source not seek Done,not notify seek complete");
            return;
        } else {
            // call finishSeek, let render resume if necessary
            finishSeek();
        }
    }
#endif
    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifySeekComplete();
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
    mSeekTimeUs = -1;
#endif
}

void NuPlayer::onSourceNotify(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case Source::kWhatInstantiateSecureDecoders:
        {
            if (mSource == NULL) {
                // This is a stale notification from a source that was
                // asynchronously preparing when the client called reset().
                // We handled the reset, the source is gone.
                break;
            }

            sp<AMessage> reply;
            CHECK(msg->findMessage("reply", &reply));
            status_t err = onInstantiateSecureDecoders();
            reply->setInt32("err", err);
            reply->post();
            break;
        }

        case Source::kWhatPrepared:
        {
            if (mSource == NULL) {
                // This is a stale notification from a source that was
                // asynchronously preparing when the client called reset().
                // We handled the reset, the source is gone.
                break;
            }

            int32_t err;
            CHECK(msg->findInt32("err", &err));

            if (err != OK) {
                // shut down potential secure codecs in case client never calls reset
                mDeferredActions.push_back(
                        new FlushDecoderAction(FLUSH_CMD_SHUTDOWN /* audio */,
                                               FLUSH_CMD_SHUTDOWN /* video */));
                processDeferredActions();
            }

#ifdef MTK_AOSP_ENHANCEMENT
            onSourcePrepard(err);
#else
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                // notify duration first, so that it's definitely set when
                // the app received the "prepare complete" callback.
                int64_t durationUs;
                if (mSource->getDuration(&durationUs) == OK) {
                    driver->notifyDuration(durationUs);
                }
                driver->notifyPrepareCompleted(err);
            }
#endif
            break;
        }

        case Source::kWhatFlagsChanged:
        {
            uint32_t flags;
            CHECK(msg->findInt32("flags", (int32_t *)&flags));

            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                if ((flags & NuPlayer::Source::FLAG_CAN_SEEK) == 0) {
                    driver->notifyListener(
                            MEDIA_INFO, MEDIA_INFO_NOT_SEEKABLE, 0);
                }
                driver->notifyFlagsChanged(flags);
            }

            if ((mSourceFlags & Source::FLAG_DYNAMIC_DURATION)
                    && (!(flags & Source::FLAG_DYNAMIC_DURATION))) {
                cancelPollDuration();
            } else if (!(mSourceFlags & Source::FLAG_DYNAMIC_DURATION)
                    && (flags & Source::FLAG_DYNAMIC_DURATION)
                    && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
                schedulePollDuration();
            }

            mSourceFlags = flags;
            break;
        }

        case Source::kWhatVideoSizeChanged:
        {
            sp<AMessage> format;
            CHECK(msg->findMessage("format", &format));

            updateVideoSize(format);
            break;
        }

        case Source::kWhatBufferingUpdate:
        {
            int32_t percentage;
            CHECK(msg->findInt32("percentage", &percentage));

            notifyListener(MEDIA_BUFFERING_UPDATE, percentage, 0);
            break;
        }

        case Source::kWhatPauseOnBufferingStart:
        {
            // ignore if not playing
            if (mStarted) {
                ALOGI("buffer low, pausing...");

                mPausedForBuffering = true;
                onPause();
            }
            // fall-thru
        }

        case Source::kWhatBufferingStart:
        {
            notifyListener(MEDIA_INFO, MEDIA_INFO_BUFFERING_START, 0);
#ifdef MTK_AOSP_ENHANCEMENT
            if (isRTSPSource() && mRenderer != NULL) {
                mRenderer->notifyBufferingStart();
            }
#endif
            break;
        }

        case Source::kWhatResumeOnBufferingEnd:
        {
            // ignore if not playing
            if (mStarted) {
                ALOGI("buffer ready, resuming...");

                mPausedForBuffering = false;

                // do not resume yet if client didn't unpause
                if (!mPausedByClient) {
#ifdef MTK_AOSP_ENHANCEMENT
               // if reseting, should not Resume, or else NE
                    if (!mIsReseting)
#endif
                    onResume();
                }
            }
            // fall-thru
        }

        case Source::kWhatBufferingEnd:
        {
            notifyListener(MEDIA_INFO, MEDIA_INFO_BUFFERING_END, 0);
#ifdef MTK_AOSP_ENHANCEMENT
            if (isRTSPSource() && mRenderer != NULL) {
                mRenderer->notifyBufferingEnd();
            }
#endif
            break;
        }

        case Source::kWhatCacheStats:
        {
            int32_t kbps;
            CHECK(msg->findInt32("bandwidth", &kbps));

            notifyListener(MEDIA_INFO, MEDIA_INFO_NETWORK_BANDWIDTH, kbps);
            break;
        }

        case Source::kWhatSubtitleData:
        {
            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));

            sendSubtitleData(buffer, 0 /* baseIndex */);
            break;
        }

        case Source::kWhatTimedMetaData:
        {
            sp<ABuffer> buffer;
            if (!msg->findBuffer("buffer", &buffer)) {
                notifyListener(MEDIA_INFO, MEDIA_INFO_METADATA_UPDATE, 0);
            } else {
                sendTimedMetaData(buffer);
            }
            break;
        }

        case Source::kWhatTimedTextData:
        {
            int32_t generation;
            if (msg->findInt32("generation", &generation)
                    && generation != mTimedTextGeneration) {
                break;
            }

            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));

            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver == NULL) {
                break;
            }

            int posMs;
            int64_t timeUs, posUs;
            driver->getCurrentPosition(&posMs);
            posUs = posMs * 1000;
            CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

            MM_LOGI("posUs:%lld, timeUs:%lld", posUs, timeUs);
            if (posUs < timeUs) {
                if (!msg->findInt32("generation", &generation)) {
                    msg->setInt32("generation", mTimedTextGeneration);
                }
                msg->post(timeUs - posUs);
            } else {
                sendTimedTextData(buffer);
            }
            break;
        }

        case Source::kWhatQueueDecoderShutdown:
        {
            int32_t audio, video;
            CHECK(msg->findInt32("audio", &audio));
            CHECK(msg->findInt32("video", &video));

            sp<AMessage> reply;
            CHECK(msg->findMessage("reply", &reply));

            queueDecoderShutdown(audio, video, reply);
            break;
        }

        case Source::kWhatDrmNoLicense:
        {
            notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, ERROR_DRM_NO_LICENSE);
            break;
        }

        default:
#ifdef MTK_AOSP_ENHANCEMENT
        if(!onSourceNotify_ext(msg))
#endif
            TRESPASS();
    }
}

void NuPlayer::onClosedCaptionNotify(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case NuPlayer::CCDecoder::kWhatClosedCaptionData:
        {
            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));
            ALOGD("rock kWhatClosedCaptionData");
            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }

            sendSubtitleData(buffer, inbandTracks);
            break;
        }

        case NuPlayer::CCDecoder::kWhatTrackAdded:
        {
            ALOGD("rock kWhatTrackAdded");
            notifyListener(MEDIA_INFO, MEDIA_INFO_METADATA_UPDATE, 0);

            break;
        }

        default:
            TRESPASS();
    }


}

void NuPlayer::sendSubtitleData(const sp<ABuffer> &buffer, int32_t baseIndex) {
    int32_t trackIndex;
    int64_t timeUs, durationUs;
    CHECK(buffer->meta()->findInt32("trackIndex", &trackIndex));
    CHECK(buffer->meta()->findInt64("timeUs", &timeUs));
    CHECK(buffer->meta()->findInt64("durationUs", &durationUs));

    Parcel in;
    in.writeInt32(trackIndex + baseIndex);
    in.writeInt64(timeUs);
    in.writeInt64(durationUs);
    in.writeInt32(buffer->size());
    in.writeInt32(buffer->size());
    in.write(buffer->data(), buffer->size());

    notifyListener(MEDIA_SUBTITLE_DATA, 0, 0, &in);
}

void NuPlayer::sendTimedMetaData(const sp<ABuffer> &buffer) {
    int64_t timeUs;
    CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

    Parcel in;
    in.writeInt64(timeUs);
    in.writeInt32(buffer->size());
    in.writeInt32(buffer->size());
    in.write(buffer->data(), buffer->size());

    notifyListener(MEDIA_META_DATA, 0, 0, &in);
}

void NuPlayer::sendTimedTextData(const sp<ABuffer> &buffer) {
    const void *data;
    size_t size = 0;
    int64_t timeUs;
    int32_t flag = TextDescriptions::LOCAL_DESCRIPTIONS;

    AString mime;
    CHECK(buffer->meta()->findString("mime", &mime));
    CHECK(strcasecmp(mime.c_str(), MEDIA_MIMETYPE_TEXT_3GPP) == 0);

    data = buffer->data();
    size = buffer->size();

    Parcel parcel;
    if (size > 0) {
        CHECK(buffer->meta()->findInt64("timeUs", &timeUs));
        flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
        TextDescriptions::getParcelOfDescriptions(
                (const uint8_t *)data, size, flag, timeUs / 1000, &parcel);
    }

    if ((parcel.dataSize() > 0)) {
#ifdef MTK_AOSP_ENHANCEMENT
        // debug for check send string content, include properties and timedtext .etc.
        {
            //int num = parcel.dataSize();
            const uint8_t *tmp = (uint8_t *)parcel.data();
            if (tmp[0] == 0x66 && tmp[4] == 0x7 && tmp[12] == 0x10) {
                int textlen = *(uint32_t *)&(tmp[16]);
                ALOGI("text len:%d", textlen);
            }
        }
#endif
        notifyListener(MEDIA_TIMED_TEXT, 0, 0, &parcel);
    } else {  // send an empty timed text
        notifyListener(MEDIA_TIMED_TEXT, 0, 0);
    }
}
////////////////////////////////////////////////////////////////////////////////

sp<AMessage> NuPlayer::Source::getFormat(bool audio) {
    sp<MetaData> meta = getFormatMeta(audio);

    if (meta == NULL) {
        return NULL;
    }

    sp<AMessage> msg = new AMessage;

    if(convertMetaDataToMessage(meta, &msg) == OK) {
        return msg;
    }
    return NULL;
}

void NuPlayer::Source::notifyFlagsChanged(uint32_t flags) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatFlagsChanged);
    notify->setInt32("flags", flags);
    notify->post();
}

void NuPlayer::Source::notifyVideoSizeChanged(const sp<AMessage> &format) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatVideoSizeChanged);
    notify->setMessage("format", format);
    notify->post();
}

void NuPlayer::Source::notifyPrepared(status_t err) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatPrepared);
    notify->setInt32("err", err);
    notify->post();
}

void NuPlayer::Source::notifyInstantiateSecureDecoders(const sp<AMessage> &reply) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatInstantiateSecureDecoders);
    notify->setMessage("reply", reply);
    notify->post();
}

void NuPlayer::Source::onMessageReceived(const sp<AMessage> & /* msg */) {
    TRESPASS();
}
#ifdef MTK_AOSP_ENHANCEMENT

int32_t NuPlayer::mPlayerCnt = 0;

 bool NuPlayer::IsHttpURL(const char *url) {
    return (!strncasecmp(url, "http://", 7) || !strncasecmp(url, "https://", 8));
}

bool NuPlayer::IsRtspURL(const char *url) {
    return !strncasecmp(url, "rtsp://", 7);
}

bool NuPlayer::IsRtspSDP(const char *url) {
    size_t len = strlen(url);
    bool isSDP = (len >= 4 && !strcasecmp(".sdp", &url[len - 4])) || strstr(url, ".sdp?");
    return (IsHttpURL(url) && isSDP);
}

void NuPlayer::init_ext(){
    mVideoDecoder = NULL;
    mAudioDecoder = NULL;
    mRenderer = NULL;
    mFlags =0;
    mPrepare = UNPREPARED;
    mDataSourceType = SOURCE_Default;
    mPlayState =STOPPED ;
    mAudioOnly = false;
    mVideoOnly =false;
    mSeekTimeUs =-1;
    mVideoinfoNotify =false;
    mAudioinfoNotify =false;
    mNotifyListenerVideodecoderIsNull =false;
#ifdef MTK_CLEARMOTION_SUPPORT
    m_i4ContainerWidth =-1;
    m_i4ContainerHeight =-1;
    mEnClearMotion =1;
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
    mslowmotion_start = -1;
    mslowmotion_end = -1;
    mslowmotion_speed =1;
#endif

    char value[PROPERTY_VALUE_MAX];   // only debug
    if (property_get("nuplayer.debug.disable.track", value, NULL)) {
        mDebugDisableTrackId = atoi(value);
    } else {
        mDebugDisableTrackId = 0;
    }
    ALOGI("disable trackId:%d", mDebugDisableTrackId);
    mIsStreamSource = false;
    mDeferTriggerSeekTimes =-1;//for suspend-resume-seek
    mIsMtkPlayback = false;
    mSourceSeekDone = false;
    mHaveSanSources = false; // for ALPS02318704; when audio decoder config error, suspend/resume video can not play.
    mIsReseting = false;     // http Streaming timing issue
}

bool NuPlayer::onSourceNotify_ext(const sp<AMessage> &msg){
    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case Source::kWhatDurationUpdate:
        {
            int64_t durationUs;
            if (mDataSourceType != SOURCE_Local)
            {
                //only handle local playback
                break;
            }
            CHECK(msg->findInt64("durationUs", &durationUs));
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                // notify duration
               driver->notifyUpdateDuration(durationUs);
            }
            break;
        }
        case Source::kWhatSourceError:
        {
            int32_t err;
            CHECK(msg->findInt32("err", &err));
            if (!mIsMtkPlayback && mDataSourceType == SOURCE_Http){
                ALOGI("http not mtk playback, do not notify not android error");
            } else {
                notifyListener(MEDIA_ERROR, err, 0);
            }
            ALOGI("Source err");
            break;
        }
#ifdef MTK_SUBTITLE_SUPPORT
        case Source::kWhatTimedTextData2:
        {
            ALOGD("func:%s L=%d ",__func__,__LINE__);
            int32_t generation;
            if (msg->findInt32("generation", &generation)
                    && generation != mTimedTextGeneration) {
                break;
            }

            sp<RefBase> obj;
            msg->findObject("subtitle", &obj);

            sp<ParcelEvent> parcelEvent;
            parcelEvent = static_cast<ParcelEvent*>(obj.get());

            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver == NULL) {
                break;
            }

            int posMs;
            int64_t timeUs, posUs;
            driver->getCurrentPosition(&posMs);
            posUs = posMs * 1000;
            CHECK((msg->findInt64("timeUs", &timeUs)));

            if (posUs < timeUs) {
                if (!msg->findInt32("generation", &generation)) {
                    msg->setInt32("generation", mTimedTextGeneration);
                }
                msg->post(timeUs - posUs);
            } else {
                int32_t SeqNum = 0;
                if (msg->findInt32("seqNum", &SeqNum)) {
                    if (SeqNum < mhandledSubtitleSeqNum)
                    {
                        ALOGE("we're late,seq = %d,mhandledSubtitleSeqNum = %d",SeqNum,mhandledSubtitleSeqNum);
                        break;
                    }
                    mhandledSubtitleSeqNum = SeqNum;
                    ALOGV("seq = %d,mhandledSubtitleSeqNum = %d",SeqNum,mhandledSubtitleSeqNum);

                }
                if ((parcelEvent->parcel.dataSize() > 0)) {
                    notifyListener(MEDIA_TIMED_TEXT, 0, 0, &parcelEvent->parcel);
                    ALOGD("post sub to app,timeus:%lld,sub-v:%lld",(long long)timeUs,timeUs-posUs);
                } else {  // send an empty timed text
                    notifyListener(MEDIA_TIMED_TEXT, 0, 0);
                    ALOGD("post sub clear to app,timeus:%lld,sub-v:%lld",(long long)timeUs,timeUs-posUs);
                }
            }
            break;
        }
#endif
        case Source::kWhatBufferNotify:
        case Source::kWhatSeekDone:
        case NuPlayer::Source::kWhatPlayDone:
        case NuPlayer::Source::kWhatPauseDone:
        case NuPlayer::Source::kWhatPicture:// orange compliance
             onSourceNotify_l(msg);
        break;
      default:
        return false;
        }
    return true;
}

void NuPlayer::updataVideoSize_ext(const sp<AMessage> &outputFormat,int32_t *displayWidth,int32_t *displayHeight){
    int32_t width, height;
        CHECK(outputFormat->findInt32("width", &width));
        CHECK(outputFormat->findInt32("height", &height));
#ifdef MTK_CLEARMOTION_SUPPORT
        int32_t NotUpdateVideoSize = 0;
        outputFormat->findInt32("NotUpdateVideoSize", &NotUpdateVideoSize);

        if (NotUpdateVideoSize > 0)
        {
            if (m_i4ContainerWidth > 0 && m_i4ContainerHeight > 0)
            {
                *displayWidth = m_i4ContainerWidth;
                *displayHeight = m_i4ContainerHeight;

            ALOGD("Video output format changed to %d x %d "
                 "force set (%d, %d))",
                 width, height,
                 *displayWidth,
                * displayHeight);
            }
            else
            {
                // Can't get video size info from extractor. Try to use decoder's output info.
                int32_t cropLeft, cropTop, cropRight, cropBottom;
                CHECK(outputFormat->findRect(
                            "crop",
                            &cropLeft, &cropTop, &cropRight, &cropBottom));

                *displayWidth = cropRight - cropLeft + 1;
                *displayHeight = cropBottom - cropTop + 1;

                ALOGD("Video output format changed to %d x %d "
                     "(crop: %d x %d @ (%d, %d))",
                     width, height,
                     *displayWidth,
                     *displayHeight,
                     cropLeft, cropTop);
            }
        }
        else
        {
            // Use decoder's output info.
            int32_t cropLeft, cropTop, cropRight, cropBottom;
            CHECK(outputFormat->findRect(
                        "crop",
                        &cropLeft, &cropTop, &cropRight, &cropBottom));

            *displayWidth = cropRight - cropLeft + 1;
            *displayHeight = cropBottom - cropTop + 1;

            ALOGI("Video output format changed to %d x %d "
                 "(crop: %d x %d @ (%d, %d))",
                 width, height,
                 *displayWidth,
                 *displayHeight,
                 cropLeft, cropTop);
        }

#else
        // Use decoder's output info.
        int32_t cropLeft, cropTop, cropRight, cropBottom;
        CHECK(outputFormat->findRect(
                    "crop",
                    &cropLeft, &cropTop, &cropRight, &cropBottom));

        *displayWidth = cropRight - cropLeft + 1;
        *displayHeight = cropBottom - cropTop + 1;

        ALOGI("Video output format changed to %d x %d "
             "(crop: %d x %d @ (%d, %d))",
             width, height,
             *displayWidth,
             *displayHeight,
             cropLeft, cropTop);

#endif // CLEARMOTION


}

status_t NuPlayer::setDataSourceAsync_proCheck(sp<AMessage> &msg,sp<AMessage> &notify __unused) {

    mDataSourceType = SOURCE_Local;
    sp<RefBase> obj;
       CHECK(msg->findObject("source", &obj));
    sp<Source> source = static_cast<Source *>(obj.get());

    status_t err = source->initCheck();
    if(err != OK){
        notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
        ALOGW("setDataSource source init check fail err=%d",err);
        source = NULL;
        msg->setObject("source", source);
        msg->setInt32("result", err);
        msg->post();
        return err;
    }
    return OK;
}
bool NuPlayer::tyrToChangeDataSourceForLocalSdp() {

    sp<AMessage> format = mSource->getFormat(false);

    if(format.get()){
        AString newUrl;
        sp<RefBase> sdp;
        if(format->findString("rtsp-uri", &newUrl) &&
            format->findObject("rtsp-sdp", &sdp)) {
            //is sdp--need re-setDataSource
                mSource.clear();
               sp<AMessage> notify = new AMessage(kWhatSourceNotify, this);
            mSource = new RTSPSource(notify,newUrl.c_str(), NULL, mUIDValid, mUID);
            static_cast<RTSPSource *>(mSource.get())->setSDP(sdp);
            ALOGI("replace local sourceto be RTSPSource");
            return true;
        }
    }
    return false;
}

bool NuPlayer::isPausing() {
    bool pausing = false;
    ALOGD("kWhatPause, %d", (int32_t)mPlayState);
    if (mPlayState == STOPPED || mPlayState == PAUSED/* || mPlayState == PLAYSENDING*/) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)){
            notifyListener(MEDIA_PAUSE_COMPLETE, INVALID_OPERATION, 0);
        }
        pausing = true;
    }
    if (mPlayState == PAUSING) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) {
            notifyListener(MEDIA_PAUSE_COMPLETE, ALREADY_EXISTS, 0);
        }
        pausing = true;
    }

    return pausing;
}

bool NuPlayer::onResume_l() {
    bool resuming = false;
    ALOGD("kWhatResume, %d", (int32_t)mPlayState);

    if (mPlayState == PLAYING/* || mPlayState == PAUSING*/) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) {
            notifyListener(MEDIA_PLAY_COMPLETE, INVALID_OPERATION, 0);
        }
        resuming = true;
    }
    if (mPlayState == PLAYSENDING) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) {
            notifyListener(MEDIA_PLAY_COMPLETE, ALREADY_EXISTS, 0);
        }
        resuming = true;
    }

    return resuming;
}


void NuPlayer::handleForACodecError(bool audio,const sp<AMessage> &codecRequest) {
#if 0
    if (!(IsFlushingState(audio ? mFlushingAudio : mFlushingVideo))) {
        ALOGE("Received error from %s decoder.",audio ? "audio" : "video");
            int32_t err;
            CHECK(codecRequest->findInt32("err", &err));
         notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
    } else {
        ALOGD("Ignore error from %s decoder when flushing", audio ? "audio" : "video");
    }
#endif
    int32_t err;
    CHECK(codecRequest->findInt32("err", &err));

    bool isACodecErr = false;
    if (codecRequest->findInt32("errACodec", &err)) {
        isACodecErr = true;
    }
    if (err == ERROR_BAD_FRAME_SIZE) {
        notifyListener(MEDIA_ERROR, MEDIA_ERROR_BAD_FILE, 0);
    } else if (isACodecErr) {         // should only handle ACodec error
        if (mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http){
            if (!mIsMtkPlayback) {
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
            } else {
                if (!audio) {
                // ALPS01889948 timing issue, should notify noce
                // or else would notify frequently, casue binder abnormal
                    if (!mVideoinfoNotify) {
                        if (mSource->getFormat(true) != NULL) {
                            if (false == mAudioinfoNotify) {
                                notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
                                notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_VIDEO, 0);
                            } else {
                                notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                            }
                        } else {
                              notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                        }
                        mVideoinfoNotify = true;
                    }
                } else {
                    // ALPS01889948 timing issue, should notify noce
                    // or else would notify frequently, casue binder abnormal
                    if (!mAudioinfoNotify) {
                        if (mVideoDecoder != NULL) {
                            if (false == mVideoinfoNotify) {
                                notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_AUDIO, 0);
                            } else {
                                notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                            }
                        } else {
                            notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                        }
                        mAudioinfoNotify = true;
                    }
                }
            }
        } else {
            if(!audio) notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
            notifyListener(MEDIA_INFO, audio ? MEDIA_INFO_HAS_UNSUPPORT_AUDIO : MEDIA_INFO_HAS_UNSUPPORT_VIDEO, 0);
        }
    } else {
          notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
    }
}



void NuPlayer::handleForRenderError1(int32_t finalResult,int32_t audio) {


    if (mSource != NULL) {
        mSource->stopTrack(audio);
    }


    if (finalResult == ERROR_BAD_FRAME_SIZE) {
        return;
    }
    // mtk80902: ALPS00436989
    if (audio) {
        notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_AUDIO, finalResult);
    } else {
        notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
        notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_VIDEO, finalResult);
    }
}

void NuPlayer::finishSeek() {
    ALOGD("finishSeek mPlayState:%d", mPlayState);
    if (mRenderer != NULL) {
        if (mPlayState == PLAYING || mPlayState == PLAYSENDING) {
            mRenderer->resume();
        }
    }
}

sp<MetaData> NuPlayer::getMetaData() const {
    if(mSource != NULL && mSource.get() != NULL)
        return mSource->getMetaData();
    else
        return NULL;
}



bool NuPlayer::onScanSources() {
    ALOGE("onScanSources");
    bool mHaveSanSources __unused = true; // for ALPS02318704; when audio decoder config error, suspend/resume video can not play.
    bool needScanAgain = false;
    bool hadAnySourcesBefore =
        (mAudioDecoder != NULL) || (mVideoDecoder != NULL);

    // mtk80902: ALPS01413054
    // get format first, then init decoder
    if (mDataSourceType == SOURCE_HttpLive) {
       // needScanAgain = (mSource->allTracksPresent() != OK);
       // if(needScanAgain)
       //     return true;
    }
    // mtk80902: for rtsp, if instantiateDecoder return EWOULDBLK
    // it means no track. no need to try again.
    status_t videoFmt = OK, audioFmt = OK;
    if (mSurface != NULL) {
#ifdef MTK_CLEARMOTION_SUPPORT
        if (mEnClearMotion) {
            sp<ANativeWindow> window = mSurface;
            if (window != NULL) {
                window->setSwapInterval(window.get(), 1);
            }
        }
#endif
        videoFmt = instantiateDecoder(false, &mVideoDecoder);
    }

    if (mAudioSink != NULL) {
        audioFmt = instantiateDecoder(true, &mAudioDecoder);
    }

    if (!hadAnySourcesBefore
            && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
        // This is the first time we've found anything playable.

        if (mSourceFlags & Source::FLAG_DYNAMIC_DURATION) {
            schedulePollDuration();
        }
    }

    if (isRTSPSource()) {
        ALOGD("audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
        mAudioSink.get(), mAudioDecoder.get(), mSurface.get(), mVideoDecoder.get());
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
            || ((mSurface != NULL) && (mVideoDecoder == NULL));
    } else if (mDataSourceType == SOURCE_HttpLive) {
        ALOGD("audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
        mAudioSink.get(), mAudioDecoder.get(), mSurface.get(), mVideoDecoder.get());
        /*
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
                      || ((mSurface != NULL) && (mVideoDecoder == NULL));
        */
    } else {
        ALOGD("Local audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
                mAudioSink.get(), mAudioDecoder.get(), mSurface.get(), mVideoDecoder.get());
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
            || ((mSurface != NULL) && (mVideoDecoder == NULL));
    }

    status_t err;
    if ((err = mSource->feedMoreTSData()) != OK) {
        if (mAudioDecoder == NULL && mVideoDecoder == NULL) {
            // We're not currently decoding anything (no audio or
            // video tracks found) and we just ran out of input data.

            if (err == ERROR_END_OF_STREAM) {
                notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
            } else {
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
            }
        }
        return false;
    }

    return needScanAgain;
}

void NuPlayer::scanSource_l(const sp<AMessage> &msg) {
    bool needScanAgain = onScanSources();
    //TODO: to handle audio only file, finisPrepare should be sent
    if (needScanAgain) {     //scanning source is not completed, continue
        msg->post(100000ll);
        mScanSourcesPending = true;
    } else {
        if(SOURCE_HttpLive == mDataSourceType) {//decoder may not shutdown after audio/video->audio only stream,can RTSP use the format way?!
            sp<AMessage> audioFormat = mSource->getFormat(true);
            sp<AMessage> videoFormat = mSource->getFormat(false);
            mAudioOnly = videoFormat == NULL;
            mVideoOnly = audioFormat == NULL;
            ALOGD("scanning sources done! Audio only=%d, Video only=%d",mAudioOnly,mVideoOnly);
            if (mAudioOnly) {
                notifyListener(MEDIA_SET_VIDEO_SIZE, 0,0);
            }

            if ((videoFormat == NULL) && (audioFormat == NULL)) {
                ALOGD("notify error to AP when there is no audio and video!");
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, 0);
            }
        } else {
            if (mIsMtkPlayback && mVideoDecoder == NULL) {
                notifyListener(MEDIA_SET_VIDEO_SIZE, 0,0);
            }

            if ((mVideoDecoder == NULL) && (mAudioDecoder == NULL)) {
                ALOGD("notify error to AP when there is no audio and video!");
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, 0);
            }
        }
    }
}

void NuPlayer::finishPrepare(int err /*= OK*/) {
    mPrepare = (err == OK)?PREPARED:UNPREPARED;
    if (mDriver == NULL)
        return;
    sp<NuPlayerDriver> driver = mDriver.promote();
    if (driver != NULL) {
        int64_t durationUs;
        if (mSource != NULL && mSource->getDuration(&durationUs) == OK) {
            driver->notifyDuration(durationUs);
        }
        driver->notifyPrepareCompleted(err);
        //if (isRTSPSource() && err == OK) {
        //    notifyListener(MEDIA_INFO, MEDIA_INFO_CHECK_LIVE_STREAMING_COMPLETE, 0);
        //}
        ALOGD("complete prepare %s", (err == OK)?"success":"fail");

        ATRACE_ASYNC_END("Prepare",mPlayerCnt);

        sp<MetaData> fileMeta = mSource->getFileFormatMeta();
      int32_t hasUnsupportVideo = 0;
        if (fileMeta != NULL && fileMeta->findInt32(kKeyHasUnsupportVideo, &hasUnsupportVideo)
                && hasUnsupportVideo != 0) {
            notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
            notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_VIDEO, 0);
            ALOGD("Notify APP that file has kKeyHasUnsupportVideo");
        }
    }
}

void NuPlayer::finishFlushIfPossible_l() {
    Mutex::Autolock autoLock(mLock);
    // http Streaming async seek, source seek done would be late
    if ((mDataSourceType != SOURCE_Http) && isSeeking()) {
           finishSeek();
    }
}

void NuPlayer::reviseNotifyErrorCode(int msg,int *ext1,int *ext2) {
    if (mIsMtkPlayback && mSource != NULL && ((mDataSourceType == SOURCE_Http) && (msg == MEDIA_ERROR || msg == MEDIA_PLAY_COMPLETE ||
        *ext1 == MEDIA_INFO_HAS_UNSUPPORT_AUDIO || *ext1 == MEDIA_INFO_HAS_UNSUPPORT_VIDEO))) {
        status_t cache_stat = mSource->getFinalStatus();
        bool bCacheSuccess = (cache_stat == OK || cache_stat == ERROR_END_OF_STREAM);

        if (!bCacheSuccess) {
            ALOGI(" http error");
            if (cache_stat == -ECANCELED) {
                ALOGD("this error triggered by user's stopping, would not report");
                return;
            } else if (cache_stat == ERROR_FORBIDDEN) {
                *ext1 = MEDIA_ERROR_INVALID_CONNECTION;//httpstatus = 403
            } else if (cache_stat == ERROR_POOR_INTERLACE) {
                *ext1 = MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK;
            } else {
                *ext1 = MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER;
            }
            *ext2 = cache_stat;
            ALOGE("report 'cannot connect' to app, cache_stat = %d", cache_stat);
            if (MEDIA_PLAY_COMPLETE == msg) {
                ALOGD("Http Error and end of stream");
                msg = MEDIA_ERROR;
            }
        }
    }

    //try to report a more meaningful error
    if (msg == MEDIA_ERROR && *ext1 == MEDIA_ERROR_UNKNOWN) {
        switch(*ext2) {
            case ERROR_MALFORMED:
                *ext1 = MEDIA_ERROR_BAD_FILE;
                break;
            case ERROR_CANNOT_CONNECT:
                *ext1 = MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER;
                break;
            case ERROR_UNSUPPORTED:
                *ext1 = MEDIA_ERROR_TYPE_NOT_SUPPORTED;
                break;
            case ERROR_FORBIDDEN:
                *ext1 = MEDIA_ERROR_INVALID_CONNECTION;
                break;
            default:
                break;
        }
    }
}

void NuPlayer::performSeek_l(int64_t seekTimeUs) {

    CHECK(seekTimeUs != -1);
    Mutex::Autolock autoLock(mLock);

    mAudioEOS = false;
    mVideoEOS = false;
    ALOGI("reset EOS flag");

#ifdef MTK_SUBTITLE_SUPPORT
    notifyListener(MEDIA_TIMED_TEXT, 0, 0);
#endif
    mPreviousSeekTimeUs = seekTimeUs;
    status_t err = mSource->seekTo(seekTimeUs);
#ifdef MTK_AOSP_ENHANCEMENT
    mSourceSeekDone = false;
#endif
    if (err == -EWOULDBLOCK) {  // finish seek when receive Source::kWhatSeekDone
        ALOGD("seek async, waiting Source seek done mSeekWouldBlock is set to true");
    } else if (err != OK) {
        ALOGE("seek error %d", (int)err);
        // add notify seek complete
        finishSeek();
    }
}

void NuPlayer::onSourcePrepard(int32_t err) {
    //if file is rtsp local sdp file, check file uses GenericSource, here check source, need to change to RTSPSource
    if((mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)
        && tyrToChangeDataSourceForLocalSdp()){
        mPrepare = UNPREPARED;
        ALOGI("to do prepare again and change mDataSourceType");
        mDataSourceType = SOURCE_Rtsp;
        prepareAsync();
        return;
   }


    if (mPrepare == PREPARED) //TODO: this would would happen when MyHandler disconnect
        return;
    if (err != OK) {
        finishPrepare(err);
        return;
    } else if (mSource == NULL) {  // ALPS00779817
        ALOGW("prepare error: source is not ready");
        finishPrepare(UNKNOWN_ERROR);
        return;
    }
    // if data source is streamingsource or local, the scan will be started in kWhatStart
    finishPrepare();
}

void NuPlayer::onSourceNotify_l(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));
    if(what == Source::kWhatBufferNotify) {
        int32_t rate;
        CHECK(msg->findInt32("bufRate", &rate));
      if(rate % 10 == 0){
         ALOGD("mFlags %d; mPlayState %d, buffering rate %d",mFlags, mPlayState, rate);
      }
        if (mPlayState == PLAYING ) {
            notifyListener(MEDIA_BUFFERING_UPDATE, rate, 0);
        }
    }
    else if(what == Source::kWhatSeekDone) {
#ifdef MTK_AOSP_ENHANCEMENT
        if (mDataSourceType == SOURCE_Http) {
            mSourceSeekDone = true;
            if (!mResumePending) {
                if (mDriver != NULL) {
                    sp<NuPlayerDriver> driver = mDriver.promote();
                    if (driver != NULL) {
                        driver->notifySeekComplete();
                    }
                }
                // finish seek, let audio resume
                finishSeek();
                mSeekTimeUs = -1;
            }
        }
#endif
    }
    else if( what == NuPlayer::Source::kWhatPlayDone) {
        int32_t ret;
        CHECK(msg->findInt32("result", &ret));
        ALOGI("play done with result %d.", ret);
        notifyListener(MEDIA_PLAY_COMPLETE, ret, 0);
        mPlayState = PLAYING;
        // mtk80902: ALPS00439792
        // special case: pause -> seek -> resume ->
        //  seek complete -> resume complete
        // in this case render cant resume in SeekDone
        if (mRenderer != NULL)
            mRenderer->resume();
    }
    else if(what == NuPlayer::Source::kWhatPauseDone) {
        int32_t ret;
        CHECK(msg->findInt32("result", &ret));
        notifyListener(MEDIA_PAUSE_COMPLETE, ret, 0);
        // ALPS00567579 - an extra pause done?
        if (mPlayState != PAUSING) {
            ALOGW("what's up? an extra pause done?");
            return;
        }
        if (ret == OK){
              mPlayState = PAUSED;
     }
    }
    else if(what == NuPlayer::Source::kWhatPicture) {
        // audio-only stream containing picture for display
        ALOGI("Notify picture existence");
        notifyListener(MEDIA_INFO, MEDIA_INFO_METADATA_UPDATE, 0);
    }
}



// static
bool NuPlayer::IsFlushingState(FlushStatus state) {
    switch (state) {
        case FLUSHING_DECODER:
            return true;

        case FLUSHING_DECODER_SHUTDOWN:
        case SHUTTING_DOWN_DECODER:
            return true;

        default:
            return false;
    }
}

bool NuPlayer::isSeeking() {
    return (mSeekTimeUs != -1);
}

bool NuPlayer::isRTSPSource() {
    if (mDataSourceType == (DataSourceType)NuPlayer::Source::SOURCE_Default && mSource != NULL) {
        mDataSourceType = (DataSourceType)mSource->getDataSourceType();
    }

    //ALOGD("jzm, isRTSPSource datatype %d", mDataSourceType);
    return NuPlayer::Source::SOURCE_Rtsp == (NuPlayer::Source::DataSourceType)mDataSourceType;
}
bool NuPlayer::isHttpLiveSource() {
    if (mDataSourceType == (DataSourceType)NuPlayer::Source::SOURCE_Default && mSource != NULL) {
        mDataSourceType = (DataSourceType)mSource->getDataSourceType();
    }
    ALOGD("rock, isHttpLiveSource datatype %d", mDataSourceType);
    return NuPlayer::Source::SOURCE_HttpLive == (NuPlayer::Source::DataSourceType)mDataSourceType;
}

#ifdef MTK_DRM_APP
void NuPlayer::getDRMClientProc(const Parcel *request) {
    if ((mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http) && mSource != NULL) {
        (static_cast<GenericSource *>(mSource.get()))->getDRMClientProc(request);
    }
}
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
status_t NuPlayer::setsmspeed(int32_t speed){
    mslowmotion_speed = speed;
    if(mVideoDecoder != NULL){
        sp<AMessage> msg = new AMessage;
        msg->setInt32("slowmotion-speed", speed);
        mVideoDecoder->setParameters(msg);
    }else{
        ALOGW("mVideoDecoder == NULL");
    }
    if(mRenderer != NULL){
        return mRenderer->setsmspeed(speed);
    }else{
        ALOGW("mRenderer = NULL");
        return NO_INIT;
    }
}
status_t NuPlayer::setslowmotionsection(int64_t slowmotion_start,int64_t slowmotion_end){
    mslowmotion_start = slowmotion_start;
    mslowmotion_end = slowmotion_end;
    if(mVideoDecoder != NULL){
        sp<AMessage> msg = new AMessage;
        msg->setInt64("slowmotion-start", slowmotion_start);
        msg->setInt64("slowmotion-end", slowmotion_end);
        msg->setInt32("slowmotion-speed", mslowmotion_speed);
        mVideoDecoder->setParameters(msg);
        return OK;
    }else {
        ALOGW("mVideoDecoder = NULL");
        return NO_INIT;
    }
}


sp<MetaData> NuPlayer::getFormatMeta(bool audio)const {
    if(mSource != NULL){
        return mSource->getFormatMeta(audio);
    }else{
        return NULL;
    }
}


#endif

#ifdef MTK_CLEARMOTION_SUPPORT
void NuPlayer::enableClearMotion(int32_t enable) {
    mEnClearMotion = enable;
}
#endif

void NuPlayer::setIsMtkPlayback(bool setting) {
    ALOGI("Is Mtk playback:%d", setting);
    mIsMtkPlayback = setting;
}


#endif

}  // namespace android
