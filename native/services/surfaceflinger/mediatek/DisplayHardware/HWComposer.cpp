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
#define MTK_LOG_ENABLE 1
#include <inttypes.h>

#include <hardware/hardware.h>
#include <hardware/hwcomposer.h>

#include <cutils/log.h>

#include <cutils/properties.h>

#include "DisplayHardware/HWComposer.h"

#include "../../SurfaceFlinger.h"

namespace android {

// ---------------------------------------------------------------------------

void HWComposer::VSyncThread::updatePeriod(String8 &result) {
    const size_t SIZE = 4096;
    char buffer[SIZE];
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.sw_vsync_fps", value, "0");
    int fps = atoi(value);
    if (fps > 0) {
        mRefreshPeriod = nsecs_t(1e9 / fps);
        ALOGD("[VSYNC] set sw vsync fps(%d), period(%" PRId64 ")", fps, mRefreshPeriod);
    }
    snprintf(buffer, sizeof(buffer), "    debug.sf.sw_vsync_fps (mRefreshPeriod): %d (%" PRIu64 ")\n", fps, mRefreshPeriod);
    result.append(buffer);
}

uint8_t HWComposer::getSubType(int disp) const {
    return mDisplayData[disp].subtype;
}

status_t HWComposer::setDisplayOrientation(int32_t id, uint32_t orientation) {
    if (id < HWC_DISPLAY_PRIMARY || id >= int32_t(mNumDisplays) ||
            !mAllocatedDisplayIDs.hasBit(id)) {
        return BAD_INDEX;
    }
    mDisplayData[id].orientation = orientation;
    return NO_ERROR;
}

void HWComposer::updateVSynThreadPeriod(String8 &result) {
    if (mVSyncThread != NULL) mVSyncThread->updatePeriod(result);
}

void HWComposer::skipDisplay(int32_t id) {
    if (id >= HWC_DISPLAY_PRIMARY && id < int32_t(mNumDisplays) &&
            mAllocatedDisplayIDs.hasBit(id)) {
        if (CC_LIKELY(mDisplayData[id].list != NULL)) {
            mDisplayData[id].list->flags |= HWC_SKIP_DISPLAY;
        }
    }
}

bool HWComposer::hasS3DLayer(int32_t id) const {
    return (!mHwc || uint32_t(id) > 31 || !mAllocatedDisplayIDs.hasBit(id)) ? false : mDisplayData[id].hasS3DLayer;
}

int HWComposer::s3dType(int32_t id) const {
    return (!mHwc || uint32_t(id) > 31 || !mAllocatedDisplayIDs.hasBit(id)) ? false : mDisplayData[id].s3dType;
}
// ---------------------------------------------------------------------------
}; // namespace android
