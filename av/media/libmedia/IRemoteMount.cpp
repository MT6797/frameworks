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

#include <gui/IGraphicBufferProducer.h>

#include <media/IRemoteMount.h>

namespace android {

enum {
    DISPOSE = IBinder::FIRST_CALL_TRANSACTION,
    GETCAMERAID,
    SENDCONTROLMESSAGE,
    SENDCAMERAREPEATRESULT,
    SENDCAMERANONREPEATRESULT,
    SETAUDIOOUTPUT,
    SETCAMERAPREVIEWORIENTATION,
    SETSPEAKERROLE,
};

class BpRemoteMount: public BpInterface<IRemoteMount>{
public:
    BpRemoteMount(const sp<IBinder>& impl)
        : BpInterface<IRemoteMount>(impl)
    {
    }

    status_t dispose()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        remote()->transact(DISPOSE, data, &reply);
        return reply.readInt32();
    }

    int getRemoteCameraId()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());

        remote()->transact(GETCAMERAID, data, &reply);
        return reply.readInt32();
    }

    status_t setAudioOutput(bool mute)
     {
         Parcel data, reply;
         data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
         if( mute==true ) {
             data.writeInt32(1);
         } else {
             data.writeInt32(0);
         }

         remote()->transact(SETAUDIOOUTPUT, data, &reply);
         return reply.readInt32();
     }

    status_t setSpeakerRole(int role)
     {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        data.writeInt32(role);
        remote()->transact(SETSPEAKERROLE, data, &reply);
        return reply.readInt32();
     }

    status_t sendControlMessage(const String8& eventDesc) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        data.writeString8(eventDesc);

        remote()->transact(SENDCONTROLMESSAGE, data, &reply);
        return reply.readInt32();
    }

    status_t sendCameraRepeatResult(CameraMetadata& nativePtr) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        data.writeInt32(1); // to mark presence of metadata object
        nativePtr.writeToParcel(&data);

        remote()->transact(SENDCAMERAREPEATRESULT, data, &reply, IBinder::FLAG_ONEWAY);
        data.writeNoException();
        return reply.readInt32();
    }

    status_t sendCameraNonRepeatResult(CameraMetadata& nativePtr, const String8& filePath) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        data.writeInt32(1); // to mark presence of metadata object
        nativePtr.writeToParcel(&data);
        data.writeString8(filePath);


        remote()->transact(SENDCAMERANONREPEATRESULT, data, &reply, IBinder::FLAG_ONEWAY);
        data.writeNoException();
        return reply.readInt32();
    }



    status_t setCameraPreviewOrientation(int angle) {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteMount::getInterfaceDescriptor());
        data.writeInt32(angle); // to mark presence of metadata object
        remote()->transact(SETCAMERAPREVIEWORIENTATION, data, &reply);
        return reply.readInt32();
    }



};

IMPLEMENT_META_INTERFACE(RemoteMount, "android.media.IRemoteMount");

// ----------------------------------------------------------------------

status_t BnRemoteMount::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case DISPOSE: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            reply->writeInt32(dispose());
            return NO_ERROR;
        }
        case GETCAMERAID: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            reply->writeInt32(getRemoteCameraId());
            return NO_ERROR;
        }
        case SETAUDIOOUTPUT: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            bool mute = (data.readInt32()==1)? true: false;
            reply->writeInt32(setAudioOutput(mute));
            return NO_ERROR;
        }
        case SENDCONTROLMESSAGE: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            String8 eventDesc = data.readString8();
            reply->writeInt32(sendControlMessage(eventDesc));
            return NO_ERROR;
        }
        case SENDCAMERAREPEATRESULT: {
            CHECK_INTERFACE(IRemoteMountClient, data, reply);
            CameraMetadata metadata;
            if (data.readInt32() != 0) {
                metadata.readFromParcel(const_cast<Parcel*>(&data));
            } else {
                ALOGW("No metadata object is present in result");
            }
            sendCameraRepeatResult(metadata);
            reply->writeInt32(data.readExceptionCode());;
            return NO_ERROR;
        }
        case SENDCAMERANONREPEATRESULT: {
            CHECK_INTERFACE(IRemoteMountClient, data, reply);
            CameraMetadata metadata;
            if (data.readInt32() != 0) {
                metadata.readFromParcel(const_cast<Parcel*>(&data));
            } else {
                ALOGW("No metadata object is present in result");
            }
            String8 filePath = data.readString8();

            sendCameraNonRepeatResult(metadata,filePath);
            reply->writeInt32(data.readExceptionCode());;
            return NO_ERROR;
        }
        case SETCAMERAPREVIEWORIENTATION: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            reply->writeInt32(setCameraPreviewOrientation(data.readInt32()));
            return NO_ERROR;
        }
        case SETSPEAKERROLE: {
            CHECK_INTERFACE(IRemoteMount, data, reply);
            reply->writeInt32(setSpeakerRole(data.readInt32()));
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
