/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006-2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.telephony.cat;

import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.Iterator;
import android.os.SystemProperties;
import android.text.TextUtils;

import com.android.internal.telephony.cat.AppInterface.CommandType;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

import com.android.internal.telephony.cat.BipUtils;
import com.android.internal.telephony.cat.ChannelStatus;
import com.android.internal.telephony.cat.BearerDesc;
import com.android.internal.telephony.cat.DefaultBearerDesc;
import com.android.internal.telephony.cat.EUTranBearerDesc;
import com.android.internal.telephony.cat.GPRSBearerDesc;

abstract class BipResponseData extends ResponseData{

}

class OpenChannelResponseDataEx extends OpenChannelResponseData {
    int mProtocolType = -1;
    OpenChannelResponseDataEx(ChannelStatus channelStatus, BearerDesc bearerDesc, int bufferSize,
                              int protocolType) {
        super(channelStatus, bearerDesc, bufferSize);
        CatLog.d("[BIP]", "OpenChannelResponseDataEx-constructor: protocolType " + protocolType);
        mProtocolType = protocolType;
    }
    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            CatLog.e("[BIP]", "OpenChannelResponseDataEx-format: buf is null");
            return;
        }
        if (BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE == mProtocolType ||
           BipUtils.TRANSPORT_PROTOCOL_UDP_REMOTE == mProtocolType) {
            if (null == mBearerDesc) {
                CatLog.e("[BIP]", "OpenChannelResponseDataEx-format: bearer null");
                return;
            } else if ((mBearerDesc.bearerType != BipUtils.BEARER_TYPE_GPRS) &&
                       (mBearerDesc.bearerType != BipUtils.BEARER_TYPE_DEFAULT) &&
                       (mBearerDesc.bearerType != BipUtils.BEARER_TYPE_EUTRAN)) {
                CatLog.e("[BIP]", "OpenChannelResponseDataEx-format: bearer type is not gprs");
            }
        }
        int tag;
        int length = 0;
        if (mChannelStatus != null) {
            CatLog.d("[BIP]", "OpenChannelResponseDataEx-format: Write channel status into TR");
            tag = ComprehensionTlvTag.CHANNEL_STATUS.value();
            buf.write(tag);
            length = 0x02;
            buf.write(length);
            buf.write(mChannelStatus.mChannelId | mChannelStatus.mChannelStatus); //For TCP status
            buf.write(mChannelStatus.mChannelStatusInfo);
            CatLog.d("[BIP]", "OpenChannel Channel status Rsp:tag[" + tag + "],len[" + length +
                     "],cId[" + mChannelStatus.mChannelId + "],status[" +
                     mChannelStatus.mChannelStatus + "]");
        } else {
            CatLog.d("[BIP]", "No Channel status in TR.");
        }
        if (mBearerDesc != null) {
            /*6.8, only required in response to OPEN CHANNEL proactive commands,
                        where Bearer description is mandatory in the command.*/
            if (BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE == mProtocolType ||
               BipUtils.TRANSPORT_PROTOCOL_UDP_REMOTE == mProtocolType) {
                CatLog.d("[BIP]", "Write bearer description into TR. bearerType: " +
                         mBearerDesc.bearerType);
                tag = ComprehensionTlvTag.BEARER_DESCRIPTION.value();
                buf.write(tag);
                if (BipUtils.BEARER_TYPE_GPRS == mBearerDesc.bearerType) {
                    if (mBearerDesc instanceof GPRSBearerDesc) {
                        GPRSBearerDesc gprsBD = (GPRSBearerDesc) mBearerDesc;
                        length = 0x07;
                        buf.write(length);
                        buf.write(gprsBD.bearerType);
                        buf.write(gprsBD.precedence);
                        buf.write(gprsBD.delay);
                        buf.write(gprsBD.reliability);
                        buf.write(gprsBD.peak);
                        buf.write(gprsBD.mean);
                        buf.write(gprsBD.pdpType);
                        CatLog.d("[BIP]", "OpenChannelResponseDataEx-format: tag: " + tag
                                + ",length: " + length
                                + ",bearerType: " + gprsBD.bearerType
                                + ",precedence: " + gprsBD.precedence
                                + ",delay: " + gprsBD.delay
                                + ",reliability: " + gprsBD.reliability
                                + ",peak: " + gprsBD.peak
                                + ",mean: " + gprsBD.mean
                                + ",pdp type: " + gprsBD.pdpType);
                    } else {
                        CatLog.d("[BIP]", "Not expected GPRSBearerDesc instance");
                    }
                } else if (BipUtils.BEARER_TYPE_EUTRAN == mBearerDesc.bearerType) {
                    int[] bufferArr = new int[10];
                    int index = 0;
                    if (mBearerDesc instanceof EUTranBearerDesc) {
                        EUTranBearerDesc euTranBD = (EUTranBearerDesc) mBearerDesc;
                        if (euTranBD.QCI != 0) {
                            bufferArr[index] = euTranBD.QCI; index++;
                        }
                        if (euTranBD.maxBitRateU != 0) {
                            bufferArr[index] = euTranBD.maxBitRateU; index++;
                        }
                        if (euTranBD.maxBitRateD != 0) {
                            bufferArr[index] = euTranBD.maxBitRateD; index++;
                        }
                        if (euTranBD.guarBitRateU != 0) {
                            bufferArr[index] = euTranBD.guarBitRateU; index++;
                        }
                        if (euTranBD.guarBitRateD != 0) {
                            bufferArr[index] = euTranBD.guarBitRateD; index++;
                        }
                        if (euTranBD.maxBitRateUEx != 0) {
                            bufferArr[index] = euTranBD.maxBitRateUEx; index++;
                        }
                        if (euTranBD.maxBitRateDEx != 0) {
                            bufferArr[index] = euTranBD.maxBitRateDEx; index++;
                        }
                        if (euTranBD.guarBitRateUEx != 0) {
                            bufferArr[index] = euTranBD.guarBitRateUEx; index++;
                        }
                        if (euTranBD.guarBitRateDEx != 0) {
                            bufferArr[index] = euTranBD.guarBitRateDEx; index++;
                        }
                        if (euTranBD.pdnType != 0) {
                            bufferArr[index] = euTranBD.pdnType; index++;
                        }
                        CatLog.d("[BIP]", "EUTranBearerDesc length: " + index);
                        if (0 < index) {
                            buf.write(index + 1);
                        } else {
                            buf.write(1);
                        }
                        buf.write(euTranBD.bearerType);
                        for (int i = 0; i < index; i++) {
                            buf.write(bufferArr[i]);
                            CatLog.d("[BIP]", "EUTranBearerDesc buf: " + bufferArr[i]);
                        }
                    } else {
                        CatLog.d("[BIP]", "Not expected EUTranBearerDesc instance");
                    }
                } else if (BipUtils.BEARER_TYPE_DEFAULT == mBearerDesc.bearerType) {
                    buf.write(1);
                    buf.write(((DefaultBearerDesc) mBearerDesc).bearerType);
                }
            }
        } else {
            CatLog.d("[BIP]", "No bearer description in TR.");
        }
        if (mBufferSize >= 0) {
            CatLog.d("[BIP]", "Write buffer size into TR.[" + mBufferSize + "]");
            tag = ComprehensionTlvTag.BUFFER_SIZE.value();
            buf.write(tag);
            length = 0x02;
            buf.write(length);
            buf.write(mBufferSize >> 8);
            buf.write(mBufferSize & 0xff);
            CatLog.d("[BIP]", "OpenChannelResponseDataEx-format: tag: " + tag
                    + ",length: " + length
                    + ",buffer size(hi-byte): " + (mBufferSize >> 8)
                    + ",buffer size(low-byte): " + (mBufferSize & 0xff));
        } else {
            CatLog.d("[BIP]", "No buffer size in TR.[" + mBufferSize + "]");
        }
    }

}
class OpenChannelResponseData extends ResponseData {
    ChannelStatus mChannelStatus = null;
    BearerDesc mBearerDesc = null;
    int mBufferSize = 0;

