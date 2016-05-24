/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
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
#define LOG_TAG "MPEG2TSExtractor"

#include <inttypes.h>
#include <utils/Log.h>

#include "include/MPEG2TSExtractor.h"
#include "include/NuCachedSource2.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/IStreamSource.h>
#include <utils/String8.h>

#include "AnotherPacketSource.h"
#include "ATSParser.h"

#ifdef MTK_AOSP_ENHANCEMENT
const static int64_t kMaxPTSTimeOutUs = 3000000LL;  //  handle find Duration for ANR
#endif
#ifdef MTK_AOSP_ENHANCEMENT
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include "include/avc_utils.h"
#define SUPPORT_M2TS
//#undef SUPPORT_M2TS
#endif
namespace android {

static const size_t kTSPacketSize = 188;
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
static const size_t kM2TSPacketSize = 192;
static size_t kFillPacketSize = 188;
#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

struct MPEG2TSSource : public MediaSource {
    MPEG2TSSource(
            const sp<MPEG2TSExtractor> &extractor,
            const sp<AnotherPacketSource> &impl,
            bool doesSeek);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

private:
    sp<MPEG2TSExtractor> mExtractor;
    sp<AnotherPacketSource> mImpl;

    // If there are both audio and video streams, only the video stream
    // will signal seek on the extractor; otherwise the single stream will seek.
    bool mDoesSeek;
#ifdef MTK_AOSP_ENHANCEMENT
    bool mIsVideo;
    List<sp<ABuffer> >mLeftBuffer;    // multi-NAL cut to signal
    bool mWantsNALFragments;
    status_t cutBufferToNAL(MediaBuffer * buffer);
#endif

    DISALLOW_EVIL_CONSTRUCTORS(MPEG2TSSource);
};

MPEG2TSSource::MPEG2TSSource(
        const sp<MPEG2TSExtractor> &extractor,
        const sp<AnotherPacketSource> &impl,
        bool doesSeek)
    : mExtractor(extractor),
      mImpl(impl),
      mDoesSeek(doesSeek) {
#ifdef MTK_AOSP_ENHANCEMENT
      mIsVideo = true;
      mWantsNALFragments = false;
#endif
}

status_t MPEG2TSSource::start(MetaData *params) {
#ifdef MTK_AOSP_ENHANCEMENT
    int32_t val;
    if (params && params->findInt32(kKeyWantsNALFragments, &val)
        && val != 0) {
        ALOGI("wants nal fragments");
        mWantsNALFragments = true;
    } else {
        mWantsNALFragments = false;
    }
#endif
    return mImpl->start(params);
}

status_t MPEG2TSSource::stop() {
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("Stop Video=%d track", mIsVideo);
    if (mIsVideo == true)
        mExtractor->setVideoState(true);
#endif
    return mImpl->stop();
}

#ifdef MTK_AOSP_ENHANCEMENT
sp<MetaData> MPEG2TSSource::getFormat() {
    if (mImpl == NULL) {
        return NULL;
    } else {
        sp<MetaData> meta = mImpl->getFormat();
        if (meta == NULL)
            return NULL;
    }

    sp<MetaData> meta = mImpl->getFormat();

    if (meta == NULL)
        return NULL;

    int64_t durationUs;

    if (!meta->findInt64(kKeyDuration,&durationUs)) {
        meta->setInt64(kKeyDuration, mExtractor->getDurationUs());
    }

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strncasecmp("audio/", mime, 6)) {
        mIsVideo = false;
#ifdef MTK_SUBTITLE_SUPPORT
    } else if (!strncasecmp("text/", mime, 5)) {
#endif
    } else {
        CHECK(!strncasecmp("video/", mime, 6));
        mIsVideo = true;
#ifdef MTK_AOSP_ENHANCEMENT
    meta->setPointer('anps', mImpl.get());
#endif
    }

    return meta;
}
#else
sp<MetaData> MPEG2TSSource::getFormat() {
    return mImpl->getFormat();
}
#endif

status_t MPEG2TSSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode seekMode;
#ifdef MTK_AOSP_ENHANCEMENT //// mtk08123 use mtk seek scenario
    if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        if (mExtractor->getVideoState() && !mIsVideo && !mDoesSeek) {
            mDoesSeek = true;
            ALOGE("video Audio can seek now");
        }
        if (mDoesSeek) {
            if(mExtractor->IsLocalSource()){//mtk08123:loacal seek
                mExtractor->seekTo(seekTimeUs);
                mImpl->queueDiscontinuity(ATSParser::DISCONTINUITY_NONE, NULL, false /* discard */ );
            }else{
                // http streaming seek is needed
                status_t err = mExtractor->seek(seekTimeUs, seekMode);
                if (err != OK) {
                    return err;
                }
            }
        }
    }
    // if has left buffer return; if seek, clear buffer   todo:maybe modify this code
    if (mWantsNALFragments) {
        if (options != NULL) {
            mLeftBuffer.clear();
        } else if (!mLeftBuffer.empty()) {
            sp<ABuffer> buffer = *mLeftBuffer.begin();
            mLeftBuffer.erase(mLeftBuffer.begin());

            int64_t timeUs;
            CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

            MediaBuffer *mediaBuffer = new MediaBuffer(buffer);
            mediaBuffer->meta_data()->setInt64(kKeyTime, timeUs);

            int64_t seekTimeUs;
            ReadOptions::SeekMode seekMode;
            if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
                mediaBuffer->meta_data()->setInt64(kKeyTargetTime,
                                                   seekTimeUs);
            }
            *out = mediaBuffer;
            return OK;
        }
    }
#else
    if (mDoesSeek && options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        // seek is needed
        status_t err = mExtractor->seek(seekTimeUs, seekMode);
        if (err != OK) {
            return err;
        }
    }
#endif

    if (mExtractor->feedUntilBufferAvailable(mImpl) != OK) {
        return ERROR_END_OF_STREAM;
    }

#ifndef MTK_AOSP_ENHANCEMENT
    return mImpl->read(out, options);
#else
    if (!mWantsNALFragments) {
#ifdef MTK_AUDIO_CHANGE_SUPPORT
        status_t ret;
        ret = mImpl->read(out, options);
        if(ret == OK){
            sp<MetaData> meta = mImpl->getFormat();
            const char *mime;
            CHECK(meta->findCString(kKeyMIMEType, &mime));
            if (!strncasecmp("audio/", mime, 6)) {
                MediaBuffer *buffer = *out;
                int64_t timeUs;
                CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));
                if(timeUs >=0){
                   mExtractor->consumeData(mImpl,timeUs,true);
                }
            }
        }
        return ret;
#else
        return mImpl->read(out, options);
