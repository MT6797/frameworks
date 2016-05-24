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
 * MediaTek Inc. (C) 2014. All rights reserved.
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

package com.android.internal.telephony;

import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAICr;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOICxH;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_ALL;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MO;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MT;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.imsphone.ImsPhone.UT_BUNDLE_KEY_CLIR;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_TERMINAL_BASED_CALL_WAITING_MODE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_UT_CFU_NOTIFICATION_MODE;
import static com.android.internal.telephony.TelephonyProperties.TERMINAL_BASED_CALL_WAITING_DISABLED;
import static com.android.internal.telephony.TelephonyProperties.UT_CFU_NOTIFICATION_MODE_OFF;
import static com.android.internal.telephony.TelephonyProperties.UT_CFU_NOTIFICATION_MODE_ON;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;

import com.android.ims.ImsCallForwardInfo;
import com.android.ims.ImsCallForwardInfoEx;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsSsInfo;
import com.android.ims.ImsUtInterface;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CallForwardInfoEx;
import com.android.internal.telephony.gsm.GSMPhone;

import java.util.ArrayList;

/**
 * {@hide}
 */
public class SSRequestDecisionMaker {
    static final String LOG_TAG = "SSDecisonMaker";

    private static final int SS_REQUEST_GET_CALL_FORWARD = 1;
    private static final int SS_REQUEST_SET_CALL_FORWARD = 2;
    private static final int SS_REQUEST_GET_CALL_BARRING = 3;
    private static final int SS_REQUEST_SET_CALL_BARRING = 4;
    private static final int SS_REQUEST_GET_CALL_WAITING = 5;
    private static final int SS_REQUEST_SET_CALL_WAITING = 6;
    private static final int SS_REQUEST_GET_CLIR = 7;
    private static final int SS_REQUEST_SET_CLIR = 8;
    private static final int SS_REQUEST_GET_CLIP = 9;
    private static final int SS_REQUEST_SET_CLIP = 10;
    private static final int SS_REQUEST_GET_COLR = 11;
    private static final int SS_REQUEST_SET_COLR = 12;
    private static final int SS_REQUEST_GET_COLP = 13;
    private static final int SS_REQUEST_SET_COLP = 14;
    private static final int SS_REQUEST_GET_CALL_FORWARD_TIME_SLOT = 15;
    private static final int SS_REQUEST_SET_CALL_FORWARD_TIME_SLOT = 16;

    private static final int EVENT_SS_SEND = 1;
    private static final int EVENT_SS_RESPONSE = 2;
    private static final int EVENT_SS_CLEAR_TEMP_VOLTE_USER_FLAG = 3;

    private static final int CLEAR_DELAY_TIMEOUT = 10 * 1000;

    private static final String PROPERTY_CS_CURRENT_PHONE_ID = "gsm.radio.ss.phoneid";

    private PhoneBase mPhone;
    private CommandsInterface mCi;
    private int mPhoneId;
    private Context mContext;
    private HandlerThread mSSHandlerThread;
    private SSRequestHandler mSSRequestHandler;
    private boolean mIsTempVolteUser;
    private ImsManager mImsManager;

    public SSRequestDecisionMaker(Context context, Phone phone) {
        mContext = context;
        mPhone = (PhoneBase) phone;
        mCi = mPhone.mCi;
        mPhoneId = phone.getPhoneId();

        mSSHandlerThread = new HandlerThread("SSRequestHandler");
        mSSHandlerThread.start();
        Looper looper = mSSHandlerThread.getLooper();
        mSSRequestHandler = new SSRequestHandler(looper);
        mImsManager = ImsManager.getInstance(mPhone.getContext(), mPhone.getPhoneId());
    }

    /**
     * Dispose SSRequestDecisionMaker.
     */
    public void dispose() {
        Rlog.d(LOG_TAG, "dispose.");
        Looper looper = mSSHandlerThread.getLooper();
        looper.quit();
    }

    private int getPhoneId() {
        mPhoneId = mPhone.getPhoneId();
        return mPhoneId;
    }

    private ImsUtInterface getUtInterface() throws ImsException {
        if (mImsManager == null) {
            throw new ImsException("no ims manager", ImsReasonInfo.CODE_UNSPECIFIED);
        }

        ImsUtInterface ut = mImsManager.getSupplementaryServiceConfiguration(0);
        return ut;
    }

