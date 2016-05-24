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
#define LOG_TAG "NuPlayerRenderer"
#include <utils/Log.h>

#include "NuPlayerRenderer.h"
#include <cutils/properties.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AUtils.h>
#include <media/stagefright/foundation/AWakeLock.h>
#include <media/stagefright/MediaClock.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/VideoFrameScheduler.h>

#include <inttypes.h>
#ifdef MTK_AOSP_ENHANCEMENT
#include <media/AudioTrackCenter.h>
#include <utils/Trace.h>
#include <cutils/properties.h>
#define DUMP_PROFILE 0
#define MAX_VIDEO_EARLY_POST_US     50000ll
#define USE_AUDIO_TRACK_CENTER  (1)
static bool sFrameAVsyncByVFS = true;
static int sDebugDumpmAQmVQ = 0;
#endif

#include <media/MtkMMLog.h>
namespace android {

/*
 * Example of common configuration settings in shell script form

   #Turn offload audio off (use PCM for Play Music) -- AudioPolicyManager
   adb shell setprop audio.offload.disable 1

   #Allow offload audio with video (requires offloading to be enabled) -- AudioPolicyManager
   adb shell setprop audio.offload.video 1

   #Use audio callbacks for PCM data
   adb shell setprop media.stagefright.audio.cbk 1

   #Use deep buffer for PCM data with video (it is generally enabled for audio-only)
   adb shell setprop media.stagefright.audio.deep 1

   #Set size of buffers for pcm audio sink in msec (example: 1000 msec)
   adb shell setprop media.stagefright.audio.sink 1000

 * These configurations take effect for the next track played (not the current track).
 */

static inline bool getUseAudioCallbackSetting() {
    return property_get_bool("media.stagefright.audio.cbk", false /* default_value */);
}

static inline int32_t getAudioSinkPcmMsSetting() {
    return property_get_int32(
            "media.stagefright.audio.sink", 500 /* default_value */);
}

// Maximum time in paused state when offloading audio decompression. When elapsed, the AudioSink
// is closed to allow the audio DSP to power down.
static const int64_t kOffloadPauseMaxUs = 10000000ll;

// static
const NuPlayer::Renderer::PcmInfo NuPlayer::Renderer::AUDIO_PCMINFO_INITIALIZER = {
        AUDIO_CHANNEL_NONE,
        AUDIO_OUTPUT_FLAG_NONE,
        AUDIO_FORMAT_INVALID,
        0, // mNumChannels
        0 // mSampleRate
};

// static
const int64_t NuPlayer::Renderer::kMinPositionUpdateDelayUs = 100000ll;

NuPlayer::Renderer::Renderer(
        const sp<MediaPlayerBase::AudioSink> &sink,
        const sp<AMessage> &notify,
        uint32_t flags)
    : mAudioSink(sink),
      mNotify(notify),
      mFlags(flags),
      mNumFramesWritten(0),
      mDrainAudioQueuePending(false),
      mDrainVideoQueuePending(false),
      mAudioQueueGeneration(0),
      mVideoQueueGeneration(0),
      mAudioDrainGeneration(0),
      mVideoDrainGeneration(0),
      mPlaybackSettings(AUDIO_PLAYBACK_RATE_DEFAULT),
      mAudioFirstAnchorTimeMediaUs(-1),
      mAnchorTimeMediaUs(-1),
      mAnchorNumFramesWritten(-1),
      mVideoLateByUs(0ll),
      mHasAudio(false),
      mHasVideo(false),
      mNotifyCompleteAudio(false),
      mNotifyCompleteVideo(false),
      mSyncQueues(false),
      mPaused(false),
      mVideoSampleReceived(false),
      mVideoRenderingStarted(false),
      mVideoRenderingStartGeneration(0),
      mAudioRenderingStartGeneration(0),
      mAudioOffloadPauseTimeoutGeneration(0),
      mAudioTornDown(false),
      mCurrentOffloadInfo(AUDIO_INFO_INITIALIZER),
      mCurrentPcmInfo(AUDIO_PCMINFO_INITIALIZER),
      mTotalBuffersQueued(0),
      mLastAudioBufferDrained(0),
      mUseAudioCallback(false),
      mWakeLock(new AWakeLock()) {
    mMediaClock = new MediaClock;
    mPlaybackRate = mPlaybackSettings.mSpeed;
    mMediaClock->setPlaybackRate(mPlaybackRate);
#ifdef MTK_AOSP_ENHANCEMENT
    init_ext();
#endif
}

NuPlayer::Renderer::~Renderer() {
    if (offloadingAudio()) {
        mAudioSink->stop();
        mAudioSink->flush();
        mAudioSink->close();
    }
}

void NuPlayer::Renderer::queueBuffer(
        bool audio,
        const sp<ABuffer> &buffer,
        const sp<AMessage> &notifyConsumed) {
    sp<AMessage> msg = new AMessage(kWhatQueueBuffer, this);
    msg->setInt32("queueGeneration", getQueueGeneration(audio));
    msg->setInt32("audio", static_cast<int32_t>(audio));
    msg->setBuffer("buffer", buffer);
    msg->setMessage("notifyConsumed", notifyConsumed);
    msg->post();
}

void NuPlayer::Renderer::queueEOS(bool audio, status_t finalResult) {
    MM_LOGI("queueEOS audio:%d", audio);
    CHECK_NE(finalResult, (status_t)OK);

    sp<AMessage> msg = new AMessage(kWhatQueueEOS, this);
    msg->setInt32("queueGeneration", getQueueGeneration(audio));
    msg->setInt32("audio", static_cast<int32_t>(audio));
    msg->setInt32("finalResult", finalResult);
    msg->post();
#ifdef MTK_AOSP_ENHANCEMENT
//handle pause then seekto eos, music turn to play next song issue part 1/3
    if(mPaused && audio)
    {
        mDrainAudioQueuePending = true;
    }
#endif
}

status_t NuPlayer::Renderer::setPlaybackSettings(const AudioPlaybackRate &rate) {
    sp<AMessage> msg = new AMessage(kWhatConfigPlayback, this);
    writeToAMessage(msg, rate);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

status_t NuPlayer::Renderer::onConfigPlayback(const AudioPlaybackRate &rate /* sanitized */) {
    if (rate.mSpeed == 0.f) {
        onPause();
        // don't call audiosink's setPlaybackRate if pausing, as pitch does not
        // have to correspond to the any non-0 speed (e.g old speed). Keep
        // settings nonetheless, using the old speed, in case audiosink changes.
        AudioPlaybackRate newRate = rate;
        newRate.mSpeed = mPlaybackSettings.mSpeed;
        mPlaybackSettings = newRate;
        return OK;
    }

    if (mAudioSink != NULL && mAudioSink->ready()) {
        status_t err = mAudioSink->setPlaybackRate(rate);
        if (err != OK) {
            return err;
        }
    }
    mPlaybackSettings = rate;
    mPlaybackRate = rate.mSpeed;
    mMediaClock->setPlaybackRate(mPlaybackRate);
    return OK;
}

status_t NuPlayer::Renderer::getPlaybackSettings(AudioPlaybackRate *rate /* nonnull */) {
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

status_t NuPlayer::Renderer::onGetPlaybackSettings(AudioPlaybackRate *rate /* nonnull */) {
    if (mAudioSink != NULL && mAudioSink->ready()) {
        status_t err = mAudioSink->getPlaybackRate(rate);
        if (err == OK) {
            if (!isAudioPlaybackRateEqual(*rate, mPlaybackSettings)) {
                ALOGW("correcting mismatch in internal/external playback rate");
            }
            // get playback settings used by audiosink, as it may be
            // slightly off due to audiosink not taking small changes.
            mPlaybackSettings = *rate;
            if (mPaused) {
                rate->mSpeed = 0.f;
            }
        }
        return err;
    }
    *rate = mPlaybackSettings;
    return OK;
}

status_t NuPlayer::Renderer::setSyncSettings(const AVSyncSettings &sync, float videoFpsHint) {
    sp<AMessage> msg = new AMessage(kWhatConfigSync, this);
    writeToAMessage(msg, sync, videoFpsHint);
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}

status_t NuPlayer::Renderer::onConfigSync(const AVSyncSettings &sync, float videoFpsHint __unused) {
    if (sync.mSource != AVSYNC_SOURCE_DEFAULT) {
        return BAD_VALUE;
    }
    // TODO: support sync sources
    return INVALID_OPERATION;
}

status_t NuPlayer::Renderer::getSyncSettings(AVSyncSettings *sync, float *videoFps) {
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

status_t NuPlayer::Renderer::onGetSyncSettings(
        AVSyncSettings *sync /* nonnull */, float *videoFps /* nonnull */) {
    *sync = mSyncSettings;
    *videoFps = -1.f;
    return OK;
}

void NuPlayer::Renderer::flush(bool audio, bool notifyComplete) {
    {
        Mutex::Autolock autoLock(mLock);
        if (audio) {
            mNotifyCompleteAudio |= notifyComplete;
            clearAudioFirstAnchorTime_l();
            ++mAudioQueueGeneration;
            ++mAudioDrainGeneration;
#ifdef MTK_AOSP_ENHANCEMENT
            mAudioFlushed = true;
#endif
        } else {
            mNotifyCompleteVideo |= notifyComplete;
            ++mVideoQueueGeneration;
            ++mVideoDrainGeneration;
        }

        clearAnchorTime_l();
        mVideoLateByUs = 0;
#ifdef MTK_AOSP_ENHANCEMENT
/*
        if (!mAudioQueue.empty()) {
            ALOGE("------signalTimeDiscontinuity (audio size=%d)----", (int)mAudioQueue.size());
            //dumpQueue(&mAudioQueue, true);
        }
        if (!mVideoQueue.empty()) {
            ALOGE("------signalTimeDiscontinuity (video size=%d)----", (int)mVideoQueue.size());
            //dumpQueue(&mVideoQueue, false);
        }
*/
        if (mFlags & FLAG_HAS_VIDEO_AUDIO) {
            ALOGI("signalTimeDiscontinuity sync queue");
            mSyncQueues = true;
        }else{
            ALOGI("signalTimeDiscontinuity not sync queue");
            syncQueuesDone_l();
        }
#else
        mSyncQueues = false;
#endif
    }

    sp<AMessage> msg = new AMessage(kWhatFlush, this);
    msg->setInt32("audio", static_cast<int32_t>(audio));
    msg->post();
}

void NuPlayer::Renderer::signalTimeDiscontinuity() {
}

void NuPlayer::Renderer::signalDisableOffloadAudio() {
    (new AMessage(kWhatDisableOffloadAudio, this))->post();
}

void NuPlayer::Renderer::signalEnableOffloadAudio() {
    (new AMessage(kWhatEnableOffloadAudio, this))->post();
}

void NuPlayer::Renderer::pause() {
    (new AMessage(kWhatPause, this))->post();
}

void NuPlayer::Renderer::resume() {
    (new AMessage(kWhatResume, this))->post();
}

void NuPlayer::Renderer::setVideoFrameRate(float fps) {
    sp<AMessage> msg = new AMessage(kWhatSetVideoFrameRate, this);
    msg->setFloat("frame-rate", fps);
    msg->post();
}

// Called on any threads.
status_t NuPlayer::Renderer::getCurrentPosition(int64_t *mediaUs) {
    return mMediaClock->getMediaTime(ALooper::GetNowUs(), mediaUs);
}

void NuPlayer::Renderer::clearAudioFirstAnchorTime_l() {
    mAudioFirstAnchorTimeMediaUs = -1;
    mMediaClock->setStartingTimeMedia(-1);
}

void NuPlayer::Renderer::setAudioFirstAnchorTimeIfNeeded_l(int64_t mediaUs) {
    if (mAudioFirstAnchorTimeMediaUs == -1) {
        mAudioFirstAnchorTimeMediaUs = mediaUs;
        mMediaClock->setStartingTimeMedia(mediaUs);
    }
}

void NuPlayer::Renderer::clearAnchorTime_l() {
    mMediaClock->clearAnchor();
    mAnchorTimeMediaUs = -1;
    mAnchorNumFramesWritten = -1;
}

void NuPlayer::Renderer::setVideoLateByUs(int64_t lateUs) {
    Mutex::Autolock autoLock(mLock);
    mVideoLateByUs = lateUs;
}

int64_t NuPlayer::Renderer::getVideoLateByUs() {
    Mutex::Autolock autoLock(mLock);
    return mVideoLateByUs;
}

status_t NuPlayer::Renderer::openAudioSink(
        const sp<AMessage> &format,
        bool offloadOnly,
        bool hasVideo,
        uint32_t flags,
        bool *isOffloaded) {
    sp<AMessage> msg = new AMessage(kWhatOpenAudioSink, this);
    msg->setMessage("format", format);
    msg->setInt32("offload-only", offloadOnly);
    msg->setInt32("has-video", hasVideo);
    msg->setInt32("flags", flags);

    sp<AMessage> response;
    msg->postAndAwaitResponse(&response);

    int32_t err;
    if (!response->findInt32("err", &err)) {
        err = INVALID_OPERATION;
    } else if (err == OK && isOffloaded != NULL) {
        int32_t offload;
        CHECK(response->findInt32("offload", &offload));
        *isOffloaded = (offload != 0);
    }
    return err;
}

void NuPlayer::Renderer::closeAudioSink() {
    sp<AMessage> msg = new AMessage(kWhatCloseAudioSink, this);

    sp<AMessage> response;
    msg->postAndAwaitResponse(&response);
}

void NuPlayer::Renderer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatOpenAudioSink:
        {
            sp<AMessage> format;
            CHECK(msg->findMessage("format", &format));

            int32_t offloadOnly;
            CHECK(msg->findInt32("offload-only", &offloadOnly));

            int32_t hasVideo;
            CHECK(msg->findInt32("has-video", &hasVideo));

            uint32_t flags;
            CHECK(msg->findInt32("flags", (int32_t *)&flags));

            status_t err = onOpenAudioSink(format, offloadOnly, hasVideo, flags);

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->setInt32("offload", offloadingAudio());

            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            response->postReply(replyID);

            break;
        }

        case kWhatCloseAudioSink:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            onCloseAudioSink();

            sp<AMessage> response = new AMessage;
            response->postReply(replyID);
            break;
        }

        case kWhatStopAudioSink:
        {
            mAudioSink->stop();
            break;
        }

        case kWhatDrainAudioQueue:
        {
            mDrainAudioQueuePending = false;

            int32_t generation;
            CHECK(msg->findInt32("drainGeneration", &generation));
            if (generation != getDrainGeneration(true /* audio */)) {
                break;
            }

            if (onDrainAudioQueue()) {
                uint32_t numFramesPlayed;
                CHECK_EQ(mAudioSink->getPosition(&numFramesPlayed),
                         (status_t)OK);

                uint32_t numFramesPendingPlayout =
                    mNumFramesWritten - numFramesPlayed;

                // This is how long the audio sink will have data to
                // play back.
                int64_t delayUs =
                    mAudioSink->msecsPerFrame()
                        * numFramesPendingPlayout * 1000ll;
                if (mPlaybackRate > 1.0f) {
                    delayUs /= mPlaybackRate;
                }

                // Let's give it more data after about half that time
                // has elapsed.
                Mutex::Autolock autoLock(mLock);
                postDrainAudioQueue_l(delayUs / 2);
            }
            break;
        }

        case kWhatDrainVideoQueue:
        {
            int32_t generation;
            CHECK(msg->findInt32("drainGeneration", &generation));
            if (generation != getDrainGeneration(false /* audio */)) {
                break;
            }

            mDrainVideoQueuePending = false;

            onDrainVideoQueue();

            postDrainVideoQueue();
            break;
        }

        case kWhatPostDrainVideoQueue:
        {
            int32_t generation;
            CHECK(msg->findInt32("drainGeneration", &generation));
            if (generation != getDrainGeneration(false /* audio */)) {
                break;
            }

            mDrainVideoQueuePending = false;
            postDrainVideoQueue();
            break;
        }

        case kWhatQueueBuffer:
        {
            onQueueBuffer(msg);
            break;
        }

        case kWhatQueueEOS:
        {
            onQueueEOS(msg);
            break;
        }

        case kWhatConfigPlayback:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            AudioPlaybackRate rate;
            readFromAMessage(msg, &rate);
            status_t err = onConfigPlayback(rate);
            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatGetPlaybackSettings:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            AudioPlaybackRate rate = AUDIO_PLAYBACK_RATE_DEFAULT;
            status_t err = onGetPlaybackSettings(&rate);
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
            AVSyncSettings sync;
            float videoFpsHint;
            readFromAMessage(msg, &sync, &videoFpsHint);
            status_t err = onConfigSync(sync, videoFpsHint);
            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatGetSyncSettings:
        {
            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            ALOGV("kWhatGetSyncSettings");
            AVSyncSettings sync;
            float videoFps = -1.f;
            status_t err = onGetSyncSettings(&sync, &videoFps);
            sp<AMessage> response = new AMessage;
            if (err == OK) {
                writeToAMessage(response, sync, videoFps);
            }
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatFlush:
        {
            onFlush(msg);
            break;
        }

        case kWhatDisableOffloadAudio:
        {
            onDisableOffloadAudio();
            break;
        }

        case kWhatEnableOffloadAudio:
        {
            onEnableOffloadAudio();
            break;
        }

        case kWhatPause:
        {
#if  defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
         if(onPauseForClearMotion(msg))break;
#endif
            onPause();
            break;
        }

        case kWhatResume:
        {
            onResume();
            break;
        }

        case kWhatSetVideoFrameRate:
        {
            float fps;
            CHECK(msg->findFloat("frame-rate", &fps));
            onSetVideoFrameRate(fps);
            break;
        }

        case kWhatAudioTearDown:
        {
            onAudioTearDown(kDueToError);
            break;
        }

        case kWhatAudioOffloadPauseTimeout:
        {
            int32_t generation;
            CHECK(msg->findInt32("drainGeneration", &generation));
            if (generation != mAudioOffloadPauseTimeoutGeneration) {
                break;
            }
            ALOGV("Audio Offload tear down due to pause timeout.");
            onAudioTearDown(kDueToTimeout);
            mWakeLock->release();
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void NuPlayer::Renderer::postDrainAudioQueue_l(int64_t delayUs) {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mDrainAudioQueuePending || isSyncQueues() || mUseAudioCallback
            || offloadingAudio()) {
        return;
    }
#else
    if (mDrainAudioQueuePending || mSyncQueues || mUseAudioCallback) {
        return;
    }
#endif

    if (mAudioQueue.empty()) {
        return;
    }

#ifdef MTK_AOSP_ENHANCEMENT
//handle pause then seekto eos, music turn to play next song issue part 2/3
    if(mPaused)
    {
        List<QueueEntry>::iterator it;
        it =  mAudioQueue.begin();
        while(it != mAudioQueue.end())
        {
            if((*it).mBuffer == NULL)
            {
                ALOGD("EOS deteced in pause status");
                return;
            }
            else
            {
                it++;
            }
        }
    }
#endif


    mDrainAudioQueuePending = true;
    sp<AMessage> msg = new AMessage(kWhatDrainAudioQueue, this);
    msg->setInt32("drainGeneration", mAudioDrainGeneration);
    msg->post(delayUs);
}

void NuPlayer::Renderer::prepareForMediaRenderingStart_l() {
    mAudioRenderingStartGeneration = mAudioDrainGeneration;
    mVideoRenderingStartGeneration = mVideoDrainGeneration;
}

void NuPlayer::Renderer::notifyIfMediaRenderingStarted_l() {
    if (mVideoRenderingStartGeneration == mVideoDrainGeneration &&
        mAudioRenderingStartGeneration == mAudioDrainGeneration) {
        mVideoRenderingStartGeneration = -1;
        mAudioRenderingStartGeneration = -1;

        sp<AMessage> notify = mNotify->dup();
        notify->setInt32("what", kWhatMediaRenderingStart);
        notify->post();
    }
}

// static
size_t NuPlayer::Renderer::AudioSinkCallback(
        MediaPlayerBase::AudioSink * /* audioSink */,
        void *buffer,
        size_t size,
        void *cookie,
        MediaPlayerBase::AudioSink::cb_event_t event) {
    NuPlayer::Renderer *me = (NuPlayer::Renderer *)cookie;

    switch (event) {
        case MediaPlayerBase::AudioSink::CB_EVENT_FILL_BUFFER:
        {
            return me->fillAudioBuffer(buffer, size);
            break;
        }

        case MediaPlayerBase::AudioSink::CB_EVENT_STREAM_END:
        {
            ALOGV("AudioSink::CB_EVENT_STREAM_END");
            me->notifyEOS(true /* audio */, ERROR_END_OF_STREAM);
            break;
        }

        case MediaPlayerBase::AudioSink::CB_EVENT_TEAR_DOWN:
        {
            ALOGV("AudioSink::CB_EVENT_TEAR_DOWN");
            me->notifyAudioTearDown();
            break;
        }
    }

    return 0;
}

size_t NuPlayer::Renderer::fillAudioBuffer(void *buffer, size_t size) {
    Mutex::Autolock autoLock(mLock);

    if (!mUseAudioCallback) {
        return 0;
    }

    bool hasEOS = false;

    size_t sizeCopied = 0;
    bool firstEntry = true;
    QueueEntry *entry;  // will be valid after while loop if hasEOS is set.
    while (sizeCopied < size && !mAudioQueue.empty()) {
        entry = &*mAudioQueue.begin();

        if (entry->mBuffer == NULL) { // EOS
            hasEOS = true;
            mAudioQueue.erase(mAudioQueue.begin());
            break;
        }

        if (firstEntry && entry->mOffset == 0) {
            firstEntry = false;
            int64_t mediaTimeUs;
            CHECK(entry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
            ALOGV("fillAudioBuffer: rendering audio at media time %.2f secs", mediaTimeUs / 1E6);
            setAudioFirstAnchorTimeIfNeeded_l(mediaTimeUs);
        }

        size_t copy = entry->mBuffer->size() - entry->mOffset;
        size_t sizeRemaining = size - sizeCopied;
        if (copy > sizeRemaining) {
            copy = sizeRemaining;
        }

        memcpy((char *)buffer + sizeCopied,
               entry->mBuffer->data() + entry->mOffset,
               copy);

        entry->mOffset += copy;
        if (entry->mOffset == entry->mBuffer->size()) {
            entry->mNotifyConsumed->post();
            mAudioQueue.erase(mAudioQueue.begin());
            entry = NULL;
        }
        sizeCopied += copy;

        notifyIfMediaRenderingStarted_l();
    }

    if (mAudioFirstAnchorTimeMediaUs >= 0) {
        int64_t nowUs = ALooper::GetNowUs();
        int64_t nowMediaUs =
            mAudioFirstAnchorTimeMediaUs + getPlayedOutAudioDurationUs(nowUs);
        // we don't know how much data we are queueing for offloaded tracks.
        mMediaClock->updateAnchor(nowMediaUs, nowUs, INT64_MAX);
    }

    // for non-offloaded audio, we need to compute the frames written because
    // there is no EVENT_STREAM_END notification. The frames written gives
    // an estimate on the pending played out duration.
    if (!offloadingAudio()) {
        mNumFramesWritten += sizeCopied / mAudioSink->frameSize();
    }

    if (hasEOS) {
        (new AMessage(kWhatStopAudioSink, this))->post();
        // As there is currently no EVENT_STREAM_END callback notification for
        // non-offloaded audio tracks, we need to post the EOS ourselves.
        if (!offloadingAudio()) {
            int64_t postEOSDelayUs = 0;
            if (mAudioSink->needsTrailingPadding()) {
                postEOSDelayUs = getPendingAudioPlayoutDurationUs(ALooper::GetNowUs());
            }
            ALOGV("fillAudioBuffer: notifyEOS "
                    "mNumFramesWritten:%u  finalResult:%d  postEOSDelay:%lld",
                    mNumFramesWritten, entry->mFinalResult, (long long)postEOSDelayUs);
            notifyEOS(true /* audio */, entry->mFinalResult, postEOSDelayUs);
        }
    }
    return sizeCopied;
}

void NuPlayer::Renderer::drainAudioQueueUntilLastEOS() {
    List<QueueEntry>::iterator it = mAudioQueue.begin(), itEOS = it;
    bool foundEOS = false;
    while (it != mAudioQueue.end()) {
        int32_t eos;
        QueueEntry *entry = &*it++;
        if (entry->mBuffer == NULL
                || (entry->mNotifyConsumed->findInt32("eos", &eos) && eos != 0)) {
            itEOS = it;
            foundEOS = true;
        }
    }

    if (foundEOS) {
        // post all replies before EOS and drop the samples
        for (it = mAudioQueue.begin(); it != itEOS; it++) {
            if (it->mBuffer == NULL) {
                // delay doesn't matter as we don't even have an AudioTrack
                notifyEOS(true /* audio */, it->mFinalResult);
            } else {
                it->mNotifyConsumed->post();
            }
        }
        mAudioQueue.erase(mAudioQueue.begin(), itEOS);
    }
}

bool NuPlayer::Renderer::onDrainAudioQueue() {
    // TODO: This call to getPosition checks if AudioTrack has been created
    // in AudioSink before draining audio. If AudioTrack doesn't exist, then
    // CHECKs on getPosition will fail.
    // We still need to figure out why AudioTrack is not created when
    // this function is called. One possible reason could be leftover
    // audio. Another possible place is to check whether decoder
    // has received INFO_FORMAT_CHANGED as the first buffer since
    // AudioSink is opened there, and possible interactions with flush
    // immediately after start. Investigate error message
    // "vorbis_dsp_synthesis returned -135", along with RTSP.
    uint32_t numFramesPlayed;
    if (mAudioSink->getPosition(&numFramesPlayed) != OK) {
        // When getPosition fails, renderer will not reschedule the draining
        // unless new samples are queued.
        // If we have pending EOS (or "eos" marker for discontinuities), we need
        // to post these now as NuPlayerDecoder might be waiting for it.
        drainAudioQueueUntilLastEOS();

        ALOGW("onDrainAudioQueue(): audio sink is not ready");
        return false;
    }

#if 0
    ssize_t numFramesAvailableToWrite =
        mAudioSink->frameCount() - (mNumFramesWritten - numFramesPlayed);
    if (numFramesAvailableToWrite == mAudioSink->frameCount()) {
        ALOGI("audio sink underrun");
    } else {
        ALOGV("audio queue has %d frames left to play",
             mAudioSink->frameCount() - numFramesAvailableToWrite);
    }
#endif

    uint32_t prevFramesWritten = mNumFramesWritten;
    while (!mAudioQueue.empty()) {
        QueueEntry *entry = &*mAudioQueue.begin();

        mLastAudioBufferDrained = entry->mBufferOrdinal;

        if (entry->mBuffer == NULL) {
            // EOS
            int64_t postEOSDelayUs = 0;
            if (mAudioSink->needsTrailingPadding()) {
                postEOSDelayUs = getPendingAudioPlayoutDurationUs(ALooper::GetNowUs());
            }
            notifyEOS(true /* audio */, entry->mFinalResult, postEOSDelayUs);

            mAudioQueue.erase(mAudioQueue.begin());
            entry = NULL;
            if (mAudioSink->needsTrailingPadding()) {
                // If we're not in gapless playback (i.e. through setNextPlayer), we
                // need to stop the track here, because that will play out the last
                // little bit at the end of the file. Otherwise short files won't play.
                mAudioSink->stop();
                mNumFramesWritten = 0;
            }
#ifdef MTK_AOSP_ENHANCEMENT
            mAudioEOS = true;
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
            if(mSMSpeed >1){
                mNeedSync = true;
            }
#endif
            ALOGD("audio position EOS");
            return false;
        }
#ifdef MTK_AOSP_ENHANCEMENT
            mAudioEOS = false;  //set the flag to false when audio is not EOS
#endif
        // ignore 0-sized buffer which could be EOS marker with no data
        if (entry->mOffset == 0 && entry->mBuffer->size() > 0) {
            int64_t mediaTimeUs;
            CHECK(entry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
            ALOGV("onDrainAudioQueue: rendering audio at media time %.2f secs",
                    mediaTimeUs / 1E6);
            onNewAudioMediaTime(mediaTimeUs);
#ifdef MTK_AOSP_ENHANCEMENT
#if DUMP_PROFILE
            dumpProfile("render", mediaTimeUs);
#endif
#endif
        }

        size_t copy = entry->mBuffer->size() - entry->mOffset;

        ssize_t written = mAudioSink->write(entry->mBuffer->data() + entry->mOffset,
                                            copy, false /* blocking */);
        if (written < 0) {
            // An error in AudioSink write. Perhaps the AudioSink was not properly opened.
            if (written == WOULD_BLOCK) {
                ALOGV("AudioSink write would block when writing %zu bytes", copy);
            } else {
                ALOGE("AudioSink write error(%zd) when writing %zu bytes", written, copy);
                notifyAudioTearDown();
            }
            break;
        }

        entry->mOffset += written;
        if (entry->mOffset == entry->mBuffer->size()) {
            entry->mNotifyConsumed->post();
            mAudioQueue.erase(mAudioQueue.begin());

            entry = NULL;
        }

        size_t copiedFrames = written / mAudioSink->frameSize();
        mNumFramesWritten += copiedFrames;

        {
            Mutex::Autolock autoLock(mLock);
            notifyIfMediaRenderingStarted_l();
        }

        if (written != (ssize_t)copy) {
            // A short count was received from AudioSink::write()
            //
            // AudioSink write is called in non-blocking mode.
            // It may return with a short count when:
            //
            // 1) Size to be copied is not a multiple of the frame size. We consider this fatal.
            // 2) The data to be copied exceeds the available buffer in AudioSink.
            // 3) An error occurs and data has been partially copied to the buffer in AudioSink.
            // 4) AudioSink is an AudioCache for data retrieval, and the AudioCache is exceeded.

            // (Case 1)
            // Must be a multiple of the frame size.  If it is not a multiple of a frame size, it
            // needs to fail, as we should not carry over fractional frames between calls.
            CHECK_EQ(copy % mAudioSink->frameSize(), 0);

            // (Case 2, 3, 4)
            // Return early to the caller.
            // Beware of calling immediately again as this may busy-loop if you are not careful.
            ALOGV("AudioSink write short frame count %zd < %zu", written, copy);
            break;
        }
    }
    int64_t maxTimeMedia;
    {
        Mutex::Autolock autoLock(mLock);
        maxTimeMedia =
            mAnchorTimeMediaUs +
                    (int64_t)(max((long long)mNumFramesWritten - mAnchorNumFramesWritten, 0LL)
                            * 1000LL * mAudioSink->msecsPerFrame());
    }
    mMediaClock->updateMaxTimeMedia(maxTimeMedia);

    // calculate whether we need to reschedule another write.
    bool reschedule = !mAudioQueue.empty()
            && (!mPaused
                || prevFramesWritten != mNumFramesWritten); // permit pause to fill buffers
    //ALOGD("reschedule:%d  empty:%d  mPaused:%d  prevFramesWritten:%u  mNumFramesWritten:%u",
    //        reschedule, mAudioQueue.empty(), mPaused, prevFramesWritten, mNumFramesWritten);
    return reschedule;
}

int64_t NuPlayer::Renderer::getDurationUsIfPlayedAtSampleRate(uint32_t numFrames) {
    int32_t sampleRate = offloadingAudio() ?
            mCurrentOffloadInfo.sample_rate : mCurrentPcmInfo.mSampleRate;
    // TODO: remove the (int32_t) casting below as it may overflow at 12.4 hours.
    return (int64_t)((int32_t)numFrames * 1000000LL / sampleRate);
}

// Calculate duration of pending samples if played at normal rate (i.e., 1.0).
int64_t NuPlayer::Renderer::getPendingAudioPlayoutDurationUs(int64_t nowUs) {
    int64_t writtenAudioDurationUs = getDurationUsIfPlayedAtSampleRate(mNumFramesWritten);
    return writtenAudioDurationUs - getPlayedOutAudioDurationUs(nowUs);
}

int64_t NuPlayer::Renderer::getRealTimeUs(int64_t mediaTimeUs, int64_t nowUs) {
    int64_t realUs;
    if (mMediaClock->getRealTimeFor(mediaTimeUs, &realUs) != OK) {
        // If failed to get current position, e.g. due to audio clock is
        // not ready, then just play out video immediately without delay.
        return nowUs;
    }
    return realUs;
}

void NuPlayer::Renderer::onNewAudioMediaTime(int64_t mediaTimeUs) {
    Mutex::Autolock autoLock(mLock);
    // TRICKY: vorbis decoder generates multiple frames with the same
    // timestamp, so only update on the first frame with a given timestamp
    if (mediaTimeUs == mAnchorTimeMediaUs) {
        return;
    }
    setAudioFirstAnchorTimeIfNeeded_l(mediaTimeUs);
    int64_t nowUs = ALooper::GetNowUs();
    int64_t nowMediaUs = mediaTimeUs - getPendingAudioPlayoutDurationUs(nowUs);
    mMediaClock->updateAnchor(nowMediaUs, nowUs, mediaTimeUs);
    mAnchorNumFramesWritten = mNumFramesWritten;
    mAnchorTimeMediaUs = mediaTimeUs;
}

// Called without mLock acquired.
void NuPlayer::Renderer::postDrainVideoQueue() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mDrainVideoQueuePending
            || isSyncQueues()
            || (mPaused && mVideoSampleReceived)) {
        return;
    }
#else
    if (mDrainVideoQueuePending
            || getSyncQueues()
            || (mPaused && mVideoSampleReceived)) {
        return;
    }
#endif

    if (mVideoQueue.empty()) {
        return;
    }

    QueueEntry &entry = *mVideoQueue.begin();

    sp<AMessage> msg = new AMessage(kWhatDrainVideoQueue, this);
    msg->setInt32("drainGeneration", getDrainGeneration(false /* audio */));

    if (entry.mBuffer == NULL) {
        // EOS doesn't carry a timestamp.
        msg->post();
        mDrainVideoQueuePending = true;
        return;
    }

    int64_t delayUs;
    int64_t nowUs = ALooper::GetNowUs();
    int64_t realTimeUs;
    if (mFlags & FLAG_REAL_TIME) {
        int64_t mediaTimeUs;
        CHECK(entry.mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
        realTimeUs = mediaTimeUs;
    } else {
        int64_t mediaTimeUs;
        CHECK(entry.mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));

        {
            Mutex::Autolock autoLock(mLock);
            if (mAnchorTimeMediaUs < 0) {
                mMediaClock->updateAnchor(mediaTimeUs, nowUs, mediaTimeUs);
                mAnchorTimeMediaUs = mediaTimeUs;
                realTimeUs = nowUs;
            } else {
                realTimeUs = getRealTimeUs(mediaTimeUs, nowUs);
            }
        }
#ifdef MTK_AOSP_ENHANCEMENT
        if (!mHasAudio || mAudioEOS) {
            mMediaClock->updateMaxTimeMedia(mediaTimeUs + 100000);
        }
#else
        if (!mHasAudio) {
            // smooth out videos >= 10fps
            mMediaClock->updateMaxTimeMedia(mediaTimeUs + 100000);
        }
#endif

        // Heuristics to handle situation when media time changed without a
        // discontinuity. If we have not drained an audio buffer that was
        // received after this buffer, repost in 10 msec. Otherwise repost
        // in 500 msec.
        delayUs = realTimeUs - nowUs;
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
        if(mSMSpeed >1){
            delayUs /=mSMSpeed;
        }
#endif
        if (delayUs > 500000) {
            int64_t postDelayUs = 500000;
            if (mHasAudio && (mLastAudioBufferDrained - entry.mBufferOrdinal) <= 0) {
                postDelayUs = 10000;
            }
            msg->setWhat(kWhatPostDrainVideoQueue);
            msg->post(postDelayUs);
            mVideoScheduler->restart();
            ALOGI("possible video time jump of %dms, retrying in %dms",
                    (int)(delayUs / 1000), (int)(postDelayUs / 1000));
            mDrainVideoQueuePending = true;
            return;
        }
    }

#ifdef MTK_AOSP_ENHANCEMENT
  if(sFrameAVsyncByVFS){
#endif
    realTimeUs = mVideoScheduler->schedule(realTimeUs * 1000) / 1000;
    int64_t twoVsyncsUs = 2 * (mVideoScheduler->getVsyncPeriod() / 1000);

    delayUs = realTimeUs - nowUs;

    ALOGW_IF(delayUs > 500000, "unusually high delayUs: %" PRId64, delayUs);
    // post 2 display refreshes before rendering is due
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_CLEARMOTION_SUPPORT
    delayUs = realTimeUs - (nowUs + MAX_VIDEO_EARLY_POST_US); //Early queue 50ms
#endif
#endif
    msg->post(delayUs > twoVsyncsUs ? delayUs - twoVsyncsUs : 0);
#ifdef MTK_AOSP_ENHANCEMENT
  }else{
    delayUs -= MAX_VIDEO_EARLY_POST_US;
       msg->post(delayUs);
  }
#endif

    mDrainVideoQueuePending = true;
}

void NuPlayer::Renderer::onDrainVideoQueue() {
    if (mVideoQueue.empty()) {
        return;
    }

    QueueEntry *entry = &*mVideoQueue.begin();

    if (entry->mBuffer == NULL) {
        // EOS

        notifyEOS(false /* audio */, entry->mFinalResult);

        mVideoQueue.erase(mVideoQueue.begin());
        entry = NULL;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
        mVideoEOS = true;
        ALOGD("mVideoEOS:%d",mVideoEOS);
#endif

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
        mMJCPauseDelay = false;
#endif
        setVideoLateByUs(0);
        return;
    }

    int64_t nowUs = -1;
    int64_t realTimeUs;
    if (mFlags & FLAG_REAL_TIME) {
        CHECK(entry->mBuffer->meta()->findInt64("timeUs", &realTimeUs));
    } else {
        int64_t mediaTimeUs;
        CHECK(entry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));

        nowUs = ALooper::GetNowUs();
        realTimeUs = getRealTimeUs(mediaTimeUs, nowUs);
    }

    bool tooLate = false;

    if (!mPaused) {
        if (nowUs == -1) {
            nowUs = ALooper::GetNowUs();
        }
        setVideoLateByUs(nowUs - realTimeUs);
/*        
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
        handleRenderBufferForSlowMotion(entry);
#endif
*/
       tooLate = (mVideoLateByUs > 40000);

#ifdef MTK_AOSP_ENHANCEMENT
             tooLate = (mVideoLateByUs > 250000);
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
        if(mSMSpeed >1){
             tooLate = (mVideoLateByUs/mSMSpeed > 250000);
        }
#endif
#endif
        if (tooLate) {
            ALOGV("video late by %lld us (%.2f secs)",
                 (long long)mVideoLateByUs, mVideoLateByUs / 1E6);
        } else {
            int64_t mediaUs = 0;
            mMediaClock->getMediaTime(realTimeUs, &mediaUs);
            ALOGV("rendering video at media time %.2f secs",
                    (mFlags & FLAG_REAL_TIME ? realTimeUs :
                    mediaUs) / 1E6);
        }
    } else {
        setVideoLateByUs(0);
        if (!mVideoSampleReceived && !mHasAudio) {
            // This will ensure that the first frame after a flush won't be used as anchor
            // when renderer is in paused state, because resume can happen any time after seek.
            Mutex::Autolock autoLock(mLock);
            clearAnchorTime_l();
        }
    }

    entry->mNotifyConsumed->setInt64("timestampNs", realTimeUs * 1000ll);
#ifdef MTK_AOSP_ENHANCEMENT
    if(mLateVideoToDisplay == true)
    tooLate = handleRenderBufferLateInfo(tooLate,realTimeUs,entry);
    ATRACE_BEGIN("RenderVideo");
#endif
    entry->mNotifyConsumed->setInt32("render", !tooLate);
    entry->mNotifyConsumed->post();
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
    handleForClearMotionPause(tooLate,entry);
#endif


    mVideoQueue.erase(mVideoQueue.begin());
    entry = NULL;

    mVideoSampleReceived = true;
#ifdef MTK_AOSP_ENHANCEMENT
    ATRACE_END( );
#endif
    if (!mPaused) {
        if (!mVideoRenderingStarted) {
            mVideoRenderingStarted = true;
            notifyVideoRenderingStart();
        }
        Mutex::Autolock autoLock(mLock);
        notifyIfMediaRenderingStarted_l();
    }
}

void NuPlayer::Renderer::notifyVideoRenderingStart() {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatVideoRenderingStart);
    notify->post();
}

void NuPlayer::Renderer::notifyEOS(bool audio, status_t finalResult, int64_t delayUs) {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatEOS);
    notify->setInt32("audio", static_cast<int32_t>(audio));
    notify->setInt32("finalResult", finalResult);
    notify->post(delayUs);
}

void NuPlayer::Renderer::notifyAudioTearDown() {
    (new AMessage(kWhatAudioTearDown, this))->post();
}

void NuPlayer::Renderer::onQueueBuffer(const sp<AMessage> &msg) {
    int32_t audio;
    CHECK(msg->findInt32("audio", &audio));

    if (dropBufferIfStale(audio, msg)) {
        return;
    }

    if (audio) {
#ifdef MTK_AOSP_ENHANCEMENT
        // when mHasAudio false, maybe some pause or resume action arrival.
        // so when mHasAudio true, should let it have chance to do it.
        // due to all action in one thread run, so need no lock.
        if (!mHasAudio  && mPendingAction != 0) {
            status_t err = OK;
            if (mPendingAction == 1) {
                mAudioSink->pause();
                startAudioOffloadPauseTimeout();
            } else if (mPendingAction == 2) {
                err = mAudioSink->start();
            }
            MM_LOGI("mPendingAction:%d err:%d", mPendingAction, err);
            mPendingAction = 0;
        }
#endif
        mHasAudio = true;
    } else {
        mHasVideo = true;
    }

    if (mHasVideo) {
        if (mVideoScheduler == NULL) {
            mVideoScheduler = new VideoFrameScheduler();
            mVideoScheduler->init();
        }
    }

    sp<ABuffer> buffer;
    CHECK(msg->findBuffer("buffer", &buffer));

    sp<AMessage> notifyConsumed;
    CHECK(msg->findMessage("notifyConsumed", &notifyConsumed));

    QueueEntry entry;
    entry.mBuffer = buffer;
    entry.mNotifyConsumed = notifyConsumed;
    entry.mOffset = 0;
    entry.mFinalResult = OK;
    entry.mBufferOrdinal = ++mTotalBuffersQueued;

    if (audio) {
        Mutex::Autolock autoLock(mLock);
        mAudioQueue.push_back(entry);
        postDrainAudioQueue_l();
    } else {
        mVideoQueue.push_back(entry);
        postDrainVideoQueue();
    }

    Mutex::Autolock autoLock(mLock);
#ifdef MTK_AOSP_ENHANCEMENT
    dumpAudioVideoQueue();
#endif

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
    if((mAudioEOS == true && mVideoEOS != true) || (mAudioEOS != true && mVideoEOS == true)){
        //ALOGD("audio or video is EOS,donot sync queue,mVideoEOS:%d,mAudioEOS:%d",mVideoEOS,mAudioEOS);
        syncQueuesDone_l();
    }
#endif

#ifdef MTK_AOSP_ENHANCEMENT
    if (!isSyncQueues() || mAudioQueue.empty() || mVideoQueue.empty()) {
        return;
    }
#else
    if (!mSyncQueues || mAudioQueue.empty() || mVideoQueue.empty()) {
        return;
    }
#endif

    sp<ABuffer> firstAudioBuffer = (*mAudioQueue.begin()).mBuffer;
    sp<ABuffer> firstVideoBuffer = (*mVideoQueue.begin()).mBuffer;

    if (firstAudioBuffer == NULL || firstVideoBuffer == NULL) {
        // EOS signalled on either queue.
        syncQueuesDone_l();
        return;
    }

    int64_t firstAudioTimeUs;
    int64_t firstVideoTimeUs;
    CHECK(firstAudioBuffer->meta()
            ->findInt64("timeUs", &firstAudioTimeUs));
    CHECK(firstVideoBuffer->meta()
            ->findInt64("timeUs", &firstVideoTimeUs));

    int64_t diff = firstVideoTimeUs - firstAudioTimeUs;

    ALOGV("queueDiff = %.2f secs", diff / 1E6);
    MM_LOGI("queueDiff = %.2f secs", diff / 1E6);

    List<QueueEntry>::iterator it;
    if (diff > 100000ll) {
        // Audio data starts More than 0.1 secs before video.
        // Drop some audio.

        (*mAudioQueue.begin()).mNotifyConsumed->post();
        mAudioQueue.erase(mAudioQueue.begin());
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGD("drop a audio data");
        if(mUseFlushAudioSyncQueues){
            it =  mAudioQueue.begin();
            while(it != mAudioQueue.end()){

                if((*it).mBuffer == NULL){
                     it++;
                }else{
                    CHECK((*it).mBuffer->meta()
                            ->findInt64("timeUs", &firstAudioTimeUs));

                    diff = firstVideoTimeUs - firstAudioTimeUs;
                    if(diff > 100000ll){
                    (*it).mNotifyConsumed->post();
                    it = mAudioQueue.erase(it);
                    ALOGD("drop a audio data %lld us", firstAudioTimeUs);
                    }else{
                      it++;
                    ALOGD("keep a audio data %lld us", firstAudioTimeUs);
                    }
               }
            }
        }
#endif

        return;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    if((diff <  -100000ll) && (mLateVideoToDisplay == true)) {
        // video data starts More than 0.1 secs before audio.
        // Drop some video.
#if defined(MTK_AUDIO_CHANGE_SUPPORT)
        it =  mVideoQueue.begin();
        while(it != mVideoQueue.end()){
            if((*it).mBuffer == NULL){
                it++;
            }else{
                CHECK((*it).mBuffer->meta()->findInt64("timeUs", &firstVideoTimeUs));

                diff = firstVideoTimeUs - firstAudioTimeUs;
                if(diff < -100000ll){
                    (*it).mNotifyConsumed->post();
                    it = mVideoQueue.erase(it);
                    ALOGD("rock still drop a video data %lld us", firstVideoTimeUs);
                }else{
                    it++;
                    ALOGD("rock keep a video data %lld us",firstVideoTimeUs);
                    return;
                }
           }
        }
        return;
#else
        ALOGE("before playback, video is early than audio drop diff = %.2f", diff / 1E6);
        (*mVideoQueue.begin()).mNotifyConsumed->post();
        mVideoQueue.erase(mVideoQueue.begin());
        return;
#endif
    }
#endif

    syncQueuesDone_l();
}

void NuPlayer::Renderer::syncQueuesDone_l() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (!isSyncQueues()) {
        return;
    }
#else
    if (!mSyncQueues) {
        return;
    }
#endif

    mSyncQueues = false;

    if (!mAudioQueue.empty()) {
        postDrainAudioQueue_l();
    }

    if (!mVideoQueue.empty()) {
        mLock.unlock();
        postDrainVideoQueue();
        mLock.lock();
    }
}

void NuPlayer::Renderer::onQueueEOS(const sp<AMessage> &msg) {
    int32_t audio;
    CHECK(msg->findInt32("audio", &audio));

    if (dropBufferIfStale(audio, msg)) {
        return;
    }

    int32_t finalResult;
    CHECK(msg->findInt32("finalResult", &finalResult));

    QueueEntry entry;
    entry.mOffset = 0;
    entry.mFinalResult = finalResult;

    if (audio) {
        Mutex::Autolock autoLock(mLock);
#ifdef MTK_AOSP_ENHANCEMENT
        // ALPS01881347 audio EOS and video all output buffer pending in render
        // should  syncQueuesDone here to render video whether audioQueue empty
        if (/*mAudioQueue.empty() &&*/ isSyncQueues()) {
            syncQueuesDone_l();
        }
#else
        if (mAudioQueue.empty() && mSyncQueues) {
            syncQueuesDone_l();
        }
#endif
        mAudioQueue.push_back(entry);
        postDrainAudioQueue_l();
    } else {
#ifdef MTK_AOSP_ENHANCEMENT
        if (/*mVideoQueue.empty() &&*/ isSyncQueues()) {
            Mutex::Autolock autoLock(mLock);
            syncQueuesDone_l();
        }
#else
        if (mVideoQueue.empty() && getSyncQueues()) {
            Mutex::Autolock autoLock(mLock);
            syncQueuesDone_l();
        }
#endif
        mVideoQueue.push_back(entry);
#ifdef MTK_AOSP_ENHANCEMENT
        if(mPaused)
            mVideoSampleReceived = true;
#endif
        postDrainVideoQueue();
#ifdef MTK_AOSP_ENHANCEMENT
       // ALPS01958589: when four audio output buffer is full and video eos comes
       // then no postDrainAudioQueue_l will be called and audio in render can not drain
       if(!isSyncQueues()){
       postDrainAudioQueue_l();
       }
#endif
    }
}

void NuPlayer::Renderer::onFlush(const sp<AMessage> &msg) {
    int32_t audio, notifyComplete;
    CHECK(msg->findInt32("audio", &audio));

    {
        Mutex::Autolock autoLock(mLock);
        if (audio) {
            notifyComplete = mNotifyCompleteAudio;
            mNotifyCompleteAudio = false;
        } else {
            notifyComplete = mNotifyCompleteVideo;
            mNotifyCompleteVideo = false;
        }

        // If we're currently syncing the queues, i.e. dropping audio while
        // aligning the first audio/video buffer times and only one of the
        // two queues has data, we may starve that queue by not requesting
        // more buffers from the decoder. If the other source then encounters
        // a discontinuity that leads to flushing, we'll never find the
        // corresponding discontinuity on the other queue.
        // Therefore we'll stop syncing the queues if at least one of them
        // is flushed.
        syncQueuesDone_l();
        clearAnchorTime_l();
    }

    ALOGI("flushing %s", audio ? "audio" : "video");
    if (audio) {
        {
            Mutex::Autolock autoLock(mLock);
#ifdef MTK_AOSP_ENHANCEMENT
            //@debug
            dumpQueue(&mAudioQueue, audio);
#endif
            flushQueue(&mAudioQueue);

            ++mAudioDrainGeneration;
            prepareForMediaRenderingStart_l();

            // the frame count will be reset after flush.
            clearAudioFirstAnchorTime_l();
        }

        mDrainAudioQueuePending = false;

        if (offloadingAudio()) {
            mAudioSink->pause();
            mAudioSink->flush();
            if (!mPaused) {
                mAudioSink->start();
            }
        } else {
#ifdef MTK_AOSP_ENHANCEMENT
/* ******************************************
*		 For fast forward timing issue.
*		 NuPlayerRenderer
*		 patch 1/1 of NuPlayerRenderer
********************************************/
            if(mHasAudio){
                mAudioSink->pause();
                mAudioSink->flush();
                // Call stop() to signal to the AudioSink to completely fill the
                // internal buffer before resuming playback.
                mAudioSink->stop();
                if (!mPaused) {
                    mAudioSink->start();
                }
                mNumFramesWritten = 0;
            }
#else
            mAudioSink->pause();
            mAudioSink->flush();
            // Call stop() to signal to the AudioSink to completely fill the
            // internal buffer before resuming playback.
            mAudioSink->stop();
            if (!mPaused) {
                mAudioSink->start();
            }
            mNumFramesWritten = 0;
#endif
        }
    } else {
#ifdef MTK_AOSP_ENHANCEMENT
        //@debug
        dumpQueue(&mVideoQueue, audio);
#endif
        flushQueue(&mVideoQueue);

        mDrainVideoQueuePending = false;

        if (mVideoScheduler != NULL) {
            mVideoScheduler->restart();
        }

        Mutex::Autolock autoLock(mLock);
        ++mVideoDrainGeneration;
        prepareForMediaRenderingStart_l();
    }

    mVideoSampleReceived = false;

    if (notifyComplete) {
        notifyFlushComplete(audio);
    }
}

void NuPlayer::Renderer::flushQueue(List<QueueEntry> *queue) {
    while (!queue->empty()) {
        QueueEntry *entry = &*queue->begin();

        if (entry->mBuffer != NULL) {
            entry->mNotifyConsumed->post();
        }

        queue->erase(queue->begin());
        entry = NULL;
    }
}

void NuPlayer::Renderer::notifyFlushComplete(bool audio) {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatFlushComplete);
    notify->setInt32("audio", static_cast<int32_t>(audio));
    notify->post();
}

