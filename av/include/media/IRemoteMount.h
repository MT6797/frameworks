/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef MTK_IREMOTEMOUNTT_H
#define MTK_IREMOTEMOUNTT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <camera/CameraMetadata.h>

namespace android {

/*
 * Represents a remote display, such as a Wifi display.
 *
 * When the remote display is created, it may not yet be connected to the
 * display.  The remote display asynchronously reports events such as successful
 * connection, disconnection and errors to an IRemoteDisplayClient interface provided by
 * the client.
 */
class IRemoteMount : public IInterface
{
public:

    DECLARE_META_INTERFACE(RemoteMount);

    // Disconnects the remote display and stops listening for new connections.
    virtual status_t dispose() = 0;
    virtual int getRemoteCameraId() =0;
    virtual int sendControlMessage(const String8& eventDesc) =0;
    virtual status_t sendCameraRepeatResult(CameraMetadata& nativePtr) =0;
    virtual status_t sendCameraNonRepeatResult(CameraMetadata& nativePtr,const String8& filePath) =0;

    virtual status_t setAudioOutput(bool mute) =0;
    virtual status_t setCameraPreviewOrientation(int angle) =0;
    virtual status_t setSpeakerRole(int role) =0;
};


// ----------------------------------------------------------------------------

class BnRemoteMount : public BnInterface<IRemoteMount>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif //MTK_IREMOTEMOUNTT_H