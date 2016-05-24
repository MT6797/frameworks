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

#include "../DisplayListOp.h"
#include "../OpenGLRenderer.h"
#include "../Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// op record implementation
///////////////////////////////////////////////////////////////////////////////

void SaveOp::record(OpenGLRenderer& renderer, int saveCount) {
    renderer.recorder().addSave(renderer, mFlags);
}

void RestoreToCountOp::record(OpenGLRenderer& renderer, int saveCount) {
    renderer.recorder().addRestoreToCount(renderer, mCount + saveCount);
}

NODE_CREATE5(SaveLayerOp, bool isSaveLayerAlpha, Rect* area, int alpha, int flags, int count) {
    NODE_LOG("SaveLayer%s " MTK_RECT_STRING ", alpha %d, flags 0x%x, count %d <%p>",
        args->isSaveLayerAlpha? "Alpha" : "", MTK_RECT_ARGS(args->area),
        args->alpha, args->flags, args->count, id);
}

void SaveLayerOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(SaveLayerOp);
    args->isSaveLayerAlpha = isSaveLayerAlpha();
    args->alpha = OpenGLRenderer::getAlphaDirect(mPaint);
    args->flags = mFlags;
    args->count = renderer.getSaveCount() - 1;
    args->area = COPY(Rect, mArea);
}

void TranslateOp::record(OpenGLRenderer& renderer, int saveCount) {
    renderer.recorder().addTranslate(renderer, mDx, mDy);
}

NODE_CREATE2(RotateOp, float degrees, Matrix4* currentTransform) {
    NODE_LOG("Rotate by %f degrees ===> currTrans" MTK_MATRIX_4_STRING,
        args->degrees, MTK_MATRIX_4_ARGS(args->currentTransform));
}

void RotateOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(RotateOp);
    args->degrees = mDegrees;
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

NODE_CREATE3(ScaleOp, float sx, float sy, Matrix4* currentTransform) {
    NODE_LOG("Scale by (%.2f, %.2f) ===> currTrans" MTK_MATRIX_4_STRING, args->sx, args->sy,
                MTK_MATRIX_4_ARGS(args->currentTransform));
}

void ScaleOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(ScaleOp);
    args->sx = mSx;
    args->sy = mSy;
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

NODE_CREATE3(SkewOp, float sx, float sy, Matrix4* currentTransform) {
    NODE_LOG("Scale by (%.2f, %.2f) ===> currTrans" MTK_MATRIX_4_STRING, args->sx, args->sy,
                MTK_MATRIX_4_ARGS(args->currentTransform));
}

void SkewOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(SkewOp);
    args->sx = mSx;
    args->sy = mSy;
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

void SetMatrixOp::record(OpenGLRenderer& renderer, int saveCount) {
    Matrix4 matrix;
    matrix.load(mMatrix);
    renderer.recorder().addSetMatrix(renderer, matrix);
}

NODE_CREATE2(SetLocalMatrixOp, Matrix4* matrix, Matrix4* currentTransform) {
    NODE_LOG("SetLocalMatrix " MTK_MATRIX_4_STRING " ===> currTrans" MTK_MATRIX_4_STRING,
        MTK_MATRIX_4_ARGS(args->matrix), MTK_MATRIX_4_ARGS(args->currentTransform));
}

void SetLocalMatrixOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(SetLocalMatrixOp);
    Matrix4 matrix;
    matrix.load(mMatrix);
    args->matrix = COPY(Matrix4, matrix);
    args->currentTransform = COPY(Matrix4, *renderer.currentTransform());
}

void ConcatMatrixOp::record(OpenGLRenderer& renderer, int saveCount) {
    Matrix4 matrix;
    matrix.load(mMatrix);
    renderer.recorder().addConcatMatrix(renderer, matrix);
}

NODE_CREATE3(ClipRectOp, Rect* area, Rect* clipRect, char* clipRegion)  {
    NODE_LOG("ClipRect " MTK_RECT_STRING " <%p> ===> currClip" MTK_RECT_STRING,
        MTK_RECT_ARGS(args->area), id, MTK_RECT_ARGS(args->clipRect));
    if (args->clipRegion) {
        NODE_LOG("%s", args->clipRegion);
    }
}

void ClipRectOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(ClipRectOp);
    args->area = COPY(Rect, mArea);
    args->clipRect = COPY(Rect, renderer.currentSnapshot()->getClipRect());
    args->clipRegion = regionToChar(renderer, renderer.currentSnapshot()->getClipRegion());
}

NODE_CREATE3(ClipPathOp, SkRect* bounds, Rect* clipRect, char* clipRegion)  {
    NODE_LOG("ClipPath bounds " MTK_SK_RECT_STRING " <%p> ===> currClip" MTK_RECT_STRING,
         MTK_SK_RECT_ARGS(args->bounds), id, MTK_RECT_ARGS(args->clipRect));
    if (args->clipRegion) {
        NODE_LOG("%s", args->clipRegion);
    }
}

void ClipPathOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(ClipPathOp);
    args->bounds = COPY(SkRect, mPath->getBounds());
    args->clipRect = COPY(Rect, renderer.currentSnapshot()->getClipRect());
    args->clipRegion = regionToChar(renderer, renderer.currentSnapshot()->getClipRegion());
}

NODE_CREATE3(ClipRegionOp, SkIRect* bounds, Rect* clipRect, char* clipRegion)  {
    NODE_LOG("ClipRegion bounds " MTK_SK_RECT_STRING " <%p> ===> currClip" MTK_RECT_STRING,
         MTK_SK_RECT_ARGS(args->bounds), id, MTK_RECT_ARGS(args->clipRect));
    if (args->clipRegion) {
        NODE_LOG("%s", args->clipRegion);
    }
}

void ClipRegionOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(ClipRegionOp);
    args->bounds = COPY(SkIRect, mRegion->getBounds());
    args->clipRect = COPY(Rect, renderer.currentSnapshot()->getClipRect());
    args->clipRegion = regionToChar(renderer, renderer.currentSnapshot()->getClipRegion());
}

NODE_CREATE3(DrawBitmapOp, const SkBitmap* bitmap, bool entry, Rect* localBounds) {
    NODE_LOG("Draw bitmap %p%s at " MTK_RECT_STRING " <%p>", args->bitmap,
        args->entry ? " using AssetAtlas" : "", MTK_RECT_ARGS(args->localBounds), id);
}

void DrawBitmapOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawBitmapOp);
    args->bitmap = mBitmap;
    args->entry = mEntry != nullptr;
    args->localBounds = COPY(Rect, mLocalBounds);
}

NODE_CREATE3(DrawBitmapRectOp, const SkBitmap* bitmap, Rect *src, Rect* localBounds) {
    NODE_LOG("Draw bitmap %p src=" MTK_RECT_STRING ", dst=" MTK_RECT_STRING " <%p>",
         args->bitmap, MTK_RECT_ARGS(args->src), MTK_RECT_ARGS(args->localBounds), id);
}

void DrawBitmapRectOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawBitmapRectOp);
    args->bitmap = mBitmap;
    args->src = COPY(Rect, mSrc);
    args->localBounds = COPY(Rect, mLocalBounds);
}

NODE_CREATE4(DrawBitmapMeshOp, const SkBitmap* bitmap, int meshWidth, int meshHeight, Rect* localBounds) {
    NODE_LOG("Draw bitmap %p mesh %d x %d at " MTK_RECT_STRING " <%p>",
        args->bitmap, args->meshWidth, args->meshHeight, MTK_RECT_ARGS(args->localBounds), id);
}

void DrawBitmapMeshOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawBitmapMeshOp);
    args->bitmap = mBitmap;
    args->meshWidth = mMeshWidth;
    args->meshHeight = mMeshHeight;
    args->localBounds = COPY(Rect, mLocalBounds);
}

NODE_CREATE3(DrawPatchOp, const SkBitmap* bitmap, bool entry, Rect* localBounds) {
    NODE_LOG("Draw patch %p%s at " MTK_RECT_STRING " <%p>", args->bitmap,
        args->entry ? " using AssetAtlas" : "", MTK_RECT_ARGS(args->localBounds), id);
}


void DrawPatchOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawPatchOp);
    args->bitmap = mBitmap;
    args->entry = mEntry != nullptr;
    args->localBounds = COPY(Rect, mLocalBounds);
}