bool NuPlayer::Renderer::dropBufferIfStale(
        bool audio, const sp<AMessage> &msg) {
    int32_t queueGeneration;
    CHECK(msg->findInt32("queueGeneration", &queueGeneration));

    if (queueGeneration == getQueueGeneration(audio)) {
        return false;
    }

    sp<AMessage> notifyConsumed;
    if (msg->findMessage("notifyConsumed", &notifyConsumed)) {
        notifyConsumed->post();
    }

    return true;
}

void NuPlayer::Renderer::onAudioSinkChanged() {
    if (offloadingAudio()) {
        return;
    }
    CHECK(!mDrainAudioQueuePending);
    mNumFramesWritten = 0;
    {
        Mutex::Autolock autoLock(mLock);
        mAnchorNumFramesWritten = -1;
    }
    uint32_t written;
    if (mAudioSink->getFramesWritten(&written) == OK) {
        mNumFramesWritten = written;
    }
}

void NuPlayer::Renderer::onDisableOffloadAudio() {
    Mutex::Autolock autoLock(mLock);
    mFlags &= ~FLAG_OFFLOAD_AUDIO;
    ++mAudioDrainGeneration;
    if (mAudioRenderingStartGeneration != -1) {
        prepareForMediaRenderingStart_l();
    }
}

