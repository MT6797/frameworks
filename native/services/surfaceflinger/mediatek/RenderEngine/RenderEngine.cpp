/*
 * Copyright 2013 The Android Open Source Project
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
#include <cutils/log.h>
#include <ui/Rect.h>
#include <ui/Region.h>

#include "RenderEngine/RenderEngine.h"
#include "RenderEngine/GLExtensions.h"
#include "RenderEngine/Mesh.h"

#include <gui/IGraphicBufferProducer.h>
#include <binder/IInterface.h>
#include <private/gui/LayerState.h>
#include "DisplayDevice.h"

#include <SkImageDecoder.h>
#include <SkBitmap.h>

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

uint32_t RenderEngine::createAndBindProtectImageTexLocked() {
    // create sec img texture if needed
    if (-1U == mProtectImageTexName) {
        SkBitmap bitmap;
        if (false == SkImageDecoder::DecodeFile(DRM_IMAGE_PATH, &bitmap)) {
            ALOGE("Failed to load DRM image");
            return -1U;
        }
        mProtectImageWidth = bitmap.width();
        mProtectImageHeight = bitmap.height();

        glGenTextures(1, &mProtectImageTexName);
        glBindTexture(GL_TEXTURE_2D, mProtectImageTexName);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, mProtectImageWidth, mProtectImageHeight, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, bitmap.getPixels());
    } else {
        glBindTexture(GL_TEXTURE_2D, mProtectImageTexName);
    }

    return mProtectImageTexName;
}

uint32_t RenderEngine::getAndClearProtectImageTexName() {
    Mutex::Autolock l(mProtectImageLock);

    GLuint texName = mProtectImageTexName;
    mProtectImageTexName = -1U;
    mProtectImageWidth = 0;
    mProtectImageHeight = 0;

    return texName;
}

void RenderEngine::drawDebugLine(
    const sp<const DisplayDevice>& hw, uint32_t color, uint32_t steps) const
{
    // geometry info
    float w = hw->getWidth();
    float h = hw->getHeight();

    // debug line size and pos
    float cnt = hw->getPageFlipCount() % steps;
    float size = h / steps;

    // flip y for GL coord
    h = (h - size) - (cnt * size);

    // get color for line
    float R = (uint8_t) color        / 255.0;
    float G = (uint8_t)(color >> 8 ) / 255.0;
    float B = (uint8_t)(color >> 16) / 255.0;
    float A = (uint8_t)(color >> 24) / 255.0;

    glEnable(GL_SCISSOR_TEST);
    glScissor(0, h, w, size);
    glClearColor(R, G, B, A);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
}

void RenderEngine::adjustScissorS3D(const sp<const DisplayDevice>& hw) {

    switch (hw->mS3DPhase) {
        case DisplayDevice::eComposingS3DSBSLeft:
            {
                const Rect& bounds(hw->getBounds());
                const Rect& scissor(hw->getScissor());
                if (scissor != bounds) {
                    const uint32_t height = hw->getHeight();
                    uint32_t tmp = scissor.getWidth() / 2;
                    setScissor((scissor.left / 2),
                                height - scissor.bottom,
                                (scissor.getWidth() - tmp),
                                scissor.getHeight());
                }
                break;
            }
        case DisplayDevice::eComposingS3DSBSRight:
            {
                const Rect& bounds(hw->getBounds());
                const Rect& scissor(hw->getScissor());
                if (scissor != bounds) {
                    const uint32_t height = hw->getHeight();
                    uint32_t tmp = scissor.getWidth() / 2;
                    setScissor((scissor.left / 2) + ((hw->getWidth() / 2)),
                                height - scissor.bottom,
                                (scissor.getWidth() - tmp),
                                scissor.getHeight());
                }
                break;
            }
        case DisplayDevice::eComposingS3DTABTop:
            {
                const Rect& bounds(hw->getBounds());
                const Rect& scissor(hw->getScissor());
                if (scissor != bounds) {
                    const uint32_t height = hw->getHeight();
                    uint32_t tmp = scissor.getHeight() / 2;
                    setScissor(scissor.left,
                                ((height - scissor.bottom) / 2),
                                scissor.getWidth(),
                                (scissor.getHeight() - tmp));
                }
                break;
            }
        case DisplayDevice::eComposingS3DTABBottom:
            {
                const Rect& bounds(hw->getBounds());
                const Rect& scissor(hw->getScissor());
                if (scissor != bounds) {
                    const uint32_t height = hw->getHeight();
                    uint32_t tmp = scissor.getHeight() / 2;
                    setScissor(scissor.left,
                            ((((height - scissor.bottom) / 2) + ((hw->getHeight()) / 2))),
                            scissor.getWidth(),
                            (scissor.getHeight() - tmp));
                }
                break;
            }
        default:
            break;
    }
}

void RenderEngine::adjustViewPortS3D(const sp<const DisplayDevice>& hw) {
    Rect b = hw->getBounds();
    uint32_t w = b.width();
    uint32_t h = b.height();

    switch (hw->mS3DPhase) {
        case DisplayDevice::eComposingS3DSBSLeft: {
            uint32_t tmp = w / 2;
            glViewport(0, 0, (w - tmp), h);
            break;
        }
        case DisplayDevice::eComposingS3DSBSRight: {
            uint32_t tmp = w / 2;
            glViewport((w / 2), 0, (w - tmp), h);
            break;
        }
        case DisplayDevice::eComposingS3DTABTop: {
            uint32_t tmp = h / 2;
            glViewport(0, 0, w, (h - tmp));
            break;
        }
        case DisplayDevice::eComposingS3DTABBottom: {
            uint32_t tmp = h / 2;
            glViewport(0, (h / 2), w, (h - tmp));
            break;
        }
        case DisplayDevice::eComposing2D:
        default: {
            glViewport(0, 0, w, h);
        }
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------
