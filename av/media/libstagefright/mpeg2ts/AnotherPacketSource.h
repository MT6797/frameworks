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

#ifndef ANOTHER_PACKET_SOURCE_H_

#define ANOTHER_PACKET_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaSource.h>
#include <utils/threads.h>
#include <utils/List.h>

#include "ATSParser.h"

namespace android {

struct ABuffer;

struct AnotherPacketSource : public MediaSource {
    AnotherPacketSource(const sp<MetaData> &meta);

    void setFormat(const sp<MetaData> &meta);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

#ifdef MTK_AOSP_ENHANCEMENT
    void clear(const bool bKeepFormat = false);
#else
    void clear();
#endif

    // Returns true if we have any packets including discontinuities
    bool hasBufferAvailable(status_t *finalResult);

    // Returns true if we have packets that's not discontinuities
    bool hasDataBufferAvailable(status_t *finalResult);

    // Returns the number of available buffers. finalResult is always OK
    // if this method returns non-0, or the final result if it returns 0.
    size_t getAvailableBufferCount(status_t *finalResult);

    // Returns the difference between the last and the first queued
    // presentation timestamps since the last discontinuity (if any).
    int64_t getBufferedDurationUs(status_t *finalResult);
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t getEstimatedDurationUs();
    int64_t getBufferedDurationUs_l(status_t *finalResult);
    status_t CheckFormatChangeBuffer(void);
#endif
    status_t nextBufferTime(int64_t *timeUs);

    void queueAccessUnit(const sp<ABuffer> &buffer);

    void queueDiscontinuity(
            ATSParser::DiscontinuityType type,
            const sp<AMessage> &extra,
            bool discard);

    void signalEOS(status_t result);

    status_t dequeueAccessUnit(sp<ABuffer> *buffer);
    void requeueAccessUnit(const sp<ABuffer> &buffer);

    bool isFinished(int64_t duration) const;

    void enable(bool enable);

    sp<AMessage> getLatestEnqueuedMeta();
    sp<AMessage> getLatestDequeuedMeta();
    sp<AMessage> getMetaAfterLastDequeued(int64_t delayUs);

    void trimBuffersAfterMeta(const sp<AMessage> &meta);
    sp<AMessage> trimBuffersBeforeMeta(const sp<AMessage> &meta);

protected:
    virtual ~AnotherPacketSource();

private:

    struct DiscontinuitySegment {
        int64_t mMaxDequeTimeUs, mMaxEnqueTimeUs;
        DiscontinuitySegment()
            : mMaxDequeTimeUs(-1),
              mMaxEnqueTimeUs(-1) {
        };

        void clear() {
            mMaxDequeTimeUs = mMaxEnqueTimeUs = -1;
        }
    };

    // Discontinuity segments are consecutive access units between
    // discontinuity markers. There should always be at least _ONE_
    // discontinuity segment, hence the various CHECKs in
    // AnotherPacketSource.cpp for non-empty()-ness.
    List<DiscontinuitySegment> mDiscontinuitySegments;

    Mutex mLock;
    Condition mCondition;

    bool mIsAudio;
    bool mIsVideo;
    bool mEnabled;
    sp<MetaData> mFormat;
    int64_t mLastQueuedTimeUs;
    List<sp<ABuffer> > mBuffers;
    status_t mEOSResult;
    sp<AMessage> mLatestEnqueuedMeta;
#ifdef MTK_AOSP_ENHANCEMENT
    size_t  mQueuedDiscontinuityCount;
#endif
    sp<AMessage> mLatestDequeuedMeta;

    bool wasFormatChange(int32_t discontinuityType) const;

    DISALLOW_EVIL_CONSTRUCTORS(AnotherPacketSource);
#ifdef MTK_AOSP_ENHANCEMENT
public:
    status_t isEOS();
    void setBufQueSize(size_t iBufQueSize) { m_BufQueSize = iBufQueSize; }
    void setTargetTime(size_t iTargetTime) { m_TargetTime = iTargetTime; }
    bool getNSN(int32_t * uiNextSeqNum);
    size_t getFreeBufSpace();
    void setScanForIDR(bool enable);
    unsigned getSourcePID();
    void setSourcePID(unsigned uStrmPid);

private:
    bool mIsEOS;

    //for bitrate-adaptation
    size_t m_BufQueSize;        //Whole Buffer queue size
    size_t m_TargetTime;        // target protected time of buffer queue duration for interrupt-free playback
    int32_t m_uiNextAduSeqNum;

    // wait IDR for 264
    bool mScanForIDR;
    bool mIsAVC;
    bool mNeedScanForIDR;
    unsigned mStrmSourcePID;
#endif
};


}  // namespace android

#endif  // ANOTHER_PACKET_SOURCE_H_