    OpenChannelResponseData(ChannelStatus channelStatus, BearerDesc bearerDesc, int bufferSize) {
        super();
        if (channelStatus != null) {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: channelStatus cid/status : "
                    + channelStatus.mChannelId + "/" + channelStatus.mChannelStatus);
        } else {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: channelStatus is null");
        }
        if (bearerDesc != null) {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: bearerDesc bearerType"
                    + bearerDesc.bearerType);
        } else {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: bearerDesc is null");
        }
        if (bufferSize > 0) {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: buffer size is " + bufferSize);
        } else {
            CatLog.d("[BIP]", "OpenChannelResponseData-constructor: bearerDesc is invalid "
                    + bufferSize);
        }

        mChannelStatus = channelStatus;
        mBearerDesc = bearerDesc;
        mBufferSize = bufferSize;
    }

    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            CatLog.d("[BIP]", "OpenChannelResponseData-format: buf is null");
            return;
        }

        if (mBearerDesc == null) {
            CatLog.e("[BIP]", "OpenChannelResponseData-format: mBearerDesc is null");
            return;
        }

        if (((GPRSBearerDesc) mBearerDesc).bearerType != BipUtils.BEARER_TYPE_GPRS) {
            CatLog.d("[BIP]", "OpenChannelResponseData-format: bearer type is not gprs");
            return;
        }

        int tag;

        if (/* mChannelStatus != null && */ mBufferSize > 0) {
            if (mChannelStatus != null) {
                CatLog.d("[BIP]", "OpenChannelResponseData-format: Write channel status into TR");
                tag = ComprehensionTlvTag.CHANNEL_STATUS.value();
                CatLog.d("[BIP]", "OpenChannelResponseData-format: tag: " + tag);
                buf.write(tag);
                CatLog.d("[BIP]", "OpenChannelResponseData-format: length: " + 0x02);
                buf.write(0x02);
                CatLog.d("[BIP]", "OpenChannelResponseData-format: channel id & isActivated: "
                        + (mChannelStatus.mChannelId | (mChannelStatus.isActivated ? 0x80 : 0x00)));
                buf.write(mChannelStatus.mChannelId | (mChannelStatus.isActivated ? 0x80 : 0x00));
                CatLog.d("[BIP]", "OpenChannelResponseData-format: channel status: "
                        + mChannelStatus.mChannelStatus);
                buf.write(mChannelStatus.mChannelStatus);
            }

            CatLog.d("[BIP]", "Write bearer description into TR");
            tag = ComprehensionTlvTag.BEARER_DESCRIPTION.value();
            CatLog.d("[BIP]", "OpenChannelResponseData-format: tag: " + tag);
            buf.write(tag);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: length: " + 0x07);
            buf.write(0x07);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: bearer type: "
                    + ((GPRSBearerDesc) mBearerDesc).bearerType);
            buf.write(((GPRSBearerDesc) mBearerDesc).bearerType);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: precedence: "
                    + ((GPRSBearerDesc) mBearerDesc).precedence);
            buf.write(((GPRSBearerDesc) mBearerDesc).precedence);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: delay: " +
                     ((GPRSBearerDesc) mBearerDesc).delay);
            buf.write(((GPRSBearerDesc) mBearerDesc).delay);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: reliability: "
                    + ((GPRSBearerDesc) mBearerDesc).reliability);
            buf.write(((GPRSBearerDesc) mBearerDesc).reliability);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: peak: " +
                     ((GPRSBearerDesc) mBearerDesc).peak);
            buf.write(((GPRSBearerDesc) mBearerDesc).peak);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: mean: " +
                     ((GPRSBearerDesc) mBearerDesc).mean);
            buf.write(((GPRSBearerDesc) mBearerDesc).mean);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: pdp type: " +
                     ((GPRSBearerDesc) mBearerDesc).pdpType);
            buf.write(((GPRSBearerDesc) mBearerDesc).pdpType);

            CatLog.d("[BIP]", "Write buffer size into TR");
            tag = ComprehensionTlvTag.BUFFER_SIZE.value();
            CatLog.d("[BIP]", "OpenChannelResponseData-format: tag: " + tag);
            buf.write(tag);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: length: " + 0x02);
            buf.write(0x02);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: length(hi-byte): "
                    + (mBufferSize >> 8));
            buf.write(mBufferSize >> 8);
            CatLog.d("[BIP]", "OpenChannelResponseData-format: length(low-byte): "
                    + (mBufferSize & 0xff));
            buf.write(mBufferSize & 0xff);
        } else {
            CatLog.d("[BIP]", "Miss ChannelStatus, BearerDesc or BufferSize");
        }
    }
}

