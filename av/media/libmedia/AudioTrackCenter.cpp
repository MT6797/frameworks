
#define LOG_TAG "AudioTrackCenter"
#define MTK_LOG_ENABLE 1
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <cutils/log.h>
#include <media/AudioTrackCenter.h>
#include <media/AudioSystem.h>
#include <system/audio.h>

#define AUDIOFLINGER_BUFFERCOUNT 6

namespace android {

AudioTrackCenter::AudioTrackCenter()
    : mAfFrameCount(0),
      mAfSampleRate(0),
      mTimeScaled(1.0)
#ifdef AUDIO_TRACK_CENTER_DEBUG
      ,mSysTimeUs(0),
      mRealTimeUs(0),
      mDeltaUs(0.0)
#endif
{

}

AudioTrackCenter::~AudioTrackCenter() {
}

status_t AudioTrackCenter::init() {
    audio_stream_type_t streamType = AUDIO_STREAM_DEFAULT;
    if (!mAfFrameCount || !mAfSampleRate) {
        if (AudioSystem::getOutputFrameCount(&mAfFrameCount, streamType) != NO_ERROR) {
            SLOGE("AudioSystem::getOutputFrameCount Fail!!!");
            return NO_INIT;
        }
        if (AudioSystem::getOutputSamplingRate(&mAfSampleRate, streamType) != NO_ERROR) {
            SLOGE("AudioSystem::getOutputSamplingRate Fail!!!");
            return NO_INIT;
        }
        SLOGD("init, mAfFrameCount = %d, mAfSampleRate = %d",mAfFrameCount, mAfSampleRate);
    }

    return OK;
}

status_t AudioTrackCenter::addTrack(intptr_t trackId, uint32_t frameCount, uint32_t sampleRate, void* trackPtr,  uint32_t afFrameCount, uint32_t afSampleRate, uint32_t framesFilled) {
    Mutex::Autolock autoLock(mLock);

    SLOGD("%s, trackId:%p, frameCount:%d, sampleRate:%d, trackPtr:%p",__FUNCTION__,(void*)trackId, frameCount, sampleRate, trackPtr);

    //if (init() != OK) return NO_INIT;
    mAfSampleRate = afSampleRate;
    mAfFrameCount = afFrameCount;
    ALOGD("addTrack: trackId = %p, mAfSampleRate = %d, sampleRate = %d, AfFrameCount = %d , mAfSampleRate = %d, frameCount = %d, framesFilled = %d",
            (void*)trackId, mAfSampleRate, sampleRate, mAfFrameCount, mAfSampleRate, frameCount, framesFilled);
    ssize_t index = mTrackList.indexOfKey(trackId);

    if (index >= 0) {
        SLOGW("trackId: %p has existed!!!", (void*)trackId);
        //return INVALID_OPERATION;
    }

    List<TrackMaps>::iterator it = mTrackMaps.begin();
    bool newTrack = true;
    int64_t framePlayed = 0;
    while(it != mTrackMaps.end()) {
        if ((*it).trackPtr == trackPtr ) {
            ssize_t index = mTrackList.indexOfKey((*it).trackId);
            if (index >= 0) {
                ALOGD("%s, update track info from trackId:%p to trackId:%p", __FUNCTION__, (void*)(*it).trackId, (void*)trackId);
                framePlayed = framesFilled;
                mTrackList.removeItemsAt(index);

                TrackMaps *maps = &*it;
                maps->trackId = trackId;
                newTrack = false;
            }
            break;
        }
        ++it;
    }

    struct TrackInfo info;
    info.server = 0;
    info.frameCount = frameCount;
    info.framePlayed = framePlayed;
    info.afFrameCount = mAfSampleRate ? (sampleRate*mAfFrameCount)/mAfSampleRate : frameCount/AUDIOFLINGER_BUFFERCOUNT;
    info.sampleRate = sampleRate;
    info.middleServer = 0;
    info.active = true;
    info.ts = ALooper::GetNowUs();
    mTrackList.add(trackId, info);

    if (newTrack) {
        struct TrackMaps maps;
        maps.trackId  = trackId;
        maps.trackPtr = trackPtr;
        maps.sinkPtr  = NULL;
        mTrackMaps.push_back(maps);
    }
    ALOGD("addTrack: trackId, info.server, info.framePlayed, info.ts, %p, %d, %lld, %lld",
            (void*)trackId, info.server, info.framePlayed, info.ts);
    return OK;
}

status_t AudioTrackCenter::removeTrack(void* trackPtr) {
    Mutex::Autolock autoLock(mLock);

    SLOGD("%s, trackPtr:%p",__FUNCTION__, trackPtr);

    List<TrackMaps>::iterator it = mTrackMaps.begin();
    while(it != mTrackMaps.end()) {
        if ((*it).trackPtr == trackPtr ) {
            ssize_t index = mTrackList.indexOfKey((*it).trackId);
            if (index < 0) {
                return UNKNOWN_ERROR;
            }
            mTrackList.removeItemsAt(index);
            mTrackMaps.erase(it);
            break;
        }
        ++it;
    }

    return OK;

}

status_t AudioTrackCenter::updateTrackMaps(void* trackPtr, void* sinkPtr) {
    Mutex::Autolock autoLock(mLock);

    SLOGD("%s, trackPtr:%p, sinkPtr:%p",__FUNCTION__, trackPtr, sinkPtr);

    List<TrackMaps>::iterator it = mTrackMaps.begin();
    while (it != mTrackMaps.end()) {
        if (it->trackPtr == trackPtr) {
            TrackMaps *maps = &*it;
            maps->sinkPtr = sinkPtr;
            return OK;
        }
        ++it;
    }

    return UNKNOWN_ERROR;
}

status_t AudioTrackCenter::updateServer(intptr_t trackId, uint32_t server, bool restore) {
    Mutex::Autolock autoLock(mLock);
#if defined(CONFIG_MT_ENG_BUILD)
    ALOGV("%s, trackId: %p, server: %d", __FUNCTION__, (void*)trackId, server);
#endif
    ssize_t index = mTrackList.indexOfKey(trackId);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }

