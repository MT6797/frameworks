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

#include "RegisterExtensions.h"
//[FIXME], remove from mediaserver
#ifdef MTK_CAM_MMSDK_SUPPORT
#include <utils/RefBase.h>
using namespace android;

//
#include <stdlib.h>
//
#include <hardware/camera.h>
#include <system/camera.h>
//
#include <Log.h>
#include <common.h>
//
#include <v1/camutils/CamFormatTransform.h>
//
#include <v1/camutils/CamProfile.h>
//
#include <v1/camutils/CamMisc.h>
#include <v1/camutils/CamInfo.h>
#include <v1/camutils/CamFormat.h>
#include <v1/camutils/CamProperty.h>
//
#include <v1/camutils/IBuffer.h>
#include <v1/camutils/ICameraBuffer.h>
#include <v1/camutils/IImgBufQueue.h>
#include <v1/camutils/ImgBufQueue.h>
#include <Format.h>

using namespace MtkCamUtils;
#include <v1/IParamsManager.h>
#include <v1/ICamClient.h>

#include <v1/sdkClient/IGestureClient.h>
#include "MMSdkService.h"
#endif

void registerExtensions()
{
//
#ifdef MTK_CAM_MMSDK_SUPPORT
    NSMMSdk::MMSdkService::instantiate();
#endif
}
