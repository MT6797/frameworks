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

package com.mediatek.internal.telephony;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiOffListener;

import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.ims.internal.IImsService;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DctController;
import com.android.internal.telephony.dataconnection.DctController.DcStateParam;
import com.android.internal.telephony.dataconnection.DcSwitchStateMachine;
import com.mediatek.ims.WfcReasonInfo;


public class ImsSwitchController extends Handler  {
    static final String LOG_TAG = "ImsSwitchController";
    public static final String IMS_SERVICE = "ims";
    /// M: ALPS02373062. Update NW_TYPE_WIFI string,
    /// workaround for WIFI status error in flight mode. @{
    public static final String NW_TYPE_WIFI = "MOBILE_IMS";
    /// @}
    public static final String NW_SUB_TYPE_IMS = "IMS";

    private Context mContext;
    private CommandsInterface[] mCi;
    private int mPhoneCount;
    private static IImsService mImsService = null;
    private DcStateParam mDcStateParam = null;
    private RadioPowerInterface mRadioPowerIf;
    private boolean mIsInVoLteCall = false;
    private ImsServiceDeathRecipient mDeathRecipient = new ImsServiceDeathRecipient();

    private boolean mNeedTurnOffWifi = false;
    private int REASON_INVALID = -1;
    private int mReason = REASON_INVALID;

    protected final Object mLock = new Object();

    /** events id definition */
    protected static final int EVENT_RADIO_NOT_AVAILABLE_PHONE1    = 1;
    protected static final int EVENT_RADIO_AVAILABLE_PHONE1        = 2;
    protected static final int EVENT_RADIO_NOT_AVAILABLE_PHONE2    = 3;
    protected static final int EVENT_RADIO_AVAILABLE_PHONE2        = 4;
    protected static final int EVENT_DC_SWITCH_STATE_CHANGE        = 5;
    protected static final int EVENT_CONNECTIVITY_CHANGE           = 6;

    static final int DEFAULT_MAJOR_CAPABILITY_PHONE_ID    = 0;
    static final int DEFAULT_IMS_STATE = 0;
    static final int DEFAULT_INVALID_PHONE_ID = -1;
    static final int DISABLE_WIFI_FLIGHTMODE = 1;

    private static final String PROPERTY_VOLTE_ENALBE = "persist.mtk.volte.enable";
    private static final String PROPERTY_WFC_ENALBE = "persist.mtk.wfc.enable";
    private static final String PROPERTY_IMS_VIDEO_ENALBE = "persist.mtk.ims.video.enable";

