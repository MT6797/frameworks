/*
 * Copyright 2014 The Android Open Source Project
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
#define LOG_TAG "NuPlayerDecoder"
#include <utils/Log.h>
#include <inttypes.h>

#include "NuPlayerCCDecoder.h"
#include "NuPlayerDecoder.h"
#include "NuPlayerRenderer.h"
#include "NuPlayerSource.h"

#include <cutils/properties.h>
#include <media/ICrypto.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>

#include <gui/Surface.h>

#include "avc_utils.h"
#include "ATSParser.h"

#include <media/MtkMMLog.h>
namespace android {

static inline bool getAudioDeepBufferSetting() {
    return property_get_bool("media.stagefright.audio.deep", false /* default_value */);
}

NuPlayer::Decoder::Decoder(
        const sp<AMessage> &notify,
        const sp<Source> &source,
        pid_t pid,
        const sp<Renderer> &renderer,
        const sp<Surface> &surface,
        const sp<CCDecoder> &ccDecoder)
    : DecoderBase(notify),
      mSurface(surface),
      mSource(source),
      mRenderer(renderer),
      mCCDecoder(ccDecoder),
      mPid(pid),
      mSkipRenderingUntilMediaTimeUs(-1ll),
      mNumFramesTotal(0ll),
      mNumInputFramesDropped(0ll),
      mNumOutputFramesDropped(0ll),
      mVideoWidth(0),
      mVideoHeight(0),
      mIsAudio(true),
      mIsVideoAVC(false),
      mIsSecure(false),
      mFormatChangePending(false),
      mTimeChangePending(false),
      mPaused(true),
      mResumePending(false),
      mComponentName("decoder") {
    mCodecLooper = new ALooper;
    mCodecLooper->setName("NPDecoder-CL");
    mCodecLooper->start(false, false, ANDROID_PRIORITY_AUDIO);
#ifdef MTK_AOSP_ENHANCEMENT
    mLeftOverBuffer = NULL;
    mSupportsPartialFrames = false;
    mIsSMPL = false;
#endif
}

NuPlayer::Decoder::~Decoder() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mCodec != NULL)
#endif
    mCodec->release();
    releaseAndResetMediaBuffers();
}

sp<AMessage> NuPlayer::Decoder::getStats() const {
    mStats->setInt64("frames-total", mNumFramesTotal);
    mStats->setInt64("frames-dropped-input", mNumInputFramesDropped);
    mStats->setInt64("frames-dropped-output", mNumOutputFramesDropped);
    return mStats;
}

