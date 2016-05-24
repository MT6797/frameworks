/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "AudioMixer"
//#define LOG_NDEBUG 0

#include "Configuration.h"
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <cutils/bitops.h>
#include <cutils/compiler.h>
#include <utils/Debug.h>

#include <system/audio.h>

#include <audio_utils/primitives.h>
#include <audio_utils/format.h>
#include <common_time/local_clock.h>
#include <common_time/cc_helper.h>

#include "AudioMixerOps.h"
#include "AudioMixer.h"


#include <media/AudioSystem.h>
#include <AudioPolicyParameters.h>
#include "AudioMTKHardwareCommand.h"

#ifdef DEBUG_MIXER_PCM
#include "AudioUtilmtk.h"
#endif
#ifdef TIME_STRETCH_ENABLE
#include "AudioMTKTimeStretch.h"
#endif

// The FCC_2 macro refers to the Fixed Channel Count of 2 for the legacy integer mixer.
#ifndef FCC_2
#define FCC_2 2
#endif

// Look for MONO_HACK for any Mono hack involving legacy mono channel to
// stereo channel conversion.

/* VERY_VERY_VERBOSE_LOGGING will show exactly which process hook and track hook is
 * being used. This is a considerable amount of log spam, so don't enable unless you
 * are verifying the hook based code.
 */
//#define VERY_VERY_VERBOSE_LOGGING
#ifdef VERY_VERY_VERBOSE_LOGGING
#define ALOGVV ALOGV
//define ALOGVV printf  // for test-mixer.cpp
#else
#define ALOGVV(a...) do { } while (0)
#endif
#ifdef MTK_AUDIO
#define MTK_ALOG_V(fmt, arg...) ALOGV(fmt, ##arg)
#define MTK_ALOG_D(fmt, arg...) ALOGD(fmt, ##arg)
#define MTK_ALOG_W(fmt, arg...) ALOGW(fmt, ##arg)
#define MTK_ALOG_E(fmt, arg...) ALOGE("Err: %5d:, "fmt, __LINE__, ##arg)
#define MTKAUD_ALOGV ALOGD
#else
#define MTK_ALOG_V(fmt, arg...) do { } while(0)
#define MTK_ALOG_D(fmt, arg...) do { } while(0)
#define MTK_ALOG_W(fmt, arg...) do { } while(0)
#define MTK_ALOG_E(fmt, arg...) do { } while(0)
#define MTKAUD_ALOGV ALOGV
#endif
#ifdef MTK_AUDIO
#ifdef DEBUG_AUDIO_PCM
#include "AudioUtilmtk.h"
    static   const char * gaf_timestretch_in_pcm = "/sdcard/mtklog/audio_dump/af_mixer_timestretch_in.pcm";
    static   const char * gaf_timestretch_in_propty = "af.timestretch.in.pcm";
#endif
    //#define DEBUG_AUDIO_PCM_FOR_TEST
    #ifdef DEBUG_AUDIO_PCM_FOR_TEST
        static   const char * gaf_mixertest_in_propty            = "af.mixer.test.pcm";
        static   const char * gaf_mixertest_in_pcm               = "/sdcard/mtklog/audio_dump/mixer_test";
    #endif
#endif

#ifndef ARRAY_SIZE
#define ARRAY_SIZE(x) (sizeof(x)/sizeof((x)[0]))
#endif

// TODO: Move these macro/inlines to a header file.
template <typename T>
static inline
T max(const T& x, const T& y) {
    return x > y ? x : y;
}

// Set kUseNewMixer to true to use the new mixer engine always. Otherwise the
// original code will be used for stereo sinks, the new mixer for multichannel.
static const bool kUseNewMixer = true;

// Set kUseFloat to true to allow floating input into the mixer engine.
// If kUseNewMixer is false, this is ignored or may be overridden internally
// because of downmix/upmix support.
static const bool kUseFloat = true;

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
static const size_t kCopyBufferFrameCount = 512*4;
#else

// Set to default copy buffer size in frames for input processing.
static const size_t kCopyBufferFrameCount = 256;
#endif


#ifdef DEBUG_MIXER_PCM
static   const char * gaf_mixer_drc_pcm_before        = "/sdcard/mtklog/audio_dump/mixer_drc_before";
static   const char * gaf_mixer_drc_pcm_after         = "/sdcard/mtklog/audio_dump/mixer_drc_after";
static   const char * gaf_mixer_drc_propty            = "af.mixer.drc.pcm";

static   const char * gaf_mixer_end_pcm               = "/sdcard/mtklog/audio_dump/mixer_end";
static   const char * gaf_mixer_end_propty            = "af.mixer.end.pcm";

#define MixerDumpPcm(name, propty, tid, value, buffer, size, format, sampleRate, channelCount ) \
{\
  char fileName[256]; \
  sprintf(fileName,"%s_%d_%p.pcm", name, tid, (void*)value); \
  AudioDump::threadDump(fileName, buffer, size, propty, format, sampleRate, channelCount); \
}
#else
#define MixerDumpPcm(name, propty, tid, value, buffer, size, format, sampleRate, channelCount)
#endif


//<MTK DRC Debug

#define FULL_FRAMECOUNT

#if 1
#define DRC_ALOGD(...)
#else
#define DRC_ALOGD(...)   ALOGD(__VA_ARGS__)
#endif

//MTK DRC Debug>



