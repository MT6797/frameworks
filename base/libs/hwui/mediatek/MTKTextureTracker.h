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

#ifndef MTK_HWUI_TEXTURETRACKER_H
#define MTK_HWUI_TEXTURETRACKER_H

#include <utils/Singleton.h>
#include "utils/SortedList.h"
#include <utils/Vector.h>
#include <utils/String8.h>

namespace android {

class String8;

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// TextureTracker
///////////////////////////////////////////////////////////////////////////////

class TextureTracker: public Singleton<TextureTracker> {
    TextureTracker();
    ~TextureTracker();

    friend class Singleton<TextureTracker>;

public:
    void startMark(const char* name);
    void endMark();
    void add(int textureId, int w, int h, int format, int type, const char* name, const char* comment = nullptr);
    void add(const char* name, int textureId, int w, int h,
        int format, int type, const char* purpose, const char* comment = nullptr);
    void remove(int textureId, const char* comment = nullptr);
    void update(int textureId, bool ghost, const char* name = nullptr);

    void dumpMemoryUsage(String8 &log);

    static int estimateMemory(int w, int h, int format, int type);

private:
    struct TextureEntry {
        TextureEntry(): mName("none"), mId(0), mWidth(0), mHeight(0)
                , mFormat(0), mType(0), mMemory(0), mGhost(true), mPurpose("none") {
        }

        TextureEntry(const char* name, int id, int w, int h, int format, int type, const char* purpose)
                : mName(name), mId(id), mWidth(w), mHeight(h)
                , mFormat(format), mType(type), mGhost(false), mPurpose(purpose) {
            mMemory = TextureTracker::estimateMemory(w, h, format, type);
        }

        TextureEntry(int id): mName("none"), mId(id), mWidth(0), mHeight(0)
                , mFormat(0), mType(0),mMemory(0), mGhost(true), mPurpose("none") {
        }

        ~TextureEntry() {
        }

        bool operator<(const TextureEntry& rhs) const {
            return mId < rhs.mId;
        }

        bool operator==(const TextureEntry& rhs) const {
            return mId == rhs.mId;
        }

        String8 mName;
        int mId;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        int mMemory;
        bool mGhost;
        const char* mPurpose;
    };

    struct Sum {
        Sum(): mName("none"), mSum(0) {
        }

        Sum(TextureEntry entry) : mName(entry.mName.string()), mSum(entry.mMemory) {
            mItems.add(entry);
        }

        ~Sum() {
        }

        bool operator<(const Sum& rhs) const {
            return strcmp(mName, rhs.mName) < 0;
        }

        bool operator==(const Sum& rhs) const {
            return strcmp(mName, rhs.mName) == 0;
        }

        const char* mName;
        int mSum;
        Vector<TextureEntry> mItems;
    };

private:
    Vector<String8> mViews;
    SortedList<TextureEntry> mMemoryList;
    mutable Mutex mLock;
};

}; // namespace uirenderer
}; // namespace android

#endif /* MTK_HWUI_TEXTURETRACKER_H */
