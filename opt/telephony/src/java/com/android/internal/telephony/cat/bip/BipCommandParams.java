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

import android.graphics.Bitmap;
import com.android.internal.telephony.cat.OtherAddress;
import com.android.internal.telephony.cat.TransportProtocol;
import com.android.internal.telephony.cat.BearerDesc;

/**
 * Container class for proactive command parameters.
 *
 */
class BipCommandParams {

}

class OpenChannelParams extends CommandParams {
    public BearerDesc bearerDesc = null;
    public int bufferSize = 0;
    public OtherAddress localAddress = null;
    public TransportProtocol transportProtocol = null;
    public OtherAddress dataDestinationAddress = null;
    public TextMessage textMsg = null;

    public GprsParams gprsParams = null;

    OpenChannelParams(CommandDetails cmdDet,
            BearerDesc bearerDesc, int size, OtherAddress localAddress,
            TransportProtocol transportProtocol, OtherAddress address,
            String apn, String login, String pwd, TextMessage textMsg) {
        super(cmdDet);
        this.bearerDesc = bearerDesc;
        this.bufferSize = size;
        this.localAddress = localAddress;
        this.transportProtocol = transportProtocol;
        this.dataDestinationAddress = address;
        this.textMsg = textMsg;
        this.gprsParams = new GprsParams(apn, login, pwd);
    }

    public class GprsParams {
        public String accessPointName = null;
        public String userLogin = null;
        public String userPwd = null;

        GprsParams(String apn, String login, String pwd) {
            this.accessPointName = apn;
            this.userLogin = login;
            this.userPwd = pwd;
        }
    }
}

class CloseChannelParams extends CommandParams {
    TextMessage textMsg = new TextMessage();
    int mCloseCid = 0;
    boolean mBackToTcpListen = false;

    CloseChannelParams(CommandDetails cmdDet, int cid, TextMessage textMsg,
                       boolean backToTcpListen) {
        super(cmdDet);
        this.textMsg = textMsg;
        mCloseCid = cid;
        mBackToTcpListen = backToTcpListen;
    }
}

class ReceiveDataParams extends CommandParams {
    int channelDataLength = 0;
    TextMessage textMsg = new TextMessage();
    int mReceiveDataCid = 0;

    ReceiveDataParams(CommandDetails cmdDet, int length, int cid, TextMessage textMsg) {
        super(cmdDet);
        this.channelDataLength = length;
        this.textMsg = textMsg;
        this.mReceiveDataCid = cid;
    }
}

class SendDataParams extends CommandParams {
    byte[] channelData = null;
    TextMessage textMsg = new TextMessage();
    int mSendDataCid = 0;
    int mSendMode = 0;

    SendDataParams(CommandDetails cmdDet, byte[] data, int cid, TextMessage textMsg, int sendMode) {
        super(cmdDet);
        this.channelData = data;
        this.textMsg = textMsg;
        mSendDataCid = cid;
        mSendMode =  sendMode;
    }
}

class GetChannelStatusParams extends CommandParams {
    TextMessage textMsg = new TextMessage();

    GetChannelStatusParams(CommandDetails cmdDet, TextMessage textMsg) {
        super(cmdDet);
        this.textMsg = textMsg;
    }
}