#endif
    } else {
        status_t ret;
        if ((ret = mImpl->read(out, options)) != OK) {
            ALOGI("mImpl->read not OK");
            return ret;
        }
        MediaBuffer *buffers = *out;
        cutBufferToNAL(buffers);
        buffers->release();

        // if left buffer is not empty, return buffer
        if (!mLeftBuffer.empty()) {
            sp<ABuffer> buffer = *mLeftBuffer.begin();
            mLeftBuffer.erase(mLeftBuffer.begin());

            int64_t timeUs;
            CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

            MediaBuffer *mediaBuffer = new MediaBuffer(buffer);
            mediaBuffer->meta_data()->setInt64(kKeyTime, timeUs);

            int64_t seekTimeUs;
            ReadOptions::SeekMode seekMode;
            if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
                mediaBuffer->meta_data()->setInt64(kKeyTargetTime,
                                                   seekTimeUs);
            }
            *out = mediaBuffer;
#ifdef MTK_AUDIO_CHANGE_SUPPORT
            sp<MetaData> meta = mImpl->getFormat();
            const char *mime;
            CHECK(meta->findCString(kKeyMIMEType, &mime));
            if (!strncasecmp("audio/", mime, 6)) {
                //MediaBuffer *buffer = *out;
                if(timeUs >=0){
                   mExtractor->consumeData(mImpl,timeUs,true);
                }
            }
#endif
            return OK;
        } else {
            ALOGW("cut nal fail");
            return UNKNOWN_ERROR;
        }
    }
#endif
}

////////////////////////////////////////////////////////////////////////////////

#ifdef MTK_AOSP_ENHANCEMENT
int32_t findSyncCode(const void *data, size_t size) {
    uint32_t i = 0;
    for (i = 0; i < size; i++) {
        if (((uint8_t *) data)[i] == 0x47u)
            return i;
    }
    return -1;
}
#endif
MPEG2TSExtractor::MPEG2TSExtractor(const sp<DataSource> &source)
    : mDataSource(source),
#ifndef MTK_AOSP_ENHANCEMENT
      mParser(new ATSParser),
#endif
      mOffset(0) {
#ifdef MTK_AOSP_ENHANCEMENT
    if(mDataSource->flags() & DataSource::kIsCachingDataSource){
        mParser = new ATSParser(0x80000000);
    }else{
        mParser = new ATSParser(0x40000000);
    }
    mDurationUs = 0;
    mSeekTimeUs = 0;
    mSeeking = false;
    mFindingMaxPTS = false;
    End_OF_FILE = false;//cherry
    mSeekingOffset = 0;
    mFileSize = 0;
    mMinOffset = 0;
    mMaxcount = 0;
    mMaxOffset = 0;
    mOffsetPAT = 0;
    mVideoUnSupportedByDecoder = false;
    ALOGD("=====================================\n");
    ALOGD("[MPEG2TS Playback capability info]\n");
    ALOGD("=====================================\n");
    ALOGD("Resolution = \"[(8,8) ~ (1280,720)]\" \n");
    ALOGD("Support Codec = \"Video:MPEG4, H264, MPEG1,MPEG2 ; Audio: AAC,MP3\" \n");
    ALOGD("Profile_Level = \"MPEG4: ASP ;  H264: Baseline/3.1, Main/3.1,High/3.1\" \n");
    ALOGD("Max frameRate =  120fps \n");
    ALOGD("Max Bitrate  = H264: 2Mbps  (720P@30fps) ; MPEG4/H263: 4Mbps (720P@30fps)\n");
    ALOGD("=====================================\n");
    if (!(mDataSource->flags() & DataSource::kIsCachingDataSource)) {   // http streaming do not get duration
    bool retb=true;
    off64_t StartOffset = 0;
    off64_t NewOffset = 0;

    if (findSyncWord(source,StartOffset,10 * kTSPacketSize,kTSPacketSize,NewOffset)) {
        ALOGD("MPEG2TSExtractor:this is ts file\n");
        kFillPacketSize = kTSPacketSize;
    }else {
        retb = false;
    }

    if(!retb){
        StartOffset = 0;
        NewOffset = 0;
        retb = true;
        if (findSyncWord(source,StartOffset,10 * kM2TSPacketSize,kM2TSPacketSize,NewOffset)) {
            ALOGD("MPEG2TSExtractor:this is m2ts file\n");
            kFillPacketSize = kM2TSPacketSize;
        } else {
            retb = false;
            ALOGE("MPEG2TSExtractor: it is not a ts/m2ts file!!!");
        }
    }
        status_t err = parseMaxPTS();   //parse all the TS packet of this file?
        if (err != OK) {
            return;
        }

#ifdef MTK_AUDIO_CHANGE_SUPPORT
    //reset the firstPTSisvalid of program which is not playing
    mParser->setFirstPTSIsValid();
#endif
    }
    //[qian]may be we should add the seek table creation this section
    //when 2st parse whole file
    ALOGD("MPEG2TSExtractor: after parseMaxPTS  mOffset=%lld", (long long)mOffset);
#endif
    init();
}

size_t MPEG2TSExtractor::countTracks() {
    return mSourceImpls.size();
}

sp<MediaSource> MPEG2TSExtractor::getTrack(size_t index) {
    if (index >= mSourceImpls.size()) {
        return NULL;
    }

#ifndef MTK_AOSP_ENHANCEMENT
    // The seek reference track (video if present; audio otherwise) performs
    // seek requests, while other tracks ignore requests.
    return new MPEG2TSSource(this, mSourceImpls.editItemAt(index),
            (mSeekSyncPoints == &mSyncPoints.editItemAt(index)));
#else
    bool doesSeek = true;
    if (mSourceImpls.size() > 1) {
#ifndef MTK_AUDIO_CHANGE_SUPPORT
        CHECK_EQ(mSourceImpls.size(), 2u);
#endif
        sp<MetaData> meta = mSourceImpls.editItemAt(index)->getFormat();
        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (!strncasecmp("audio/", mime, 6)) {
            doesSeek = false;
        }
#ifdef MTK_SUBTITLE_SUPPORT
        if (!strncasecmp("text/", mime, 5)){
            doesSeek = false;
        }
#endif
    }

    return new MPEG2TSSource(this, mSourceImpls.editItemAt(index), doesSeek);
#endif
}

sp<MetaData> MPEG2TSExtractor::getTrackMetaData(
        size_t index, uint32_t /* flags */) {
#ifdef MTK_AOSP_ENHANCEMENT
    if (index >= mSourceImpls.size())
        return NULL;

    sp<MetaData> meta = mSourceImpls.editItemAt(index)->getFormat();

    int64_t durationUs = (int64_t) (getDurationUs());
    if (durationUs < 0 || NULL == meta.get())
        return NULL;

    if (meta != NULL) {
        meta->setInt64(kKeyDuration, getDurationUs());
    }
#endif
    return index < mSourceImpls.size()
        ? mSourceImpls.editItemAt(index)->getFormat() : NULL;
}