namespace android {

#ifdef MTK_HIFI_AUDIO
int32_t AudioMixer::BesSurroundTrackCount = 0;
#endif

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
int AudioMixer::BLOCKSIZE = 512;
#endif

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
extern "C" {
void DRCCallback(void *data);
void SetDRCCallback(void *data);
}
#endif

#ifdef TIME_STRETCH_ENABLE
bool AudioMixer::isTimeStretchCapable = true;
#endif
// ----------------------------------------------------------------------------

template <typename T>
T min(const T& a, const T& b)
{
    return a < b ? a : b;
}

// ----------------------------------------------------------------------------

// Ensure mConfiguredNames bitmask is initialized properly on all architectures.
// The value of 1 << x is undefined in C when x >= 32.

AudioMixer::AudioMixer(size_t frameCount, uint32_t sampleRate, uint32_t maxNumTracks)
    :   mTrackNames(0), mConfiguredNames((maxNumTracks >= 32 ? 0 : 1 << maxNumTracks) - 1),
        mSampleRate(sampleRate)
{
    ALOG_ASSERT(maxNumTracks <= MAX_NUM_TRACKS, "maxNumTracks %u > MAX_NUM_TRACKS %u",
            maxNumTracks, MAX_NUM_TRACKS);

    // AudioMixer is not yet capable of more than 32 active track inputs
    ALOG_ASSERT(32 >= MAX_NUM_TRACKS, "bad MAX_NUM_TRACKS %d", MAX_NUM_TRACKS);

    pthread_once(&sOnceControl, &sInitRoutine);

    mState.enabledTracks= 0;
    mState.needsChanged = 0;
    mState.frameCount   = frameCount;
    mState.hook         = process__nop;
    mState.outputTemp   = NULL;
    mState.resampleTemp = NULL;
    mState.mLog         = &mDummyLog;
#ifdef FULL_FRAMECOUNT
    mState.nonResampleTemp = NULL;
#endif
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    mState.mSampleRate = mSampleRate;
#endif
#ifndef MTK_BESSURROUND_ENABLE
        mState.resampleTemp = new int32_t[MAX_NUM_CHANNELS * mState.frameCount];
#else
        mState.downMixBuffer = new int32_t[MAX_NUM_CHANNELS*mState.frameCount];
        mState.resampleTemp = new int32_t[MAX_NUM_CHANNELS * mState.frameCount];
        ALOGD("resampleTemp 0x%x, downMixBuffer %x, size %d(int32 )", mState.resampleTemp, mState.downMixBuffer, mState.frameCount*MAX_NUM_CHANNELS);
#endif
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        mState.mDRCSupport = false;
        mState.pDRCTempBuffer = new int32_t[FCC_2 * mState.frameCount];
        if(BLOCKSIZE > mState.frameCount)
            BLOCKSIZE = 16;  // google default
#endif
#ifdef FULL_FRAMECOUNT
    if (!mState.nonResampleTemp) {
        mState.nonResampleTemp = new int32_t[MAX_NUM_CHANNELS * mState.frameCount];
        ALOGD("%s, new nonResampleTemp 0x%x", __FUNCTION__, mState.nonResampleTemp);
    }
#endif
    // mState.reserved

    // FIXME Most of the following initialization is probably redundant since
    // tracks[i] should only be referenced if (mTrackNames & (1 << i)) != 0
    // and mTrackNames is initially 0.  However, leave it here until that's verified.
    track_t* t = mState.tracks;
    for (unsigned i=0 ; i < MAX_NUM_TRACKS ; i++) {
        t->resampler = NULL;
        t->downmixerBufferProvider = NULL;
        t->mReformatBufferProvider = NULL;
        t->mTimestretchBufferProvider = NULL;
        #ifdef TIME_STRETCH_ENABLE
        t->mMTKTimestretchBufferProvider = NULL;
        #endif
#ifdef MTK_BESSURROUND_ENABLE
        t->mSurroundMixer = NULL;
        if(mState.downMixBuffer !=NULL){
            t->mDownMixBuffer = mState.downMixBuffer;
        }
        t->mSurroundEnable = 0;
        t->mSurroundMode = 0;
#endif
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        t->mDRCEnable = false;
        t->mDRCState = false;
        t->mSteroToMono = BLOUD_S2M_MODE_NONE;
        t->mpDRCObj = NULL;
#endif
#ifdef MTK_HIFI_AUDIO
        t->mBliSrcAdaptorState=0;
        t->mBliSrcDown = NULL;
        t->mBliSrcUp = NULL;
        t->mBliSrcAdaptor = NULL;
        t->mBliSrcOutputBuffer = NULL;
        t->mBliSrcAdaptorShift = 0;
#endif
        t++;
    }

}

AudioMixer::~AudioMixer()
{
    ALOGD("%s start", __FUNCTION__);
    track_t* t = mState.tracks;
    for (unsigned i=0 ; i < MAX_NUM_TRACKS ; i++) {
        delete t->resampler;
        delete t->downmixerBufferProvider;
        delete t->mReformatBufferProvider;
        delete t->mTimestretchBufferProvider;
#ifdef TIME_STRETCH_ENABLE
        if(t->mMTKTimestretchBufferProvider  != NULL)
        {
           delete t->mMTKTimestretchBufferProvider;
        }
 #endif
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        if (t->mpDRCObj && t->mDRCState) {
            t->mpDRCObj->Close();
            t->mDRCState = false;
        }
        delete t->mpDRCObj;
#endif
#ifdef MTK_BESSURROUND_ENABLE
        if (t->mSurroundMixer != NULL)
        {
            delete  t->mSurroundMixer;
            BesSurroundTrackCount --;
        }
#endif

#ifdef MTK_HIFI_AUDIO
        t->deinitBliSrc();
#endif

        t++;
    }
    delete [] mState.outputTemp;
    delete [] mState.resampleTemp;
#ifdef FULL_FRAMECOUNT
    delete [] mState.nonResampleTemp;
#endif

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    delete [] mState.pDRCTempBuffer;
#endif
#ifdef MTK_BESSURROUND_ENABLE
        delete [] mState.downMixBuffer;
#endif

    ALOGD("%s end", __FUNCTION__);
}

void AudioMixer::setLog(NBLog::Writer *log)
{
    mState.mLog = log;
}

static inline audio_format_t selectMixerInFormat(audio_format_t inputFormat __unused) {
    return kUseFloat && kUseNewMixer ? AUDIO_FORMAT_PCM_FLOAT : AUDIO_FORMAT_PCM_16_BIT;
}

int AudioMixer::getTrackName(audio_channel_mask_t channelMask,
        audio_format_t format, int sessionId)
{
    if (!isValidPcmTrackFormat(format)) {
        ALOGE("AudioMixer::getTrackName invalid format (%#x)", format);
        return -1;
    }
    uint32_t names = (~mTrackNames) & mConfiguredNames;
    if (names != 0) {
        int n = __builtin_ctz(names);
        ALOGV("add track (%d)", n);
        // assume default parameters for the track, except where noted below
        track_t* t = &mState.tracks[n];
        t->needs = 0;

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        t->mState = &mState;
#endif

        // Integer volume.
        // Currently integer volume is kept for the legacy integer mixer.
        // Will be removed when the legacy mixer path is removed.
        t->volume[0] = UNITY_GAIN_INT;
        t->volume[1] = UNITY_GAIN_INT;
        t->prevVolume[0] = UNITY_GAIN_INT << 16;
        t->prevVolume[1] = UNITY_GAIN_INT << 16;
        t->volumeInc[0] = 0;
        t->volumeInc[1] = 0;
        t->auxLevel = 0;
        t->auxInc = 0;
        t->prevAuxLevel = 0;

        // Floating point volume.
        t->mVolume[0] = UNITY_GAIN_FLOAT;
        t->mVolume[1] = UNITY_GAIN_FLOAT;
        t->mPrevVolume[0] = UNITY_GAIN_FLOAT;
        t->mPrevVolume[1] = UNITY_GAIN_FLOAT;
        t->mVolumeInc[0] = 0.;
        t->mVolumeInc[1] = 0.;
        t->mAuxLevel = 0.;
        t->mAuxInc = 0.;
        t->mPrevAuxLevel = 0.;
#ifdef MTK_AUDIO
        t->mPreVolumeValid[0] = false;
        t->mPreVolumeValid[1] = false;
        t->mPreAuxValid = false;
#endif

        // no initialization needed
        // t->frameCount
        t->channelCount = audio_channel_count_from_out_mask(channelMask);
        t->enabled = false;
        ALOGV_IF(audio_channel_mask_get_bits(channelMask) != AUDIO_CHANNEL_OUT_STEREO,
                "Non-stereo channel mask: %d\n", channelMask);
        t->channelMask = channelMask;
        t->sessionId = sessionId;
        // setBufferProvider(name, AudioBufferProvider *) is required before enable(name)
        t->bufferProvider = NULL;
        t->buffer.raw = NULL;
        // no initialization needed
        // t->buffer.frameCount
        t->hook = NULL;
        t->in = NULL;
        t->resampler = NULL;
        t->sampleRate = mSampleRate;
        // setParameter(name, TRACK, MAIN_BUFFER, mixBuffer) is required before enable(name)
        t->mainBuffer = NULL;
        t->auxBuffer = NULL;
        t->mInputBufferProvider = NULL;
        t->mReformatBufferProvider = NULL;
        t->downmixerBufferProvider = NULL;
        t->mPostDownmixReformatBufferProvider = NULL;
        t->mTimestretchBufferProvider = NULL;
        t->mMixerFormat = AUDIO_FORMAT_PCM_16_BIT;
        t->mFormat = format;
        t->mMixerInFormat = selectMixerInFormat(format);
        t->mDownmixRequiresFormat = AUDIO_FORMAT_INVALID; // no format required
        t->mMixerChannelMask = audio_channel_mask_from_representation_and_bits(
                AUDIO_CHANNEL_REPRESENTATION_POSITION, AUDIO_CHANNEL_OUT_STEREO);
        t->mMixerChannelCount = audio_channel_count_from_out_mask(t->mMixerChannelMask);
        t->mPlaybackRate = AUDIO_PLAYBACK_RATE_DEFAULT;
#ifdef TIME_STRETCH_ENABLE
        t->mMTKTimestretchBufferProvider = NULL;
        t-> mTrackPlayed = 0;
#endif
#ifdef MTK_BESSURROUND_ENABLE
        t->mSurroundMixer = NULL;
#endif
        t->mDevSampleRate = mSampleRate;
#ifdef MTK_AUDIO
    // add frameCount for dowmix buffer provider to reformat.
         t->frameCount = mState.frameCount;
#endif
        ALOGD("%s, n %d start init", __FUNCTION__, n);

        // Check the downmixing (or upmixing) requirements.
        status_t status = t->prepareForDownmix();
        if (status != OK) {
            ALOGE("AudioMixer::getTrackName invalid channelMask (%#x)", channelMask);
            return -1;
        }
        // prepareForDownmix() may change mDownmixRequiresFormat
        ALOGVV("mMixerFormat:%#x  mMixerInFormat:%#x\n", t->mMixerFormat, t->mMixerInFormat);
        t->prepareForReformat();
        mTrackNames |= 1 << n;

#ifdef MTK_AUDIO
// for multi-channel track , dowmixer will set mMixerInFormat to 16bit,
//set data format to float for data transform is done in dowmix buffer provider.
t->mMixerInFormat = kUseFloat && kUseNewMixer
        ? AUDIO_FORMAT_PCM_FLOAT : AUDIO_FORMAT_PCM_16_BIT;
#endif
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        SetDRCCallback(this);
#endif

        return TRACK0 + n;
    }
    ALOGE("AudioMixer::getTrackName out of available tracks");
    return -1;
}

void AudioMixer::invalidateState(uint32_t mask)
{
    if (mask != 0) {
        mState.needsChanged |= mask;
        mState.hook = process__validate;
    }
 }

// Called when channel masks have changed for a track name
// TODO: Fix DownmixerBufferProvider not to (possibly) change mixer input format,
// which will simplify this logic.
bool AudioMixer::setChannelMasks(int name,
        audio_channel_mask_t trackChannelMask, audio_channel_mask_t mixerChannelMask) {
    track_t &track = mState.tracks[name];

    if (trackChannelMask == track.channelMask
            && mixerChannelMask == track.mMixerChannelMask) {
        return false;  // no need to change
    }
    // always recompute for both channel masks even if only one has changed.
    const uint32_t trackChannelCount = audio_channel_count_from_out_mask(trackChannelMask);
    const uint32_t mixerChannelCount = audio_channel_count_from_out_mask(mixerChannelMask);
    const bool mixerChannelCountChanged = track.mMixerChannelCount != mixerChannelCount;

    ALOG_ASSERT((trackChannelCount <= MAX_NUM_CHANNELS_TO_DOWNMIX)
            && trackChannelCount
            && mixerChannelCount);
    track.channelMask = trackChannelMask;
    track.channelCount = trackChannelCount;
    track.mMixerChannelMask = mixerChannelMask;
    track.mMixerChannelCount = mixerChannelCount;

    // channel masks have changed, does this track need a downmixer?
    // update to try using our desired format (if we aren't already using it)
    const audio_format_t prevDownmixerFormat = track.mDownmixRequiresFormat;
    const status_t status = mState.tracks[name].prepareForDownmix();
    ALOGE_IF(status != OK,
            "prepareForDownmix error %d, track channel mask %#x, mixer channel mask %#x",
            status, track.channelMask, track.mMixerChannelMask);

    if (prevDownmixerFormat != track.mDownmixRequiresFormat) {
        track.prepareForReformat(); // because of downmixer, track format may change!
    }

    if (track.resampler && mixerChannelCountChanged) {
        // resampler channels may have changed.
        const uint32_t resetToSampleRate = track.sampleRate;
        delete track.resampler;
        track.resampler = NULL;

#ifdef MTK_HIFI_AUDIO
        track.deinitBliSrc();
#endif

        track.sampleRate = mSampleRate; // without resampler, track rate is device sample rate.
        // recreate the resampler with updated format, channels, saved sampleRate.
        track.setResampler(resetToSampleRate /*trackSampleRate*/, mSampleRate /*devSampleRate*/);
    }
    return true;
}



#ifdef MTK_BESSURROUND_ENABLE
status_t AudioMixer::track_t::prepareTrackForSurroundMix(){
    status_t ret;
    ALOGV("%x", __FUNCTION__);
    unprepareTrackForSurroundMix();
    if(AudioMixer::BesSurroundTrackCount >= 3){
        ALOGV("prepareTrackForSurroundMix , no surround track available, BesSurroundTrackCount %d, trackName %p", AudioMixer::BesSurroundTrackCount, this);
        return NO_INIT;
    }
    // init either normal downmix or surround down mix
    mSurroundMixer = new AudioMTKSurroundDownMix();
    mSurroundMixer->SetBesSurroundOnOFF(mSurroundEnable);
    mSurroundMixer->SetBesSurroundMode(mSurroundMode);

    uint32_t sampleRate = mDevSampleRate;
    #ifdef MTK_HIFI_AUDIO
    if(OUTPUT_RATE_192 == sampleRate || OUTPUT_RATE_96 == sampleRate)
        sampleRate = OUTPUT_RATE_48;
    else if(OUTPUT_RATE_176_4 == sampleRate || OUTPUT_RATE_88_2 == sampleRate)
        sampleRate = OUTPUT_RATE_44_1;
    #endif

    if (0 != (mSurroundMixer->Init((void*)this, sessionId, channelMask, sampleRate)))
    {
        delete mSurroundMixer;
        mSurroundMixer = NULL;
        return NO_INIT;
    }
    AudioMixer::BesSurroundTrackCount ++;
    ALOGV(" BesSurroundTrackCount++ %dtrackName %p",AudioMixer::BesSurroundTrackCount , this);
    return  OK;
}

void AudioMixer::track_t::unprepareTrackForSurroundMix() {
    if (mSurroundMixer != NULL) {
        // this track had previously been configured with a downmixer, delete it
        delete mSurroundMixer;
        mSurroundMixer = NULL;
        AudioMixer::BesSurroundTrackCount --;
        ALOGV("unprepareTrackForSurroundMix BesSurroundTrackCount-- %d, trackName %p", AudioMixer::BesSurroundTrackCount, this);
    } else  {
        ALOGV(" nothing to do, no downmixer to delete");
    }
}
#endif

void AudioMixer::track_t::unprepareForDownmix() {
    ALOGV("AudioMixer::unprepareForDownmix(%p)", this);
#ifdef MTK_BESSURROUND_ENABLE
        unprepareTrackForSurroundMix();    //override this function
#endif

    mDownmixRequiresFormat = AUDIO_FORMAT_INVALID;
    if (downmixerBufferProvider != NULL) {
        // this track had previously been configured with a downmixer, delete it
        ALOGV(" deleting old downmixer");
        delete downmixerBufferProvider;
        downmixerBufferProvider = NULL;
        reconfigureBufferProviders();
    } else {
        ALOGV(" nothing to do, no downmixer to delete");
    }
}
#ifdef TIME_STRETCH_ENABLE
#ifdef VERY_VERY_VERBOSE_LOGGING
int timetotal;
#endif
AudioMixer::MTKTimeStretchBufferProvider::MTKTimeStretchBufferProvider(int framecount, track_t* pTrack) : PassthruBufferProvider(),
        mTrackBufferProvider(NULL), mTimeStretchHandle(NULL), mOutBuffer(NULL), mOutRemain(0)
{
    if(framecount >0)
    {
        mOutframecount = framecount;
    }
    else
        mOutframecount = 4096;
         ALOGV("new Timestretch, internal input buffer framecount %d ",mOutframecount );
    mTimeStretchHandle = new AudioMTKTimeStretch(mOutframecount);
        mTimeStretchHandle->SetFirstRamp(pTrack->mTrackPlayed);
    mOutBuffer = new short[mOutframecount*4]; // *2 for channel count, *2 for downsampling.
    mInBuffer = new short[mOutframecount*4];   // for stretch 4 times. may not enough if downsampling
}
AudioMixer::MTKTimeStretchBufferProvider::~MTKTimeStretchBufferProvider()
{
    //ALOGV("AudioMTKMixer deleting MTKTimeStretchBufferProvider (%p)", this);
    if(mTimeStretchHandle !=NULL)
    {
        delete mTimeStretchHandle;
    }
    if(mOutBuffer != NULL)
    {
        delete []mOutBuffer;
    }
    if(mInBuffer != NULL)
    {
        delete []mInBuffer;
    }
}
void AudioMixer::MTKTimeStretchBufferProvider::releaseBuffer(AudioBufferProvider::Buffer *pBuffer) {

    int ChannelCount = popcount(mTimeStretchConfig.inputCfg.channels);
    ALOGVV("MTKTimeStretchBufferProvider::releaseBuffer()");

    if(pBuffer ==NULL)
    {
        ALOGE("DownmixerBufferProvider::releaseBuffer() error: NULL track buffer provider");
        return;
    }
    if(mOutRemain  == 0 && pBuffer->frameCount != 0)
    {
        // for frame count < 512,  time stretch is not activated, so getNextBuffer returns non-time stretched buffer
        // and release non time stretched buffer here.
        ALOGVV("for in frame count <512 case realease real buffer (non-stretched) count");
        mBuffer.frameCount = pBuffer->frameCount;
        mTrackBufferProvider->releaseBuffer(&mBuffer);
    }
    else{
        // maintain internal buffer: internal buffer(mOutBuffer) keeps time stretched data.
        ALOGVV("release pBuffer-> raw %x, pBuffer->frameCount %d",pBuffer->raw ,pBuffer->frameCount  );
        mOutRemain -= pBuffer->frameCount ;
        memcpy(mOutBuffer, mOutBuffer + (pBuffer->frameCount * ChannelCount),(mOutRemain* ChannelCount)*sizeof(short) );
        //mBuffer.raw = pBuffer->raw;
        ALOGVV("release mBuffer-> raw %x, mBuffer->frameCount %d",mBuffer.raw ,mBuffer.frameCount  );
        // release what we originally get from audio Track.
        mTrackBufferProvider->releaseBuffer(&mBuffer);
    }
    pBuffer->raw = mOutBuffer;
    pBuffer->frameCount = 0;
    ALOGVV("MTKTimeStretchBufferProvider %d keeped.",mOutRemain);
    ALOGVV("release pBuffer-> raw %x, pBuffer->frameCount %d",pBuffer->raw ,pBuffer->frameCount  );
}
status_t AudioMixer::MTKTimeStretchBufferProvider::getNextBuffer(AudioBufferProvider::Buffer *pBuffer,
int64_t pts) {
    int  outsize;
    int insize;
    int ChannelCount;
    short* OutBufferPtr;
         int second_request = 0;
         int dataGet = 0;
    int original_require = pBuffer->frameCount;
    status_t res;
    ALOGD("MTKTimeStretchBufferProvider::getNextBuffer() this %x", this);

    if (this->mTrackBufferProvider == NULL ) {
        ALOGE("MTKTimeStretchBufferProvider::getNextBuffer() error: NULL track buffer provider");
        return NO_INIT;
    }
    if( mOutBuffer == NULL){

        ALOGE("MTKTimeStretchBufferProvider::getNextBuffer() error: NULL internal buffer");
        return NO_INIT;
    }
    if(mOutRemain !=0)
    {
        // if internal buffer still has time stretched data, return directly.
        pBuffer->frameCount = (original_require < mOutRemain) ? original_require: mOutRemain;
        ALOGV("MTKTimeStretchBufferProvider::getNextBuffer() directly return %d",  pBuffer->frameCount);
        pBuffer->raw = mOutBuffer;
        return OK;
    }

    /////////////// Get new data and process///////////////////////////

    ALOGVV("mOutframecount%d, pBuffer->frameCount %d",mOutframecount,pBuffer->frameCount);

    ////////////Check buffer size availability//////////////////////////////
    if (mOutframecount < pBuffer->frameCount)
    {
        pBuffer->frameCount = mOutframecount; // can't exceed internal buffer size;
    }

    ALOGVV(" pBuffer->frameCount  %d",pBuffer->frameCount);

    /////////// Calculate needed input frame count//////////////////////////
    if(mTimeStretchHandle->mBTS_RTParam.TS_Ratio == 100 || mTimeStretchHandle ==NULL){
        pBuffer->frameCount = pBuffer->frameCount;
    }else{
        pBuffer->frameCount = (pBuffer->frameCount*100)/mTimeStretchHandle->mBTS_RTParam.TS_Ratio ;
                pBuffer->frameCount = (pBuffer->frameCount == 0)?(pBuffer->frameCount+1) : pBuffer->frameCount;
    }
          pBuffer->frameCount = mTimeStretchHandle->InternalBufferSpace() > pBuffer->frameCount ? pBuffer->frameCount: mTimeStretchHandle->InternalBufferSpace();
          if(mTimeStretchHandle->mBTS_RTParam.TS_Ratio <= 400){
          if(mTimeStretchHandle->InternalBufferFrameCount() + pBuffer->frameCount  <256)
          {
            // require more frame so that time stretch can be motivated.
            //ALOGVV("enforce process framecount >256");
            pBuffer->frameCount = 256-mTimeStretchHandle->InternalBufferFrameCount();
              }
          }
    /////////Get data////////////////////////////////////////////////
    ALOGV("Timestertch getNextBuffer real required%d", pBuffer->frameCount );
    mBuffer.frameCount = pBuffer->frameCount;
    mBuffer.raw = pBuffer->raw;
    res = mTrackBufferProvider->getNextBuffer(&mBuffer, pts);

    ChannelCount = popcount(mTimeStretchConfig.inputCfg.channels);
#ifdef MTK_AUDIO
#ifdef DEBUG_AUDIO_PCM
            const int SIZE = 256;
            char fileName[SIZE];
            sprintf(fileName,"%s_%p.pcm",gaf_timestretch_in_pcm,this);
            AudioDump::dump(fileName,mBuffer.raw,mBuffer.frameCount<<ChannelCount,gaf_timestretch_in_propty);
            #ifdef VERY_VERY_VERBOSE_LOGGING
            timetotal += mBuffer.frameCount;
            ALOGVV("timetotal %d, mBuffer.frameCount %d", timetotal, mBuffer.frameCount );
            #endif
#endif
#endif
    //ALOGD("mBuffer.raw %x,mBuffer.frameCount*4 %d",mBuffer.raw,mBuffer.frameCount<<ChannelCount);
    ALOGV("Timestertch getNextBuffer real get%d", mBuffer.frameCount );
        dataGet += mBuffer.frameCount;
        second_request =pBuffer->frameCount - mBuffer.frameCount;
        if(second_request && dataGet !=0 )
        {
            ALOGV("second_request real require %d", second_request);
            ALOGVV("mBuffer.raw %x pBuffer-> raw %x", mBuffer.raw, pBuffer->raw);
            memcpy(mInBuffer, mBuffer.raw, mBuffer.frameCount<<ChannelCount);
            mTrackBufferProvider->releaseBuffer(&mBuffer);
            mBuffer.frameCount = second_request;
            ALOGVV("mBuffer.raw %x, mBuffer.frameCount %d", mBuffer.raw,mBuffer.frameCount);
            mTrackBufferProvider->getNextBuffer(&mBuffer, pts);
            if (mBuffer.frameCount){
                memcpy(mInBuffer + dataGet* ChannelCount, mBuffer.raw, mBuffer.frameCount<<ChannelCount);
             }
            ALOGV("second_request real get %d", mBuffer.frameCount);
            dataGet += mBuffer.frameCount;
#ifdef MTK_AUDIO
#ifdef DEBUG_AUDIO_PCM
                if(mBuffer.frameCount){
                        AudioDump::dump(fileName,mBuffer.raw,mBuffer.frameCount<<ChannelCount,gaf_timestretch_in_propty);
                        #ifdef VERY_VERY_VERBOSE_LOGGING
                        timetotal += mBuffer.frameCount;
                        ALOGVV("timetotal %d, mBuffer.frameCount %d", timetotal, mBuffer.frameCount );
                        #endif
                    }
#endif
#endif
        }
    #if  1
    ////////////////////process data///////////////////////////////////
    if (res == OK &&dataGet !=0) {
                    insize = dataGet<< ChannelCount;

        // available output buffer space
        outsize = (mOutframecount -mOutRemain) << ChannelCount;

        // output pointer offset to last round data
        OutBufferPtr = mOutBuffer + (mOutRemain << (ChannelCount-1));
                    ALOGVV("mBuffer.i16 %d pBuffer->raw %d", mBuffer.i16, pBuffer->raw);

                   short* inptr = (second_request && dataGet !=0 )? mInBuffer: mBuffer.i16;
        mTimeStretchHandle->process(inptr,OutBufferPtr ,&insize, &outsize );
        // insize always returns 0, since timestretch accumulate samples.
        //ALOGV("getNextBuffer is TimeStretching");
        mOutRemain += outsize >> (ChannelCount);
        // real consumed sample count, release later.
        //mBuffer.frameCount -= ( insize >> ChannelCount); //(stereo : 2, mono :1)
        #if 0
        if(mOutRemain != 0)
        {
            // stretched output framecount
            pBuffer->frameCount = mOutRemain;
            // replace out buffer.
            pBuffer->raw = mOutBuffer;
        }
        else{
            /// for smple less than 512 sample, do not do time stretch, returns original getNextBuffer sample count.
            /// use orignal  buffer frame count and buffer size to bypass time stretch.
            pBuffer->frameCount = mBuffer.frameCount;
            pBuffer->raw = mBuffer.raw;
        }
        #else
        // stretched output framecount
        //pBuffer->frameCount = mOutRemain;
        pBuffer->frameCount = (original_require < mOutRemain) ? original_require: mOutRemain;
                    // replace out buffer.
                    pBuffer->raw = mOutBuffer;
        #endif
        ALOGVV(" getNextBuffer: mOutRemain %d", mOutRemain);
    }
    else
    {
        ALOGD("getNexBuffer returns not ok");
        pBuffer->frameCount  = mBuffer.frameCount ;
        pBuffer->raw = mBuffer.raw;
    }
    #else
    memcpy(mOutBuffer, mBuffer.i16, mBuffer.frameCount*4);

    ALOGD("mBuffer-> raw %x, mBuffer->frameCount %d",mBuffer.raw ,mBuffer.frameCount  );
    //pBuffer->raw = mBuffer.raw;
    pBuffer->raw = mOutBuffer;
    pBuffer->frameCount = mBuffer.frameCount;
    //mBuffer.raw = mOutBuffer;
    //mBuffer.frameCount = pBuffer->frameCount;

    ALOGD("pBuffer-> raw %x, pBuffer->frameCount %d",pBuffer->raw ,pBuffer->frameCount  );
    #endif
return res;
}
status_t AudioMixer::MTKTimeStretchBufferProvider::TimeStretchConfig(int ratio)
{
    int ch_num;
    if(mTimeStretchHandle == NULL)
        return -1;
    if(mTimeStretchConfig.inputCfg.samplingRate == 0 ||
        (mTimeStretchConfig.inputCfg.channels & (!AUDIO_CHANNEL_OUT_STEREO)) )
        return -1;

    if(mTimeStretchConfig.inputCfg.format != AUDIO_FORMAT_PCM_16_BIT )
        return -1;
    ch_num = (mTimeStretchConfig.inputCfg.channels & AUDIO_CHANNEL_OUT_MONO) ? 1: 2;
    mTimeStretchHandle->setParameters(&ratio);
        return OK;
}
#endif

#ifdef TIME_STRETCH_ENABLE
status_t AudioMixer::track_t::initTrackMTKTimeStretch(int ratio)
    {
        status_t status = OK;
        MTKTimeStretchBufferProvider* tsbufferProvider =(MTKTimeStretchBufferProvider*) mMTKTimestretchBufferProvider;
                    if((tsbufferProvider == NULL) && ratio == 100)
                    {
                        return status;
                    }
        if (ratio> 55) {
            //pTrack->channelMask = mask;
            //pTrack->channelCount = channelCount;
                if(tsbufferProvider == NULL){
                ALOGV("initTrackMTKTimeStretch(track=%p, ratio= %d) calls prepareTrackForMTKTimeStretch()",
                        this, ratio);
                status = prepareTrackForMTKTimeStretch( mState->frameCount,ratio);}
                else{
                ALOGVV("initTrackMTKTimeStretch( ratio= %d) calls TimeStretchConfig()",
                   ratio);
                      status =   tsbufferProvider->TimeStretchConfig(ratio);
                    }
        } else {
            ALOGV("initTrackMTKTimeStretch(track=%p, ratio= %d) calls unprepareTrackForMTKTimeStretch()",
                    this, ratio);
            unprepareTrackForMTKTimeStretch();
        }
        return status;
    }


void AudioMixer::track_t::unprepareTrackForMTKTimeStretch() {
    ALOGV("unprepareTrackForMTKTimeStretch(%p)", this);
    MTKTimeStretchBufferProvider* tsbufferProvider =(MTKTimeStretchBufferProvider*) mMTKTimestretchBufferProvider;

    if (tsbufferProvider != NULL) {
        // this track had previously been configured with a Time stretch, delete it
    if(tsbufferProvider->mOutRemain !=0)
    {
        if(resampler!= NULL)
        {
            // in case resampler keeps time stretched data.
            resampler->ResetBuffer();
         }
    }
        bufferProvider = tsbufferProvider->mTrackBufferProvider;
        delete tsbufferProvider;
        mMTKTimestretchBufferProvider = NULL;

        reconfigureBufferProviders();
    } else {
        //ALOGV(" nothing to do, no timestretch to delete");
    }
}

status_t AudioMixer::track_t::prepareTrackForMTKTimeStretch(int framecount, int ratio)
{
    ALOGV("AudioMTKMixer::prepareTrackForMTKTimeStretch(%p) with ratio 0x%x, framecount %d", this, ratio,framecount);

    // discard the previous downmixer if there was one
    unprepareTrackForMTKTimeStretch();

    int32_t status;

    if (!isTimeStretchCapable) {
        ALOGE("prepareTrackForMTKTimeStretch(%p) fails: mixer doesn't support TimeStretch ",
                this);
        return NO_INIT;
    }
    if(bufferProvider ==NULL)
    {
        ALOGE("prepareTrackForMTKTimeStretch(%p) fails: pTrack->bufferProvider is null, pTrack 0x%x", this, this);
        return NO_INIT;
    }
    MTKTimeStretchBufferProvider* pDbp = new MTKTimeStretchBufferProvider(framecount, this);
   if(pDbp == NULL)
   {
       ALOGE("prepareTrackForMTKTimeStretch(%p) fails: MTKTimeStretchBufferProvider is null", this);
        return NO_INIT;
   }
    /*if(pTrack->mBitFormat != AUDIO_FORMAT_PCM_16_BIT)
    {

    ALOGE("prepareTrackForMTKTimeStretch(%d) fails: TimeStretch doesn't support format other than AUDIO_FORMAT_PCM_16_BIT ",
            trackName);
    goto noTimeStretchForActiveTrack;
    }*/

    // channel input configuration will be overridden per-track
    pDbp->mTimeStretchConfig.inputCfg.channels =channelMask;
    pDbp->mTimeStretchConfig.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pDbp->mTimeStretchConfig.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pDbp->mTimeStretchConfig.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pDbp->mTimeStretchConfig.inputCfg.samplingRate = sampleRate;
    pDbp->mTimeStretchConfig.outputCfg.samplingRate = sampleRate;
    pDbp->mTimeStretchConfig.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    pDbp->mTimeStretchConfig.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    // input and output buffer provider, and frame count will not be used as the downmix effect
    // process() function is called directly (see DownmixerBufferProvider::getNextBuffer())
    pDbp->mTimeStretchConfig.inputCfg.mask = EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS |
            EFFECT_CONFIG_FORMAT | EFFECT_CONFIG_ACC_MODE;
    pDbp->mTimeStretchConfig.outputCfg.mask = pDbp->mTimeStretchConfig.inputCfg.mask;

    // Configure and enable TimeStretch
     if(pDbp->mTimeStretchHandle->init(sampleRate,  popcount(channelMask), ratio) != 0)
     {
        ALOGE("prepareTrackForMTKTimeStretch(%p) fails: Open Time stretch fail ",this);
        goto noTimeStretchForActiveTrack;
        }
    // initialization successful:
    // if reformat buffer provider is used:
    if (mReformatBufferProvider != NULL) {
        bufferProvider =   mReformatBufferProvider->getBufferProvider();
        ALOGV("reset track buffer provider if reformat is used  pTrack->bufferProvider  0x%x", bufferProvider );
    }
    // - keep track of the real buffer provider in case it was set before
    pDbp->mTrackBufferProvider = bufferProvider;
    // - we'll use the downmix effect integrated inside this
    //    track's buffer provider, and we'll use it as the track's buffer provider
    mMTKTimestretchBufferProvider = pDbp;
    bufferProvider = pDbp;

    if (mReformatBufferProvider) {
        mReformatBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = mReformatBufferProvider;

        ALOGV("set reformat buffer provider after time stretch  pTrack->bufferProvider  0x%x", bufferProvider );
    }

    ALOGV("prepareTrackForMTKTimeStretch, pTrack->bufferProvider : %x  pTrack->mTrackBufferProvider %x ",bufferProvider,pDbp->mTrackBufferProvider );
    return NO_ERROR;

noTimeStretchForActiveTrack:
    delete pDbp;
    mMTKTimestretchBufferProvider = NULL;
    return NO_INIT;
}
#endif

status_t AudioMixer::track_t::prepareForDownmix()
{
    ALOGV("AudioMixer::prepareForDownmix(%p) with mask 0x%x",
            this, channelMask);

    // discard the previous downmixer if there was one
    unprepareForDownmix();
    // MONO_HACK Only remix (upmix or downmix) if the track and mixer/device channel masks
    // are not the same and not handled internally, as mono -> stereo currently is.
    if (channelMask == mMixerChannelMask
            || (channelMask == AUDIO_CHANNEL_OUT_MONO
                    && mMixerChannelMask == AUDIO_CHANNEL_OUT_STEREO)) {
        return NO_ERROR;
    }

#ifdef MTK_BESSURROUND_ENABLE
   //ALOGD("%s prepareTrackForSurroundMix ", __FUNCTION__);
   status_t status = prepareTrackForSurroundMix();
   if (status != NO_INIT)
   {
      return status;
   }
#endif

    // DownmixerBufferProvider is only used for position masks.
    if (audio_channel_mask_get_representation(channelMask)
                == AUDIO_CHANNEL_REPRESENTATION_POSITION
            && DownmixerBufferProvider::isMultichannelCapable()) {
        DownmixerBufferProvider* pDbp = new DownmixerBufferProvider(channelMask,
                mMixerChannelMask,
                AUDIO_FORMAT_PCM_16_BIT /* TODO: use mMixerInFormat, now only PCM 16 */,
                sampleRate, sessionId, kCopyBufferFrameCount);

        if (pDbp->isValid()) { // if constructor completed properly
            mDownmixRequiresFormat = AUDIO_FORMAT_PCM_16_BIT; // PCM 16 bit required for downmix
            downmixerBufferProvider = pDbp;
            reconfigureBufferProviders();

            return NO_ERROR;
        }
        delete pDbp;
    }

    // Effect downmixer does not accept the channel conversion.  Let's use our remixer.
    RemixBufferProvider* pRbp = new RemixBufferProvider(channelMask,
            mMixerChannelMask, mMixerInFormat, kCopyBufferFrameCount);
    // Remix always finds a conversion whereas Downmixer effect above may fail.
    downmixerBufferProvider = pRbp;
    reconfigureBufferProviders();
    return NO_ERROR;
}

void AudioMixer::track_t::unprepareForReformat() {
    ALOGV("AudioMixer::unprepareForReformat(%p)", this);
    bool requiresReconfigure = false;
    if (mReformatBufferProvider != NULL) {
        delete mReformatBufferProvider;
        mReformatBufferProvider = NULL;
        requiresReconfigure = true;
    }
    if (mPostDownmixReformatBufferProvider != NULL) {
        delete mPostDownmixReformatBufferProvider;
        mPostDownmixReformatBufferProvider = NULL;
        requiresReconfigure = true;
    }
    if (requiresReconfigure) {
        reconfigureBufferProviders();
    }
}

status_t AudioMixer::track_t::prepareForReformat()
{
    ALOGV("AudioMixer::prepareForReformat(%p) with format %#x", this, mFormat);
    // discard previous reformatters
    unprepareForReformat();
    // only configure reformatters as needed
    const audio_format_t targetFormat = mDownmixRequiresFormat != AUDIO_FORMAT_INVALID
            ? mDownmixRequiresFormat : mMixerInFormat;
    bool requiresReconfigure = false;
    if (mFormat != targetFormat) {
        mReformatBufferProvider = new ReformatBufferProvider(
                audio_channel_count_from_out_mask(channelMask),
                mFormat,
                targetFormat,
                kCopyBufferFrameCount);
        requiresReconfigure = true;
    }
    if (targetFormat != mMixerInFormat) {
        mPostDownmixReformatBufferProvider = new ReformatBufferProvider(
                audio_channel_count_from_out_mask(mMixerChannelMask),
                targetFormat,
                mMixerInFormat,
                kCopyBufferFrameCount);
        requiresReconfigure = true;
    }
    if (requiresReconfigure) {
        reconfigureBufferProviders();
    }
    return NO_ERROR;
}

void AudioMixer::track_t::reconfigureBufferProviders()
{
    bufferProvider = mInputBufferProvider;
    ALOGV("%s, mInputBufferProvider %p", __FUNCTION__, mInputBufferProvider);
    #ifdef TIME_STRETCH_ENABLE
    // order does matter.. MTK only support 16 bit processing.
    if (mMTKTimestretchBufferProvider) {
        ALOGD("set mMTKTimestretchBufferProvider %p ", bufferProvider);
        mMTKTimestretchBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = mMTKTimestretchBufferProvider;
    }
    #endif
    if (mReformatBufferProvider) {
        ALOGV("set mReformatBufferProvider %p ", bufferProvider);
        mReformatBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = mReformatBufferProvider;
    }
    if (downmixerBufferProvider) {
        ALOGV("set downmixerBufferProvider %p ", bufferProvider);
        downmixerBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = downmixerBufferProvider;
    }
    if (mPostDownmixReformatBufferProvider) {
        ALOGV("set mPostDownmixReformatBufferProvider %p ", bufferProvider);
        mPostDownmixReformatBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = mPostDownmixReformatBufferProvider;
    }
    if (mTimestretchBufferProvider) {
        ALOGV("set mTimestretchBufferProviderv %p ", bufferProvider);
        mTimestretchBufferProvider->setBufferProvider(bufferProvider);
        bufferProvider = mTimestretchBufferProvider;
    }
}

void AudioMixer::deleteTrackName(int name)
{
    ALOGV("AudioMixer::deleteTrackName(%d)", name);
    name -= TRACK0;
    ALOG_ASSERT(uint32_t(name) < MAX_NUM_TRACKS, "bad track name %d", name);
    ALOGV("deleteTrackName(%d)", name);
    track_t& track(mState.tracks[ name ]);
    if (track.enabled) {
        track.enabled = false;
        invalidateState(1<<name);
    }
    // delete the resampler
    delete track.resampler;
    track.resampler = NULL;
    // delete the downmixer
    mState.tracks[name].unprepareForDownmix();
    // delete the reformatter
    mState.tracks[name].unprepareForReformat();

#ifdef TIME_STRETCH_ENABLE
    mState.tracks[name].unprepareTrackForMTKTimeStretch() ;
    delete track.mMTKTimestretchBufferProvider;
    track.mMTKTimestretchBufferProvider = NULL;
    track.mTrackPlayed = 0;
#endif
    // delete the timestretch provider
    delete track.mTimestretchBufferProvider;
    track.mTimestretchBufferProvider = NULL;

#ifdef MTK_HIFI_AUDIO
        track.deinitBliSrc();
#endif

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    track.mDRCEnable = false;
    if (track.mpDRCObj) {
        track.mpDRCObj->Close();
        track.mDRCState = false;
        if(NULL != track.mpDRCObj) {
            delete track.mpDRCObj;
            track.mpDRCObj = NULL;
        }
    }
#endif

    mTrackNames &= ~(1<<name);
}

void AudioMixer::enable(int name)
{
    name -= TRACK0;
    ALOG_ASSERT(uint32_t(name) < MAX_NUM_TRACKS, "bad track name %d", name);
    track_t& track = mState.tracks[name];

    if (!track.enabled) {
        track.enabled = true;
        ALOGV("enable(%d)", name);
        invalidateState(1 << name);
    }
}

void AudioMixer::disable(int name)
{
    name -= TRACK0;
    ALOG_ASSERT(uint32_t(name) < MAX_NUM_TRACKS, "bad track name %d", name);
    track_t& track = mState.tracks[name];

    if (track.enabled) {
        track.enabled = false;
        ALOGV("disable(%d)", name);
        invalidateState(1 << name);
    }
}

/* Sets the volume ramp variables for the AudioMixer.
 *
 * The volume ramp variables are used to transition from the previous
 * volume to the set volume.  ramp controls the duration of the transition.
 * Its value is typically one state framecount period, but may also be 0,
 * meaning "immediate."
 *
 * FIXME: 1) Volume ramp is enabled only if there is a nonzero integer increment
 * even if there is a nonzero floating point increment (in that case, the volume
 * change is immediate).  This restriction should be changed when the legacy mixer
 * is removed (see #2).
 * FIXME: 2) Integer volume variables are used for Legacy mixing and should be removed
 * when no longer needed.
 *
 * @param newVolume set volume target in floating point [0.0, 1.0].
 * @param ramp number of frames to increment over. if ramp is 0, the volume
 * should be set immediately.  Currently ramp should not exceed 65535 (frames).
 * @param pIntSetVolume pointer to the U4.12 integer target volume, set on return.
 * @param pIntPrevVolume pointer to the U4.28 integer previous volume, set on return.
 * @param pIntVolumeInc pointer to the U4.28 increment per output audio frame, set on return.
 * @param pSetVolume pointer to the float target volume, set on return.
 * @param pPrevVolume pointer to the float previous volume, set on return.
 * @param pVolumeInc pointer to the float increment per output audio frame, set on return.
 * @return true if the volume has changed, false if volume is same.
 */
static inline bool setVolumeRampVariables(float newVolume, int32_t ramp,
        int16_t *pIntSetVolume, int32_t *pIntPrevVolume, int32_t *pIntVolumeInc,
        float *pSetVolume, float *pPrevVolume, float *pVolumeInc) {
    // check floating point volume to see if it is identical to the previously
    // set volume.
    // We do not use a tolerance here (and reject changes too small)
    // as it may be confusing to use a different value than the one set.
    // If the resulting volume is too small to ramp, it is a direct set of the volume.
    if (newVolume == *pSetVolume) {
        return false;
    }
    if (newVolume < 0) {
        newVolume = 0; // should not have negative volumes
    } else {
        switch (fpclassify(newVolume)) {
        case FP_SUBNORMAL:
        case FP_NAN:
            newVolume = 0;
            break;
        case FP_ZERO:
            break; // zero volume is fine
        case FP_INFINITE:
            // Infinite volume could be handled consistently since
            // floating point math saturates at infinities,
            // but we limit volume to unity gain float.
            // ramp = 0; break;
            //
            newVolume = AudioMixer::UNITY_GAIN_FLOAT;
            break;
        case FP_NORMAL:
        default:
            // Floating point does not have problems with overflow wrap
            // that integer has.  However, we limit the volume to
            // unity gain here.
            // TODO: Revisit the volume limitation and perhaps parameterize.
            if (newVolume > AudioMixer::UNITY_GAIN_FLOAT) {
                newVolume = AudioMixer::UNITY_GAIN_FLOAT;
            }
            break;
        }
    }

    // set floating point volume ramp
    if (ramp != 0) {
        // when the ramp completes, *pPrevVolume is set to *pSetVolume, so there
        // is no computational mismatch; hence equality is checked here.
        ALOGD_IF(*pPrevVolume != *pSetVolume, "previous float ramp hasn't finished,"
                " prev:%f  set_to:%f", *pPrevVolume, *pSetVolume);
        const float inc = (newVolume - *pPrevVolume) / ramp; // could be inf, nan, subnormal
        const float maxv = max(newVolume, *pPrevVolume); // could be inf, cannot be nan, subnormal

        if (isnormal(inc) // inc must be a normal number (no subnormals, infinite, nan)
                && maxv + inc != maxv) { // inc must make forward progress
            *pVolumeInc = inc;
            // ramp is set now.
            // Note: if newVolume is 0, then near the end of the ramp,
            // it may be possible that the ramped volume may be subnormal or
            // temporarily negative by a small amount or subnormal due to floating
            // point inaccuracies.
        } else {
            ramp = 0; // ramp not allowed
        }
    }

    // compute and check integer volume, no need to check negative values
    // The integer volume is limited to "unity_gain" to avoid wrapping and other
    // audio artifacts, so it never reaches the range limit of U4.28.
    // We safely use signed 16 and 32 bit integers here.
    const float scaledVolume = newVolume * AudioMixer::UNITY_GAIN_INT; // not neg, subnormal, nan
    const int32_t intVolume = (scaledVolume >= (float)AudioMixer::UNITY_GAIN_INT) ?
            AudioMixer::UNITY_GAIN_INT : (int32_t)scaledVolume;

    // set integer volume ramp
    if (ramp != 0) {
        // integer volume is U4.12 (to use 16 bit multiplies), but ramping uses U4.28.
        // when the ramp completes, *pIntPrevVolume is set to *pIntSetVolume << 16, so there
        // is no computational mismatch; hence equality is checked here.
        ALOGD_IF(*pIntPrevVolume != *pIntSetVolume << 16, "previous int ramp hasn't finished,"
                " prev:%d  set_to:%d", *pIntPrevVolume, *pIntSetVolume << 16);
        const int32_t inc = ((intVolume << 16) - *pIntPrevVolume) / ramp;

        if (inc != 0) { // inc must make forward progress
            *pIntVolumeInc = inc;
        } else {
            ramp = 0; // ramp not allowed
        }
    }

    // if no ramp, or ramp not allowed, then clear float and integer increments
    if (ramp == 0) {
        *pVolumeInc = 0;
        *pPrevVolume = newVolume;
        *pIntVolumeInc = 0;
        *pIntPrevVolume = intVolume << 16;
    }
    *pSetVolume = newVolume;
    *pIntSetVolume = intVolume;
    return true;
}
#ifdef MTK_AUDIO
static inline bool setVolumeRampVariables(float newVolume, int32_t ramp,
        int16_t *pIntSetVolume, int32_t *pIntPrevVolume, int32_t *pIntVolumeInc,
        float *pSetVolume, float *pPrevVolume, float *pVolumeInc, bool *pPreVolumeValid) {
    if (newVolume == *pSetVolume) {
        *pPreVolumeValid = true;
        return false;
    }
    /* set the floating point volume variables */
    bool PreVolumeValid = *pPreVolumeValid;
    if (ramp != 0 && *pPreVolumeValid ) {
        *pVolumeInc = (newVolume - *pSetVolume) / ramp;
        *pPrevVolume = *pSetVolume;
    } else {
        *pPreVolumeValid = true;
        *pVolumeInc = 0;
        *pPrevVolume = newVolume;
    }
    *pSetVolume = newVolume;

    /* set the legacy integer volume variables */
    int32_t intVolume = newVolume * AudioMixer::UNITY_GAIN_INT;
    if (intVolume > AudioMixer::UNITY_GAIN_INT) {
        intVolume = AudioMixer::UNITY_GAIN_INT;
    } else if (intVolume < 0) {
        ALOGE("negative volume %.7g", newVolume);
        intVolume = 0; // should never happen, but for safety check.
    }
    if (intVolume == *pIntSetVolume) {
        *pIntVolumeInc = 0;
        /* TODO: integer/float workaround: ignore floating volume ramp */
        *pVolumeInc = 0;
        *pPrevVolume = newVolume;
        return true;
    }
    if (ramp != 0 && PreVolumeValid ) {
        *pIntVolumeInc = ((intVolume - *pIntSetVolume) << 16) / ramp;
        *pIntPrevVolume = (*pIntVolumeInc == 0 ? intVolume : *pIntSetVolume) << 16;
    } else {
        *pIntVolumeInc = 0;
        *pIntPrevVolume = intVolume << 16;
    }
    *pIntSetVolume = intVolume;
    return true;
}
#endif

void AudioMixer::setParameter(int name, int target, int param, void *value)
{
    name -= TRACK0;
    ALOG_ASSERT(uint32_t(name) < MAX_NUM_TRACKS, "bad track name %d", name);
    track_t& track = mState.tracks[name];

    int valueInt = static_cast<int>(reinterpret_cast<uintptr_t>(value));
    int32_t *valueBuf = reinterpret_cast<int32_t*>(value);

    switch (target) {

    case TRACK:
        switch (param) {
        case CHANNEL_MASK: {
            const audio_channel_mask_t trackChannelMask =
                static_cast<audio_channel_mask_t>(valueInt);
            if (setChannelMasks(name, trackChannelMask, track.mMixerChannelMask)) {
                ALOGV("setParameter(TRACK, CHANNEL_MASK, %x)", trackChannelMask);
                invalidateState(1 << name);
            }
            } break;
        case MAIN_BUFFER:
            if (track.mainBuffer != valueBuf) {
                track.mainBuffer = valueBuf;
                ALOGV("setParameter(TRACK, MAIN_BUFFER, %p)", valueBuf);
                invalidateState(1 << name);
            }
            break;
        case AUX_BUFFER:
            if (track.auxBuffer != valueBuf) {
                track.auxBuffer = valueBuf;
                ALOGV("setParameter(TRACK, AUX_BUFFER, %p)", valueBuf);
                invalidateState(1 << name);
            }
            break;
        case FORMAT: {
            audio_format_t format = static_cast<audio_format_t>(valueInt);
            if (track.mFormat != format) {
                ALOG_ASSERT(audio_is_linear_pcm(format), "Invalid format %#x", format);
                track.mFormat = format;
                ALOGV("setParameter(TRACK, FORMAT, %#x)", format);
                track.prepareForReformat();
                invalidateState(1 << name);
            }
            } break;
        // FIXME do we want to support setting the downmix type from AudioFlinger?
        //         for a specific track? or per mixer?
        /* case DOWNMIX_TYPE:
            break          */
        case MIXER_FORMAT: {
            audio_format_t format = static_cast<audio_format_t>(valueInt);
            if (track.mMixerFormat != format) {
                track.mMixerFormat = format;
                ALOGV("setParameter(TRACK, MIXER_FORMAT, %#x)", format);
            }
            } break;
        case MIXER_CHANNEL_MASK: {
            const audio_channel_mask_t mixerChannelMask =
                    static_cast<audio_channel_mask_t>(valueInt);
            if (setChannelMasks(name, track.channelMask, mixerChannelMask)) {
                ALOGV("setParameter(TRACK, MIXER_CHANNEL_MASK, %#x)", mixerChannelMask);
                invalidateState(1 << name);
            }
            } break;
#ifdef TIME_STRETCH_ENABLE
            case DO_TIMESTRETCH:
                mState.tracks[name].initTrackMTKTimeStretch( valueInt);
                //ALOGD("DO_TIMESTRETCH track name %d ration %d",name, valueInt);
                break;
#endif
#ifdef MTK_AUDIO
        case STREAM_TYPE:
            track.mStreamType = (audio_stream_type_t)valueInt;
            break;
#endif
        case STEREO2MONO:
            //ALOGD("STEREO2MONO valueInt %d", valueInt);
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
            if(track.mSteroToMono != (BLOUD_S2M_MODE_ENUM)valueInt) {
                if(track.mpDRCObj != NULL) {
                    track.mpDRCObj->SetParameter(BLOUD_PAR_SET_STEREO_TO_MONO_MODE, (void *)valueInt);
                }
                ALOGD("setParameter(TRACK, STEREO2MONO, %#x)", valueInt);
            }
#endif
            track.mSteroToMono = (BLOUD_S2M_MODE_ENUM)valueInt;
            break;
        default:
            LOG_ALWAYS_FATAL("setParameter track: bad param %d", param);
        }
        break;

    case RESAMPLE:
        switch (param) {
        case SAMPLE_RATE:
            ALOG_ASSERT(valueInt > 0, "bad sample rate %d", valueInt);
            if (track.setResampler(uint32_t(valueInt), mSampleRate)) {
                ALOGD("setParameter(RESAMPLE, SAMPLE_RATE, %u)",
                        uint32_t(valueInt));
                ALOGV("setParameter(RESAMPLE, SAMPLE_RATE, %u)",
                        uint32_t(valueInt));
                invalidateState(1 << name);
            }
            break;
        case RESET:
            track.resetResampler();
            invalidateState(1 << name);
            break;
        case REMOVE:
            delete track.resampler;
            track.resampler = NULL;

#ifdef MTK_HIFI_AUDIO
            track.deinitBliSrc();
#endif

            track.sampleRate = mSampleRate;
            invalidateState(1 << name);
            break;
#ifdef MTK_HIFI_AUDIO
        case ADAPTOR:
            {
                int BliSrcAdaptorState = -1;
                uint32_t e0 = mState.enabledTracks;
                while (e0) {
                    const int i = 31 - __builtin_clz(e0);
                    e0 &= ~(1<<i);
                    track_t& t = mState.tracks[i];
                    if(t.mBliSrcAdaptorState != uint32_t(valueInt)) {
                        t.mBliSrcAdaptorState = uint32_t(valueInt);
                        BliSrcAdaptorState = uint32_t(valueInt);
                    }
                }
                if (BliSrcAdaptorState != -1) {
                    ALOGD("setParameter(TRACK, ADAPTOR, %#x)", BliSrcAdaptorState);
                }
            }
            break;
#endif
        default:
            LOG_ALWAYS_FATAL("setParameter resample: bad param %d", param);
        }
        break;

    case RAMP_VOLUME:
    case VOLUME:
        switch (param) {
        case AUXLEVEL:
            if (setVolumeRampVariables(*reinterpret_cast<float*>(value),
                    target == RAMP_VOLUME ? mState.frameCount : 0,
                    &track.auxLevel, &track.prevAuxLevel, &track.auxInc,
#ifdef MTK_AUDIO
                    &track.mAuxLevel, &track.mPrevAuxLevel, &track.mAuxInc, &track.mPreAuxValid ))
#else
                    &track.mAuxLevel, &track.mPrevAuxLevel, &track.mAuxInc ))
#endif
            {
                ALOGV("setParameter(%s, AUXLEVEL: %04x)",
                        target == VOLUME ? "VOLUME" : "RAMP_VOLUME", track.auxLevel);
                invalidateState(1 << name);
            }
            break;
        default:
            //ALOGD("%s, volume %f", __FUNCTION__, *reinterpret_cast<float*>(value));
            if ((unsigned)param >= VOLUME0 && (unsigned)param < VOLUME0 + MAX_NUM_VOLUMES) {
                if (setVolumeRampVariables(*reinterpret_cast<float*>(value),
                        target == RAMP_VOLUME ? mState.frameCount : 0,
                        &track.volume[param - VOLUME0], &track.prevVolume[param - VOLUME0],
                        &track.volumeInc[param - VOLUME0],
                        &track.mVolume[param - VOLUME0], &track.mPrevVolume[param - VOLUME0],
#ifdef MTK_AUDIO
                        &track.mVolumeInc[param - VOLUME0], &track.mPreVolumeValid[param - VOLUME0]))
#else
                        &track.mVolumeInc[param - VOLUME0]))
#endif
                {
                    ALOGV("setParameter(%s, VOLUME%d: %04x)",
                            target == VOLUME ? "VOLUME" : "RAMP_VOLUME", param - VOLUME0,
                                    track.volume[param - VOLUME0]);
                    invalidateState(1 << name);
                }
            } else {
                LOG_ALWAYS_FATAL("setParameter volume: bad param %d", param);
            }
        }
        break;
        case TIMESTRETCH:
            switch (param) {
            case PLAYBACK_RATE: {
                const AudioPlaybackRate *playbackRate =
                        reinterpret_cast<AudioPlaybackRate*>(value);
                ALOGW_IF(!isAudioPlaybackRateValid(*playbackRate),
                        "bad parameters speed %f, pitch %f",playbackRate->mSpeed,
                        playbackRate->mPitch);
                if (track.setPlaybackRate(*playbackRate)) {
                    ALOGV("setParameter(TIMESTRETCH, PLAYBACK_RATE, STRETCH_MODE, FALLBACK_MODE "
                            "%f %f %d %d",
                            playbackRate->mSpeed,
                            playbackRate->mPitch,
                            playbackRate->mStretchMode,
                            playbackRate->mFallbackMode);
                    // invalidateState(1 << name);
                }
            } break;
            default:
                LOG_ALWAYS_FATAL("setParameter timestretch: bad param %d", param);
            }
            break;

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    case DRC:
        switch (param) {
        case DEVICE:
            track.setDRCHandler(valueInt, mState.frameCount * FCC_2 * 2, mSampleRate);
            break;
        case UPDATE:
            track.updateDRCParam(mSampleRate);
            break;
        case RESET:
            track.resetDRC();
            break;
        default:
            LOG_FATAL("bad param");
        }
        break;
