/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
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

package com.android.internal.telephony.cat;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.uicc.IccUtils;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;

import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.DATA_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.CHANNEL_STATUS_EVENT;

public class BipService {
    private static final boolean DBG = true;
    private static BipService[] mInstance = null;

    private Handler mHandler = null;
    private BipCmdMessage mCurrentCmd = null;
    private BipCmdMessage mCmdMessage = null;

    private Context mContext = null;
    //private Phone mPhone = null;
    private ConnectivityManager mConnMgr = null;

    BearerDesc mBearerDesc = null;
    int mBufferSize = 0;
    OtherAddress mLocalAddress = null;
    TransportProtocol mTransportProtocol = null;
    OtherAddress mDataDestinationAddress = null;
    int mLinkMode = 0;
    boolean mAutoReconnected = false;
    private final Object mCloseLock = new Object();

    String mApn = null;
    String mLogin = null;
    String mPassword = null;

    final int NETWORK_TYPE = ConnectivityManager.TYPE_MOBILE;

    private int mChannelStatus = BipUtils.CHANNEL_STATUS_UNKNOWN;
    private int mChannelId = 1;
    private Channel mChannel = null;
    private ChannelStatus mChannelStatusDataObject = null;
    private boolean isParamsValid = false;
    private int mSlotId = -1;
    private static int mSimCount = 0;
    private boolean mIsApnInserting = false;
    private boolean  mIsListenDataAvailable = false;
    private boolean  mIsListenChannelStatus = false;
    protected CatCmdMessage mCurrentSetupEventCmd = null;

    protected static final int MSG_ID_BIP_CONN_MGR_TIMEOUT            = 10;
    protected static final int MSG_ID_BIP_CONN_DELAY_TIMEOUT          = 11;
    protected static final int MSG_ID_BIP_DISCONNECT_TIMEOUT          = 12;
    protected static final int MSG_ID_OPEN_CHANNEL_DONE                = 13;
    protected static final int MSG_ID_SEND_DATA_DONE                   = 14;
    protected static final int MSG_ID_RECEIVE_DATA_DONE                = 15;
    protected static final int MSG_ID_CLOSE_CHANNEL_DONE               = 16;
    protected static final int MSG_ID_GET_CHANNEL_STATUS_DONE         = 17;
    protected static final int MSG_ID_BIP_PROACTIVE_COMMAND           = 18;
    protected static final int MSG_ID_EVENT_NOTIFY                      = 19;
    protected static final int MSG_ID_RIL_MSG_DECODED                  = 20;

    private static final int DEV_ID_KEYPAD      = 0x01;
    private static final int DEV_ID_DISPLAY     = 0x02;
    private static final int DEV_ID_UICC        = 0x81;
    private static final int DEV_ID_TERMINAL    = 0x82;
    private static final int DEV_ID_NETWORK     = 0x83;

    static final int ADDITIONAL_INFO_FOR_BIP_NO_SPECIFIC_CAUSE = 0x00;
    static final int ADDITIONAL_INFO_FOR_BIP_NO_CHANNEL_AVAILABLE = 0x01;
    static final int ADDITIONAL_INFO_FOR_BIP_CHANNEL_CLOSED = 0x02;
    static final int ADDITIONAL_INFO_FOR_BIP_CHANNEL_ID_NOT_AVAILABLE = 0x03;
    static final int ADDITIONAL_INFO_FOR_BIP_REQUESTED_BUFFER_SIZE_NOT_AVAILABLE = 0x04;
    static final int ADDITIONAL_INFO_FOR_BIP_SECURITY_ERROR = 0x05;
    static final int ADDITIONAL_INFO_FOR_BIP_REQUESTED_INTERFACE_TRANSPORT_LEVEL_NOT_AVAILABLE =
            0x06;

    private static final int CONN_MGR_TIMEOUT = 50 * 1000;
    private static final int CONN_DELAY_TIMEOUT = 5 * 1000;
    private boolean isConnMgrIntentTimeout = false;
    private BipChannelManager mBipChannelManager = null;
    private BipRilMessageDecoder mBipMsgDecoder = null;
    private BipCmdMessage mCurrntCmd = null;
    private CommandsInterface mCmdIf = null;
    private boolean mIsOpenInProgress = false;
    private boolean mIsCloseInProgress = false;
    private boolean mIsNetworkAvailableReceived = false;
    private static final String PROPERTY_PDN_REUSE = "ril.pdn.reuse";
    private static final String PROPERTY_OVERRIDE_APN = "ril.pdn.overrideApn";
    private static final String BIP_NAME = "__M-BIP__";
    private Network mNetwork;
    // The callback to register when we request BIP network
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    // This is really just for using the capability
    private NetworkRequest mNetworkRequest = null;

    private Handler mBipSrvHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Message timerMsg = null;
            CatLog.d(this, "handleMessage[" + msg.what + "]");
            BipCmdMessage cmd = null;
            ResponseData resp = null;
            int ret = 0;