sp<MetaData> MPEG2TSExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;
#ifdef MTK_AOSP_ENHANCEMENT
    bool hasVideo = false;
    for (size_t index = 0; index < mSourceImpls.size(); index++) {
        sp<MetaData> meta = mSourceImpls.editItemAt(index)->getFormat();
        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));
        if (!strncasecmp("video/", mime, 6)) {
            hasVideo = true;
        }
    }
    //[qian]can set the hasvideo to be class member, not need to read meta
    //has parsed the hasvideo value in init() funtion
    if (hasVideo) {
        meta->setCString(kKeyMIMEType, "video/mp2ts");
    } else {
        meta->setCString(kKeyMIMEType, "audio/mp2ts");
    }

    // set flag for handle the case: video too long to audio
    meta->setInt32(kKeyVideoPreCheck, 1);

#else
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

#endif
    return meta;
}

void MPEG2TSExtractor::init() {
    bool haveAudio = false;
    bool haveVideo = false;
    int64_t startTime = ALooper::GetNowUs();

#ifdef MTK_AOSP_ENHANCEMENT
    mOffset = 0;
    End_OF_FILE = false;
#endif
#ifdef MTK_AUDIO_CHANGE_SUPPORT
    int numPacketsParsed = 0;
    sp<MetaData> meta;
    Vector<sp<AnotherPacketSource> > mSourceImplsTemp;
    unsigned* puStrmPidInfo = NULL;
    int i4PidNum = mParser->parsedPIDSize();
    int i4PidNumIdx = 0;
    unsigned i4PidSourceIdx = 0;
    puStrmPidInfo = new unsigned[i4PidNum];

    for (i4PidNumIdx = 0; i4PidNumIdx < i4PidNum; i4PidNumIdx ++){
       puStrmPidInfo[i4PidNumIdx] = mParser->getParsedPID(i4PidNumIdx);
    }

    while (feedMore() == OK) {
        unsigned i = 0;
        unsigned i4PidIdx = 0;

        if (!haveVideo) {
            sp<AnotherPacketSource> impl =
                (AnotherPacketSource *)mParser->getSource(
                        ATSParser::VIDEO).get();

            if (impl != NULL) {
                haveVideo = true;
            }
        }
        if (!haveAudio) {
            sp<AnotherPacketSource> impl =
                (AnotherPacketSource *)mParser->getSource(
                        ATSParser::AUDIO).get();

            if (impl != NULL) {
                haveAudio = true;
            }
        }
        while (i < mParser->parsedPIDSize()) {

            unsigned elemPID = mParser->getParsedPID(i);
            bool hasAddSouce = false;

            for(i4PidIdx = 0; i4PidIdx < mSourceImplsTemp.size(); i4PidIdx++){
               if (elemPID == mSourceImplsTemp.editItemAt(i4PidIdx)->getSourcePID()){
                   hasAddSouce = true;
                   break;
               }
            }
            if(!hasAddSouce) {
                sp<AnotherPacketSource> impl =
                    (AnotherPacketSource *)mParser->getSource(elemPID, 0).get();

                if (impl != NULL) {
                    ALOGD("add source with PID:%x  n:%d", elemPID, numPacketsParsed);
                    impl->setSourcePID(elemPID);
                    mSourceImplsTemp.push(impl);
                    //mParser->removeParsedPID(i);
                    //i = 0;//reset index
                    //ALOGD("isParsedPIDEmpty:%d, size:%d",mParser->isParsedPIDEmpty(),mSourceImpls.size());
                }
            }
            i++;
        }

        if((mParser->parsedPIDSize() == mSourceImplsTemp.size()) && mSourceImplsTemp.size() != 0) {
            ALOGD("find all source n:%d",numPacketsParsed);
            break;
        }
        if (++numPacketsParsed > 30000) {
            ALOGD("can not fine the source data %d",numPacketsParsed);
            break;
        }
    }
    if (mSourceImplsTemp.size() != 0){
        for (i4PidNumIdx = 0; i4PidNumIdx < i4PidNum; i4PidNumIdx ++){
           for(i4PidSourceIdx = 0; i4PidSourceIdx<mSourceImplsTemp.size(); i4PidSourceIdx++){
               if (puStrmPidInfo[i4PidNumIdx] == mSourceImplsTemp.editItemAt(i4PidSourceIdx)->getSourcePID()){
                   mSourceImpls.push(mSourceImplsTemp.editItemAt(i4PidSourceIdx));
                   mSyncPoints.push();
                   mSeekSyncPoints = &mSyncPoints.editTop();
                   ALOGD("re-add source with PID:%u", puStrmPidInfo[i4PidNumIdx]);
               }
           }
        }
    }
    if (puStrmPidInfo != NULL)
    {
        free(puStrmPidInfo);
        puStrmPidInfo = NULL;
    }

    for (unsigned i=0; i < mSourceImpls.size();i++){
        ALOGD("mSourceImpls.size():%d,implPID:0x%x",(int)mSourceImpls.size(),mSourceImpls.editItemAt(i)->getSourcePID());
    }
#else
    while (feedMore() == OK) {
        if (haveAudio && haveVideo) {
            break;
        }
        if (!haveVideo) {
            sp<AnotherPacketSource> impl =
                (AnotherPacketSource *)mParser->getSource(
                        ATSParser::VIDEO).get();

            if (impl != NULL) {
                haveVideo = true;
                mSourceImpls.push(impl);
                mSyncPoints.push();
                mSeekSyncPoints = &mSyncPoints.editTop();
            }
        }

        if (!haveAudio) {
            sp<AnotherPacketSource> impl =
                (AnotherPacketSource *)mParser->getSource(
                        ATSParser::AUDIO).get();

            if (impl != NULL) {
                haveAudio = true;
                mSourceImpls.push(impl);
                mSyncPoints.push();
                if (!haveVideo) {
                    mSeekSyncPoints = &mSyncPoints.editTop();
                }
            }
        }

        // Wait only for 2 seconds to detect audio/video streams.
        if (ALooper::GetNowUs() - startTime > 2000000ll) {

            break;
        }
    }
#endif//MTK_AUDIO_CHANGE_SUPPORT

    off64_t size;

#ifdef MTK_AOSP_ENHANCEMENT
    if ((mDataSource->flags() & DataSource::kIsCachingDataSource)&&
        mDataSource->getSize(&size) == OK && (haveAudio || haveVideo)) {
#else
    if (mDataSource->getSize(&size) == OK && (haveAudio || haveVideo)) {
#endif
        sp<AnotherPacketSource> impl = haveVideo
                ? (AnotherPacketSource *)mParser->getSource(
                        ATSParser::VIDEO).get()
                : (AnotherPacketSource *)mParser->getSource(
                        ATSParser::AUDIO).get();
        size_t prevSyncSize = 1;
        int64_t durationUs = -1;
        List<int64_t> durations;
        // Estimate duration --- stabilize until you get <500ms deviation.
        while (feedMore() == OK
                && ALooper::GetNowUs() - startTime <= 2000000ll) {
            if (mSeekSyncPoints->size() > prevSyncSize) {
                prevSyncSize = mSeekSyncPoints->size();
                int64_t diffUs = mSeekSyncPoints->keyAt(prevSyncSize - 1)
                        - mSeekSyncPoints->keyAt(0);
                off64_t diffOffset = mSeekSyncPoints->valueAt(prevSyncSize - 1)
                        - mSeekSyncPoints->valueAt(0);
                durationUs = size * diffUs / diffOffset;
                durations.push_back(durationUs);
                if (durations.size() > 5) {
                    durations.erase(durations.begin());
                    int64_t min = *durations.begin();
                    int64_t max = *durations.begin();
                    for (List<int64_t>::iterator i = durations.begin();
                            i != durations.end();
                            ++i) {
                        if (min > *i) {
                            min = *i;
                        }
                        if (max < *i) {
                            max = *i;
                        }
                    }
                    if (max - min < 500 * 1000) {
                        break;
                    }
                }
            }
        }
        status_t err;
        int64_t bufferedDurationUs;
        bufferedDurationUs = impl->getBufferedDurationUs(&err);
        if (err == ERROR_END_OF_STREAM) {
            durationUs = bufferedDurationUs;
        }
        if (durationUs > 0) {
            const sp<MetaData> meta = impl->getFormat();
#ifdef MTK_AOSP_ENHANCEMENT
            mDurationUs = durationUs;
#else
            meta->setInt64(kKeyDuration, durationUs);
            impl->setFormat(meta);
#endif
        }
    }

    ALOGI("haveAudio=%d, haveVideo=%d, elaspedTime=%" PRId64,
            haveAudio, haveVideo, ALooper::GetNowUs() - startTime);
}

status_t MPEG2TSExtractor::feedMore() {
    Mutex::Autolock autoLock(mLock);
#ifdef MTK_AOSP_ENHANCEMENT// mtk08123 use mtk seek scenario
    if (mSeeking) {
        int64_t pts = mParser->getMaxPTS(); //[qian] get the max pts in the had read data
        if(End_OF_FILE){
            ALOGE("seek to End_OF_FILE last time");
            mOffset=
               (off64_t) ((((mMinOffset +
                              mMaxOffset) / 2) / kFillPacketSize) *
                           kFillPacketSize);

            mSeekingOffset = mOffset;
            End_OF_FILE=false;

        }//to solve the problem that when seek to End_OF_FILE then seek to another place ,instantly. and can not play

        if (pts > 0) {
            mMaxcount++;
            if ((pts - mSeekTimeUs < 50000 && pts - mSeekTimeUs > -50000)
                || mMinOffset == mMaxOffset || mMaxcount > 13) {
                //ALOGE("pts=%lld,mSeekTimeUs=%lld,mMaxcount=%lld,mMinOffset=0x%x,mMaxOffset=0x%x",pts/1000,mSeekTimeUs/1000,mMaxcount, mMinOffset,mMaxOffset );
                mSeeking = false;
                mParser->setDequeueState(true); //
            } else {
                mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                             /* isSeek */ );
                if (pts < mSeekTimeUs) {
                    mMinOffset = mSeekingOffset;    //[qian], 1 enter this will begin with the mid of file

                } else {
                    mMaxOffset = mSeekingOffset;
                }
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
                mSeekingOffset =
                    (off64_t) ((((mMinOffset +
                                  mMaxOffset) / 2) / kFillPacketSize) *
                               kFillPacketSize);
#else //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

                mSeekingOffset =
                    (off64_t) ((((mMinOffset +
                                  mMaxOffset) / 2) / kTSPacketSize) *
                               kTSPacketSize);
#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

                mOffset = mSeekingOffset;
            }
            ALOGE
                ("pts=%lld,mSeekTimeUs=%lld,mMaxcount=%lld,mOffset=%lld,mMinOffset=%lld,mMaxOffset=%lld",
                 (long long)pts / 1000, (long long)mSeekTimeUs / 1000, (long long)mMaxcount, (long long)mOffset,
                 (long long)mMinOffset, (long long)mMaxOffset);

        }
    }
#endif
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
    uint8_t packet[kFillPacketSize * 2];
    status_t retv = OK;
    ssize_t n = mDataSource->readAt(mOffset, packet, kFillPacketSize);

    if (n < (ssize_t) kFillPacketSize) {
        ALOGE(" mOffset=%lld,n =%zd", (long long)mOffset, n);
        End_OF_FILE = true;
        if ((!mFindingMaxPTS)&&(!mSeeking)&&(n >= 0)) {
            mParser->signalEOS(ERROR_END_OF_STREAM);
        }
        return (n < 0) ? (status_t) n : ERROR_END_OF_STREAM;
    }
    ALOGV("feedMore:mOffset = %lld  packet=0x%x,0x%x,0x%x,0x%x", (long long)mOffset,
          packet[0], packet[1], packet[2], packet[3]);

    ATSParser::SyncEvent event(mOffset); //mtk08123 add for M
    mOffset += n;
    if (kFillPacketSize == kM2TSPacketSize) {

        retv = mParser->feedTSPacket(packet + 4, kFillPacketSize - 4, &event);
        if (event.isInit()) {
            for (size_t i = 0; i < mSourceImpls.size(); ++i) {
                if (mSourceImpls[i].get() == event.getMediaSource().get()) {
                    KeyedVector<int64_t, off64_t> *syncPoints = &mSyncPoints.editItemAt(i);
                    syncPoints->add(event.getTimeUs(), event.getOffset());
                    // We're keeping the size of the sync points at most 5mb per a track.
                    size_t size = syncPoints->size();
                    if (size >= 327680) {
                        int64_t firstTimeUs = syncPoints->keyAt(0);
                        int64_t lastTimeUs = syncPoints->keyAt(size - 1);
                        if (event.getTimeUs() - firstTimeUs > lastTimeUs - event.getTimeUs()) {
                            syncPoints->removeItemsAt(0, 4096);
                        } else {
                            syncPoints->removeItemsAt(size - 4096, 4096);
                        }
                    }
                    break;
                }
            }
        }

        if (retv == BAD_VALUE) {
            int32_t syncOff = 0;
            syncOff = findSyncCode(packet + 4, kFillPacketSize - 4);
            if (syncOff >= 0) {
                mOffset -= n;
                mOffset += syncOff;
            }
            return OK;
        } else {
            return retv;
        }

    } else {

        retv = mParser->feedTSPacket(packet, kFillPacketSize, &event);
        if (event.isInit()) {
            for (size_t i = 0; i < mSourceImpls.size(); ++i) {
                if (mSourceImpls[i].get() == event.getMediaSource().get()) {
                    KeyedVector<int64_t, off64_t> *syncPoints = &mSyncPoints.editItemAt(i);
                    syncPoints->add(event.getTimeUs(), event.getOffset());
                    // We're keeping the size of the sync points at most 5mb per a track.
                    size_t size = syncPoints->size();
                    if (size >= 327680) {
                        int64_t firstTimeUs = syncPoints->keyAt(0);
                        int64_t lastTimeUs = syncPoints->keyAt(size - 1);
                        if (event.getTimeUs() - firstTimeUs > lastTimeUs - event.getTimeUs()) {
                            syncPoints->removeItemsAt(0, 4096);
                        } else {
                            syncPoints->removeItemsAt(size - 4096, 4096);
                        }
                    }
                    break;
                }
            }
        }

        if (retv == BAD_VALUE) {
            int32_t syncOff = 0;
            syncOff = findSyncCode(packet, kFillPacketSize);
            if (syncOff >= 0) {
                mOffset -= n;
                mOffset += syncOff;
            }
            ALOGE("[TS_ERROR]correction once offset mOffset=%lld", (long long)mOffset);
            return OK;
        } else {
            return retv;
        }
    }
#else //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

#ifdef MTK_AOSP_ENHANCEMENT
    uint8_t packet[kTSPacketSize];
    status_t retv = OK;

    ssize_t n = mDataSource->readAt(mOffset, packet, kTSPacketSize);

    if (n < (ssize_t) kTSPacketSize) {
        ALOGE(" mOffset=%lld,n =%ld", mOffset, n);
        if ((!mFindingMaxPTS)&&(!mSeeking)&&(n >= 0)) { //add
            mParser->signalEOS(ERROR_END_OF_STREAM);
        }//add
        return (n < 0) ? (status_t) n : ERROR_END_OF_STREAM;
    }

    ALOGV("mOffset= %lld  packet=0x%x,0x%x,0x%x,0x%x", mOffset, packet[0],
          packet[1], packet[2], packet[3]);
    ATSParser::SyncEvent event(mOffset); //mtk08123 add for M, but no use
    mOffset += n;
    retv = mParser->feedTSPacket(packet, kTSPacketSize, &event);

    if (retv == BAD_VALUE) {
        int32_t syncOff = 0;
        syncOff = findSyncCode(packet, kTSPacketSize);
        if (syncOff >= 0) {
            mOffset -= n;
            mOffset += syncOff;
        }
        ALOGE("[TS_ERROR]correction once offset mOffset=%lld", mOffset);
        return OK;
    } else {
        return retv;
    }
#else
    uint8_t packet[kTSPacketSize];
    ssize_t n = mDataSource->readAt(mOffset, packet, kTSPacketSize);

    if (n < (ssize_t)kTSPacketSize) {
        if (n >= 0) {
            mParser->signalEOS(ERROR_END_OF_STREAM);
        }
        return (n < 0) ? (status_t)n : ERROR_END_OF_STREAM;
    }

    ATSParser::SyncEvent event(mOffset);
    mOffset += n;
    status_t err = mParser->feedTSPacket(packet, kTSPacketSize, &event);
    if (event.isInit()) {
        for (size_t i = 0; i < mSourceImpls.size(); ++i) {
            if (mSourceImpls[i].get() == event.getMediaSource().get()) {
                KeyedVector<int64_t, off64_t> *syncPoints = &mSyncPoints.editItemAt(i);
                syncPoints->add(event.getTimeUs(), event.getOffset());
                // We're keeping the size of the sync points at most 5mb per a track.
                size_t size = syncPoints->size();
                if (size >= 327680) {
                    int64_t firstTimeUs = syncPoints->keyAt(0);
                    int64_t lastTimeUs = syncPoints->keyAt(size - 1);
                    if (event.getTimeUs() - firstTimeUs > lastTimeUs - event.getTimeUs()) {
                        syncPoints->removeItemsAt(0, 4096);
                    } else {
                        syncPoints->removeItemsAt(size - 4096, 4096);
                    }
                }
                break;
            }
        }
    }
    return err;
#endif
#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))
}