#endif
#ifdef MTK_BESSURROUND_ENABLE
        case SURROUND:
            switch (param) {
            case DEVICE:
                        if( track.mSurroundMixer !=NULL)
                        {track.mSurroundMixer->SetBesSurroundDevice(valueInt);}
                break;
            case BESSURND_ENABLE:
            case BESSURND_ENABLE_RAMP:
                        ALOGV("setParameter(%s, %d)",
                                param == BESSURND_ENABLE ? "BESSURND_ENABLE" : "BESSURND_ENABLE_RAMP",valueInt);
                        track.mSurroundEnable = valueInt;
                        //ALOGV("BESSURND_ENABLE %d",valueInt);
                        if(track.mSurroundMixer == NULL && track.mSurroundEnable == true )
                        {
                            ALOGV("init mSurroundMixer");
                           mState.tracks[name].prepareTrackForSurroundMix();
                        }
                        if( track.mSurroundMixer !=NULL)
                       {
                            ALOGV("setBesSurround On Off");
                            track.mSurroundMixer->SetBesSurroundOnOFF(valueInt);
                            if(param == BESSURND_ENABLE)
                            {
                                 track.mSurroundMixer->SetNoRamp();
                            }
                        }
                        break;
            case BESSURND_MODE:
                track.mSurroundMode = valueInt;
                //ALOGV("BESSURND_MODE%d", valueInt);
                if( track.mSurroundMixer !=NULL)
               {         track.mSurroundMixer->SetBesSurroundMode(valueInt);}
                break;
            case RESET:
                if( track.mSurroundMixer !=NULL)
                 {       track.mSurroundMixer->ResetBesSurround();}
                 break;
            default:
                LOG_FATAL("bad param");
            }
            break;
