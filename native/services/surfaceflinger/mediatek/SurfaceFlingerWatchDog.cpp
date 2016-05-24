/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define MTK_LOG_ENABLE 1
#include <inttypes.h>

#include <sys/stat.h>
#include <selinux/android.h>
#include <cutils/log.h>
#include <cutils/properties.h>
#include "cutils/debugger.h"

#include "SurfaceFlingerWatchDog.h"

#ifdef HAVE_AEE_FEATURE
#include "aee.h"
#endif

namespace android {

ANDROID_SINGLETON_STATIC_INSTANCE(SFWatchDog);

#define SW_WATCHDOG_TIMER       1000
#define SW_WATCHDOG_INIT_THRESHOLD   800L
#define SW_WATCHDOG_RTTCOUNT    10
#define RTT_FOLDER_PATH         "/data/anr/SF_RTT/"
#define RTT_FILE_NAME           "rtt_dump"
#define RTT_DUMP                (RTT_FOLDER_PATH RTT_FILE_NAME)

Mutex SFWatchDog::sLock(Mutex::PRIVATE);
sp<SFWatchDog> SFWatchDog::sInstance;

SFWatchDog::SFWatchDog() : Thread(false) {
    ALOGI("[%s]", __func__);
}

SFWatchDog::~SFWatchDog() {
    ALOGI("[%s]", __func__);

    mNodeList.clear();
}

void SFWatchDog::onFirstRef() {
    ALOGI("[%s]", __func__);
    mUpdateCount = 0;
    mFtraceDumped = false;
    run("SFWatchDog", PRIORITY_BACKGROUND);
}

bool SFWatchDog::createFolder(const char* path) {
    struct stat sb;
    if (stat(path, &sb) != 0) {
        if (mkdir(path, 0777) != 0) {
            ALOGE("mkdir(%s) failed: %s", path, strerror(errno));
            return false;
        }
        if (selinux_android_restorecon(path, 0) == -1) {
            ALOGE("restorecon failed(%s) failed", path);
            return false;
        } else {
            ALOGV("restorecon(%s)", path);
        }
    }
    return true;
}

status_t SFWatchDog::readyToRun() {
    ALOGI("[%s]", __func__);

    mThreshold = SW_WATCHDOG_INIT_THRESHOLD;
    mTimer = SW_WATCHDOG_TIMER;
    mShowLog = false;

    return NO_ERROR;
}

#ifdef HAVE_AEE_FEATURE
// AED Exported Functions
static int aee_ioctl_wdt_kick(int value)
{
    int ret = 0;
    int fd = open(AE_WDT_DEVICE_PATH, O_RDONLY);
    if (fd < 0) {
        ALOGD("[SF-WD] ERROR: open %s failed.", AE_WDT_DEVICE_PATH);
        return 1;
    } else {
        if (ioctl(fd, AEEIOCTL_WDT_KICK_POWERKEY, (int)value) != 0) {
            ALOGD("[SF-WD] ERROR(%s): aee wdt kick powerkey ioctl failed.", strerror(errno));
            close(fd);
            return 1;
        }
    }
    close(fd);
    return ret;
}

static int aee_ioctl_swt_set(nsecs_t time)
{
    int ret = 0;
    int fd = open(AE_WDT_DEVICE_PATH, O_RDONLY);
    if (fd < 0) {
        ALOGD("[SF-WD] ERROR: open %s failed.", AE_WDT_DEVICE_PATH);
        return 1;
    } else {
        if (ioctl(fd, AEEIOCTL_SET_SF_STATE, (long long)(&time)) != 0) {
            ALOGD("[SF-WD] ERROR(%s): aee swt set state ioctl failed.", strerror(errno));
            close(fd);
            return 1;
        }
    }
    close(fd);
    return ret;
}
#endif

sp<SFWatchDog> SFWatchDog::getInstance() {
    Mutex::Autolock _l(sLock);
    static sp<SFWatchDog> instance = sInstance;
    if (instance.get() == 0) {
        instance = new SFWatchDog();
        sInstance = instance;
    }
    return instance;
}

bool SFWatchDog::threadLoop() {
    ALOGV("[%s]", __func__);

    {
        Mutex::Autolock _l(mScreenLock);
    }

    nsecs_t stopTime = 1;
    if (isSFThreadHang(&stopTime)) {
        char cmds[256];
        static uint32_t rtt_ct = SW_WATCHDOG_RTTCOUNT;
        if (rtt_ct > 0) {
            rtt_ct --;
        } else {
            ALOGD("[SF-WD] swap rtt dump file");

            // swap rtt dump file
            snprintf(cmds, sizeof(cmds), "mv %s.txt %s_1.txt", RTT_DUMP, RTT_DUMP);
            system(cmds);

            rtt_ct = SW_WATCHDOG_RTTCOUNT;
        }

        // create rtt folder
        createFolder(RTT_FOLDER_PATH);

        // append SurfaceFlinger rtt information to rtt file
        char filename[100];
        snprintf(filename, sizeof(filename), "%s.txt", RTT_DUMP);
        int fd = open(filename, O_CREAT | O_WRONLY | O_NOFOLLOW, 0666);  /* -rw-rw-rw- */
        if (fd < 0) {
            ALOGE("Can't open %s: %s\n", filename, strerror(errno));
        } else {
            if (lseek(fd, 0, SEEK_END) < 0) {
                fprintf(stderr, "lseek: %s\n", strerror(errno));
            } else {
                dump_backtrace_to_file(getpid(), fd);
                const size_t dc = mVDSList.size();
                int32_t cPid = -1;
                sp<ANativeWindow> nativeWindow;
                String8 info;
                for(size_t i=0 ; i < dc ; i++){
                    nativeWindow =  mVDSList.keyAt(i);
                    info = mVDSList.valueAt(i);
                    nativeWindow->query(nativeWindow.get(), NATIVE_WINDOW_CONSUMER_PID, &cPid);
                    ALOGI("Try to get Consumer PID: %d", cPid);
                    if(cPid > 0){
                        write(fd, info.string(), info.size());
                        dump_backtrace_to_file(cPid, fd);
                    } else {
                        ALOGW("Can't get consmuer pid of Virtual Display !!");
                    }
                }

            }
            close(fd);
            ALOGD("[SF-WD] dump rtt file: %s.txt", RTT_DUMP);
            ALOGW("[SF-WD] ============================================");
        }

        stopTime = ns2ms(stopTime);
    }

    getProperty();

#ifdef HAVE_AEE_FEATURE
    aee_ioctl_swt_set(stopTime);
#endif

/*
    if (stopTime < 0 || stopTime >= 2147483247) {
        volatile nsecs_t tmpStopTime = stopTime;

        String8 msg = String8::format("[SF-WD] tmpStopTime=(%" PRId64 ", %" PRId64 ")",
                                    tmpStopTime, stopTime);

        ALOGW(msg.string());

        if (!mFtraceDumped) {
#ifdef HAVE_AEE_FEATURE
            aee_system_warning(LOG_TAG, NULL, DB_OPT_FTRACE | DB_OPT_DUMMY_DUMP, msg.string());
#endif
            mFtraceDumped = true;
        }
    } else {
        mFtraceDumped = false;
    }
*/

    if (mUpdateCount) {
        if (mShowLog)
            ALOGD("[SF-WD] mUpdateCount: %d", mUpdateCount);
#ifdef HAVE_AEE_FEATURE
        aee_ioctl_wdt_kick(WDT_SETBY_SF);
#endif
        mUpdateCount = 0;
    }
#ifdef HAVE_AEE_FEATURE
    //else {
    //  ALOGV("[SF-WD] mUpdateCount not update!!!!!: %d", mUpdateCount);
    //  aee_ioctl_wdt_kick(WDT_SETBY_SF_NEED_NOT_UPDATE);
    //}
#endif
    usleep(mTimer * 1000);
    return true;
}

uint32_t SFWatchDog::registerNodeName(const char* name) {
    Mutex::Autolock _l(mLock);

    uint32_t index = 0;

    for (uint32_t i = 0; i < mNodeList.size(); i++) {
        if (name == mNodeList[i]->mName) {
            ALOGI("register an already registered name: %s (%s)", name, mNodeList[i]->mName.string());
            return i;
        }
    }

    index = mNodeList.size();
    sp<NodeElement> node = new NodeElement();
    node->mName = name;
    node->mStartTransactionTime = 0;
    mNodeList.add(node);

    ALOGI("[%s] name=%s, index=%d", __func__, name, index);

    return index;
}

ssize_t SFWatchDog::registerVirtualDisplay(const sp<ANativeWindow>& nativeWindow, const String8& info) {
    ssize_t size = 0;

    const ssize_t i = mVDSList.indexOfKey(nativeWindow);

    if (i > 0) {
        ALOGI("register an already registered VDS");
        return i;
    }
    mVDSList.add(nativeWindow, info);
    size = mVDSList.size();
    ALOGI("[%s] registered VDS:%p, size:%zd,", __func__, nativeWindow.get(), size);

    return size;
}

ssize_t SFWatchDog::unregisterVirtualDisplay(const sp<ANativeWindow>& nativeWindow) {
    ssize_t size = 0;

    const ssize_t i = mVDSList.indexOfKey(nativeWindow);
    if (i < 0) {
        ALOGI("unregister an already VDS");
        return i;
    }
    mVDSList.removeItem(nativeWindow);
    size = mVDSList.size();
    ALOGI("[%s] unregistered VDS:%p, size:%zd", __func__, nativeWindow.get(), size);

    return size;
}

bool SFWatchDog::isSFThreadHang(nsecs_t* pStopTime) {
    Mutex::Autolock _l(mLock);

    const nsecs_t now = systemTime();
    if (mShowLog) {
        ALOGI("[SF-WD] Threshold: %" PRId64 " ns NodeList size: %zu", mThreshold * 1000 * 1000, mNodeList.size());
        for (uint32_t i = 0; i < mNodeList.size(); i++) {
            ALOGI("[SF-WD]   [%s] last transaction: %" PRId64 ", now: %" PRId64 ".",
                    mNodeList[i]->mName.string(),
                    mNodeList[i]->mStartTransactionTime, now);
        }
    }

    *pStopTime = INT64_MAX;
    for (uint32_t i = 0; i < mNodeList.size(); i++) {
        if ((mNodeList[i]->mStartTransactionTime != 0 || mThreshold == 0) &&
            mThreshold * 1000 * 1000 < now - mNodeList[i]->mStartTransactionTime) {
            // we could set mThreshold as zero for debugging
            *pStopTime = *pStopTime < now - mNodeList[i]->mStartTransactionTime ?
                *pStopTime : now - mNodeList[i]->mStartTransactionTime;
        }
    }

    if (*pStopTime != INT64_MAX) { // maybe hang
        ALOGW("[SF-WD] ============================================");
        ALOGW("[SF-WD] detect SF maybe hang, Threshold: %" PRId64 " ns StopTime: %" PRId64 " ns, List(size: %zu):", mThreshold * 1000 * 1000,
                *pStopTime, mNodeList.size());
        for (uint32_t i = 0; i < mNodeList.size(); i++) {
            if (mNodeList[i]->mStartTransactionTime == 0) {
                ALOGI("[SF-WD]   [%s] wait event", mNodeList[i]->mName.string());
            } else {
                ALOGW("[SF-WD]   [%s] stopTime= %" PRId64 "ns", mNodeList[i]->mName.string(), now - mNodeList[i]->mStartTransactionTime);
            }
        }

        if (mThreshold == 0) {
            *pStopTime = 1;
        }
        return true;
    } else {
        *pStopTime = 1;
        return false;
    }
}

void SFWatchDog::markStartTransactionTime(uint32_t index) {
    Mutex::Autolock _l(mLock);

    if (index >= mNodeList.size()) {
        ALOGE("[unmarkStartTransactionTime] index=%d > Node list size=%zu", index, mNodeList.size());
        return;
    }

    mNodeList[index]->mStartTransactionTime = systemTime();
    mUpdateCount ++;
    if (mShowLog)
        ALOGV("[%s] name=%s, index=%d, time = %" PRId64 "", __func__, mNodeList[index]->mName.string(), index, mNodeList[index]->mStartTransactionTime);
}

void SFWatchDog::unmarkStartTransactionTime(uint32_t index) {
    Mutex::Autolock _l(mLock);

    if (index >= mNodeList.size()) {
        ALOGE("[unmarkStartTransactionTime] index=%d > Node list size=%zu", index, mNodeList.size());
        return;
    }

    mNodeList[index]->mStartTransactionTime = 0;

    if (mShowLog)
        ALOGV("[%s] name=%s, index=%d, time = %" PRId64 "", __func__, mNodeList[index]->mName.string(), index, mNodeList[index]->mStartTransactionTime);
}

void SFWatchDog::screenReleased() {
    mScreenLock.lock();

    if (mShowLog)
        ALOGD("[SF-WD] screen release");
}

void SFWatchDog::screenAcquired() {
    mScreenLock.unlock();

    if (mShowLog)
        ALOGD("[SF-WD] acquire");
}

void SFWatchDog::getProperty() {
    char value[PROPERTY_VALUE_MAX];

    if (property_get("debug.sf.wdthreshold", value, NULL) > 0) {
        nsecs_t threshold = static_cast<nsecs_t>(atoi(value));
        if (threshold != mThreshold) {
            ALOGD("SF watch dog change threshold from %" PRId64 " --> %" PRId64 ".", mThreshold, threshold);
            mThreshold = threshold;
        }
    }

    if (property_get("debug.sf.wdtimer", value, NULL) > 0) {
        int timer = atoi(value);
        if (timer != (int)mTimer) {
            ALOGD("SF watch dog change timer from %d --> %d.", mTimer, timer);
            mTimer = timer;
        }
    }

    property_get("debug.sf.wdlog", value, "0");
    mShowLog = atoi(value);
}

void SFWatchDog::setThreshold(const nsecs_t& time) {
    Mutex::Autolock _l(mLock);
    ALOGD("SF watch dog change threshold from %" PRId64 " --> %" PRId64 ".", mThreshold, time);
    mThreshold = time;
}

nsecs_t SFWatchDog::getThreshold() {
    Mutex::Autolock _l(mLock);
    return mThreshold;
}
}; // namespace android