uint32_t MPEG2TSExtractor::flags() const {
    return CAN_PAUSE | CAN_SEEK_BACKWARD | CAN_SEEK_FORWARD;
}

//mtk08123 http streaming use google default seek
status_t MPEG2TSExtractor::seek(int64_t seekTimeUs,
        const MediaSource::ReadOptions::SeekMode &seekMode) {
    if (mSeekSyncPoints == NULL || mSeekSyncPoints->isEmpty()) {
        ALOGW("No sync point to seek to.");
        // ... and therefore we have nothing useful to do here.
        return OK;
    }

    // Determine whether we're seeking beyond the known area.
    bool shouldSeekBeyond =
            (seekTimeUs > mSeekSyncPoints->keyAt(mSeekSyncPoints->size() - 1));

    // Determine the sync point to seek.
    size_t index = 0;
    for (; index < mSeekSyncPoints->size(); ++index) {
        int64_t timeUs = mSeekSyncPoints->keyAt(index);
        if (timeUs > seekTimeUs) {
            break;
        }
    }

    switch (seekMode) {
        case MediaSource::ReadOptions::SEEK_NEXT_SYNC:
            if (index == mSeekSyncPoints->size()) {
                ALOGW("Next sync not found; starting from the latest sync.");
                --index;
            }
            break;
        case MediaSource::ReadOptions::SEEK_CLOSEST_SYNC:
        case MediaSource::ReadOptions::SEEK_CLOSEST:
            ALOGW("seekMode not supported: %d; falling back to PREVIOUS_SYNC",
                    seekMode);
            // fall-through
        case MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC:
            if (index == 0) {
                ALOGW("Previous sync not found; starting from the earliest "
                        "sync.");
            } else {
                --index;
            }
#ifdef MTK_AOSP_ENHANCEMENT
        default:
#endif
            break;
    }
    if (!shouldSeekBeyond || mOffset <= mSeekSyncPoints->valueAt(index)) {
        int64_t actualSeekTimeUs = mSeekSyncPoints->keyAt(index);
        mOffset = mSeekSyncPoints->valueAt(index);
        status_t err = queueDiscontinuityForSeek(actualSeekTimeUs);
        if (err != OK) {
            return err;
        }
    }

    if (shouldSeekBeyond) {
        status_t err = seekBeyond(seekTimeUs);
        if (err != OK) {
            return err;
        }
    }

    // Fast-forward to sync frame.
    for (size_t i = 0; i < mSourceImpls.size(); ++i) {
        const sp<AnotherPacketSource> &impl = mSourceImpls[i];
        status_t err;
        feedUntilBufferAvailable(impl);
        while (impl->hasBufferAvailable(&err)) {
            sp<AMessage> meta = impl->getMetaAfterLastDequeued(0);
            sp<ABuffer> buffer;
            if (meta == NULL) {
                return UNKNOWN_ERROR;
            }
            int32_t sync;
            if (meta->findInt32("isSync", &sync) && sync) {
                break;
            }
            err = impl->dequeueAccessUnit(&buffer);
            if (err != OK) {
                return err;
            }
            feedUntilBufferAvailable(impl);
        }
    }

    return OK;
}