#endif

    default:
        LOG_ALWAYS_FATAL("setParameter: bad target %d", target);
    }
}
#ifdef MTK_AUDIO
        bool AudioMixer::track_t::setResampler(uint32_t trackSampleRate, uint32_t devSampleRate)
        {
            mDevSampleRate = devSampleRate;
#ifndef FIXED_RESAMPLER
            if (trackSampleRate != devSampleRate || resampler != NULL) {
                if (sampleRate != trackSampleRate) {
#endif
                    sampleRate = trackSampleRate;
                    if (resampler == NULL) {
                        ALOGV("Creating resampler from track %d Hz to device %d Hz",
                                trackSampleRate, devSampleRate);
                        AudioResampler::src_quality quality;
                        // force lowest quality level resampler if use case isn't music or video
                        // FIXME this is flawed for dynamic sample rates, as we choose the resampler
                        // quality level based on the initial ratio, but that could change later.
                        // Should have a way to distinguish tracks with static ratios vs. dynamic ratios.
                        if (!((trackSampleRate == 44100 && devSampleRate == 48000) ||
                              (trackSampleRate == 48000 && devSampleRate == 44100))) {
                            quality = AudioResampler::DYN_LOW_QUALITY;
                        } else {
                            quality = AudioResampler::DEFAULT_QUALITY;
                        }
                        quality = AudioResampler::DYN_HIGH_QUALITY; // Fixed to high quality
        // TODO: Remove MONO_HACK. Resampler sees #channels after the downmixer
        // but if none exists, it is the channel count (1 for mono).
        int resamplerChannelCount = downmixerBufferProvider != NULL
                ? mMixerChannelCount : channelCount;
                        ALOGD("Creating resampler:"
                                " format(%#x) channels(%d) devSampleRate(%u) quality(%d) resamplerChannelCount(%d)\n",
                                mMixerInFormat, resamplerChannelCount, devSampleRate, quality,resamplerChannelCount);
                        resampler = AudioResampler::create(
                                mMixerInFormat,
                                resamplerChannelCount,
                                devSampleRate, quality);
                        resampler->setLocalTimeFreq(sLocalTimeFreq);
                    }
#ifndef FIXED_RESAMPLER
                    else {
                        return false;
                    }
#endif
                    return true;
#ifndef FIXED_RESAMPLER
                }
            }

#ifdef MTK_HIFI_AUDIO
            initBliSrc();
#endif

            return false;
#endif
        }
#else

bool AudioMixer::track_t::setResampler(uint32_t trackSampleRate, uint32_t devSampleRate)
{
    if (trackSampleRate != devSampleRate || resampler != NULL) {
        if (sampleRate != trackSampleRate) {
            sampleRate = trackSampleRate;
            if (resampler == NULL) {
                ALOGV("Creating resampler from track %d Hz to device %d Hz",
                        trackSampleRate, devSampleRate);
                AudioResampler::src_quality quality;
                // force lowest quality level resampler if use case isn't music or video
                // FIXME this is flawed for dynamic sample rates, as we choose the resampler
                // quality level based on the initial ratio, but that could change later.
                // Should have a way to distinguish tracks with static ratios vs. dynamic ratios.
                if (isMusicRate(trackSampleRate)) {
                    quality = AudioResampler::DEFAULT_QUALITY;
                } else {
                    quality = AudioResampler::DYN_LOW_QUALITY;
                }

                // TODO: Remove MONO_HACK. Resampler sees #channels after the downmixer
                // but if none exists, it is the channel count (1 for mono).
                const int resamplerChannelCount = downmixerBufferProvider != NULL
                        ? mMixerChannelCount : channelCount;
                ALOGVV("Creating resampler:"
                        " format(%#x) channels(%d) devSampleRate(%u) quality(%d)\n",
                        mMixerInFormat, resamplerChannelCount, devSampleRate, quality);
                resampler = AudioResampler::create(
                        mMixerInFormat,
                        resamplerChannelCount,
                        devSampleRate, quality);
                resampler->setLocalTimeFreq(sLocalTimeFreq);
            }
            return true;
        }
    }
    return false;
}
#endif

bool AudioMixer::track_t::setPlaybackRate(const AudioPlaybackRate &playbackRate)
{
// TODO: Do MTK timestretch support this function??
    if ((mTimestretchBufferProvider == NULL &&
            fabs(playbackRate.mSpeed - mPlaybackRate.mSpeed) < AUDIO_TIMESTRETCH_SPEED_MIN_DELTA &&
            fabs(playbackRate.mPitch - mPlaybackRate.mPitch) < AUDIO_TIMESTRETCH_PITCH_MIN_DELTA) ||
            isAudioPlaybackRateEqual(playbackRate, mPlaybackRate)) {
        return false;
    }
    mPlaybackRate = playbackRate;
    if (mTimestretchBufferProvider == NULL) {
        // TODO: Remove MONO_HACK. Resampler sees #channels after the downmixer
        // but if none exists, it is the channel count (1 for mono).
        const int timestretchChannelCount = downmixerBufferProvider != NULL
                ? mMixerChannelCount : channelCount;
        mTimestretchBufferProvider = new TimestretchBufferProvider(timestretchChannelCount,
                mMixerInFormat, sampleRate, playbackRate);
        reconfigureBufferProviders();
    } else {
        reinterpret_cast<TimestretchBufferProvider*>(mTimestretchBufferProvider)
                ->setPlaybackRate(playbackRate);
    }
    return true;
}

/* Checks to see if the volume ramp has completed and clears the increment
 * variables appropriately.
 *
 * FIXME: There is code to handle int/float ramp variable switchover should it not
 * complete within a mixer buffer processing call, but it is preferred to avoid switchover
 * due to precision issues.  The switchover code is included for legacy code purposes
 * and can be removed once the integer volume is removed.
 *
 * It is not sufficient to clear only the volumeInc integer variable because
 * if one channel requires ramping, all channels are ramped.
 *
 * There is a bit of duplicated code here, but it keeps backward compatibility.
 */
inline void AudioMixer::track_t::adjustVolumeRamp(bool aux, bool useFloat)
{
    if (useFloat) {
        for (uint32_t i = 0; i < MAX_NUM_VOLUMES; i++) {
            if ((mVolumeInc[i] > 0 && mPrevVolume[i] + mVolumeInc[i] >= mVolume[i]) ||
                     (mVolumeInc[i] < 0 && mPrevVolume[i] + mVolumeInc[i] <= mVolume[i])) {
                volumeInc[i] = 0;
                prevVolume[i] = volume[i] << 16;
                mVolumeInc[i] = 0.;
                mPrevVolume[i] = mVolume[i];
            } else {
                //ALOGV("ramp: %f %f %f", mVolume[i], mPrevVolume[i], mVolumeInc[i]);
                prevVolume[i] = u4_28_from_float(mPrevVolume[i]);
            }
        }
    } else {
        for (uint32_t i = 0; i < MAX_NUM_VOLUMES; i++) {
            if (((volumeInc[i]>0) && (((prevVolume[i]+volumeInc[i])>>16) >= volume[i])) ||
                    ((volumeInc[i]<0) && (((prevVolume[i]+volumeInc[i])>>16) <= volume[i]))) {
                volumeInc[i] = 0;
                prevVolume[i] = volume[i] << 16;
                mVolumeInc[i] = 0.;
                mPrevVolume[i] = mVolume[i];
            } else {
                //ALOGV("ramp: %d %d %d", volume[i] << 16, prevVolume[i], volumeInc[i]);
                mPrevVolume[i]  = float_from_u4_28(prevVolume[i]);
            }
        }
    }
    /* TODO: aux is always integer regardless of output buffer type */
    if (aux) {
        if (((auxInc>0) && (((prevAuxLevel+auxInc)>>16) >= auxLevel)) ||
                ((auxInc<0) && (((prevAuxLevel+auxInc)>>16) <= auxLevel))) {
            auxInc = 0;
            prevAuxLevel = auxLevel << 16;
            mAuxInc = 0.;
            mPrevAuxLevel = mAuxLevel;
        } else {
            //ALOGV("aux ramp: %d %d %d", auxLevel << 16, prevAuxLevel, auxInc);
        }
    }
}

size_t AudioMixer::getUnreleasedFrames(int name) const
{
    name -= TRACK0;
    if (uint32_t(name) < MAX_NUM_TRACKS) {
        return mState.tracks[name].getUnreleasedFrames();
    }
    return 0;
}

void AudioMixer::setBufferProvider(int name, AudioBufferProvider* bufferProvider)
{
    name -= TRACK0;
    ALOG_ASSERT(uint32_t(name) < MAX_NUM_TRACKS, "bad track name %d", name);

    if (mState.tracks[name].mInputBufferProvider == bufferProvider) {
        return; // don't reset any buffer providers if identical.
    }
    if (mState.tracks[name].mReformatBufferProvider != NULL) {
        mState.tracks[name].mReformatBufferProvider->reset();
    } else if (mState.tracks[name].downmixerBufferProvider != NULL) {
        mState.tracks[name].downmixerBufferProvider->reset();
    } else if (mState.tracks[name].mPostDownmixReformatBufferProvider != NULL) {
        mState.tracks[name].mPostDownmixReformatBufferProvider->reset();
    } else if (mState.tracks[name].mTimestretchBufferProvider != NULL) {
        mState.tracks[name].mTimestretchBufferProvider->reset();
    }
    mState.tracks[name].mInputBufferProvider = bufferProvider;
    mState.tracks[name].reconfigureBufferProviders();
}


void AudioMixer::process(int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    mState.hook(&mState, pts);
}


void AudioMixer::process__validate(state_t* state, int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGW_IF(!state->needsChanged,
        "in process__validate() but nothing's invalid");

    uint32_t changed = state->needsChanged;
    state->needsChanged = 0; // clear the validation flag

    // recompute which tracks are enabled / disabled
    uint32_t enabled = 0;
    uint32_t disabled = 0;
    while (changed) {
        const int i = 31 - __builtin_clz(changed);
        const uint32_t mask = 1<<i;
        changed &= ~mask;
        track_t& t = state->tracks[i];
        (t.enabled ? enabled : disabled) |= mask;
    }
    state->enabledTracks &= ~disabled;
    state->enabledTracks |=  enabled;

    // compute everything we need...
    int countActiveTracks = 0;
    // TODO: fix all16BitsStereNoResample logic to
    // either properly handle muted tracks (it should ignore them)
    // or remove altogether as an obsolete optimization.
    bool all16BitsStereoNoResample = true;
#ifdef MTK_AUDIO
    bool resampling = true;
#else
    bool resampling = false;
#endif
    bool volumeRamp = false;
    uint32_t en = state->enabledTracks;
    while (en) {
        const int i = 31 - __builtin_clz(en);
        en &= ~(1<<i);

        countActiveTracks++;
        track_t& t = state->tracks[i];
        uint32_t n = 0;
        // FIXME can overflow (mask is only 3 bits)
        n |= NEEDS_CHANNEL_1 + t.channelCount - 1;
        if (t.doesResample()) {
            n |= NEEDS_RESAMPLE;
        }
        if (t.auxLevel != 0 && t.auxBuffer != NULL) {
            n |= NEEDS_AUX;
        }

        if (t.volumeInc[0]|t.volumeInc[1]) {
            volumeRamp = true;
        } else if (!t.doesResample() && t.volumeRL == 0) {
            n |= NEEDS_MUTE;
        }
        t.needs = n;

        if (n & NEEDS_MUTE) {
            t.hook = track__nop;
        } else {
            if (n & NEEDS_AUX) {
                all16BitsStereoNoResample = false;
            }
            if (n & NEEDS_RESAMPLE) {
                all16BitsStereoNoResample = false;
                resampling = true;
                t.hook = getTrackHook(TRACKTYPE_RESAMPLE, t.mMixerChannelCount,
                        t.mMixerInFormat, t.mMixerFormat);
                ALOGV_IF((n & NEEDS_CHANNEL_COUNT__MASK) > NEEDS_CHANNEL_2,
                        "Track %d needs downmix + resample", i);
            } else {
                if ((n & NEEDS_CHANNEL_COUNT__MASK) == NEEDS_CHANNEL_1){
                    t.hook = getTrackHook(
                            (t.mMixerChannelMask == AUDIO_CHANNEL_OUT_STEREO  // TODO: MONO_HACK
                                    && t.channelMask == AUDIO_CHANNEL_OUT_MONO)
                                ? TRACKTYPE_NORESAMPLEMONO : TRACKTYPE_NORESAMPLE,
                            t.mMixerChannelCount,
                            t.mMixerInFormat, t.mMixerFormat);
                    all16BitsStereoNoResample = false;
                }
                if ((n & NEEDS_CHANNEL_COUNT__MASK) >= NEEDS_CHANNEL_2){
                    t.hook = getTrackHook(TRACKTYPE_NORESAMPLE, t.mMixerChannelCount,
                            t.mMixerInFormat, t.mMixerFormat);
                    ALOGV_IF((n & NEEDS_CHANNEL_COUNT__MASK) > NEEDS_CHANNEL_2,
                            "Track %d needs downmix", i);
                    #ifdef MTK_AUDIO
                    if( t.mMixerInFormat !=AUDIO_FORMAT_PCM_16_BIT)
                        all16BitsStereoNoResample = false;
                        #endif
                }
            }
        }
    }

    // select the processing hooks
    state->hook = process__nop;
    if (countActiveTracks > 0) {
        if (resampling) {
            if (!state->outputTemp) {
                state->outputTemp = new int32_t[MAX_NUM_CHANNELS * state->frameCount];
            }
            if (!state->resampleTemp) {
                state->resampleTemp = new int32_t[MAX_NUM_CHANNELS * state->frameCount];
            }
            state->hook = process__genericResampling;
        } else {
            if (state->outputTemp) {
                delete [] state->outputTemp;
                state->outputTemp = NULL;
            }
            #ifdef MTK_BESSURROUND_ENABLE
            // do not delete resample temp for Bessurround
            #else
            if (state->resampleTemp) {
                delete [] state->resampleTemp;
                state->resampleTemp = NULL;
            }
            #endif
            state->hook = process__genericNoResampling;
            if (all16BitsStereoNoResample && !volumeRamp) {
                if (countActiveTracks == 1) {
                    const int i = 31 - __builtin_clz(state->enabledTracks);
                    track_t& t = state->tracks[i];
                    if ((t.needs & NEEDS_MUTE) == 0) {
                        // The check prevents a muted track from acquiring a process hook.
                        //
                        // This is dangerous if the track is MONO as that requires
                        // special case handling due to implicit channel duplication.
                        // Stereo or Multichannel should actually be fine here.
                        state->hook = getProcessHook(PROCESSTYPE_NORESAMPLEONETRACK,
                                t.mMixerChannelCount, t.mMixerInFormat, t.mMixerFormat);
                    }
                }
            }
        }
    }

    ALOGV("mixer configuration change: %d activeTracks (%08x) "
        "all16BitsStereoNoResample=%d, resampling=%d, volumeRamp=%d",
        countActiveTracks, state->enabledTracks,
        all16BitsStereoNoResample, resampling, volumeRamp);

   state->hook(state, pts);

    // Now that the volume ramp has been done, set optimal state and
    // track hooks for subsequent mixer process
    if (countActiveTracks > 0) {
        bool allMuted = true;
        uint32_t en = state->enabledTracks;
        while (en) {
            const int i = 31 - __builtin_clz(en);
            en &= ~(1<<i);
            track_t& t = state->tracks[i];
            if (!t.doesResample() && t.volumeRL == 0) {
                t.needs |= NEEDS_MUTE;
                t.hook = track__nop;
            } else {
                allMuted = false;
            }
        }
        if (allMuted) {
            state->hook = process__nop;
        } else if (all16BitsStereoNoResample) {
            if (countActiveTracks == 1) {
                const int i = 31 - __builtin_clz(state->enabledTracks);
                track_t& t = state->tracks[i];
                // Muted single tracks handled by allMuted above.
                state->hook = getProcessHook(PROCESSTYPE_NORESAMPLEONETRACK,
                        t.mMixerChannelCount, t.mMixerInFormat, t.mMixerFormat);
            }
        }
    }
}