NODE_CREATE2(DrawColorOp, int color, int mode) {
    NODE_LOG("Draw color 0x%08x, mode %d <%p>", args->color, args->mode, id);
}

void DrawColorOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawColorOp);
    args->color = mColor;
    args->mode = mMode;
}

NODE_CREATE5(DrawRectOp, Rect* localBounds, int style, int isAntiAlias, int xfermode, int color) {
    NODE_LOG("Draw Rect " MTK_RECT_STRING ", style %d, AA %d, mode %d, color 0x%08x <%p>",
        MTK_RECT_ARGS(args->localBounds), args->style, args->isAntiAlias,
        args->xfermode, args->color, id);
}

void DrawRectOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawRectOp);
    args->localBounds = COPY(Rect, mLocalBounds);
    args->style = mPaint->getStyle();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->xfermode = OpenGLRenderer::getXfermodeDirect(mPaint);
    args->color = mPaint->getColor();
}

NODE_CREATE1(DrawRectsOp, int count) {
    NODE_LOG("Draw Rects count %d <%p>", args->count, id);
}

void DrawRectsOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawRectsOp);
    args->count = mCount;
}

NODE_CREATE6(DrawRoundRectOp, Rect* localBounds, float rx, float ry,
        int style, bool isAntiAlias, int color) {
    NODE_LOG("Draw RoundRect " MTK_RECT_STRING ", rx %f, ry %f, style %d, AA %d, color 0x%08x <%p>",
    MTK_RECT_ARGS(args->localBounds), args->rx, args->ry, args->style, args->isAntiAlias,
    args->color, id);
}

void DrawRoundRectOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawRoundRectOp);
    args->localBounds = COPY(Rect, mLocalBounds);
    args->rx = mRx;
    args->ry = mRy;
    args->style = mPaint->getStyle();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
}

NODE_CREATE6(DrawRoundRectPropsOp, Rect* localBounds, float rx, float ry,
        int style, bool isAntiAlias, int color) {
    NODE_LOG("Draw RoundRect Props " MTK_RECT_STRING ", rx %f, ry %f, style %d, AA %d, color 0x%08x <%p>",
    MTK_RECT_ARGS(args->localBounds), args->rx, args->ry, args->style, args->isAntiAlias,
    args->color, id);
}

void DrawRoundRectPropsOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawRoundRectPropsOp);
    args->localBounds = COPY(Rect, Rect(*mLeft, *mTop, *mRight, *mBottom));
    args->rx = *mRx;
    args->ry = *mRy;
    args->style = mPaint->getStyle();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
}

NODE_CREATE7(DrawCircleOp, float x, float y, float radius,
        int style, float strokeWidth , bool isAntiAlias, int color) {
    NODE_LOG("Draw Circle x %f, y %f, r %f, style %d, width %f, AA %d, color 0x%08x <%p>",
        args->x, args->y, args->radius, args->style,
        args->strokeWidth, args->isAntiAlias, args->color, id);
}

void DrawCircleOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawCircleOp);
    args->x = mX;
    args->y = mY;
    args->radius = mRadius;
    args->style = mPaint->getStyle();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
}

NODE_CREATE7(DrawCirclePropsOp, float x, float y, float radius,
        int style, float strokeWidth , bool isAntiAlias, int color) {
    NODE_LOG("Draw Circle Props x %f, y %f, r %f, style %d, width %f, AA %d, color 0x%08x <%p>",
        args->x, args->y, args->radius, args->style,
        args->strokeWidth, args->isAntiAlias, args->color, id);
}

void DrawCirclePropsOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawCirclePropsOp);
    args->x = *mX;
    args->y = *mY;
    args->radius = *mRadius;
    args->style = mPaint->getStyle();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
}

NODE_CREATE4(DrawOvalOp, Rect* localBounds, int style, bool isAntiAlias, int color) {
    NODE_LOG("Draw Oval " MTK_RECT_STRING ", style %d, AA %d, color 0x%08x <%p>",
        MTK_RECT_ARGS(args->localBounds), args->style, args->isAntiAlias, args->color, id);
}

void DrawOvalOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawOvalOp);
    args->localBounds = COPY(Rect, mLocalBounds);
    args->style = mPaint->getStyle();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
}

