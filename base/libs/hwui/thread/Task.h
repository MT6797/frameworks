/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TASK_H
#define ANDROID_HWUI_TASK_H

#include <sys/resource.h>
#include <utils/RefBase.h>
#include <utils/Trace.h>

#include "Debug.h"
#include "Future.h"

namespace android {
namespace uirenderer {

#define TASK_LOGD(...) if (CC_UNLIKELY(g_HWUI_debug_hwuitask)) ALOGD(__VA_ARGS__);

class TaskBase: public RefBase {
public:
    TaskBase(): mTid(-1) { }
    virtual ~TaskBase() { }

    pid_t mTid;   /// M: tid for setting thread priority
};

template<typename T>
class Task: public TaskBase {
public:
    Task(): mFuture(new Future<T>()) { }
    virtual ~Task() { }

    T getResult() const {
        ScopedTrace tracer(ATRACE_TAG_VIEW, "waitForTask");

        /// M: increase hwuiTask priority to avoid blocking RenderThread
        int prev_pri = PRIORITY_FOREGROUND;
        if (mTid > 0) {
            prev_pri = getpriority(PRIO_PROCESS, mTid);
            TASK_LOGD("get original task priority of %d = %d", mTid, prev_pri);
            setpriority(PRIO_PROCESS, mTid, PRIORITY_URGENT_DISPLAY);
            TASK_LOGD("set task priority of %d to urgent display", mTid);
        }

        T ret = mFuture->get();

        /// M: rollback hwuiTask priority
        if (mTid > 0) {
            setpriority(PRIO_PROCESS, mTid, prev_pri);
            TASK_LOGD("set task priority of %d back to %d", mTid, prev_pri);
        }

        return ret;
    }

    void setResult(T result) {
        mFuture->produce(result);
    }

protected:
    const sp<Future<T> >& future() const {
        return mFuture;
    }

private:
    sp<Future<T> > mFuture;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TASK_H
