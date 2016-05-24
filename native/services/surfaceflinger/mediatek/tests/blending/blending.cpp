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

/*
 * Copyright (C) 2010 The Android Open Source Project
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
#include <cutils/memory.h>

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <ui/GraphicBuffer.h>
#include <gui/Surface.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <cutils/properties.h>

#include <ui/DisplayInfo.h>
#include <ui/GraphicBufferExtra.h>
#include <ui/gralloc_extra.h>

#include "graphics_mtk_defs.h"

using namespace android;

struct Data_Config
{
    uint32_t fmt;
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
};

struct Layer_Config
{
    Layer_Config(ANativeWindow* window, uint32_t width, uint32_t height)
        : win(window)
        , w(width)
        , h(height)
    {
        // set api connection type as register
        native_window_api_connect(win, 0);
    }
    ~Layer_Config()
    {
        // disconnect as unregister
        native_window_api_disconnect(win, 0);
    }
    ANativeWindow *win;
    uint32_t w;
    uint32_t h;
};


status_t showTestFrame(Layer_Config* layer, Data_Config* data, bool isGolden) {

    int width = layer->w;
    int height = layer->h;

    if (isGolden)
    {
        width /= 2;
        height /= 2;
    }

    // set buffer size
    native_window_set_buffers_dimensions(layer->win, width, height);

    // set format
    native_window_set_buffers_format(layer->win, data->fmt);

    // set usage software write-able and hardware texture bind-able
    native_window_set_usage(layer->win, GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_HW_TEXTURE);

    // set scaling to match window display size
    native_window_set_scaling_mode(layer->win, NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);

    // set buffer count
    native_window_set_buffer_count(layer->win, 10);

    ANativeWindowBuffer *buf;
    sp<GraphicBuffer>   gb;
    void                *ptr;
    const Rect          rect(width, height);

    int err;
    int fenceFd = -1;
    err = layer->win->dequeueBuffer(layer->win, &buf, &fenceFd);
    sp<Fence> fence1(new Fence(fenceFd));
    fence1->wait(Fence::TIMEOUT_NEVER);
    if(err) {
        ALOGE("%s", strerror(-err));
    }

    gb = new GraphicBuffer(buf, false);

    gb->lock(GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_HW_TEXTURE, rect, &ptr);
    {
        uint32_t bpp = 4;
        uint32_t color = 0;

        if (HAL_PIXEL_FORMAT_RGB_565 == data->fmt)
        {
            color |= ((data->b & 0x1F) << 11);
            color |= ((data->g & 0x3F) << 5);
            color |= ((data->r & 0x1F));
        }
        else
        {
            if (HAL_PIXEL_FORMAT_RGB_888 == data->fmt)
                bpp = 3;

            color |= (data->a << 24);
            color |= (data->b << 16);
            color |= (data->g << 8);
            color |= (data->r);
        }

        if (HAL_PIXEL_FORMAT_RGB_888 == data->fmt)
            bpp = 3;
        else if (HAL_PIXEL_FORMAT_RGB_565 == data->fmt)
            bpp = 2;

        memset(ptr, 255, width * bpp);
        int start = isGolden ? width / 10 : 0;

        for (int i = start; i < width; i++)
        {
            memcpy(ptr + bpp * i, &color, bpp);
        }

        int length = gb->stride * bpp;
        for (int i = 1; i < height; i++)
        {
            memcpy(ptr + (i * length), ptr, length);
        }
    }
    gb->unlock();

    err = layer->win->queueBuffer(layer->win, buf, -1);
    sp<Fence> fence2(new Fence(fenceFd));
    fence2->wait(Fence::TIMEOUT_NEVER);
    if(err) {
        ALOGE("%s", strerror(-err));
    }

    usleep(500 * 1000);
    return NO_ERROR;
}

status_t main(int argc, char** argv) {

    bool opaque = false;
    bool fullGLES = false;
    bool coverage = false;
    int a = 255;
    int f = 0;

    if (argc > 1) {
        int in;
        sscanf(argv[1], "%d", &in);
        if (in >= 0 && in < 5)
            f = in;
        else
        {
            printf("RGBA:0 / RGBX:1 / BGRA:2 / RGB888:3 / RGB565:4\n");
            return 0;
        }
    }

    if (argc > 2) {
        int in;
        sscanf(argv[2], "%d", &in);
        if (in < 256 && in >= 0)
            a = in;
        else
        {
            printf("0 <= alpha <256\n");
            return 0;
        }
    }

    if (argc > 3) {
        for (int i = 3; i < argc; i++)
        {
            char in;
            sscanf(argv[i], "%c", &in);
            switch (in)
            {
                case 'c':
                    coverage = true;
                    break;
                case 'g':
                    fullGLES = true;
                    break;
                case 'o':
                    opaque = true;
                    break;
            }
        }
    }

    // set up the thread-pool
    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    // create a client to surfaceflinger
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    DisplayInfo dinfo;
    sp<IBinder> display = SurfaceComposerClient::getBuiltInDisplay(
            ISurfaceComposer::eDisplayIdMain);
    SurfaceComposerClient::getDisplayInfo(display, &dinfo);
    uint32_t dispw = dinfo.w;
    uint32_t disph = dinfo.h;

    uint32_t baseSurfaceW = dispw;
    uint32_t baseSurfaceH = disph;
    uint32_t testSurfaceW = baseSurfaceW - 400;
    uint32_t testSurfaceH = baseSurfaceH / 4 - 50;

    SurfaceComposerClient::openGlobalTransaction();
    // -----------------------------------------------------------------base-
    sp<SurfaceControl> baseSurfaceControl = client->createSurface(
        String8("baseSurface"), baseSurfaceW, baseSurfaceH, PIXEL_FORMAT_RGB_565, ISurfaceComposerClient::eOpaque);

    sp<Surface> baseSurface = baseSurfaceControl->getSurface();
    ANativeWindow* baseWindow = baseSurface.get();

    baseSurfaceControl->setPosition(0, 0);
    baseSurfaceControl->setLayer(300000);

    Layer_Config baseConfig(baseWindow, baseSurfaceW, baseSurfaceH);
    // ------------------------------------------------------------------------------------
    uint32_t flag = 0;
    if (coverage) flag |= ISurfaceComposerClient::eNonPremultiplied;
    if (opaque)   flag |= ISurfaceComposerClient::eOpaque;

    // --------------------------------------------------------------goldern-
    sp<SurfaceControl> goldernSurfaceControl = client->createSurface(
        String8("goldernSurface"), testSurfaceW, testSurfaceH,
        PIXEL_FORMAT_RGB_565, flag);

    sp<Surface> goldernSurface = goldernSurfaceControl->getSurface();
    ANativeWindow* goldernWindow = goldernSurface.get();

    goldernSurfaceControl->setPosition(200, 200);
    goldernSurfaceControl->setLayer(325000);
    // --------------------------------------------------------------test-
    sp<SurfaceControl> testSurfaceControl = client->createSurface(
        String8("testSurface"), testSurfaceW, testSurfaceH,
        PIXEL_FORMAT_RGB_565, flag);

    sp<Surface> testSurface = testSurfaceControl->getSurface();
    ANativeWindow* testWindow = testSurface.get();

    testSurfaceControl->setPosition(200, 200 + testSurfaceH);
    testSurfaceControl->setLayer(350000);
    // ----------------------------------------------------------------------
    SurfaceComposerClient::closeGlobalTransaction();

    Data_Config data = {HAL_PIXEL_FORMAT_RGBA_8888, 0, 255, 0, 255};
    showTestFrame(&baseConfig, &data, fullGLES);
    // ----------------------------------------------------------------------
    Layer_Config goldernConfig(goldernWindow, testSurfaceW, testSurfaceH);
    Layer_Config testConfig(testWindow, testSurfaceW, testSurfaceH);

    switch(f)
    {
        case 0:
            printf("RGBA_8888\n");
            data = {HAL_PIXEL_FORMAT_RGBA_8888, 0, 0, 255, (uint8_t)a};
            break;
        case 1:
            printf("RGBX_8888\n");
            data = {HAL_PIXEL_FORMAT_RGBX_8888, 0, 0, 255, (uint8_t)a};
            break;

        case 2:
            printf("BGRA_8888\n");
            data = {HAL_PIXEL_FORMAT_BGRA_8888, 0, 0, 255, (uint8_t)a};
            break;

        case 3:
            printf("RGB_888\n");
            data = {HAL_PIXEL_FORMAT_RGB_888, 0, 0, 255, (uint8_t)a};
            break;

        case 4:
            printf("RGB_565\n");
            data = {HAL_PIXEL_FORMAT_RGB_565, 0, 0, 255, (uint8_t)a};
            break;
    }

    if (!coverage)
    {
        printf("HWC_BLENDING_PREMULT ");
        data.r = (uint8_t)((float)data.r * (float)a / 255.0);
        data.g = (uint8_t)((float)data.g * (float)a / 255.0);
        data.b = (uint8_t)((float)data.b * (float)a / 255.0);
    }
    else
    {
        printf("HWC_BLENDING_COVERAGE ");
    }

    printf("/ r:%d  g:%d  b:%d a:%d \n", data.r, data.g, data.b, data.a);
    showTestFrame(&goldernConfig, &data, true);
    showTestFrame(&testConfig, &data, false);
    for (int i = 0; i < 256; i++)
    {
        usleep(32 * 1000);
        SurfaceComposerClient::openGlobalTransaction();
        goldernSurfaceControl->setAlpha((float)i/255);
        testSurfaceControl->setAlpha((float)i/255);
        SurfaceComposerClient::closeGlobalTransaction();
        if (i % 32 == 31) printf("ca=%d \n", i);
    }
    // ----------------------------------------------------------------------

    printf("\ntest complete. CTRL+C to finish.\n");
    IPCThreadState::self()->joinThreadPool();
    return NO_ERROR;
}