void NuPlayer::Renderer::onEnableOffloadAudio() {
    Mutex::Autolock autoLock(mLock);
    mFlags |= FLAG_OFFLOAD_AUDIO;
    ++mAudioDrainGeneration;
    if (mAudioRenderingStartGeneration != -1) {
        prepareForMediaRenderingStart_l();
    }
}

void NuPlayer::Renderer::onPause() {
    MM_LOGI("mPause:%d, mHasAudio:%d", mPaused, mHasAudio);
    if (mPaused) {
        return;
    }

    {
        Mutex::Autolock autoLock(mLock);
        // we do not increment audio drain generation so that we fill audio buffer during pause.
        ++mVideoDrainGeneration;
        prepareForMediaRenderingStart_l();
        mPaused = true;
        mMediaClock->setPlaybackRate(0.0);
    }

    mDrainAudioQueuePending = false;
    mDrainVideoQueuePending = false;

    if (mHasAudio) {
        mAudioSink->pause();
        startAudioOffloadPauseTimeout();
    }

#ifdef MTK_AOSP_ENHANCEMENT
    else {
        mPendingAction = 1;     // pause is pending
    }
#endif
    ALOGV("now paused audio queue has %zu entries, video has %zu entries",
          mAudioQueue.size(), mVideoQueue.size());
}