void AudioMixer::track__genericResample(track_t* t, int32_t* out, size_t outFrameCount,
        int32_t* temp, int32_t* aux)
{
    ALOGVV("track__genericResample\n");
    t->resampler->setSampleRate(t->sampleRate);

    // ramp gain - resample to temp buffer and scale/mix in 2nd step
    if (aux != NULL) {
        // always resample with unity gain when sending to auxiliary buffer to be able
        // to apply send level after resampling
        t->resampler->setVolume(UNITY_GAIN_FLOAT, UNITY_GAIN_FLOAT);
        memset(temp, 0, outFrameCount * t->mMixerChannelCount * sizeof(int32_t));
        t->resampler->resample(temp, outFrameCount, t->bufferProvider);
        // add  BesSurround
        #ifdef MTK_BESSURROUND_ENABLE
        if(t ->mSurroundMixer){
                memcpy_by_audio_format(temp, AUDIO_FORMAT_PCM_32_BIT, temp, AUDIO_FORMAT_PCM_16_BIT ,outFrameCount* t->channelCount);
                t->mSurroundMixer->process(temp,(t->mDownMixBuffer),outFrameCount);
                memcpy_by_audio_format(temp, AUDIO_FORMAT_PCM_16_BIT, (t->mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT ,outFrameCount* 2);
                }
        #endif
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc)) {
            volumeRampStereo(t, out, outFrameCount, temp, aux);
        } else {
            volumeStereo(t, out, outFrameCount, temp, aux);
        }
    } else {
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1])) {
            t->resampler->setVolume(UNITY_GAIN_FLOAT, UNITY_GAIN_FLOAT);
            memset(temp, 0, outFrameCount * MAX_NUM_CHANNELS * sizeof(int32_t));
            t->resampler->resample(temp, outFrameCount, t->bufferProvider);
#ifdef MTK_BESSURROUND_ENABLE
            if(t ->mSurroundMixer){
                    memcpy_by_audio_format(temp, AUDIO_FORMAT_PCM_32_BIT, temp, AUDIO_FORMAT_PCM_16_BIT ,outFrameCount* t->channelCount);
                    t->mSurroundMixer->process(temp,(t->mDownMixBuffer),outFrameCount);
                    memcpy_by_audio_format(temp, AUDIO_FORMAT_PCM_16_BIT, (t->mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT ,outFrameCount* 2);
                    }
#endif
            volumeRampStereo(t, out, outFrameCount, temp, aux);
        }

        // constant gain
        else {
            t->resampler->setVolume(t->mVolume[0], t->mVolume[1]);
            t->resampler->resample(out, outFrameCount, t->bufferProvider);
#ifdef MTK_BESSURROUND_ENABLE
            if(t ->mSurroundMixer){
                    memcpy_by_audio_format(out, AUDIO_FORMAT_PCM_32_BIT, out, AUDIO_FORMAT_PCM_16_BIT ,outFrameCount* t->channelCount);
                    t->mSurroundMixer->process(out,(t->mDownMixBuffer),outFrameCount);
                    memcpy_by_audio_format(out, AUDIO_FORMAT_PCM_16_BIT, (t->mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT ,outFrameCount* 2);
                    }
#endif
        }
    }
#ifdef TIME_STRETCH_ENABLE
            t->mTrackPlayed = 1;
#endif
}

void AudioMixer::track__nop(track_t* t __unused, int32_t* out __unused,
        size_t outFrameCount __unused, int32_t* temp __unused, int32_t* aux __unused)
{
}

void AudioMixer::volumeRampStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp,
        int32_t* aux)
{
    int32_t vl = t->prevVolume[0];
    int32_t vr = t->prevVolume[1];
    const int32_t vlInc = t->volumeInc[0];
    const int32_t vrInc = t->volumeInc[1];

    //ALOGD("[0] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
    //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
    //       (vl + vlInc*frameCount)/65536.0f, frameCount);

    // ramp volume
    if (CC_UNLIKELY(aux != NULL)) {
        int32_t va = t->prevAuxLevel;
        const int32_t vaInc = t->auxInc;
        int32_t l;
        int32_t r;

        do {
            l = (*temp++ >> 12);
            r = (*temp++ >> 12);
            *out++ += (vl >> 16) * l;
            *out++ += (vr >> 16) * r;
            *aux++ += (va >> 17) * (l + r);
            vl += vlInc;
            vr += vrInc;
            va += vaInc;
        } while (--frameCount);
        t->prevAuxLevel = va;
    } else {
        do {
            *out++ += (vl >> 16) * (*temp++ >> 12);
            *out++ += (vr >> 16) * (*temp++ >> 12);
            vl += vlInc;
            vr += vrInc;
        } while (--frameCount);
    }
    t->prevVolume[0] = vl;
    t->prevVolume[1] = vr;
    t->adjustVolumeRamp(aux != NULL);
}

void AudioMixer::volumeStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp,
        int32_t* aux)
{
    const int16_t vl = t->volume[0];
    const int16_t vr = t->volume[1];

    if (CC_UNLIKELY(aux != NULL)) {
        const int16_t va = t->auxLevel;
        do {
            int16_t l = (int16_t)(*temp++ >> 12);
            int16_t r = (int16_t)(*temp++ >> 12);
            out[0] = mulAdd(l, vl, out[0]);
            int16_t a = (int16_t)(((int32_t)l + r) >> 1);
            out[1] = mulAdd(r, vr, out[1]);
            out += 2;
            aux[0] = mulAdd(a, va, aux[0]);
            aux++;
        } while (--frameCount);
    } else {
        do {
            int16_t l = (int16_t)(*temp++ >> 12);
            int16_t r = (int16_t)(*temp++ >> 12);
            out[0] = mulAdd(l, vl, out[0]);
            out[1] = mulAdd(r, vr, out[1]);
            out += 2;
        } while (--frameCount);
    }
}

void AudioMixer::track__16BitsStereo(track_t* t, int32_t* out, size_t frameCount,
        int32_t* temp __unused, int32_t* aux)
{
    ALOGVV("track__16BitsStereo\n");
    const int16_t *in = static_cast<const int16_t *>(t->in);
#ifdef MTK_BESSURROUND_ENABLE
    if(t ->mSurroundMixer){
            memcpy_by_audio_format(temp, AUDIO_FORMAT_PCM_32_BIT, in, AUDIO_FORMAT_PCM_16_BIT ,frameCount* t->channelCount);
            t->mSurroundMixer->process(temp,(t->mDownMixBuffer),frameCount);
            memcpy_by_audio_format((int16_t*)in, AUDIO_FORMAT_PCM_16_BIT, (t->mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT ,frameCount* 2);
            }
#endif

    if (CC_UNLIKELY(aux != NULL)) {
        int32_t l;
        int32_t r;
        // ramp gain
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc)) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            int32_t va = t->prevAuxLevel;
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];
            const int32_t vaInc = t->auxInc;
            // ALOGD("[1] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //        (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                l = (int32_t)*in++;
                r = (int32_t)*in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * r;
                *aux++ += (va >> 17) * (l + r);
                vl += vlInc;
                vr += vrInc;
                va += vaInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->prevAuxLevel = va;
            t->adjustVolumeRamp(true);
        }

        // constant gain
        else {
            const uint32_t vrl = t->volumeRL;
            const int16_t va = (int16_t)t->auxLevel;
            do {
                uint32_t rl = *reinterpret_cast<const uint32_t *>(in);
                int16_t a = (int16_t)(((int32_t)in[0] + in[1]) >> 1);
                in += 2;
                out[0] = mulAddRL(1, rl, vrl, out[0]);
                out[1] = mulAddRL(0, rl, vrl, out[1]);
                out += 2;
                aux[0] = mulAdd(a, va, aux[0]);
                aux++;
            } while (--frameCount);
        }
    } else {
        // ramp gain
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1])) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];

            // ALOGD("[1] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //        (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                *out++ += (vl >> 16) * (int32_t) *in++;
                *out++ += (vr >> 16) * (int32_t) *in++;
                vl += vlInc;
                vr += vrInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->adjustVolumeRamp(false);
        }

        // constant gain
        else {
            const uint32_t vrl = t->volumeRL;
            do {
                uint32_t rl = *reinterpret_cast<const uint32_t *>(in);
                in += 2;
                out[0] = mulAddRL(1, rl, vrl, out[0]);
                out[1] = mulAddRL(0, rl, vrl, out[1]);
                out += 2;
            } while (--frameCount);
        }
    }
    t->in = in;
}

void AudioMixer::track__16BitsMono(track_t* t, int32_t* out, size_t frameCount,
        int32_t* temp __unused, int32_t* aux)
{
    ALOGVV("track__16BitsMono\n");
    const int16_t *in = static_cast<int16_t const *>(t->in);

    if (CC_UNLIKELY(aux != NULL)) {
        // ramp gain
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc)) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            int32_t va = t->prevAuxLevel;
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];
            const int32_t vaInc = t->auxInc;

            // ALOGD("[2] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //         t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //         (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                int32_t l = *in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * l;
                *aux++ += (va >> 16) * l;
                vl += vlInc;
                vr += vrInc;
                va += vaInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->prevAuxLevel = va;
            t->adjustVolumeRamp(true);
        }
        // constant gain
        else {
            const int16_t vl = t->volume[0];
            const int16_t vr = t->volume[1];
            const int16_t va = (int16_t)t->auxLevel;
            do {
                int16_t l = *in++;
                out[0] = mulAdd(l, vl, out[0]);
                out[1] = mulAdd(l, vr, out[1]);
                out += 2;
                aux[0] = mulAdd(l, va, aux[0]);
                aux++;
            } while (--frameCount);
        }
    } else {
        // ramp gain
        if (CC_UNLIKELY(t->volumeInc[0]|t->volumeInc[1])) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];

            // ALOGD("[2] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //         t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //         (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                int32_t l = *in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * l;
                vl += vlInc;
                vr += vrInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->adjustVolumeRamp(false);
        }
        // constant gain
        else {
            const int16_t vl = t->volume[0];
            const int16_t vr = t->volume[1];
            do {
                int16_t l = *in++;
                out[0] = mulAdd(l, vl, out[0]);
                out[1] = mulAdd(l, vr, out[1]);
                out += 2;
            } while (--frameCount);
        }
    }
    t->in = in;
}

// no-op case
void AudioMixer::process__nop(state_t* state, int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("process__nop\n");
    uint32_t e0 = state->enabledTracks;
    while (e0) {
        // process by group of tracks with same output buffer to
        // avoid multiple memset() on same buffer
        uint32_t e1 = e0, e2 = e0;
        int i = 31 - __builtin_clz(e1);
        {
            track_t& t1 = state->tracks[i];
            e2 &= ~(1<<i);
            while (e2) {
                i = 31 - __builtin_clz(e2);
                e2 &= ~(1<<i);
                track_t& t2 = state->tracks[i];
                if (CC_UNLIKELY(t2.mainBuffer != t1.mainBuffer)) {
                    e1 &= ~(1<<i);
                }
            }
            e0 &= ~(e1);

            memset(t1.mainBuffer, 0, state->frameCount * t1.mMixerChannelCount
                    * audio_bytes_per_sample(t1.mMixerFormat));
        }

        while (e1) {
            i = 31 - __builtin_clz(e1);
            e1 &= ~(1<<i);
            {
                track_t& t3 = state->tracks[i];
                size_t outFrames = state->frameCount;
                while (outFrames) {
                    t3.buffer.frameCount = outFrames;
                    int64_t outputPTS = calculateOutputPTS(
                        t3, pts, state->frameCount - outFrames);
                    t3.bufferProvider->getNextBuffer(&t3.buffer, outputPTS);
                    if (t3.buffer.raw == NULL) break;
                    outFrames -= t3.buffer.frameCount;
                    t3.bufferProvider->releaseBuffer(&t3.buffer);
                }
            }
        }
    }
}

// generic code without resampling
void AudioMixer::process__genericNoResampling(state_t* state, int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("process__genericNoResampling\n");
    int32_t outTemp[BLOCKSIZE * MAX_NUM_CHANNELS] __attribute__((aligned(32)));

    // acquire each track's buffer
    uint32_t enabledTracks = state->enabledTracks;
    uint32_t e0 = enabledTracks;
    while (e0) {
        const int i = 31 - __builtin_clz(e0);
        e0 &= ~(1<<i);
        track_t& t = state->tracks[i];
        t.buffer.frameCount = state->frameCount;
        t.bufferProvider->getNextBuffer(&t.buffer, pts);
        t.frameCount = t.buffer.frameCount;
        t.in = t.buffer.raw;
    }

    e0 = enabledTracks;
    while (e0) {
        // process by group of tracks with same output buffer to
        // optimize cache use
        uint32_t e1 = e0, e2 = e0;
        int j = 31 - __builtin_clz(e1);
        track_t& t1 = state->tracks[j];
        e2 &= ~(1<<j);
        while (e2) {
            j = 31 - __builtin_clz(e2);
            e2 &= ~(1<<j);
            track_t& t2 = state->tracks[j];
            if (CC_UNLIKELY(t2.mainBuffer != t1.mainBuffer)) {
                e1 &= ~(1<<j);
            }
        }
        e0 &= ~(e1);
        // this assumes output 16 bits stereo, no resampling
        int32_t *out = t1.mainBuffer;
        size_t numFrames = 0;
        do {
            memset(outTemp, 0, sizeof(outTemp));
            e2 = e1;
            while (e2) {
                const int i = 31 - __builtin_clz(e2);
                e2 &= ~(1<<i);
                track_t& t = state->tracks[i];
                size_t outFrames = BLOCKSIZE;
                int32_t *aux = NULL;
                if (CC_UNLIKELY(t.needs & NEEDS_AUX)) {
                    aux = t.auxBuffer + numFrames;
                }

#ifdef FULL_FRAMECOUNT
                int8_t *tempBuffer = reinterpret_cast<int8_t*>(state->nonResampleTemp);
                int channelCount = t.channelCount;
                int32_t channelSize = channelCount * audio_bytes_per_sample(t.mMixerInFormat);
                memset(tempBuffer, 0, BLOCKSIZE*channelSize);
#endif
                while (outFrames) {
                    // t.in == NULL can happen if the track was flushed just after having
                    // been enabled for mixing.
                   if (t.in == NULL) {
                        enabledTracks &= ~(1<<i);
                        e1 &= ~(1<<i);
                        break;
                    }
                    size_t inFrames = (t.frameCount > outFrames)?outFrames:t.frameCount;
                    if (inFrames > 0) {
#ifdef FULL_FRAMECOUNT
                        int32_t sampleSize = inFrames * channelSize;
                        memcpy(tempBuffer+ (BLOCKSIZE - outFrames)*channelSize, t.in, sampleSize);
                        t.in = ((int8_t *)t.in) + sampleSize;
#else
                        t.hook(&t, outTemp + (BLOCKSIZE - outFrames) * t.mMixerChannelCount,
                                inFrames, state->resampleTemp, aux);
#endif
                        t.frameCount -= inFrames;
                        outFrames -= inFrames;

                        #ifndef FULL_FRAMECOUNT
                        if (CC_UNLIKELY(aux != NULL)) {
                            aux += inFrames;
                        }
                        #endif
                    }
                    if (t.frameCount == 0 && outFrames) {
                        t.bufferProvider->releaseBuffer(&t.buffer);
                        t.buffer.frameCount = (state->frameCount - numFrames) -
                                (BLOCKSIZE - outFrames);
                        int64_t outputPTS = calculateOutputPTS(
                            t, pts, numFrames + (BLOCKSIZE - outFrames));
                        t.bufferProvider->getNextBuffer(&t.buffer, outputPTS);
                        t.in = t.buffer.raw;
                        if (t.in == NULL) {
                            enabledTracks &= ~(1<<i);
                            e1 &= ~(1<<i);
                            break;
                        }
                        t.frameCount = t.buffer.frameCount;
                    }
                }
#ifdef FULL_FRAMECOUNT
               t.hook(&t, outTemp, BLOCKSIZE, state->nonResampleTemp, aux);
                if (CC_UNLIKELY(aux != NULL)) {
                aux += BLOCKSIZE;
                    }
#endif
            }

            size_t framecount = BLOCKSIZE;

#ifdef MTK_HIFI_AUDIO
            framecount = framecount >> t1.mBliSrcAdaptorShift;
#endif
#ifdef FULL_FRAMECOUNT
            convertMixerFormat(out, t1.mMixerFormat, outTemp, t1.mMixerInFormat,
                    framecount * t1.mMixerChannelCount);
            //ALOGD("%s, framecount %d, mMixerChannelCount %d, mMixerFormat %d", __FUNCTION__, framecount, t1.mMixerChannelCount, t1.mMixerFormat);
            //MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, framecount*t1.mMixerChannelCount*audio_bytes_per_sample(t1.mMixerFormat));
            MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, framecount*t1.mMixerChannelCount*audio_bytes_per_sample(t1.mMixerFormat),
                t1.mMixerFormat, t1.sampleRate, t1.mMixerChannelCount );

            // TODO: fix ugly casting due to choice of out pointer type
            out = reinterpret_cast<int32_t*>((uint8_t*)out
                    + framecount * t1.mMixerChannelCount
                        * audio_bytes_per_sample(t1.mMixerFormat));
#else
            convertMixerFormat(out, t1.mMixerFormat, outTemp, t1.mMixerInFormat,
                    BLOCKSIZE * t1.mMixerChannelCount);
            // TODO: fix ugly casting due to choice of out pointer type
            out = reinterpret_cast<int32_t*>((uint8_t*)out
                    + BLOCKSIZE * t1.mMixerChannelCount
                        * audio_bytes_per_sample(t1.mMixerFormat));
#endif
            numFrames += BLOCKSIZE;
        } while (numFrames < state->frameCount);
    }

    // release each track's buffer
    e0 = enabledTracks;
    while (e0) {
        const int i = 31 - __builtin_clz(e0);
        e0 &= ~(1<<i);
        track_t& t = state->tracks[i];
        t.bufferProvider->releaseBuffer(&t.buffer);
    }
}


