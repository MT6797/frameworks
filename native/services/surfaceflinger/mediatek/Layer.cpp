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

#include <cutils/properties.h>


#include <cutils/log.h>
#include <ui/gralloc_extra.h>
#include <ui/DisplayInfo.h>

#include "Layer.h"
#include <ui/GraphicBufferExtra.h>

namespace android {

void Layer::setBufferCount() {
    mSurfaceFlingerConsumer->setDefaultMaxBufferCount(mFlinger->sPropertiesState.mBqCount);
}

status_t Layer::adjustTexCoord(const sp<const DisplayDevice>& hw, Mesh::VertexArray<vec2> texCoords) const {

    if (!hasS3DBuffer()) {
        return NO_ERROR;
    }

    switch (hw->mS3DPhase) {
        case DisplayDevice::eComposingS3DSBSLeft: {
            // use bottom half
            texCoords[0].x = texCoords[0].x * 0.5;
            texCoords[1].x = texCoords[1].x * 0.5;
            texCoords[2].x = texCoords[2].x * 0.5;
            texCoords[3].x = texCoords[3].x * 0.5;
            break;
        }
        case DisplayDevice::eComposingS3DSBSRight: {
            // use top half
            texCoords[0].x = 0.5 + texCoords[0].x * 0.5;
            texCoords[1].x = 0.5 + texCoords[1].x * 0.5;
            texCoords[2].x = 0.5 + texCoords[2].x * 0.5;
            texCoords[3].x = 0.5 + texCoords[3].x * 0.5;
            break;
        }
        case DisplayDevice::eComposingS3DTABTop: {
            // use bottom half
            texCoords[0].y = texCoords[0].y * 0.5;
            texCoords[1].y = texCoords[1].y * 0.5;
            texCoords[2].y = texCoords[2].y * 0.5;
            texCoords[3].y = texCoords[3].y * 0.5;
            break;
        }
        case DisplayDevice::eComposingS3DTABBottom: {
            // use top half
            texCoords[0].y = 0.5 + texCoords[0].y * 0.5;
            texCoords[1].y = 0.5 + texCoords[1].y * 0.5;
            texCoords[2].y = 0.5 + texCoords[2].y * 0.5;
            texCoords[3].y = 0.5 + texCoords[3].y * 0.5;
            break;
        }
        default:
            break;
    }
    return NO_ERROR;
}

bool Layer::hasS3DBuffer() const {

    if (mActiveBuffer == NULL) {
        return false;
    }

    ANativeWindowBuffer* nativeBuffer = mActiveBuffer->getNativeBuffer();
    buffer_handle_t handle = nativeBuffer->handle;
    gralloc_extra_ion_sf_info_t extInfo;

    int err = 0;
    err |= gralloc_extra_query(handle, GRALLOC_EXTRA_GET_IOCTL_ION_SF_INFO, &extInfo);

    int bit_S3D = (extInfo.status & GRALLOC_EXTRA_MASK_S3D);

    return (bit_S3D == GRALLOC_EXTRA_BIT_S3D_SBS) || (bit_S3D == GRALLOC_EXTRA_BIT_S3D_TAB);
}
};