status_t NuPlayer::Decoder::setVideoSurface(const sp<Surface> &surface) {
    if (surface == NULL || ADebug::isExperimentEnabled("legacy-setsurface")) {
        return BAD_VALUE;
    }

    sp<AMessage> msg = new AMessage(kWhatSetVideoSurface, this);

    msg->setObject("surface", surface);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

void NuPlayer::Decoder::onMessageReceived(const sp<AMessage> &msg) {
    ALOGV("[%s] onMessage: %s", mComponentName.c_str(), msg->debugString().c_str());

    switch (msg->what()) {
        case kWhatCodecNotify:
        {
            int32_t cbID;
            CHECK(msg->findInt32("callbackID", &cbID));

            ALOGV("[%s] kWhatCodecNotify: cbID = %d, paused = %d",
                    mIsAudio ? "audio" : "video", cbID, mPaused);
#ifdef MTK_AOSP_ENHANCEMENT
            /// ALPS02455341 (The audio is not support. But it is found unsupport at Execurting State)
            /// start() -> flush()
            /// Flush() will set mPaused true. When Resume() will set to false.
            /// ACodec-Error maybe return between Flush() and Resume(). (mPause == true)
            /// ACodec-Error maybe return after Resume(). (mPause = false)
            /// Solution:
            if (mPaused) {
                ALOGD("[%s] kWhatCodecNotify: cbID = %d, paused = %d",
                        mIsAudio ? "audio" : "video", cbID, mPaused);
                if (cbID != MediaCodec::CB_ERROR &&
                        cbID != MediaCodec::CB_OUTPUT_FORMAT_CHANGED) {
                    break;
                }
            }
#else
            if (mPaused) {
                break;
            }
#endif
            switch (cbID) {
                case MediaCodec::CB_INPUT_AVAILABLE:
                {
                    int32_t index;
                    CHECK(msg->findInt32("index", &index));

                    handleAnInputBuffer(index);
                    break;
                }

                case MediaCodec::CB_OUTPUT_AVAILABLE:
                {
                    int32_t index;
                    size_t offset;
                    size_t size;
                    int64_t timeUs;
                    int32_t flags;

                    CHECK(msg->findInt32("index", &index));
                    CHECK(msg->findSize("offset", &offset));
                    CHECK(msg->findSize("size", &size));
                    CHECK(msg->findInt64("timeUs", &timeUs));
                    CHECK(msg->findInt32("flags", &flags));

                    handleAnOutputBuffer(index, offset, size, timeUs, flags);
                    break;
                }

                case MediaCodec::CB_OUTPUT_FORMAT_CHANGED:
                {
                    sp<AMessage> format;
                    CHECK(msg->findMessage("format", &format));

                    handleOutputFormatChange(format);
                    break;
                }

                case MediaCodec::CB_ERROR:
                {
                    status_t err;
                    CHECK(msg->findInt32("err", &err));
#ifdef MTK_AOSP_ENHANCEMENT
                    ALOGE("Decoder (%s) reported error : 0x%x, paused=%d",
                            mIsAudio ? "audio" : "video", err, mPaused);
                    handleError(err, true);
#else
                    ALOGE("Decoder (%s) reported error : 0x%x",
                            mIsAudio ? "audio" : "video", err);
                    handleError(err);
#endif
                    break;
                }

                default:
                {
                    TRESPASS();
                    break;
                }
            }

            break;
        }

        case kWhatRenderBuffer:
        {
            if (!isStaleReply(msg)) {
                onRenderBuffer(msg);
            }
            break;
        }

        case kWhatSetVideoSurface:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            sp<RefBase> obj;
            CHECK(msg->findObject("surface", &obj));
            sp<Surface> surface = static_cast<Surface *>(obj.get()); // non-null
            int32_t err = INVALID_OPERATION;
            // NOTE: in practice mSurface is always non-null, but checking here for completeness
            if (mCodec != NULL && mSurface != NULL) {
                // TODO: once AwesomePlayer is removed, remove this automatic connecting
                // to the surface by MediaPlayerService.
                //
                // at this point MediaPlayerService::client has already connected to the
                // surface, which MediaCodec does not expect
                err = native_window_api_disconnect(surface.get(), NATIVE_WINDOW_API_MEDIA);
                if (err == OK) {
                    err = mCodec->setSurface(surface);
                    ALOGI_IF(err, "codec setSurface returned: %d", err);
                    if (err == OK) {
                        // reconnect to the old surface as MPS::Client will expect to
                        // be able to disconnect from it.
                        (void)native_window_api_connect(mSurface.get(), NATIVE_WINDOW_API_MEDIA);
                        mSurface = surface;
                    }
                }
                if (err != OK) {
                    // reconnect to the new surface on error as MPS::Client will expect to
                    // be able to disconnect from it.
                    (void)native_window_api_connect(surface.get(), NATIVE_WINDOW_API_MEDIA);
                }
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        default:
            DecoderBase::onMessageReceived(msg);
            break;
    }
}

void NuPlayer::Decoder::onConfigure(const sp<AMessage> &format) {
    CHECK(mCodec == NULL);

    mFormatChangePending = false;
    mTimeChangePending = false;

    ++mBufferGeneration;

    AString mime;
    CHECK(format->findString("mime", &mime));

    mIsAudio = !strncasecmp("audio/", mime.c_str(), 6);
    mIsVideoAVC = !strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime.c_str());

    mComponentName = mime;
    mComponentName.append(" decoder");
    ALOGV("[%s] onConfigure (surface=%p)", mComponentName.c_str(), mSurface.get());

    mCodec = MediaCodec::CreateByType(
            mCodecLooper, mime.c_str(), false /* encoder */, NULL /* err */, mPid);
    int32_t secure = 0;
    if (format->findInt32("secure", &secure) && secure != 0) {
        if (mCodec != NULL) {
            mCodec->getName(&mComponentName);
            mComponentName.append(".secure");
            mCodec->release();
            ALOGI("[%s] creating", mComponentName.c_str());
            mCodec = MediaCodec::CreateByComponentName(
                    mCodecLooper, mComponentName.c_str(), NULL /* err */, mPid);
        }
    }
    if (mCodec == NULL) {
        ALOGE("Failed to create %s%s decoder",
                (secure ? "secure " : ""), mime.c_str());
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(UNKNOWN_ERROR, true);
#else
        handleError(UNKNOWN_ERROR);
#endif
        return;
    }
    mIsSecure = secure;

    mCodec->getName(&mComponentName);

    status_t err;
    if (mSurface != NULL) {
        // disconnect from surface as MediaCodec will reconnect
        err = native_window_api_disconnect(
                mSurface.get(), NATIVE_WINDOW_API_MEDIA);
        // We treat this as a warning, as this is a preparatory step.
        // Codec will try to connect to the surface, which is where
        // any error signaling will occur.
        ALOGW_IF(err != OK, "failed to disconnect from surface: %d", err);
    }
#ifdef MTK_16X_SLOWMOTION_VIDEO_SUPPORT
    int64_t slowmotion_start = -1;
    int64_t slowmotion_end = -1;
    int32_t slowmotion_speed = -1;
    if (format->findInt64("slowmotion-start", &slowmotion_start) &&
        format->findInt64("slowmotion-end", &slowmotion_end) &&
        format->findInt32("slowmotion-speed", &slowmotion_speed)) {
        err = mCodec->configure(format, mSurface, NULL, MediaCodec::CONFIGURE_FLAG_16X_SLOWMOTION);
    }else{
        err = mCodec->configure(format, mSurface, NULL /* crypto */, 0 /* flags */);
    }
#else
    err = mCodec->configure(
            format, mSurface, NULL /* crypto */, 0 /* flags */);
#endif
    if (err != OK) {
        ALOGE("Failed to configure %s decoder (err=%d)", mComponentName.c_str(), err);
        mCodec->release();
        mCodec.clear();
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(err, true);
#else
        handleError(err);
#endif
        return;
    }
    rememberCodecSpecificData(format);

    // the following should work in configured state
    CHECK_EQ((status_t)OK, mCodec->getOutputFormat(&mOutputFormat));
    CHECK_EQ((status_t)OK, mCodec->getInputFormat(&mInputFormat));

    mStats->setString("mime", mime.c_str());
    mStats->setString("component-name", mComponentName.c_str());

    if (!mIsAudio) {
        int32_t width, height;
        if (mOutputFormat->findInt32("width", &width)
                && mOutputFormat->findInt32("height", &height)) {
            mStats->setInt32("width", width);
            mStats->setInt32("height", height);
        }
    }

    sp<AMessage> reply = new AMessage(kWhatCodecNotify, this);
    mCodec->setCallback(reply);

#ifdef MTK_AOSP_ENHANCEMENT
    int32_t partialFramesSupport = false;
    if ((mInputFormat->findInt32("support-partial-frame", &partialFramesSupport))
        && partialFramesSupport) {
            mSupportsPartialFrames = true;
    }
    ALOGI("mSupportsPartialFrames %d ", mSupportsPartialFrames);
#endif
    err = mCodec->start();
    if (err != OK) {
        ALOGE("Failed to start %s decoder (err=%d)", mComponentName.c_str(), err);
        mCodec->release();
        mCodec.clear();
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(err, true);
#else
        handleError(err);
#endif
        return;
    }

#ifdef MTK_16X_SLOWMOTION_VIDEO_SUPPORT
    if ((slowmotion_start != -1) && (slowmotion_end != -1) && (slowmotion_speed != -1)) {
        sp<AMessage> params = new AMessage;
        params->setInt64("slowmotion-start", slowmotion_start);
        params->setInt64("slowmotion-end", slowmotion_end);
        params->setInt32("slowmotion-speed", slowmotion_speed);
        this->setParameters(params);
    }
#endif
    releaseAndResetMediaBuffers();
    mPaused = false;
    mResumePending = false;
}

void NuPlayer::Decoder::onSetParameters(const sp<AMessage> &params) {
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
    int32_t videospeed = -1;
    if(params->findInt32("slowmotion-speed", &videospeed)&&videospeed != -1){
         mIsSMPL= true;
         ALOGD("slowmotion playback,current speed %d",videospeed);
    }
#endif
    if (mCodec == NULL) {
        ALOGW("onSetParameters called before codec is created.");
        return;
    }
    mCodec->setParameters(params);
}

void NuPlayer::Decoder::onSetRenderer(const sp<Renderer> &renderer) {
    bool hadNoRenderer = (mRenderer == NULL);
    mRenderer = renderer;
    if (hadNoRenderer && mRenderer != NULL) {
        // this means that the widevine legacy source is ready
        onRequestInputBuffers();
    }
}

void NuPlayer::Decoder::onGetInputBuffers(
        Vector<sp<ABuffer> > *dstBuffers) {
    CHECK_EQ((status_t)OK, mCodec->getWidevineLegacyBuffers(dstBuffers));
}

void NuPlayer::Decoder::onResume(bool notifyComplete) {
    mPaused = false;

    if (notifyComplete) {
        mResumePending = true;
    }
    mCodec->start();
}

void NuPlayer::Decoder::doFlush(bool notifyComplete) {
    if (mCCDecoder != NULL) {
        mCCDecoder->flush();
    }

    if (mRenderer != NULL) {
        mRenderer->flush(mIsAudio, notifyComplete);
        mRenderer->signalTimeDiscontinuity();
    }

    status_t err = OK;
    if (mCodec != NULL) {
        err = mCodec->flush();
        mCSDsToSubmit = mCSDsForCurrentFormat; // copy operator
        ++mBufferGeneration;
    }

    if (err != OK) {
        ALOGE("failed to flush %s (err=%d)", mComponentName.c_str(), err);
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(err, true);
#else
        handleError(err);
#endif
        // finish with posting kWhatFlushCompleted.
        // we attempt to release the buffers even if flush fails.
    }
    releaseAndResetMediaBuffers();
#ifdef MTK_CLEARMOTION_SUPPORT
    if (mLeftOverBuffer != NULL) {
      ALOGD("flush mLeftOverBuffer");
      mLeftOverBuffer = NULL;
    }
#endif
    mPaused = true;
}

void NuPlayer::Decoder::onFlush() {
    doFlush(true);

    if (isDiscontinuityPending()) {
        // This could happen if the client starts seeking/shutdown
        // after we queued an EOS for discontinuities.
        // We can consider discontinuity handled.
        finishHandleDiscontinuity(false /* flushOnTimeChange */);
    }

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatFlushCompleted);
    notify->post();
}