// generic code with resampling
void AudioMixer::process__genericResampling(state_t* state, int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("process__genericResampling\n");
    // this const just means that local variable outTemp doesn't change
    int32_t* const outTemp = state->outputTemp;
    size_t numFrames = state->frameCount;

    uint32_t e0 = state->enabledTracks;
    while (e0) {
        // process by group of tracks with same output buffer
        // to optimize cache use
        uint32_t e1 = e0, e2 = e0;
        int j = 31 - __builtin_clz(e1);
        track_t& t1 = state->tracks[j];
        e2 &= ~(1<<j);
        while (e2) {
            j = 31 - __builtin_clz(e2);
            e2 &= ~(1<<j);
            track_t& t2 = state->tracks[j];
            if (CC_UNLIKELY(t2.mainBuffer != t1.mainBuffer)) {
                e1 &= ~(1<<j);
            }
        }
        e0 &= ~(e1);
        int32_t *out = t1.mainBuffer;
        #ifdef MTK_BESSURROUND_ENABLE
        memset(outTemp, 0, sizeof(*outTemp) *( t1.channelCount >= t1.mMixerChannelCount ? t1.channelCount :t1.mMixerChannelCount )* state->frameCount);
        #else
        memset(outTemp, 0, sizeof(*outTemp) * t1.mMixerChannelCount * state->frameCount);
        #endif
        while (e1) {
            const int i = 31 - __builtin_clz(e1);
            e1 &= ~(1<<i);
            track_t& t = state->tracks[i];
            int32_t *aux = NULL;
            if (CC_UNLIKELY(t.needs & NEEDS_AUX)) {
                aux = t.auxBuffer;
            }

            // this is a little goofy, on the resampling case we don't
            // acquire/release the buffers because it's done by
            // the resampler.
            if (t.needs & NEEDS_RESAMPLE) {
                t.resampler->setPTS(pts);
                t.hook(&t, outTemp, numFrames, state->resampleTemp, aux);
            } else {

                size_t outFrames = 0;

#ifdef FULL_FRAMECOUNT
                int8_t *tempBuffer = reinterpret_cast<int8_t*>(state->nonResampleTemp);
                int channelCount = ((1==t.channelCount) && (2==t.mMixerChannelCount)) ? 1 : t.mMixerChannelCount;
                #ifdef MTK_BESSURROUND_ENABLE
                channelCount = t.channelCount >=2?  t.channelCount : channelCount;
                #endif
                ALOGVV("channelCount %d,  t.channelCount %d ", channelCount ,    t.channelCount);
                int32_t channelSize = channelCount * audio_bytes_per_sample(t.mMixerInFormat);
                memset(tempBuffer, 0, numFrames*channelSize);
#endif
                while (outFrames < numFrames) {
                    t.buffer.frameCount = numFrames - outFrames;
                    int64_t outputPTS = calculateOutputPTS(t, pts, outFrames);
                    t.bufferProvider->getNextBuffer(&t.buffer, outputPTS);
                    t.in = t.buffer.raw;
                    // t.in == NULL can happen if the track was flushed just after having
                    // been enabled for mixing.
                    if (t.in == NULL) break;
#ifndef FULL_FRAMECOUNT
                    if (CC_UNLIKELY(aux != NULL)) {
                        aux += outFrames;
                    }
#endif

#ifdef FULL_FRAMECOUNT
                    int32_t sampleSize = t.buffer.frameCount * channelSize;
                    memcpy(tempBuffer+ outFrames*channelSize, t.in, sampleSize);
                    t.in = ((int8_t *)t.in) + sampleSize;
#else
                    t.hook(&t, outTemp + outFrames * t.mMixerChannelCount, t.buffer.frameCount,
                            state->resampleTemp, aux);
#endif
                    outFrames += t.buffer.frameCount;
                    t.bufferProvider->releaseBuffer(&t.buffer);
                }
#ifdef FULL_FRAMECOUNT
                t.hook(&t, outTemp, numFrames, state->nonResampleTemp, aux);
                if (CC_UNLIKELY(aux != NULL)) {
                aux += numFrames;
                    }
#endif
            }
        }


        size_t framecount = numFrames;

#ifdef MTK_HIFI_AUDIO
        framecount = framecount >> t1.mBliSrcAdaptorShift;
#endif

#ifdef FULL_FRAMECOUNT
        convertMixerFormat(out, t1.mMixerFormat,
                outTemp, t1.mMixerInFormat, framecount * t1.mMixerChannelCount);
#else
        convertMixerFormat(out, t1.mMixerFormat,
                outTemp, t1.mMixerInFormat, numFrames * t1.mMixerChannelCount);
#endif

        //ALOGD("%s, framecount %d, mMixerChannelCount %d, mMixerFormat %d", __FUNCTION__, framecount, t1.mMixerChannelCount, t1.mMixerFormat);
        //MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, framecount*t1.mMixerChannelCount*audio_bytes_per_sample(t1.mMixerFormat));
        MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, framecount*t1.mMixerChannelCount*audio_bytes_per_sample(t1.mMixerFormat),
            t1.mMixerFormat, t1.mDevSampleRate, t1.mMixerChannelCount );

    }
}

// one track, 16 bits stereo without resampling is the most common case
void AudioMixer::process__OneTrack16BitsStereoNoResampling(state_t* state,
                                                           int64_t pts)
{
    ALOGVV("process__OneTrack16BitsStereoNoResampling\n");
    // This method is only called when state->enabledTracks has exactly
    // one bit set.  The asserts below would verify this, but are commented out
    // since the whole point of this method is to optimize performance.
    //ALOG_ASSERT(0 != state->enabledTracks, "no tracks enabled");
    const int i = 31 - __builtin_clz(state->enabledTracks);
    //ALOG_ASSERT((1 << i) == state->enabledTracks, "more than 1 track enabled");
    const track_t& t = state->tracks[i];

    AudioBufferProvider::Buffer& b(t.buffer);

    int32_t* out = t.mainBuffer;
    float *fout = reinterpret_cast<float*>(out);
    size_t numFrames = state->frameCount;

    const int16_t vl = t.volume[0];
    const int16_t vr = t.volume[1];
    const uint32_t vrl = t.volumeRL;
    while (numFrames) {
        b.frameCount = numFrames;
        int64_t outputPTS = calculateOutputPTS(t, pts, out - t.mainBuffer);
        t.bufferProvider->getNextBuffer(&b, outputPTS);
        const int16_t *in = b.i16;

        // in == NULL can happen if the track was flushed just after having
        // been enabled for mixing.
        if (in == NULL || (((uintptr_t)in) & 3)) {
            memset(out, 0, numFrames
                    * t.mMixerChannelCount * audio_bytes_per_sample(t.mMixerFormat));
            ALOGE_IF((((uintptr_t)in) & 3),
                    "process__OneTrack16BitsStereoNoResampling: misaligned buffer"
                    " %p track %d, channels %d, needs %08x, volume %08x vfl %f vfr %f",
                    in, i, t.channelCount, t.needs, vrl, t.mVolume[0], t.mVolume[1]);
            return;
        }
        size_t outFrames = b.frameCount;
#ifdef MTK_BESSURROUND_ENABLE
        if(t.mSurroundMixer){
               memcpy_by_audio_format(state->resampleTemp , AUDIO_FORMAT_PCM_32_BIT, in,t.mMixerFormat ,outFrames* t.channelCount);
               t.mSurroundMixer->process(state->resampleTemp,(t.mDownMixBuffer) ,outFrames);
               memcpy_by_audio_format((void*)in,t.mMixerFormat, (t.mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT, outFrames*2 );
               }
#endif

        switch (t.mMixerFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            do {
                uint32_t rl = *reinterpret_cast<const uint32_t *>(in);
                in += 2;
                int32_t l = mulRL(1, rl, vrl);
                int32_t r = mulRL(0, rl, vrl);
                *fout++ = float_from_q4_27(l);
                *fout++ = float_from_q4_27(r);
                // Note: In case of later int16_t sink output,
                // conversion and clamping is done by memcpy_to_i16_from_float().
            } while (--outFrames);
            break;
        case AUDIO_FORMAT_PCM_16_BIT:
            if (CC_UNLIKELY(uint32_t(vl) > UNITY_GAIN_INT || uint32_t(vr) > UNITY_GAIN_INT)) {
                // volume is boosted, so we might need to clamp even though
                // we process only one track.
                do {
                    uint32_t rl = *reinterpret_cast<const uint32_t *>(in);
                    in += 2;
                    int32_t l = mulRL(1, rl, vrl) >> 12;
                    int32_t r = mulRL(0, rl, vrl) >> 12;
                    // clamping...
                    l = clamp16(l);
                    r = clamp16(r);
                    *out++ = (r<<16) | (l & 0xFFFF);
                } while (--outFrames);
            } else {
                do {
                    uint32_t rl = *reinterpret_cast<const uint32_t *>(in);
                    in += 2;
                    int32_t l = mulRL(1, rl, vrl) >> 12;
                    int32_t r = mulRL(0, rl, vrl) >> 12;
                    *out++ = (r<<16) | (l & 0xFFFF);
                } while (--outFrames);
            }
            break;
        default:
            LOG_ALWAYS_FATAL("bad mixer format: %d", t.mMixerFormat);
        }
        numFrames -= b.frameCount;
        t.bufferProvider->releaseBuffer(&b);
    }
}

int64_t AudioMixer::calculateOutputPTS(const track_t& t, int64_t basePTS,
                                       int outputFrameIndex)
{
    if (AudioBufferProvider::kInvalidPTS == basePTS) {
        return AudioBufferProvider::kInvalidPTS;
    }

    return basePTS + ((outputFrameIndex * sLocalTimeFreq) / t.sampleRate);
}

/*static*/ uint64_t AudioMixer::sLocalTimeFreq;
/*static*/ pthread_once_t AudioMixer::sOnceControl = PTHREAD_ONCE_INIT;

/*static*/ void AudioMixer::sInitRoutine()
{
    LocalClock lc;
    sLocalTimeFreq = lc.getLocalFreq(); // for the resampler

    DownmixerBufferProvider::init(); // for the downmixer
}

/* TODO: consider whether this level of optimization is necessary.
 * Perhaps just stick with a single for loop.
 */

// Needs to derive a compile time constant (constexpr).  Could be targeted to go
// to a MONOVOL mixtype based on MAX_NUM_VOLUMES, but that's an unnecessary complication.
#define MIXTYPE_MONOVOL(mixtype) (mixtype == MIXTYPE_MULTI ? MIXTYPE_MULTI_MONOVOL : \
        mixtype == MIXTYPE_MULTI_SAVEONLY ? MIXTYPE_MULTI_SAVEONLY_MONOVOL : mixtype)

/* MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
template <int MIXTYPE,
        typename TO, typename TI, typename TV, typename TA, typename TAV>
static void volumeRampMulti(uint32_t channels, TO* out, size_t frameCount,
        const TI* in, TA* aux, TV *vol, const TV *volinc, TAV *vola, TAV volainc)
{
    switch (channels) {
    case 1:
        volumeRampMulti<MIXTYPE, 1>(out, frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 2:
        volumeRampMulti<MIXTYPE, 2>(out, frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 3:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 3>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 4:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 4>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 5:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 5>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 6:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 6>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 7:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 7>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    case 8:
        volumeRampMulti<MIXTYPE_MONOVOL(MIXTYPE), 8>(out,
                frameCount, in, aux, vol, volinc, vola, volainc);
        break;
    }
}

/* MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
template <int MIXTYPE,
        typename TO, typename TI, typename TV, typename TA, typename TAV>
static void volumeMulti(uint32_t channels, TO* out, size_t frameCount,
        const TI* in, TA* aux, const TV *vol, TAV vola)
{
    switch (channels) {
    case 1:
        volumeMulti<MIXTYPE, 1>(out, frameCount, in, aux, vol, vola);
        break;
    case 2:
        volumeMulti<MIXTYPE, 2>(out, frameCount, in, aux, vol, vola);
        break;
    case 3:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 3>(out, frameCount, in, aux, vol, vola);
        break;
    case 4:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 4>(out, frameCount, in, aux, vol, vola);
        break;
    case 5:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 5>(out, frameCount, in, aux, vol, vola);
        break;
    case 6:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 6>(out, frameCount, in, aux, vol, vola);
        break;
    case 7:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 7>(out, frameCount, in, aux, vol, vola);
        break;
    case 8:
        volumeMulti<MIXTYPE_MONOVOL(MIXTYPE), 8>(out, frameCount, in, aux, vol, vola);
        break;
    }
}

/* MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * USEFLOATVOL (set to true if float volume is used)
 * ADJUSTVOL   (set to true if volume ramp parameters needs adjustment afterwards)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
template <int MIXTYPE, bool USEFLOATVOL, bool ADJUSTVOL,
    typename TO, typename TI, typename TA>
void AudioMixer::volumeMix(TO *out, size_t outFrames,
        const TI *in, TA *aux, bool ramp, AudioMixer::track_t *t)
{
    if (USEFLOATVOL) {
        if (ramp) {
#ifdef MTK_AUDIO
            if ( t->mVolumeInc[0] != 0 )
            {
                ALOGD("%s, stream %d, vol %f, volinc %f = %f", __FUNCTION__,
                    t->mStreamType, t->mPrevVolume[0], t->mVolumeInc[0],
                    t->mPrevVolume[0] + t->mVolumeInc[0] * outFrames );
            }
#endif
            volumeRampMulti<MIXTYPE>(t->mMixerChannelCount, out, outFrames, in, aux,
                    t->mPrevVolume, t->mVolumeInc, &t->prevAuxLevel, t->auxInc);
            if (ADJUSTVOL) {
                t->adjustVolumeRamp(aux != NULL, true);
            }
        } else {
            volumeMulti<MIXTYPE>(t->mMixerChannelCount, out, outFrames, in, aux,
                    t->mVolume, t->auxLevel);
        }
    } else {
        if (ramp) {
#ifdef MTK_AUDIO
            if ( t->volumeInc[0] != 0 )
            {
                ALOGD("%s, stream %d, vol %d, volinc %d = %d", __FUNCTION__,
                    t->mStreamType, t->prevVolume[0], t->volumeInc[0],
                    t->prevVolume[0] + t->volumeInc[0] * outFrames );
            }
#endif
            volumeRampMulti<MIXTYPE>(t->mMixerChannelCount, out, outFrames, in, aux,
                    t->prevVolume, t->volumeInc, &t->prevAuxLevel, t->auxInc);
            if (ADJUSTVOL) {
                t->adjustVolumeRamp(aux != NULL);
            }
        } else {
            volumeMulti<MIXTYPE>(t->mMixerChannelCount, out, outFrames, in, aux,
                    t->volume, t->auxLevel);
        }
    }
}

/* This process hook is called when there is a single track without
 * aux buffer, volume ramp, or resampling.
 * TODO: Update the hook selection: this can properly handle aux and ramp.
 *
 * MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
template <int MIXTYPE, typename TO, typename TI, typename TA>
void AudioMixer::process_NoResampleOneTrack(state_t* state, int64_t pts)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("process_NoResampleOneTrack\n");
    // CLZ is faster than CTZ on ARM, though really not sure if true after 31 - clz.
    const int i = 31 - __builtin_clz(state->enabledTracks);
    ALOG_ASSERT((1 << i) == state->enabledTracks, "more than 1 track enabled");
    track_t *t = &state->tracks[i];
#ifndef MTK_BESSURROUND_ENABLE
    const uint32_t channels = t->mMixerChannelCount;
#else
    const uint32_t channels = t->channelCount;
#endif

#ifdef FULL_FRAMECOUNT
    TI* out = reinterpret_cast<TI*>(state->nonResampleTemp);
    TO* final_out = reinterpret_cast<TO*>(t->mainBuffer);
#else
    TO* out = reinterpret_cast<TO*>(t->mainBuffer);
#endif
    TA* aux = reinterpret_cast<TA*>(t->auxBuffer);
    const bool ramp = t->needsRamp();

    for (size_t numFrames = state->frameCount; numFrames; ) {
        AudioBufferProvider::Buffer& b(t->buffer);
        // get input buffer
        b.frameCount = numFrames;
        const int64_t outputPTS = calculateOutputPTS(*t, pts, state->frameCount - numFrames);
        t->bufferProvider->getNextBuffer(&b, outputPTS);
        const TI *in = reinterpret_cast<TI*>(b.raw);

        // in == NULL can happen if the track was flushed just after having
        // been enabled for mixing.
        if (in == NULL || (((uintptr_t)in) & 3)) {
#ifdef FULL_FRAMECOUNT
            memset(out, 0, numFrames
                    * channels * audio_bytes_per_sample(t->mMixerInFormat));
            memset(final_out, 0, numFrames
                    * t->mMixerChannelCount * audio_bytes_per_sample(t->mMixerFormat));
#else
            memset(out, 0, numFrames
                    * channels * audio_bytes_per_sample(t->mMixerFormat));
#endif
            ALOGE_IF((((uintptr_t)in) & 3), "process_NoResampleOneTrack: bus error: "
                    "buffer %p track %p, channels %d, needs %#x",
                    in, t, t->channelCount, t->needs);
#ifdef FULL_FRAMECOUNT
            break;
#else
            return;
#endif
        }

#ifdef FULL_FRAMECOUNT
        // only all16BitsStereoNoResample can use process_NoResampleOneTrack(), so there no mono channel.
        size_t outFrames = b.frameCount;
        int32_t sampleCount = outFrames * channels;
        memcpy(out, in, sampleCount * audio_bytes_per_sample(t->mMixerInFormat));
        out += sampleCount;
        numFrames -= b.frameCount;

        // release buffer
        t->bufferProvider->releaseBuffer(&b);
    }
    {


        size_t outFrames = state->frameCount;
        TO* out = reinterpret_cast<TO*>(t->mainBuffer);
        const TI *in = reinterpret_cast<TI*>(state->nonResampleTemp);
#else
        const size_t outFrames = b.frameCount;
#endif

#ifdef MTK_AUDIO
#ifdef FULL_FRAMECOUNT
        t->doPostProcessing<MIXTYPE>(state->nonResampleTemp, t->mMixerInFormat, outFrames);
#else
        t->doPostProcessing<MIXTYPE>(b.raw, t->mMixerInFormat, b.frameCount);
#endif
#endif

#ifndef FULL_FRAMECOUNT
        size_t outFrames = b.frameCount;
#endif


#ifdef MTK_HIFI_AUDIO
        outFrames = outFrames >> t->mBliSrcAdaptorShift;
#endif

        volumeMix<MIXTYPE, is_same<TI, float>::value, false> (
                out, outFrames, in, aux, ramp, t);
#ifdef FULL_FRAMECOUNT
        if (aux != NULL) {
            aux += channels;
        }
#endif

        //ALOGD("%s, framecount %d, mMixerChannelCount %d, mMixerFormat %d", __FUNCTION__, outFrames, t->mMixerChannelCount, t->mMixerFormat);
        //MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, outFrames*t->mMixerChannelCount*audio_bytes_per_sample(t->mMixerFormat));
        MixerDumpPcm(gaf_mixer_end_pcm, gaf_mixer_end_propty, gettid(), (int)out, out, outFrames*t->mMixerChannelCount*audio_bytes_per_sample(t->mMixerFormat),
            t->mMixerFormat, t->sampleRate, t->mMixerChannelCount );


#ifndef FULL_FRAMECOUNT
        out += outFrames * channels;
        if (aux != NULL) {
            aux += channels;
        }
        numFrames -= b.frameCount;

        // release buffer
        t->bufferProvider->releaseBuffer(&b);
#endif
    }
    if (ramp) {
        t->adjustVolumeRamp(aux != NULL, is_same<TI, float>::value);
    }
#ifdef TIME_STRETCH_ENABLE
                t->mTrackPlayed = 1;
#endif
}
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
static inline int32_t clamp4_27(int32_t sample);
#endif

/* This track hook is called to do resampling then mixing,
 * pulling from the track's upstream AudioBufferProvider.
 *
 * MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
 int dump_sample  = 0;
template <int MIXTYPE, typename TO, typename TI, typename TA>
void AudioMixer::track__Resample(track_t* t, TO* out, size_t outFrameCount, TO* temp, TA* aux)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("track__Resample\n");
    t->resampler->setSampleRate(t->sampleRate);
    const bool ramp = t->needsRamp();

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    if (1)
#else
    if (ramp || aux != NULL)
#endif
    {
        // if ramp:        resample with unity gain to temp buffer and scale/mix in 2nd step.
        // if aux != NULL: resample with unity gain to temp buffer then apply send level.

        t->resampler->setVolume(UNITY_GAIN_FLOAT, UNITY_GAIN_FLOAT);
        memset(temp, 0, outFrameCount * t->mMixerChannelCount * sizeof(TO));
        t->resampler->resample((int32_t*)temp, outFrameCount, t->bufferProvider);

#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        if( (t->mMixerInFormat == AUDIO_FORMAT_PCM_16_BIT) && t->checkDRCEnable())
        {
            ALOGD(" t->mMixerFormat == AUDIO_FORMAT_PCM_16_BIT");
            size_t i;
            int* ptr = (int*) temp;
            int* ptr_out  = (int*) temp;
            int resamplerChannelCount = t->downmixerBufferProvider != NULL
                    ? t->mMixerChannelCount : t->channelCount;
            resamplerChannelCount = resamplerChannelCount==1? 2:resamplerChannelCount;
            for (i=0 ; i<outFrameCount*  resamplerChannelCount  ; i++)
            {
                int32_t nl =(*ptr++) ;
                *ptr_out++ = (clamp4_27(nl)<<4);
            }
        }
    #endif
#ifdef MTK_AUDIO
        t->doPostProcessing<MIXTYPE>(temp,
            (AUDIO_FORMAT_PCM_16_BIT==t->mMixerInFormat?AUDIO_FORMAT_PCM_32_BIT:t->mMixerInFormat), outFrameCount);
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        if( t->mMixerInFormat == AUDIO_FORMAT_PCM_16_BIT && t->checkDRCEnable())
        {
            size_t i;
            int* ptr = (int*) temp;
            int* ptr_out  = (int*) temp;
            int resamplerChannelCount = t->downmixerBufferProvider != NULL
                    ? t->mMixerChannelCount : t->channelCount;
            resamplerChannelCount = resamplerChannelCount==1? 2:resamplerChannelCount;
            for (i=0 ; i<outFrameCount* resamplerChannelCount  ; i++)
            {
                int32_t nl =(*ptr++) ;
                nl = (nl+ (1<<3))>>4;
                *ptr_out++ = nl ;
            }
        }
#endif
#endif

#ifdef MTK_HIFI_AUDIO
        outFrameCount = outFrameCount >> t->mBliSrcAdaptorShift;
#endif

        volumeMix<MIXTYPE, is_same<TI, float>::value, true>(
                out, outFrameCount, temp, aux, ramp, t);

    } else { // constant volume gain
        t->resampler->setVolume(t->mVolume[0], t->mVolume[1]);
        t->resampler->resample((int32_t*)out, outFrameCount, t->bufferProvider);
#ifdef MTK_BESSURROUND_ENABLE
       if(t ->mSurroundMixer){
               memcpy_by_audio_format(out, AUDIO_FORMAT_PCM_32_BIT, out,
                   is_same<TI, int16_t>::value ? AUDIO_FORMAT_PCM_16_BIT : AUDIO_FORMAT_PCM_FLOAT
               ,outFrameCount* t->channelCount);
               t->mSurroundMixer->process((int32_t*)out,(t->mDownMixBuffer),outFrameCount);
               memcpy_by_audio_format(out,
                   is_same<TI, int16_t>::value ? AUDIO_FORMAT_PCM_16_BIT : AUDIO_FORMAT_PCM_FLOAT
               , (t->mDownMixBuffer), AUDIO_FORMAT_PCM_32_BIT, outFrameCount*2 );
               }
#endif
    }
#ifdef TIME_STRETCH_ENABLE
                t->mTrackPlayed = 1;
#endif
}

/* This track hook is called to mix a track, when no resampling is required.
 * The input buffer should be present in t->in.
 *
 * MIXTYPE     (see AudioMixerOps.h MIXTYPE_* enumeration)
 * TO: int32_t (Q4.27) or float
 * TI: int32_t (Q4.27) or int16_t (Q0.15) or float
 * TA: int32_t (Q4.27)
 */