    struct TrackInfo &info = mTrackList.editValueFor(trackId);

    if (!info.active) {
        SLOGV("%s, trackId:%p, active = %d",__FUNCTION__, (void*)trackId, info.active);
        return OK;
    }

    uint32_t s;
    s = (server > info.server) ? (server - info.server) : 0;

    if (s && info.middleServer && s < info.afFrameCount) {
        info.middleServer  = server;
#if defined(CONFIG_MT_ENG_BUILD)
        ALOGV("updateServer: s = %d < info.afFrameCount = %d, info.middleServer = %d, return!",
                s, info.afFrameCount, info.middleServer);
#endif
        // For handling buffer wrap around
        return OK;
    }
    /// M: ALPS02445917: TODO: Update info when first server updating
    if (!restore && info.server) {
        info.framePlayed = info.framePlayed + s;
        info.ts = ALooper::GetNowUs();
    }

    info.server = server;
#if defined(CONFIG_MT_ENG_BUILD)
    ALOGV("updateServer: trackId, info.server, info.framePlayed, info.ts, %p, %d, %lld, %lld",
            (void*)trackId, info.server, info.framePlayed, info.ts);
#endif
    info.middleServer  = server;

    return OK;
}

intptr_t AudioTrackCenter::getTrackId(void* trackPtr, void* sinkPtr) {
    Mutex::Autolock autoLock(mLock);
    #if defined(CONFIG_MT_ENG_BUILD)
    SLOGV("%s, trackPtr:%p, sinkPtr:%p",__FUNCTION__, trackPtr, sinkPtr);
    #endif
    List<TrackMaps>::iterator it = mTrackMaps.begin();
    while (it != mTrackMaps.end()) {
        if ((trackPtr && it->trackPtr == trackPtr) || (sinkPtr && it->sinkPtr == sinkPtr)) {
#if defined(CONFIG_MT_ENG_BUILD)
            SLOGV("%s, return trackId:%p",__FUNCTION__, (void*)it->trackId);
#endif
            return it->trackId;
        }
        ++it;
    }
    #if defined(CONFIG_MT_ENG_BUILD)
    SLOGV("%s, no valid trackId!!",__FUNCTION__);
    #endif
    return 0;
}

status_t AudioTrackCenter::getRealTimePosition(intptr_t trackId, int64_t *position) {
    Mutex::Autolock autoLock(mLock);

        #if defined(CONFIG_MT_ENG_BUILD)
    SLOGV("%s, trackId:%p",__FUNCTION__, (void*)trackId);
        #endif
    ssize_t index = mTrackList.indexOfKey(trackId);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }

    const struct TrackInfo info = mTrackList.valueFor(trackId);
    int64_t delayUs = ALooper::GetNowUs() - info.ts;
    delayUs = delayUs/mTimeScaled;
    *position = info.framePlayed;

    if (!info.framePlayed) {
        SLOGV("trackId = %p, server = %d, framePlayed = %lld", (void*)trackId, info.server, info.framePlayed);
        return OK;
    }

    if (info.server) {
        uint32_t deltaFrames = (uint32_t)((delayUs*info.sampleRate)/1000000);
        if (deltaFrames > info.frameCount) {
            deltaFrames = info.frameCount;
        }

        if (!info.active) {
            SLOGV("%s, trackId = %p, track is not active , set deltaFrames to 0",__FUNCTION__, (void*)trackId);
            deltaFrames = 0;
        }
        *position += deltaFrames;
    }