    void sendGenericErrorResponse(Message onComplete) {
        Rlog.d(LOG_TAG, "sendErrorResponse");
        if (onComplete != null) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.GENERIC_FAILURE));
            onComplete.sendToTarget();
        }
    }

    private int getActionFromCFAction(int action) {
        switch(action) {
            case CF_ACTION_DISABLE: return ImsUtInterface.ACTION_DEACTIVATION;
            case CF_ACTION_ENABLE: return ImsUtInterface.ACTION_ACTIVATION;
            case CF_ACTION_ERASURE: return ImsUtInterface.ACTION_ERASURE;
            case CF_ACTION_REGISTRATION: return ImsUtInterface.ACTION_REGISTRATION;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    private int getConditionFromCFReason(int reason) {
        switch(reason) {
            case CF_REASON_UNCONDITIONAL: return ImsUtInterface.CDIV_CF_UNCONDITIONAL;
            case CF_REASON_BUSY: return ImsUtInterface.CDIV_CF_BUSY;
            case CF_REASON_NO_REPLY: return ImsUtInterface.CDIV_CF_NO_REPLY;
            case CF_REASON_NOT_REACHABLE: return ImsUtInterface.CDIV_CF_NOT_REACHABLE;
            case CF_REASON_ALL: return ImsUtInterface.CDIV_CF_ALL;
            case CF_REASON_ALL_CONDITIONAL: return ImsUtInterface.CDIV_CF_ALL_CONDITIONAL;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    private int getCBTypeFromFacility(String facility) {
        if (CB_FACILITY_BAOC.equals(facility)) {
            return ImsUtInterface.CB_BAOC;
        } else if (CB_FACILITY_BAOIC.equals(facility)) {
            return ImsUtInterface.CB_BOIC;
        } else if (CB_FACILITY_BAOICxH.equals(facility)) {
            return ImsUtInterface.CB_BOIC_EXHC;
        } else if (CB_FACILITY_BAIC.equals(facility)) {
            return ImsUtInterface.CB_BAIC;
        } else if (CB_FACILITY_BAICr.equals(facility)) {
            return ImsUtInterface.CB_BIC_WR;
        } else if (CB_FACILITY_BA_ALL.equals(facility)) {
            return ImsUtInterface.CB_BA_ALL;
        } else if (CB_FACILITY_BA_MO.equals(facility)) {
            return ImsUtInterface.CB_BA_MO;
        } else if (CB_FACILITY_BA_MT.equals(facility)) {
            return ImsUtInterface.CB_BA_MT;
        }

        return 0;
    }

    private int[] handleCbQueryResult(ImsSsInfo[] infos) {
        int[] cbInfos = new int[1];
        cbInfos[0] = infos[0].mStatus;

        return cbInfos;
    }

    private int[] handleCwQueryResult(ImsSsInfo[] infos) {
        int[] cwInfos = new int[2];
        cwInfos[0] = 0;

        if (infos[0].mStatus == 1) {
            cwInfos[0] = 1;
            cwInfos[1] = SERVICE_CLASS_VOICE;
        }

        return cwInfos;
    }

    private CallForwardInfoEx getCallForwardInfoEx(ImsCallForwardInfoEx info) {
        CallForwardInfoEx cfInfo = new CallForwardInfoEx();
        cfInfo.status = info.mStatus;
        cfInfo.reason = getCFReasonFromCondition(info.mCondition);
        cfInfo.serviceClass = info.mServiceClass;
        cfInfo.toa = info.mToA;
        cfInfo.number = info.mNumber;
        cfInfo.timeSeconds = info.mTimeSeconds;
        cfInfo.timeSlot = info.mTimeSlot;
        return cfInfo;
    }

    private CallForwardInfoEx[] imsCFInfoExToCFInfoEx(ImsCallForwardInfoEx[] infos) {
        CallForwardInfoEx[] cfInfos = null;

        if (infos != null && infos.length != 0) {
            cfInfos = new CallForwardInfoEx[infos.length];
        }

        if (infos == null || infos.length == 0) {
            Rlog.d(LOG_TAG, "No CFInfoEx exist.");
            return null;
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                cfInfos[i] = getCallForwardInfoEx(infos[i]);
            }
        }

        Rlog.d(LOG_TAG, "imsCFInfoExToCFInfoEx finish.");
        return cfInfos;
    }

    private CallForwardInfo getCallForwardInfo(ImsCallForwardInfo info) {
        CallForwardInfo cfInfo = new CallForwardInfo();
        cfInfo.status = info.mStatus;
        cfInfo.reason = getCFReasonFromCondition(info.mCondition);
        cfInfo.serviceClass = SERVICE_CLASS_VOICE;
        cfInfo.toa = info.mToA;
        cfInfo.number = info.mNumber;
        cfInfo.timeSeconds = info.mTimeSeconds;
        return cfInfo;
    }

    private CallForwardInfo[] imsCFInfoToCFInfo(ImsCallForwardInfo[] infos) {
        CallForwardInfo[] cfInfos = null;

        if (infos != null && infos.length != 0) {
            cfInfos = new CallForwardInfo[infos.length];
        }

        if (infos == null || infos.length == 0) {
            Rlog.d(LOG_TAG, "No CFInfo exist.");
            return null;
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                cfInfos[i] = getCallForwardInfo(infos[i]);
            }
        }

        Rlog.d(LOG_TAG, "imsCFInfoToCFInfo finish.");
        return cfInfos;
    }

    private int getCFReasonFromCondition(int condition) {
        switch(condition) {
            case ImsUtInterface.CDIV_CF_UNCONDITIONAL: return CF_REASON_UNCONDITIONAL;
            case ImsUtInterface.CDIV_CF_BUSY: return CF_REASON_BUSY;
            case ImsUtInterface.CDIV_CF_NO_REPLY: return CF_REASON_NO_REPLY;
            case ImsUtInterface.CDIV_CF_NOT_REACHABLE: return CF_REASON_NOT_REACHABLE;
            case ImsUtInterface.CDIV_CF_ALL: return CF_REASON_ALL;
            case ImsUtInterface.CDIV_CF_ALL_CONDITIONAL: return CF_REASON_ALL_CONDITIONAL;
            default:
                break;
        }

        return CF_REASON_NOT_REACHABLE;
    }

    class SSRequestHandler extends Handler implements Runnable {

        public SSRequestHandler(Looper looper) {
            super(looper);
        }

        //***** Runnable implementation
        public void
            run() {
            //setup if needed
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mPhone.mIsTheCurrentActivePhone) {
                Rlog.e(LOG_TAG, "SSRequestDecisionMaker-Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
                return;
            }

            switch (msg.what) {
                case EVENT_SS_SEND:
                    processSendRequest(msg.obj);
                    break;
                case EVENT_SS_RESPONSE:
                    processResponse(msg.obj);
                    break;
                case EVENT_SS_CLEAR_TEMP_VOLTE_USER_FLAG:
                    mIsTempVolteUser = false;
                    break;
                default:
                    Rlog.d(LOG_TAG, "SSRequestDecisionMaker:msg.what=" + msg.what);
            }
        }
    }

    private void processSendRequest(Object obj) {
        Message resp = null;
        ArrayList <Object> ssParmList = (ArrayList <Object>) obj;
        Integer request = (Integer) ssParmList.get(0);
        Message utResp = mSSRequestHandler.obtainMessage(EVENT_SS_RESPONSE,
                ssParmList);
        Rlog.d(LOG_TAG, "processSendRequest, request = " + request);

        switch (request.intValue()) {
            case SS_REQUEST_GET_CALL_FORWARD: {
                int cfReason = ((Integer) ssParmList.get(1)).intValue();
                int serviceClass = ((Integer) ssParmList.get(2)).intValue();
                String number = (String) ssParmList.get(3);
                resp = (Message) ssParmList.get(4);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCallForward(getConditionFromCFReason(cfReason), null, utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CALL_FORWARD: {
                int action = ((Integer) ssParmList.get(1)).intValue();
                int cfReason = ((Integer) ssParmList.get(2)).intValue();
                int serviceClass = ((Integer) ssParmList.get(3)).intValue();
                String number = (String) ssParmList.get(4);
                int timeSeconds = ((Integer) ssParmList.get(5)).intValue();
                resp = (Message) ssParmList.get(6);

                // + [ALPS02301009]
                if (number == null || number.isEmpty()) {
                    if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
                        if ((mPhone instanceof GSMPhone)
                            && ((GSMPhone) mPhone).isSupportSaveCFNumber()) {
                            if (action == CF_ACTION_ENABLE ||
                                action == CF_ACTION_REGISTRATION) {
                                String getNumber =
                                    ((GSMPhone) mPhone).getCFPreviousDialNumber(cfReason);

                                if (getNumber != null && !getNumber.isEmpty()) {
                                    number = getNumber;
                                }
                            }
                        }
                    }
                }
                // - [ALPS02301009]

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCallForward(getActionFromCFAction(action),
                            getConditionFromCFReason(cfReason),
                            number,
                            serviceClass,
                            timeSeconds,
                            utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_CALL_FORWARD_TIME_SLOT: {
                int cfReason = ((Integer) ssParmList.get(1)).intValue();
                int serviceClass = ((Integer) ssParmList.get(2)).intValue();
                resp = (Message) ssParmList.get(3);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCallForwardInTimeSlot(
                            getConditionFromCFReason(cfReason),
                            utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CALL_FORWARD_TIME_SLOT: {
                int action = ((Integer) ssParmList.get(1)).intValue();
                int cfReason = ((Integer) ssParmList.get(2)).intValue();
                int serviceClass = ((Integer) ssParmList.get(3)).intValue();
                String number = (String) ssParmList.get(4);
                int timeSeconds = ((Integer) ssParmList.get(5)).intValue();
                long[] timeSlot = (long[]) ssParmList.get(6);
                resp = (Message) ssParmList.get(7);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCallForwardInTimeSlot(getActionFromCFAction(action),
                            getConditionFromCFReason(cfReason),
                            number,
                            timeSeconds,
                            timeSlot,
                            utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_CALL_BARRING: {
                String facility = (String) ssParmList.get(1);
                String password = (String) ssParmList.get(2);
                int serviceClass = ((Integer) ssParmList.get(3)).intValue();
                resp = (Message) ssParmList.get(4);

                if (((GSMPhone) mPhone).isOp01IccCard() &&
                        ((GSMPhone) mPhone).isOutgoingCallBarring(facility)) {
                    if (mIsTempVolteUser) {
                        if (resp != null) {
                            AsyncResult.forMessage(resp, null, new CommandException(
                                    CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED));
                            resp.sendToTarget();
                        }
                        return;
                    } else {
                        facility = CommandsInterface.CB_FACILITY_BAIC;
                    }
                }

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCallBarring(getCBTypeFromFacility(facility), utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CALL_BARRING: {
                String facility = (String) ssParmList.get(1);
                boolean lockState = ((Boolean) ssParmList.get(2)).booleanValue();
                String password = (String) ssParmList.get(3);
                int serviceClass = ((Integer) ssParmList.get(4)).intValue();
                resp = (Message) ssParmList.get(5);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCallBarring(getCBTypeFromFacility(facility), lockState, utResp, null);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_CALL_WAITING: {
                int serviceClass = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCallWaiting(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CALL_WAITING: {
                boolean enable = ((Boolean) ssParmList.get(1)).booleanValue();
                int serviceClass = ((Integer) ssParmList.get(2)).intValue();
                resp = (Message) ssParmList.get(3);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    // query call waiting to determine VoLTE user
                    //ut.updateCallWaiting(enable, serviceClass, utResp);
                    ut.queryCallWaiting(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_CLIR: {
                resp = (Message) ssParmList.get(1);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCLIR(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CLIR: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCLIR(mode, utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_CLIP: {
                resp = (Message) ssParmList.get(1);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCLIP(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_CLIP: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCLIP((mode != 0), utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_COLR: {
                resp = (Message) ssParmList.get(1);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCOLR(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_COLR: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCOLR(mode, utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_GET_COLP: {
                resp = (Message) ssParmList.get(1);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.queryCOLP(utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            case SS_REQUEST_SET_COLP: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                try {
                    ImsUtInterface ut = getUtInterface();
                    setCSCurrentPhoneId(getPhoneId());
                    ut.updateCOLP((mode != 0), utResp);
                } catch (ImsException e) {
                    sendGenericErrorResponse(resp);
                }
                break;
            }
            default:
                break;
        }
    }

    private void processResponse(Object obj) {
        Message resp = null;
        AsyncResult ar = (AsyncResult) obj;
        Object arResult = ar.result;
        Throwable arException = ar.exception;
        ArrayList <Object> ssParmList = (ArrayList <Object>) (ar.userObj);
        Integer request = (Integer) ssParmList.get(0);
        Rlog.d(LOG_TAG, "processResponse, request = " + request);

        switch (request.intValue()) {
            case SS_REQUEST_GET_CALL_FORWARD: {
                int cfReason = ((Integer) ssParmList.get(1)).intValue();
                int serviceClass = ((Integer) ssParmList.get(2)).intValue();
                String number = (String) ssParmList.get(3);
                resp = (Message) ssParmList.get(4);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.queryCallForwardStatus(cfReason, serviceClass, number, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.queryCallForwardStatus(cfReason, serviceClass, number, resp);
                        return;
                    }
                }

                if (arResult != null) {
                    Rlog.d(LOG_TAG, "SS_REQUEST_GET_CALL_FORWARD cfinfo check.");
                    if (arResult instanceof ImsCallForwardInfo[]) {
                        ImsCallForwardInfo[] infos = (ImsCallForwardInfo[]) arResult;
                        if (infos == null || infos.length == 0) {
                            for (int i = 0, s = infos.length; i < s; i++) {
                                if (infos[i].mCondition == ImsUtInterface.CDIV_CF_UNCONDITIONAL) {
                                    String mode = infos[i].mStatus == 1 ?
                                        UT_CFU_NOTIFICATION_MODE_ON : UT_CFU_NOTIFICATION_MODE_OFF;

                                    ((GSMPhone) mPhone).setSystemProperty(
                                        PROPERTY_UT_CFU_NOTIFICATION_MODE, mode);
                                }
                            }
                        }
                        arResult = (Object) imsCFInfoToCFInfo((ImsCallForwardInfo[]) arResult);
                    }
                }
                break;
            }
            case SS_REQUEST_SET_CALL_FORWARD: {
                int action = ((Integer) ssParmList.get(1)).intValue();
                int cfReason = ((Integer) ssParmList.get(2)).intValue();
                int serviceClass = ((Integer) ssParmList.get(3)).intValue();
                String number = (String) ssParmList.get(4);
                int timeSeconds = ((Integer) ssParmList.get(5)).intValue();
                resp = (Message) ssParmList.get(6);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.setCallForward(action, cfReason, serviceClass,
                                number, timeSeconds, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.setCallForward(action, cfReason, serviceClass, number, timeSeconds,
                                resp);
                        return;
                    }
                }

                if (ar.exception == null) {
                    // + [ALPS02301009]
                    if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
                        if ((mPhone instanceof GSMPhone)
                            && ((GSMPhone) mPhone).isSupportSaveCFNumber()) {
                            if ((action == CF_ACTION_ENABLE)
                                || (action == CF_ACTION_REGISTRATION)) {
                                boolean ret =
                                    ((GSMPhone) mPhone).applyCFSharePreference(cfReason, number);
                                if (!ret) {
                                    Rlog.d(LOG_TAG, "applySharePreference false.");
                                }
                            } else if (action == CF_ACTION_ERASURE) {
                                ((GSMPhone) mPhone).clearCFSharePreference(cfReason);
                            }
                        }
                    }
                    // - [ALPS02301009]

                    if (((GSMPhone) mPhone).isOp05IccCard()
                        && cfReason == CF_REASON_UNCONDITIONAL) {
                        CallForwardInfo[] cfInfo = null;
                        if (arResult == null) {
                            Rlog.i(LOG_TAG, "arResult is null.");
                        } else {
                            if (arResult instanceof ImsCallForwardInfo[]) {
                                cfInfo = imsCFInfoToCFInfo((ImsCallForwardInfo[]) arResult);
                                arResult = (Object) cfInfo;
                            } else if (arResult instanceof CallForwardInfo[]) {
                                cfInfo = (CallForwardInfo[]) arResult;
                            }
                        }
                        if (cfInfo == null || cfInfo.length == 0) {
                            Rlog.i(LOG_TAG, "cfInfo is null or length is 0.");
                        } else {
                            for (int i = 0; i < cfInfo.length ; i++) {
                                if ((CommandsInterface.SERVICE_CLASS_VOICE
                                    & cfInfo[i].serviceClass) != 0) {
                                    if (cfInfo[i].status == 0) {
                                        Rlog.i(LOG_TAG, "Set CF_DISABLE, serviceClass: "
                                            + cfInfo[i].serviceClass);
                                        action = CF_ACTION_DISABLE;
                                    } else {
                                        Rlog.i(LOG_TAG, "Set CF_ENABLE, serviceClass: "
                                            + cfInfo[i].serviceClass);
                                        action = CF_ACTION_ENABLE;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    if (cfReason == CF_REASON_UNCONDITIONAL) {
                        if ((action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION)) {
                            mPhone.setSystemProperty(PROPERTY_UT_CFU_NOTIFICATION_MODE,
                                    UT_CFU_NOTIFICATION_MODE_ON);
                        } else {
                            mPhone.setSystemProperty(PROPERTY_UT_CFU_NOTIFICATION_MODE,
                                    UT_CFU_NOTIFICATION_MODE_OFF);
                        }
                    }
                }
                break;
            }
            case SS_REQUEST_GET_CALL_FORWARD_TIME_SLOT: {
                resp = (Message) ssParmList.get(3);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        arException = new CommandException(
                                CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                        arResult = null;
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        if (resp != null) {
                            AsyncResult.forMessage(resp, null,
                                    new CommandException(
                                    CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED));
                            resp.sendToTarget();
                        }
                        return;
                    }
                }

                if (arResult != null) {
                    Rlog.d(LOG_TAG, "SS_REQUEST_GET_CALL_FORWARD_TIME_SLOT cfinfoEx check.");
                    if (arResult instanceof ImsCallForwardInfoEx[]) {
                        ImsCallForwardInfoEx[] infos = (ImsCallForwardInfoEx[]) arResult;
                        if (infos == null || infos.length == 0) {
                            for (int i = 0, s = infos.length; i < s; i++) {
                                if (infos[i].mCondition == ImsUtInterface.CDIV_CF_UNCONDITIONAL) {
                                    String mode = infos[i].mStatus == 1 ?
                                        UT_CFU_NOTIFICATION_MODE_ON : UT_CFU_NOTIFICATION_MODE_OFF;

                                    ((GSMPhone) mPhone).setSystemProperty(
                                        PROPERTY_UT_CFU_NOTIFICATION_MODE, mode);
                                }
                            }
                        }
                        arResult = (Object) imsCFInfoExToCFInfoEx(
                            (ImsCallForwardInfoEx[]) arResult);
                    }
                }
                break;
            }
            case SS_REQUEST_SET_CALL_FORWARD_TIME_SLOT: {
                resp = (Message) ssParmList.get(7);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        arException = new CommandException(
                                CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                        arResult = null;
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        if (resp != null) {
                            AsyncResult.forMessage(resp, null,
                                    new CommandException(
                                    CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED));
                            resp.sendToTarget();
                        }
                        return;
                    }
                }
                break;
            }
            case SS_REQUEST_GET_CALL_BARRING: {
                String facility = (String) ssParmList.get(1);
                String password = (String) ssParmList.get(2);
                int serviceClass = ((Integer) ssParmList.get(3)).intValue();
                resp = (Message) ssParmList.get(4);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.queryFacilityLock(facility,
                                password, serviceClass, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.queryFacilityLock(facility, password, serviceClass, resp);
                        return;
                    } else if (imsException.getCode() ==
                            ImsReasonInfo.CODE_UT_XCAP_404_NOT_FOUND) {
                        //Transfer here because it only consider for CB case.
                        if (((GSMPhone) mPhone).isOp05IccCard()) {
                            Rlog.d(LOG_TAG, "processResponse CODE_UT_XCAP_404_NOT_FOUND");
                            arException =
                                new CommandException(CommandException.Error.UT_XCAP_404_NOT_FOUND);
                        }
                    }
                }

                if (arResult != null) {
                    Rlog.d(LOG_TAG, "SS_REQUEST_GET_CALL_BARRING ssinfo check.");
                    if (arResult instanceof ImsSsInfo[]) {
                        arResult = (Object) handleCbQueryResult((ImsSsInfo[]) ar.result);
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard() &&
                        ((GSMPhone) mPhone).isOutgoingCallBarring(facility)) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                    mIsTempVolteUser = true;
                    Message msg;
                    msg = mSSRequestHandler.obtainMessage(EVENT_SS_CLEAR_TEMP_VOLTE_USER_FLAG);
                    mSSRequestHandler.sendMessageDelayed(msg, CLEAR_DELAY_TIMEOUT);
                }
                break;
            }
            case SS_REQUEST_SET_CALL_BARRING: {
                String facility = (String) ssParmList.get(1);
                boolean lockState = ((Boolean) ssParmList.get(2)).booleanValue();
                String password = (String) ssParmList.get(3);
                int serviceClass = ((Integer) ssParmList.get(4)).intValue();
                resp = (Message) ssParmList.get(5);

                if (((GSMPhone) mPhone).isOp01IccCard() &&
                        ((GSMPhone) mPhone).isOutgoingCallBarring(facility)) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.setFacilityLock(facility, lockState,
                                password, serviceClass, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.setFacilityLock(facility, lockState, password, serviceClass, resp);
                        return;
                    } else if (imsException.getCode() ==
                        ImsReasonInfo.CODE_UT_XCAP_404_NOT_FOUND) {
                        //Transfer here because it only consider for CB case.
                        if (((GSMPhone) mPhone).isOp05IccCard()) {
                            Rlog.d(LOG_TAG, "processResponse CODE_UT_XCAP_404_NOT_FOUND");
                            arException =
                                new CommandException(CommandException.Error.UT_XCAP_404_NOT_FOUND);
                        }
                    }
                }
                break;
            }
            case SS_REQUEST_GET_CALL_WAITING: {
                boolean queryVolteUser = false;
                if (mPhone instanceof GSMPhone) {
                    GSMPhone gsmPhone = (GSMPhone) mPhone;
                    if (gsmPhone.getTbcwMode() == GSMPhone.TBCW_UNKNOWN) {
                        queryVolteUser = true;
                    }
                }

                if (queryVolteUser) {
                    GSMPhone gsmPhone = (GSMPhone) mPhone;
                    Integer reqCode = (Integer) ssParmList.get(0);
                    int serviceClass;
                    boolean enable = false;
                    if (reqCode.intValue() == SS_REQUEST_GET_CALL_WAITING) {
                        serviceClass = ((Integer) ssParmList.get(1)).intValue();
                        resp = (Message) ssParmList.get(2);
                    } else {
                        enable = ((Boolean) ssParmList.get(1)).booleanValue();
                        serviceClass = ((Integer) ssParmList.get(2)).intValue();
                        resp = (Message) ssParmList.get(3);
                    }

                    ImsException imsException = null;
                    if (ar.exception != null && ar.exception instanceof ImsException) {
                        imsException = (ImsException) ar.exception;
                    }

                    if (ar.exception == null) {
                        gsmPhone.setTbcwMode(GSMPhone.TBCW_OPTBCW_VOLTE_USER);
                        gsmPhone.setTbcwToEnabledOnIfDisabled();
                        if (reqCode.intValue() == SS_REQUEST_GET_CALL_WAITING) {
                            gsmPhone.getTerminalBasedCallWaiting(resp);
                        } else {
                            gsmPhone.setTerminalBasedCallWaiting(enable, resp);
                        }
                    } else if (imsException != null
                            && imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        gsmPhone.setTbcwMode(GSMPhone.TBCW_OPTBCW_NOT_VOLTE_USER);
                        gsmPhone.setSystemProperty(PROPERTY_TERMINAL_BASED_CALL_WAITING_MODE,
                                TERMINAL_BASED_CALL_WAITING_DISABLED);
                        if (reqCode.intValue() == SS_REQUEST_GET_CALL_WAITING) {
                            mCi.queryCallWaiting(serviceClass, resp);
                        } else {
                            mCi.setCallWaiting(enable, serviceClass, resp);
                        }
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                    } else if (imsException != null
                            && imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        if (reqCode.intValue() == SS_REQUEST_GET_CALL_WAITING) {
                            mCi.queryCallWaiting(serviceClass, resp);
                        } else {
                            mCi.setCallWaiting(enable, serviceClass, resp);
                        }
                    } else {
                        gsmPhone.setTbcwToEnabledOnIfDisabled();
                        if (reqCode.intValue() == SS_REQUEST_GET_CALL_WAITING) {
                            gsmPhone.getTerminalBasedCallWaiting(resp);
                        } else {
                            gsmPhone.setTerminalBasedCallWaiting(enable, resp);
                        }
                    }
                    return;
                } else {
                    int serviceClass = ((Integer) ssParmList.get(1)).intValue();
                    resp = (Message) ssParmList.get(2);

                    if (ar.exception != null && ar.exception instanceof ImsException) {
                        ImsException imsException = (ImsException) ar.exception;
                        if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                            mCi.queryCallWaiting(serviceClass, resp);
                            mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                            return;
                        } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                            mCi.queryCallWaiting(serviceClass, resp);
                            return;
                        }
                    }

                    if (arResult != null) {
                        Rlog.d(LOG_TAG, "SS_REQUEST_GET_CALL_WAITING ssinfo check.");
                        if (arResult instanceof ImsSsInfo[]) {
                            arResult = (Object) handleCwQueryResult((ImsSsInfo[]) ar.result);
                        }
                    }
                }
                // Todo
                break;
            }
            case SS_REQUEST_SET_CALL_WAITING: {
                boolean enable = ((Boolean) ssParmList.get(1)).booleanValue();
                int serviceClass = ((Integer) ssParmList.get(2)).intValue();
                resp = (Message) ssParmList.get(3);
                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.setCallWaiting(enable, serviceClass, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.setCallWaiting(enable, serviceClass, resp);
                        return;
                    }
                }
                // Todo
                break;
            }
            case SS_REQUEST_GET_CLIR: {
                resp = (Message) ssParmList.get(1);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.getCLIR(resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.getCLIR(resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                } else {
                    int[] clirInfo = null;
                    if (ar.exception == null) {
                        Bundle ssInfo = (Bundle) arResult;
                        clirInfo = ssInfo.getIntArray(UT_BUNDLE_KEY_CLIR);
                        // clirInfo[0] = The 'n' parameter from TS 27.007 7.7
                        // clirInfo[1] = The 'm' parameter from TS 27.007 7.7
                        Rlog.d(LOG_TAG, "SS_REQUEST_GET_CLIR: CLIR param n=" + clirInfo[0]
                                + " m=" + clirInfo[1]);
                    }
                    arResult = clirInfo;
                }
                break;
            }
            case SS_REQUEST_SET_CLIR: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                resp = (Message) ssParmList.get(2);

                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.setCLIR(mode, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.setCLIR(mode, resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_GET_CLIP: {
                resp = (Message) ssParmList.get(1);
                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.queryCLIP(resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.queryCLIP(resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_SET_CLIP: {
                int mode = ((Integer) ssParmList.get(1)).intValue();
                boolean modeCs = (mode == 0) ? false : true;
                resp = (Message) ssParmList.get(2);
                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.setCLIP(modeCs, resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.setCLIP(modeCs, resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_GET_COLR: {
                resp = (Message) ssParmList.get(1);
                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.getCOLR(resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.getCOLR(resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_SET_COLR: {
                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_GET_COLP: {
                resp = (Message) ssParmList.get(1);
                if (ar.exception != null && ar.exception instanceof ImsException) {
                    ImsException imsException = (ImsException) ar.exception;
                    if (imsException.getCode() == ImsReasonInfo.CODE_UT_XCAP_403_FORBIDDEN) {
                        mCi.getCOLP(resp);
                        mPhone.setCsFallbackStatus(PhoneConstants.UT_CSFB_UNTIL_NEXT_BOOT);
                        return;
                    } else if (imsException.getCode() == ImsReasonInfo.CODE_UT_UNKNOWN_HOST) {
                        mCi.getCOLP(resp);
                        return;
                    }
                }

                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                }
                break;
            }
            case SS_REQUEST_SET_COLP: {
                if (((GSMPhone) mPhone).isOp01IccCard()) {
                    arException = new CommandException(
                            CommandException.Error.SPECAIL_UT_COMMAND_NOT_SUPPORTED);
                    arResult = null;
                    break;
                }
            }
            default:
                break;
        }

        if (arException != null && arException instanceof ImsException) {
            Rlog.d(LOG_TAG, "processResponse, imsException.getCode = " +
                    ((ImsException) arException).getCode());
            //arException = new CommandException(CommandException.Error.GENERIC_FAILURE);
            arException = getCommandException((ImsException) arException);
        }

        if (resp != null) {
            AsyncResult.forMessage(resp, arResult, arException);
            resp.sendToTarget();
        }
    }

    private CommandException getCommandException(ImsException imsException) {
        switch(imsException.getCode()) {
            case ImsReasonInfo.CODE_UT_XCAP_409_CONFLICT:
                if (((GSMPhone) mPhone).isEnableXcapHttpResponse409()) {
                    Rlog.d(LOG_TAG, "getCommandException UT_XCAP_409_CONFLICT");
                    return new CommandException(CommandException.Error.UT_XCAP_409_CONFLICT);
                }
                break;
            default:
                Rlog.d(LOG_TAG, "getCommandException GENERIC_FAILURE");
                return new CommandException(CommandException.Error.GENERIC_FAILURE);
        }
        Rlog.d(LOG_TAG, "getCommandException GENERIC_FAILURE");
        return new CommandException(CommandException.Error.GENERIC_FAILURE);
    }

    public void queryCallForwardStatus(int cfReason, int serviceClass,
            String number, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CALL_FORWARD));
        ssParmList.add(new Integer(cfReason));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(number);
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCallForward(int action, int cfReason, int serviceClass,
            String number, int timeSeconds, Message response) {

        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CALL_FORWARD));
        ssParmList.add(new Integer(action));
        ssParmList.add(new Integer(cfReason));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(number);
        ssParmList.add(new Integer(timeSeconds));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void queryCallForwardInTimeSlotStatus(int cfReason,
            int serviceClass, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CALL_FORWARD_TIME_SLOT));
        ssParmList.add(new Integer(cfReason));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCallForwardInTimeSlot(int action, int cfReason, int serviceClass,
            String number, int timeSeconds, long[] timeSlot, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CALL_FORWARD_TIME_SLOT));
        ssParmList.add(new Integer(action));
        ssParmList.add(new Integer(cfReason));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(number);
        ssParmList.add(new Integer(timeSeconds));
        ssParmList.add(timeSlot);
        ssParmList.add(response);
        send(ssParmList);
    }

    public void queryFacilityLock(String facility, String password,
            int serviceClass, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CALL_BARRING));
        ssParmList.add(facility);
        ssParmList.add(password);
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setFacilityLock(String facility, boolean lockState,
            String password, int serviceClass, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CALL_BARRING));
        ssParmList.add(facility);
        ssParmList.add(new Boolean(lockState));
        ssParmList.add(password);
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void queryCallWaiting(int serviceClass, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CALL_WAITING));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCallWaiting(boolean enable, int serviceClass, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CALL_WAITING));
        ssParmList.add(new Boolean(enable));
        ssParmList.add(new Integer(serviceClass));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void getCLIR(Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CLIR));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCLIR(int clirMode, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CLIR));
        ssParmList.add(new Integer(clirMode));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void getCLIP(Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_CLIP));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCLIP(int clipMode, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_CLIP));
        ssParmList.add(new Integer(clipMode));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void getCOLR(Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_COLR));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCOLR(int colrMode, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_COLR));
        ssParmList.add(new Integer(colrMode));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void getCOLP(Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_GET_COLP));
        ssParmList.add(response);
        send(ssParmList);
    }

    public void setCOLP(int colpMode, Message response) {
        ArrayList<Object> ssParmList = new ArrayList<Object>();
        ssParmList.add(new Integer(SS_REQUEST_SET_COLP));
        ssParmList.add(new Integer(colpMode));
        ssParmList.add(response);
        send(ssParmList);
    }

    private void setCSCurrentPhoneId(int phoneId) {
        Rlog.d(LOG_TAG, "setCSCurrentPhoneId: " + phoneId);
        SystemProperties.set(PROPERTY_CS_CURRENT_PHONE_ID, String.valueOf(phoneId));
    }

    void send(ArrayList<Object> ssParmList) {
        Message msg;
        msg = mSSRequestHandler.obtainMessage(EVENT_SS_SEND, ssParmList);
        msg.sendToTarget();
    }
}