NODE_CREATE10(DrawArcOp, Rect* localBounds, float startAngle, float sweepAngle, int useCenter, int style,
    bool isAntiAlias, int color, float strokeWidth, int strokeJoin, int strokeCap) {
    NODE_LOG("Draw Arc " MTK_RECT_STRING ", start %f, sweep %f, useCenter %d, style %d,"
        "AA %d, color 0x%08x, width %f, join %d, cap %d <%p>", MTK_RECT_ARGS(args->localBounds),
        args->startAngle, args->sweepAngle, args->useCenter, args->style, args->isAntiAlias,
        args->color, args->strokeWidth, args->strokeJoin, args->strokeCap, id);
}

void DrawArcOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawArcOp);
    args->localBounds = COPY(Rect, mLocalBounds);
    args->startAngle = mStartAngle;
    args->sweepAngle = mSweepAngle;
    args->useCenter = mUseCenter;
    args->style = mPaint->getStyle();
    args->isAntiAlias = mPaint->isAntiAlias();
    args->color = mPaint->getColor();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->strokeJoin = mPaint->getStrokeJoin();
    args->strokeCap = mPaint->getStrokeCap();
}

NODE_CREATE7(DrawPathOp, int countPoints, int color, float strokeWidth, int strokeJoin,
    int strokeCap, Rect* localBounds, char* points) {
    NODE_LOG("Draw Path count %d, color 0x%08x, width %f, join %d, cap %d in " MTK_RECT_STRING
        " <%p> ===> Points(%s)", args->countPoints, args->color, args->strokeWidth, args->strokeJoin,
        args->strokeCap, MTK_RECT_ARGS(args->localBounds), id, args->points ? args->points : "");
}

void DrawPathOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawPathOp);
    args->countPoints = mPath->countPoints();
    args->localBounds = COPY(Rect, mLocalBounds);
    args->color = mPaint->getColor();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->strokeJoin = mPaint->getStrokeJoin();
    args->strokeCap = mPaint->getStrokeCap();
    if (mPath->countPoints() > 0) {
        String8 log;
        int total = mPath->countPoints();
        for (int i = 0; i < total; i++) {
            SkPoint p = mPath->getPoint(i);
            log.appendFormat("(%d,%d)", (int) (p.x()), (int) (p.y()));
        }
        args->points = copyCharString(renderer, log.string(), log.size());
    } else {
        args->points = nullptr;
    }
}

NODE_CREATE7(DrawLinesOp, int count, int color, float strokeWidth, int strokeJoin,
    int strokeCap, Rect* localBounds, char* points) {
    NODE_LOG("Draw Lines count %d, color 0x%08x, width %f, join %d, cap %d in " MTK_RECT_STRING
        " <%p> ===> Points(%s)", args->count, args->color, args->strokeWidth, args->strokeJoin,
        args->strokeCap, MTK_RECT_ARGS(args->localBounds), id, args->points ? args->points : "");
}

void DrawLinesOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawLinesOp);
    args->count = mCount;
    args->localBounds = COPY(Rect, mLocalBounds);
    args->color = mPaint->getColor();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->strokeJoin = mPaint->getStrokeJoin();
    args->strokeCap = mPaint->getStrokeCap();
    if (mCount > 0) {
        String8 log;
        for (int i = 0; i < mCount; i += 2) {
            log.appendFormat("(%d,%d)", (int) mPoints[i], (int) mPoints[i + 1]);
        }
        args->points = copyCharString(renderer, log.string(), log.size());
    } else {
        args->points = nullptr;
    }
}

NODE_CREATE7(DrawPointsOp, int count, int color, float strokeWidth, int strokeJoin,
    int strokeCap, Rect* localBounds, char* points) {
    NODE_LOG("Draw Points count %d, color 0x%08x, width %f, join %d, cap %d in " MTK_RECT_STRING
        " <%p> ===> Points(%s)", args->count, args->color, args->strokeWidth, args->strokeJoin,
        args->strokeCap, MTK_RECT_ARGS(args->localBounds), id, args->points ? args->points : "");
}

void DrawPointsOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawPointsOp);
    args->count = mCount;
    args->localBounds = COPY(Rect, mLocalBounds);
    args->color = mPaint->getColor();
    args->strokeWidth = mPaint->getStrokeWidth();
    args->strokeJoin = mPaint->getStrokeJoin();
    args->strokeCap = mPaint->getStrokeCap();
    if (mCount > 0) {
        String8 log;
        for (int i = 0; i < mCount; i += 2) {
            log.appendFormat("(%d,%d)", (int) mPoints[i], (int) mPoints[i + 1]);
        }
        args->points = copyCharString(renderer, log.string(), log.size());
    } else {
        args->points = nullptr;
    }
}

NODE_CREATE2(DrawSomeTextOp, char* str, int bytesCount) {
    NODE_LOG("Draw some text \"%s\", %d bytes <%p>", args->str, args->bytesCount, id);
}

void DrawSomeTextOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawSomeTextOp);
    args->bytesCount = mBytesCount;
    args->str = unicharToChar(renderer, mPaint, mText, mCount);
}

NODE_CREATE5(DrawTextOp, char* str, int count, int bytesCount, int color, Rect* localBounds) {
    NODE_LOG("Draw Text \"%s\", count %d, bytes %d, color 0x%08x at " MTK_RECT_STRING " <%p>",
        args->str, args->count, args->bytesCount, args->color,
        MTK_RECT_ARGS(args->localBounds), id);
}

void DrawTextOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawTextOp);
    args->bytesCount = mBytesCount;
    args->count = mCount;
    args->color = mPaint->getColor();
    args->localBounds = COPY(Rect, mLocalBounds);
    args->str = unicharToChar(renderer, mPaint, mText, mCount);
}

NODE_CREATE1(DrawFunctorOp, Functor* functor) {
    NODE_LOG("Draw Functor %p <%p>", args->functor, id);
}

void DrawFunctorOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawFunctorOp);
    args->functor = mFunctor;
}

NODE_CREATE7(DrawShadowOp, float x, float y, float z, float radius,
    float alpha, Matrix4* transformXY, Matrix4* transformZ) {
    NODE_LOG("Draw Shadow center (%f, %f, %f), radius %f, alpha %f, transformXY "
        MTK_MATRIX_4_STRING ", transformZ " MTK_MATRIX_4_STRING " <%p>",
        args->x, args->y, args->z, args->radius, args->alpha,
        MTK_MATRIX_4_ARGS(args->transformXY), MTK_MATRIX_4_ARGS(args->transformZ), id);
}

void DrawShadowOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawShadowOp);
    args->x = renderer.getLightCenter().x;
    args->y = renderer.getLightCenter().y;
    args->z = renderer.getLightCenter().z;
    args->radius = renderer.getLightRadius();
    args->alpha = mCasterAlpha;
    args->transformXY = COPY(Matrix4, mTransformXY);
    args->transformZ = COPY(Matrix4, mTransformZ);
}

NODE_CREATE11(DrawLayerOp, Layer* layer, float x, float y, int width, int height,
    float layerWidth, float layerHeight, int alpha, int isIdentity, int isBlend, int isTextureLayer) {
    NODE_LOG("Draw Layer %p at (%.2f, %.2f), textureSize (%d, %d), layerSize"
        " (%.2f, %.2f), alpha %d, isIdentity %d, isBlend %d, isTextureLayer %d <%p>",
        args->layer, args->x, args->y, args->width, args->height, args->layerWidth, args->layerHeight,
        args->alpha, args->isIdentity, args->isBlend, args->isTextureLayer, id);
}

void DrawLayerOp::record(OpenGLRenderer& renderer, int saveCount) {
    SETUP_NODE(DrawLayerOp);
    args->layer = mLayer;
    args->x = mX;
    args->y = mY;
    args->width = mLayer->getWidth();
    args->height = mLayer->getHeight();
    args->layerWidth = mLayer->layer.getWidth();
    args->layerHeight = mLayer->layer.getHeight();
    args->alpha = mLayer->getAlpha();
    args->isIdentity = mLayer->getTransform().isIdentity();
    args->isBlend = mLayer->isBlend();
    args->isTextureLayer = mLayer->isTextureLayer();
}

}; // namespace uirenderer
}; // namespace android
