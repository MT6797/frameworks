/*
**
** Copyright (C) 2008, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "Camera"
#include <utils/Log.h>
#include <utils/String16.h>

#include <camera/Camera.h>
#include <camera/ICameraService.h>
#include <camera/ICamera.h>
#include <camera/IMetadataCallbacks.h>

namespace android {

status_t Camera::setMetadataCallback(sp<IMetadataCallbacks>& cb)
{
    ALOGV("Camera::setMetadataCallback");
    sp <ICamera> c = mCamera;
    if (c == 0) {
        ALOGE("Camera::setMetadataCallback mCamera=NULL");
        return NO_INIT;
    }
    if (cb == NULL) {
        ALOGE("Camera::setMetadataCallback cb=NULL");
        return NO_INIT;
    }
    status_t res = OK;
    res = c->setMetadataCallback(cb);

    return res;
}

//!++
#if 1   // defined(MTK_CAMERA_BSP_SUPPORT)

status_t
Camera::
getProperty(String8 const& key, String8& value)
{
    const sp<ICameraService>& cs = getCameraService();
    if (cs == 0) return UNKNOWN_ERROR;
    return cs->getProperty(key, value);
}


status_t
Camera::
setProperty(String8 const& key, String8 const& value)
{
    const sp<ICameraService>& cs = getCameraService();
    if (cs == 0) return UNKNOWN_ERROR;
    return cs->setProperty(key, value);
}

#endif
//!--

}; // namespace android