status_t MPEG2TSExtractor::queueDiscontinuityForSeek(int64_t actualSeekTimeUs) {
    // Signal discontinuity
    sp<AMessage> extra(new AMessage);
    extra->setInt64(IStreamListener::kKeyMediaTimeUs, actualSeekTimeUs);
    mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME, extra);

    // After discontinuity, impl should only have discontinuities
    // with the last being what we queued. Dequeue them all here.
    for (size_t i = 0; i < mSourceImpls.size(); ++i) {
        const sp<AnotherPacketSource> &impl = mSourceImpls.itemAt(i);
        sp<ABuffer> buffer;
        status_t err;
        while (impl->hasBufferAvailable(&err)) {
            if (err != OK) {
                return err;
            }
            err = impl->dequeueAccessUnit(&buffer);
            // If the source contains anything but discontinuity, that's
            // a programming mistake.
            CHECK(err == INFO_DISCONTINUITY);
        }
    }

    // Feed until we have a buffer for each source.
    for (size_t i = 0; i < mSourceImpls.size(); ++i) {
        const sp<AnotherPacketSource> &impl = mSourceImpls.itemAt(i);
        sp<ABuffer> buffer;
        status_t err = feedUntilBufferAvailable(impl);
        if (err != OK) {
            return err;
        }
    }

    return OK;
}