            switch(msg.what) {
            case MSG_ID_BIP_PROACTIVE_COMMAND:
            case MSG_ID_EVENT_NOTIFY:
                CatLog.d(this, "ril message arrived, slotid: " + mSlotId);
                String data = null;
                if (msg.obj != null) {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.result != null) {
                        try {
                            data = (String) ar.result;
                        } catch (ClassCastException e) {
                            break;
                        }
                    }
                }
                mBipMsgDecoder.sendStartDecodingMessageParams(new RilMessage(msg.what, data));
                break;
            case MSG_ID_RIL_MSG_DECODED:
                handleRilMsg((RilMessage) msg.obj);
                break;
            case MSG_ID_OPEN_CHANNEL_DONE:
                ret = msg.arg1;
                cmd = (BipCmdMessage) msg.obj;
                // resp = new OpenChannelResponseData(cmd.mChannelStatus,
                // cmd.mBearerDesc, cmd.mBufferSize);
                if (mCurrentCmd == null) {
                    CatLog.d("[BIP]", "SS-handleMessage: " +
                             "skip open channel response because current cmd is null");
                    break;
                } else if (mCurrentCmd != null) {
                    if (mCurrentCmd.mCmdDet.typeOfCommand !=
                        AppInterface.CommandType.OPEN_CHANNEL.value()) {
                        CatLog.d("[BIP]", "SS-handleMessage: " +
                        "skip open channel response because current cmd type is not OPEN_CHANNEL");
                        break;
                    }
                }
                if (ret == ErrorValue.NO_ERROR) {
                    resp = new OpenChannelResponseDataEx(cmd.mChannelStatusData, cmd.mBearerDesc,
                            cmd.mBufferSize, cmd.mTransportProtocol.protocolType);
                    CatLog.d("[BIP]", "SS-handleMessage: open channel successfully");
                    sendTerminalResponse(mCurrentCmd.mCmdDet, ResultCode.OK, false, 0, resp);
                } else if (ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                    resp = new OpenChannelResponseDataEx(cmd.mChannelStatusData, cmd.mBearerDesc,
                            cmd.mBufferSize, cmd.mTransportProtocol.protocolType);
                    CatLog.d("[BIP]", "SS-handleMessage: open channel with modified parameters");
                    sendTerminalResponse(mCurrentCmd.mCmdDet, ResultCode.PRFRMD_WITH_MODIFICATION,
                            false, 0, resp);
                } else if (ret == ErrorValue.ME_IS_BUSY_ON_CALL) {
                    resp = new OpenChannelResponseDataEx(null, cmd.mBearerDesc, cmd.mBufferSize,
                            cmd.mTransportProtocol.protocolType);
                    CatLog.d("[BIP]", "SS-handleMessage: ME is busy on call");
                    sendTerminalResponse(mCurrentCmd.mCmdDet,
                            ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, true,
                            ADDITIONAL_INFO_FOR_BIP_CHANNEL_CLOSED, resp);
                } else {
                    resp = new OpenChannelResponseDataEx(cmd.mChannelStatusData, cmd.mBearerDesc,
                            cmd.mBufferSize, cmd.mTransportProtocol.protocolType);
                    CatLog.d("[BIP]", "SS-handleMessage: open channel failed");
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, false, 0, resp);
                }
                break;
            case MSG_ID_SEND_DATA_DONE:
                ret = msg.arg1;
                int size = msg.arg2;
                cmd = (BipCmdMessage) msg.obj;
                resp = new SendDataResponseData(size);
                if (ret == ErrorValue.NO_ERROR) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.OK, false, 0, resp);
                } else if (ret == ErrorValue.CHANNEL_ID_NOT_VALID) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, true,
                            ADDITIONAL_INFO_FOR_BIP_CHANNEL_ID_NOT_AVAILABLE, null);
                } else {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, false, 0, resp);
                }
                break;
            case MSG_ID_RECEIVE_DATA_DONE:
                ret = msg.arg1;
                cmd = (BipCmdMessage) msg.obj;
                byte[] buffer = cmd.mChannelData;
                int remainingCount = cmd.mRemainingDataLength;

                resp = new ReceiveDataResponseData(buffer, remainingCount);
                if (ret == ErrorValue.NO_ERROR) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.OK, false, 0, resp);
                } else if (ret == ErrorValue.MISSING_DATA) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.PRFRMD_WITH_MISSING_INFO, false,
                            0, resp);
                } else {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, false, 0, null);
                }
                break;
            case MSG_ID_CLOSE_CHANNEL_DONE:
                cmd = (BipCmdMessage) msg.obj;
                if (msg.arg1 == ErrorValue.NO_ERROR) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.OK, false, 0, null);
                } else if (msg.arg1 == ErrorValue.CHANNEL_ID_NOT_VALID) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, true,
                            ADDITIONAL_INFO_FOR_BIP_CHANNEL_ID_NOT_AVAILABLE, null);
                } else if (msg.arg1 == ErrorValue.CHANNEL_ALREADY_CLOSED) {
                    sendTerminalResponse(cmd.mCmdDet, ResultCode.BIP_ERROR, true,
                            ADDITIONAL_INFO_FOR_BIP_CHANNEL_CLOSED, null);
                }
                break;
            case MSG_ID_GET_CHANNEL_STATUS_DONE:
                ArrayList arrList = null;
                ret = msg.arg1;
                cmd = (BipCmdMessage) msg.obj;
                arrList = (ArrayList) cmd.mChannelStatusList; //(BipCmdMessage) msg.obj;

                CatLog.d("[BIP]", "SS-handleCmdResponse: MSG_ID_GET_CHANNEL_STATUS_DONE:" +
                         arrList.size());
                resp = new GetMultipleChannelStatusResponseData(arrList);
                sendTerminalResponse(cmd.mCmdDet, ResultCode.OK, false, 0, resp);
                break;
            case MSG_ID_BIP_CONN_MGR_TIMEOUT:
                CatLog.d("[BIP]", "handleMessage MSG_ID_BIP_CONN_MGR_TIMEOUT");
                isConnMgrIntentTimeout = true;
                disconnect();
                break;
            case MSG_ID_BIP_CONN_DELAY_TIMEOUT:
                CatLog.d("[BIP]", "handleMessage MSG_ID_BIP_CONN_DELAY_TIMEOUT");
                acquireNetwork();
                break;
            case MSG_ID_BIP_DISCONNECT_TIMEOUT:
                CatLog.d("[BIP]", "handleMessage MSG_ID_BIP_DISCONNECT_TIMEOUT");
                synchronized (mCloseLock) {
                    if (true == mIsCloseInProgress) {
                        mIsCloseInProgress = false;
                        timerMsg = mBipSrvHandler.obtainMessage(MSG_ID_CLOSE_CHANNEL_DONE,
                                ErrorValue.NO_ERROR, 0, mCurrentCmd);
                        mBipSrvHandler.sendMessage(timerMsg);
                    }
                }
                break;
            }
        }
    };

    public BipService(Context context, Handler handler, int sim_id) {
        CatLog.d("[BIP]", "Construct BipService");

        if (context == null) {
            CatLog.e("[BIP]", "Fail to construct BipService");
            return;
        }

        mContext = context;
        mSlotId = sim_id;
        CatLog.d("[BIP]", "Construct instance sim id: " + sim_id);
        mConnMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHandler = handler;
        mBipChannelManager = new BipChannelManager();

        IntentFilter connFilter = new IntentFilter();
        connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mNetworkConnReceiver, connFilter);

        // During normal booting, it must make sure there is no APN whose
        // name is "BIP_NAME" in the APN list. This may affect the initial
        // PDP or PDN attach.
        Thread t = new Thread() {
            public void run() {
                deleteApnParams();
            }
        };
        t.start();
    }

    public BipService(Context context, Handler handler, int sim_id, CommandsInterface cmdIf,
            IccFileHandler fh) {
        CatLog.d("[BIP]", "Construct BipService " + sim_id);

        if (context == null) {
            CatLog.e("[BIP]", "Fail to construct BipService");
            return;
        }

        mContext = context;
        mSlotId = sim_id;
        mCmdIf = cmdIf;
        mConnMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHandler = handler;
        mBipChannelManager = new BipChannelManager();
        mBipMsgDecoder = BipRilMessageDecoder.getInstance(mBipSrvHandler, fh, mSlotId);
        if (null == mBipMsgDecoder) {
            CatLog.d(this, "Null BipRilMessageDecoder instance");
            return;
        }
        mBipMsgDecoder.start();

        cmdIf.setOnBipProactiveCmd(mBipSrvHandler, MSG_ID_BIP_PROACTIVE_COMMAND, null);
        IntentFilter connFilter = new IntentFilter();
        connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mNetworkConnReceiver, connFilter);

        // During normal booting, it must make sure there is no APN whose
        // name is "BIP_NAME" in the APN list. This may affect the initial
        // PDP or PDN attach.
        Thread t = new Thread() {
            public void run() {
                deleteApnParams();
            }
        };
        t.start();
    }

    private ConnectivityManager getConnectivityManager() {
        if (mConnMgr == null) {
            mConnMgr = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }
        return mConnMgr;
    }

    public static BipService getInstance(Context context, Handler handler, int simId) {
        CatLog.d("[BIP]", "getInstance sim : " + simId);
        if (null == mInstance) {
            mSimCount = TelephonyManager.getDefault().getSimCount();
            mInstance = new BipService[mSimCount];
            for (int i = 0; i < mSimCount; i++) {
                mInstance[i] = null;
            }
        }
        if (simId < PhoneConstants.SIM_ID_1 || simId > mSimCount) {
            CatLog.d("[BIP]", "getInstance invalid sim : " + simId);
            return null;
        }
        if (null == mInstance[simId]) {
            mInstance[simId] = new BipService(context, handler, simId);
        }
        return mInstance[simId];
    }

   public static BipService getInstance(Context context, Handler handler,
           int simId, CommandsInterface cmdIf, IccFileHandler fh) {
       CatLog.d("[BIP]", "getInstance sim : " + simId);
       if (null == mInstance) {
           mSimCount = TelephonyManager.getDefault().getSimCount();
           mInstance = new BipService[mSimCount];
           for (int i = 0; i < mSimCount; i++) {
               mInstance[i] = null;
           }
       }
       if (simId < PhoneConstants.SIM_ID_1 || simId > mSimCount) {
           CatLog.d("[BIP]", "getInstance invalid sim : " + simId);
           return null;
       }
       if (null == mInstance[simId]) {
           mInstance[simId] = new BipService(context, handler, simId, cmdIf, fh);
       }
       return mInstance[simId];
   }

   public void dispose() {
       int i = 0;
       CatLog.d("[BIP]", "dispose slotId : " + mSlotId);
       if (null != mInstance) {
           if (null != mInstance[mSlotId]) {
               mInstance[mSlotId] = null;
           }
           // Check if all mInstance[] is null
           for (i = 0 ; i < mSimCount ; i++) {
                 if (null != mInstance[i]) {
                     break;
                 }
           }
           // All mInstance[] has been null, set mInstance as null
           if (i == mSimCount) {
                 mInstance = null;
           }
       }
    }

    private void handleRilMsg(RilMessage rilMsg) {
        if (rilMsg == null) {
            return;
        }

        // dispatch messages
        CommandParams cmdParams = null;
        switch (rilMsg.mId) {
        case MSG_ID_BIP_PROACTIVE_COMMAND:
            try {
                cmdParams = (CommandParams) rilMsg.mData;
            } catch (ClassCastException e) {
                // for error handling : cast exception
                CatLog.d(this, "Fail to parse proactive command");
                // Don't send Terminal Resp if command detail is not available
                if (mCurrntCmd != null) {
                    sendTerminalResponse(mCurrntCmd.mCmdDet, ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                                     false, 0x00, null);
                }
                break;
            }
            if (cmdParams != null) {
                if (rilMsg.mResCode == ResultCode.OK) {
                    handleCommand(cmdParams, true);
                } else {
                    // for proactive commands that couldn't be decoded
                    // successfully respond with the code generated by the
                    // message decoder.
                    sendTerminalResponse(cmdParams.mCmdDet, rilMsg.mResCode,
                            false, 0, null);
                }
            }
            break;
        }
    }
    private void checkPSEvent(CatCmdMessage cmdMsg) {
        mIsListenDataAvailable = false;
        mIsListenChannelStatus = false;
        for (int eventVal: cmdMsg.getSetEventList().eventList) {
            CatLog.d(this,"Event: " + eventVal);
            switch (eventVal) {
                /* Currently android is supporting only the below events in SetupEventList
                 * Language Selection.  */
                case DATA_AVAILABLE_EVENT:
                    mIsListenDataAvailable = true;
                    break;
                case CHANNEL_STATUS_EVENT:
                    mIsListenChannelStatus = true;
                    break;
                default:
                    break;
            }
        }
    }
    void setSetupEventList(CatCmdMessage cmdMsg) {
        mCurrentSetupEventCmd = cmdMsg;
        checkPSEvent(cmdMsg);
    }
    boolean hasPsEvent(int eventId) {
        switch (eventId) {
            /* Currently android is supporting only the below events in SetupEventList
             * Language Selection.  */
            case DATA_AVAILABLE_EVENT:
                return mIsListenDataAvailable;
            case CHANNEL_STATUS_EVENT:
                return mIsListenChannelStatus;
            default:
                break;
        }
        return false;
    }
    /**
     * Handles RIL_UNSOL_STK_EVENT_NOTIFY or RIL_UNSOL_STK_BIP_PROACTIVE_COMMAND command
     * from RIL.
     * Sends valid proactive command data to the application using intents.
     * RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE will be send back if the command is
     * from RIL_UNSOL_STK_BIP_PROACTIVE_COMMAND.
     */
    private void handleCommand(CommandParams cmdParams, boolean isProactiveCmd) {
        CatLog.d(this, cmdParams.getCommandType().name());

        CharSequence message;
        BipCmdMessage cmdMsg = new BipCmdMessage(cmdParams);
        Message response = null;

        switch (cmdParams.getCommandType()) {
            case OPEN_CHANNEL:
                CatLog.d(this, "SS-handleProactiveCommand: process OPEN_CHANNEL");
                PhoneConstants.State call_state = PhoneConstants.State.IDLE;
                CallManager callmgr = CallManager.getInstance();
                int subId[] = SubscriptionManager.getSubId(mSlotId);
                int phoneId = SubscriptionManager.getPhoneId(subId[0]);
                Phone myPhone = PhoneFactory.getPhone(phoneId);
                if (myPhone == null) {
                    CatLog.d(this, "myPhone is still null");
                    break;
                }
                if (myPhone.getServiceState().getNetworkType() <=
                        TelephonyManager.NETWORK_TYPE_EDGE && null != callmgr) {
                    call_state = callmgr.getState();
                    CatLog.d(this, "call_state" + call_state);
                    if (call_state != PhoneConstants.State.IDLE) {
                        CatLog.d(this, "SS-handleProactiveCommand: ME is busy on call");
                        cmdMsg.mChannelStatusData = new ChannelStatus(getFreeChannelId(),
                                ChannelStatus.CHANNEL_STATUS_NO_LINK,
                                ChannelStatus.CHANNEL_STATUS_INFO_NO_FURTHER_INFO);
                        cmdMsg.mChannelStatusData.mChannelStatus =
                                ChannelStatus.CHANNEL_STATUS_NO_LINK;
                        mCurrentCmd = cmdMsg;
                        response = mBipSrvHandler.obtainMessage(MSG_ID_OPEN_CHANNEL_DONE,
                                ErrorValue.ME_IS_BUSY_ON_CALL, 0, cmdMsg);
                        response.sendToTarget();
                        return;
                    }
                } else {
                    CatLog.d(this, "SS-handleProactiveCommand: type:" +
                            myPhone.getServiceState().getNetworkType() + ",or null callmgr");
                }
                response = mBipSrvHandler.obtainMessage(MSG_ID_OPEN_CHANNEL_DONE);
                openChannel(cmdMsg, response);
                break;
            case CLOSE_CHANNEL:
                CatLog.d(this, "SS-handleProactiveCommand: process CLOSE_CHANNEL");
                response = mBipSrvHandler.obtainMessage(MSG_ID_CLOSE_CHANNEL_DONE);
                closeChannel(cmdMsg, response);
            break;
            case RECEIVE_DATA:
                CatLog.d(this, "SS-handleProactiveCommand: process RECEIVE_DATA");
                response = mBipSrvHandler.obtainMessage(MSG_ID_RECEIVE_DATA_DONE);
                receiveData(cmdMsg, response);
            break;
            case SEND_DATA:
                CatLog.d(this, "SS-handleProactiveCommand: process SEND_DATA");
                response = mBipSrvHandler.obtainMessage(MSG_ID_SEND_DATA_DONE);
                sendData(cmdMsg, response);
            break;
            case GET_CHANNEL_STATUS:
                CatLog.d(this, "SS-handleProactiveCommand: process GET_CHANNEL_STATUS");
                mCmdMessage = cmdMsg;
                response = mBipSrvHandler.obtainMessage(MSG_ID_GET_CHANNEL_STATUS_DONE);
                getChannelStatus(cmdMsg, response);
            break;
            default:
                CatLog.d(this, "Unsupported command");
                return;
        }
        mCurrntCmd = cmdMsg;
    }

    private void sendTerminalResponse(CommandDetails cmdDet,
            ResultCode resultCode, boolean includeAdditionalInfo,
            int additionalInfo, ResponseData resp) {

        if (cmdDet == null) {
            CatLog.e(this, "SS-sendTR: cmdDet is null");
            return;
        }

        CatLog.d(this, "SS-sendTR: command type is " + cmdDet.typeOfCommand);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // command details
        int tag = ComprehensionTlvTag.COMMAND_DETAILS.value();
        if (cmdDet.compRequired) {
            tag |= 0x80;
        }
        buf.write(tag);
        buf.write(0x03); // length
        buf.write(cmdDet.commandNumber);
        buf.write(cmdDet.typeOfCommand);
        buf.write(cmdDet.commandQualifier);

        // device identities
        // According to TS102.223/TS31.111 section 6.8 Structure of
        // TERMINAL RESPONSE, "For all SIMPLE-TLV objects with Min=N,
        // the ME should set the CR(comprehension required) flag to
        // comprehension not required.(CR=0)"
        // Since DEVICE_IDENTITIES and DURATION TLVs have Min=N,
        // the CR flag is not set.
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_TERMINAL); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // result
        tag = ComprehensionTlvTag.RESULT.value();
        if (cmdDet.compRequired) {
            tag |= 0x80;
        }
        buf.write(tag);
        int length = includeAdditionalInfo ? 2 : 1;
        buf.write(length);
        buf.write(resultCode.value());

        // additional info
        if (includeAdditionalInfo) {
            buf.write(additionalInfo);
        }

        // Fill optional data for each corresponding command
        if (resp != null) {
            CatLog.d(this, "SS-sendTR: write response data into TR");
            resp.format(buf);
        } else {
            //encodeOptionalTags(cmdDet, resultCode, cmdInput, buf);
            CatLog.d(this, "SS-sendTR: null resp.");
        }

        byte[] rawData = buf.toByteArray();
        String hexString = IccUtils.bytesToHexString(rawData);
        if (DBG) {
            CatLog.d(this, "TERMINAL RESPONSE: " + hexString);
        }

        mCmdIf.sendTerminalResponse(hexString, null);
    }

    private int getDataConnectionFromSetting() {
        int currentDataConnectionSimId = -1;

        currentDataConnectionSimId =  Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.GPRS_CONNECTION_SETTING,
                Settings.System.GPRS_CONNECTION_SETTING_DEFAULT) - 1;
        CatLog.d("[BIP]", "Default Data Setting value=" + currentDataConnectionSimId);

        return currentDataConnectionSimId;
    }
    private void connect() {
        int ret = ErrorValue.NO_ERROR;
        CatLog.d("[BIP]", "establishConnect");
        /*
        if(requestRouteToHost() == false) {
            CatLog.d("[BIP]", "requestNetwork: Fail - requestRouteToHost");
            ret = ErrorValue.NETWORK_CURRENTLY_UNABLE_TO_PROCESS_COMMAND;
        }
        */
        mCurrentCmd.mChannelStatusData.isActivated = true;

        CatLog.d("[BIP]", "requestNetwork: establish data channel");
        ret = establishLink();

        Message response = null;
        if (ret != ErrorValue.WAIT_OPEN_COMPLETED) {
            if (ret == ErrorValue.NO_ERROR ||
                ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                CatLog.d("[BIP]", "1 channel is activated");
                updateCurrentChannelStatus(ChannelStatus.CHANNEL_STATUS_LINK);
            } else {
                CatLog.d("[BIP]", "2 channel is un-activated");
                updateCurrentChannelStatus(ChannelStatus.CHANNEL_STATUS_NO_LINK);
            }
            mIsOpenInProgress = false;
            mIsNetworkAvailableReceived = false;
            response = mBipSrvHandler.obtainMessage(MSG_ID_OPEN_CHANNEL_DONE, ret, 0, mCurrentCmd);
            mBipSrvHandler.sendMessage(response);
        }
    }

    private void disconnect() {
        int ret = ErrorValue.NO_ERROR;
        Message response = null;

        CatLog.d("[BIP]", "disconnect: opening ? " + mIsOpenInProgress);

        deleteApnParams();

        if (true == mIsOpenInProgress &&
                mChannelStatus != BipUtils.CHANNEL_STATUS_OPEN) {
            Channel channel = mBipChannelManager.getChannel(mChannelId);
            ret = ErrorValue.NETWORK_CURRENTLY_UNABLE_TO_PROCESS_COMMAND;

            if(null != channel) {
                channel.closeChannel();
                mBipChannelManager.removeChannel(mChannelId);
            } else {
                mBipChannelManager.releaseChannelId(mChannelId, mTransportProtocol.protocolType);
            }

            mChannelStatus = BipUtils.CHANNEL_STATUS_CLOSE;
            mCurrentCmd.mChannelStatusData.mChannelStatus = ChannelStatus.CHANNEL_STATUS_NO_LINK;
            mCurrentCmd.mChannelStatusData.isActivated = false;
            mIsOpenInProgress = false;
            response = mBipSrvHandler.obtainMessage(MSG_ID_OPEN_CHANNEL_DONE, ret, 0, mCurrentCmd);
            mBipSrvHandler.sendMessage(response);
        } else {
            int i = 0;
            ArrayList<Byte> alByte = new ArrayList<Byte>();
            byte[] additionalInfo = null;
            CatLog.d("[BIP]", "this is a drop link");
            mChannelStatus = BipUtils.CHANNEL_STATUS_CLOSE;

            CatResponseMessage resMsg =
                    new CatResponseMessage(CHANNEL_STATUS_EVENT);

            for (i = 1; i <= BipChannelManager.MAXCHANNELID; i++) {
                if (true == mBipChannelManager.isChannelIdOccupied(i)) {
                    try {
                        Channel channel = mBipChannelManager.getChannel(i);
                        CatLog.d("[BIP]", "channel protocolType:" + channel.mProtocolType);
                        if (BipUtils.TRANSPORT_PROTOCOL_UDP_REMOTE == channel.mProtocolType ||
                                BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE == channel.mProtocolType) {
                            releaseRequest(mNetworkCallback);
                            resetLocked();
                            channel.closeChannel();
                            mBipChannelManager.removeChannel(i);
                            //additionalInfo[firstIdx] = (byte) 0xB8; // Channel status
                            alByte.add((byte)0xB8);
                            //additionalInfo[firstIdx+1] = 0x02;
                            alByte.add((byte)0x02);
                            //additionalInfo[firstIdx+2] =
                            //(byte) (channel.mChannelId | ChannelStatus.CHANNEL_STATUS_NO_LINK);
                            alByte.add((byte)(channel.mChannelId |
                                    ChannelStatus.CHANNEL_STATUS_NO_LINK));
                            //additionalInfo[firstIdx+3] =
                            //ChannelStatus.CHANNEL_STATUS_INFO_LINK_DROPED;
                            alByte.add((byte)ChannelStatus.CHANNEL_STATUS_INFO_LINK_DROPED);
                        }
                    } catch (NullPointerException ne){
                        CatLog.e("[BIP]", "NPE, channel null.");
                        ne.printStackTrace();
                    }
                }
            }
            if (alByte.size() > 0) {
                additionalInfo = new byte[alByte.size()];
                for (i = 0; i < additionalInfo.length; i++) {
                    additionalInfo[i] = alByte.get(i);
                }
                resMsg.setSourceId(0x82);
                resMsg.setDestinationId(0x81);
                resMsg.setAdditionalInfo(additionalInfo);
                resMsg.setOneShot(false);
                resMsg.setEventDownload(CHANNEL_STATUS_EVENT, additionalInfo);
                CatLog.d("[BIP]", "onEventDownload: for channel status");
                ((CatService)mHandler).onEventDownload(resMsg);
            } else {
                CatLog.d("[BIP]", "onEventDownload: No client channels are opened.");
            }
        }
    }

    public void acquireNetwork(){
        int result = PhoneConstants.APN_TYPE_NOT_AVAILABLE;
        int ret = ErrorValue.NO_ERROR;

        mIsOpenInProgress = true;
        if (mNetwork != null) {
            // Already available
            CatLog.d("[BIP]", "acquireNetwork: already available");
            Channel channel = mBipChannelManager.getChannel(mChannelId);
            if (null == channel) {
                connect();
            }
            return;
        }

        CatLog.d("[BIP]", "requestNetwork: slotId " + mSlotId);
        newRequest();
    }

    public void openChannel(BipCmdMessage cmdMsg, Message response) {
        int result = PhoneConstants.APN_TYPE_NOT_AVAILABLE;
        CatLog.d("[BIP]", "BM-openChannel: enter");
        int ret = ErrorValue.NO_ERROR;
        Channel channel = null;

        // In current design, the 3G/4G capability is bonded to data preference
        // setting. Before open channel, we need to check if data preference
        // belongs to current SIM. If not, we can expect PDP/PDN will be failed.
        // So directly reject the open channel command.
        // To avoid affect normal case or test case, we just consider the inserted
        // SIM number is two or more condition.
        if (false == checkDataCapability(cmdMsg)) {
            cmdMsg.mChannelStatusData = new ChannelStatus(0,
                ChannelStatus.CHANNEL_STATUS_NO_LINK,
                ChannelStatus.CHANNEL_STATUS_INFO_NO_FURTHER_INFO);
            response.arg1 = ErrorValue.BIP_ERROR;
            response.obj = cmdMsg;
            mBipSrvHandler.sendMessage(response);
            return;
        }

        CatLog.d("[BIP]", "BM-openChannel: init channel status object");

        isConnMgrIntentTimeout = false;

        if (cmdMsg.mTransportProtocol == null) {
            CatLog.e("[BIP]", "BM-openChannel: transport protocol is null");
            return;
        }

        mChannelId = mBipChannelManager.acquireChannelId(cmdMsg.mTransportProtocol.protocolType);
        if(0 == mChannelId) {
            CatLog.d("[BIP]", "BM-openChannel: acquire channel id = 0");
            response.arg1 = ErrorValue.BIP_ERROR;
            response.obj = cmdMsg;
            mCurrentCmd = cmdMsg;
            mBipSrvHandler.sendMessage(response);
            return;
        }
        cmdMsg.mChannelStatusData = new ChannelStatus(mChannelId,
                                                 ChannelStatus.CHANNEL_STATUS_NO_LINK,
                                                 ChannelStatus.CHANNEL_STATUS_INFO_NO_FURTHER_INFO);
        mCurrentCmd = cmdMsg;

        mBearerDesc = cmdMsg.mBearerDesc;
        if(cmdMsg.mBearerDesc != null) {
            CatLog.d("[BIP]", "BM-openChannel: bearer type " + cmdMsg.mBearerDesc.bearerType);
        } else {
            CatLog.d("[BIP]", "BM-openChannel: bearer type is null");
        }

        mBufferSize = cmdMsg.mBufferSize;
        CatLog.d("[BIP]", "BM-openChannel: buffer size " + cmdMsg.mBufferSize);

        mLocalAddress = cmdMsg.mLocalAddress;
        if(cmdMsg.mLocalAddress != null) {
            CatLog.d("[BIP]", "BM-openChannel: local address " +
                     cmdMsg.mLocalAddress.address.toString());
        } else {
            CatLog.d("[BIP]", "BM-openChannel: local address is null");
        }

        mTransportProtocol = cmdMsg.mTransportProtocol;
        CatLog.d("[BIP]", "BM-openChannel: transport protocol type/port "
                + cmdMsg.mTransportProtocol.protocolType + "/"
                + cmdMsg.mTransportProtocol.portNumber);

        mDataDestinationAddress = cmdMsg.mDataDestinationAddress;
        if(cmdMsg.mDataDestinationAddress != null) {
            CatLog.d("[BIP]", "BM-openChannel: dest address " +
                     cmdMsg.mDataDestinationAddress.address.toString());
        } else {
            CatLog.d("[BIP]", "BM-openChannel: dest address is null");
        }

        mApn = cmdMsg.mApn;
        if (cmdMsg.mApn != null) {
            CatLog.d("[BIP]", "BM-openChannel: apn " + cmdMsg.mApn);
        } else {
            CatLog.d("[BIP]", "BM-openChannel: apn is null.");
        }

        mLogin = cmdMsg.mLogin;
        CatLog.d("[BIP]", "BM-openChannel: login " + cmdMsg.mLogin);
        mPassword = cmdMsg.mPwd;
        CatLog.d("[BIP]", "BM-openChannel: password " + cmdMsg.mPwd);

        mLinkMode = ((cmdMsg.getCmdQualifier() & 0x01) == 1) ?
                BipUtils.LINK_ESTABLISHMENT_MODE_IMMEDIATE :
                BipUtils.LINK_ESTABLISHMENT_MODE_ONDEMMAND;

        CatLog.d("[BIP]", "BM-openChannel: mLinkMode " + cmdMsg.getCmdQualifier());

        mAutoReconnected = ((cmdMsg.getCmdQualifier() & 0x02) == 0) ? false : true;

        String isPdnReuse = SystemProperties.get(PROPERTY_PDN_REUSE);
        CatLog.d("[BIP]", "BM-openChannel: isPdnReuse: " + isPdnReuse);

        if (null != mBearerDesc) {
            if (mBearerDesc.bearerType == BipUtils.BEARER_TYPE_DEFAULT) {
                /* default bearer -> enable initial attach apn reuse. */
                SystemProperties.set(PROPERTY_PDN_REUSE, "2");
            } else {
                /* Not default bearer -> disable initial attach apn reuse. */
                if (mApn != null && mApn.length() > 0) {
                    CatLog.d("[BIP]", "BM-openChannel: override apn: " + mApn);
                    SystemProperties.set(PROPERTY_OVERRIDE_APN, mApn);
                }
                SystemProperties.set(PROPERTY_PDN_REUSE, "0");
            }
        } else {
            if ((BipUtils.TRANSPORT_PROTOCOL_SERVER != mTransportProtocol.protocolType)
                && (BipUtils.TRANSPORT_PROTOCOL_UDP_LOCAL != mTransportProtocol.protocolType)
                && (BipUtils.TRANSPORT_PROTOCOL_TCP_LOCAL != mTransportProtocol.protocolType)) {
                CatLog.e("[BIP]", "BM-openChannel: miss bearer info.");
                response.arg1 = ErrorValue.BIP_ERROR;
                response.obj = mCurrentCmd;
                mBipSrvHandler.sendMessage(response);
                return;
            }
        }

        //if(mBearerDesc.bearerType == BipUtils.BEARER_TYPE_GPRS) {
        //  CatLog.d("[BIP]", "BM-openChannel: Set QoS params");
        //  SystemProperties.set(BipUtils.KEY_QOS_PRECEDENCE, String.valueOf(
        //  mBearerDesc.precedence));
        //  SystemProperties.set(BipUtils.KEY_QOS_DELAY, String.valueOf(mBearerDesc.delay));
        //  SystemProperties.set(BipUtils.KEY_QOS_RELIABILITY, String.valueOf(
        //  mBearerDesc.reliability));
        //  SystemProperties.set(BipUtils.KEY_QOS_PEAK, String.valueOf(mBearerDesc.peak));
        //  SystemProperties.set(BipUtils.KEY_QOS_MEAN, String.valueOf(mBearerDesc.mean));
        //}

        setApnParams(mApn, mLogin, mPassword);
        SystemProperties.set("gsm.stk.bip", "1");

        // Wait for APN is ready. This is a tempoarily solution

        CatLog.d("[BIP]", "BM-openChannel: call startUsingNetworkFeature:" + mSlotId);
        CatLog.d("[BIP]", "MAXCHANNELID :" + BipChannelManager.MAXCHANNELID);

        if(BipUtils.TRANSPORT_PROTOCOL_SERVER == mTransportProtocol.protocolType) {
            ret = establishLink();

            if (ret == ErrorValue.NO_ERROR || ret ==
                ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                CatLog.d("[BIP]", "BM-openChannel: channel is activated");
                channel = mBipChannelManager.getChannel(mChannelId);
                cmdMsg.mChannelStatusData.mChannelStatus =
                        channel.mChannelStatusData.mChannelStatus;
            } else {
                CatLog.d("[BIP]", "BM-openChannel: channel is un-activated");
                cmdMsg.mChannelStatusData.mChannelStatus = BipUtils.TCP_STATUS_CLOSE;
            }

            response.arg1 = ret;
            response.obj = mCurrentCmd;
            mBipSrvHandler.sendMessage(response);
        } else {
            /* Update APN db will result from apn change(deactivate->activate pdn)
             * after calling startUsingNetworkFeature(),
             * so we should delay a while to make sure update db is earlier than
             * calling startUsingNetworkFeature. */
            if (true == mIsApnInserting) {
                CatLog.d("[BIP]", "BM-openChannel: startUsingNetworkFeature delay trigger.");
                Message timerMsg = mBipSrvHandler.obtainMessage(MSG_ID_BIP_CONN_DELAY_TIMEOUT);
                timerMsg.obj = cmdMsg;
                mBipSrvHandler.sendMessageDelayed(timerMsg, CONN_DELAY_TIMEOUT);
                mIsApnInserting = false;
            } else {
                // If APN is not being inserted(In case APN is null), not need to send a
                // delay message(MSG_ID_BIP_CONN_DELAY_TIMEOUT). It can directly call
                // acquireNetwork()
                acquireNetwork();
            }
        }
        CatLog.d("[BIP]", "BM-openChannel: exit");
    }

    public void closeChannel(BipCmdMessage cmdMsg, Message response) {
        CatLog.d("[BIP]", "BM-closeChannel: enter");

        Channel lChannel = null;
        int cId = cmdMsg.mCloseCid;

        response.arg1 = ErrorValue.NO_ERROR;
        mCurrentCmd = cmdMsg;
        if(0 > cId || BipChannelManager.MAXCHANNELID < cId){
            CatLog.d("[BIP]", "BM-closeChannel: channel id is wrong");
            response.arg1 = ErrorValue.CHANNEL_ID_NOT_VALID;
        } else {
            try {
                if (BipUtils.CHANNEL_STATUS_UNKNOWN ==
                    mBipChannelManager.getBipChannelStatus(cId)) {
                    response.arg1 = ErrorValue.CHANNEL_ID_NOT_VALID;
                } else if(BipUtils.CHANNEL_STATUS_CLOSE ==
                          mBipChannelManager.getBipChannelStatus(cId)) {
                    response.arg1 = ErrorValue.CHANNEL_ALREADY_CLOSED;
                } else {
                    lChannel = mBipChannelManager.getChannel(cId);
                    if(null == lChannel) {
                        CatLog.d("[BIP]", "BM-closeChannel: channel has already been closed");
                        response.arg1 = ErrorValue.CHANNEL_ID_NOT_VALID;
                    } else { //null != lChannel
                        //mConnMgr.stopUsingNetworkFeature(NETWORK_TYPE, Phone.FEATURE_ENABLE_SUPL);
                        TcpServerChannel tcpSerCh = null;
                        if (BipUtils.TRANSPORT_PROTOCOL_SERVER == lChannel.mProtocolType) {
                            if (lChannel instanceof TcpServerChannel) {
                                tcpSerCh = (TcpServerChannel)lChannel;
                                tcpSerCh.setCloseBackToTcpListen(cmdMsg.mCloseBackToTcpListen);
                            }
                        } else {
                            CatLog.d("[BIP]", "BM-closeChannel: stop data connection");
                            mIsCloseInProgress = true;
                            releaseRequest(mNetworkCallback);
                            resetLocked();
                        }
                        deleteApnParams();
                        response.arg1 = lChannel.closeChannel();
                        if (BipUtils.TRANSPORT_PROTOCOL_SERVER == lChannel.mProtocolType) {
                            if (null != tcpSerCh && false == tcpSerCh.isCloseBackToTcpListen()) {
                                mBipChannelManager.removeChannel(cId);
                            }
                        } else {
                            mBipChannelManager.removeChannel(cId);
                        }

                        mChannel = null;
                        mChannelStatus = BipUtils.CHANNEL_STATUS_CLOSE;
                    }
                }
            }catch (IndexOutOfBoundsException e){
                CatLog.e("[BIP]", "BM-closeChannel: IndexOutOfBoundsException cid="+cId);
                response.arg1 = ErrorValue.CHANNEL_ID_NOT_VALID;
            }
        }
        isParamsValid = false;
        if (false == mIsCloseInProgress) {
            response.obj = cmdMsg;
            mBipSrvHandler.sendMessage(response);
        } else {
            sendBipDisconnectTimeOutMsg(cmdMsg);
        }
        CatLog.d("[BIP]", "BM-closeChannel: exit");
    }

    public void receiveData(BipCmdMessage cmdMsg, Message response) {
        int requestCount = cmdMsg.mChannelDataLength;
        ReceiveDataResult result = new ReceiveDataResult();
        Channel lChannel = null;
        int cId = cmdMsg.mReceiveDataCid;

        lChannel = mBipChannelManager.getChannel(cId);
        CatLog.d("[BIP]", "BM-receiveData: receiveData enter");

        if(null == lChannel) {
            CatLog.e("[BIP]", "lChannel is null cid="+cId);
            response.arg1 = ErrorValue.BIP_ERROR;
            response.obj = cmdMsg;
            mBipSrvHandler.sendMessage(response);
            return;
        }
        if (lChannel.mChannelStatus == BipUtils.CHANNEL_STATUS_OPEN
                || lChannel.mChannelStatus == BipUtils.CHANNEL_STATUS_SERVER_CLOSE) {
            if (requestCount > BipUtils.MAX_APDU_SIZE) {
                CatLog.d("[BIP]", "BM-receiveData: Modify channel data length to MAX_APDU_SIZE");
                requestCount = BipUtils.MAX_APDU_SIZE;
            }
            Thread recvThread = new Thread(new RecvDataRunnable(requestCount, result, cmdMsg,
                                                                response));
            recvThread.start();
        } else {
            // response ResultCode.BIP_ERROR
            CatLog.d("[BIP]", "BM-receiveData: Channel status is invalid " + mChannelStatus);
            response.arg1 = ErrorValue.BIP_ERROR;
            response.obj = cmdMsg;
            mBipSrvHandler.sendMessage(response);
        }
    }

    public void sendData(BipCmdMessage cmdMsg, Message response)
    {
        CatLog.d("[BIP]", "sendData: Enter");
        Thread rt = new Thread(new SendDataThread(cmdMsg, response));
        rt.start();
        CatLog.d("[BIP]", "sendData: Leave");
    }

    /**
     * Start a new {@link android.net.NetworkRequest} for BIP
     */
    private void newRequest() {
        final ConnectivityManager connectivityManager = getConnectivityManager();
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                if (false == isConnMgrIntentTimeout) {
                    mBipSrvHandler.removeMessages(MSG_ID_BIP_CONN_MGR_TIMEOUT);
                }
                Channel channel = mBipChannelManager.getChannel(mChannelId);
                CatLog.d("[BIP]", "NetworkCallbackListener.onAvailable, mChannelId: "
                        + mChannelId + " , mIsOpenInProgress: " + mIsOpenInProgress
                        + " , mIsNetworkAvailableReceived: " + mIsNetworkAvailableReceived);
                if (null == channel) {
                    CatLog.d("[BIP]", "Channel is null.");
                }
                if (true == mIsOpenInProgress && false == mIsNetworkAvailableReceived) {
                    mIsNetworkAvailableReceived = true;
                    mNetwork = network;
                    connect();
                } else {
                    CatLog.d("[BIP]", "Bip channel has been established.");
                }
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
                int ret = ErrorValue.NO_ERROR;
                Message response = null;
                if (false == isConnMgrIntentTimeout) {
                    mBipSrvHandler.removeMessages(MSG_ID_BIP_CONN_MGR_TIMEOUT);
                }
                CatLog.d("[BIP]", "NetworkCallbackListener.onLost: network=" + network);
                releaseRequest(this);
                if (mNetworkCallback == this) {
                    resetLocked();
                }
                disconnect();
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                if (false == isConnMgrIntentTimeout) {
                    mBipSrvHandler.removeMessages(MSG_ID_BIP_CONN_MGR_TIMEOUT);
                }
                CatLog.d("[BIP]", "NetworkCallbackListener.onUnavailable");
                releaseRequest(this);
                if (mNetworkCallback == this) {
                    resetLocked();
                }
                disconnect();
            }
        };
        int subId[] = SubscriptionManager.getSubId(mSlotId);
        if (SubscriptionManager.isValidSubscriptionId(subId[0])) {
            mNetworkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)
                .setNetworkSpecifier(String.valueOf(subId[0]))
                .build();
        } else {
            mNetworkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)
                .build();
        }

        connectivityManager.requestNetwork(
                mNetworkRequest, mNetworkCallback, CONN_MGR_TIMEOUT);
        //Use internal time out timer, since time out of requestNetwork is not work.(Google issue).
        CatLog.d("[BIP]", "Start request network timer.");
        sendBipConnTimeOutMsg(mCurrentCmd);
    }

    /**
     * Reset the state
     */
    private void resetLocked() {
        mNetworkCallback = null;
        mNetwork = null;
    }

    /**
     * Release the current {@link android.net.NetworkRequest} for BIP
     *
     * @param callback the {@link android.net.ConnectivityManager.NetworkCallback} to unregister
     */
    private void releaseRequest(ConnectivityManager.NetworkCallback callback) {
        if (callback != null) {
            CatLog.d("[BIP]", "releaseRequest");
            final ConnectivityManager connectivityManager = getConnectivityManager();
            connectivityManager.unregisterNetworkCallback(callback);
        } else {
            CatLog.d("[BIP]", "releaseRequest: networkCallback is null.");
        }
    }

    protected class SendDataThread implements Runnable
    {
        BipCmdMessage cmdMsg;
        Message response;

        SendDataThread(BipCmdMessage Msg,Message resp)
        {
            CatLog.d("[BIP]", "SendDataThread Init");
            cmdMsg = Msg;
            response = resp;
        }

        @Override
        public void run()
        {
            CatLog.d("[BIP]", "SendDataThread Run Enter");
            int ret = ErrorValue.NO_ERROR;

            byte[] buffer = cmdMsg.mChannelData;
            int mode = cmdMsg.mSendMode;
            Channel lChannel = null;
            int cId = cmdMsg.mSendDataCid;

            lChannel = mBipChannelManager.getChannel(cId);
            do {
                if(null == lChannel) {//if(mChannelId != cmdMsg.mSendDataCid)
                    CatLog.d("[BIP]", "SendDataThread Run mChannelId != cmdMsg.mSendDataCid");
                    ret = ErrorValue.CHANNEL_ID_NOT_VALID;
                    break;
                }

                if(lChannel.mChannelStatus == BipUtils.CHANNEL_STATUS_OPEN)
                {
                    CatLog.d("[BIP]", "SendDataThread Run mChannel.sendData");
                    ret = lChannel.sendData(buffer, mode);
                    response.arg2 = lChannel.getTxAvailBufferSize();
                }
                else
                {
                    CatLog.d("[BIP]", "SendDataThread Run CHANNEL_ID_NOT_VALID");
                    ret = ErrorValue.CHANNEL_ID_NOT_VALID;
                }
            }while(false);
            response.arg1 = ret;
            response.obj = cmdMsg;
            CatLog.d("[BIP]", "SendDataThread Run mBipSrvHandler.sendMessage(response);");
            mBipSrvHandler.sendMessage(response);
        }
    }

    public void getChannelStatus(BipCmdMessage cmdMsg, Message response) {
        int ret = ErrorValue.NO_ERROR;
        int cId = 1;
        List<ChannelStatus> csList = new ArrayList<ChannelStatus>();

        try {
            while(cId <= mBipChannelManager.MAXCHANNELID){
                if(true == mBipChannelManager.isChannelIdOccupied(cId)) {
                    CatLog.d("[BIP]", "getChannelStatus: cId:"+cId);
                    csList.add(mBipChannelManager.getChannel(cId).mChannelStatusData);
                }
                cId++;
            }
        } catch (NullPointerException ne) {
            CatLog.e("[BIP]", "getChannelStatus: NE");
            ne.printStackTrace();
        }
        cmdMsg.mChannelStatusList = csList;
        response.arg1 = ret;
        response.obj = cmdMsg;
        mBipSrvHandler.sendMessage(response);
    }

    private void sendBipConnTimeOutMsg(BipCmdMessage cmdMsg) {
        Message bipTimerMsg = mBipSrvHandler.obtainMessage(MSG_ID_BIP_CONN_MGR_TIMEOUT);
        bipTimerMsg.obj = cmdMsg;
        mBipSrvHandler.sendMessageDelayed(bipTimerMsg, CONN_MGR_TIMEOUT);
    }

    private void sendBipDisconnectTimeOutMsg(BipCmdMessage cmdMsg) {
        Message bipTimerMsg = mBipSrvHandler.obtainMessage(MSG_ID_BIP_DISCONNECT_TIMEOUT);
        bipTimerMsg.obj = cmdMsg;
        mBipSrvHandler.sendMessageDelayed(bipTimerMsg, CONN_DELAY_TIMEOUT);
    }

    private void updateCurrentChannelStatus(int status){
        try {
            mBipChannelManager.updateChannelStatus(mChannelId, status);
            mCurrentCmd.mChannelStatusData.mChannelStatus = status;
        } catch (NullPointerException ne) {
            CatLog.e("[BIP]", "updateCurrentChannelStatus id:"+mChannelId+" is null");
            ne.printStackTrace();
        }
    }
    private boolean requestRouteToHost() {
        CatLog.d("[BIP]", "requestRouteToHost");
        byte[] addressBytes = null;
        if (mDataDestinationAddress != null) {
            addressBytes = mDataDestinationAddress.address.getAddress();
        } else {
            CatLog.d("[BIP]", "mDataDestinationAddress is null");
            return false;
        }
        int addr = 0;
        addr = ((addressBytes[3] & 0xFF) << 24)
                | ((addressBytes[2] & 0xFF) << 16)
                | ((addressBytes[1] & 0xFF) << 8)
                | (addressBytes[0] & 0xFF);

        return mConnMgr.requestRouteToHost(ConnectivityManager.TYPE_MOBILE_SUPL, addr);
    }

    private boolean checkNetworkInfo(NetworkInfo nwInfo, NetworkInfo.State exState) {
        if (nwInfo == null) {
            return false;
        }

        int type = nwInfo.getType();
        NetworkInfo.State state = nwInfo.getState();
        CatLog.d("[BIP]", "network type is " + ((type == ConnectivityManager.TYPE_MOBILE) ?
                                                "MOBILE" : "WIFI"));
        CatLog.d("[BIP]", "network state is " + state);

        if (type == ConnectivityManager.TYPE_MOBILE && state == exState) {
            return true;
        }

        return false;
    }

    private int establishLink() {
        int ret = ErrorValue.NO_ERROR;
        Channel lChannel = null;

        if (mTransportProtocol.protocolType == BipUtils.TRANSPORT_PROTOCOL_SERVER) {
            CatLog.d("[BIP]", "BM-establishLink: establish a TCPServer link");
            try {
                lChannel = new TcpServerChannel(mChannelId, mLinkMode,
                                                mTransportProtocol.protocolType,
                    mTransportProtocol.portNumber, mBufferSize, ((CatService)mHandler), this);
            } catch (NullPointerException ne){
                CatLog.e("[BIP]", "BM-establishLink: NE,new TCP server channel fail.");
                ne.printStackTrace();
                return ErrorValue.BIP_ERROR;
            }
            ret = lChannel.openChannel(mCurrentCmd, mNetwork);
            if (ret == ErrorValue.NO_ERROR ||
                ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                mChannelStatus = BipUtils.CHANNEL_STATUS_OPEN;
                mBipChannelManager.addChannel(mChannelId, lChannel);
            } else {
                mBipChannelManager.releaseChannelId(mChannelId,BipUtils.TRANSPORT_PROTOCOL_SERVER);
                mChannelStatus = BipUtils.CHANNEL_STATUS_ERROR;
            }
        } else if (mTransportProtocol.protocolType == BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE) {
            CatLog.d("[BIP]", "BM-establishLink: establish a TCP link");
            try {
                lChannel = new TcpChannel(mChannelId, mLinkMode,
                    mTransportProtocol.protocolType, mDataDestinationAddress.address,
                    mTransportProtocol.portNumber, mBufferSize, ((CatService)mHandler), this);
            } catch (NullPointerException ne){
                CatLog.e("[BIP]", "BM-establishLink: NE,new TCP client channel fail.");
                ne.printStackTrace();
                if(null == mDataDestinationAddress) {
                    return ErrorValue.MISSING_DATA;
                } else {
                    return ErrorValue.BIP_ERROR;
                }
            }
            ret = lChannel.openChannel(mCurrentCmd, mNetwork);
            if (ret != ErrorValue.WAIT_OPEN_COMPLETED) {
                if (ret == ErrorValue.NO_ERROR ||
                    ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                    mChannelStatus = BipUtils.CHANNEL_STATUS_OPEN;
                    mBipChannelManager.addChannel(mChannelId, lChannel);
                } else {
                    mBipChannelManager.releaseChannelId(
                            mChannelId,BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE);
                    mChannelStatus = BipUtils.CHANNEL_STATUS_ERROR;
                }
            }
        } else if (mTransportProtocol.protocolType == BipUtils.TRANSPORT_PROTOCOL_UDP_REMOTE) {
            // establish upd link
            CatLog.d("[BIP]", "BM-establishLink: establish a UDP link");
            try {
            lChannel = new UdpChannel(mChannelId, mLinkMode, mTransportProtocol.protocolType,
                    mDataDestinationAddress.address, mTransportProtocol.portNumber, mBufferSize,
                    ((CatService)mHandler), this);
            } catch (NullPointerException ne){
                CatLog.e("[BIP]", "BM-establishLink: NE,new UDP client channel fail.");
                ne.printStackTrace();
                return ErrorValue.BIP_ERROR;
            }
            ret = lChannel.openChannel(mCurrentCmd, mNetwork);
            if (ret == ErrorValue.NO_ERROR ||
                ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
                mChannelStatus = BipUtils.CHANNEL_STATUS_OPEN;
                mBipChannelManager.addChannel(mChannelId, lChannel);
            } else {
                mBipChannelManager.releaseChannelId(
                        mChannelId,BipUtils.TRANSPORT_PROTOCOL_UDP_REMOTE);
                mChannelStatus = BipUtils.CHANNEL_STATUS_ERROR;
            }
        } else {
            CatLog.d("[BIP]", "BM-establishLink: unsupported channel type");
            ret = ErrorValue.UNSUPPORTED_TRANSPORT_PROTOCOL_TYPE;
            mChannelStatus = BipUtils.CHANNEL_STATUS_ERROR;
        }

        CatLog.d("[BIP]", "BM-establishLink: ret:" + ret);
        return ret;
    }

    private Uri getUri(Uri uri, int slodId) {
        int subId[] = SubscriptionManager.getSubId(slodId);

        if (SubscriptionManager.isValidSubscriptionId(subId[0])) {
            return Uri.withAppendedPath(uri, "/subId/" + subId[0]);
        } else {
            CatLog.d("[BIP]", "BM-getUri: invalid subId.");
            return null;
        }
    }
    private void deleteApnParams() {
        Uri uri = getUri(Telephony.Carriers.CONTENT_URI, mSlotId);
        int subId[] = SubscriptionManager.getSubId(mSlotId);
        String numeric = null;

        CatLog.d("[BIP]", "BM-deleteApnParams: enter. ");

        if (SubscriptionManager.isValidSubscriptionId(subId[0])) {
            numeric = TelephonyManager.getDefault().getSimOperator(subId[0]);
        }

        if (uri == null) {
            CatLog.e("[BIP]", "BM-deleteApnParams: Invalid uri");
            return;
        }
        String selection = "name = '" + BIP_NAME + "'";
        int rows = mContext.getContentResolver().delete(uri, selection, null);
        CatLog.d("[BIP]", "BM-deleteApnParams:[" + rows + "] end");
    }
    private void setApnParams(String apn, String user, String pwd) {
        CatLog.d("[BIP]", "BM-setApnParams: enter");
        if (apn == null) {
            CatLog.d("[BIP]", "BM-setApnParams: No apn parameters");
            return;
        }

        Uri uri = getUri(Telephony.Carriers.CONTENT_URI, mSlotId);
        String numeric = null;
        String mcc = null;
        String mnc = null;
        String apnType = "supl";
        int subId[] = SubscriptionManager.getSubId(mSlotId);

        /*
         * M for telephony provider enhancement
         */
//FIXME: mutiple network
        if (SubscriptionManager.isValidSubscriptionId(subId[0])) {
            numeric = TelephonyManager.getDefault().getSimOperator(subId[0]);
        }
        if (uri == null) {
            CatLog.e("[BIP]", "BM-setApnParams: Invalid uri");
        }

        if (numeric != null && numeric.length() >= 4) {
            Cursor cursor = null;
            mcc = numeric.substring(0, 3);
            mnc = numeric.substring(3);
            CatLog.d("[BIP]", "BM-setApnParams: apn = " + apn + "mcc = " + mcc + ", mnc = " + mnc);
            String selection = Telephony.Carriers.APN + " = '" + apn + "'" +
                    " and " + Telephony.Carriers.NUMERIC + " = '" + mcc + mnc + "'" +
                    " and " + Telephony.Carriers.SUBSCRIPTION_ID + " = '" + subId[0] + "'";
//FIXME: multiple network for MSIN ?
            cursor = mContext.getContentResolver().query(
                    uri, null, selection, null, null);

            if (cursor != null) {
                ContentValues values = new ContentValues();
                values.put(Telephony.Carriers.NAME, BIP_NAME);
                values.put(Telephony.Carriers.APN, apn);
                values.put(Telephony.Carriers.USER, user);
                values.put(Telephony.Carriers.PASSWORD, pwd);
                values.put(Telephony.Carriers.TYPE, apnType);
                values.put(Telephony.Carriers.MCC, mcc);
                values.put(Telephony.Carriers.MNC, mnc);
                values.put(Telephony.Carriers.NUMERIC, mcc + mnc);
                values.put(Telephony.Carriers.SUBSCRIPTION_ID, subId[0]);

                if (cursor.getCount() == 0) {
                    // int updateResult = mContext.getContentResolver().update(
                    // uri, values, selection, selectionArgs);
                    CatLog.d("[BIP]", "BM-setApnParams: insert one record");
                    Uri newRow = mContext.getContentResolver().insert(uri, values);
                    if (newRow != null) {
                        CatLog.d("[BIP]", "insert a new record into db");
                        mIsApnInserting = true;
                    } else {
                        CatLog.d("[BIP]", "Fail to insert apn params into db");
                    }
                } else {
                    CatLog.d("[BIP]", "BM-setApnParams: do not update one record");
                    //mContext.getContentResolver().update(uri, values, selection, null);
                }
                cursor.close();
            }
            // cursor.close();
        }
        CatLog.d("[BIP]", "BM-setApnParams: exit");
    }

    private boolean checkDataCapability(BipCmdMessage cmdMsg) {
        TelephonyManager mTelMan = (TelephonyManager) mContext.getSystemService(
            Context.TELEPHONY_SERVICE);
        int simInsertedCount = 0;
        for (int i = 0; i < mSimCount; i++) {
            if (mTelMan.hasIccCard(i)) {
                simInsertedCount++;
            }
        }
        int defaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        int subId[] = SubscriptionManager.getSubId(mSlotId);
        int currentSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subId != null) {
            currentSubId = subId[0];
        } else {
            CatLog.d("[BIP]", "checkDataCapability: invalid subId");
            return false;
        }
        CatLog.d("[BIP]", "checkDataCapability: simInsertedCount:" + simInsertedCount
            + " currentSubId:" + currentSubId + " defaultDataSubId:"
            + defaultDataSubId);
        if (simInsertedCount >= 2 &&
            null != cmdMsg.mBearerDesc &&
            (BipUtils.BEARER_TYPE_GPRS == cmdMsg.mBearerDesc.bearerType ||
             BipUtils.BEARER_TYPE_DEFAULT == cmdMsg.mBearerDesc.bearerType ||
             BipUtils.BEARER_TYPE_EUTRAN == cmdMsg.mBearerDesc.bearerType) &&
            currentSubId != defaultDataSubId) {
            CatLog.d("[BIP]", "checkDataCapability: return false");
            return false;
        }
        CatLog.d("[BIP]", "checkDataCapability: return true");
        return true;
    }

    public int getChannelId() {
        CatLog.d("[BIP]", "BM-getChannelId: channel id is " + mChannelId);
        return mChannelId;
    }

    public int getFreeChannelId(){
        return mBipChannelManager.getFreeChannelId();
    }

    public void openChannelCompleted(int ret, Channel lChannel){
        CatLog.d("[BIP]", "BM-openChannelCompleted: ret: " + ret);

        if(ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
            mCurrentCmd.mBufferSize = mBufferSize;
        }
        if(ret == ErrorValue.NO_ERROR || ret == ErrorValue.COMMAND_PERFORMED_WITH_MODIFICATION) {
            mChannelStatus = BipUtils.CHANNEL_STATUS_OPEN;
            mBipChannelManager.addChannel(mChannelId, lChannel);
        } else {
            mBipChannelManager.releaseChannelId(mChannelId,BipUtils.TRANSPORT_PROTOCOL_TCP_REMOTE);
            mChannelStatus = BipUtils.CHANNEL_STATUS_ERROR;
        }
        mCurrentCmd.mChannelStatusData = lChannel.mChannelStatusData;

        if(true == mIsOpenInProgress && false == isConnMgrIntentTimeout) {
            mIsOpenInProgress = false;
            mIsNetworkAvailableReceived = false;
            Message response = mBipSrvHandler.obtainMessage(MSG_ID_OPEN_CHANNEL_DONE, ret, 0,
                                                            mCurrentCmd);
            response.arg1 = ret;
            response.obj = mCurrentCmd;
            mBipSrvHandler.sendMessage(response);
        }
    }

    public BipChannelManager getBipChannelManager(){
        return mBipChannelManager;
    }

    public boolean isTestSim() {
        CatLog.d("[BIP]", "isTestSim slotId: " + mSlotId);
        if (PhoneConstants.SIM_ID_1 == mSlotId &&
                true == SystemProperties.get("gsm.sim.ril.testsim", "0")
                .equals("1"))  {
            return true;
        } else if (PhoneConstants.SIM_ID_2 == mSlotId &&
                true == SystemProperties.get("gsm.sim.ril.testsim.2", "0")
                .equals("1")) {
            return true;
        } else if (PhoneConstants.SIM_ID_3 == mSlotId &&
                true == SystemProperties.get("gsm.sim.ril.testsim.3", "0")
                .equals("1")) {
            return true;
        } else if (PhoneConstants.SIM_ID_4 == mSlotId &&
                true == SystemProperties.get("gsm.sim.ril.testsim.4", "0")
                .equals("1")) {
            return true;
        }
        return false;
    }
    protected class ConnectivityChangeThread implements Runnable
    {
        Intent intent;

        ConnectivityChangeThread(Intent in)
        {
            CatLog.d("[BIP]", "ConnectivityChangeThread Init");
            intent = in;
        }

        @Override
        public void run()
        {
            CatLog.d("[BIP]", "ConnectivityChangeThread Enter");
            CatLog.d("[BIP]", "Connectivity changed");
            int ret = ErrorValue.NO_ERROR;
            Message response = null;

            NetworkInfo info = (NetworkInfo)intent.getExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            //FIXME
            String strSubId = intent.getStringExtra("subId");
            int subId = 0;
            if (null == strSubId) {
                CatLog.d("[BIP]", "No subId in intet extra.");
                return;
            }
            try {
                subId = Integer.parseInt(strSubId);
            } catch (NumberFormatException e) {
                CatLog.e("[BIP]", "Invalid long string. strSubId: " + strSubId);
            }
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                CatLog.e("[BIP]", "Invalid subId: " + subId);
                return;
            }
            int simId = SubscriptionManager.getSlotId(subId);
            CatLog.d("[BIP]", "EXTRA_SIM_ID :" + simId + ",mSlotId:" + mSlotId);
            if(info == null || simId != mSlotId) {
                CatLog.d("[BIP]", "receive CONN intent sim!=" + mSlotId);
                return;
            } else {
                CatLog.d("[BIP]", "receive valid CONN intent");
            }

            int type = info.getType();
            NetworkInfo.State state = info.getState();
            CatLog.d("[BIP]", "network type is " + type);
            CatLog.d("[BIP]", "network state is " + state);

            if (type == ConnectivityManager.TYPE_MOBILE_SUPL) {
                if (false == isConnMgrIntentTimeout) {
                    mBipSrvHandler.removeMessages(MSG_ID_BIP_CONN_MGR_TIMEOUT);
                }
                if (state == NetworkInfo.State.CONNECTED) {
                    /*Connected state is handled by onAvailable for L.*/
                    CatLog.d("[BIP]", "network state - connected.");
                } else if (state == NetworkInfo.State.DISCONNECTED) {
                    CatLog.d("[BIP]", "network state - disconnected");
                    synchronized (mCloseLock) {
                        CatLog.d("[BIP]", "mIsCloseInProgress: " + mIsCloseInProgress);
                        if (true == mIsCloseInProgress) {
                            CatLog.d("[BIP]", "Return TR for close channel.");
                            mBipSrvHandler.removeMessages(MSG_ID_BIP_DISCONNECT_TIMEOUT);
                            mIsCloseInProgress = false;
                            response = mBipSrvHandler.obtainMessage(MSG_ID_CLOSE_CHANNEL_DONE,
                                    ErrorValue.NO_ERROR, 0, mCurrentCmd);
                            mBipSrvHandler.sendMessage(response);
                        }
                    }
                }
            }
        }
    }

    private BroadcastReceiver mNetworkConnReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            CatLog.d("[BIP]", "mNetworkConnReceiver:" + mIsOpenInProgress + " , " +
                    mIsCloseInProgress + " , " + isConnMgrIntentTimeout);
            if(null != mBipChannelManager) {
                CatLog.d("[BIP]", "isClientChannelOpened:" +
                        mBipChannelManager.isClientChannelOpened());
            }
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)
                    && ((mIsOpenInProgress == true && isConnMgrIntentTimeout == false) ||
                    (true == mIsCloseInProgress))) {
                CatLog.d("[BIP]", "Connectivity changed onReceive Enter");

                Thread rt = new Thread(new ConnectivityChangeThread(intent));
                rt.start();
                CatLog.d("[BIP]", "Connectivity changed onReceive Leave");
            }
        }
    };

    public void setConnMgrTimeoutFlag(boolean flag) {
        isConnMgrIntentTimeout = flag;
    }
    public void setOpenInProgressFlag(boolean flag){
        mIsOpenInProgress = flag;
    }
    private class RecvDataRunnable implements Runnable {
        int requestDataSize;
        ReceiveDataResult result;
        BipCmdMessage cmdMsg;
        Message response;

        public RecvDataRunnable(int size, ReceiveDataResult result, BipCmdMessage cmdMsg,
                                Message response) {
            this.requestDataSize = size;
            this.result = result;
            this.cmdMsg = cmdMsg;
            this.response = response;
        }

        public void run() {
            Channel lChannel = null;
            int errCode = ErrorValue.NO_ERROR;

            CatLog.d("[BIP]", "BM-receiveData: start to receive data");
            lChannel = mBipChannelManager.getChannel(cmdMsg.mReceiveDataCid);
            if(null == lChannel)
                errCode = ErrorValue.BIP_ERROR;
            else {
                synchronized (lChannel.mLock) {
                    lChannel.isReceiveDataTRSent = false;
                }
                errCode = lChannel.receiveData(requestDataSize, result);
            }

            cmdMsg.mChannelData = result.buffer;
            cmdMsg.mRemainingDataLength = result.remainingCount;
            response.arg1 = errCode;
            response.obj = cmdMsg;
            mBipSrvHandler.sendMessage(response);
            if (null != lChannel) {
                synchronized (lChannel.mLock) {
                    CatLog.d("[BIP]", "BM-receiveData: notify waiting channel.");
                    lChannel.isReceiveDataTRSent = true;
                    lChannel.mLock.notify();
                }
            } else {
                CatLog.e("[BIP]", "BM-receiveData: null channel.");
            }
            CatLog.d("[BIP]", "BM-receiveData: end to receive data. Result code = " + errCode);
        }
    }
}

class ReceiveDataResult {
    public byte[] buffer = null;
    public int requestCount = 0;
    public int remainingCount = 0;
}