#ifdef AUDIO_TRACK_CENTER_DEBUG
    SLOGD("trackId = %p, realTimeUs and sysTimeUs distance: %8.3f", (void*)trackId, countDeltaUs(((int64_t)(*position)*1000000)/info.sampleRate));
#endif
#if defined(CONFIG_MT_ENG_BUILD)
    ALOGD("getRealTimePosition: trackId, server, framePlayed, delayUs, *position = %p, %d, %lld, %lld, %lld",
            (void*)trackId, info.server, info.framePlayed, delayUs, *position);
#endif
    return OK;
}

status_t AudioTrackCenter::setTrackActive(intptr_t trackId, bool active) {
    Mutex::Autolock autoLock(mLock);
    ALOGD("%s, trackId: %p, active: %d",__FUNCTION__, (void*)trackId, active);
    ssize_t index = mTrackList.indexOfKey(trackId);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }

    struct TrackInfo &info = mTrackList.editValueFor(trackId);

    info.active = active;
    // Update time stamp after pause/resume
    // Otherwise AudioTrack returns old system time which it get before pause
    info.ts = ALooper::GetNowUs();
    return OK;
}
status_t AudioTrackCenter::setTimeStretch(float timeScaled) {
    Mutex::Autolock autoLock(mLock);

    SLOGV("%s, timeScaled:%f",__FUNCTION__, timeScaled);

    mTimeScaled = timeScaled;
    return OK;
}

status_t AudioTrackCenter::setTimeStretch(uint32_t timeScaled) {
    Mutex::Autolock autoLock(mLock);

    SLOGV("%s, timeScaled:%d",__FUNCTION__, timeScaled);

    if (timeScaled != 1 && timeScaled != 2 && timeScaled != 4 && timeScaled != 8 && timeScaled != 16 && timeScaled != 32) {
        return BAD_VALUE;
    }

    mTimeScaled = (float)timeScaled;

    return OK;
}
status_t AudioTrackCenter::reset_flush(intptr_t trackId) {
    Mutex::Autolock autoLock(mLock);

    SLOGV("%s, trackId:%p",__FUNCTION__, (void*)trackId);

    ssize_t index = mTrackList.indexOfKey(trackId);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }

    struct TrackInfo &info = mTrackList.editValueFor(trackId);

    info.server = 0;
    info.middleServer = 0;
    info.ts = ALooper::GetNowUs();
    info.framePlayed = 0;

#ifdef AUDIO_TRACK_CENTER_DEBUG
    mSysTimeUs = 0;
    mRealTimeUs = 0;
    mDeltaUs = 0;
#endif

    return OK;
}

status_t AudioTrackCenter::reset(intptr_t trackId) {
    Mutex::Autolock autoLock(mLock);

    SLOGV("%s, trackId:%p",__FUNCTION__, (void*)trackId);

    ssize_t index = mTrackList.indexOfKey(trackId);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }

    struct TrackInfo &info = mTrackList.editValueFor(trackId);

    info.server = 0;
    info.middleServer = 0;
    info.ts = ALooper::GetNowUs();

#ifdef AUDIO_TRACK_CENTER_DEBUG
    mSysTimeUs = 0;
    mRealTimeUs = 0;
    mDeltaUs = 0;
#endif

    return OK;
}

#ifdef AUDIO_TRACK_CENTER_DEBUG
float AudioTrackCenter::countDeltaUs(int64_t realTimeUs) {
    int64_t deltaRealUs = 0;
    int64_t delatSysUs = 0 ;

    if (!realTimeUs) return 0;

    if (mSysTimeUs) {
        deltaRealUs = realTimeUs - mRealTimeUs;
        delatSysUs = ALooper::GetNowUs() -    mSysTimeUs;
    }
    mSysTimeUs = ALooper::GetNowUs();
    mRealTimeUs = realTimeUs;

    mDeltaUs = (float)(deltaRealUs - delatSysUs)/1000.00;

    return mDeltaUs;

}
#endif

}
