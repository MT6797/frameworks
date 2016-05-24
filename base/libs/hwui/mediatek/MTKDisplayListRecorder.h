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

#ifndef MTK_HWUI_DISPLAYLISTRECORDER_H
#define MTK_HWUI_DISPLAYLISTRECORDER_H

#include "../Matrix.h"
#include "../utils/LinearAllocator.h"
#include "Outline.h"

#include <utils/Log.h>
#include <utils/Singleton.h>
#include <utils/String8.h>
#include <utils/Trace.h>
#include <unordered_map>
#include <set>
#include <SkMatrix.h>
#include <SkPath.h>
#include <SkPaint.h>
#include <SkRegion.h>

namespace android {
namespace uirenderer {

class DisplayListRecorder;
class OpenGLRenderer;
class RenderNode;
class OpNode;
class OpList;
class Layer;
class DisplayListOp;

/*
 * DisplayListRecorder records a list of display list op of a frame.
 * The result is different from RenderNode's output. It gives
 * more information about the current frame like clip region, color,
 * transformation, text, alpha ... so we can be easier to analyze
 * display wrong issues.
 */

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define node_id void*
typedef void (*RunnableFunction)(DisplayListRecorder* recorder, node_id id, void* data);

#define MTK_RECT_STRING "(%.0f, %.0f, %.0f, %.0f)"
#define MTK_RECT_ARGS(r) \
    (r)->left, (r)->top, (r)->right, (r)->bottom

#define MTK_SK_RECT_STRING "(%.0f, %.0f, %.0f, %.0f)"
#define MTK_SK_RECT_ARGS(r) \
        (r)->left(), (r)->top(), (r)->right(), (r)->bottom()

/* These MACRO are for SkMatrix which is row major
 *       | 0 1 2 |
 *       | 3 4 5 |
 *       | 6 7 8 |
*/
#define MTK_SK_MATRIX_STRING "{[%f %f %f] [%f %f %f] [%f %f %f]}"
#define MTK_SK_MATRIX_ARGS(m) \
    (m)->get(0), (m)->get(1), (m)->get(2), \
    (m)->get(3), (m)->get(4), (m)->get(5), \
    (m)->get(6), (m)->get(7), (m)->get(8)

/* but Matrix4 is column major
*       | 0  4  8  12 |
*       | 1  5  9  13 |
*       | 2  6 10  14 |
*       | 3  7 11  15 |
*/
#define MTK_MATRIX_4_STRING "{0x%x [%f %f %f %f] [%f %f %f %f] [%f %f %f %f] [%f %f %f %f]}"
#define MTK_MATRIX_4_ARGS(m) \
    (m)->getType(), \
    (m)->data[0], (m)->data[4], (m)->data[8], (m)->data[12], \
    (m)->data[1], (m)->data[5], (m)->data[9], (m)->data[13], \
    (m)->data[2], (m)->data[6], (m)->data[10], (m)->data[14], \
    (m)->data[3], (m)->data[7], (m)->data[11], (m)->data[15] \

#define MALLOC(TYPE) MALLOC_SIZE(TYPE, 1)
#define MALLOC_SIZE(TYPE, SIZE) reinterpret_cast<TYPE*>(renderer.recorder().allocator().alloc(SIZE * sizeof(TYPE)))
#define COPY(TYPE, SRC) (__tmp##TYPE = MALLOC(TYPE), *__tmp##TYPE = SRC, __tmp##TYPE)

#define NODE_LOG(...) recorder->log(__VA_ARGS__)
#define NODE_ARGS(name) name ## Args

#define NODE_CREATE0(name) NC(name,,,,,,,,,,,)
#define NODE_CREATE1(name, a1) NC(name, a1,,,,,,,,,,)
#define NODE_CREATE2(name, a1, a2) NC(name, a1,a2,,,,,,,,,)
#define NODE_CREATE3(name, a1, a2, a3) NC(name, a1,a2,a3,,,,,,,,)
#define NODE_CREATE4(name, a1, a2, a3, a4) NC(name, a1,a2,a3,a4,,,,,,,)
#define NODE_CREATE5(name, a1, a2, a3, a4, a5) NC(name, a1,a2,a3,a4,a5,,,,,,)
#define NODE_CREATE6(name, a1, a2, a3, a4, a5, a6) NC(name, a1,a2,a3,a4,a5,a6,,,,,)
#define NODE_CREATE7(name, a1, a2, a3, a4, a5, a6, a7) NC(name, a1,a2,a3,a4,a5,a6,a7,,,,)
#define NODE_CREATE8(name, a1, a2, a3, a4, a5, a6, a7, a8) NC(name, a1,a2,a3,a4,a5,a6,a7,a8,,,)
#define NODE_CREATE9(name, a1, a2, a3, a4, a5, a6, a7, a8, a9) NC(name, a1,a2,a3,a4,a5,a6,a7,a8,a9,,)
#define NODE_CREATE10(name, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) NC(name, a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,)
#define NODE_CREATE11(name, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) NC(name, a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11)
#define NC(name, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) \
        typedef struct { \
            a1; a2; a3; a4; a5; a6; a7; a8; a9; a10; a11;\
        } NODE_ARGS(name); \
        static void Node_Bridge_ ## name(DisplayListRecorder* recorder, node_id id, NODE_ARGS(name)* args)

#define SETUP_NODE(name) \
        if (!renderer.recorder().isEnabled()) return; \
        OpNode* node = new (renderer.recorder().allocator()) OpNode((RunnableFunction) Node_Bridge_ ## name, this); \
        NODE_ARGS(name) *args = MALLOC(NODE_ARGS(name)); \
        node->setData(args); \
        renderer.recorder().addOp(node); \
        Rect* __tmpRect; \
        Matrix4* __tmpMatrix4; \
        SkRect* __tmpSkRect; \
        SkIRect* __tmpSkIRect;

extern char* copyCharString(OpenGLRenderer& renderer, const char* str, int len);
extern char* regionToChar(OpenGLRenderer& renderer, const SkRegion& clipRegion);
extern char* unicharToChar(OpenGLRenderer& renderer, const SkPaint* paint, const char* text, int count);

///////////////////////////////////////////////////////////////////////////////
// DisplayListRecorder
///////////////////////////////////////////////////////////////////////////////

class DisplayListRecorder {
public:
    DisplayListRecorder();
    ~DisplayListRecorder();

public:
    void prepare(OpenGLRenderer& renderer, Layer* layer = nullptr);
    void finishRecording(OpenGLRenderer& renderer);
    OpNode* query(const DisplayListOp* op);
    LinearAllocator& allocator();

    void setName(const char* name) {
        if (name) {
            mName.setTo(name);
        }
    }
    const char* getName() { return mName.string(); }
    int getWidth() { return mWidth; }
    int getHeight() { return mHeight; }
    int getFrameCount() { return mFrameCount; }

    void addOp(OpNode* op);
    void addSetFromViewProperties(OpenGLRenderer& renderer, bool from);
    void addStart(OpenGLRenderer& renderer, RenderNode* rendernode, bool empty, Layer* layer);
    void addEnd(OpenGLRenderer& renderer, RenderNode* rendernode);
    void addEmpty(OpenGLRenderer& renderer, RenderNode* rendernode);
    void addReject(OpenGLRenderer& renderer, RenderNode* rendernode);
    void addSave(OpenGLRenderer& renderer, int flags);
    void addRestoreToCount(OpenGLRenderer& renderer, int count);
    void addTranslate(OpenGLRenderer& renderer, float dx, float dy);
    void addConcatMatrix(OpenGLRenderer& renderer, const Matrix4& matrix);
    void addSetMatrix(OpenGLRenderer& renderer, const Matrix4& matrix);
    void addScaleAlpha(OpenGLRenderer& renderer, float alpha);
    void addSetClippingOutline(OpenGLRenderer& renderer, const Outline* outline);
    void addSetClippingRoundRect(OpenGLRenderer& renderer,
            const Rect& rect, float radius, bool highPriority);

    void levelUp() { mLevel++; }
    void levelDown() { mLevel--; }
    void setFromViewProperties(bool from);
    bool isEnabled() { return mEnabled; }

    void log(const char* fmt, ...);
    void dump(FILE* file = nullptr);

private:
    OpList* mOpList;
    OpNode* mCurrentGroup;
    char* mTempBuffer;
    FILE* mTempFile;
    OpenGLRenderer* mRenderer;
    Layer* mLayer;
    String8 mName;
    int mLevel;
    int mWidth;
    int mHeight;
    int mFrameCount;
    bool mFromViewProperties;
    bool mEnabled;
};

///////////////////////////////////////////////////////////////////////////////
// OpNode
///////////////////////////////////////////////////////////////////////////////

class OpNode {
    friend class DisplayListRecorder;
public:
    OpNode(RunnableFunction function, node_id id)
        : mFunction(function)
        , mGroup(nullptr)
        , mPrev(nullptr)
        , mNext(nullptr)
        , mData(nullptr)
        , mDuration(0)
        , mId(id) {}
    // These objects should always be allocated with a LinearAllocator, and never destroyed/deleted.
    // standard new() intentionally not implemented, and delete/deconstructor should never be used.
    ~OpNode() { LOG_ALWAYS_FATAL("Destructor not supported"); }
    static void operator delete(void* ptr) { LOG_ALWAYS_FATAL("delete not supported"); }
    static void* operator new(size_t size) = delete; /** PURPOSELY OMITTED **/
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    void setData(void* data) {
        LOG_ALWAYS_FATAL_IF(mData, "Can't call OpNode payload twice!!");
        mData = data;
    }

    void setDuration(nsecs_t duration) {
        mDuration = duration;
    }

    const char* getGroupName();

private:
    RunnableFunction mFunction;
    OpNode* mGroup;
    OpNode* mPrev;
    OpNode* mNext;
    void* mData;
    nsecs_t mDuration;
    node_id mId;
};

///////////////////////////////////////////////////////////////////////////////
// OpList
///////////////////////////////////////////////////////////////////////////////

class OpList {
    friend class DisplayListRecorder;
public:
    OpList() : mHead(nullptr), mTail(nullptr) {}
    ~OpList() { mMap.clear(); }

private:
    // allocator into which all ops were allocated
    LinearAllocator allocator;
    OpNode* mHead;
    OpNode* mTail;
    std::unordered_map<DisplayListOp*, OpNode*> mMap;
};

///////////////////////////////////////////////////////////////////////////////
// OpenGLRendererWrapper
///////////////////////////////////////////////////////////////////////////////

class OpenGLRendererWrapper {
public:
    OpenGLRendererWrapper(OpenGLRenderer& renderer, bool isSetViewProperties);
    ~OpenGLRendererWrapper();

    int save(int flags);
    void restoreToCount(int saveCount);

    void setMatrix(const Matrix4& matrix);
    void concatMatrix(const Matrix4& matrix);
    void translate(float dx, float dy, float dz = 0.0f);
    void scaleAlpha(float alpha);
    void setClippingOutline(LinearAllocator& allocator, const Outline* outline);
    void setClippingRoundRect(LinearAllocator& allocator,
            const Rect& rect, float radius, bool highPriority = true);
    void setProjectionPathMask(LinearAllocator& allocator, const SkPath* path);
    int getSaveCount() const;

private:
    OpenGLRenderer& mRenderer;
    DisplayListRecorder& mRecorder;
    bool mIsSetViewProperties;
};

///////////////////////////////////////////////////////////////////////////////
// RecorderManager
///////////////////////////////////////////////////////////////////////////////

class RecordingManager: public Singleton<RecordingManager> {
    friend class Singleton<RecordingManager>;
public:
    RecordingManager() { }
    ~RecordingManager() { }

    void registerRecorder(DisplayListRecorder* recorder) {
        mRecorders.insert(recorder);
    }

    void unregisterRecorder(DisplayListRecorder* recorder) {
        mRecorders.erase(recorder);
    }

    void dump(FILE* file = nullptr);

private:
    std::set<DisplayListRecorder*> mRecorders;
};

}; // namespace uirenderer
}; // namespace android

#endif /* MTK_HWUI_DISPLAYLISTRECORDER */