class SendDataResponseData extends ResponseData {
    int mTxBufferSize = 0;

    SendDataResponseData(int size) {
        super();
        mTxBufferSize = size;
    }

    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            return;
        }

        int tag;

        tag = 0x80 | ComprehensionTlvTag.CHANNEL_DATA_LENGTH.value();
        buf.write(tag);
        buf.write(1);
        if (mTxBufferSize >= 0xFF) {
            buf.write(0xFF);
        } else {
            buf.write(mTxBufferSize);
        }
    }
}

class ReceiveDataResponseData extends ResponseData {
    byte[] mData = null;
    int mRemainingCount = 0;

    ReceiveDataResponseData(byte[] data, int remaining) {
        super();
        mData = data;
        mRemainingCount = remaining;
    }

    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            return;
        }

        int tag;

        tag = 0x80 | ComprehensionTlvTag.CHANNEL_DATA.value();
        buf.write(tag);

        if (mData != null) {
            if (mData.length >= 0x80) {
                buf.write(0x81);
            }

            buf.write(mData.length);
            buf.write(mData, 0, mData.length);
        } else {
            buf.write(0);
        }

        tag = 0x80 | ComprehensionTlvTag.CHANNEL_DATA_LENGTH.value();
        buf.write(tag);
        buf.write(0x01);

        CatLog.d("[BIP]", "ReceiveDataResponseData: length: " + mRemainingCount);

        if (mRemainingCount >= 0xFF) {
            buf.write(0xFF);
        } else {
            buf.write(mRemainingCount);
        }
    }
}

