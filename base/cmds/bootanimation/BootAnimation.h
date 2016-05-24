/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_BOOTANIMATION_H
#define ANDROID_BOOTANIMATION_H

#include <stdint.h>
#include <sys/types.h>

#include <androidfw/AssetManager.h>
#include <utils/Thread.h>

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <media/mediaplayer.h>

class SkBitmap;

namespace android {

class AudioPlayer;
class Surface;
class SurfaceComposerClient;
class SurfaceControl;

// ---------------------------------------------------------------------------
enum boot_video_play_type{
     BOOT_VIDEO_PLAY_REPEAT, // repeat to play until boot completing.
     BOOT_VIDEO_PLAY_FULL, // play a full video no matter if boot is complete.
     BOOT_VIDEO_PLAY_ONCE_WAIT, // play a video once even if boot is not complete.
};



class BootAnimation : public Thread, public IBinder::DeathRecipient
{
public:
                BootAnimation();
                BootAnimation(bool bSetBootOrShutDown,bool bSetPlayMP3,bool bSetRotated);
    virtual     ~BootAnimation();
    void        setBootVideoPlayState(int playState);

    sp<SurfaceComposerClient> session() const;

private:
    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();
    virtual void        binderDied(const wp<IBinder>& who);

    struct Texture {
        GLint   w;
        GLint   h;
        GLuint  name;
    };

    struct Animation {
        struct Frame {
            String8 name;
            FileMap* map;
            String8 fullPath;
            mutable GLuint tid;
            bool operator < (const Frame& rhs) const {
                return name < rhs.name;
            }
        };
        struct Part {
            int count;
            int pause;
            String8 path;
            SortedVector<Frame> frames;
            bool playUntilComplete;
            float backgroundColor[3];
            FileMap* audioFile;
        };
        int fps;
        int width;
        int height;
        Vector<Part> parts;
    };

    status_t initTexture(Texture* texture, AssetManager& asset, const char* name);
    status_t initTexture(const Animation::Frame& frame);
    bool android();
    bool readFile(const char* name, String8& outString);
    bool movie();

    void checkExit();

    sp<SurfaceComposerClient>       mSession;
    sp<AudioPlayer>                 mAudioPlayer;
    AssetManager mAssets;
    Texture     mAndroid[2];
    int         mWidth;
    int         mHeight;
    EGLDisplay  mDisplay;
    EGLDisplay  mContext;
    EGLDisplay  mSurface;
    sp<SurfaceControl> mFlingerSurfaceControl;
    sp<Surface> mFlingerSurface;
    ZipFileRO   *mZip;

    status_t initTexture(const char* EntryName);
    void initBootanimationZip();
    void initShutanimationZip();
    const char* initAudioPath();
    bool ETC1movie();
    void initShader();
    GLuint buildShader(const char* source, GLenum shaderType);
    GLuint buildProgram (const char* vertexShaderSource, const char* fragmentShaderSource);

    bool bBootOrShutDown;
    bool bShutRotate;
    bool bPlayMP3;
    GLuint mProgram;
    GLint mAttribPosition;
    GLint mAttribTexCoord;
    GLint mUniformTexture;
    bool bETC1Movie;
    int mBootVideoPlayType;
    int mBootVideoPlayState;
};

class BootVideoListener: public MediaPlayerListener{
public:
                BootVideoListener(const sp<BootAnimation> &player);
    virtual     ~BootVideoListener();
    virtual void notify(int msg, int ext1, int ext2, const Parcel *obj);
    sp<BootAnimation> mBootanim;

};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_BOOTANIMATION_H