template <int MIXTYPE, typename TO, typename TI, typename TA>
void AudioMixer::track__NoResample(track_t* t, TO* out, size_t frameCount,
        TO* temp __unused, TA* aux)
{
    DRC_ALOGD("%s", __FUNCTION__);
    ALOGVV("track__NoResample\n");

#ifdef FULL_FRAMECOUNT
    const TI *in = reinterpret_cast<const TI *>(temp);
#else
    const TI *in = static_cast<const TI *>(t->in);
#endif

#ifdef MTK_AUDIO
    void *buffer = static_cast<void *>(temp);
    t->doPostProcessing<MIXTYPE>(buffer, t->mMixerInFormat, frameCount);
#endif

#ifdef MTK_HIFI_AUDIO
    frameCount = frameCount >> t->mBliSrcAdaptorShift;
#endif

    volumeMix<MIXTYPE, is_same<TI, float>::value, true>(
            out, frameCount, in, aux, t->needsRamp(), t);

    // MIXTYPE_MONOEXPAND reads a single input channel and expands to NCHAN output channels.
    // MIXTYPE_MULTI reads NCHAN input channels and places to NCHAN output channels.
#ifndef FULL_FRAMECOUNT
    in += (MIXTYPE == MIXTYPE_MONOEXPAND) ? frameCount : frameCount * t->mMixerChannelCount;
    t->in = in;
#endif
#ifdef TIME_STRETCH_ENABLE
            t->mTrackPlayed = 1;
#endif
}

/* The Mixer engine generates either int32_t (Q4_27) or float data.
 * We use this function to convert the engine buffers
 * to the desired mixer output format, either int16_t (Q.15) or float.
 */
void AudioMixer::convertMixerFormat(void *out, audio_format_t mixerOutFormat,
        void *in, audio_format_t mixerInFormat, size_t sampleCount)
{
    switch (mixerInFormat) {
    case AUDIO_FORMAT_PCM_FLOAT:
        switch (mixerOutFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            memcpy(out, in, sampleCount * sizeof(float)); // MEMCPY. TODO optimize out
            break;
        case AUDIO_FORMAT_PCM_16_BIT:
            memcpy_to_i16_from_float((int16_t*)out, (float*)in, sampleCount);
            break;
        default:
            LOG_ALWAYS_FATAL("bad mixerOutFormat: %#x", mixerOutFormat);
            break;
        }
        break;
    case AUDIO_FORMAT_PCM_16_BIT:
        switch (mixerOutFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            memcpy_to_float_from_q4_27((float*)out, (int32_t*)in, sampleCount);
            break;
        case AUDIO_FORMAT_PCM_16_BIT:
            // two int16_t are produced per iteration
            ditherAndClamp((int32_t*)out, (int32_t*)in, sampleCount >> 1);
            break;
        default:
            LOG_ALWAYS_FATAL("bad mixerOutFormat: %#x", mixerOutFormat);
            break;
        }
        break;
    default:
        LOG_ALWAYS_FATAL("bad mixerInFormat: %#x", mixerInFormat);
        break;
    }
}

/* Returns the proper track hook to use for mixing the track into the output buffer.
 */
AudioMixer::hook_t AudioMixer::getTrackHook(int trackType, uint32_t channelCount,
        audio_format_t mixerInFormat, audio_format_t mixerOutFormat __unused)
{
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    if (0)
#else
    if (!kUseNewMixer && channelCount == FCC_2 && mixerInFormat == AUDIO_FORMAT_PCM_16_BIT)
#endif
    {
        switch (trackType) {
        case TRACKTYPE_NOP:
            return track__nop;
        case TRACKTYPE_RESAMPLE:
            return track__genericResample;
        case TRACKTYPE_NORESAMPLEMONO:
            return track__16BitsMono;
        case TRACKTYPE_NORESAMPLE:
            return track__16BitsStereo;
        default:
            LOG_ALWAYS_FATAL("bad trackType: %d", trackType);
            break;
        }
    }
    LOG_ALWAYS_FATAL_IF(channelCount > MAX_NUM_CHANNELS);
    switch (trackType) {
    case TRACKTYPE_NOP:
        return track__nop;
    case TRACKTYPE_RESAMPLE:
        switch (mixerInFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            return (AudioMixer::hook_t)
                    track__Resample<MIXTYPE_MULTI, float /*TO*/, float /*TI*/, int32_t /*TA*/>;
        case AUDIO_FORMAT_PCM_16_BIT:
            return (AudioMixer::hook_t)\
                    track__Resample<MIXTYPE_MULTI, int32_t, int16_t, int32_t>;
        default:
            LOG_ALWAYS_FATAL("bad mixerInFormat: %#x", mixerInFormat);
            break;
        }
        break;
    case TRACKTYPE_NORESAMPLEMONO:
        switch (mixerInFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            return (AudioMixer::hook_t)
                    track__NoResample<MIXTYPE_MONOEXPAND, float, float, int32_t>;
        case AUDIO_FORMAT_PCM_16_BIT:
            return (AudioMixer::hook_t)
                    track__NoResample<MIXTYPE_MONOEXPAND, int32_t, int16_t, int32_t>;
        default:
            LOG_ALWAYS_FATAL("bad mixerInFormat: %#x", mixerInFormat);
            break;
        }
        break;
    case TRACKTYPE_NORESAMPLE:
        switch (mixerInFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            return (AudioMixer::hook_t)
                    track__NoResample<MIXTYPE_MULTI, float, float, int32_t>;
        case AUDIO_FORMAT_PCM_16_BIT:
            return (AudioMixer::hook_t)
                    track__NoResample<MIXTYPE_MULTI, int32_t, int16_t, int32_t>;
        default:
            LOG_ALWAYS_FATAL("bad mixerInFormat: %#x", mixerInFormat);
            break;
        }
        break;
    default:
        LOG_ALWAYS_FATAL("bad trackType: %d", trackType);
        break;
    }
    return NULL;
}

/* Returns the proper process hook for mixing tracks. Currently works only for
 * PROCESSTYPE_NORESAMPLEONETRACK, a mix involving one track, no resampling.
 *
 * TODO: Due to the special mixing considerations of duplicating to
 * a stereo output track, the input track cannot be MONO.  This should be
 * prevented by the caller.
 */
AudioMixer::process_hook_t AudioMixer::getProcessHook(int processType, uint32_t channelCount,
        audio_format_t mixerInFormat, audio_format_t mixerOutFormat)
{
    if (processType != PROCESSTYPE_NORESAMPLEONETRACK) { // Only NORESAMPLEONETRACK
        LOG_ALWAYS_FATAL("bad processType: %d", processType);
        return NULL;
    }
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    if (0)
#else
    if (!kUseNewMixer && channelCount == FCC_2 && mixerInFormat == AUDIO_FORMAT_PCM_16_BIT)
#endif
    {
        return process__OneTrack16BitsStereoNoResampling;
    }
    LOG_ALWAYS_FATAL_IF(channelCount > MAX_NUM_CHANNELS);
    switch (mixerInFormat) {
    case AUDIO_FORMAT_PCM_FLOAT:
        switch (mixerOutFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            return process_NoResampleOneTrack<MIXTYPE_MULTI_SAVEONLY,
                    float /*TO*/, float /*TI*/, int32_t /*TA*/>;
        case AUDIO_FORMAT_PCM_16_BIT:
            return process_NoResampleOneTrack<MIXTYPE_MULTI_SAVEONLY,
                    int16_t, float, int32_t>;
        default:
            LOG_ALWAYS_FATAL("bad mixerOutFormat: %#x", mixerOutFormat);
            break;
        }
        break;
    case AUDIO_FORMAT_PCM_16_BIT:
        switch (mixerOutFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            return process_NoResampleOneTrack<MIXTYPE_MULTI_SAVEONLY,
                    float, int16_t, int32_t>;
        case AUDIO_FORMAT_PCM_16_BIT:
            return process_NoResampleOneTrack<MIXTYPE_MULTI_SAVEONLY,
                    int16_t, int16_t, int32_t>;
        default:
            LOG_ALWAYS_FATAL("bad mixerOutFormat: %#x", mixerOutFormat);
            break;
        }
        break;
    default:
        LOG_ALWAYS_FATAL("bad mixerInFormat: %#x", mixerInFormat);
        break;
    }
    return NULL;
}

// ----------------------------------------------------------------------------

// MTK Add start

#ifdef MTK_AUDIO
template <int MIXTYPE>
void AudioMixer::track_t::DoStereoMonoProcessing(void *buffer,
    audio_format_t process_format, size_t frameCount, int32_t process_channel)
{
    // Stereo to Mono process
    int32_t sampleSize = frameCount * process_channel * audio_bytes_per_sample(process_format);

    bool bFloat = false;
    if ( process_format == AUDIO_FORMAT_PCM_FLOAT )
    {
        bFloat = true;
        memcpy_by_audio_format(buffer, AUDIO_FORMAT_PCM_32_BIT, buffer, process_format,frameCount * process_channel);
        process_format = AUDIO_FORMAT_PCM_32_BIT;
    }

    if(AUDIO_FORMAT_PCM_32_BIT == process_format) {
        DoStereoMonoConvert<MIXTYPE, int32_t>((void *)buffer, sampleSize);
    } else if(AUDIO_FORMAT_PCM_16_BIT == process_format) {
        DoStereoMonoConvert<MIXTYPE, int16_t>((void *)buffer, sampleSize);
    }

    if ( bFloat )
    {
       memcpy_by_audio_format(buffer, AUDIO_FORMAT_PCM_FLOAT, buffer, process_format,frameCount * process_channel);
    }
}

template <int MIXTYPE>
bool AudioMixer::track_t::doPostProcessing(void *buffer, audio_format_t format, size_t frameCount)
{
    DRC_ALOGD("+%s", __FUNCTION__);

    int32_t process_channel = (MIXTYPE == MIXTYPE_MONOEXPAND?channelCount:mMixerChannelCount);

    if ( mStreamType == AUDIO_STREAM_PATCH )
    {
        DoStereoMonoProcessing<MIXTYPE>( buffer, format, frameCount, process_channel );
        return true;
    }

    bool SurroundMix_enable = false;
#ifdef MTK_BESSURROUND_ENABLE
    SurroundMix_enable = (MIXTYPE != MIXTYPE_MONOEXPAND)&&(mSurroundMixer) &&(channelCount != 1);
#endif

#ifdef DEBUG_AUDIO_PCM_FOR_TEST
    {
        process_channel = SurroundMix_enable? channelCount : process_channel;
        String8 fileName = String8::format("%s.beforePostProcess", gaf_mixertest_in_pcm );
        AudioDump::threadDump(fileName.string(), buffer,
            frameCount*process_channel *audio_bytes_per_sample(format), gaf_mixertest_in_propty,
            format, mDevSampleRate, process_channel);
    }
#endif

    bool DRC_enable = false;
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
    DRC_enable = checkDRCEnable();
#endif

    if((FCC_2==mMixerChannelCount) && (SurroundMix_enable||DRC_enable||(mSteroToMono==BLOUD_S2M_MODE_ST2MO2ST))) {

        DRC_ALOGD("%s, format %d, channel %d %d, frameCount %d, MIXTYPE %d", __FUNCTION__, format, mMixerChannelCount, channelCount, frameCount, MIXTYPE);
        if(!((AUDIO_FORMAT_PCM_32_BIT==format) || (AUDIO_FORMAT_PCM_16_BIT==format) || (AUDIO_FORMAT_PCM_FLOAT==format))) {
            ALOGE("%s, format not support!!", __FUNCTION__);
            return false;
        }

        // format wrapper start, if need
        audio_format_t process_format = format;
        void* processBuffer = buffer;

        if(SurroundMix_enable ||AUDIO_FORMAT_PCM_FLOAT == process_format ){
            #ifdef MTK_BESSURROUND_ENABLE
            processBuffer = SurroundMix_enable ? mDownMixBuffer: buffer;
            process_channel = SurroundMix_enable? channelCount : process_channel;
            #endif
            ALOGV("%s , frameCount (%d) ,t->channelCount(%d) process_channel (%d) process_format (%d)", __FUNCTION__, frameCount,channelCount , process_channel,process_format);
            memcpy_by_audio_format(processBuffer, AUDIO_FORMAT_PCM_32_BIT, buffer, process_format,frameCount * process_channel);
            process_format = AUDIO_FORMAT_PCM_32_BIT;

            /*#ifdef DEBUG_AUDIO_PCM_FOR_TEST
            {
                String8 fileName = String8::format("%s.to32bit", gaf_mixertest_in_pcm );
                AudioDump::threadDump(fileName.string(), processBuffer,
                    frameCount*process_channel *audio_bytes_per_sample(process_format), gaf_mixertest_in_propty,
                    process_format, mDevSampleRate, process_channel);
            }
            #endif*/
        }

        // BesSurround process
#ifdef MTK_BESSURROUND_ENABLE
        if(SurroundMix_enable){
            void *pBufferAfterBliSrc = NULL;
            uint32_t bytesAfterBliSrc = 0;

            ALOGV(" %s, surroundMix process", __FUNCTION__);

#ifdef MTK_HIFI_AUDIO
            size_t frameCountAdaptor = frameCount;
            if(OUTPUT_RATE_192 == mDevSampleRate || OUTPUT_RATE_176_4 == mDevSampleRate)
                frameCountAdaptor = frameCountAdaptor >> 2;
            else if(OUTPUT_RATE_96 == mDevSampleRate || OUTPUT_RATE_88_2 == mDevSampleRate)
                frameCountAdaptor = frameCountAdaptor >> 1;
            doBliSrc(mBliSrcDown, processBuffer, frameCount*process_channel*audio_bytes_per_sample(process_format), &pBufferAfterBliSrc, &bytesAfterBliSrc);
            mSurroundMixer->process((int32_t*)pBufferAfterBliSrc, (int32_t*) buffer,frameCountAdaptor);
            doBliSrc(mBliSrcUp, buffer, frameCountAdaptor*mMixerChannelCount*audio_bytes_per_sample(process_format), &pBufferAfterBliSrc, &bytesAfterBliSrc);
            ALOGV("%s, frameCount %d, frameCountAdaptor %d, process_channel %d, bytesAfterBliSrc %d", __FUNCTION__, frameCount, frameCountAdaptor, process_channel, bytesAfterBliSrc);
            process_channel = mMixerChannelCount;
            if(pBufferAfterBliSrc != buffer && 0 != bytesAfterBliSrc)
            {
                memset(buffer, 0, bytesAfterBliSrc);
                memcpy(buffer, pBufferAfterBliSrc, bytesAfterBliSrc);
            }
#else
            mSurroundMixer->process(mDownMixBuffer, (int32_t*) buffer,frameCount);
            process_channel = mMixerChannelCount;
#endif
            #ifdef DEBUG_AUDIO_PCM_FOR_TEST
            {
                String8 fileName = String8::format("%s.surroundProcess", gaf_mixertest_in_pcm );
                AudioDump::threadDump(fileName.string(), buffer,
                    frameCount*mMixerChannelCount *audio_bytes_per_sample(process_format), gaf_mixertest_in_propty,
                    process_format, mDevSampleRate, mMixerChannelCount);
            }
            #endif
        }
        #endif

        // Stereo to Mono process
        DoStereoMonoProcessing<MIXTYPE>( buffer, process_format, frameCount, process_channel );

        /*#ifdef DEBUG_AUDIO_PCM_FOR_TEST
        {
            String8 fileName = String8::format("%s.stereotoMono", gaf_mixertest_in_pcm );
            AudioDump::threadDump(fileName.string(), buffer,
                frameCount*process_channel *audio_bytes_per_sample(process_format), gaf_mixertest_in_propty,
                process_format, mDevSampleRate, process_channel);
        }
        #endif*/

        // DRC process
#ifdef MTK_AUDIOMIXER_ENABLE_DRC
        if ((frameCount >= 512) && (frameCount % 512 == 0)) // DRC need at least 512 frameCount.
        {
            int32_t sampleSize = frameCount * process_channel * audio_bytes_per_sample(process_format);
            if ( format == AUDIO_FORMAT_PCM_16_BIT && process_format != AUDIO_FORMAT_PCM_16_BIT )
            {
                int32_t sampleCount = frameCount * process_channel;
                sampleSize = frameCount * process_channel * audio_bytes_per_sample(format);
                memcpy_by_audio_format(buffer, format, buffer, process_format, sampleCount);
                process_format = AUDIO_FORMAT_PCM_16_BIT;
            }
            applyDRC( (void *)buffer, sampleSize, mState->pDRCTempBuffer, process_format, process_channel);
        }
#endif

        // format wrapper end, if need
        if( process_format != format)
        {
            int32_t sampleCount = frameCount * (MIXTYPE == MIXTYPE_MONOEXPAND?channelCount:mMixerChannelCount);

            ALOGVV("frameCount(%d), sampleCount (%d) format(%d)", frameCount, sampleCount,format);
            memcpy_by_audio_format(buffer, format, buffer, process_format, sampleCount);

            /*#ifdef DEBUG_AUDIO_PCM_FOR_TEST
            {
                String8 fileName = String8::format("%s.toOriginalFormat", gaf_mixertest_in_pcm );
                AudioDump::threadDump(fileName.string(), buffer,
                    frameCount*mMixerChannelCount *audio_bytes_per_sample(format), gaf_mixertest_in_propty,
                    format, mDevSampleRate, mMixerChannelCount);
            }
            #endif*/
        }

#if 0//def DEBUG_AUDIO_PCM_FOR_TEST
                      const int SIZE = 256;
                      char fileName[SIZE];
                      sprintf(fileName,"%s_%p.pcm",gaf_mixertest_in_pcm, this);
                      //ALOGD("dump frameCount(%d)* t->channelCount(%d)*sizeof(int),  = %d", frameCount, channelCount, frameCount* channelCount*sizeof(int));
                      AudioDump::dump(fileName,buffer,frameCount* channelCount*sizeof(int),gaf_mixertest_in_propty);
#endif
    }

#ifdef MTK_HIFI_AUDIO
    if(mBliSrcAdaptorState && (OUTPUT_RATE_192 == mDevSampleRate || OUTPUT_RATE_96 == mDevSampleRate || OUTPUT_RATE_176_4 == mDevSampleRate || OUTPUT_RATE_88_2 == mDevSampleRate)) {
        void *pBufferAfterBliSrc = NULL;
        uint32_t bytesAfterBliSrc = 0;
        size_t frameCountAdaptor = frameCount;

        mBliSrcAdaptorShift=0;
        if(OUTPUT_RATE_192 == mDevSampleRate || OUTPUT_RATE_176_4 == mDevSampleRate)
            mBliSrcAdaptorShift = 2;
        else
            mBliSrcAdaptorShift = 1;

        memcpy_by_audio_format(buffer, AUDIO_FORMAT_PCM_32_BIT, buffer, format,frameCountAdaptor*process_channel);

        doBliSrc(mBliSrcAdaptor, buffer, frameCount*process_channel*audio_bytes_per_sample(AUDIO_FORMAT_PCM_32_BIT), &pBufferAfterBliSrc, &bytesAfterBliSrc);
        frameCountAdaptor = frameCountAdaptor >> mBliSrcAdaptorShift;
        memcpy_by_audio_format(buffer, format, pBufferAfterBliSrc, AUDIO_FORMAT_PCM_32_BIT,frameCountAdaptor*process_channel);
    }
    else{
        mBliSrcAdaptorShift=0;
    }
#endif

    DRC_ALOGD("-%s", __FUNCTION__);

    return true;
}