status_t MPEG2TSExtractor::seekBeyond(int64_t seekTimeUs) {
    // If we're seeking beyond where we know --- read until we reach there.
    size_t syncPointsSize = mSeekSyncPoints->size();

    while (seekTimeUs > mSeekSyncPoints->keyAt(
            mSeekSyncPoints->size() - 1)) {
        status_t err;
        if (syncPointsSize < mSeekSyncPoints->size()) {
            syncPointsSize = mSeekSyncPoints->size();
            int64_t syncTimeUs = mSeekSyncPoints->keyAt(syncPointsSize - 1);
            // Dequeue buffers before sync point in order to avoid too much
            // cache building up.
            sp<ABuffer> buffer;
            for (size_t i = 0; i < mSourceImpls.size(); ++i) {
                const sp<AnotherPacketSource> &impl = mSourceImpls[i];
                int64_t timeUs;
                while ((err = impl->nextBufferTime(&timeUs)) == OK) {
                    if (timeUs < syncTimeUs) {
                        impl->dequeueAccessUnit(&buffer);
                    } else {
                        break;
                    }
                }
                if (err != OK && err != -EWOULDBLOCK) {
                    return err;
                }
            }
        }
        if (feedMore() != OK) {
            return ERROR_END_OF_STREAM;
        }
    }

    return OK;
}
//

