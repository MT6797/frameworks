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

package com.android.internal.telephony.cat;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CommandParams;
import com.android.internal.telephony.cat.BipCommandParams;
import com.android.internal.telephony.cat.AppInterface;

/**
 * Class used to pass CAT messages from telephony to application. Application
 * should call getXXX() to get commands's specific values.
 *
 */
public class BipCmdMessage implements Parcelable {
    // members
    CommandDetails mCmdDet;
    private TextMessage mTextMsg;
    public BearerDesc mBearerDesc = null;
    public int mBufferSize = 0;
    public OtherAddress mLocalAddress = null;
    public TransportProtocol mTransportProtocol = null;
    public OtherAddress mDataDestinationAddress = null;

    public String mApn = null;
    public String mLogin = null;
    public String mPwd = null;

    public int mChannelDataLength = 0;
    public int mRemainingDataLength = 0;
    public byte[] mChannelData = null;

    public ChannelStatus mChannelStatusData = null;

    public int mCloseCid = 0;
    public int mSendDataCid = 0;
    public int mReceiveDataCid = 0;
    public boolean mCloseBackToTcpListen = false;
    public int mSendMode = 0;
    public List<ChannelStatus> mChannelStatusList = null;

    public int mInfoType = 0;
    public String mDestAddress = null;
    private SetupEventListSettings mSetupEventListSettings = null;

    public class SetupEventListSettings {
        public int[] eventList;
    }

    BipCmdMessage(CommandParams cmdParams) {
        mCmdDet = cmdParams.mCmdDet;
        switch(getCmdType()) {
        case GET_CHANNEL_STATUS:
            mTextMsg = ((GetChannelStatusParams) cmdParams).textMsg;
            break;
        case OPEN_CHANNEL:
            mBearerDesc = ((OpenChannelParams) cmdParams).bearerDesc;
            mBufferSize = ((OpenChannelParams) cmdParams).bufferSize;
            mLocalAddress = ((OpenChannelParams) cmdParams).localAddress;
            mTransportProtocol = ((OpenChannelParams) cmdParams).transportProtocol;
            mDataDestinationAddress = ((OpenChannelParams) cmdParams).dataDestinationAddress;
            mTextMsg = ((OpenChannelParams) cmdParams).textMsg;

            if (mBearerDesc != null) {
                if (mBearerDesc.bearerType == BipUtils.BEARER_TYPE_GPRS ||
                    mBearerDesc.bearerType == BipUtils.BEARER_TYPE_DEFAULT ||
                    mBearerDesc.bearerType == BipUtils.BEARER_TYPE_EUTRAN) {
                    mApn = ((OpenChannelParams) cmdParams).gprsParams.accessPointName;
                    mLogin = ((OpenChannelParams) cmdParams).gprsParams.userLogin;
                    mPwd = ((OpenChannelParams) cmdParams).gprsParams.userPwd;
                }
            } else {
                CatLog.d("[BIP]", "Invalid BearerDesc object");
            }
            break;
        case CLOSE_CHANNEL:
            mTextMsg = ((CloseChannelParams) cmdParams).textMsg;
            mCloseCid = ((CloseChannelParams) cmdParams).mCloseCid;
            mCloseBackToTcpListen = ((CloseChannelParams) cmdParams).mBackToTcpListen;
            break;
        case RECEIVE_DATA:
            mTextMsg = ((ReceiveDataParams) cmdParams).textMsg;
            mChannelDataLength = ((ReceiveDataParams) cmdParams).channelDataLength;
            mReceiveDataCid = ((ReceiveDataParams) cmdParams).mReceiveDataCid;
            break;
        case SEND_DATA:
            mTextMsg = ((SendDataParams) cmdParams).textMsg;
            mChannelData = ((SendDataParams) cmdParams).channelData;
            mSendDataCid = ((SendDataParams) cmdParams).mSendDataCid;
            mSendMode = ((SendDataParams) cmdParams).mSendMode;
            break;
        case SET_UP_EVENT_LIST:
            mSetupEventListSettings = new SetupEventListSettings();
            mSetupEventListSettings.eventList = ((SetEventListParams) cmdParams).mEventInfo;
            break;
        default:
            break;
        }
    }

    public BipCmdMessage(Parcel in) {
        mCmdDet = in.readParcelable(null);
        mTextMsg = in.readParcelable(null);
        switch (getCmdType()) {
        case SET_UP_EVENT_LIST:
            mSetupEventListSettings = new SetupEventListSettings();
            int length = in.readInt();
            mSetupEventListSettings.eventList = new int[length];
            for (int i = 0; i < length; i++) {
                mSetupEventListSettings.eventList[i] = in.readInt();
            }
            break;
        case OPEN_CHANNEL:
            mBearerDesc = in.readParcelable(null);
            break;
        default:
            break;
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCmdDet, 0);
        dest.writeParcelable(mTextMsg, 0);
        switch(getCmdType()) {
        case SET_UP_EVENT_LIST:
            dest.writeIntArray(mSetupEventListSettings.eventList);
            break;
        case OPEN_CHANNEL:
            dest.writeParcelable(mBearerDesc, 0);
            break;
        default:
            break;
        }
    }

    public static final Parcelable.Creator<BipCmdMessage> CREATOR =
            new Parcelable.Creator<BipCmdMessage>() {
        @Override
        public BipCmdMessage createFromParcel(Parcel in) {
            return new BipCmdMessage(in);
        }

        @Override
        public BipCmdMessage[] newArray(int size) {
            return new BipCmdMessage[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /* external API to be used by application */
    /**
     * Return command qualifier
     * @internal
     */
    public int getCmdQualifier() {
        return mCmdDet.commandQualifier;
    }

    public AppInterface.CommandType getCmdType() {
        return AppInterface.CommandType.fromInt(mCmdDet.typeOfCommand);
    }

    /**
     * Return bearer description
     * @internal
     */
    public BearerDesc getBearerDesc() {
        return mBearerDesc;
    }
}
