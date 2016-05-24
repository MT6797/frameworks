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

#include <stdint.h>
#include <sys/types.h>

#include <media/IRemoteMountClient.h>
#include <gui/IGraphicBufferProducer.h>
#include <utils/String8.h>
#include "camera/CameraMetadata.h"

namespace android {

enum {
    ON_DISPLAY_CONNECTED = IBinder::FIRST_CALL_TRANSACTION,
    ON_DISPLAY_DISCONNECTED,
    ON_DISPLAY_ERROR,
    ON_CAPTURE_EVENT,
    ON_SENSOR_STATUS_CHANGED,
    ON_REPEAT_REQUEST,
    ON_NONREPEAT_REQUEST,
    ON_PREVIEW_ORIENTATION,
};


class BpRemoteMountClient: public BpInterface<IRemoteMountClient> {
public:
    BpRemoteMountClient(const sp<IBinder>& impl)
        : BpInterface<IRemoteMountClient>(impl) {
    }

    void onRemoteMountConnected(const sp<IGraphicBufferProducer>& bufferProducer,
                                uint32_t width, uint32_t height, uint32_t flags,
                                uint32_t session, uint32_t service_port) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeStrongBinder(IInterface::asBinder(bufferProducer));
        data.writeInt32(width);
        data.writeInt32(height);
        data.writeInt32(flags);
        data.writeInt32(session);
        data.writeInt32(service_port);
        remote()->transact(ON_DISPLAY_CONNECTED, data, &reply, IBinder::FLAG_ONEWAY);
    }

    void onRemoteMountDisconnected() {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        remote()->transact(ON_DISPLAY_DISCONNECTED, data, &reply, IBinder::FLAG_ONEWAY);
    }

    void onRemoteMountError(int32_t error) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(error);
        remote()->transact(ON_DISPLAY_ERROR, data, &reply, IBinder::FLAG_ONEWAY);
    }

    void onCameraCaptureEvent(int32_t error) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(error);
        remote()->transact(ON_CAPTURE_EVENT, data, &reply, IBinder::FLAG_ONEWAY);
    }
    void  onSensorStatusChanged(int type, bool enabled, uint64_t delay) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(type);
        data.writeInt32(enabled);
        data.writeInt64(delay);
        remote()->transact(ON_SENSOR_STATUS_CHANGED, data, &reply, IBinder::FLAG_ONEWAY);
    }
    void onRepeatRequest(CameraMetadata& metadata) {
        // ALOGV("onRepeatRequest");
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(1); // to mark presence of metadata object
        metadata.writeToParcel(&data);

        remote()->transact(ON_REPEAT_REQUEST, data, &reply, IBinder::FLAG_ONEWAY);
        data.writeNoException();
    }
    void onNonRepeatRequest(CameraMetadata& metadata, int info0, int info1,int info2,int info3,
        int info4,int info5,int info6,int info7,int info8) {
        // ALOGV("onRepeatRequest");
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(1);  // to mark presence of metadata object
        metadata.writeToParcel(&data);

        data.writeInt32(info0);
        data.writeInt32(info1);
        data.writeInt32(info2);
        data.writeInt32(info3);
        data.writeInt32(info4);
        data.writeInt32(info5);
        data.writeInt32(info6);
        data.writeInt32(info7);
        data.writeInt32(info8);

        ALOGD("IRemoteMountClient onNonRepeatRequest info0 = %d", info0);
        ALOGD("IRemoteMountClient onNonRepeatRequest info1 = %d", info1);
        ALOGD("IRemoteMountClient onNonRepeatRequest info2 = %d", info2);
        ALOGD("IRemoteMountClient onNonRepeatRequest info3 = %d", info3);
        ALOGD("IRemoteMountClient onNonRepeatRequest info4 = %d", info4);
        ALOGD("IRemoteMountClient onNonRepeatRequest info5 = %d", info5);
        ALOGD("IRemoteMountClient onNonRepeatRequest info8 = %d", info8);

        remote()->transact(ON_NONREPEAT_REQUEST, data, &reply, IBinder::FLAG_ONEWAY);
        data.writeNoException();
    }
    void onPreviewOrientation(int32_t angle) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMountClient::getInterfaceDescriptor());
        data.writeInt32(angle);
        remote()->transact(ON_PREVIEW_ORIENTATION, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(RemoteMountClient, "android.media.IRemoteMountClient");

// ----------------------------------------------------------------------

status_t BnRemoteMountClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    switch (code) {
    case ON_DISPLAY_CONNECTED: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        sp<IGraphicBufferProducer> surfaceTexture(
            interface_cast<IGraphicBufferProducer>(data.readStrongBinder()));
        uint32_t width = data.readInt32();
        uint32_t height = data.readInt32();
        uint32_t flags = data.readInt32();
        uint32_t session = data.readInt32();
        uint32_t service_port = data.readInt32();
        onRemoteMountConnected(surfaceTexture, width, height, flags, session, service_port);
        return NO_ERROR;
    }
    case ON_DISPLAY_DISCONNECTED: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        onRemoteMountDisconnected();
        return NO_ERROR;
    }
    case ON_DISPLAY_ERROR: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        int32_t error = data.readInt32();
        onRemoteMountError(error);
        return NO_ERROR;
    }
    case ON_CAPTURE_EVENT: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        int32_t event = data.readInt32();
        onCameraCaptureEvent(event);
        return NO_ERROR;
    }
    case ON_SENSOR_STATUS_CHANGED: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        int32_t type = data.readInt32();
        int32_t enabled = (bool)data.readInt32();
        uint64_t delay = data.readInt64();
        onSensorStatusChanged(type, enabled, delay);
        return NO_ERROR;
    }
    case ON_REPEAT_REQUEST:{
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        CameraMetadata metadata;
        if (data.readInt32() != 0) {
            metadata.readFromParcel(const_cast<Parcel*>(&data));
        } else {
            ALOGW("No metadata object is present in result");
        }
        onRepeatRequest(metadata);
        data.readExceptionCode();
        return NO_ERROR;
    }
    case ON_NONREPEAT_REQUEST:{
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        CameraMetadata metadata;
        if (data.readInt32() != 0) {
            metadata.readFromParcel(const_cast<Parcel*>(&data));
        } else {
            ALOGW("No metadata object is present in result");
        }
        int info0 = data.readInt32();
        int info1 = data.readInt32();
        int info2 = data.readInt32();
        int info3 = data.readInt32();
        int info4 = data.readInt32();
        int info5 = data.readInt32();
        int info6 = data.readInt32();
        int info7 = data.readInt32();
        int info8 = data.readInt32();

        onNonRepeatRequest(metadata, info0, info1, info2, info3,info4,
            info5,info6,info7,info8);
        data.readExceptionCode();
    }
    case ON_PREVIEW_ORIENTATION: {
        CHECK_INTERFACE(IRemoteMountClient, data, reply);
        int32_t angle = data.readInt32();
        onPreviewOrientation(angle);
        return NO_ERROR;
    }
    default:
        return BBinder::onTransact(code, data, reply, flags);
    }
}
}; // namespace android
