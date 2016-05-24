#ifndef ANDROID_RESYNC_H
#define ANDROID_RESYNC_H

#include <stddef.h>

#include <utils/Mutex.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>

namespace android {

class SurfaceFlinger;

struct VSyncSourceData{
public:
    VSyncSourceData() {};
    VSyncSourceData(nsecs_t offset,
                    VSyncSource* vss,
                    DispSync::Callback* cb,
                    int32_t sc,
                    bool delay);
    VSyncSource* mVss;
    DispSync::Callback* mCb;
    nsecs_t mOffset;
    nsecs_t mPendingOffset;
    nsecs_t mLastEventTime;
    bool mDelayChange;
    bool mIsPendingOffset;
    int32_t mSkip;
    int32_t mPendingSkip;
    int32_t mSkipCount;
};

class Resync : public LightRefBase<Resync>
{
public:
    Resync(SurfaceFlinger* flinger, int dpy, nsecs_t offset);
    ~Resync();

    void updateModelLocked(nsecs_t period, nsecs_t phase);

    void updateSyncTimeLocked(int num, nsecs_t sync);

    status_t registerEventListener(nsecs_t offset,
                                   VSyncSource* vss,
                                   DispSync::Callback* cb,
                                   bool delay);

    status_t addEventListener(void* ptr);

    status_t removeEventListener(void* ptr);

    status_t adjustVsyncPeriod(int32_t fps);

    status_t fireCallbackInvocations(void* ptr);

    status_t adjustVsyncOffset(nsecs_t offset);

    void dump(String8& result);

    nsecs_t getVsyncOffset() const;

private:
    status_t checkVsyncAccuracy(void* ptr);
    void calculateEventDelayTime(nsecs_t timestamp);
    int32_t isMultipleOfFps(int32_t fps);

    nsecs_t mPeriod;
    nsecs_t mPhase;

    Vector<struct VSyncSourceData> mVsyncSourceList;

    mutable Mutex mMutexVssd;
    mutable Mutex mMutexState;
    bool mHasPendingOffset;
    int32_t mSkipCount;
    bool mOffsetChange;
    nsecs_t mOffset;
    int32_t mFps;

    void* mGed;
};

}

#endif // ANDROID_DISPSYNC_H