void NuPlayer::Renderer::onResume() {
    MM_LOGI("mPause:%d, mHasAudio:%d", mPaused, mHasAudio);
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
      readProperties();

    if( mPausing) {
        mPausing = false;
        return;
    }
#endif
    if (!mPaused) {
        return;
    }

    if (mHasAudio) {
        cancelAudioOffloadPauseTimeout();
        status_t err = mAudioSink->start();
        if (err != OK) {
#ifdef MTK_AOSP_ENHANCEMENT
            // not offload case, check error type, if is invalid operation
            // do not notifyAudioTearDown, due to repeat start audioTrack
            if (!offloadingAudio() && err == INVALID_OPERATION) {
                MM_LOGI("no offload mode mAudioSink->start() repeatly err:%d", err);
            } else
#endif
            notifyAudioTearDown();
        }
        MM_LOGI("mAudioSink->start()");
    }
#ifdef MTK_AOSP_ENHANCEMENT
    else {
        mPendingAction = 2;        // start is pending
    }
#endif

    {
        Mutex::Autolock autoLock(mLock);
        mPaused = false;

#ifdef MTK_AOSP_ENHANCEMENT
        //handle pause then seekto eos, music turn to play next song issue part 3/3
        mDrainAudioQueuePending = false;
#endif
        // configure audiosink as we did not do it when pausing
        if (mAudioSink != NULL && mAudioSink->ready()) {
            mAudioSink->setPlaybackRate(mPlaybackSettings);
        }

        mMediaClock->setPlaybackRate(mPlaybackRate);
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
//      mSMSpeed = mPauseSpeed;
//      mPlaybackRate = 1/(float)mSMSpeed;
        if(mAudioSink!=NULL && mAudioSink->ready()){
            if(mSMSpeed == 1)
                mAudioSink->setParameters(String8("time_stretch=100"));
            else if(mSMSpeed == 2)
                mAudioSink->setParameters(String8("time_stretch=200"));
            else if(mSMSpeed == 4)
                mAudioSink->setParameters(String8("time_stretch=400"));
            else if(mSMSpeed == 8)
                mAudioSink->setParameters(String8("time_stretch=800"));
            else if(mSMSpeed == 16)
                mAudioSink->setParameters(String8("time_stretch=1600"));
            else if(mSMSpeed == 32)
                mAudioSink->setParameters(String8("time_stretch=3200"));
            extern AudioTrackCenter gAudioTrackCenter;
            gAudioTrackCenter.setTimeStretch((uint32_t)mSMSpeed);
        }
        if(mSMSpeed > 1){
            mMediaClock->setPlaybackRate(1/(float)mSMSpeed);
        }
#endif

        if (!mAudioQueue.empty()) {
            postDrainAudioQueue_l();
        }
    }

    if (!mVideoQueue.empty()) {
        postDrainVideoQueue();
    }
}