class GetMultipleChannelStatusResponseData extends ResponseData {
    ArrayList mArrList = null;

    GetMultipleChannelStatusResponseData(ArrayList arrList) {
        mArrList = arrList;
    }

    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            return;
        }

        int tag = 0x80 | ComprehensionTlvTag.CHANNEL_STATUS.value();
        CatLog.d("[BIP]", "ChannelStatusResp: size: " + mArrList.size());

        if (0 < mArrList.size()) {
            Iterator iterator = mArrList.iterator();
            ChannelStatus chStatus = null;
            while (iterator.hasNext()) {
                buf.write(tag);
                buf.write(0x02);
                chStatus = (ChannelStatus) iterator.next();
                buf.write((chStatus.mChannelId & 0x07) | (chStatus.mChannelStatus));
                buf.write(chStatus.mChannelStatusInfo);
                CatLog.d("[BIP]", "ChannelStatusResp: cid:" + chStatus.mChannelId + ",status:" +
                         chStatus.mChannelStatus + ",info:" + chStatus.mChannelStatusInfo);
            }
        } else { //No channel available, link not established or PDP context not activated.
            CatLog.d("[BIP]", "ChannelStatusResp: no channel status.");
            buf.write(tag);
            buf.write(0x02);
            buf.write(0x00);
            buf.write(0x00);
        }
    }
}

class GetChannelStatusResponseData extends ResponseData {
    int mChannelId = 0;
    int mChannelStatus = 0;
    int mChannelStatusInfo = 0;

    GetChannelStatusResponseData(int cid, int status, int statusInfo) {
        mChannelId = cid;
        mChannelStatus = status;
        mChannelStatusInfo = statusInfo;
    }

    @Override
    public void format(ByteArrayOutputStream buf) {
        if (buf == null) {
            return;
        }

        int tag = 0x80 | ComprehensionTlvTag.CHANNEL_STATUS.value();
        buf.write(tag);
        buf.write(0x02);
        buf.write((mChannelId & 0x07) | mChannelStatus);
        buf.write(mChannelStatusInfo);
    }
}
// ICS Migration end