status_t MPEG2TSExtractor::feedUntilBufferAvailable(
        const sp<AnotherPacketSource> &impl) {
    status_t finalResult;
#ifdef MTK_AOSP_ENHANCEMENT //mtk08123 add for local seek
    while (!impl->hasBufferAvailable(&finalResult)|| getSeeking()) {
#else
    while (!impl->hasBufferAvailable(&finalResult)) {
#endif
        if (finalResult != OK) {
            return finalResult;
        }

        status_t err = feedMore();
        if (err != OK) {
            impl->signalEOS(err);
        }
    }
    return OK;
}

////////////////////////////////////////////////////////////////////////////////
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
bool SniffMPEG2TS(const sp<DataSource> &source, String8 * mimeType,
                  float *confidence, sp<AMessage> *) {
    bool retb = true;

    for (int i = 0; i < 5; ++i) {
        char header;
        if (source->readAt(kTSPacketSize * i, &header, 1) != 1
            || header != 0x47) {
            retb = false;
            break;
        }
    }
    if (retb) {
        ALOGD("this is ts file\n");
        kFillPacketSize = kTSPacketSize;
    } else {
        retb = true;
        for (int i = 0; i < 5; ++i) {
            char header[5];
            if (source->readAt(kM2TSPacketSize * i, &header, 5) != 5
                || header[4] != 0x47) {
                retb = false;
                return retb;
            }
        }
        if (retb) {
            ALOGD("this is m2ts file\n");
            kFillPacketSize = kM2TSPacketSize;
        }
    }

    *confidence = 0.3f;

    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

    return true;
}

#else

bool SniffMPEG2TS(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    for (int i = 0; i < 5; ++i) {
        char header;
        if (source->readAt(kTSPacketSize * i, &header, 1) != 1
                || header != 0x47) {
            return false;
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
    *confidence = 0.3f;
#else
    *confidence = 0.1f;
#endif
    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

    return true;
}

#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))
#ifdef MTK_AOSP_ENHANCEMENT ////////////////////////////////////////////////////////////////refine
status_t MPEG2TSSource::cutBufferToNAL(MediaBuffer * buffer) {
    const uint8_t *data = (uint8_t *) buffer->data() + buffer->range_offset();
    size_t size = buffer->range_length();

    int64_t timeUs = 0;
    CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));

    status_t err;
    const uint8_t *nalStart;
    size_t nalSize;
    while ((err =
            getNextNALUnit(&data, &size, &nalStart, &nalSize, true)) == OK) {
        CHECK_GT(nalSize, 0u);
        sp<ABuffer> nalBuf = new ABuffer(nalSize);
        memcpy(nalBuf->data(), nalStart, nalSize);
        nalBuf->meta()->setInt64("timeUs", timeUs);
        mLeftBuffer.push_back(nalBuf);
    }
    return OK;
}

#ifdef MTK_AUDIO_CHANGE_SUPPORT
bool MPEG2TSExtractor::needRemoveData(sp<AnotherPacketSource> impl,int64_t timeUs,bool isAudio){
    status_t finalResult ;
    bool i = isAudio;//only for build error
    i = !i;//only for build error
    if(impl->hasBufferAvailable(&finalResult)){
       sp<AMessage> firstMeta = impl->getLatestDequeuedMeta();
       if(firstMeta == NULL){
           int64_t timeDur = impl->getEstimatedDurationUs();
           if(timeDur > 2000000){ //2s
               ALOGD("dur :%lld >2s",(long long)timeDur);
               return true;
           }else{
               ALOGD("dur :%lld ",(long long)timeDur);
               return false;
           }

       }
       int64_t timeUs2;
       if((firstMeta !=NULL) && firstMeta->findInt64("timeUs", &timeUs2)){
          if(timeUs2 != -1 &&timeUs2 + 500000 < timeUs  ){
              ALOGV("timeUS:%lld, timeUs2:%lld",(long long)timeUs,(long long)timeUs2);
              return true;
          }
       }
    }
    return false;
}
bool MPEG2TSExtractor::consumeData(sp<AnotherPacketSource> impl,int64_t timeUs,bool isAudio){
    ALOGV("consumeData++ track num:%d, time:%lld",(int)mSourceImpls.size(),(long long)timeUs);
    bool i = isAudio;//only for build error
    i = !i;//only for build error
    sp<MetaData> meta = impl->getFormat();
    //const char *mime0;
    for (size_t index = 0; index < mSourceImpls.size(); index++) {
        sp<AnotherPacketSource> impl1 = mSourceImpls.editItemAt(index);
        if(impl1 != impl){// can compare?
            sp<MetaData> meta2 = impl1->getFormat();
            if(meta2 == NULL){
                ALOGD("meta2 null ");
                continue;
            }
            const char *mime;
            CHECK(meta2->findCString('mime', &mime));
            if (!strncasecmp("audio/", mime, 6)) {
                status_t finalResult;
                while(impl1->hasBufferAvailable(&finalResult)){
                    if(needRemoveData(impl1,timeUs,true)){
                        MediaBuffer *out;
                        MediaSource::ReadOptions options;
                        status_t err1 = impl1->read(&out,&options);
                        if(err1 == OK){
                           MediaBuffer *buffer = out;
                           int64_t  removetimeUs;
                           CHECK(buffer->meta_data()->findInt64(kKeyTime, &removetimeUs));
                           ALOGD("remove time:%lld,play time :%lld, index:%d,mime:%s",(long long)removetimeUs,(long long)timeUs,(int)index,mime);
                           buffer->release();
                        }else{
                            ALOGD("read err:%d",err1);
                        }
                     }else{
                          ALOGV("no need remove");
                          break;
                     }
                }
            }
        }
    }
    ALOGV("consumeData--");
    return true;
}
#endif
bool MPEG2TSExtractor::getSeeking() {
    return mSeeking;
}
bool MPEG2TSExtractor::IsLocalSource() {
    if(!(mDataSource->flags() & DataSource::kIsCachingDataSource)){
       return true;
    }
    return false;
}
void MPEG2TSExtractor::setVideoState(bool state) {
    mVideoUnSupportedByDecoder = state;
    ALOGE("setVideoState  mVideoUnSupportedByDecoder=%d",
          mVideoUnSupportedByDecoder);
}
bool MPEG2TSExtractor::getVideoState(void) {
    ALOGE("getVideoState  mVideoUnSupportedByDecoder=%d",
          mVideoUnSupportedByDecoder);
    return mVideoUnSupportedByDecoder;

}
bool MPEG2TSExtractor::findPAT() {
    Mutex::Autolock autoLock(mLock);

#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
    uint8_t packet[kFillPacketSize];
    mDataSource->readAt(mOffset, packet, kFillPacketSize);
    ALOGV("findPAT mOffset= %lld  packet=0x%x,0x%x,0x%x,0x%x", (long long)mOffset,
          packet[0], packet[1], packet[2], packet[3]);
    if (kFillPacketSize == kM2TSPacketSize) {
        return mParser->findPAT(packet + 4, kFillPacketSize - 4);
    } else {
        return mParser->findPAT(packet, kFillPacketSize);
    }
#else //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

    uint8_t packet[kTSPacketSize];

    mDataSource->readAt(mOffset, packet, kTSPacketSize);
    ALOGV("findPAT mOffset=0x%lld,packet=0x%x,0x%x,0x%x,0x%x", mOffset,
          packet[0], packet[1], packet[2], packet[3]);
    return mParser->findPAT(packet, kTSPacketSize);

#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

}
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
bool findSyncWord(const sp<DataSource> &source,off64_t StartOffset, uint64_t size, size_t PacketSize, off64_t &NewOffset) {

    uint8_t packet[PacketSize];
    off64_t Offset = StartOffset;

    source->readAt(Offset, packet, PacketSize);
    ALOGV("findSyncWord mOffset= %lld  packet=0x%x,0x%x,0x%x,0x%x,0x%x",(long long)Offset,packet[0],packet[1],packet[2],packet[3],packet[4]);

    if(((PacketSize == kTSPacketSize) && packet[0] != 0x47) ||
        ((PacketSize == kM2TSPacketSize) && packet[4] != 0x47)){
        uint8_t packetTempS[PacketSize*3];
        int32_t index = 0;
        for (;Offset < (off64_t)(StartOffset + size - 3*PacketSize);) {

            Offset = Offset + PacketSize;
            source->readAt(Offset, packetTempS, PacketSize*3);
            for (index = 0; index < (int32_t)PacketSize; index++) {
                if((packetTempS[index] == 0x47) && (packetTempS[index+ PacketSize] == 0x47) && (packetTempS[index+ PacketSize*2] == 0x47)){
                    break;
                    }
            }
            if(index >= ((int32_t)PacketSize)){
                //ALOGE("findSyncWord: can not find sync word");
            }else if (index >= 0) {
                if(PacketSize == kTSPacketSize) {
                    NewOffset = Offset + index + 2 * PacketSize;
                }else {
                    NewOffset = Offset + index - 4 + 2 * PacketSize;
                }
                ALOGD("findSyncWord mOffset= %lld  kFillPacketSize:%zu packet=0x%x,0x%x,0x%x,0x%x,0x%x",(long long)NewOffset,PacketSize,packetTempS[index],packetTempS[index+1],packetTempS[index+2],packetTempS[index+3],packetTempS[index+4]);
                return true;
            }
        }
        ALOGE("findSyncWord: can not find sync word");
    }else {
        return true;
    }
    return false;
}
#endif
status_t MPEG2TSExtractor::parseMaxPTS() {
    mFindingMaxPTS = true;
    mDataSource->getSize(&mFileSize);
    ALOGE("mFileSize:%lld",(long long)mFileSize);
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
    off64_t counts = mFileSize / kFillPacketSize;
#else //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))
    off64_t counts = mFileSize / kTSPacketSize;
#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

#ifdef MTK_AOSP_ENHANCEMENT
    int32_t numPacketsParsed = 0;
#endif
    int64_t maxPTSStart = systemTime() / 1000;
    //really dequeue data?
    mParser->setDequeueState(false);
    //[qian]set false, when parse the ts pakect, will not exec the  main function of onPayloadData
    //only parse the PAT, PMT,PES header, save parse time

    //if (!(mParser->mFlags & TS_TIMESTAMPS_ARE_ABSOLUTE)) {
    //get first pts(pts in in PES packet)
    bool foundFirstPTS = false;
    while (feedMore() == OK) {
        if (mParser->firstPTSIsValid()) {
            ALOGD("parseMaxPTS:firstPTSIsValid, mOffset %lld", (long long)mOffset);
            foundFirstPTS = true;
            break;
        }
#ifdef MTK_AOSP_ENHANCEMENT
        if (++numPacketsParsed > 30000)
        {
            break;
        }
#endif
    }
    if (!foundFirstPTS) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGI("not found first PTS numPacketsParsed %d", numPacketsParsed);
#else
        ALOGI("not found first PTS");
#endif
        return OK;
    }
    //clear

    mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME /* isSeek */ );
    //}

    //get duration
    mOffsetPAT = mFileSize;
    for (off64_t i = 1; i <= counts; i++) {
        int64_t maxPTSDuration = systemTime() / 1000 - maxPTSStart;
        if (maxPTSDuration > kMaxPTSTimeOutUs) {
            ALOGD("TimeOut find PTS, start time=%lld, duration=%lld",
                  (long long)maxPTSStart, (long long)maxPTSDuration);
            return UNKNOWN_ERROR;
        }
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))

        if(mOffsetPAT > (off64_t)(2500 * i * i * kFillPacketSize)){
            mOffsetPAT= (off64_t)(mOffsetPAT - 2500 * i * i * kFillPacketSize);
        }else {
            mOffsetPAT = 0;
        }

        mOffset = mOffsetPAT;