void NuPlayer::Decoder::onShutdown(bool notifyComplete) {
    status_t err = OK;

    // if there is a pending resume request, notify complete now
    notifyResumeCompleteIfNecessary();

    if (mCodec != NULL) {
        err = mCodec->release();
        mCodec = NULL;
        ++mBufferGeneration;

        if (mSurface != NULL) {
            // reconnect to surface as MediaCodec disconnected from it
            status_t error =
                    native_window_api_connect(mSurface.get(), NATIVE_WINDOW_API_MEDIA);
            ALOGW_IF(error != NO_ERROR,
                    "[%s] failed to connect to native window, error=%d",
                    mComponentName.c_str(), error);
        }
        mComponentName = "decoder";
    }

    releaseAndResetMediaBuffers();

    if (err != OK) {
        ALOGE("failed to release %s (err=%d)", mComponentName.c_str(), err);
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(err, true);
#else
        handleError(err);
#endif
        // finish with posting kWhatShutdownCompleted.
    }

    if (notifyComplete) {
        sp<AMessage> notify = mNotify->dup();
        notify->setInt32("what", kWhatShutdownCompleted);
        notify->post();
        mPaused = true;
    }
}

/*
 * returns true if we should request more data
 */
bool NuPlayer::Decoder::doRequestBuffers() {
    // mRenderer is only NULL if we have a legacy widevine source that
    // is not yet ready. In this case we must not fetch input.
    if (isDiscontinuityPending() || mRenderer == NULL) {
        return false;
    }
    status_t err = OK;
    while (err == OK && !mDequeuedInputBuffers.empty()) {
        size_t bufferIx = *mDequeuedInputBuffers.begin();
        sp<AMessage> msg = new AMessage();
        msg->setSize("buffer-ix", bufferIx);
        err = fetchInputData(msg);
        if (err != OK && err != ERROR_END_OF_STREAM) {
            // if EOS, need to queue EOS buffer
            break;
        }
        mDequeuedInputBuffers.erase(mDequeuedInputBuffers.begin());

        if (!mPendingInputMessages.empty()
                || !onInputBufferFetched(msg)) {
            mPendingInputMessages.push_back(msg);
        }
    }

    return err == -EWOULDBLOCK
            && mSource->feedMoreTSData() == OK;
}