void NuPlayer::Renderer::onSetVideoFrameRate(float fps) {
    if (mVideoScheduler == NULL) {
        mVideoScheduler = new VideoFrameScheduler();
    }
    mVideoScheduler->init(fps);
}

int32_t NuPlayer::Renderer::getQueueGeneration(bool audio) {
    Mutex::Autolock autoLock(mLock);
    return (audio ? mAudioQueueGeneration : mVideoQueueGeneration);
}

int32_t NuPlayer::Renderer::getDrainGeneration(bool audio) {
    Mutex::Autolock autoLock(mLock);
    return (audio ? mAudioDrainGeneration : mVideoDrainGeneration);
}

bool NuPlayer::Renderer::getSyncQueues() {
    Mutex::Autolock autoLock(mLock);
    return mSyncQueues;
}

// TODO: Remove unnecessary calls to getPlayedOutAudioDurationUs()
// as it acquires locks and may query the audio driver.
//
// Some calls could conceivably retrieve extrapolated data instead of
// accessing getTimestamp() or getPosition() every time a data buffer with
// a media time is received.
//
// Calculate duration of played samples if played at normal rate (i.e., 1.0).
int64_t NuPlayer::Renderer::getPlayedOutAudioDurationUs(int64_t nowUs) {
#ifdef MTK_AOSP_ENHANCEMENT
#if USE_AUDIO_TRACK_CENTER
    if (!offloadingAudio()) {
        return  getPlayedOutAudioDurationUsByAudioTrackCenter();
    }
#endif
#endif


    uint32_t numFramesPlayed;
    int64_t numFramesPlayedAt;
    AudioTimestamp ts;
    static const int64_t kStaleTimestamp100ms = 100000;

    status_t res = mAudioSink->getTimestamp(ts);
    if (res == OK) {                 // case 1: mixing audio tracks and offloaded tracks.
        numFramesPlayed = ts.mPosition;
        numFramesPlayedAt =
            ts.mTime.tv_sec * 1000000LL + ts.mTime.tv_nsec / 1000;
        const int64_t timestampAge = nowUs - numFramesPlayedAt;
        if (timestampAge > kStaleTimestamp100ms) {
            // This is an audio FIXME.
            // getTimestamp returns a timestamp which may come from audio mixing threads.
            // After pausing, the MixerThread may go idle, thus the mTime estimate may
            // become stale. Assuming that the MixerThread runs 20ms, with FastMixer at 5ms,
            // the max latency should be about 25ms with an average around 12ms (to be verified).
            // For safety we use 100ms.
            ALOGV("getTimestamp: returned stale timestamp nowUs(%lld) numFramesPlayedAt(%lld)",
                    (long long)nowUs, (long long)numFramesPlayedAt);
            numFramesPlayedAt = nowUs - kStaleTimestamp100ms;
        }
        //ALOGD("getTimestamp: OK %d %lld", numFramesPlayed, (long long)numFramesPlayedAt);
    } else if (res == WOULD_BLOCK) { // case 2: transitory state on start of a new track
        numFramesPlayed = 0;
        numFramesPlayedAt = nowUs;
        //ALOGD("getTimestamp: WOULD_BLOCK %d %lld",
        //        numFramesPlayed, (long long)numFramesPlayedAt);
    } else {                         // case 3: transitory at new track or audio fast tracks.
        res = mAudioSink->getPosition(&numFramesPlayed);
        CHECK_EQ(res, (status_t)OK);
        numFramesPlayedAt = nowUs;
        numFramesPlayedAt += 1000LL * mAudioSink->latency() / 2; /* XXX */
        //ALOGD("getPosition: %u %lld", numFramesPlayed, (long long)numFramesPlayedAt);
    }

    //CHECK_EQ(numFramesPlayed & (1 << 31), 0);  // can't be negative until 12.4 hrs, test
    int64_t durationUs = getDurationUsIfPlayedAtSampleRate(numFramesPlayed)
            + nowUs - numFramesPlayedAt;
    if (durationUs < 0) {
        // Occurs when numFramesPlayed position is very small and the following:
        // (1) In case 1, the time nowUs is computed before getTimestamp() is called and
        //     numFramesPlayedAt is greater than nowUs by time more than numFramesPlayed.
        // (2) In case 3, using getPosition and adding mAudioSink->latency() to
        //     numFramesPlayedAt, by a time amount greater than numFramesPlayed.
        //
        // Both of these are transitory conditions.
        ALOGV("getPlayedOutAudioDurationUs: negative duration %lld set to zero", (long long)durationUs);
        durationUs = 0;
    }
    ALOGV("getPlayedOutAudioDurationUs(%lld) nowUs(%lld) frames(%u) framesAt(%lld)",
            (long long)durationUs, (long long)nowUs, numFramesPlayed, (long long)numFramesPlayedAt);
    return durationUs;
}

