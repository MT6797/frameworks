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

#ifndef ANDROID_IREMOTEMOUNTCLIENT_H
#define ANDROID_IREMOTEMOUNTCLIENT_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include "camera/CameraMetadata.h"




namespace android {

class IGraphicBufferProducer;
class CameraMetadata;

class IRemoteMountClient : public IInterface {
public:
    DECLARE_META_INTERFACE(RemoteMountClient);

    enum {
        // Flag: The remote display is using a secure transport protocol such as HDCP.
        kDisplayFlagSecure = 1 << 0,
    };

    enum {
        // Error: An unknown / generic error occurred.
        kDisplayErrorUnknown = 1,
        // Error: The connection was dropped unexpectedly.
        kDisplayErrorConnectionDropped = 2,
    };

    // M: add for rtsp generic message define RTSP
    enum {
        kDisplayRtspPlayEvent = 0,
        kDisplayRtspPauseEvent = 1,
        kDisplayRtspTeardownEvent = 2,
    };


    // Indicates that the remote display has been connected successfully.
    // Provides a surface texture that the client should use to stream buffers to
    // the remote display.
    virtual void onRemoteMountConnected(const sp<IGraphicBufferProducer>& bufferProducer,
                                        uint32_t width, uint32_t height, uint32_t flags,
                                        uint32_t session, uint32_t service_port) = 0;  // one-way

    // Indicates that the remote display has been disconnected normally.
    // This method should only be called once the client has called 'dispose()'
    // on the IRemoteDisplay.
    // It is currently an error for the display to disconnect for any other reason.
    virtual void onRemoteMountDisconnected() = 0;  // one-way

    // Indicates that a connection could not be established to the remote display
    // or an unrecoverable error occurred and the connection was severed.
    virtual void onRemoteMountError(int32_t error) = 0;  // one-way

    virtual void onCameraCaptureEvent(int32_t error) = 0;  // one-way

    virtual void onSensorStatusChanged(int type, bool enabled, uint64_t delay) = 0;  // one-way

    virtual void onRepeatRequest(CameraMetadata& metadata) = 0;  // one-way

    // virtual void onNonRepeatRequest(const CameraMetadata& metadata, int info[]) = 0; // one-way

    virtual void onNonRepeatRequest(CameraMetadata& metadata, int info0,
        int info1, int info2, int info3,
        int info4, int info5, int info6, int info7, int info8) = 0;

    virtual void onPreviewOrientation(int32_t angle) =0;
};


// ----------------------------------------------------------------------------

class BnRemoteMountClient : public BnInterface<IRemoteMountClient> {
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

};

#endif // ANDROID_IREMOTEMOUNTCLIENT_H