void NuPlayer::Decoder::handleError(int32_t err)
{
    // We cannot immediately release the codec due to buffers still outstanding
    // in the renderer.  We signal to the player the error so it can shutdown/release the
    // decoder after flushing and increment the generation to discard unnecessary messages.

    ++mBufferGeneration;

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatError);
    notify->setInt32("err", err);
    notify->post();
}

bool NuPlayer::Decoder::handleAnInputBuffer(size_t index) {
    if (isDiscontinuityPending()) {
        return false;
    }

    sp<ABuffer> buffer;
    mCodec->getInputBuffer(index, &buffer);

    if (buffer == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(UNKNOWN_ERROR, true);
#else
        handleError(UNKNOWN_ERROR);
#endif
        return false;
    }

    if (index >= mInputBuffers.size()) {
        for (size_t i = mInputBuffers.size(); i <= index; ++i) {
            mInputBuffers.add();
            mMediaBuffers.add();
            mInputBufferIsDequeued.add();
            mMediaBuffers.editItemAt(i) = NULL;
            mInputBufferIsDequeued.editItemAt(i) = false;
        }
    }
    mInputBuffers.editItemAt(index) = buffer;

    //CHECK_LT(bufferIx, mInputBuffers.size());

    if (mMediaBuffers[index] != NULL) {
        mMediaBuffers[index]->release();
        mMediaBuffers.editItemAt(index) = NULL;
    }
    mInputBufferIsDequeued.editItemAt(index) = true;

    if (!mCSDsToSubmit.isEmpty()) {
        sp<AMessage> msg = new AMessage();
        msg->setSize("buffer-ix", index);

        sp<ABuffer> buffer = mCSDsToSubmit.itemAt(0);
        ALOGI("[%s] resubmitting CSD", mComponentName.c_str());
        msg->setBuffer("buffer", buffer);
        mCSDsToSubmit.removeAt(0);
        CHECK(onInputBufferFetched(msg));
        return true;
    }

    while (!mPendingInputMessages.empty()) {
        sp<AMessage> msg = *mPendingInputMessages.begin();
        if (!onInputBufferFetched(msg)) {
            break;
        }
        mPendingInputMessages.erase(mPendingInputMessages.begin());
    }

    if (!mInputBufferIsDequeued.editItemAt(index)) {
        return true;
    }

    mDequeuedInputBuffers.push_back(index);

    onRequestInputBuffers();
    return true;
}

bool NuPlayer::Decoder::handleAnOutputBuffer(
        size_t index,
        size_t offset,
        size_t size,
        int64_t timeUs,
        int32_t flags) {
//    CHECK_LT(bufferIx, mOutputBuffers.size());
    sp<ABuffer> buffer;
#ifdef MTK_AOSP_ENHANCEMENT
    status_t res = mCodec->getOutputBuffer(index, &buffer);
    if (res != OK) {
        ALOGE("%s: Failed to get output buffers, res=%d, index=%zu, buffer=%p",
                mComponentName.c_str(), res, index, buffer.get());
        handleError(res, true);
        return false;
    }
#else
    mCodec->getOutputBuffer(index, &buffer);
#endif

    if (index >= mOutputBuffers.size()) {
        for (size_t i = mOutputBuffers.size(); i <= index; ++i) {
            mOutputBuffers.add();
        }
    }

    mOutputBuffers.editItemAt(index) = buffer;

    buffer->setRange(offset, size);
    buffer->meta()->clear();
    buffer->meta()->setInt64("timeUs", timeUs);

    bool eos = flags & MediaCodec::BUFFER_FLAG_EOS;
    // we do not expect CODECCONFIG or SYNCFRAME for decoder
#ifdef MTK_CLEARMOTION_SUPPORT
        if (flags & MediaCodec::BUFFER_FLAG_INTERPOLATE_FRAME) {
                buffer->meta()->setInt32("interpolateframe", 1);
             ALOGD("interl frame");
        }
#endif

    sp<AMessage> reply = new AMessage(kWhatRenderBuffer, this);
    reply->setSize("buffer-ix", index);
    reply->setInt32("generation", mBufferGeneration);

    if (eos) {
        ALOGI("[%s] saw output EOS", mIsAudio ? "audio" : "video");

        buffer->meta()->setInt32("eos", true);
        reply->setInt32("eos", true);
    } else if (mSkipRenderingUntilMediaTimeUs >= 0) {
        if (timeUs < mSkipRenderingUntilMediaTimeUs) {
            ALOGV("[%s] dropping buffer at time %lld as requested.",
                     mComponentName.c_str(), (long long)timeUs);
            MM_LOGD("[%s] dropping buffer at time %lld as requested.",
                     mComponentName.c_str(), (long long)timeUs);
            reply->post();
#ifdef MTK_AOSP_ENHANCEMENT
            if (flags & MediaCodec::BUFFER_FLAG_EOS) {
                mRenderer->queueEOS(mIsAudio, ERROR_END_OF_STREAM);
                mSkipRenderingUntilMediaTimeUs = -1;
            }
#endif
            return true;
        }

#ifdef MTK_AOSP_ENHANCEMENT
        ALOGI("[%s] seek_preroll end, set mSkipRenderingUntilMediaTimeUs = %lld to -1",
              mComponentName.c_str(), mSkipRenderingUntilMediaTimeUs);
#endif
        mSkipRenderingUntilMediaTimeUs = -1;
    }

    mNumFramesTotal += !mIsAudio;

    // wait until 1st frame comes out to signal resume complete
    notifyResumeCompleteIfNecessary();

    if (mRenderer != NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
        if (flags & MediaCodec::BUFFER_FLAG_EOS && size == 0 && timeUs == 0) {
            reply->setInt32("render", 0);
            reply->post();
            ALOGI("[%s] EOS ,timeUs:%lld ,size 0",
                    mComponentName.c_str(), timeUs);
        } else {
            // send the buffer to renderer.
            mRenderer->queueBuffer(mIsAudio, buffer, reply);
            MM_LOGI("[%s] queueBuffer(%s, %lld)",
                    mComponentName.c_str(), mIsAudio ? "audio" : "video", (long long)timeUs);
        }
#else
        // send the buffer to renderer.
        mRenderer->queueBuffer(mIsAudio, buffer, reply);
#endif
        if (eos && !isDiscontinuityPending()) {
            MM_LOGI("[%s] EOS ,timeUs:%lld",
                    mComponentName.c_str(), timeUs);
            mRenderer->queueEOS(mIsAudio, ERROR_END_OF_STREAM);
        }
    }

    return true;
}