void NuPlayer::Renderer::onAudioTearDown(AudioTearDownReason reason) {
    if (mAudioTornDown) {
        return;
    }
    mAudioTornDown = true;

    int64_t currentPositionUs;
    sp<AMessage> notify = mNotify->dup();
    if (getCurrentPosition(&currentPositionUs) == OK) {
        notify->setInt64("positionUs", currentPositionUs);
    }

    mAudioSink->stop();
    mAudioSink->flush();

    notify->setInt32("what", kWhatAudioTearDown);
    notify->setInt32("reason", reason);
    notify->post();
}

void NuPlayer::Renderer::startAudioOffloadPauseTimeout() {
    if (offloadingAudio()) {
        mWakeLock->acquire();
        sp<AMessage> msg = new AMessage(kWhatAudioOffloadPauseTimeout, this);
        msg->setInt32("drainGeneration", mAudioOffloadPauseTimeoutGeneration);
        msg->post(kOffloadPauseMaxUs);
    }
}

void NuPlayer::Renderer::cancelAudioOffloadPauseTimeout() {
    if (offloadingAudio()) {
        mWakeLock->release(true);
        ++mAudioOffloadPauseTimeoutGeneration;
    }
}

status_t NuPlayer::Renderer::onOpenAudioSink(
        const sp<AMessage> &format,
        bool offloadOnly,
        bool hasVideo,
        uint32_t flags) {
    ALOGV("openAudioSink: offloadOnly(%d) offloadingAudio(%d)",
            offloadOnly, offloadingAudio());

#ifdef MTK_AOSP_ENHANCEMENT

/* ******************************************
*		 For mp3 and ape low power
*		 NuPlayerDecoder & NuPlayerRenderer
*		 patch 1/2 of NuPlayerRenderer
********************************************/
    mIsMP3orAPE = 0;
    if(format->findInt32("is-mp3-or-ape", &mIsMP3orAPE)&& mIsMP3orAPE == 1)
    {
        ALOGD("It's MP3 or APE playback, disable rtsp frequency postDrainAudioQueue");
    }
#endif

    bool audioSinkChanged = false;

    int32_t numChannels;
    CHECK(format->findInt32("channel-count", &numChannels));

    int32_t channelMask;
    if (!format->findInt32("channel-mask", &channelMask)) {
        // signal to the AudioSink to derive the mask from count.
        channelMask = CHANNEL_MASK_USE_CHANNEL_ORDER;
    }

    int32_t sampleRate;
    CHECK(format->findInt32("sample-rate", &sampleRate));

    if (offloadingAudio()) {
        audio_format_t audioFormat = AUDIO_FORMAT_PCM_16_BIT;
        AString mime;
        CHECK(format->findString("mime", &mime));
        status_t err = mapMimeToAudioFormat(audioFormat, mime.c_str());

        if (err != OK) {
            ALOGE("Couldn't map mime \"%s\" to a valid "
                    "audio_format", mime.c_str());
            onDisableOffloadAudio();
        } else {
            ALOGV("Mime \"%s\" mapped to audio_format 0x%x",
                    mime.c_str(), audioFormat);

            int avgBitRate = -1;
            format->findInt32("bit-rate", &avgBitRate);

            int32_t aacProfile = -1;
            if (audioFormat == AUDIO_FORMAT_AAC
                    && format->findInt32("aac-profile", &aacProfile)) {
                // Redefine AAC format as per aac profile
                mapAACProfileToAudioFormat(
                        audioFormat,
                        aacProfile);
            }

            audio_offload_info_t offloadInfo = AUDIO_INFO_INITIALIZER;
            offloadInfo.duration_us = -1;
            format->findInt64(
                    "durationUs", &offloadInfo.duration_us);
            offloadInfo.sample_rate = sampleRate;
            offloadInfo.channel_mask = channelMask;
            offloadInfo.format = audioFormat;
            offloadInfo.stream_type = AUDIO_STREAM_MUSIC;
            offloadInfo.bit_rate = avgBitRate;
            offloadInfo.has_video = hasVideo;
            offloadInfo.is_streaming = true;

            if (memcmp(&mCurrentOffloadInfo, &offloadInfo, sizeof(offloadInfo)) == 0) {
                ALOGV("openAudioSink: no change in offload mode");
                // no change from previous configuration, everything ok.
                return OK;
            }
            mCurrentPcmInfo = AUDIO_PCMINFO_INITIALIZER;

            ALOGV("openAudioSink: try to open AudioSink in offload mode");
            uint32_t offloadFlags = flags;
            offloadFlags |= AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD;
            offloadFlags &= ~AUDIO_OUTPUT_FLAG_DEEP_BUFFER;
            audioSinkChanged = true;
            mAudioSink->close();

            err = mAudioSink->open(
                    sampleRate,
                    numChannels,
                    (audio_channel_mask_t)channelMask,
                    audioFormat,
                    0 /* bufferCount - unused */,
                    &NuPlayer::Renderer::AudioSinkCallback,
                    this,
                    (audio_output_flags_t)offloadFlags,
                    &offloadInfo);

            if (err == OK) {
                err = mAudioSink->setPlaybackRate(mPlaybackSettings);
            }

            if (err == OK) {
                // If the playback is offloaded to h/w, we pass
                // the HAL some metadata information.
                // We don't want to do this for PCM because it
                // will be going through the AudioFlinger mixer
                // before reaching the hardware.
                // TODO
                mCurrentOffloadInfo = offloadInfo;
                if (!mPaused) { // for preview mode, don't start if paused
                    err = mAudioSink->start();
                }
                ALOGV_IF(err == OK, "openAudioSink: offload succeeded");
            }
            if (err != OK) {
                // Clean up, fall back to non offload mode.
                mAudioSink->close();
                onDisableOffloadAudio();
                mCurrentOffloadInfo = AUDIO_INFO_INITIALIZER;
                ALOGV("openAudioSink: offload failed");
            } else {
                mUseAudioCallback = true;  // offload mode transfers data through callback
                ++mAudioDrainGeneration;  // discard pending kWhatDrainAudioQueue message.
            }
        }
    }
    if (!offloadOnly && !offloadingAudio()) {
        ALOGV("openAudioSink: open AudioSink in NON-offload mode");

#ifdef MTK_AOSP_ENHANCEMENT
    int32_t change = 0;
       if(format->findInt32("change",&change) && change ==1){
            if( (!audioFormatChange(format))
                #if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
                && (!mNoJudgeWhenChangeAudio)  //for ALPS01940787,not return OK to ensure call mAudioSink->open function
                #endif
                )  return OK;
       }
#endif

        uint32_t pcmFlags = flags;
        pcmFlags &= ~AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD;

        const PcmInfo info = {
                (audio_channel_mask_t)channelMask,
                (audio_output_flags_t)pcmFlags,
                AUDIO_FORMAT_PCM_16_BIT, // TODO: change to audioFormat
                numChannels,
                sampleRate
        };
#ifdef MTK_AOSP_ENHANCEMENT
        if (memcmp(&mCurrentPcmInfo, &info, sizeof(info)) == 0) {
                ALOGV("openAudioSink: no change in pcm mode");
    #if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
                if (!mNoJudgeWhenChangeAudio) //for ALPS01940787,not return OK to ensure call mAudioSink->open function
    #endif
                return OK;
        }
#else
        if (memcmp(&mCurrentPcmInfo, &info, sizeof(info)) == 0) {
            ALOGV("openAudioSink: no change in pcm mode");
            // no change from previous configuration, everything ok.
            return OK;
        }
#endif

        audioSinkChanged = true;
        mAudioSink->close();
        mCurrentOffloadInfo = AUDIO_INFO_INITIALIZER;
        // Note: It is possible to set up the callback, but not use it to send audio data.
        // This requires a fix in AudioSink to explicitly specify the transfer mode.
        mUseAudioCallback = getUseAudioCallbackSetting();
        if (mUseAudioCallback) {
            ++mAudioDrainGeneration;  // discard pending kWhatDrainAudioQueue message.
        }

        // Compute the desired buffer size.
        // For callback mode, the amount of time before wakeup is about half the buffer size.
        const uint32_t frameCount =
                (unsigned long long)sampleRate * getAudioSinkPcmMsSetting() / 1000;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)
        int32_t bitWidth = 0;
        audio_format_t audioFormat = AUDIO_FORMAT_PCM_16_BIT;
        if (format->findInt32("bit-width", &bitWidth) && bitWidth > 16) {
            ALOGI("bits width: %d, NuPlayer use high resolution audiotrack.", bitWidth);
            audioFormat = AUDIO_FORMAT_PCM_8_24_BIT;
        }
        status_t err = mAudioSink->open(
                    sampleRate,
                    numChannels,
                    (audio_channel_mask_t)channelMask,
                    audioFormat,
                    0 /* bufferCount - unused */,
                    mUseAudioCallback ? &NuPlayer::Renderer::AudioSinkCallback : NULL,
                    mUseAudioCallback ? this : NULL,
                    (audio_output_flags_t)pcmFlags,
                    NULL,
                    true /* doNotReconnect */,
                    frameCount);
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
        mNoJudgeWhenChangeAudio = false;
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
        if(mAudioSink!=NULL){
            if(mSMSpeed == 1)
                mAudioSink->setParameters(String8("time_stretch=100"));
            else if(mSMSpeed == 2)
                mAudioSink->setParameters(String8("time_stretch=200"));
            else if(mSMSpeed == 4)
                mAudioSink->setParameters(String8("time_stretch=400"));
            else if(mSMSpeed == 8)
                mAudioSink->setParameters(String8("time_stretch=800"));
            else if(mSMSpeed == 16)
                mAudioSink->setParameters(String8("time_stretch=1600"));
            else if(mSMSpeed == 32)
                mAudioSink->setParameters(String8("time_stretch=3200"));

            extern AudioTrackCenter gAudioTrackCenter;
            gAudioTrackCenter.setTimeStretch((uint32_t)mSMSpeed);
        }else {
            ALOGW("mAudioSink==NULL");
        }
        mMediaClock->setPlaybackRate(1/(float)mSMSpeed);
#endif
#else
        status_t err = mAudioSink->open(
                    sampleRate,
                    numChannels,
                    (audio_channel_mask_t)channelMask,
                    AUDIO_FORMAT_PCM_16_BIT,
                    0 /* bufferCount - unused */,
                    mUseAudioCallback ? &NuPlayer::Renderer::AudioSinkCallback : NULL,
                    mUseAudioCallback ? this : NULL,
                    (audio_output_flags_t)pcmFlags,
                    NULL,
                    true /* doNotReconnect */,
                    frameCount);

#endif
        if (err == OK) {
            err = mAudioSink->setPlaybackRate(mPlaybackSettings);
        }
        if (err != OK) {
            ALOGW("openAudioSink: non offloaded open failed status: %d", err);
            mCurrentPcmInfo = AUDIO_PCMINFO_INITIALIZER;
            return err;
        }
        mCurrentPcmInfo = info;
        MM_LOGI("openAudioSink: pause:%d ", mPaused);
        if (!mPaused) { // for preview mode, don't start if paused
            mAudioSink->start();
        }
    }
    if (audioSinkChanged) {
        onAudioSinkChanged();
    }
    mAudioTornDown = false;
    return OK;
}