template <int MIXTYPE, typename TO>
bool AudioMixer::track_t::DoStereoMonoConvert(void *buffer, size_t byte)
{
    DRC_ALOGD("DoStereoMonoConvert start mSteroToMono = %d, buffer 0x%x, byte %d",mSteroToMono, buffer, byte);

    if(MIXTYPE_MONOEXPAND == MIXTYPE)
        return true;

    int32_t len = sizeof(TO)*FCC_2;

    if (mSteroToMono == BLOUD_S2M_MODE_ST2MO2ST)
    {
        TO FinalValue  = 0;
        TO *Sample = (TO *)buffer;
        while (byte > 0)
        {
            FinalValue = ((*Sample) >> 1) + ((*(Sample + 1)) >> 1);
            *Sample++ = FinalValue;
            *Sample++ = FinalValue;
            byte -= len;
        }
    }
    DRC_ALOGD("DoStereoMonoConvert end");
    return true;
}

#else
template <int MIXTYPE>
void AudioMixer::track_t::DoStereoMonoProcessing(void *buffer,
    audio_format_t process_format, size_t frameCount, int32_t process_channel)
{
}

template <int MIXTYPE>
bool AudioMixer::track_t::doPostProcessing(void *buffer, audio_format_t format, size_t frameCount)
{
    return true;
}

template <int MIXTYPE, typename TO>
bool AudioMixer::track_t::DoStereoMonoConvert(void *buffer, size_t byte)
{
    return true;
}

#endif



#ifdef MTK_HIFI_AUDIO


#define kBliSrcOutputBufferSize 0x40000  // 64k



status_t AudioMixer::track_t::initBliSrc()
{
    if(mBliSrcDown != NULL || mBliSrcUp != NULL || mBliSrcAdaptor != NULL ||mBliSrcOutputBuffer != NULL)
        return NO_ERROR;

    if(OUTPUT_RATE_192 == mDevSampleRate || OUTPUT_RATE_96 == mDevSampleRate || OUTPUT_RATE_176_4 == mDevSampleRate || OUTPUT_RATE_88_2 == mDevSampleRate) {

        uint32_t destSampleRate = (OUTPUT_RATE_192 == mDevSampleRate || OUTPUT_RATE_96 == mDevSampleRate)?OUTPUT_RATE_48:OUTPUT_RATE_44_1;

        ALOGD("%s start : mDevSampleRate %d, destSampleRate %d", __FUNCTION__, mDevSampleRate, destSampleRate);

        mBliSrcDown = new MtkAudioSrc(mDevSampleRate, channelCount, destSampleRate      , channelCount, SRC_IN_Q1P31_OUT_Q1P31);
        mBliSrcUp   = new MtkAudioSrc(destSampleRate      , FCC_2       , mDevSampleRate, FCC_2       , SRC_IN_Q1P31_OUT_Q1P31);

        mBliSrcDown->MultiChannel_Open();
        mBliSrcUp->MultiChannel_Open();

        mBliSrcAdaptor = new MtkAudioSrc(mDevSampleRate, FCC_2, destSampleRate      , FCC_2, SRC_IN_Q1P31_OUT_Q1P31);
        mBliSrcAdaptor->MultiChannel_Open();

        mBliSrcOutputBuffer = (char*) new int32_t[MAX_NUM_CHANNELS*mState->frameCount];
    }

    //ALOGD("%s end, mBliSrcDown 0x%x, mBliSrcUp 0x%x, mBliSrcAdaptor 0x%x, mBliSrcOutputBuffer 0x%x, size %d", __FUNCTION__, mBliSrcDown, mBliSrcUp, mBliSrcAdaptor, mBliSrcOutputBuffer, MAX_NUM_CHANNELS*mState->frameCount);
    return NO_ERROR;
}



status_t AudioMixer::track_t::deinitBliSrc()
{
    //ALOGD("%s", __FUNCTION__);

    // deinit BLI SRC if need
    if (mBliSrcDown != NULL)
    {
        mBliSrcDown->Close();
        delete mBliSrcDown;
        mBliSrcDown = NULL;
    }

    if (mBliSrcUp != NULL)
    {
        mBliSrcUp->Close();
        delete mBliSrcUp;
        mBliSrcUp = NULL;
    }

    if (mBliSrcAdaptor != NULL)
    {
        mBliSrcAdaptor->Close();
        delete mBliSrcAdaptor;
        mBliSrcAdaptor = NULL;
    }

    if (mBliSrcOutputBuffer != NULL)
    {
        delete[] mBliSrcOutputBuffer;
        mBliSrcOutputBuffer = NULL;
    }

    return NO_ERROR;
}


status_t AudioMixer::track_t::doBliSrc(MtkAudioSrc* mBliSrc,void *pInBuffer, uint32_t inBytes, void **ppOutBuffer, uint32_t *pOutBytes)
{
    if (mBliSrc == NULL) // No need SRC
    {
        *ppOutBuffer = pInBuffer;
        *pOutBytes = inBytes;
    }
    else
    {
        char *p_read = (char *)pInBuffer;
        uint32_t num_raw_data_left = inBytes;
        uint32_t num_converted_data = MAX_NUM_CHANNELS*mState->frameCount; // max convert num_free_space

        uint32_t consumed = num_raw_data_left;


        //ALOGD("%s, mBliSrc 0x%x, p_read 0x%x, size %d, buffer 0x%x", __FUNCTION__, mBliSrc, p_read, num_raw_data_left, mBliSrcOutputBuffer);
        mBliSrc->MultiChannel_Process((int16_t *)p_read, &num_raw_data_left,
                         (int16_t *)mBliSrcOutputBuffer, &num_converted_data);
        consumed -= num_raw_data_left;
        p_read += consumed;

        //ALOGV("%s(), num_raw_data_left = %u, num_converted_data = %u",
        //      __FUNCTION__, num_raw_data_left, num_converted_data);

        if (num_raw_data_left > 0)
        {
            ALOGW("%s(), num_raw_data_left(%u) > 0", __FUNCTION__, num_raw_data_left);
            ASSERT(num_raw_data_left == 0);
        }

        *ppOutBuffer = mBliSrcOutputBuffer;
        *pOutBytes = num_converted_data;
    }

    ASSERT(*ppOutBuffer != NULL && *pOutBytes != 0);
    return NO_ERROR;
}

#endif


#ifdef MTK_AUDIOMIXER_ENABLE_DRC

bool AudioMixer::mUIDRCEnable = true;


void AudioMixer::releaseDRC(int name)
{
    name -= TRACK0;
    track_t& track(mState.tracks[ name ]);

    if (track.mpDRCObj) {
        track.mpDRCObj->Close();
        track.mDRCState = false;
        if(NULL != track.mpDRCObj) {
            delete track.mpDRCObj;
            track.mpDRCObj = NULL;
        }
    }
}

void AudioMixer::track_t::resetDRC()
{
    ALOGD("%s", __FUNCTION__);
    if (mpDRCObj) {
        mpDRCObj->ResetBuffer();
    }
}

void AudioMixer::track_t::updateDRCParam(int devSampleRate)
{
    if (mpDRCObj) {
        mpDRCObj->ResetBuffer();
        mpDRCObj->Close();

        mpDRCObj->SetParameter(BLOUD_PAR_SET_SAMPLE_RATE, (void *)devSampleRate);

        // DRC will always receive 2ch data.
        if ( !doesResample() &&
              mMixerChannelMask == AUDIO_CHANNEL_OUT_STEREO &&
              channelMask == AUDIO_CHANNEL_OUT_MONO)
        {
            mpDRCObj->SetParameter(BLOUD_PAR_SET_CHANNEL_NUMBER, (void *)BLOUD_HD_MONO);
        } else {
            mpDRCObj->SetParameter(BLOUD_PAR_SET_CHANNEL_NUMBER, (void *)BLOUD_HD_STEREO);
        }

        if(AUDIO_FORMAT_PCM_16_BIT == mMixerInFormat && !doesResample()) {
            mpDRCObj->SetParameter(BLOUD_PAR_SET_PCM_FORMAT, (void *)BLOUD_IN_Q1P15_OUT_Q1P15);
        } else {
            mpDRCObj->SetParameter(BLOUD_PAR_SET_PCM_FORMAT, (void *)BLOUD_IN_Q1P31_OUT_Q1P31);
        }

        //mpDRCObj->SetParameter(BLOUD_PAR_SET_FILTER_TYPE, (void *)AUDIO_COMP_FLT_AUDIO);
        mpDRCObj->SetParameter(BLOUD_PAR_SET_USE_DEFAULT_PARAM_FORCE_RELOAD, (void *)NULL);
        mpDRCObj->SetParameter(BLOUD_PAR_SET_WORK_MODE, (void *)AUDIO_CMP_FLT_LOUDNESS_LITE);
        mpDRCObj->SetParameter(BLOUD_PAR_SET_STEREO_TO_MONO_MODE, (void *)mSteroToMono);
        mpDRCObj->Open();
    }
}

void AudioMixer::track_t::setDRCHandler(audio_devices_t device, uint32_t bufferSize, uint32_t sampleRate)
{
    DRC_ALOGD("setDRCHandler, mUIDRCEnable %d, mpDRCObj 0x%x, mStreamType %d, device %d, mDRCState %d, mSteroToMono %d, this 0x%x", mUIDRCEnable, mpDRCObj, mStreamType, device, mDRCState, mSteroToMono, this);

    if(!(device&AUDIO_DEVICE_OUT_SPEAKER)) {
        if (mpDRCObj && mDRCState) {
            mpDRCObj->Close();
            mDRCState = false;
            delete mpDRCObj;
            mpDRCObj = NULL;
        }
    }

    if ( (true==mUIDRCEnable) &&
        (device & AUDIO_DEVICE_OUT_SPEAKER) && (mStreamType != AUDIO_STREAM_DTMF)) {
        if (mpDRCObj == NULL) {
            //ALOGD("new MtkAudioLoud");
#if defined(MTK_AUDIO_BLOUD_CUSTOMPARAMETER_V4)||defined(MTK_AUDIO_BLOUD_CUSTOMPARAMETER_V3)
            mpDRCObj = new MtkAudioLoud(AUDIO_COMP_FLT_AUDIO);
#else
            if (mStreamType == AUDIO_STREAM_RING)
                mpDRCObj = new MtkAudioLoud(AUDIO_COMP_FLT_DRC_FOR_RINGTONE);
            else
                mpDRCObj = new MtkAudioLoud(AUDIO_COMP_FLT_DRC_FOR_MUSIC);

            if ( mStreamType == AUDIO_STREAM_VOICE_CALL )
            {
                mpDRCObj->SetParameter(BLOUD_PAR_SET_NOISE_FILTER, (void *)true);
            }
#endif

            mpDRCObj->SetParameter(BLOUD_PAR_SET_SAMPLE_RATE, (void *)sampleRate);


            if ( !doesResample() &&
                  mMixerChannelMask == AUDIO_CHANNEL_OUT_STEREO &&
                  channelMask == AUDIO_CHANNEL_OUT_MONO)
            {
                mpDRCObj->SetParameter(BLOUD_PAR_SET_CHANNEL_NUMBER, (void *)BLOUD_HD_MONO);
            } else {
                mpDRCObj->SetParameter(BLOUD_PAR_SET_CHANNEL_NUMBER, (void *)BLOUD_HD_STEREO);
            }

            if (AUDIO_FORMAT_PCM_16_BIT == mMixerInFormat && !doesResample()) {
                mpDRCObj->SetParameter(BLOUD_PAR_SET_PCM_FORMAT, (void *)BLOUD_IN_Q1P15_OUT_Q1P15);
            } else {
                mpDRCObj->SetParameter(BLOUD_PAR_SET_PCM_FORMAT, (void *)BLOUD_IN_Q1P31_OUT_Q1P31);
            }

            //mpDRCObj->SetParameter(BLOUD_PAR_SET_FILTER_TYPE, (void *)AUDIO_COMP_FLT_AUDIO);
            mpDRCObj->SetParameter(BLOUD_PAR_SET_USE_DEFAULT_PARAM, (void *)NULL);
            mpDRCObj->SetParameter(BLOUD_PAR_SET_WORK_MODE, (void *)AUDIO_CMP_FLT_LOUDNESS_LITE);
            mpDRCObj->SetParameter(BLOUD_PAR_SET_STEREO_TO_MONO_MODE, (void *)mSteroToMono);
            mpDRCObj->Open();
            mDRCState = true;
            mDRCEnable = true;
            resetDRC();
        }
        else {
            if(false == mDRCEnable) {
                //ALOGD("Change2Normal, mDRCEnable %d", mDRCEnable);
                if(ACE_SUCCESS == mpDRCObj->Change2Normal())
                    mDRCEnable = true;
            }
        }
    } else {
        if( (true==mDRCState) && (mpDRCObj != NULL)) {
            if(true == mDRCEnable) {
                //ALOGD("Change2ByPass, mDRCEnable %d", mDRCEnable);
                if(ACE_SUCCESS == mpDRCObj->Change2ByPass())
                    mDRCEnable = false;
            }
        }
    }
}


void AudioMixer::track_t::applyDRC(void *ioBuffer, uint32_t SampleSize, int32_t *tempBuffer,
    audio_format_t process_format, int process_channel)
{
    uint32_t inputSampleSize, outputSampleSize;

    if(!checkDRCEnable())
       return;

    DRC_ALOGD("%s, SampleSize %d", __FUNCTION__, SampleSize);

    inputSampleSize = outputSampleSize = SampleSize;

    //MixerDumpPcm(gaf_mixer_drc_pcm_before, gaf_mixer_drc_propty, gettid(), this, ioBuffer, SampleSize);
    MixerDumpPcm(gaf_mixer_drc_pcm_before, gaf_mixer_drc_propty, gettid(), this, ioBuffer, SampleSize,
        process_format, mDevSampleRate, process_channel );
    mpDRCObj->Process((void *)ioBuffer, &inputSampleSize, (void *)tempBuffer, &outputSampleSize);
    //MixerDumpPcm(gaf_mixer_drc_pcm_after,  gaf_mixer_drc_propty, gettid(), this, tempBuffer, SampleSize);
    MixerDumpPcm(gaf_mixer_drc_pcm_after,  gaf_mixer_drc_propty, gettid(), this, tempBuffer, SampleSize,
        process_format, mDevSampleRate, process_channel );

    memcpy(ioBuffer, tempBuffer, SampleSize);
}


bool AudioMixer::track_t::checkDRCEnable()
{
    if ((mpDRCObj == NULL) || (!mDRCState))
       return false;
    else
       return true;
}

static AudioMixer *MixerInstance = NULL;

void DRCCallback(void *data)
{
    DRC_ALOGD("%s",__FUNCTION__);
    if (MixerInstance != NULL)
    {
        MixerInstance->setDRCEnable((bool)data);
    }
}

void SetDRCCallback(void *data)
{
    DRC_ALOGD("%s",__FUNCTION__);
    if(MixerInstance)
        return;

    MixerInstance = (AudioMixer *)data;
    BESLOUDNESS_CONTROL_CALLBACK_STRUCT callback_data;
    callback_data.callback = DRCCallback;
    status_t af_status = AudioSystem::SetAudioData(HOOK_BESLOUDNESS_CONTROL_CALLBACK, 0, &callback_data);
    if ( af_status != OK )
    {
        ALOGD("AudioSystem::SetAudioData fail: %d", af_status);
    }
}

static inline int32_t clamp4_27(int32_t sample)
{
    if ((sample>>27) ^ (sample>>31))
        sample = 0x7FFFFFF ^ (sample>>31);
    return sample;
}
#endif






// MTK Add end


} // namespace android