void NuPlayer::Decoder::handleOutputFormatChange(const sp<AMessage> &format) {
    if (!mIsAudio) {
        int32_t width, height;
        if (format->findInt32("width", &width)
                && format->findInt32("height", &height)) {
            mStats->setInt32("width", width);
            mStats->setInt32("height", height);
        }
        sp<AMessage> notify = mNotify->dup();
        notify->setInt32("what", kWhatVideoSizeChanged);
        notify->setMessage("format", format);
        notify->post();
    } else if (mRenderer != NULL) {
        uint32_t flags;
        int64_t durationUs;
        bool hasVideo = (mSource->getFormat(false /* audio */) != NULL);
        if (getAudioDeepBufferSetting() // override regardless of source duration
                || (!hasVideo
                        && mSource->getDuration(&durationUs) == OK
                        && durationUs > AUDIO_SINK_MIN_DEEP_BUFFER_DURATION_US)) {
            flags = AUDIO_OUTPUT_FLAG_DEEP_BUFFER;
        } else {
            flags = AUDIO_OUTPUT_FLAG_NONE;
        }

#ifdef MTK_AOSP_ENHANCEMENT

/* ******************************************
*		 For mp3 and ape low power
*		 NuPlayerDecoder & NuPlayerRenderer
*		 patch 1/1 of NuPlayerDecoder
********************************************/
    AString mime;
    mInputFormat->findString("mime", &mime);
    if ((!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_AUDIO_MPEG)) || (!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_AUDIO_APE)))
    {
        format->setInt32("is-mp3-or-ape", 1);
    }
#endif

        mRenderer->openAudioSink(
                format, false /* offloadOnly */, hasVideo, flags, NULL /* isOffloaed */);
    }
}

void NuPlayer::Decoder::releaseAndResetMediaBuffers() {
    for (size_t i = 0; i < mMediaBuffers.size(); i++) {
        if (mMediaBuffers[i] != NULL) {
            mMediaBuffers[i]->release();
            mMediaBuffers.editItemAt(i) = NULL;
        }
    }
    mMediaBuffers.resize(mInputBuffers.size());
    for (size_t i = 0; i < mMediaBuffers.size(); i++) {
        mMediaBuffers.editItemAt(i) = NULL;
    }
    mInputBufferIsDequeued.clear();
    mInputBufferIsDequeued.resize(mInputBuffers.size());
    for (size_t i = 0; i < mInputBufferIsDequeued.size(); i++) {
        mInputBufferIsDequeued.editItemAt(i) = false;
    }

    mPendingInputMessages.clear();
    mDequeuedInputBuffers.clear();
    mSkipRenderingUntilMediaTimeUs = -1;
}

void NuPlayer::Decoder::requestCodecNotification() {
    if (mCodec != NULL) {
        sp<AMessage> reply = new AMessage(kWhatCodecNotify, this);
        reply->setInt32("generation", mBufferGeneration);
        mCodec->requestActivityNotification(reply);
    }
}

bool NuPlayer::Decoder::isStaleReply(const sp<AMessage> &msg) {
    int32_t generation;
    CHECK(msg->findInt32("generation", &generation));
    return generation != mBufferGeneration;
}