#else //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))

        mOffset = (off64_t) ((counts - i) * kTSPacketSize);
#endif //#if !defined(ANDROID_DEFAULT_CODE) && (defined(SUPPORT_M2TS))
#if defined(MTK_AOSP_ENHANCEMENT) && (defined(SUPPORT_M2TS))
        if (findSyncWord(mDataSource,mOffsetPAT,1000 * kFillPacketSize,kFillPacketSize,mOffset)) {//find last PAT
#else
        if (findPAT()) {        //find last PAT
#endif
            //start searching from the last PAT
            ALOGD("parseMaxPTS:findPAT done, mOffset=%lld", (long long)mOffset);
            mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                         /* isSeek */ );
            while (feedMore() == OK) {  //[qian]the end of file? parse all the TS packet of this file?
                //may be we should add the seek table when 2st parse whole file
#ifdef MTK_AOSP_ENHANCEMENT
                int64_t maxPTSfeedmoreDuration = systemTime() / 1000 - maxPTSStart;
                if (maxPTSfeedmoreDuration > kMaxPTSTimeOutUs) {
                    ALOGD("TimeOut find PTS, start time=%lld, maxPTSfeedmoreduration=%lld",
                          (long long)maxPTSStart, (long long)maxPTSfeedmoreDuration);
                    return UNKNOWN_ERROR;
                }

                if(((mOffset - mOffsetPAT) > (off64_t)(10000 * kFillPacketSize)) && (mParser->getMaxPTS() == 0)) {
                    ALOGD("stop feedmore (no PES) mOffset=%lld  mOffsetPAT=%lld",(long long)mOffset,(long long)mOffsetPAT);
                    break;
                 }
#endif
            }
            mDurationUs = mParser->getMaxPTS();
#ifdef MTK_AOSP_ENHANCEMENT
            End_OF_FILE=false;
            ALOGD("reset End_OF_FILE to false");
#endif
            if (mDurationUs){
                mFindingMaxPTS = false;
                break;
            }
        }
    }
    //clear data queue
    mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME /* isSeek */ );
    mParser->setDequeueState(true); //
    ALOGD("getMaxPTS->mDurationUs:%lld", (long long)mDurationUs);

    return OK;
    //ALOGD("getMaxPTS->mDurationUs:%lld", mDurationUs);
}
uint64_t MPEG2TSExtractor::getDurationUs() {
        return mDurationUs;
}
void MPEG2TSExtractor::seekTo(int64_t seekTimeUs) {
    Mutex::Autolock autoLock(mLock);

    ALOGE("seekTo:mDurationMs =%lld,seekTimeMs= %lld, mOffset:%lld",
          (long long)(mDurationUs / 1000), (long long)(seekTimeUs / 1000), (long long)mOffset);
    if (seekTimeUs == 0) {
        mOffset = 0;
        mSeeking = false;
        // clear MaxPTS
        mParser->setDequeueState(false);
        mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                     /* isSeek */ );
        // clear buffer queue
        mParser->setDequeueState(true);
        mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                     /* isSeek */ );
    } else if ((mDurationUs - seekTimeUs) < 10000)  //seek to end
    {
        mOffset = mFileSize;
        mSeeking = false;
        // set ATSParser MaxTimeUs to mDurationUs
        mParser->setDequeueState(false);
        sp<AMessage> maxTimeUsMsg = new AMessage;
        maxTimeUsMsg->setInt64("MaxtimeUs", mDurationUs);
        mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME,
                                     maxTimeUsMsg);
        // clear buffer queue
        mParser->setDequeueState(true);
        mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                     /* isSeek */ );

    } else {
        mParser->signalDiscontinuity(ATSParser::DISCONTINUITY_TIME
                                     /* isSeek */ );

        //[qian] firstly find the rough offset by packet size
        //I thinks we should use the
        //mSeekingOffset=(off64_t)((seekTimeUs*mFileSize/mDuration)/kTSPacketSize)* kTSPacketSize;
        mSeekingOffset = mOffset;

        mSeekTimeUs = seekTimeUs;
        mMinOffset = 0;
        mMaxOffset = mFileSize;
        mMaxcount = 0;
        mParser->setDequeueState(false);    //[qian] will start search mode, not read data mode
        mSeeking = true;
    }
    return;

}

#endif
}  // namespace android
