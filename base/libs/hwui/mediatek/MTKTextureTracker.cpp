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

#include "MTKTextureTracker.h"

#include "MTKDebug.h"

#include <GLES3/gl3.h>
#include <utils/String8.h>
#include <utils/Trace.h>

namespace android {

using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(TextureTracker);

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// TextureTracker
///////////////////////////////////////////////////////////////////////////////

#define TTLOGD(...) MLOGD(debug_texture_tracker, __VA_ARGS__)

TextureTracker::TextureTracker() {
    TTLOGD("[TT] TextureTracker +");
}

TextureTracker::~TextureTracker() {
    TTLOGD("[TT] TextureTracker -");
}

void TextureTracker::startMark(const char* name) {
    Mutex::Autolock _l(mLock);
    mViews.push(String8(name));
}

void TextureTracker::endMark() {
    Mutex::Autolock _l(mLock);
    mViews.pop();
}

void TextureTracker::add(int textureId, int w, int h, int format, int type, const char* purpose, const char* comment) {
    Mutex::Autolock _l(mLock);

    // Get texture id for TextureCache.uploadToTexture case.
    if (textureId == -1) {
        if (g_HWUI_debug_texture_tracker) {
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &textureId);
        }
    }

    if (mViews.size() == 0) {
        ALOGE("[TT] add error %s %d %d %d 0x%x 0x%x %s",
            comment ? comment : "", textureId, w, h, format, type, purpose);
        return;
    }
    TextureEntry entry(mViews.top().string(), textureId, w, h, format, type, purpose);
    mMemoryList.add(entry);

    if (comment != nullptr) {
        TTLOGD("[TT] %s %s %d %d %d 0x%x 0x%x => %d %s",
            comment, entry.mName.string(), textureId, w, h, format, type, entry.mMemory, purpose);
    }
}

void TextureTracker::add(const char* name, int textureId,
    int w, int h, int format, int type, const char* purpose, const char* comment) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(name, textureId, w, h, format, type, purpose);
    mMemoryList.add(entry);

    if (comment != nullptr) {
        TTLOGD("[TT] %s %s %d %d %d 0x%x 0x%x => %d %s",
            comment, name, textureId, w, h, format, type, entry.mMemory, purpose);
    }
}

void TextureTracker::remove(int textureId, const char* comment) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(textureId);
    ssize_t index = mMemoryList.indexOf(entry);

    if (index >= 0) {
        entry = mMemoryList.itemAt(index);
        mMemoryList.removeAt(index);

        TTLOGD("[TT] %s %s %d", comment, entry.mName.string(), textureId);
    } else {
        TTLOGD("[TT] %s already %d", comment, textureId);
    }
}

void TextureTracker::update(int textureId, bool ghost, const char* name) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(textureId);
    ssize_t index = mMemoryList.indexOf(entry);

    if (index >= 0) {
        TextureEntry& item = mMemoryList.editItemAt(index);
        TTLOGD("[TT] update before %s %d %d %d %d %d\n", item.mName.string(), item.mId, item.mWidth, item.mHeight,
                        item.mMemory, item.mGhost);

        item.mGhost = ghost;

        if (name == nullptr) {
            if (!ghost) {
                item.mName = mViews.top();
            }
        } else {
            item.mName = String8(name);
        }

        entry = mMemoryList.itemAt(index);
        TTLOGD("[TT] update after %s %d %d %d %d %d\n", entry.mName.string(), entry.mId, entry.mWidth, entry.mHeight,
                    entry.mMemory, entry.mGhost);
    } else {
        TTLOGD("[TT] update not found %d", textureId);
    }
}