void NuPlayer::Renderer::onCloseAudioSink() {
    mAudioSink->close();
    mCurrentOffloadInfo = AUDIO_INFO_INITIALIZER;
    mCurrentPcmInfo = AUDIO_PCMINFO_INITIALIZER;
}


#ifdef MTK_AOSP_ENHANCEMENT

void NuPlayer::Renderer::readProperties() {
    char sync[PROPERTY_VALUE_MAX];
    if (property_get("persist.sys.media.vfs", sync, NULL)) {
        sFrameAVsyncByVFS =
            !strcmp("1", sync) || !strcasecmp("true", sync);
    }
    ALOGI("sFrameAVsyncByVFS %d",sFrameAVsyncByVFS);

    char value[PROPERTY_VALUE_MAX];   // only debug
    if (property_get("dump.queuebuffer.maq.mvq", value, NULL)) {
        sDebugDumpmAQmVQ = atoi(value);
    } else {
        sDebugDumpmAQmVQ = 0;
    }
    ALOGI("sDebugDumpmAQmVQ = %d", sDebugDumpmAQmVQ);
}


void NuPlayer::Renderer::init_ext(){
    mPendingAction = 0;
#ifdef MTK_CLEARMOTION_SUPPORT
      mPausing = false;
      mMJCPauseDelay = false;
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
      mSMSpeed= 1;
      mPreSMSpeed = 1;
//    mPauseSpeed = 1;
      mNeedSync = false;
      mSMSynctime = -1;
#endif
   mUseSyncQueues = true;
    mLateVideoToDisplay = true;
    mUseFlushAudioSyncQueues = false;
    mAudioEOS = false;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT)
    mVideoEOS= false;
    mNoJudgeWhenChangeAudio = false;//for ALPS01940787
#endif
    mAudioFlushed = false;
    readProperties();
    mBufferingStartTimeRealUs = -1;
}
void NuPlayer::Renderer::notifyBufferingStart() {
    if (!mHasAudio) {
        mBufferingStartTimeRealUs = ALooper::GetNowUs();
    }
}

void NuPlayer::Renderer::notifyBufferingEnd() {
    if (!mHasAudio) {
        if (mBufferingStartTimeRealUs != -1) {
            mMediaClock->addBufferingTimeToAnchorTimeRealUs(ALooper::GetNowUs() - mBufferingStartTimeRealUs);
        }
        mBufferingStartTimeRealUs = -1;
    }
}

void NuPlayer::Renderer::setUseSyncQueues(bool use) {
    mUseSyncQueues = use;
}

void NuPlayer::Renderer::setUseFlushAudioSyncQueues(bool use)
{
    ALOGD("set flush audio sync queue %d", use);
    mUseFlushAudioSyncQueues = use;
}
bool NuPlayer::Renderer::isSyncQueues() {
    return mUseSyncQueues && mSyncQueues;
}

//enable or disable when video is late, display one ,drop one or drop all late frames.
void NuPlayer::Renderer::setLateVideoToDisplay(bool display) {
    mLateVideoToDisplay = display;
    ALOGD("setLateVideoToDisplay = %d", mLateVideoToDisplay);
}