    ImsSwitchController(Context context , int phoneCount, CommandsInterface[] ci) {

        log("Initialize ImsSwitchController");

        mContext = context;
        mCi = ci;
        mPhoneCount = phoneCount;

        // For TC1, do not use MTK IMS stack solution
        if (SystemProperties.get("ro.mtk_ims_support").equals("1") &&
            !SystemProperties.get("ro.mtk_tc1_feature").equals("1")) {

            IntentFilter intentFilter = new IntentFilter(ImsManager.ACTION_IMS_SERVICE_DOWN);
            intentFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            intentFilter.addAction(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);

            if (SystemProperties.get("ro.mtk_wfc_support").equals("1")) {
                intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            }
            mContext.registerReceiver(mIntentReceiver, intentFilter);
            mRadioPowerIf = new RadioPowerInterface();
            RadioManager.registerForRadioPowerChange(LOG_TAG, mRadioPowerIf);

            mCi[PhoneConstants.SIM_ID_1].registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE_PHONE1, null);
            mCi[PhoneConstants.SIM_ID_1].registerForAvailable(this, EVENT_RADIO_AVAILABLE_PHONE1, null);

            if (mPhoneCount > PhoneConstants.SIM_ID_2) {
                mCi[PhoneConstants.SIM_ID_2].registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE_PHONE2, null);
                mCi[PhoneConstants.SIM_ID_2].registerForAvailable(this, EVENT_RADIO_AVAILABLE_PHONE2, null);
            }
        }
    }

    class RadioPowerInterface implements IRadioPower {
        public void notifyRadioPowerChange(boolean power, int phoneId) {

            log("notifyRadioPowerChange, power:" + power + " phoneId:" + phoneId);

            if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == phoneId) {

                boolean isVoLTEEnable = isVoLTEEnable();
                boolean isVoWiFiEnable = isVoWiFiEnable();

                log("notifyRadioPowerChange, isVoLTEEnable:" + isVoLTEEnable +
                        " isVoWiFiEnable:" + isVoWiFiEnable);

                if (power) {

                    if (isVoLTEEnable || isVoWiFiEnable) {
                         switchImsCapability(true, phoneId);
                    }
                } else {
                    /**
                     * When radio off we need to check the vowifi is using ims service or not.
                     * If VoWiFi is disable then we can disable the IMS capability.
                     */
                    if (!isVoWiFiEnable) {
                         switchImsCapability(false, phoneId);
                    }
                }
            }
        }
    }

    /**
     * Death recipient class for monitoring IMS service.
     *
     * @param phoneId  to indicate which phone.
     */
    private void checkAndBindImsService(int phoneId) {
        IBinder b = ServiceManager.getService(IMS_SERVICE);

        if (b != null) {
            try {
                b.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
        }

        mImsService = IImsService.Stub.asInterface(b);
        log("checkAndBindImsService: mImsService = " + mImsService);

    }

    /**
     * Death recipient class for monitoring IMS service.
     */
    private class ImsServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mImsService = null;
        }
    }

    /**
     * To check VoLTE is enable or not. Only return true when platform support and setting enable.
     */
    private boolean isVoLTEEnable() {

        if (SystemProperties.get("ro.mtk_ims_support").equals("1") &&
            SystemProperties.get(PROPERTY_VOLTE_ENALBE).equals("1")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * To check VoWiFi is enable or not. Only return true when platform support and setting enable.
     */
    private boolean isVoWiFiEnable() {

        if (SystemProperties.get("ro.mtk_wfc_support").equals("1") &&
            SystemProperties.get(PROPERTY_WFC_ENALBE).equals("1")) {
            return true;
        } else {
            return false;
        }
    }

    private void switchImsCapability(boolean on, int phoneId) {
        log("switchImsCapability, on:" + on + " phoneId:" + phoneId);
        if (mImsService == null) {
            checkAndBindImsService(phoneId);
        }

        if (mImsService != null) {
            if(on) {
                try {
                    mImsService.turnOnIms(phoneId);
                } catch (RemoteException e) {
                    log("RemoteException can't turn on ims");
                }
            } else {
                try {
                    mImsService.turnOffIms(phoneId);
                } catch (RemoteException e) {
                    log("RemoteException can't turn off ims");
                }
            }
        } else {
            log("switchImsCapability: ImsService not ready !!!");
        }
    }

    private void registerEvent() {
        log("registerEvent, major phoneid:" + RadioCapabilitySwitchUtil.getMainCapabilityPhoneId());

        DctController.getInstance().registerForDcSwitchStateChange(this, EVENT_DC_SWITCH_STATE_CHANGE, null,
                DctController.getInstance().new DcStateParam(LOG_TAG, true));
        //RadioManager.getInstance().registerForRadioPowerChange(LOG_TAG, mRadioPowerIf);
        if (SystemProperties.get("ro.mtk_wfc_support").equals("1")) {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            onReceiveWifiStateChange(wifiManager.getWifiState());
            wifiManager.addWifiOffListener(new WifiOffListener() {
                public void onWifiOff(int reason) {
                    mReason = reason;
                    mNeedTurnOffWifi = true;
                    log("onWifiOff reason = " + reason);
                    //If no need to deregister, directly turn off wifi
                    if (isImsDeregisterRequired() == false) {
                        WifiManager wifiManager = (WifiManager) mContext
                                .getSystemService(Context.WIFI_SERVICE);
                        wifiManager.setWifiDisabled(mReason);
                        mNeedTurnOffWifi = false;
                    }
                }
            });
        }
    }

    private void unregisterEvent() {
        log("unregisterEvent, major phoneid:" + RadioCapabilitySwitchUtil.getMainCapabilityPhoneId());
        DctController.getInstance().unregisterForDcSwitchStateChange(this);
        //RadioManager.getInstance().unregisterForRadioPowerChange(mRadioPowerIf);
    }

    private void handleDcStateAttaching(DcStateParam param) {
        synchronized (mLock) {
            log("handleDcStateAttaching param.getPhoneId():" + param.getPhoneId());

            if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == param.getPhoneId()) {

                boolean isVoLTEEnable = isVoLTEEnable();
                boolean isVoWiFiEnable = isVoWiFiEnable();

                log("handleDcStateAttaching, isVoLTEEnable:" + isVoLTEEnable +
                        " isVoWiFiEnable:" + isVoWiFiEnable);

                if (isVoLTEEnable || isVoWiFiEnable) {
                    switchImsCapability(true, param.getPhoneId());
                }
            }
        }
    }

    private void handleDcStatePreCheckDisconnect(DcStateParam param) {
        synchronized (mLock) {
            if (mIsInVoLteCall == true) {
                log("handleDcStatePreCheckDisconnect, in volte call, suspend DcState preCheck");
                mDcStateParam = param;
                return;
            }

            log("handleDcStatePreCheckDisconnect, param.getPhoneId():" + param.getPhoneId());

            if (param.getPhoneId() == RadioCapabilitySwitchUtil.getMainCapabilityPhoneId()) {
                if (mImsService == null) {
                    checkAndBindImsService(param.getPhoneId());
                }

                if (mImsService != null) {
                    int state = PhoneConstants.IMS_STATE_DISABLED;
                    try {
                      state = mImsService.getImsState();
                    } catch (RemoteException e) {
                        log("RemoteException can't get ims state");
                    }

                    if (state != PhoneConstants.IMS_STATE_DISABLED) {
                        try {
                            mImsService.turnOffIms(param.getPhoneId());
                        } catch (RemoteException e) {
                            log("RemoteException can't turn off ims");
                        }
                        mDcStateParam = param;
                    } else {
                        log("handleDcStatePreCheckDisconnect: ims is disable and confirm directly");
                        param.confirmPreCheckDetach();
                    }
                } else {
                    log("handleDcStatePreCheckDisconnect: ImsService not ready !!!");
                    mDcStateParam = param;
                }
            } else {
                param.confirmPreCheckDetach();
            }
        }
    }

    private void onReceiveDcSwitchStateChange(DcStateParam param) {
        log("handleMessage param.getState: " + param.getState() + " param.getReason(): " + param.getReason());
        switch (param.getState()) {
            case DcSwitchStateMachine.DCSTATE_PREDETACH_CHECK:
                handleDcStatePreCheckDisconnect(param);
                break;
            case DcSwitchStateMachine.DCSTATE_ATTACHING:
                if (!param.getReason().equals("Lost Connection")) {
                    handleDcStateAttaching(param);
                }
                break;
            default:
                break;
        }
    }

    private void onReceivePhoneStateChange(int phoneId, int phoneType, PhoneConstants.State phoneState) {
        synchronized (mLock) {
            log("onReceivePhoneStateChange phoneId:" + phoneId +
                    " phoneType: " + phoneType + " phoneState: " + phoneState);
            log("mIsInVoLteCall: " + mIsInVoLteCall);


            if (mIsInVoLteCall == true) {
                if (phoneId == RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() &&
                        phoneState == PhoneConstants.State.IDLE) {
                    mIsInVoLteCall = false;
                    if (mDcStateParam != null) {
                        if ( mImsService == null) {
                            checkAndBindImsService(phoneId);
                        }

                        if (mImsService != null) {
                            try {
                            mImsService.turnOffIms(mDcStateParam.getPhoneId());
                            } catch (RemoteException e) {
                                log("RemoteException can't turn on ims");
                            }
                        } else {
                            log("onReceivePhoneStateChange: ImsService not ready !!!");
                        }
                    }
                }
            } else {
                if (phoneType == RILConstants.IMS_PHONE &&
                        !(phoneState == PhoneConstants.State.IDLE)) {
                    mIsInVoLteCall = true;
                }
            }
        }
    }

    private void confirmPreCheckDetachIfNeed() {
        synchronized (mLock) {
            if (mDcStateParam != null) {
                log("confirmPreCheckDetachIfNeed, phoneId:" + mDcStateParam.getPhoneId());
                mDcStateParam.confirmPreCheckDetach();
                mDcStateParam = null;
            }
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            log("mIntentReceiver Receive action " + action);

            if (action.equals(ImsManager.ACTION_IMS_SERVICE_DOWN)) {
                confirmPreCheckDetachIfNeed();
                mIsInVoLteCall = false;
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED) ||
                    action.equals(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED)) {
                String state = intent.getStringExtra(PhoneConstants.STATE_KEY);
                onReceivePhoneStateChange(
                        intent.getIntExtra(PhoneConstants.PHONE_KEY, DEFAULT_INVALID_PHONE_ID),
                        intent.getIntExtra(PhoneConstants.PHONE_TYPE_KEY, RILConstants.NO_PHONE),
                        Enum.valueOf(PhoneConstants.State.class, state));
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                onReceiveWifiStateChange(wifiState);
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE)) {
                String state = DctController.getInstance().getDcSwitchState(
                        RadioCapabilitySwitchUtil.getMainCapabilityPhoneId());
                boolean isVoLTEEnable = isVoLTEEnable();
                boolean isVoWiFiEnable = isVoWiFiEnable();
                log("handle ACTION_SET_RADIO_CAPABILITY_DONE: data state:" + state +
                    " isVoLTEEnable:" + isVoLTEEnable + " isVoWiFiEnable:" + isVoWiFiEnable);

                if ((isVoLTEEnable || isVoWiFiEnable) &&
                        (state.equals(DcSwitchStateMachine.DCSTATE_ATTACHING) ||
                         state.equals(DcSwitchStateMachine.DCSTATE_ATTACHED))) {
                    switchImsCapability(true,
                            RadioCapabilitySwitchUtil.getMainCapabilityPhoneId());
                }
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                sendMessage(obtainMessage(EVENT_CONNECTIVITY_CHANGE));
            }
        }
    };

    private String eventIdtoString(int what) {
        String str = null;
        switch (what) {
            case EVENT_RADIO_NOT_AVAILABLE_PHONE1:
                str = "RADIO_NOT_AVAILABLE_PHONE1";
                break;
            case EVENT_RADIO_NOT_AVAILABLE_PHONE2:
                str = "RADIO_NOT_AVAILABLE_PHONE2";
                break;
            case EVENT_RADIO_AVAILABLE_PHONE1:
                str = "RADIO_AVAILABLE_PHONE1";
                break;
            case EVENT_RADIO_AVAILABLE_PHONE2:
                str = "RADIO_AVAILABLE_PHONE2";
                break;
            case EVENT_DC_SWITCH_STATE_CHANGE:
                str = "DC_SWITCH_STATE_CHANGE";
                break;
            default:
                break;

        }
        return str;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        log("handleMessage msg.what: " + eventIdtoString(msg.what));
        int phoneId = 0;
        switch (msg.what) {
            case EVENT_RADIO_NOT_AVAILABLE_PHONE1:
                phoneId = PhoneConstants.SIM_ID_1;
                if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == phoneId) {
                    unregisterEvent();
                }
                break;
            case EVENT_RADIO_NOT_AVAILABLE_PHONE2:
                phoneId = PhoneConstants.SIM_ID_2;
                if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == phoneId) {
                    unregisterEvent();
                }
                break;
            case EVENT_RADIO_AVAILABLE_PHONE1:
                phoneId = PhoneConstants.SIM_ID_1;
                if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == phoneId) {
                    registerEvent();
                }
                break;
            case EVENT_RADIO_AVAILABLE_PHONE2:
                phoneId = PhoneConstants.SIM_ID_2;
                if (RadioCapabilitySwitchUtil.getMainCapabilityPhoneId() == phoneId) {
                    registerEvent();
                }
                break;
            case EVENT_DC_SWITCH_STATE_CHANGE:
                DcStateParam param = (DcStateParam) ar.result;
                onReceiveDcSwitchStateChange(param);
                break;
            case EVENT_CONNECTIVITY_CHANGE:
                handleConnectivityChange();
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    /**
     * To receive wifi state change event
     *
     * @param wifiState to indicate the wifi state
     */
    private void onReceiveWifiStateChange(int wifiState) {

        boolean isVoWiFiEnable = isVoWiFiEnable();
        log("onReceiveWifiStateChange wifiState = " + wifiState +
                " isVoWiFiEnable:" + isVoWiFiEnable);

        if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
            if (isVoWiFiEnable) {
                switchImsCapability(true, RadioCapabilitySwitchUtil.getMainCapabilityPhoneId());
           }
        }
    }

    /**
     * To check we need to turn off IMS or not when wifi state is changed.
     */
    private boolean isImsDeregisterRequired() {
        int regStat = WfcReasonInfo.CODE_UNSPECIFIED;
        ImsManager imsManager = ImsManager.getInstance(mContext,
                SubscriptionManager.getDefaultVoicePhoneId());
        TelephonyManager telephonyMgr = (TelephonyManager) mContext
                .getSystemService(Context.TELEPHONY_SERVICE);
        boolean isVoLTeEnable = isVoLTEEnable();
        int phoneId = RadioCapabilitySwitchUtil.getMainCapabilityPhoneId();
        int netType = telephonyMgr.getNetworkType(
                SubscriptionManager.getSubIdUsingPhoneId(phoneId));

        regStat = imsManager.getWfcStatusCode(); // get registration

        log("isImsDeregisterRequired regStat = " + regStat + " mReason = "
                + mReason + "netType = " + netType + " phoneId = " + phoneId +
                " isVoLTeEnable = " + isVoLTeEnable);
        /*Need to turn off IMS if
                  (1) Registered over epdg and wifi turned off through flight mode
                  (2) Registered over epdg and LTE not available or VoLTE switch off.
             */
        if (((WfcReasonInfo.CODE_WFC_SUCCESS == regStat) && (mReason == DISABLE_WIFI_FLIGHTMODE))
                || ((WfcReasonInfo.CODE_WFC_SUCCESS == regStat) &&
                ((netType != TelephonyManager.NETWORK_TYPE_LTE) || (isVoLTeEnable == false)))) {
            switchImsCapability(false,phoneId);
            return true;
        } else {
            // Ims dereg not required
            log("IMS registration false");
            return false;
        }

    }

    /**
     * handle connectivity state change event to disable wifi if epdg is disconnected.
     */
    private void handleConnectivityChange() {
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo nwInfo = connectivityManager
                .getNetworkInfo(ConnectivityManager.TYPE_MOBILE_IMS);
        String typeName = nwInfo.getTypeName();
        String subTypeName = nwInfo.getSubtypeName();
        boolean isEpdgConnected = true;
        boolean isVoLTEEnable = isVoLTEEnable();
        log("handleConnectivityChange typeName =" + typeName
                + " subTypeName = " + subTypeName + " isVoLTEEnable = "
                + isVoLTEEnable + " mNeedTurnOffWifi = " + mNeedTurnOffWifi);
        /// M: ALPS02373062. Workaround for WiFi status error in flight mode. @{
        if ((mNeedTurnOffWifi == true) && (NW_TYPE_WIFI.equals(typeName))) {
        /// @}
            isEpdgConnected = nwInfo.isConnected();
            log("handleConnectivityChange isEpdgConnected =" + isEpdgConnected);
            if (isEpdgConnected == false) {
                WifiManager wifiManager = (WifiManager) mContext
                        .getSystemService(Context.WIFI_SERVICE);
                wifiManager.setWifiDisabled(mReason);
                mNeedTurnOffWifi = false;

                //If volte switch is turned on, need to turn on IMS again
                if (isVoLTEEnable == true) {
                    int phoneId = RadioCapabilitySwitchUtil.getMainCapabilityPhoneId();
                    switchImsCapability(true,phoneId);
                }
            }
        }
    }
    private static void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