int TextureTracker::estimateMemory(int w, int h, int format, int type) {
    int bytesPerPixel = 0;

    switch (type) {
        case GL_UNSIGNED_BYTE:
            switch (format) {
                case GL_RGBA:
                    bytesPerPixel = 4;
                    break;
                case GL_RGB:
                    bytesPerPixel = 3;
                    break;
                case GL_LUMINANCE_ALPHA:
                    bytesPerPixel = 2;
                    break;
                case GL_ALPHA:
                case GL_LUMINANCE:
                    bytesPerPixel = 1;
                    break;
                default:
                    ALOGE("[TT] estimateMemory Error!! type:0x%x, format:0x%x", type, format);
                    break;
            }
            break;
        case GL_UNSIGNED_SHORT_4_4_4_4: // GL_RGBA format
        case GL_UNSIGNED_SHORT_5_5_5_1: // GL_RGBA format
        case GL_UNSIGNED_SHORT_5_6_5:   // GL_RGB
            bytesPerPixel = 2;
            break;
        case GL_FLOAT:
            switch (format) {
                case GL_RED:
                    bytesPerPixel = 2;
                    break;
                case GL_RGBA:
                    bytesPerPixel = 8;
                    break;
                default:
                    ALOGE("[TT] estimateMemory Error!! type:0x%x, format:0x%x", type, format);
                    break;
            }
            break;
        default:
            ALOGE("[TT] estimateMemory Error!! type:0x%x, format:0x%x", type, format);
            break;
    }

    return w * h * bytesPerPixel;
}

#define GL_CASE(target, x) case x: target = #x; break;
void TextureTracker::dumpMemoryUsage(String8& log) {
    log.appendFormat("\nTextureTracker:\n");

    int sum = 0;

    SortedList<Sum> list;
    size_t count = mMemoryList.size();
    for (size_t i = 0; i < count; i++) {
        Sum current(mMemoryList.itemAt(i));
        ssize_t index = list.indexOf(current);
        if (index < 0) {
            list.add(current);
        } else {
            Sum& item = list.editItemAt(index);
            item.mSum += mMemoryList.itemAt(i).mMemory;
            item.mItems.add(mMemoryList.itemAt(i));
        }
    }

    Vector<Sum> result;
    result.add(list.itemAt(0));
    size_t sortSize = list.size();
    for (size_t i = 1; i < sortSize; i++) {
        Sum entry = list.itemAt(i);
        size_t index = result.size();
        size_t size = result.size();
        for (size_t j = 0; j < size; j++) {
            Sum e = result.itemAt(j);
            if (entry.mSum > e.mSum) {
                index = j;
                break;
            }
       }
       result.insertAt(entry, index);
    }

    for (size_t i = 0; i < sortSize; i++) {
        const Sum& current = result.itemAt(i);
        String8 tmpString;
        size_t itemCount = current.mItems.size();
        for (size_t j = 0; j < itemCount; j++) {
            const TextureEntry& entry = current.mItems.itemAt(j);

            const char* format = "";
            const char* type = "";

            switch (entry.mFormat) {
                GL_CASE(format, GL_RGBA);
                GL_CASE(format, GL_RGB);
                GL_CASE(format, GL_ALPHA);
                GL_CASE(format, GL_LUMINANCE);
                GL_CASE(format, GL_LUMINANCE_ALPHA);
                default:
                    break;
            }

            switch (entry.mType) {
                GL_CASE(type, GL_UNSIGNED_BYTE);
                GL_CASE(type, GL_UNSIGNED_SHORT_4_4_4_4);
                GL_CASE(type, GL_UNSIGNED_SHORT_5_5_5_1);
                GL_CASE(type, GL_UNSIGNED_SHORT_5_6_5);
                GL_CASE(type, GL_FLOAT);
                default:
                    break;
            }

            tmpString.appendFormat("        (%d, %d) (%s, %s) %d <%s> %s\n",
                entry.mWidth, entry.mHeight, format, type, entry.mMemory,
                entry.mPurpose, entry.mGhost ? "g" : "");
        }

        sum += current.mSum;
        log.appendFormat("%s: %d bytes, %.2f KB, %.2f MB\n",
            current.mName, current.mSum, current.mSum / 1024.0f, current.mSum / 1048576.0f);
        log.append(tmpString);
        log.append("\n");
    }

    log.appendFormat("\nTotal monitored:\n  %d bytes, %.2f KB, %.2f MB\n", sum, sum / 1024.0f, sum / 1048576.0f);
}

}; // namespace uirenderer
}; // namespace android
