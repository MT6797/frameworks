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

#include "MTKDisplayListRecorder.h"
#include "../CanvasState.h"
#include "../OpenGLRenderer.h"
#include "../RenderNode.h"
#include "../Layer.h"

#include <utils/Log.h>
//#include <utils/Trace.h>

namespace android {

using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(RecordingManager);

namespace uirenderer {

#define BUFFER_SIZE 1000 * sizeof(char)

///////////////////////////////////////////////////////////////////////////////
// DisplayListRecorder
///////////////////////////////////////////////////////////////////////////////

DisplayListRecorder::DisplayListRecorder()
    : mOpList(nullptr)
    , mCurrentGroup(nullptr)
    , mTempBuffer(nullptr)
    , mTempFile(nullptr)
    , mRenderer(nullptr)
    , mLayer(nullptr)
    , mLevel(0)
    , mWidth(0)
    , mHeight(0)
    , mFrameCount(0)
    , mFromViewProperties(false)
    , mEnabled(false) {
}

DisplayListRecorder::~DisplayListRecorder() {
    delete mOpList;
}

OpNode* DisplayListRecorder::query(const DisplayListOp* op) {
    if (mOpList) {
        std::unordered_map<DisplayListOp*, OpNode*>::const_iterator got
                = mOpList->mMap.find(const_cast<DisplayListOp *>(op));

        if (got != mOpList->mMap.end()) return got->second;
    }
    return nullptr;
}

LinearAllocator& DisplayListRecorder::allocator() {
    LOG_ALWAYS_FATAL_IF(!mOpList, "op list is not prepared yet!!");
    return mOpList->allocator;
}

void DisplayListRecorder::prepare(OpenGLRenderer& renderer, Layer* layer) {
    mEnabled = IS_ENG_BUILD || IS_USERDEBUG_BUILD ||
        g_HWUI_debug_continuous_frame || g_HWUI_debug_current_frame;
    mFrameCount = mFrameCount < INT_MAX - 1 ? mFrameCount + 1 : 1;

    if (mEnabled) {
        mRenderer = &renderer;
        mLayer = layer;
        // mTempBuffer is allocated by LinearAllocator of mOpList. Don't delete it twice
        if (mOpList) delete mOpList;
        mOpList = new OpList();
        mTempBuffer = (char*) allocator().alloc(BUFFER_SIZE);
    }
}

void DisplayListRecorder::finishRecording(OpenGLRenderer& renderer) {
    LOG_ALWAYS_FATAL_IF(mRenderer != nullptr &&
        mRenderer != &renderer, "renderer changed!! old=%p, new=%p", mRenderer, &renderer);
    mEnabled = false;

    // delay to set width, height because of LayerRenderer
    mWidth = renderer.getViewportWidth();
    mHeight = renderer.getViewportHeight();

    if (g_HWUI_debug_continuous_frame) {
        // must create data/HWUI_dump before dump
        char file[512];
        snprintf(file, sizeof(file), "/data/data/%s/frame_%p_%09d.txt",
            Dumper::getInstance().mProcessName, mRenderer, getFrameCount());
        FILE* fPtr = fopen(file, "w");
        ALOGD("%s [%s]: %dx%d %s%s", __FUNCTION__, getName(), mWidth, mHeight, file,
            fPtr != nullptr ? "" : " can't open");

        if (fPtr != nullptr) {
            dump(fPtr);
            fclose(fPtr);
        } else {
            snprintf(file, sizeof(file), "/data/HWUI_dump/frame_%p_%09d.txt",
                mRenderer, getFrameCount());
            fPtr = fopen(file, "w");
            ALOGD("%s [%s]: %dx%d %s%s", __FUNCTION__, getName(), mWidth, mHeight, file,
                fPtr != nullptr ? "" : " can't open");

            if (fPtr != nullptr) {
                dump(fPtr);
                fclose(fPtr);
            } else {
                dump();
            }
        }
    }
}

void DisplayListRecorder::log(const char* fmt, ...) {
    if (mTempBuffer) {
        va_list ap;

        va_start(ap, fmt);
        vsnprintf(mTempBuffer, BUFFER_SIZE, fmt, ap);
        va_end(ap);

        if (mTempFile) {
            fprintf(mTempFile, "%*s%s%s\n", mLevel * 2, "",
                mTempBuffer, mFromViewProperties ? " by property" : "");
        } else {
            ALOGD("%*s%s%s", mLevel * 2, "",
                mTempBuffer, mFromViewProperties ? " by property" : "");
        }
    }
}

void DisplayListRecorder::addOp(OpNode* op) {
    LOG_ALWAYS_FATAL_IF(!mOpList, "op list is not prepared yet!!");

    if (mOpList->mTail) {
        mOpList->mTail->mNext = op;
        op->mPrev = mOpList->mTail;
        op->mGroup = mCurrentGroup;;
        mOpList->mTail = op;
    } else {
        mOpList->mTail = mOpList->mHead = op;
    }

    if (op->mId != this)  mOpList->mMap[reinterpret_cast<DisplayListOp*>(op->mId)] = op;
}

void DisplayListRecorder::setFromViewProperties(bool from) {
    mFromViewProperties = from;
}

void DisplayListRecorder::dump(FILE* file) {
    mTempFile = file;
    if (mOpList) {
        bool notLayer = !mLayer;
        char layer[20];
        snprintf(layer, 20, "Layer %p", mLayer);
        log("--defer%s start (%dx%d, %s) frame#%d <%p>",
            notLayer ? "" : " layer", getWidth(), getHeight(),
            notLayer ? mName.string() : layer, getFrameCount(), mRenderer);
        int count = 0;
        OpNode* current = mOpList->mHead;
        while(current) {
            current->mFunction(this, current->mId, current->mData);
            current = current->mNext;
            count++;
        }

        log("--defer%s end frame#%d <%p>", notLayer ? "" : " layer", getFrameCount(), mRenderer);
        log("--flush%s start (%dx%d, %s) frame#%d <%p>",
            notLayer ? "" : " layer", getWidth(), getHeight(),
            notLayer ? mName.string() : layer, getFrameCount(), mRenderer);

        current = mOpList->mHead;
        while(current) {
            if (current->mDuration != 0) {
                log("O1 (%p, %dus) <%p>", current->mId, int(current->mDuration * 0.001), mRenderer);
            }
            current = current->mNext;
        }

        log("--flush%s end frame#%d <%p>", notLayer ? "" : " layer", getFrameCount(), mRenderer);
        log("total %d op, allocator %p usedSize = %d", count, &allocator(), allocator().usedSize());
    }
    mTempFile = nullptr;
}

///////////////////////////////////////////////////////////////////////////////
// helper function
///////////////////////////////////////////////////////////////////////////////

char* copyCharString(OpenGLRenderer& renderer, const char* str, int len) {
    char* buffer = MALLOC_SIZE(char, len + 1);
    memcpy(buffer, str, len);
    buffer[len] = 0;
    return buffer;
}

char* regionToChar(OpenGLRenderer& renderer, const SkRegion& clipRegion) {
    if (g_HWUI_debug_continuous_frame && clipRegion.isComplex()) {
        String8 dump, points;
        int count = 0;
        SkRegion::Iterator iter(clipRegion);
        while (!iter.done()) {
            const SkIRect& r = iter.rect();
            points.appendFormat("(%d,%d,%d,%d)", r.fLeft, r.fTop, r.fRight, r.fBottom);
            count++;
            if (count > 0 && count % 16 == 0) points.append("\n");
            iter.next();
        }
        dump.appendFormat("===> currRegions %d <%p>\n%s", count, &renderer, points.string());
        return copyCharString(renderer, dump.string(), dump.size());
    }
    return nullptr;
}

char* unicharToChar(OpenGLRenderer& renderer, const SkPaint* paint, const char* text, int count) {
    if (g_HWUI_debug_continuous_frame) {
        SkUnichar *tmpText = MALLOC_SIZE(SkUnichar, count);
        paint->glyphsToUnichars((uint16_t*)text, count, tmpText);
        int total = utf32_to_utf8_length((char32_t*)tmpText, count) + 1;
        char* str = MALLOC_SIZE(char, total);
        utf32_to_utf8((char32_t*)tmpText, count, str);
        return str;
    }
    return nullptr;
}

///////////////////////////////////////////////////////////////////////////////
// op related implementation
///////////////////////////////////////////////////////////////////////////////

NODE_CREATE1(SetFromViewPropertiesOp, bool from) {
    recorder->setFromViewProperties(args->from);
}

void DisplayListRecorder::addSetFromViewProperties(OpenGLRenderer& renderer, bool from) {
    SETUP_NODE(SetFromViewPropertiesOp);
    args->from = from;
}

NODE_CREATE3(StartOp, char* name, Rect* clipRect, char* clipRegion) {
    NODE_LOG("Start display list (%p, %s) ===> currClip" MTK_RECT_STRING,
        id, args->name, MTK_RECT_ARGS(args->clipRect));
    if (args->clipRegion) NODE_LOG("%s", args->clipRegion);
    recorder->levelUp();
}

NODE_CREATE1(EmptyOp, char* name) {
    NODE_LOG("Empty display list (%p, %s)", id, args->name);
    recorder->levelUp();
}

NODE_CREATE2(RejectOp, char* name, float alpha) {
    NODE_LOG("Rejected display list (%p, %s), alpha %f", id, args->name, args->alpha);
    recorder->levelUp();
}

void DisplayListRecorder::addStart(OpenGLRenderer& renderer, RenderNode* renderNode, bool empty, Layer* layer) {
    const Outline& outline = renderNode->properties().getOutline();
    if (empty) {
        SETUP_NODE(EmptyOp);
        node->mGroup = mCurrentGroup = node;
        node->mId= renderNode;
        args->name = copyCharString(renderer, renderNode->getName(), strlen(renderNode->getName()));
    } else if ((!layer || (layer && (&renderer != layer->renderer.get()))) &&
            (renderNode->properties().getAlpha() <= 0 || (outline.getShouldClip() && outline.isEmpty()))) {
        SETUP_NODE(RejectOp);
        node->mGroup = mCurrentGroup = node;
        node->mId = renderNode;
        args->name = copyCharString(renderer, renderNode->getName(), strlen(renderNode->getName()));
        args->alpha = renderNode->properties().getAlpha();
    } else {
        SETUP_NODE(StartOp);
        node->mGroup = mCurrentGroup = node;
        node->mId = renderNode;
        args->name = copyCharString(renderer, renderNode->getName(), strlen(renderNode->getName()));
        args->clipRect = COPY(Rect, renderer.currentSnapshot()->getClipRect());
        args->clipRegion = regionToChar(renderer, renderer.currentSnapshot()->getClipRegion());
    }
}

NODE_CREATE1(EndOp, const char* name) {
    recorder->levelDown();
    NODE_LOG("Done (%p, %s)", id, args->name);
}

void DisplayListRecorder::addEnd(OpenGLRenderer& renderer, RenderNode* renderNode) {
    SETUP_NODE(EndOp);
    mCurrentGroup = node->mGroup->mPrev ? node->mGroup->mPrev->mGroup : nullptr;
    node->mId = renderNode;
    args->name = renderNode->getName();
}

NODE_CREATE2(SaveOp, int flags, int count) {
    NODE_LOG("Save flags 0x%x, count %d <%p>", args->flags, args->count, args);
}

void DisplayListRecorder::addSave(OpenGLRenderer& renderer, int flags) {
    SETUP_NODE(SaveOp);
    args->flags = flags;
    args->count = renderer.getSaveCount() - 1;
}

NODE_CREATE1(RestoreToCountOp, int count) {
    NODE_LOG("Restore to count %d <%p>", args->count, args);
}

void DisplayListRecorder::addRestoreToCount(OpenGLRenderer& renderer, int count){
    SETUP_NODE(RestoreToCountOp);
    args->count = count;
}

NODE_CREATE3(TranslateOp, float dx, float dy, Matrix4* currentTransform) {
    NODE_LOG("Translate by (%.2f, %.2f) ===> currTrans" MTK_MATRIX_4_STRING, args->dx, args->dy,
        MTK_MATRIX_4_ARGS(args->currentTransform));
}

void DisplayListRecorder::addTranslate(OpenGLRenderer& renderer, float dx, float dy) {
    SETUP_NODE(TranslateOp);
    args->dx = dx;
    args->dy = dy;
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

NODE_CREATE2(ContactMatrixOp, Matrix4* matrix, Matrix4* currentTransform) {
    NODE_LOG("ConcatMatrix " MTK_MATRIX_4_STRING " ===> currTrans" MTK_MATRIX_4_STRING,
        MTK_MATRIX_4_ARGS(args->matrix), MTK_MATRIX_4_ARGS(args->currentTransform));
}

void DisplayListRecorder::addConcatMatrix(OpenGLRenderer& renderer, const Matrix4& matrix) {
    SETUP_NODE(ContactMatrixOp);
    args->matrix = COPY(Matrix4, matrix);
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

NODE_CREATE2(SetMatrixOp, Matrix4* matrix, Matrix4* currentTransform) {
    NODE_LOG("SetMatrix " MTK_MATRIX_4_STRING " ===> currTrans" MTK_MATRIX_4_STRING,
        MTK_MATRIX_4_ARGS(args->matrix), MTK_MATRIX_4_ARGS(args->currentTransform));
}

void DisplayListRecorder::addSetMatrix(OpenGLRenderer& renderer, const Matrix4& matrix) {
    SETUP_NODE(SetMatrixOp);
    args->matrix = COPY(Matrix4, matrix);
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

NODE_CREATE1(ScaleAlphaOp, float alpha) {
    NODE_LOG("ScaleAlpha %.2f", args->alpha);
}

void DisplayListRecorder::addScaleAlpha(OpenGLRenderer& renderer, float alpha) {
    SETUP_NODE(ScaleAlphaOp);
    args->alpha = alpha;
}

void DisplayListRecorder::addSetClippingOutline(OpenGLRenderer& renderer, const Outline* outline) {
    // not support yet
}

NODE_CREATE2(SetClippingRoundRectOp, Rect* rect, float radius) {
    NODE_LOG("SetClippingRoundRect " MTK_RECT_STRING ", r %f <%p>",
        MTK_RECT_ARGS(args->rect), args->radius, id);
}

void DisplayListRecorder::addSetClippingRoundRect(OpenGLRenderer& renderer,
        const Rect& rect, float radius, bool highPriority) {
    SETUP_NODE(SetClippingRoundRectOp);
    args->rect = COPY(Rect, rect);
    args->radius = radius;
}

///////////////////////////////////////////////////////////////////////////////
// OpNode
///////////////////////////////////////////////////////////////////////////////

const char* OpNode::getGroupName() {
    LOG_ALWAYS_FATAL_IF(!mGroup, "op group is not set well!!");
    NODE_ARGS(StartOp) *args = reinterpret_cast<NODE_ARGS(StartOp) *>(mGroup->mData);
    return args->name;
}

///////////////////////////////////////////////////////////////////////////////
// OpenGLRendererWrapper
///////////////////////////////////////////////////////////////////////////////

OpenGLRendererWrapper::OpenGLRendererWrapper(OpenGLRenderer& renderer, bool isSetViewProperties)
        : mRenderer (renderer)
        , mRecorder (renderer.recorder())
        , mIsSetViewProperties(isSetViewProperties) {
    if (mIsSetViewProperties) {
        mRecorder.addSetFromViewProperties(mRenderer, true);
    }
}

OpenGLRendererWrapper::~OpenGLRendererWrapper() {
    if (mIsSetViewProperties) {
        mRecorder.addSetFromViewProperties(mRenderer, false);
    }
}

int OpenGLRendererWrapper::save(int flags) {
    int ret = mRenderer.save(flags);
    mRecorder.addSave(mRenderer, flags);
    return ret;
}
void OpenGLRendererWrapper::restoreToCount(int saveCount){
    mRenderer.restoreToCount(saveCount);
    mRecorder.addRestoreToCount(mRenderer, saveCount);
}

void OpenGLRendererWrapper::setMatrix(const Matrix4& matrix) {
    mRenderer.setMatrix(matrix);
    mRecorder.addSetMatrix(mRenderer, matrix);
}
void OpenGLRendererWrapper::concatMatrix(const Matrix4& matrix) {
    mRenderer.concatMatrix(matrix);
    mRecorder.addConcatMatrix(mRenderer, matrix);
}
void OpenGLRendererWrapper::translate(float dx, float dy, float dz) {
    mRenderer.translate(dx, dy, dz);
    mRecorder.addTranslate(mRenderer, dx, dy);
}

void OpenGLRendererWrapper::scaleAlpha(float alpha) {
    mRenderer.scaleAlpha(alpha);
    mRecorder.addScaleAlpha(mRenderer, alpha);
}

void OpenGLRendererWrapper::setClippingOutline(LinearAllocator& allocator, const Outline* outline) {
    mRenderer.setClippingOutline(allocator, outline);
    mRecorder.addSetClippingOutline(mRenderer, outline);
}

void OpenGLRendererWrapper::setClippingRoundRect(LinearAllocator& allocator,
        const Rect& rect, float radius, bool highPriority) {
    mRenderer.setClippingRoundRect(allocator, rect, radius, highPriority);
    mRecorder.addSetClippingRoundRect(mRenderer, rect, radius, highPriority);
}

void OpenGLRendererWrapper::setProjectionPathMask(LinearAllocator& allocator, const SkPath* path) {
    mRenderer.setProjectionPathMask(allocator, path);
}

int OpenGLRendererWrapper::getSaveCount() const {
    return mRenderer.getSaveCount();
}

///////////////////////////////////////////////////////////////////////////////
// RecordingManager
///////////////////////////////////////////////////////////////////////////////

void RecordingManager::dump(FILE* file) {
    FILE* fPtr = nullptr;
    if (file == nullptr) {
        // must create data/HWUI_dump before dump
        // renderthread::RenderThread::getInstance().getTid() is inaccessible,
        // just use Pid as Tid for file name
        char fileName[512];
        snprintf(fileName, sizeof(fileName),
            "/data/data/%s/current_frame_%09d_%09d_%p.txt", Dumper::getInstance().mProcessName,
            Dumper::getInstance().mPid, Dumper::getInstance().mPid, this);
        fPtr = fopen(fileName, "w");
        ALOGD("RecordingManager dumpCurrentFrame [%s]: %s%s",
                Dumper::getInstance().mProcessName, fileName, fPtr != nullptr ? "" : " can't open");
        if (fPtr == nullptr) {
            snprintf(fileName, sizeof(fileName),
                "/data/HWUI_dump/current_frame_%09d_%09d_%p.txt", Dumper::getInstance().mPid,
                Dumper::getInstance().mPid, this);
            fPtr = fopen(fileName, "w");
            ALOGD("RecordingManager dumpCurrentFrame [%s]: %s%s",
                Dumper::getInstance().mProcessName, fileName, fPtr != nullptr ? "" : " can't open");
        }
    } else {
        fPtr = file;
    }

    for (std::set<DisplayListRecorder*>::iterator lit = mRecorders.begin();
            lit != mRecorders.end(); lit++) {
        DisplayListRecorder* recorder = *(lit);
        recorder->dump(fPtr);
    }

    if (file == nullptr && fPtr != nullptr) {
        fclose(fPtr);
    }
}

}; // namespace uirenderer
}; // namespace android