status_t NuPlayer::Decoder::fetchInputData(sp<AMessage> &reply) {
    sp<ABuffer> accessUnit;
    bool dropAccessUnit;
    do {
        status_t err = mSource->dequeueAccessUnit(mIsAudio, &accessUnit);

        if (err == -EWOULDBLOCK) {
            return err;
        } else if (err != OK) {
            if (err == INFO_DISCONTINUITY) {
                int32_t type;
                CHECK(accessUnit->meta()->findInt32("discontinuity", &type));

                bool formatChange =
                    (mIsAudio &&
                     (type & ATSParser::DISCONTINUITY_AUDIO_FORMAT))
                    || (!mIsAudio &&
                            (type & ATSParser::DISCONTINUITY_VIDEO_FORMAT));

                bool timeChange = (type & ATSParser::DISCONTINUITY_TIME) != 0;

                ALOGI("%s discontinuity (format=%d, time=%d)",
                        mIsAudio ? "audio" : "video", formatChange, timeChange);

                bool seamlessFormatChange = false;
                sp<AMessage> newFormat = mSource->getFormat(mIsAudio);
                if (formatChange) {
                    seamlessFormatChange =
                        supportsSeamlessFormatChange(newFormat);
                    // treat seamless format change separately
                    formatChange = !seamlessFormatChange;
                }

                // For format or time change, return EOS to queue EOS input,
                // then wait for EOS on output.
                if (formatChange /* not seamless */) {
                    mFormatChangePending = true;
                    err = ERROR_END_OF_STREAM;
                } else if (timeChange) {
                    rememberCodecSpecificData(newFormat);
                    mTimeChangePending = true;
                    err = ERROR_END_OF_STREAM;
                } else if (seamlessFormatChange) {
                    // reuse existing decoder and don't flush
                    rememberCodecSpecificData(newFormat);
                    continue;
                } else {
                    // This stream is unaffected by the discontinuity
                    return -EWOULDBLOCK;
                }
            }

            // reply should only be returned without a buffer set
            // when there is an error (including EOS)
            CHECK(err != OK);

            reply->setInt32("err", err);
            return ERROR_END_OF_STREAM;
        }

        dropAccessUnit = false;
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
        if(mIsSMPL){
            dropAccessUnit = false;
        }else {
            if (!mIsAudio
                    && !mIsSecure
                    && mRenderer->getVideoLateByUs() > 100000ll
                    && mIsVideoAVC
                    && !IsAVCReferenceFrame(accessUnit)) {
                dropAccessUnit = true;
                ++mNumInputFramesDropped;
#ifdef MTK_AOSP_ENHANCEMENT
                int64_t mediaTimeUs;
                accessUnit->meta()->findInt64("timeUs", &mediaTimeUs);
                MM_LOGI("[%s] drop one frame timeUs:%lld (%lld frames has dropped)",
                        mComponentName.c_str(), (long long)mediaTimeUs, mNumInputFramesDropped);
#endif
            }
        }
#else
        if (!mIsAudio
                && !mIsSecure
                && mRenderer->getVideoLateByUs() > 100000ll
                && mIsVideoAVC
                && !IsAVCReferenceFrame(accessUnit)) {
            dropAccessUnit = true;
            ++mNumInputFramesDropped;
#ifdef MTK_AOSP_ENHANCEMENT
            int64_t mediaTimeUs;
            accessUnit->meta()->findInt64("timeUs", &mediaTimeUs);
            MM_LOGI("[%s] drop one frame timeUs:%lld (%lld frames has dropped)",
                    mComponentName.c_str(), (long long)mediaTimeUs, mNumInputFramesDropped);
#endif
        }
#endif
    } while (dropAccessUnit);

    // ALOGV("returned a valid buffer of %s data", mIsAudio ? "mIsAudio" : "video");
#if 0
    int64_t mediaTimeUs;
    CHECK(accessUnit->meta()->findInt64("timeUs", &mediaTimeUs));
    ALOGV("[%s] feeding input buffer at media time %.3f",
         mIsAudio ? "audio" : "video",
         mediaTimeUs / 1E6);
#endif

    if (mCCDecoder != NULL) {
        mCCDecoder->decode(accessUnit);
    }

    reply->setBuffer("buffer", accessUnit);

    return OK;
}

bool NuPlayer::Decoder::onInputBufferFetched(const sp<AMessage> &msg) {
    size_t bufferIx;
    CHECK(msg->findSize("buffer-ix", &bufferIx));
    CHECK_LT(bufferIx, mInputBuffers.size());
    sp<ABuffer> codecBuffer = mInputBuffers[bufferIx];

    sp<ABuffer> buffer;
    bool hasBuffer = msg->findBuffer("buffer", &buffer);

    // handle widevine classic source - that fills an arbitrary input buffer
    MediaBuffer *mediaBuffer = NULL;
    if (hasBuffer) {
        mediaBuffer = (MediaBuffer *)(buffer->getMediaBufferBase());
        if (mediaBuffer != NULL) {
            // likely filled another buffer than we requested: adjust buffer index
            size_t ix;
            for (ix = 0; ix < mInputBuffers.size(); ix++) {
                const sp<ABuffer> &buf = mInputBuffers[ix];
                if (buf->data() == mediaBuffer->data()) {
                    // all input buffers are dequeued on start, hence the check
                    if (!mInputBufferIsDequeued[ix]) {
                        ALOGV("[%s] received MediaBuffer for #%zu instead of #%zu",
                                mComponentName.c_str(), ix, bufferIx);
                        mediaBuffer->release();
                        return false;
                    }

                    // TRICKY: need buffer for the metadata, so instead, set
                    // codecBuffer to the same (though incorrect) buffer to
                    // avoid a memcpy into the codecBuffer
                    codecBuffer = buffer;
                    codecBuffer->setRange(
                            mediaBuffer->range_offset(),
                            mediaBuffer->range_length());
                    bufferIx = ix;
                    break;
                }
            }
            CHECK(ix < mInputBuffers.size());
        }
    }

    if (buffer == NULL /* includes !hasBuffer */) {
        int32_t streamErr = ERROR_END_OF_STREAM;
        CHECK(msg->findInt32("err", &streamErr) || !hasBuffer);

        CHECK(streamErr != OK);

        // attempt to queue EOS
#ifdef  MTK_PLAYREADY_SUPPORT
        if (!mInputBufferIsDequeued[bufferIx]) {
            ALOGI("[%s] bufferIx:%zu, is not dequeued now",
                    mComponentName.c_str(),  bufferIx);
            return false;
        }
#endif
        status_t err = mCodec->queueInputBuffer(
                bufferIx,
                0,
                0,
                0,
                MediaCodec::BUFFER_FLAG_EOS);
        if (err == OK) {
            mInputBufferIsDequeued.editItemAt(bufferIx) = false;
        } else if (streamErr == ERROR_END_OF_STREAM) {
            streamErr = err;
            // err will not be ERROR_END_OF_STREAM
        }

        if (streamErr != ERROR_END_OF_STREAM) {
            ALOGE("Stream error for %s (err=%d), EOS %s queued",
                    mComponentName.c_str(),
                    streamErr,
                    err == OK ? "successfully" : "unsuccessfully");
#ifdef MTK_AOSP_ENHANCEMENT
            handleError(streamErr, false);     // not ACodec error
#else
            handleError(streamErr);
#endif
        }
    } else {
        sp<AMessage> extra;
        if (buffer->meta()->findMessage("extra", &extra) && extra != NULL) {
            int64_t resumeAtMediaTimeUs;
            if (extra->findInt64(
                        "resume-at-mediaTimeUs", &resumeAtMediaTimeUs)) {
                ALOGI("[%s] suppressing rendering until %lld us",
                        mComponentName.c_str(), (long long)resumeAtMediaTimeUs);
                mSkipRenderingUntilMediaTimeUs = resumeAtMediaTimeUs;
            }
#ifdef MTK_AOSP_ENHANCEMENT
            int64_t seekTimeUsForDecoder = 0;
            if (extra->findInt64(
                        "decode-seekTime", &seekTimeUsForDecoder)) {
                if (!mIsAudio && mCodec != NULL) {
                    sp<AMessage> msg = new AMessage;
                    msg->setInt64("seekTimeUs", seekTimeUsForDecoder);
                    mCodec->setParameters(msg);
                    ALOGI("set video decode seek time:%lld", (long long)seekTimeUsForDecoder);
                }
            }
#endif
        }

        int64_t timeUs = 0;
        uint32_t flags = 0;
        CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

        int32_t eos, csd;
        // we do not expect SYNCFRAME for decoder
        if (buffer->meta()->findInt32("eos", &eos) && eos) {
            flags |= MediaCodec::BUFFER_FLAG_EOS;
        } else if (buffer->meta()->findInt32("csd", &csd) && csd) {
            flags |= MediaCodec::BUFFER_FLAG_CODECCONFIG;
        }

#ifdef MTK_AOSP_ENHANCEMENT
    int32_t fgInvalidTimeUs = false;
    if (buffer->meta()->findInt32("invt", &fgInvalidTimeUs) && fgInvalidTimeUs) {
        flags |= MediaCodec::BUFFER_FLAG_INVALID_PTS;
    }
#endif
        // copy into codec buffer
        if (buffer != codecBuffer) {
#ifndef MTK_AOSP_ENHANCEMENT
            CHECK_LE(buffer->size(), codecBuffer->capacity());
#else
        if (!checkHandlePartialFrame(buffer, codecBuffer, csd, eos, &flags, mediaBuffer)) {
            return false;
        }
#endif
            codecBuffer->setRange(0, buffer->size());
            memcpy(codecBuffer->data(), buffer->data(), buffer->size());
        }

        status_t err = mCodec->queueInputBuffer(
                        bufferIx,
                        codecBuffer->offset(),
                        codecBuffer->size(),
                        timeUs,
                        flags);
        if (err != OK) {
            if (mediaBuffer != NULL) {
                mediaBuffer->release();
            }
            ALOGE("Failed to queue input buffer for %s (err=%d)",
                    mComponentName.c_str(), err);
#ifdef MTK_AOSP_ENHANCEMENT
            handleError(err, true);
#else
            handleError(err);
#endif
        } else {
            mInputBufferIsDequeued.editItemAt(bufferIx) = false;
            if (mediaBuffer != NULL) {
                CHECK(mMediaBuffers[bufferIx] == NULL);
                mMediaBuffers.editItemAt(bufferIx) = mediaBuffer;
            }
        }
    }
    return true;
}