void NuPlayer::Renderer::dumpQueue(List<QueueEntry> *queue, bool audio) {
    List<QueueEntry>::iterator it = queue->begin();
    ALOGD("dumping current %s queue(%d fs)", audio ? "audio" : "video", queue->size());
    while (it != queue->end()) {
        QueueEntry *entry = &*it;
        if (entry->mBuffer != NULL) {
            int64_t mediaTimeUs = 0;
            CHECK(entry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
            ALOGD("\t\t (%.2f secs)",  mediaTimeUs / 1E6);
        } else {
            ALOGD("\t\t (null)");
        }
        it++;
    }

}

void NuPlayer::Renderer::dumpProfile(const char* tag, int64_t timeUs) {
    ALOGD("[dump] %s %s %.2f", "audio", tag, timeUs / 1E6);
}

void NuPlayer::Renderer::dumpBuffer(const char* fileName, char* p, size_t size) {
    FILE *fp;
    fp = fopen(fileName, "a+");
    if (fp == NULL) {
        ALOGE("error when create dump file %s", fileName);
        return;
    }
    fwrite(p, sizeof(char), size, fp);
    fclose(fp);

}
int64_t NuPlayer::Renderer::getAudioPendingPlayoutUsByAudioTrackCenter() {
        uint32_t numFramesPlayed;
        numFramesPlayed = getNumFramesPlayedByAudioTrackCenter();
        uint32_t numFramesPendingPlayout = mNumFramesWritten - numFramesPlayed;
        if(mPlaybackRate > 0){
            return numFramesPendingPlayout * mPlaybackRate * mAudioSink->msecsPerFrame() * 1000;
        }else{
            return numFramesPendingPlayout * mAudioSink->msecsPerFrame() * 1000;
        }
}
uint32_t NuPlayer::Renderer::getNumFramesPlayedByAudioTrackCenter() {
            uint32_t numFramesPlayed;
      extern AudioTrackCenter gAudioTrackCenter;
      intptr_t trackId = 0;
      static int64_t lastPlayedUs = 0;
      static int64_t lastNowUs = 0;

      trackId = gAudioTrackCenter.getTrackId(NULL, mAudioSink.get());
      if (trackId) {
          int64_t framePlayed = 0;
          CHECK_EQ(gAudioTrackCenter.getRealTimePosition(trackId, &framePlayed), (status_t)OK);
          if (framePlayed > 0xffffffff)
              ALOGW("warning!!!, getRealTimePosition framePlayed = %lld", framePlayed);
          numFramesPlayed = (uint32_t)framePlayed;
      } else {
          CHECK_EQ(mAudioSink->getPosition(&numFramesPlayed), (status_t)OK);
      }
      int64_t nowPlayedUs = 0;
      if(mPlaybackRate > 0){
        nowPlayedUs = (int64_t)(((int64_t)numFramesPlayed)* mPlaybackRate * mAudioSink->msecsPerFrame()*1000ll);
      }else{
        nowPlayedUs = (int64_t)(((int64_t)numFramesPlayed)* mAudioSink->msecsPerFrame()*1000ll);
      }

      int64_t nowUs =  ALooper::GetNowUs();
      ALOGD("audio played time(%lld us), system time(%lld us),[S-A] (%lld ms)",
            (long long)nowPlayedUs, (long long)nowUs,
            (long long)((nowUs - lastNowUs) - (nowPlayedUs - lastPlayedUs)) / 1000ll);
        if (numFramesPlayed > mNumFramesWritten) {
            numFramesPlayed = mNumFramesWritten;
            ALOGW("numFramesPlayed(%dus) > mNumFramesWritten(%dus), reset numFramesPlayed",
                    numFramesPlayed, mNumFramesWritten);
        }

      if(lastPlayedUs > 0 && lastNowUs >0){
              ATRACE_INT64("System-Audio[ms]",((nowUs-lastNowUs) - (nowPlayedUs-lastPlayedUs))/1000ll);
      }
      lastPlayedUs= nowPlayedUs;
      lastNowUs = nowUs;

        return numFramesPlayed;

}

int64_t  NuPlayer::Renderer::getPlayedOutAudioDurationUsByAudioTrackCenter() {
    uint32_t numFramesPlayed;
    numFramesPlayed = getNumFramesPlayedByAudioTrackCenter();
    if(mPlaybackRate > 0){
        return  (int64_t)(((int64_t)numFramesPlayed)* mPlaybackRate *mAudioSink->msecsPerFrame()*1000ll);
    }else{
        return  (int64_t)(((int64_t)numFramesPlayed)* mAudioSink->msecsPerFrame()*1000ll);
    }
}

bool NuPlayer::Renderer::handleRenderBufferLateInfo(bool tooLate,int64_t realTimeUs,QueueEntry *processBufferEntry){
    // if preformance not ok, show one ,then drop one
    int64_t mediaTimeUs;
    CHECK(processBufferEntry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
    static int32_t SinceLastDropped = 0;
    static int64_t lastRealTimeUs = -1;
    if(tooLate)
    {
        if (SinceLastDropped > 0)
        {
            //drop
            ALOGE("we're late dropping one timeUs %lld ms after %d frames",mediaTimeUs/1000ll,SinceLastDropped);
            SinceLastDropped = 0;
        }else{
            //not drop
            tooLate = false;
            SinceLastDropped ++;
        }
    }else{
        SinceLastDropped ++;
    }
    if(!sFrameAVsyncByVFS){
        processBufferEntry->mNotifyConsumed->setInt64("realtimeus", realTimeUs);
        processBufferEntry->mNotifyConsumed->setInt64("delaytimeus", -mVideoLateByUs);
    }
    int64_t currentPositionUs;
    if (getCurrentPosition(&currentPositionUs) != OK) {
        currentPositionUs = 0;
    }
    processBufferEntry->mNotifyConsumed->setInt64("AvSyncRefTimeUs", currentPositionUs);
    ALOGD("[%s buffer] ACodec delay time(%lld us), video mediaTimeUs(%lld us), realtimeUs(%lld us) ,not Render %d",
        (mVideoLateByUs > 0)?"late":"early",-mVideoLateByUs, mediaTimeUs, realTimeUs,tooLate);

   if(lastRealTimeUs > 0){
    ATRACE_INT64("realTimeDelta",(realTimeUs-lastRealTimeUs)/1000ll);
   }
   lastRealTimeUs = realTimeUs;
   return tooLate;

}

void NuPlayer::Renderer::handleForClearMotionPause(bool tooLate,QueueEntry *processBufferEntry){
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
    int32_t isInterpolateFrame = 0;
    if(!processBufferEntry->mBuffer->meta()->findInt32("interpolateframe", &isInterpolateFrame)) {
        ALOGV("no key:interpolateframe in meta");
        isInterpolateFrame = 0;
    }

    if(mPausing) {
        ALOGD("rendering frame when pausing, tooLate(%s), InterpolateFrame(%s)",
                tooLate?"true":"false", isInterpolateFrame?"true":"false");
    }

    if( !tooLate ) {
        if( isInterpolateFrame )
            mMJCPauseDelay = true;
        else {
            mMJCPauseDelay = false;
            if(mPausing) {
                mPausing = false;
                onPause();
                ALOGI("paused after rendering an uninterpolated frame");
            }
        }
    }
#else
    tooLate = false;
    processBufferEntry = NULL;
#endif
}

void NuPlayer::Renderer::setFlags(uint32_t flag, bool setting) {
    if(flag & FLAG_HAS_VIDEO_AUDIO) {
        if (setting) {
            mSyncQueues = true;
            ALOGI("turn on sync queue ");
        } else {
            mSyncQueues = false;
            ALOGI("turn off sync queue ");
        }
   }
}

bool NuPlayer::Renderer::audioFormatChange(sp<AMessage> format){

    bool audioSinkChanged = false;
    int32_t numChannels;

    CHECK(format->findInt32("channel-count", &numChannels));

    int32_t channelMask;
    if (!format->findInt32("channel-mask", &channelMask)) {
                // signal to the AudioSink to derive the mask from count.
                channelMask = CHANNEL_MASK_USE_CHANNEL_ORDER;
    }

    int32_t sampleRate;
    CHECK(format->findInt32("sample-rate", &sampleRate));



    int32_t bitWidth = 0;
    audio_format_t audioFormat = AUDIO_FORMAT_PCM_16_BIT;
    if (format->findInt32("bit-width", &bitWidth) && bitWidth > 16) {
                ALOGI("bits width: %d, NuPlayer use high resolution audiotrack.", bitWidth);
                audioFormat = AUDIO_FORMAT_PCM_8_24_BIT;
    }

    if((mAudioSink->getSampleRate() != (uint32_t)sampleRate)
                    ||(mAudioSink->channelCount() != (ssize_t)numChannels) ) {
        ALOGD("samplerate, channelcount differ: %u/%u Hz, %u/%d ch",
          mAudioSink->getSampleRate(), sampleRate,
          mAudioSink->channelCount(), numChannels);
        audioSinkChanged = true;
    }

    return audioSinkChanged;


}
#if  defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_CLEARMOTION_SUPPORT)
bool NuPlayer::Renderer::onPauseForClearMotion(const sp<AMessage>&msg){

        int32_t forcePause = 0;
        if(msg->findInt32("force-pause", &forcePause) && forcePause == 1) {
            if(!mPausing)
                return true;
            ALOGI("delay time arrived, try to force pause");
            mPausing = false;
            mMJCPauseDelay = false;

        }

        if (mPaused || mPausing) {
            ALOGW("NuPlayer::Renderer::onPause already paused or in pausing(%d) state", mPausing);
            return true;
        }

        if( mMJCPauseDelay && !mPausing) {
            mPausing = true;
            ALOGI("need pause delay for MJC");
            msg->setInt32("force-pause", 1);
            msg->post(300000);
            return true;
        }
        return false;

}
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
status_t NuPlayer::Renderer::setsmspeed(int32_t speed){
    ALOGD("renderer set speed = %d",speed);
/*    
    if(speed != mSMSpeed){
        mNeedSync = true;
        if(mPaused){
            mPauseSpeed = speed;
        }else{
            mPauseSpeed = speed;
            mSMSpeed = speed;
        }
    }
*/
    mSMSpeed = speed;
//  mPauseSpeed = speed;
    if(mMediaClock != NULL && !mPaused){
        mMediaClock->setPlaybackRate(1/(float)speed);
    }

    if(mAudioSink!=NULL && !mPaused){
        if(mSMSpeed == 1)
            mAudioSink->setParameters(String8("time_stretch=100"));
        else if(mSMSpeed == 2)
            mAudioSink->setParameters(String8("time_stretch=200"));
        else if(mSMSpeed == 4)
            mAudioSink->setParameters(String8("time_stretch=400"));
        else if(mSMSpeed == 8)
            mAudioSink->setParameters(String8("time_stretch=800"));
        else if(mSMSpeed == 16)
            mAudioSink->setParameters(String8("time_stretch=1600"));
        else if(mSMSpeed == 32)
            mAudioSink->setParameters(String8("time_stretch=3200"));

        extern AudioTrackCenter gAudioTrackCenter;
        gAudioTrackCenter.setTimeStretch((uint32_t)mSMSpeed);
        return OK;
    }else {
        ALOGW("mAudioSink==NULL");
        return NO_INIT;
    }
}
/*
void NuPlayer::Renderer::handleRenderBufferForSlowMotion(QueueEntry *processBufferEntry){
    int64_t mediaTimeUs;
    CHECK(processBufferEntry->mBuffer->meta()->findInt64("timeUs", &mediaTimeUs));
    if((mNeedSync)&& ((!mHasAudio)||(mAudioEOS))){
        mMediaClock->updateAnchor(mediaTimeUs, ALooper::GetNowUs(), INT64_MAX);
        mNeedSync = false;
        mPreSMSpeed = mSMSpeed;
    }
    if((mSMSpeed >0)&&(!mHasAudio||mAudioEOS)&&(!mNeedSync)){
        mVideoLateByUs = ALooper::GetNowUs()- getRealTimeUs(mediaTimeUs, ALooper::GetNowUs());
    }
}
*/
#endif

#ifdef MTK_AUDIO_CHANGE_SUPPORT
void NuPlayer::Renderer::changeAudio(){
   mNoJudgeWhenChangeAudio = true; //for ALPS01940787
}
#endif

void NuPlayer::Renderer::dumpAudioVideoQueue() {
    // +dump mAudioQueue & mVideoQueue
    if (sDebugDumpmAQmVQ == 1) {
        List<QueueEntry>::iterator it;
        int64_t tempTimeUs;
        ALOGI("+++++ dump mVideoQueue +++++");
        for (it = mVideoQueue.begin(); it != mVideoQueue.end(); it++) {
            if ((*it).mBuffer == NULL) {
                ALOGI("mBuffer == NULL");
            } else {
                (*it).mBuffer->meta()->findInt64("timeUs",&tempTimeUs);
                ALOGI("ts = %lld", tempTimeUs);
            }
        }
        ALOGI("----- dump mVideoQueue -----");
        ALOGI("+++++ dump mAudioQueue +++++");
        for (it = mAudioQueue.begin(); it != mAudioQueue.end(); it++) {
            if ((*it).mBuffer == NULL) {
                ALOGI("mBuffer == NULL");
            } else {
                (*it).mBuffer->meta()->findInt64("timeUs",&tempTimeUs);
                ALOGI("ts = %lld",tempTimeUs);
            }
        }
        ALOGI("----- dump mAudioQueue -----");
    }
}
#endif
}  // namespace android

