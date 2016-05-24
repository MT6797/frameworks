#define LOG_TAG "BufferQueueMonitor"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <cutils/log.h>
#include <cutils/process_name.h>
#include <gui/BufferQueueCore.h>
#include <gui/mediatek/BufferQueueMonitor.h>
#include <IGuiExtService.h>

//#define BQM_LOGV(x, ...) ALOGV("[BufferQueueMonitor] " x, ##__VA_ARGS__)
//#define BQM_LOGD(x, ...) ALOGD("[BufferQueueMonitor] " x, ##__VA_ARGS__)
#define BQM_LOGI(x, ...) ALOGI("[BufferQueueMonitor] " x, ##__VA_ARGS__)
//#define BQM_LOGW(x, ...) ALOGW("[BufferQueueMonitor] " x, ##__VA_ARGS__)
//#define BQM_LOGE(x, ...) ALOGE("[BufferQueueMonitor] " x, ##__VA_ARGS__)

namespace android {

// -----------------------------------------------------------------------------
// BufferQueueMonitor part
// -----------------------------------------------------------------------------
ANDROID_SINGLETON_STATIC_INSTANCE(BufferQueueMonitor)

BufferQueueMonitor::BufferQueueMonitor()
    : mIsRegistered(false)
{
    getProcessName();
}

BufferQueueMonitor::~BufferQueueMonitor()
{
    Mutex::Autolock _l(mMutex);

    if (mIsRegistered)
    {
        unregDump(String8::format("BQM-[%d:%s]", getpid(), mProcessName.string()));
        mIsRegistered = false;
    }
}

status_t BufferQueueMonitor::monitor(wp<BufferQueueCore> pBq)
{
    Mutex::Autolock _l(mMutex);

    mBqList.add(pBq, 0);
    if (!mIsRegistered)
    {
        mBqDumpTunnel = new BufferQueueDumpTunnel(this);
        if (regDump(mBqDumpTunnel, String8::format("BQM-[%d:%s]", getpid(), mProcessName.string())))
        {
            mIsRegistered = true;
        }
    }
    return NO_ERROR;
}

status_t BufferQueueMonitor::unmonitor(wp<BufferQueueCore> pBq)
{
    Mutex::Autolock _l(mMutex);

    mBqList.removeItem(pBq);
    return NO_ERROR;
}

status_t BufferQueueMonitor::dump(String8& result, const char* /*prefix*/)
{
    size_t listSz;
    Vector<sp<BufferQueueCore>> bqCores;

    // add strong ref to another list
    // avoid ~BufferQueueCore() happens in the mutex scope, which causes deadlock
    {
        Mutex::Autolock _l(mMutex);
        listSz = mBqList.size();

        for (size_t i = 0; i < listSz; i++)
        {
            wp<BufferQueueCore> pBq = mBqList.keyAt(i);
            sp<BufferQueueCore> bq = pBq.promote();
            if (bq != NULL)
            {
                bqCores.add(bq);
            }
            else
            {
                BQM_LOGI("kickDump() failed because BufferQueue(%p) is dead", pBq.unsafe_get());
            }
        }
    }

    result.appendFormat("\t  [%p]    BufferQueueCnt : %zu\n", this, listSz);
    result.append("\t  -----------------------\n");

    listSz = bqCores.size();
    for (size_t i = 0; i < listSz; i++)
    {
        sp<BufferQueueCore> bq = bqCores.itemAt(i);
        if (bq != NULL)
        {
            result.appendFormat("           %zu)\n",i+1);
            bq->dump(result, "            ");
        }
    }

    result.append("\t  -----------------------\n");
    return NO_ERROR;
}

status_t BufferQueueMonitor::getProcessName()
{
    int pid = getpid();
    FILE *fp = fopen(String8::format("/proc/%d/cmdline", pid), "r");
    if (NULL != fp)
    {
        const size_t size = 64;
        char proc_name[size];
        fgets(proc_name, size, fp);
        fclose(fp);
        mProcessName = proc_name;
    }
    else
    {
        mProcessName = "unknownProcess";
    }
    return NO_ERROR;
}

// -----------------------------------------------------------------------------
// BufferQueueDumpTunnel part
// -----------------------------------------------------------------------------
BufferQueueDumpTunnel::BufferQueueDumpTunnel(BufferQueueMonitor* pMonitor)
    : mMonitor(pMonitor)
{
}

BufferQueueDumpTunnel::~BufferQueueDumpTunnel()
{
}

status_t BufferQueueDumpTunnel::kickDump(String8& result, const char* prefix)
{
    return mMonitor->dump(result, prefix);
}

}; // namespace android