void NuPlayer::Decoder::onRenderBuffer(const sp<AMessage> &msg) {
    status_t err;
    int32_t render;
    size_t bufferIx;
    int32_t eos;
    CHECK(msg->findSize("buffer-ix", &bufferIx));

    if (!mIsAudio) {
        int64_t timeUs;
        sp<ABuffer> buffer = mOutputBuffers[bufferIx];
        buffer->meta()->findInt64("timeUs", &timeUs);

        if (mCCDecoder != NULL && mCCDecoder->isSelected()) {
            mCCDecoder->display(timeUs);
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
    setRenderBufferInfo(bufferIx, msg);
#endif

    if (msg->findInt32("render", &render) && render) {
        int64_t timestampNs;
        CHECK(msg->findInt64("timestampNs", &timestampNs));
        err = mCodec->renderOutputBufferAndRelease(bufferIx, timestampNs);
    } else {
        mNumOutputFramesDropped += !mIsAudio;
        err = mCodec->releaseOutputBuffer(bufferIx);
    }
    if (err != OK) {
        ALOGE("failed to release output buffer for %s (err=%d)",
                mComponentName.c_str(), err);
#ifdef MTK_AOSP_ENHANCEMENT
        handleError(err, true);
#else
        handleError(err);
#endif
    }
    if (msg->findInt32("eos", &eos) && eos
            && isDiscontinuityPending()) {
        finishHandleDiscontinuity(true /* flushOnTimeChange */);
    }
}

bool NuPlayer::Decoder::isDiscontinuityPending() const {
    return mFormatChangePending || mTimeChangePending;
}

void NuPlayer::Decoder::finishHandleDiscontinuity(bool flushOnTimeChange) {
    ALOGV("finishHandleDiscontinuity: format %d, time %d, flush %d",
            mFormatChangePending, mTimeChangePending, flushOnTimeChange);

    // If we have format change, pause and wait to be killed;
    // If we have time change only, flush and restart fetching.

    if (mFormatChangePending) {
        mPaused = true;
    } else if (mTimeChangePending) {
        if (flushOnTimeChange) {
            doFlush(false /* notifyComplete */);
            signalResume(false /* notifyComplete */);
        }
    }

    // Notify NuPlayer to either shutdown decoder, or rescan sources
    sp<AMessage> msg = mNotify->dup();
    msg->setInt32("what", kWhatInputDiscontinuity);
    msg->setInt32("formatChange", mFormatChangePending);
    msg->post();

    mFormatChangePending = false;
    mTimeChangePending = false;
}

bool NuPlayer::Decoder::supportsSeamlessAudioFormatChange(
        const sp<AMessage> &targetFormat) const {
    if (targetFormat == NULL) {
        return true;
    }

    AString mime;
    if (!targetFormat->findString("mime", &mime)) {
        return false;
    }

    if (!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_AUDIO_AAC)) {
        // field-by-field comparison
        const char * keys[] = { "channel-count", "sample-rate", "is-adts" };
        for (unsigned int i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
            int32_t oldVal, newVal;
            if (!mInputFormat->findInt32(keys[i], &oldVal) ||
                    !targetFormat->findInt32(keys[i], &newVal) ||
                    oldVal != newVal) {
                return false;
            }
        }

        sp<ABuffer> oldBuf, newBuf;
        if (mInputFormat->findBuffer("csd-0", &oldBuf) &&
                targetFormat->findBuffer("csd-0", &newBuf)) {
            if (oldBuf->size() != newBuf->size()) {
                return false;
            }
            return !memcmp(oldBuf->data(), newBuf->data(), oldBuf->size());
        }
    }
    return false;
}

bool NuPlayer::Decoder::supportsSeamlessFormatChange(const sp<AMessage> &targetFormat) const {
    if (mInputFormat == NULL) {
        return false;
    }

    if (targetFormat == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
        return false;
#else
        return true;
#endif
    }

    AString oldMime, newMime;
    if (!mInputFormat->findString("mime", &oldMime)
            || !targetFormat->findString("mime", &newMime)
            || !(oldMime == newMime)) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGI("not support seamless format change from %s to %s", oldMime.c_str(), newMime.c_str());
#endif
        return false;
    }

    bool audio = !strncasecmp(oldMime.c_str(), "audio/", strlen("audio/"));
    bool seamless;
    if (audio) {
        seamless = supportsSeamlessAudioFormatChange(targetFormat);
    } else {
        int32_t isAdaptive;
        seamless = (mCodec != NULL &&
                mInputFormat->findInt32("adaptive-playback", &isAdaptive) &&
                isAdaptive);
    }

#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("%s seamless support for %s", seamless ? "yes" : "no", oldMime.c_str());
#else
    ALOGV("%s seamless support for %s", seamless ? "yes" : "no", oldMime.c_str());
#endif
    return seamless;
}

void NuPlayer::Decoder::rememberCodecSpecificData(const sp<AMessage> &format) {
    if (format == NULL) {
        return;
    }
    mCSDsForCurrentFormat.clear();
    for (int32_t i = 0; ; ++i) {
        AString tag = "csd-";
        tag.append(i);
        sp<ABuffer> buffer;
        if (!format->findBuffer(tag.c_str(), &buffer)) {
            break;
        }
        mCSDsForCurrentFormat.push(buffer);
    }
}

void NuPlayer::Decoder::notifyResumeCompleteIfNecessary() {
    if (mResumePending) {
        mResumePending = false;

        sp<AMessage> notify = mNotify->dup();
        notify->setInt32("what", kWhatResumeCompleted);
        notify->post();
    }
}
#ifdef MTK_AOSP_ENHANCEMENT
void NuPlayer::Decoder::setRenderBufferInfo(size_t bufferIx, const sp<AMessage> &msgFrom) {
    sp<ABuffer> buffer = mOutputBuffers[bufferIx];
    int64_t realTimeUs = -1;
    int64_t delaytimeus = -1;
    int64_t  AvSyncRefTimeUs = -1;
    if (msgFrom->findInt64("realtimeus", &realTimeUs) && realTimeUs != -1) {
         buffer->meta()->setInt64("realtimeus", realTimeUs);
    }
    if (msgFrom->findInt64("delaytimeus", &delaytimeus) && delaytimeus != -1) {
         buffer->meta()->setInt64("delaytimeus", delaytimeus);
    }
    if (msgFrom->findInt64("AvSyncRefTimeUs", &AvSyncRefTimeUs) && AvSyncRefTimeUs != -1) {
         buffer->meta()->setInt64("AvSyncRefTimeUs", AvSyncRefTimeUs);
    }
}

bool NuPlayer::Decoder::checkHandlePartialFrame(sp<ABuffer> srcBuffer, const sp<ABuffer> &codecBuffer,
        bool isCsd, bool isEos, uint32_t *flags, MediaBuffer * mBuffer) {
    if (srcBuffer->size() > codecBuffer->capacity()) {
           int64_t timeUs = 0;
        CHECK(srcBuffer->meta()->findInt64("timeUs", &timeUs));
        if (mSupportsPartialFrames) {
            sp<ABuffer> leftBuffer = new ABuffer(srcBuffer->size() - codecBuffer->capacity());
            memcpy(leftBuffer->data(), srcBuffer->data() + codecBuffer->capacity(),
                    srcBuffer->size() - codecBuffer->capacity());
            leftBuffer->meta()->setInt64("timeUs", timeUs);
            if (isCsd) {
                leftBuffer->meta()->setInt32("csd", isCsd);
            } else if (isEos) {
                leftBuffer->meta()->setInt32("eos", isEos);
            }
            srcBuffer->setRange(srcBuffer->offset(), codecBuffer->capacity());
            *flags |= MediaCodec::BUFFER_FLAG_PARTAIL_FRAME;

            ALOGI("split big input buffer %d to %d + %d",
                    srcBuffer->size(), codecBuffer->capacity(), leftBuffer->size());

            mLeftOverBuffer = leftBuffer;
        } else {
            if (mBuffer != NULL) {
                mBuffer->release();
            }
            ALOGE("not support partial frame,buffer size:%d, large codec input buffer size:%d",
                    srcBuffer->size(), codecBuffer->capacity());
            handleError(ERROR_BAD_FRAME_SIZE, false);
            return false;
        }
    }
    return true;
}

void NuPlayer::Decoder::handleError(int32_t err, bool isACodecErr) {
    // We cannot immediately release the codec due to buffers still outstanding
    // in the renderer.  We signal to the player the error so it can shutdown/release the
    // decoder after flushing and increment the generation to discard unnecessary messages.

    ++mBufferGeneration;

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatError);
    notify->setInt32("err", err);
    if (isACodecErr) {
        notify->setInt32("errACodec", err);
    }
    notify->post();
}
#endif
}  // namespace android

