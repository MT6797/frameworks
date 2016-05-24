/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.ICarrierConfigLoader;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RestrictedState;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.SubscriptionController;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.dataconnection.DcTrackerBase;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.SpnOverride;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.mediatek.common.MPlugin;
import com.mediatek.common.telephony.IServiceStateExt;
import com.mediatek.internal.telephony.RadioManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.IccUtils;

import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;
import com.mediatek.internal.telephony.worldphone.WorldPhoneUtil;

/**
 * {@hide}
 */
final class GsmServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "GsmSST";
    static final boolean VDBG = true;
    //CAF_MSIM make it private ??
    private static final int EVENT_ALL_DATA_DISCONNECTED = 1001;
    private GSMPhone mPhone;
    GsmCellLocation mCellLoc;
    GsmCellLocation mNewCellLoc;
    int mPreferredNetworkType;

    private int mMaxDataCalls = 1;
    private int mNewMaxDataCalls = 1;
    private int mReasonDataDenied = -1;
    private int mNewReasonDataDenied = -1;

    /**
     * GSM roaming status solely based on TS 27.007 7.2 CREG. Only used by
     * handlePollStateResult to store CREG roaming result.
     */
    private boolean mGsmRoaming = false;

    /**
     * Data roaming status solely based on TS 27.007 10.1.19 CGREG. Only used by
     * handlePollStateResult to store CGREG roaming result.
     */
    private boolean mDataRoaming = false;

    /**
     * Mark when service state is in emergency call only mode
     */
    private boolean mEmergencyOnly = false;

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    private boolean mNeedFixZoneAfterNitz = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    private boolean mGotCountryCode = false;
    private ContentResolver mCr;

    /** Boolean is true is setTimeFromNITZString was called */
    private boolean mNitzUpdatedTime = false;

    //[ALPS01825832]
    private static boolean[] sReceiveNitz
            = new boolean[TelephonyManager.getDefault().getPhoneCount()];

    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /** Started the recheck process after finding gprs should registered but not. */
    private boolean mStartedGprsRegCheck = false;

    /** Already sent the event-log for no gprs register. */
    private boolean mReportedGprsNoReg = false;

    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Notification type. */
    static final int PS_ENABLED = 1001;            // Access Control blocks data service
    static final int PS_DISABLED = 1002;           // Access Control enables data service
    static final int CS_ENABLED = 1003;            // Access Control blocks all voice/sms service
    static final int CS_DISABLED = 1004;           // Access Control enables all voice/sms service
    static final int CS_NORMAL_ENABLED = 1005;     // Access Control blocks normal voice/sms service
    static final int CS_EMERGENCY_ENABLED = 1006;  // Access Control blocks emergency call service

    /** Notification id. */
    static final int PS_NOTIFICATION = 888;  // Id to update and cancel PS restricted
    static final int CS_NOTIFICATION = 999;  // Id to update and cancel CS restricted

    /** mtk01616_120613 Notification id. */
    static final int REJECT_NOTIFICATION = 890;

    /** [ALPS01558804] Add notification id for using some spcial icc card*/
    static final int SPECIAL_CARD_TYPE_NOTIFICATION = 8903;

    private int gprsState = ServiceState.STATE_OUT_OF_SERVICE;
    private int newGPRSState = ServiceState.STATE_OUT_OF_SERVICE;

    private String mHhbName = null;
    private String mCsgId = null;
    private int mFemtocellDomain = 0;

    /* ALPS00236452: manufacturer maintained table for specific operator with multiple PLMN id */
    // ALFMS00040828 - add "46008"
    public static final String[][] customEhplmn = {{"46000", "46002", "46007", "46008"},
                                       {"45400", "45402", "45418"},
                                       {"46001", "46009"},
                                       {"45403", "45404"},
                                       {"45412", "45413"},
                                       {"45416", "45419"},
                                       {"45501", "45504"},
                                       {"45503", "45505"},
                                       {"45002", "45008"},
                                       {"52501", "52502"},
                                       {"43602", "43612"},
                                       {"52010", "52099"},
                                       {"24001", "24005"},
                                       {"26207", "26208"},
                                       {"23430", "23431", "23432", "23433", "23434"},
                                       {"72402", "72403", "72404"},
                                       {"72406", "72410", "72411", "72423"},
                                       {"72432", "72433", "72434"},
                                       {"31026", "31031", "310160", "310200", "310210", "310220",
                                        "310230", "310240", "310250", "310260", "310270", "310280",
                                        "311290", "310300", "310310", "310320", "311330", "310660",
                                        "310800"},
                                       {"310150", "310170", "310380", "310410"},
                                       {"31033", "310330"},
                                       //ALPS02446235[
                                       {"21401", "21402", "21403", "21404", "21405", "21406",
                                        "21407", "21408", "21409", "21410", "21411", "21412",
                                        "21413", "21414", "21415", "21416", "21417", "21418",
                                        "21419", "21420", "21421"}
                                       //ALPS02446235]
                                       };

    /// M: [ALPS02503235] add operator considered roaming configures
    private static final String[][] customOperatorConsiderRoamingMcc = {
        {"404", "405"}
    };

    public boolean dontUpdateNetworkStateFlag = false;

//MTK-START [mtk03851][111124]MTK added
    protected static final int EVENT_SET_AUTO_SELECT_NETWORK_DONE = 50;
    /** Indicate the first radio state changed **/
    private boolean mFirstRadioChange = true;
    private int explict_update_spn = 0;


    private String mLastRegisteredPLMN = null;
    private String mLastPSRegisteredPLMN = null;
    private boolean mEverPollSignalStrength = false; // ALPS02332737
    /* ALPS02281049 only allow Raido off once when already POWER_OFF */
    private boolean mEverRadioHandled = false;
    private boolean mEverIVSR = false;  /* ALPS00324111: at least one chance to do IVSR  */

    //MTK-ADD: for for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS only
    //SIM card or CS domain network registeration temporary failure
    private boolean isCsInvalidCard = false;

    private IServiceStateExt mServiceStateExt;

    private String mLocatedPlmn = null;
    private int mPsRegState = ServiceState.STATE_OUT_OF_SERVICE;
    private int mPsRegStateRaw = ServiceState.STATE_OUT_OF_SERVICE;

    /** [ALPS01558804] Add notification id for using some spcial icc card*/
    private String mSimType = "";
    //MTK-START : [ALPS01262709] update TimeZone by MCC/MNC
    /* manufacturer maintained table for specific timezone
         with multiple timezone of country in time_zones_by_country.xml */
    private String[][] mTimeZoneIdOfCapitalCity = {{"au", "Australia/Sydney"},
                                                   {"br", "America/Sao_Paulo"},
                                                   {"ca", "America/Toronto"},
                                                   {"cl", "America/Santiago"},
                                                   {"es", "Europe/Madrid"},
                                                   {"fm", "Pacific/Ponape"},
                                                   {"gl", "America/Godthab"},
                                                   {"id", "Asia/Jakarta"},
                                                   {"kz", "Asia/Almaty"},
                                                   {"mn", "Asia/Ulaanbaatar"},
                                                   {"mx", "America/Mexico_City"},
                                                   {"pf", "Pacific/Tahiti"},
                                                   {"pt", "Europe/Lisbon"},
                                                   {"ru", "Europe/Moscow"},
                                                   {"us", "America/New_York"}
                                                  };
    //MTK-END [ALPS01262709]
    /* manufacturer maintained table for the case that
       MccTable.defaultTimeZoneForMcc() returns unexpected timezone */
    private String[][] mTimeZoneIdByMcc = {{"460", "Asia/Shanghai"},
                                           {"404", "Asia/Calcutta"},
                                           {"454", "Asia/Hong_Kong"}
                                          };

    private boolean mIsImeiLock = false;

    // IMS
    private int mImsRegInfo = 0;
    private int mImsExtInfo = 0;

    //[ALPS01132085] for NetworkType display abnormal
    //[ALPS01497861] when ipo reboot this value must be ture
    //private boolean mIsScreenOn = true;  //[ALPS01810775,ALPS01868743]removed
    private boolean mIsForceSendScreenOnForUpdateNwInfo = false;

    private static Timer mCellInfoTimer = null;

    protected boolean bHasDetachedDuringPolling = false;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mPhone.mIsTheCurrentActivePhone) {
                Rlog.e(LOG_TAG, "Received Intent " + intent +
                        " while being destroyed. Ignoring.");
                return;
            }

            log("BroadcastReceiver: " + intent.getAction());
            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                // update emergency string whenever locale changed
                ///M: Support language update for spn display. @{
                // updateSpnDisplay();
                refreshSpnDisplay();
                /// @}
            } else if (intent.getAction().equals(ACTION_RADIO_OFF)) {
                mAlarmSwitch = false;
                DcTrackerBase dcTracker = mPhone.mDcTracker;
                powerOffRadioSafely(dcTracker);
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                //[ALPS02042805] pollState after RILJ noitfy URC when screen on
                //pollState();
                explict_update_spn = 1;
                if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    try {
                        if (mServiceStateExt.needEMMRRS()) {
                            if (isCurrentPhoneDataConnectionOn()) {
                                getEINFO(EVENT_ENABLE_EMMRRS_STATUS);
                            }
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    try {
                        if (mServiceStateExt.needEMMRRS()) {
                            if (isCurrentPhoneDataConnectionOn()) {
                                getEINFO(EVENT_DISABLE_EMMRRS_STATUS);
                            }
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            } else if (intent.getAction().equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simState = IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;

                int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
                if (slotId == mPhone.getPhoneId()) {
                    simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                    log("SIM state change, slotId: " + slotId + " simState[" + simState + "]");
                }

                //[ALPS01558804] MTK-START: send notification for using some spcial icc card
                if ((simState.equals(IccCardConstants.INTENT_VALUE_ICC_READY))
                        && (mSimType.equals(""))) {
                    mSimType = PhoneFactory.getPhone(mPhone.getPhoneId())
                            .getIccCard().getIccCardType();

                    log("SimType= " + mSimType);

                    if ((mSimType != null) && (!mSimType.equals(""))) {
                        if (mSimType.equals("SIM") || mSimType.equals("USIM")) {
                            if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                                try {
                                    if (mServiceStateExt.needIccCardTypeNotification(mSimType)) {
                                    //[ALPS01600557] - start : need to check 3G Capability SIM
                                        if (TelephonyManager.getDefault().getPhoneCount() > 1) {
                                            int capabilityPhoneId = Integer.valueOf(SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1"));
                                            log("capabilityPhoneId=" + capabilityPhoneId);
                                            if (mPhone.getPhoneId() == (capabilityPhoneId - 1)) {
                                                setSpecialCardTypeNotification(mSimType, 0, 0);
                                            }
                                        } else {
                                            setSpecialCardTypeNotification(mSimType, 0, 0);
                                        }
                                    }
                                } catch (RuntimeException e) {
                                      e.printStackTrace();
                                }
                            }
                        }
                    }
                }

                /* [ALPS01602110] START */
                if (slotId == mPhone.getPhoneId()
                        && simState.equals(IccCardConstants.INTENT_VALUE_ICC_IMSI)) {
                    setDeviceRatMode(slotId);
                }
                /* [ALPS01602110] END */

                if (simState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT) ||
                    simState.equals(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                    mSimType = "";
                    NotificationManager notificationManager = (NotificationManager)
                            context.getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.cancel(SPECIAL_CARD_TYPE_NOTIFICATION);

                    //[ALPS01825832] reset flag
                    setReceivedNitz(mPhone.getPhoneId(), false);
                    //[ALPS01839778] reset flag for user change SIM card
                    mLastRegisteredPLMN = null;
                    mLastPSRegisteredPLMN = null;

                    //[ALPS01509553]-start:reset flag when sim plug-out
                    if (simState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)) {
                        dontUpdateNetworkStateFlag = false;
                    }
                    //[ALPS01509553]-end
                }
                //[ALPS01558804] MTK-END: send notification for using some special icc card
            } else if (intent.getAction().equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                /* ALPS01845510: force notify service state changed to handle service state changed
                   happend during sub info not ready short period  */
                log("ACTION_SUBINFO_RECORD_UPDATED force notifyServiceStateChanged: " + mSS);
                mPhone.notifyServiceStateChanged(mSS);
                if (intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                        SubscriptionManager.EXTRA_VALUE_NOCHANGE)
                        != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                    updateSpnDisplay(true);
                }
            }
        }
    };
    /// M: Simulate IMS Registration @{
    private boolean mImsRegistry = false;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mImsRegistry = intent.getBooleanExtra("registry", false);
            Rlog.w(LOG_TAG, "Simulate IMS Registration: " + mImsRegistry);
            int[] result = new int[] {
                (mImsRegistry ? 1 : 0),
                15 };
            AsyncResult ar = new AsyncResult(null, result, null);
            sendMessage(obtainMessage(EVENT_IMS_REGISTRATION_INFO, ar));
        }
    };
    /// @}
    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Rlog.i("GsmServiceStateTracker", "Auto time state changed");
            revertToNitzTime();
        }
    };

    private ContentObserver mAutoTimeZoneObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Rlog.i("GsmServiceStateTracker", "Auto time zone state changed");
            revertToNitzTimeZone();
        }
    };

    //MTK-START [ALPS00368272]
    private ContentObserver mDataConnectionSettingObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            log("Data Connection Setting changed");
            if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                try {
                    if (mServiceStateExt.needEMMRRS()) {
                        if (isCurrentPhoneDataConnectionOn()) {
                            getEINFO(EVENT_ENABLE_EMMRRS_STATUS);
                        } else {
                            getEINFO(EVENT_DISABLE_EMMRRS_STATUS);
                        }
                    }
                } catch (RuntimeException e) {
                        e.printStackTrace();
                }
            }
        }
    };
    //MTK-END [ALPS00368272]

    //[ALPS01577029]-START:To support auto switch rat mode to 2G only for 3M TDD csfb project when
    //we are not in china
    private ContentObserver mMsicFeatureConfigObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Rlog.i("GsmServiceStateTracker", "Msic Feature Config has changed");
            pollState();
        }
    };
    //[ALPS01577029]-END

    public GsmServiceStateTracker(GSMPhone phone) {
        super(phone, phone.mCi, new CellInfoGsm());

        mPhone = phone;
        mCellLoc = new GsmCellLocation();
        mNewCellLoc = new GsmCellLocation();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                mServiceStateExt = MPlugin.createInstance(
                        IServiceStateExt.class.getName(), phone.getContext());
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        mCi.registerForVoiceNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED, null);
        mCi.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        mCi.setOnRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);

// M: MTK added
        mCi.registerForPsNetworkStateChanged(this, EVENT_PS_NETWORK_STATE_CHANGED, null);
        mCi.setInvalidSimInfo(this, EVENT_INVALID_SIM_INFO, null); //ALPS00248788
        mCi.registerForModulation(this, EVENT_MODULATION_INFO, null);

        try {
            if (mServiceStateExt.isImeiLocked())
                mCi.registerForIMEILock(this, EVENT_IMEI_LOCK, null);
        } catch (RuntimeException e) {
            /* BSP must exception here but Turnkey should not exception here */
            loge("No isImeiLocked");
        }

        mCi.registerForIccRefresh(this, EVENT_ICC_REFRESH, null);
        if (SystemProperties.get("ro.mtk_ims_support").equals("1")) {
            mCi.registerForImsDisable(this, EVENT_IMS_DISABLED_URC, null);
            mCi.registerForImsRegistrationInfo(this, EVENT_IMS_REGISTRATION_INFO, null);
        }
        if (SystemProperties.get("ro.mtk_femto_cell_support").equals("1"))
            mCi.registerForFemtoCellInfo(this, EVENT_FEMTO_CELL_INFO, null);
        if (needResumeModem()) {
            mCi.setOnRegistrationSuspended(this, EVENT_REG_SUSPENDED, null);
        }

        mCi.registerForNetworkEvent(this, EVENT_NETWORK_EVENT, null);
//M: MTK added end

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.Global.getInt(
                phone.getContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        mCr = phone.getContext().getContentResolver();
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                mAutoTimeObserver);
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true,
                mAutoTimeZoneObserver);

        //[ALPS01577029]-START:To support auto switch rat mode to 2G only for 3M TDD csfb project
        //when we are not in china
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TELEPHONY_MISC_FEATURE_CONFIG), true,
                mMsicFeatureConfigObserver);
        //[ALPS01577029]-END

        setSignalStrengthDefaultValues();

        // Monitor locale change
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        phone.getContext().registerReceiver(mIntentReceiver, filter);

        filter = new IntentFilter();
        Context context = phone.getContext();
        filter.addAction(ACTION_RADIO_OFF);

// M : MTK added
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        filter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
// M: MTK added end

        context.registerReceiver(mIntentReceiver, filter);

        /// M: Simulate IMS Registration @{
        final IntentFilter imsfilter = new IntentFilter();
        imsfilter.addAction("ACTION_IMS_SIMULATE");
        context.registerReceiver(mBroadcastReceiver, imsfilter);
        /// @}

        //MTK-START [ALPS00368272]
        mCr.registerContentObserver(
                Settings.System.getUriFor(Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION), true,
                mDataConnectionSettingObserver);
        mCr.registerContentObserver(
                Settings.System.getUriFor(Settings.Global.MOBILE_DATA), true,
                mDataConnectionSettingObserver);
        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                if (mServiceStateExt.needEMMRRS()) {
                    if (isCurrentPhoneDataConnectionOn()) {
                        getEINFO(EVENT_ENABLE_EMMRRS_STATUS);
                    } else {
                        getEINFO(EVENT_DISABLE_EMMRRS_STATUS);
                    }
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        //MTK-END [ALPS00368272]

        //[ALPS01825832] reset flag
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            setReceivedNitz(i, false);
        }
    }

    @Override
    public void dispose() {
        checkCorrectThread();
        log("ServiceStateTracker dispose");

        // Unregister for all events.
        mCi.unregisterForAvailable(this);
        mCi.unregisterForRadioStateChanged(this);
        mCi.unregisterForVoiceNetworkStateChanged(this);
        mCi.unregisterForModulation(this);
        mCi.unregisterForNetworkEvent(this);
        if (mUiccApplcation != null) {mUiccApplcation.unregisterForReady(this);}
        if (mIccRecords != null) {mIccRecords.unregisterForRecordsLoaded(this);}
        mCi.unSetOnRestrictedStateChanged(this);
        mCi.unSetOnNITZTime(this);
        mCr.unregisterContentObserver(mAutoTimeObserver);
        mCr.unregisterContentObserver(mAutoTimeZoneObserver);
        mCr.unregisterContentObserver(mMsicFeatureConfigObserver);   //[ALPS01577029]
        mCr.unregisterContentObserver(mDataConnectionSettingObserver);

        try {
            if (mServiceStateExt.isImeiLocked())
                mCi.unregisterForIMEILock(this);
        } catch (RuntimeException e) {
            /* BSP must exception here but Turnkey should not exception here */
            loge("No isImeiLocked");
        }

        if (SystemProperties.get("ro.mtk_femto_cell_support").equals("1"))
            mCi.unregisterForFemtoCellInfo(this);

        mCi.unregisterForIccRefresh(this);

        mPhone.getContext().unregisterReceiver(mIntentReceiver);
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalize");
    }

    @Override
    protected Phone getPhone() {
        return mPhone;
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        Message message;

        if (!mPhone.mIsTheCurrentActivePhone) {
            Rlog.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE:
                log("handle EVENT_RADIO_AVAILABLE");
                //check if we boot up under airplane mode
                if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    log("not BSP package, notify!");
                    RadioManager.getInstance().notifyRadioAvailable(mPhone.getPhoneId());
                }
                //this is unnecessary
                //setPowerStateToDesired();
                break;
            case EVENT_SIM_READY:
                // Reset the mPreviousSubId so we treat a SIM power bounce
                // as a first boot.  See b/19194287
                mOnSubscriptionsChangedListener.mPreviousSubId.set(-1);
                log("handle EVENT_SIM_READY");
                boolean skipRestoringSelection = mPhone.getContext().getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);
                if (DBG) log("skipRestoringSelection=" + skipRestoringSelection);
                if (!skipRestoringSelection) {
                    // restore the previous network selection.
                    mPhone.restoreSavedNetworkSelection(null);
                }
                pollState();
                // Signal strength polling stops when radio is off
                queueNextSignalStrengthPoll();
                break;
            case EVENT_RADIO_STATE_CHANGED:
                log("handle EVENT_RADIO_STATE_CHANGED");
                if (RadioManager.isMSimModeSupport()) {
                    log("MTK propiertary Power on flow, setRadioPower:  mDesiredPowerState="
                            + mDesiredPowerState + "  phoneId=" + mPhone.getPhoneId());
                    RadioManager.getInstance().setRadioPower(
                            mDesiredPowerState, mPhone.getPhoneId());
                }
                else {
                    // This will do nothing in the radio not
                    // available case
                    // setPowerStateToDesired();
                    log("BSP package but use MTK Power on flow");
                    RadioManager.getInstance().setRadioPower(
                            mDesiredPowerState, mPhone.getPhoneId());
                }
                if (mCi.getRadioState() == CommandsInterface.RadioState.RADIO_ON) {
                    setDeviceRatMode(mPhone.getPhoneId());
                }
                pollState();
                break;
            case EVENT_NETWORK_STATE_CHANGED:
                log("handle EVENT_NETWORK_STATE_CHANGED");
                ar = (AsyncResult) msg.obj;
                onNetworkStateChangeResult(ar);
                pollState();
                break;
            case EVENT_PS_NETWORK_STATE_CHANGED:
                log("handle EVENT_PS_NETWORK_STATE_CHANGED");
                ar = (AsyncResult) msg.obj;
                onPsNetworkStateChangeResult(ar);
                pollState();
                break;
            case EVENT_GET_SIGNAL_STRENGTH:
            case EVENT_GET_SIGNAL_STRENGTH_ONLY:
                log("handle EVENT_GET_SIGNAL_STRENGTH");
                // This callback is called when signal strength is polled
                // all by itself
                if (!(mCi.getRadioState().isOn())) {
                    // Polling will continue when radio turns back on
                    return;
                }
                ar = (AsyncResult) msg.obj;
                if ((ar.exception == null) && (ar.result != null)) {
                    mSignalStrengthChangedRegistrants.notifyResult(
                            new SignalStrength((SignalStrength) ar.result));
                }
                onSignalStrengthResult(ar, true);
                queueNextSignalStrengthPoll();
                break;
            case EVENT_GET_LOC_DONE:
                log("handle EVENT_GET_LOC_DONE");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    String states[] = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    if (states.length >= 3) {
                        try {
                            if (states[1] != null && states[1].length() > 0) {
                                lac = Integer.parseInt(states[1], 16);
                            }
                            if (states[2] != null && states[2].length() > 0) {
                                cid = Integer.parseInt(states[2], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Rlog.w(LOG_TAG, "error parsing location: " + ex);
                        }
                    }
                    mCellLoc.setLacAndCid(lac, cid);
                    mPhone.notifyLocationChanged();
                }
                // Release any temporary cell lock, which could have been
                // acquired to allow a single-shot location update.
                disableSingleLocationUpdate();
                break;
            case EVENT_POLL_STATE_REGISTRATION:
            case EVENT_POLL_STATE_GPRS:
            case EVENT_POLL_STATE_OPERATOR:
            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                if (msg.what == EVENT_POLL_STATE_REGISTRATION) {
                    log("handle EVENT_POLL_STATE_REGISTRATION");
                } else if (msg.what == EVENT_POLL_STATE_GPRS) {
                    log("handle EVENT_POLL_STATE_GPRS");
                } else if (msg.what == EVENT_POLL_STATE_OPERATOR) {
                    log("handle EVENT_POLL_STATE_OPERATOR");
                } else {
                    log("handle EVENT_POLL_STATE_NETWORK_SELECTION_MODE");
                }
                ar = (AsyncResult) msg.obj;
                handlePollStateResult(msg.what, ar);
                break;
            case EVENT_POLL_SIGNAL_STRENGTH:
                log("handle EVENT_POLL_SIGNAL_STRENGTH");
                if (mDontPollSignalStrength) {
                    // The radio is telling us about signal strength changes
                    // we don't have to ask it
                    return;
                }
                // Just poll signal strength...not part of pollState()
                mCi.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
                break;
            case EVENT_NITZ_TIME:
                log("handle EVENT_NITZ_TIME");
                ar = (AsyncResult) msg.obj;
                String nitzString = (String)((Object[])ar.result)[0];
                long nitzReceiveTime = ((Long)((Object[])ar.result)[1]).longValue();
                setTimeFromNITZString(nitzString, nitzReceiveTime);
                break;
            case EVENT_SIGNAL_STRENGTH_UPDATE:
                // This is a notification from
                // CommandsInterface.setOnSignalStrengthUpdate
                log("handle EVENT_SIGNAL_STRENGTH_UPDATE");
                ar = (AsyncResult) msg.obj;
                // The radio is telling us about signal strength changes
                // we don't have to ask it
                mDontPollSignalStrength = true;
                if ((ar.exception == null) && (ar.result != null)) {
                    mSignalStrengthChangedRegistrants.notifyResult(
                            new SignalStrength((SignalStrength) ar.result));
                }
                onSignalStrengthResult(ar, true);
                break;
            case EVENT_SIM_RECORDS_LOADED:
                log("handle EVENT_SIM_RECORDS_LOADED");
                // Gsm doesn't support OTASP so its not needed
                mPhone.notifyOtaspChanged(OTASP_NOT_NEEDED);
                updatePhoneObject();
                /* updateSpnDisplay() will be executed in refreshSpnDisplay() */
                ////updateSpnDisplay();
                // pollState() result may be faster than load EF complete, so
                // update ss.alphaLongShortName
                refreshSpnDisplay();
                break;
            case EVENT_LOCATION_UPDATES_ENABLED:
                log("handle EVENT_LOCATION_UPDATES_ENABLED");
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mCi.getVoiceRegistrationState(obtainMessage(EVENT_GET_LOC_DONE, null));
                }
                break;
            case EVENT_SET_PREFERRED_NETWORK_TYPE:
                log("handle EVENT_SET_PREFERRED_NETWORK_TYPE");
                ar = (AsyncResult) msg.obj;
                // Don't care the result, only use for dereg network (COPS=2)
                message = obtainMessage(EVENT_RESET_PREFERRED_NETWORK_TYPE, ar.userObj);
                mCi.setPreferredNetworkType(mPreferredNetworkType, message);
                break;
            case EVENT_RESET_PREFERRED_NETWORK_TYPE:
                log("handle EVENT_RESET_PREFERRED_NETWORK_TYPE");
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;
            case EVENT_GET_PREFERRED_NETWORK_TYPE:
                log("handle EVENT_GET_PREFERRED_NETWORK_TYPE");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    mPreferredNetworkType = ((int[])ar.result)[0];
                } else {
                    mPreferredNetworkType = RILConstants.NETWORK_MODE_GLOBAL;
                }
                message = obtainMessage(EVENT_SET_PREFERRED_NETWORK_TYPE, ar.userObj);
                int toggledNetworkType = RILConstants.NETWORK_MODE_GLOBAL;
                mCi.setPreferredNetworkType(toggledNetworkType, message);
                break;
            case EVENT_CHECK_REPORT_GPRS:
                log("handle EVENT_CHECK_REPORT_GPRS");
                if (mSS != null && !isGprsConsistent(mSS.getDataRegState(),
                        mSS.getVoiceRegState())) {
                    // Can't register data service while voice service is ok
                    // i.e. CREG is ok while CGREG is not
                    // possible a network or baseband side error
                    GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.DATA_NETWORK_REGISTRATION_FAIL,
                            mSS.getOperatorNumeric(), loc != null ? loc.getCid() : -1);
                    mReportedGprsNoReg = true;
                }
                mStartedGprsRegCheck = false;
                break;
            case EVENT_RESTRICTED_STATE_CHANGED:
                // This is a notification from
                // CommandsInterface.setOnRestrictedStateChanged
                log("handle EVENT_RESTRICTED_STATE_CHANGED");
                if (DBG) log("EVENT_RESTRICTED_STATE_CHANGED");

                ar = (AsyncResult) msg.obj;
                onRestrictedStateChanged(ar);
                break;
            case EVENT_ALL_DATA_DISCONNECTED:
                log("handle EVENT_ALL_DATA_DISCONNECTED");
                int dds = SubscriptionManager.getDefaultDataSubId();
                ProxyController.getInstance().unregisterForAllDataDisconnected(dds, this);
                synchronized(this) {
                    if (mPendingRadioPowerOffAfterDataOff) {
                        if (DBG) log("EVENT_ALL_DATA_DISCONNECTED, turn radio off now.");
                        hangupAndPowerOff();
                        mPendingRadioPowerOffAfterDataOff = false;
                    } else {
                        log("EVENT_ALL_DATA_DISCONNECTED is stale");
                    }
                }
                break;
            case EVENT_CHANGE_IMS_STATE:
                log("handle EVENT_CHANGE_IMS_STATE");
                setPowerStateToDesired();
                break;
            case EVENT_INVALID_SIM_INFO: //ALPS00248788
                log("handle EVENT_INVALID_SIM_INFO");
                ar = (AsyncResult) msg.obj;
                onInvalidSimInfoReceived(ar);
                break;
            case EVENT_MODULATION_INFO:
                log("handle EVENT_MODULATION_INFO");
                ar = (AsyncResult) msg.obj;
                onModulationInfoReceived(ar);
                break;
            case EVENT_IMEI_LOCK: //ALPS00296298
                log("handle EVENT_IMEI_LOCK");
                mIsImeiLock = true;
                break;
            case EVENT_ICC_REFRESH:
                log("handle EVENT_ICC_REFRESH");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    IccRefreshResponse res = ((IccRefreshResponse) ar.result);
                    if (res.refreshResult == 6) {
                        /* ALPS00949490 */
                        mLastRegisteredPLMN = null;
                        mLastPSRegisteredPLMN = null;
                        log("Reset mLastRegisteredPLMN and mLastPSRegisteredPLMN");
                    }
                }
                break;
            case EVENT_ENABLE_EMMRRS_STATUS:
                log("handle EVENT_ENABLE_EMMRRS_STATUS");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    String data[] = (String []) ar.result;
                    log("EVENT_ENABLE_EMMRRS_STATUS, data[0] is : " + data[0]);
                    log("EVENT_ENABLE_EMMRRS_STATUS, einfo value is : " + data[0].substring(8));
                    int oldValue = Integer.valueOf(data[0].substring(8));
                    int value = oldValue | 0x80;
                    log("EVENT_ENABLE_EMMRRS_STATUS, einfo value change is : " + value);
                    if (oldValue != value) {
                        setEINFO(value, null);
                    }
                }
                log("EVENT_ENABLE_EMMRRS_STATUS end");
                break;
            case EVENT_DISABLE_EMMRRS_STATUS:
                log("handle EVENT_DISABLE_EMMRRS_STATUS");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    String data[] = (String []) ar.result;
                    log("EVENT_DISABLE_EMMRRS_STATUS, data[0] is : " + data[0]);
                    log("EVENT_DISABLE_EMMRRS_STATUS, einfo value is : " + data[0].substring(8));

                    try {
                        int oldValue = Integer.valueOf(data[0].substring(8));
                        int value = oldValue & 0xff7f;
                        log("EVENT_DISABLE_EMMRRS_STATUS, einfo value change is : " + value);
                        if (oldValue != value) {
                            setEINFO(value, null);
                        }
                    } catch (NumberFormatException ex) {
                        loge("Unexpected einfo value : " + ex);
                    }
                }
                log("EVENT_DISABLE_EMMRRS_STATUS end");
                break;
            case EVENT_FEMTO_CELL_INFO:
                log("handle EVENT_FEMTO_CELL_INFO");
                ar = (AsyncResult) msg.obj;
                onFemtoCellInfoResult(ar);
                break;
            case EVENT_IMS_REGISTRATION_INFO:
                log("handle EVENT_IMS_REGISTRATION_INFO");
                ar = (AsyncResult) msg.obj;
                /// M: Simulate IMS Registration @{
                if (SystemProperties.getInt("persist.ims.simulate", 0) == 1) {
                    ((int[]) ar.result)[0] = (mImsRegistry ? 1 : 0);
                    log("Override EVENT_IMS_REGISTRATION_INFO: new mImsRegInfo=" +
                            ((int[]) ar.result)[0]);
                }
                /// @}
                if (((int[]) ar.result)[1] > 0) {
                    mImsExtInfo = ((int[]) ar.result)[1];
                }
                log("ImsRegistrationInfoResult [" + mImsRegInfo + ", " + mImsExtInfo + "]");
                break;

            case EVENT_IMS_CAPABILITY_CHANGED:
                if (DBG) log("EVENT_IMS_CAPABILITY_CHANGED");
                updateSpnDisplay();
                break;

            case EVENT_REG_SUSPENDED:
                log("handle EVENT_REG_SUSPENDED");
                ar = (AsyncResult) msg.obj;
                int suspendId = ((int[]) ar.result)[0];
                mCi.setResumeRegistration(suspendId, obtainMessage(EVENT_RESUME_CAMPING));
                break;
            case EVENT_RESUME_CAMPING:
                log("handle EVENT_RESUME_CAMPING");
                mCi.setRegistrationSuspendEnabled(0, null);
                break;
            case EVENT_NETWORK_EVENT:
                log("handle EVENT_NETWORK_EVENT");
                ar = (AsyncResult) msg.obj;
                onNetworkEventReceived(ar);
                break;
           default:
                super.handleMessage(msg);
            break;
        }
        log("handleMessage msg done");

    }

    protected void setDeviceRatMode(int phoneId) {
        log("[setDeviceRatMode]+");
        boolean hasCdmaApp = false;
        int capabilityPhoneId = RadioCapabilitySwitchUtil.getMainCapabilityPhoneId();
        int targetNetworkMode = RILConstants.NETWORK_MODE_GSM_ONLY;
        int restrictedNwMode = -1 ;

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                if (mServiceStateExt.isSupportRatBalancing()) {
                    log("Network Type is controlled by RAT Blancing, no need to set network type");
                    log("[setDeviceRatMode]-");
                    return;
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        if (SystemProperties.getInt("ro.telephony.cl.config", 0) == 1) {
            targetNetworkMode = RILConstants.NETWORK_MODE_LTE_GSM_WCDMA;
        } else if (phoneId == capabilityPhoneId) {
            targetNetworkMode = getPreferredNetworkModeSettings(phoneId);

        } else {
            targetNetworkMode = getPreferredNetworkModeSettings(phoneId);
            int mCapability = ((PhoneProxy)PhoneFactory.getPhone(phoneId)).getRadioAccessFamily();
            log("Not major phone, targetNetworkMode="+ targetNetworkMode +
                " mCapability=" + mCapability);
            if ((mCapability & RadioAccessFamily.RAF_UMTS)== RadioAccessFamily.RAF_UMTS){
                //Current modem support L+W and active workd mode is uLWG
                if (targetNetworkMode == RILConstants.NETWORK_MODE_LTE_GSM_WCDMA){
                    log("Set RAT to 3/2G for non-major phoe support 3/2G capability");
                    targetNetworkMode = RILConstants.NETWORK_MODE_WCDMA_PREF;
                }
            } else {
                log("Set RAT to 2G for non-major phoe only support 2G capability");
                targetNetworkMode = RILConstants.NETWORK_MODE_GSM_ONLY;
            }
        }

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                restrictedNwMode = mServiceStateExt.needAutoSwitchRatMode(phoneId, mLocatedPlmn);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        log("restrictedNwMode = " + restrictedNwMode);
        if (restrictedNwMode >= Phone.NT_MODE_WCDMA_PREF) {
            if (restrictedNwMode != targetNetworkMode) {
                log("Revise targetNetworkMode to " + restrictedNwMode);
                targetNetworkMode = restrictedNwMode;
            }
        }

        log("targetNetworkMode = " + targetNetworkMode
                        + " capcapabilityPhoneId = " + capabilityPhoneId);

        if (targetNetworkMode >= Phone.NT_MODE_WCDMA_PREF) {
            mCi.setPreferredNetworkType(targetNetworkMode, null);
            log("[setDeviceRatMode]- " + targetNetworkMode);
        } else {
            log("[setDeviceRatMode]- targetNetworkMode invalid!!");
        }
    }

    @Override
    public boolean isPsRegStateRoamByUnsol() {
        boolean psRegState = false;

        if (mPsRegStateRaw == ServiceState.RIL_REG_STATE_ROAMING)
            psRegState = true;

        return psRegState;
    }

    @Override
    protected void setPowerStateToDesired() {

        if (DBG) {
            //log("mDeviceShuttingDown = " + mDeviceShuttingDown);
            //log("mDesiredPowerState = " + mDesiredPowerState);
            //log("getRadioState = " + mCi.getRadioState());
            //log("mPowerOffDelayNeed = " + mPowerOffDelayNeed);
            //log("mAlarmSwitch = " + mAlarmSwitch);
            log("mDeviceShuttingDown = " + mDeviceShuttingDown
                    + " mDesiredPowerState = " + mDesiredPowerState
                    + " getRadioState = " + mCi.getRadioState()
                    + " mPowerOffDelayNeed = " + mPowerOffDelayNeed
                    + " mAlarmSwitch = " + mAlarmSwitch);
        }

        if (mAlarmSwitch) {
            if(DBG) log("mAlarmSwitch == true");
            Context context = mPhone.getContext();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(mRadioOffIntent);
            mAlarmSwitch = false;
        }

        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
                && mCi.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            //MTK-START [mtk06800] some actions must be took before EFUN
            RadioManager.getInstance().sendRequestBeforeSetRadioPower(true, mPhone.getPhoneId());
            //MTK-END [mtk06800] some actions must be took before EFUN
            mCi.setRadioPower(true, null);
        } else if ((!mDesiredPowerState && mCi.getRadioState().isOn()) ||
                   (isOp01Support() && !mEverRadioHandled && !mDesiredPowerState &&
                    mCi.getRadioState() == CommandsInterface.RadioState.RADIO_OFF)) {
            // If it's on and available and we want it off gracefully
            if (mPowerOffDelayNeed) {
                if (mImsRegistrationOnOff && !mAlarmSwitch) {
                    if(DBG) log("mImsRegistrationOnOff == true");
                    Context context = mPhone.getContext();
                    AlarmManager am = (AlarmManager) context.getSystemService(
                            Context.ALARM_SERVICE);

                    Intent intent = new Intent(ACTION_RADIO_OFF);
                    mRadioOffIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

                    mAlarmSwitch = true;
                    if (DBG) log("Alarm setting");
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + 3000, mRadioOffIntent);
                } else {
                    DcTrackerBase dcTracker = mPhone.mDcTracker;
                    powerOffRadioSafely(dcTracker);
                }
            } else {
                DcTrackerBase dcTracker = mPhone.mDcTracker;
                powerOffRadioSafely(dcTracker);
            }
        } else if (mDeviceShuttingDown && mCi.getRadioState().isAvailable()) {
            mCi.requestShutdown(null);
        }
        mEverRadioHandled = true;
    }

    @Override
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        if (mPhone.isInCall()) {
            mPhone.mCT.mRingingCall.hangupIfAlive();
            mPhone.mCT.mBackgroundCall.hangupIfAlive();
            mPhone.mCT.mForegroundCall.hangupIfAlive();
        }
        //MTK-START [mtk06800] some actions must be took before EFUN
        RadioManager.getInstance().sendRequestBeforeSetRadioPower(false, mPhone.getPhoneId());
        //MTK-END [mtk06800] some actions must be took before EFUN
        mCi.setRadioPower(false, null);
    }

    public void refreshSpnDisplay() {
        String numeric = mSS.getOperatorNumeric();
        String newAlphaLong = null;
        String newAlphaShort = null;

        if ((numeric != null) && (!(numeric.equals("")))) {
            newAlphaLong = SpnOverride.getInstance().lookupOperatorName(
                    SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()), numeric,
                    true, mPhone.getContext());
            newAlphaShort = SpnOverride.getInstance().lookupOperatorName(
                    SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()), numeric,
                    false, mPhone.getContext());
            //[ALPS01804936]-start:fix JE when change system language to "Burmese"
            //mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA, newAlphaLong);
            //updateOperatorAlpha(newAlphaLong);    //remark for [ALPS01965792]
            //[ALPS01804936]-end
            if (newAlphaLong != null) {
                newAlphaLong = mServiceStateExt.updateOpAlphaLongForHK(newAlphaLong,
                    numeric, mPhone.getPhoneId());
            }

            log("refreshSpnDisplay set alpha to " + newAlphaLong + ","
                    + newAlphaShort + "," + numeric);
            mSS.setOperatorName(newAlphaLong, newAlphaShort, numeric);
        }
        updateSpnDisplay();
    }

    @Override
    protected void updateSpnDisplay() {
        updateSpnDisplay(false);
    }

    protected void updateSpnDisplay(boolean forceUpdate) {
        SIMRecords simRecords = null;
        IccRecords r = mPhone.mIccRecords.get();
        if (r != null) {
            simRecords = (SIMRecords) r;
        }

        int rule = (simRecords != null) ? simRecords.getDisplayRule(
                mSS.getOperatorNumeric()) : SIMRecords.SPN_RULE_SHOW_PLMN;
        String strNumPlmn = mSS.getOperatorNumeric();
        String spn = (simRecords != null) ? simRecords.getServiceProviderName() : "";
        String sEons = null;
        boolean showPlmn = false;
        String plmn = null;
        String realPlmn = null;
        String mSimOperatorNumeric = (simRecords != null) ? simRecords.getOperatorNumeric() : "";

        try {
            sEons = (simRecords != null) ? simRecords.getEonsIfExist(mSS.getOperatorNumeric(),
                    mCellLoc.getLac(), true) : null;
        } catch (RuntimeException ex) {
            loge("Exception while getEonsIfExist. " + ex);
        }

        if (sEons != null) {
            plmn = sEons;
        }
        else if (strNumPlmn != null && strNumPlmn.equals(mSimOperatorNumeric)) {
            log("Home PLMN, get CPHS ons");
            plmn = (simRecords != null) ? simRecords.getSIMCPHSOns() : "";
        }

        if (TextUtils.isEmpty(plmn)) {
            log("No matched EONS and No CPHS ONS");
            plmn = mSS.getOperatorAlphaLong();
            // M:[ALPS02414050] OperatorAlphaLong maybe ""
            if (TextUtils.isEmpty(plmn) || plmn.equals(mSS.getOperatorNumeric())) {
                plmn = mSS.getOperatorAlphaShort();
            }
        }

        /*[ALPS00460547] - star */
        //keep operator name for update PROPERTY_OPERATOR_ALPHA
        realPlmn = plmn;
        /*[ALPS00460547] - end */

        // Do not display SPN before get normal service
        //M: for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS only SIM card
        //or CS domain network registeration temporary failure
        //if (mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE) {
        if ((mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE) &&
                (mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE)) {
            showPlmn = true;
            plmn = Resources.getSystem().
                    getText(com.android.internal.R.string.lockscreen_carrier_default).toString();

        }
        log("updateSpnDisplay mVoiceCapable=" + mVoiceCapable + " mEmergencyOnly=" + mEmergencyOnly
            + " mCi.getRadioState().isOn()=" + mCi.getRadioState().isOn() + " getVoiceRegState()="
            + mSS.getVoiceRegState() + " getDataRegState()" + mSS.getDataRegState());

        // ALPS00283717 For emergency calls only, pass the EmergencyCallsOnly string via EXTRA_PLMN
        //MTK-ADD START : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS
        //only SIM card or CS domain network registeration temporary failure
        if (mVoiceCapable && mEmergencyOnly && mCi.getRadioState().isOn()
                && (mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE)) {
            log("updateSpnDisplay show mEmergencyOnly");
            showPlmn = true;

            plmn = Resources.getSystem().getText(
                    com.android.internal.R.string.emergency_calls_only).toString();

            if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                try {
                    //CDR-NWS-2409
                    if(mServiceStateExt.needBlankDisplay(mSS.getVoiceRejectCause()) == true){
                        log("Do NOT show emergency call only display");
                        plmn = "";
                    }
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
                * mImeiAbnormal=0, Valid IMEI
                * mImeiAbnormal=1, IMEI is null or not valid format
                * mImeiAbnormal=2, Phone1/Phone2 have same IMEI
                */
        int imeiAbnormal = mPhone.isDeviceIdAbnormal();
        if (imeiAbnormal == 1) {
            //[ALPS00872883] don't update plmn string when radio is not available
            if (mCi.getRadioState() != CommandsInterface.RadioState.RADIO_UNAVAILABLE) {
                plmn = Resources.getSystem().getText(com.mediatek.R.string.invalid_imei).toString();
            }
        } else if (imeiAbnormal == 2) {
            plmn = Resources.getSystem().getText(com.mediatek.R.string.same_imei).toString();
        } else if (imeiAbnormal == 0) {
            if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                try {
                    plmn = mServiceStateExt.onUpdateSpnDisplay(plmn, mSS,
                               mPhone.getPhoneId());
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }

            // If CS not registered , PS registered , add "Data
            // connection only" postfix in PLMN name
            if ((mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE) &&
                    (mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
                //[ALPS01650043]-Start: don't update PLMN name
                // when it is null for backward compatible
                if (plmn != null) {
                    plmn = plmn + "(" + Resources.getSystem()
                            .getText(com.mediatek.R.string.data_conn_only)
                            .toString() + ")";
                } else {
                    log("PLMN name is null when CS not registered and PS registered");
                }
            }
        }

        /* ALPS00296298 */
        if (mIsImeiLock) {
            plmn = Resources.getSystem().getText(com.mediatek.R.string.invalid_card).toString();
        }

        //MTK-ADD Start : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS
        //only SIM card or CS domain network registeration temporary failure
        //if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
        if ((mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) ||
            (mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
            showPlmn = !TextUtils.isEmpty(plmn) &&
                    ((rule & SIMRecords.SPN_RULE_SHOW_PLMN)
                            == SIMRecords.SPN_RULE_SHOW_PLMN);
        /* } else {
                  // Power off state, such as airplane mode, show plmn as "No service"
                  showPlmn = true;
                  plmn = Resources.getSystem().
                  getText(com.android.internal.R.string.lockscreen_carrier_default).toString();
                  if (DBG) log("updateSpnDisplay: radio is off w/ showPlmn="
                         + showPlmn + " plmn=" + plmn);
              }              */
        }

       /*
              // The value of spn/showSpn are same in different scenarios.
              // EXTRA_SHOW_SPN = depending on IccRecords rul
              // EXTRA_SPN = spn
              String spn = (iccRecords != null) ? iccRecords.getServiceProviderName() : "";
             */

        // The value of spn/showSpn are same in different scenarios.
        //    EXTRA_SHOW_SPN = depending on IccRecords rule and radio/IMS state
        //    EXTRA_SPN = spn
        //    EXTRA_DATA_SPN = dataSpn
        String dataSpn = spn;
        boolean showSpn = !TextUtils.isEmpty(spn)
                && ((rule & SIMRecords.SPN_RULE_SHOW_SPN)
                        == SIMRecords.SPN_RULE_SHOW_SPN);

        if (!TextUtils.isEmpty(spn)
                && mPhone.getImsPhone() != null
                && ((ImsPhone) mPhone.getImsPhone()).isVowifiEnabled()) {
            // In Wi-Fi Calling mode show SPN+WiFi
            String formatVoice = mPhone.getContext().getText(
                    com.android.internal.R.string.wfcSpnFormat).toString();
            String formatData = mPhone.getContext().getText(
                    com.android.internal.R.string.wfcDataSpnFormat).toString();
            String originalSpn = spn.trim();
            spn = String.format(formatVoice, originalSpn);
            dataSpn = String.format(formatData, originalSpn);
            showSpn = true;
            showPlmn = false;
        /// M: ALPS02293142, don't show spn when no service/emergency only
        } else if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF
                || (showPlmn && TextUtils.equals(spn, plmn))
                || ((mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE) &&
                        (mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE))) {
            // airplane mode or spn equals plmn, do not show spn
            spn = null;
            showSpn = false;
        }

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                if (mServiceStateExt.needSpnRuleShowPlmnOnly() && !TextUtils.isEmpty(plmn)) {
                    log("origin showSpn:" + showSpn + " showPlmn:" + showPlmn + " rule:" + rule);
                    showSpn = false;
                    showPlmn = true;
                    rule = SIMRecords.SPN_RULE_SHOW_PLMN;
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        ///M : WFC @{
        try {
            plmn = mServiceStateExt.onUpdateSpnDisplayForIms(
                                   plmn, mSS, mCellLoc.getLac(), mPhone.getPhoneId(),simRecords);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
        /// @}

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int[] subIds = SubscriptionManager.getSubId(mPhone.getPhoneId());
        if (subIds != null && subIds.length > 0) {
            subId = subIds[0];
        }

        // Update SPN_STRINGS_UPDATED_ACTION IFF any value changes
        if (mSubId != subId ||
                showPlmn != mCurShowPlmn
                || showSpn != mCurShowSpn
                || !TextUtils.equals(spn, mCurSpn)
                || !TextUtils.equals(dataSpn, mCurDataSpn)
                || !TextUtils.equals(plmn, mCurPlmn)
                || forceUpdate) {
            // M: [ALPS521030] for [CT case][TC-IRLAB-02009]
            if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                try {
                    if (!mServiceStateExt.allowSpnDisplayed()) {
                        log("For CT test case don't show SPN.");
                        if (rule == (SIMRecords.SPN_RULE_SHOW_PLMN
                                | SIMRecords.SPN_RULE_SHOW_SPN)) {
                            showSpn = false;
                            spn = null;
                        }
                    }
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
            if (DBG) {
                log(String.format("updateSpnDisplay: changed" +
                        " sending intent rule=" + rule +
                        " showPlmn='%b' plmn='%s' showSpn='%b' spn='%s' dataSpn='%s' subId='%d'",
                        showPlmn, plmn, showSpn, spn, dataSpn, subId));
            }

            Intent intent = new Intent(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);

            // For multiple SIM support, share the same intent, do not replace the other one
            if (TelephonyManager.getDefault().getPhoneCount() == 1) {
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            }

            intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(TelephonyIntents.EXTRA_SPN, spn);
            intent.putExtra(TelephonyIntents.EXTRA_DATA_SPN, dataSpn);
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);

            //M: Femtocell (CSG) info
            intent.putExtra(TelephonyIntents.EXTRA_HNB_NAME, mHhbName);
            intent.putExtra(TelephonyIntents.EXTRA_CSG_ID, mCsgId);
            intent.putExtra(TelephonyIntents.EXTRA_DOMAIN, mFemtocellDomain);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);

            int phoneId = mPhone.getPhoneId();

            // Append Femtocell (CSG) Info
            if (SystemProperties.get("ro.mtk_femto_cell_support").equals("1")){
                if((mHhbName == null) && (mCsgId != null)){
                    if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                        try {
                            if (mServiceStateExt.needToShowCsgId() == true) {
                                plmn += " - ";
                                plmn += mCsgId;
                            }
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    } else {
                        plmn += " - ";
                        plmn += mCsgId;
                    }
                } else if(mHhbName != null){
                    plmn += " - ";
                    plmn += mHhbName;
                }
            }

            boolean setResult = mSubscriptionController.setPlmnSpn(phoneId,
                    showPlmn, plmn, showSpn, spn);
            if (!setResult) {
                mSpnUpdatePending = true;
            }
            log("showSpn:" + showSpn + " spn:" + spn + " showPlmn:" + showPlmn +
                    " plmn:" + plmn + " rule:" + rule +
                    " setResult:" + setResult + " phoneId:" + phoneId);
        }

        //[ALPS01554309]-start
        // update new operator info. when operator numeric has change.
        /* ALPS00357573 for consistent operator name display */
        if ((showSpn == true) && (showPlmn == false) && (spn != null)) {
            /* When only <spn> is shown , we update with <spn> */
            log("updateAllOpertorInfo with spn:" + spn);
            //[ALPS01804936]-start:fix JE when change system language to "Burmese"
            //mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA, spn);
            mSS.setOperatorAlphaLong(spn);  //add for [ALPS01965792]
            updateOperatorAlpha(spn);
            //[ALPS01804936]-end
        } else {
            log("updateAllOpertorInfo with realPlmn:" + realPlmn);
            //[ALPS01804936]-start:fix JE when change system language to "Burmese"
            //mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA, realPlmn);
            mSS.setOperatorAlphaLong(realPlmn);  //add for [ALPS01965792]
            updateOperatorAlpha(realPlmn);
            //[ALPS01804936]-end
        }
        //[ALPS01554309]-end

        mSubId = subId;
        mCurShowSpn = showSpn;
        mCurShowPlmn = showPlmn;
        mCurSpn = spn;
        mCurDataSpn = dataSpn;
        mCurPlmn = plmn;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        //MTK-ADD Start : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS
        //only SIM card or CS domain network registeration temporary failure
        /* update  mNewCellLoc when CS is not registered but PS is registered */
        int psLac = -1;
        int psCid = -1;
        //MTK-ADD END: for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS only
        //SIM card or CS domain network registeration temporary failure

        // Ignore stale requests from last poll
        if (ar.userObj != mPollingContext) {
            loge("handlePollStateResult return due to (ar.userObj != mPollingContext)");
            return;
        }

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                loge("handlePollStateResult cancelPollState due to RADIO_NOT_AVAILABLE");
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("RIL implementation has returned an error where it must succeed" +
                        ar.exception);
            }
        } else try {
            switch (what) {
                case EVENT_POLL_STATE_REGISTRATION: {
                    states = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    int type = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
                    int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                    int reasonRegStateDenied = -1;
                    int psc = -1;
                    int rejCause = -1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);
                            if (states.length >= 3) {
                                if (states[1] != null && states[1].length() > 0) {
                                    //[ALPS00907900]-START
                                    int tempLac = Integer.parseInt(states[1], 16);
                                    if (tempLac < 0) {
                                        log("set Lac to previous value");
                                        tempLac = mCellLoc.getLac();
                                    }
                                    lac = tempLac;
                                    //[ALPS00907900]-END
                                }
                                if (states[2] != null && states[2].length() > 0) {
                                    //[ALPS00907900]-START
                                    int tempCid = Integer.parseInt(states[2], 16);
                                    if (tempCid < 0) {
                                        log("set Cid to previous value");
                                        tempCid = mCellLoc.getCid();
                                    }
                                    cid = tempCid;
                                    //[ALPS00907900]-END
                                }
                                if (states.length >= 4 &&
                                        states[3] != null && states[3].length() > 0) {
                                    //[ALPS01810775,ALPS01868743] -Start: update network type at
                                    //screen off
                                    updateNetworkInfo(regState, Integer.parseInt(states[3]));
                                    //[ALPS01810775,ALPS01868743] -End
                                }
                                if (states.length >= 14 &&
                                        states[13] != null && states[13].length() > 0) {
                                    rejCause = Integer.parseInt(states[13]);
                                    mNewSS.setVoiceRejectCause(rejCause);
                                    log("set voice reject cause to " + rejCause);
                                }
                            }
                            if (states.length > 14) {
                                if (states[14] != null && states[14].length() > 0) {
                                    psc = Integer.parseInt(states[14], 16);
                                }
                            }
                            log("EVENT_POLL_STATE_REGISTRATION mSS getRilVoiceRadioTechnology:"
                                    + mSS.getRilVoiceRadioTechnology() +
                                    ", regState:" + regState +
                                    ", NewSS RilVoiceRadioTechnology:"
                                    + mNewSS.getRilVoiceRadioTechnology() +
                                    ", lac:" + lac +
                                    ", cid:" + cid);
                        } catch (NumberFormatException ex) {
                            loge("error parsing RegistrationState: " + ex);
                        }
                    }

                    mGsmRoaming = regCodeIsRoaming(regState);
                    mNewSS.setState(regCodeToServiceState(regState));
                    mNewSS.setRegState(regState);

                    boolean isVoiceCapable = mPhoneBase.getContext().getResources()
                            .getBoolean(com.android.internal.R.bool.config_voice_capable);
                    if ((regState == ServiceState.RIL_REG_STATE_DENIED_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_NOT_REG_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_SEARCHING_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_UNKNOWN_EMERGENCY_CALL_ENABLED)
                         && isVoiceCapable) {
                        mEmergencyOnly = true;
                    } else {
                        mEmergencyOnly = false;
                    }
                    log("regState = " + regState + ", isVoiceCapable = " + isVoiceCapable +
                            ", mEmergencyOnly = " + mEmergencyOnly);

                    // LAC and CID are -1 if not avail. LAC and CID will be updated in
                    // onNetworkStateChangeResult() when in OUT_SERVICE
                    if (states.length > 3) {
                        log("states.length > 3");

                        /* ALPS00291583: ignore unknown lac or cid value */
                        if (lac == 0xfffe || cid == 0x0fffffff) {
                            log("unknown lac:" + lac + " or cid:" + cid);
                        } else {
                            /* AT+CREG? result won't include <lac> and <cid> when  in OUT_SERVICE */
                            if (regCodeToServiceState(regState)
                                    != ServiceState.STATE_OUT_OF_SERVICE) {
                                mNewCellLoc.setLacAndCid(lac, cid);
                            }
                        }
                    }
                    mNewCellLoc.setPsc(psc);
                    break;
                }

                case EVENT_POLL_STATE_GPRS: {
                    states = (String[])ar.result;

                    int type = 0;
                    int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                    mNewReasonDataDenied = -1;
                    mNewMaxDataCalls = 1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);

                            //MTK-ADD Start : for CS not registered , PS regsitered (ex: LTE PS only
                            //mode or 2/3G PS only SIM card or CS domain network registeration
                            //temporary failure
                            if (states.length >= 3) {
                                if (states[1] != null && states[1].length() > 0) {
                                    int tempLac = Integer.parseInt(states[1], 16);
                                    if (tempLac < 0) {
                                        log("set Lac to previous value");
                                        tempLac = mCellLoc.getLac();
                                    }
                                    psLac = tempLac;
                                }
                                if (states[2] != null && states[2].length() > 0) {
                                    int tempCid = Integer.parseInt(states[2], 16);
                                    if (tempCid < 0) {
                                        log("set Cid to previous value");
                                        tempCid = mCellLoc.getCid();
                                    }
                                    psCid = tempCid;
                                }
                            }
                            //MTK-ADD END : for CS not registered , PS regsitered (ex: LTE PS only
                            //mode or 2/3G PS only SIM card or CS domain network registeration
                            //temporary failure
                            // states[3] (if present) is the current radio technology
                            if (states.length >= 4 && states[3] != null) {
                                type = Integer.parseInt(states[3]);
                            }
                            if ((states.length >= 5) && (states[4] != null)
                                    && (regState == ServiceState.RIL_REG_STATE_DENIED)) {
                                mNewReasonDataDenied = Integer.parseInt(states[4]);
                                log("<mNewReasonDataDenied> " + mNewReasonDataDenied);
                                mNewSS.setDataRejectCause(mNewReasonDataDenied);
                                log("set data reject cause to " + mNewReasonDataDenied);
                            }
                            if (states.length >= 6 && states[5] != null) {
                                mNewMaxDataCalls = Integer.parseInt(states[5]);
                                log("<mNewMaxDataCalls> " + mNewMaxDataCalls);
                            }
                        } catch (NumberFormatException ex) {
                            loge("error parsing GprsRegistrationState: " + ex);
                        }
                    }
                    int dataRegState = regCodeToServiceState(regState);
                    mNewSS.setRilDataRegState(regState);
                    mNewSS.setDataRegState(dataRegState);
                    mDataRoaming = regCodeIsRoaming(regState);

                    //carrier aggregation
                    mNewSS.setProprietaryDataRadioTechnology(type);
                    //mNewSS.setRilDataRadioTechnology(type);

                    if (DBG) {
                        log("handlPollStateResultMessage: GsmSST setDataRegState=" + dataRegState
                                + " regState=" + regState
                                + " dataRadioTechnology=" + type);
                    }
                    break;
                }

                case EVENT_POLL_STATE_OPERATOR: {
                    String opNames[] = (String[])ar.result;

                    if (opNames != null && opNames.length >= 3) {
                        // FIXME: Giving brandOverride higher precedence, is this desired?
                        String brandOverride = mUiccController.getUiccCard(getPhoneId()) != null ?
                                mUiccController.getUiccCard(getPhoneId()).getOperatorBrandOverride()
                                : null;
                        if (brandOverride != null) {
                            log("EVENT_POLL_STATE_OPERATOR: use brandOverride=" + brandOverride);
                            mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                        } else {
                            String strOperatorLong = null;
                            String strOperatorShort = null;
                            SpnOverride spnOverride = SpnOverride.getInstance();

                            strOperatorLong = mCi.lookupOperatorNameFromNetwork(
                                    SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()),
                                    opNames[2], true);
                            if (strOperatorLong != null) {
                                log("EVENT_POLL_STATE_OPERATOR: OperatorLong use lookFromNetwork");
                            } else {
                                strOperatorLong = spnOverride.lookupOperatorName(
                                        SubscriptionManager.getSubIdUsingPhoneId(
                                                mPhone.getPhoneId()), opNames[2], true,
                                                mPhone.getContext());
                                if (strOperatorLong != null) {
                                    log("EVENT_POLL_STATE_OPERATOR: OperatorLong use lookupOperatorName");
                                    strOperatorLong = mServiceStateExt.updateOpAlphaLongForHK(
                                        strOperatorLong, opNames[2], mPhone.getPhoneId());
                                } else {
                                    log("EVENT_POLL_STATE_OPERATOR: OperatorLong use value from ril");
                                    strOperatorLong = opNames[0];
                                }
                            }
                            strOperatorShort = mCi.lookupOperatorNameFromNetwork(
                                    SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()),
                                            opNames[2], false);
                            if (strOperatorShort != null) {
                                log("EVENT_POLL_STATE_OPERATOR: OperatorShort use lookupOperatorNameFromNetwork");
                            } else {
                                strOperatorShort = spnOverride.lookupOperatorName(
                                                  SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()), opNames[2], false, mPhone.getContext());
                                if (strOperatorShort != null) {
                                    log("EVENT_POLL_STATE_OPERATOR: OperatorShort use lookupOperatorName");
                                } else {
                                    log("EVENT_POLL_STATE_OPERATOR: OperatorShort use value from ril");
                                    strOperatorShort = opNames[1];
                                }
                            }
                            log("EVENT_POLL_STATE_OPERATOR: " + strOperatorLong + ", " + strOperatorShort);
                            mNewSS.setOperatorName (strOperatorLong, strOperatorShort, opNames[2]);
                        }
                        updateLocatedPlmn(opNames[2]);
                    } else if (opNames != null && opNames.length == 1) {
                        log("opNames:" + opNames[0] + " len=" + opNames[0].length());
                        mNewSS.setOperatorName(null, null, null); // to keep the original AOSP behavior, set null when not registered

                        /* Do NOT update invalid PLMN value "000000" */
                        if (opNames[0].length() >= 5 && !(opNames[0].equals("000000"))) {
                            updateLocatedPlmn(opNames[0]);
                        } else {
                            updateLocatedPlmn(null);
                        }
                    }
                    break;
                }

                case EVENT_POLL_STATE_NETWORK_SELECTION_MODE: {
                    ints = (int[])ar.result;
                    mNewSS.setIsManualSelection(ints[0] == 1);
                    if ((ints[0] == 1) && (!mPhone.isManualNetSelAllowed())) {
                        /*
                         * modem is currently in manual selection but manual
                         * selection is not allowed in the current mode so
                         * switch to automatic registration
                         */
                        mPhone.setNetworkSelectionModeAutomatic (null);
                        log(" Forcing Automatic Network Selection, " +
                                "manual selection is not allowed");
                    }
                    break;
                }
            }
        } catch (RuntimeException ex) {
            loge("Exception while polling service state. Probably malformed RIL response." + ex);
        }

        mPollingContext[0]--;

        if (mPollingContext[0] == 0) {
            /**
             * Notify pending PS restricted status here
             */
            if (mPendingPsRestrictDisabledNotify) {
                mPsRestrictDisabledRegistrants.notifyRegistrants();
                setNotification(PS_DISABLED);
                mPendingPsRestrictDisabledNotify = false;
            }

            /**
             * [ALPS00006527]
             * Only when CS in service, treat PS as in service
             */
            if ((mNewSS.getState() != ServiceState.STATE_IN_SERVICE) &&
                (mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
                    //when CS not registered, we update cellLoc by +CGREG
                    log("update cellLoc by +CGREG");
                    mNewCellLoc.setLacAndCid(psLac, psCid);
            }
            updateRoamingState();
            mNewSS.setEmergencyOnly(mEmergencyOnly);
            pollStateDone();
        }
    }

    /**
     * Query the carrier configuration to determine if there any network overrides
     * for roaming or not roaming for the current service state.
     */
    protected void updateRoamingState() {
        /**
         * Since the roaming state of gsm service (from +CREG) and
         * data service (from +CGREG) could be different, the new SS
         * is set to roaming when either is true.
         *
         * There are exceptions for the above rule.
         * The new SS is not set as roaming while gsm service reports
         * roaming but indeed it is same operator.
         * And the operator is considered non roaming.
         *
         * The test for the operators is to handle special roaming
         * agreements and MVNO's.
         */
        boolean roaming = (mGsmRoaming || mDataRoaming);
        log("set roaming=" + roaming + ",mGsmRoaming= " + mGsmRoaming
                + ",mDataRoaming= " + mDataRoaming);

        //add for special SIM
        boolean isRoamingForSpecialSim = false;
        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                if ((mNewSS.getOperatorNumeric() != null)
                        && (getSIMOperatorNumeric() != null)
                        && mServiceStateExt.isRoamingForSpecialSIM(
                                mNewSS.getOperatorNumeric(), getSIMOperatorNumeric())) {
                    isRoamingForSpecialSim = true;
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        if (!isRoamingForSpecialSim) {
            //ALPS02446235[
            /* AOSP
            if (mGsmRoaming && !isOperatorConsideredRoaming(mNewSS) &&
                (isSameNamedOperators(mNewSS) || isOperatorConsideredNonRoaming(mNewSS))) {
            */
            if (mGsmRoaming && isSameNamedOperators(mNewSS)
                    && !isOperatorConsideredRoamingMtk(mNewSS)) {
            // ALPS02446235]
                if (VDBG)
                    log("set raoming fasle due to special roaming agreements and MVNO's.");
                roaming = false;
            }

            if (mPhone.isMccMncMarkedAsNonRoaming(mNewSS.getOperatorNumeric())) {
                roaming = false;
            } else if (mPhone.isMccMncMarkedAsRoaming(mNewSS.getOperatorNumeric())) {
                roaming = true;
            }
        }

        // Save the roaming state before carrier config possibly overrides it.
        mNewSS.setDataRoamingFromRegistration(roaming);

        ICarrierConfigLoader configLoader =
                (ICarrierConfigLoader) ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE);
        if (configLoader != null) {
            try {
                PersistableBundle b = configLoader.getConfigForSubId(mPhone.getSubId());

                if (alwaysOnHomeNetwork(b)) {
                    log("updateRoamingState: carrier config override always on home network");
                    roaming = false;
                } else if (isNonRoamingInGsmNetwork(b, mNewSS.getOperatorNumeric())) {
                    log("updateRoamingState: carrier config override set non roaming:"
                            + mNewSS.getOperatorNumeric());
                    roaming = false;
                } else if (isRoamingInGsmNetwork(b, mNewSS.getOperatorNumeric())) {
                    log("updateRoamingState: carrier config override set roaming:"
                            + mNewSS.getOperatorNumeric());
                    roaming = true;
                }
            } catch (RemoteException e) {
                loge("updateRoamingState: unable to access carrier config service");
            }
        } else {
            log("updateRoamingState: no carrier config service available");
        }

        mNewSS.setVoiceRoaming(roaming);
        mNewSS.setDataRoaming(roaming);
    }

    /**
     * Set both voice and data roaming type,
     * judging from the ISO country of SIM VS network.
     */
    protected void setRoamingType(ServiceState currentServiceState) {
        final boolean isVoiceInService =
                (currentServiceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE);
        if (isVoiceInService) {
            if (currentServiceState.getVoiceRoaming()) {
                // check roaming type by MCC
                if (inSameCountry(currentServiceState.getVoiceOperatorNumeric())) {
                    currentServiceState.setVoiceRoamingType(
                            ServiceState.ROAMING_TYPE_DOMESTIC);
                } else {
                    currentServiceState.setVoiceRoamingType(
                            ServiceState.ROAMING_TYPE_INTERNATIONAL);
                }
            } else {
                currentServiceState.setVoiceRoamingType(ServiceState.ROAMING_TYPE_NOT_ROAMING);
            }
        }
        final boolean isDataInService =
                (currentServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE);
        final int dataRegType = currentServiceState.getRilDataRadioTechnology();
        if (isDataInService) {
            if (!currentServiceState.getDataRoaming()) {
                currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_NOT_ROAMING);
            } else if (ServiceState.isGsm(dataRegType)) {
                if (isVoiceInService) {
                    // GSM data should have the same state as voice
                    currentServiceState.setDataRoamingType(currentServiceState
                            .getVoiceRoamingType());
                } else {
                    // we can not decide GSM data roaming type without voice
                    currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_UNKNOWN);
                }
            } else {
                // we can not decide 3gpp2 roaming state here
                currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_UNKNOWN);
            }
        }
    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength(true);
    }

    private void setNullState() {
        mGsmRoaming = false;
        mNewReasonDataDenied = -1;
        mNewMaxDataCalls = 1;
        mDataRoaming = false;
        //[ALPS00423362]
        mEmergencyOnly = false;
        updateLocatedPlmn(null);
        //[ALPS00439473] MTK add - START
        mDontPollSignalStrength = false;
        mLastSignalStrength = new SignalStrength(true);
        //[ALPS00439473] MTK add - END
        //MTK-ADD : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS only SIM
        //card or CS domain network registeration temporary failure
        isCsInvalidCard = false;
        //MTK-ADD: ALPS01830723
        mPsRegState = ServiceState.STATE_OUT_OF_SERVICE;
        mPsRegStateRaw = ServiceState.STATE_OUT_OF_SERVICE;
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    @Override
    public void pollState() {
        //[ALPS01577029]-START:To support auto switch rat mode to 2G only for 3M TDD csfb project
        //when we are not in china
        int currentNetworkMode = getPreferredNetworkModeSettings(mPhone.getPhoneId());
        //[ALPS01577029]-END

        log("pollState RadioState is " + mCi.getRadioState() + ", currentNetworkMode= "
                + currentNetworkMode);

        mPollingContext = new int[1];
        mPollingContext[0] = 0;

        //[ALPS01996342]
        if (dontUpdateNetworkStateFlag == true) {
            log("pollState is ignored!!");
            return;
        }

        switch (mCi.getRadioState()) {
            case RADIO_UNAVAILABLE:
                //M: MTK added for [ALPS01802701]
                //mNewSS.setStateOutOfService();
                mNewSS.setStateOff();
                //M: MTK added end
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                //M: MTK added
                setNullState();
                //M: MTK added end
                pollStateDone();
            break;

            case RADIO_OFF:
                mNewSS.setStateOff();
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                //M: MTK added
                setNullState();
                //M: MTK added end
                if (ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                        != mSS.getRilDataRadioTechnology()) {
                    pollStateDone();
                }

            default:
                // Issue all poll-related commands at once
                // then count down the responses, which
                // are allowed to arrive out-of-order

                mPollingContext[0]++;
                mCi.getOperator(
                    obtainMessage(
                        EVENT_POLL_STATE_OPERATOR, mPollingContext));

                mPollingContext[0]++;
                mCi.getDataRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_GPRS, mPollingContext));

                mPollingContext[0]++;
                mCi.getVoiceRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_REGISTRATION, mPollingContext));

                mPollingContext[0]++;
                mCi.getNetworkSelectionMode(
                    obtainMessage(
                        EVENT_POLL_STATE_NETWORK_SELECTION_MODE, mPollingContext));
            break;
        }
    }

    private void pollStateDone() {
        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
            mNewSS.setVoiceRoaming(true);
            mNewSS.setDataRoaming(true);
        }
        useDataRegStateForDataOnlyDevices();
        resetServiceStateInIwlanMode();


        if (DBG) {
            log("Poll ServiceState done: " +
                " oldSS=[" + mSS + "] newSS=[" + mNewSS + "]" +
                " oldMaxDataCalls=" + mMaxDataCalls +
                " mNewMaxDataCalls=" + mNewMaxDataCalls +
                " oldReasonDataDenied=" + mReasonDataDenied +
                " mNewReasonDataDenied=" + mNewReasonDataDenied);
        }

        //[ALPS01664312]-Add:Start
        //change format to update cid , lac and network type when camp on network after screen off
        if (mIsForceSendScreenOnForUpdateNwInfo) {
            log("send screen state OFF to restore format of CREG");
            mIsForceSendScreenOnForUpdateNwInfo = false;

            //[ALPS01810775,ALPS01868743] -Start: update network type at screen off
            //if (!mIsScreenOn) {
            if (mCi.getDisplayState() == Display.STATE_OFF) {
                mCi.sendScreenState(false);
            }
        }
        //[ALPS01664312]-Add:end

        boolean hasRegistered =
            mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasGprsAttached =
                mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasGprsDetached =
                mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasDataRegStateChanged =
                mSS.getDataRegState() != mNewSS.getDataRegState();

        boolean hasVoiceRegStateChanged =
                mSS.getVoiceRegState() != mNewSS.getVoiceRegState();

        //[ALPS01507528]-START:udpate Sim Indicate State when +CREG:<state> is changed
        boolean hasRilVoiceRegStateChanged =
                mSS.getRilVoiceRegState() != mNewSS.getRilVoiceRegState();
        //[ALPS01507528]-END


        boolean hasRilVoiceRadioTechnologyChanged =
                mSS.getRilVoiceRadioTechnology() != mNewSS.getRilVoiceRadioTechnology();

        boolean hasRilDataRadioTechnologyChanged =
                mSS.getRilDataRadioTechnology() != mNewSS.getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasVoiceRoamingOn = !mSS.getVoiceRoaming() && mNewSS.getVoiceRoaming();

        boolean hasVoiceRoamingOff = mSS.getVoiceRoaming() && !mNewSS.getVoiceRoaming();

        boolean hasDataRoamingOn = !mSS.getDataRoaming() && mNewSS.getDataRoaming();

        boolean hasDataRoamingOff = mSS.getDataRoaming() && !mNewSS.getDataRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        boolean hasLacChanged = mNewCellLoc.getLac() != mCellLoc.getLac();

        TelephonyManager tm =
                (TelephonyManager) mPhone.getContext().getSystemService(Context.TELEPHONY_SERVICE);

        log("pollStateDone,hasRegistered:" + hasRegistered + ",hasDeregistered:" + hasDeregistered
                + ",hasGprsAttached:" + hasGprsAttached
                + ",hasRilVoiceRadioTechnologyChanged:" + hasRilVoiceRadioTechnologyChanged
                + ",hasRilDataRadioTechnologyChanged:" + hasRilDataRadioTechnologyChanged
                + ",hasVoiceRegStateChanged:" + hasVoiceRegStateChanged + ",hasDataRegStateChanged:"
                + hasDataRegStateChanged + ",hasChanged:" + hasChanged + ",hasVoiceRoamingOn:"
                + hasVoiceRoamingOn + ",hasVoiceRoamingOff:" + hasVoiceRoamingOff
                + ",hasDataRoamingOn:" + hasDataRoamingOn + ",hasDataRoamingOff:"
                + hasDataRoamingOff + ",hasLocationChanged:" + hasLocationChanged
                + ",hasLacChanged:" + hasLacChanged
                + ",sReceiveNitz:" + getReceivedNitz());
        // Add an event log when connection state changes
        if (hasVoiceRegStateChanged || hasDataRegStateChanged) {
            EventLog.writeEvent(EventLogTags.GSM_SERVICE_STATE_CHANGE,
                mSS.getVoiceRegState(), mSS.getDataRegState(),
                mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        // Add an event log when network type switched
        // TODO: we may add filtering to reduce the event logged,
        // i.e. check preferred network setting, only switch to 2G, etc
        if (hasRilVoiceRadioTechnologyChanged) {
            int cid = -1;
            GsmCellLocation loc = mNewCellLoc;
            if (loc != null) cid = loc.getCid();
            // NOTE: this code was previously located after mSS and mNewSS are swapped, so
            // existing logs were incorrectly using the new state for "network_from"
            // and STATE_OUT_OF_SERVICE for "network_to". To avoid confusion, use a new log tag
            // to record the correct states.
            EventLog.writeEvent(EventLogTags.GSM_RAT_SWITCHED_NEW, cid,
                    mSS.getRilVoiceRadioTechnology(),
                    mNewSS.getRilVoiceRadioTechnology());
            if (DBG) {
                log("RAT switched "
                        + ServiceState.rilRadioTechnologyToString(mSS.getRilVoiceRadioTechnology())
                        + " -> "
                        + ServiceState.rilRadioTechnologyToString(
                                mNewSS.getRilVoiceRadioTechnology()) + " at cell " + cid);
            }
        }

        // swap mSS and mNewSS to put new state in mSS
        ServiceState tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        ////mNewSS.setStateOutOfService();
        // swap mCellLoc and mNewCellLoc to put new state in mCellLoc
        GsmCellLocation tcl = mCellLoc;
        mCellLoc = mNewCellLoc;
        mNewCellLoc = tcl;

        mReasonDataDenied = mNewReasonDataDenied;
        mMaxDataCalls = mNewMaxDataCalls;

        if (hasRilVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasRilDataRadioTechnologyChanged) {
            tm.setDataNetworkTypeForPhone(mPhone.getPhoneId(), mSS.getRilDataRadioTechnology());

            if (ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                        == mSS.getRilDataRadioTechnology()) {
                log("pollStateDone: IWLAN enabled");
            }
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
            mLastRegisteredPLMN = mSS.getOperatorNumeric() ;
            log("mLastRegisteredPLMN= " + mLastRegisteredPLMN);

            if (DBG) {
                log("pollStateDone: registering current mNitzUpdatedTime=" +
                        mNitzUpdatedTime + " changing to false");
            }
            mNitzUpdatedTime = false;
        }

        if (explict_update_spn == 1)
        {
             /* ALPS00273961 :Screen on, modem explictly send CREG URC , but still not able to
                update screen due to hasChanged is false
                In this case , we update SPN display by explict_update_spn */
             if (!hasChanged)
             {
                 log("explict_update_spn trigger to refresh SPN");
                 updateSpnDisplay();
             }
             explict_update_spn = 0;
        }

        if (hasChanged) {
            String operatorNumeric;

            updateSpnDisplay();

            //[ALPS01804936]-start:fix JE when change system language to "Burmese"
            // tm.setNetworkOperatorNameForPhone(mPhone.getPhoneId(), mSS.getOperatorAlphaLong());
            //updateOperatorAlpha(mSS.getOperatorAlphaLong());  //remark for [ALPS01965792]
            //[ALPS01804936]-end

            String prevOperatorNumeric = tm.getNetworkOperatorForPhone(mPhone.getPhoneId());
            operatorNumeric = mSS.getOperatorNumeric();
            tm.setNetworkOperatorNumericForPhone(mPhone.getPhoneId(), operatorNumeric);
            updateCarrierMccMncConfiguration(operatorNumeric,
                    prevOperatorNumeric, mPhone.getContext());
            //[ALPS01416062] MTK ADD-START
            if ((operatorNumeric != null) && (!isNumeric(operatorNumeric))) {
                if (DBG) log("operatorNumeric is Invalid value, don't update timezone");
            } else if (TextUtils.isEmpty(operatorNumeric)) {
                if (DBG) log("operatorNumeric is null");
                updateCarrierMccMncConfiguration(operatorNumeric,
                    prevOperatorNumeric, mPhone.getContext());
                tm.setNetworkCountryIsoForPhone(mPhone.getPhoneId(), "");
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
            } else {
                String iso = "";
                String mcc = "";
                try{
                    mcc = operatorNumeric.substring(0, 3);
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
                } catch (NumberFormatException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                tm.setNetworkCountryIsoForPhone(mPhone.getPhoneId(), iso);
                mGotCountryCode = true;

                TimeZone zone = null;

                if (!mNitzUpdatedTime && !mcc.equals("000") && !TextUtils.isEmpty(iso) &&
                        getAutoTimeZone()) {

                    // Test both paths if ignore nitz is true
                    boolean testOneUniqueOffsetPath = SystemProperties.getBoolean(
                                TelephonyProperties.PROPERTY_IGNORE_NITZ, false) &&
                                    ((SystemClock.uptimeMillis() & 1) == 0);

                    ArrayList<TimeZone> uniqueZones = TimeUtils.getTimeZonesWithUniqueOffsets(iso);
                    if ((uniqueZones.size() == 1) || testOneUniqueOffsetPath) {
                        zone = uniqueZones.get(0);
                        if (DBG) {
                           log("pollStateDone: no nitz but one TZ for iso-cc=" + iso +
                                   " with zone.getID=" + zone.getID() +
                                   " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath);
                        }
                        setAndBroadcastNetworkSetTimeZone(zone.getID());
                    //MTK-START: [ALPS01262709] update time with MCC/MNC
                    //} else {
                    } else if (uniqueZones.size() > 1) {
                        log("uniqueZones.size=" + uniqueZones.size() + " iso= " + iso);
                        zone = getTimeZonesWithCapitalCity(iso);
                        if (zone != null) {
                            setAndBroadcastNetworkSetTimeZone(zone.getID());
                        } else {
                            log("Can't find time zone for capital city");
                        }
                    //MTK-END: [ALPS01262709] update time with MCC/MNC
                    } else {
                        if (DBG) {
                            log("pollStateDone: there are " + uniqueZones.size() +
                                " unique offsets for iso-cc='" + iso +
                                " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath +
                                "', do nothing");
                        }
                    }
                }

                if (shouldFixTimeZoneNow(mPhone, operatorNumeric, prevOperatorNumeric,
                        mNeedFixZoneAfterNitz)) {
                    // If the offset is (0, false) and the timezone property
                    // is set, use the timezone property rather than
                    // GMT.
                    String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
                    if (DBG) {
                        log("pollStateDone: fix time zone zoneName='" + zoneName +
                            "' mZoneOffset=" + mZoneOffset + " mZoneDst=" + mZoneDst +
                            " iso-cc='" + iso +
                            "' iso-cc-idx=" + Arrays.binarySearch(GMT_COUNTRY_CODES, iso));
                    }

                    if (iso.equals("") && mNeedFixZoneAfterNitz) {
                        // Country code not found.  This is likely a test network.
                        // Get a TimeZone based only on the NITZ parameters (best guess).
                        zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
                        if (DBG) log("pollStateDone: using NITZ TimeZone");
                    } else if ((mZoneOffset == 0) && (mZoneDst == false) &&
                        (zoneName != null) && (zoneName.length() > 0) &&
                        (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)) {
                    // "(mZoneOffset == 0) && (mZoneDst == false) &&
                    //  (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)"
                    // means that we received a NITZ string telling
                    // it is in GMT+0 w/ DST time zone
                    // BUT iso tells is NOT, e.g, a wrong NITZ reporting
                    // local time w/ 0 offset.
                    /*
                    if ((mZoneOffset == 0) && (mZoneDst == false) &&
                         (zoneName != null) && (zoneName.length() > 0) &&
                         (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)) {*/

                        zone = TimeZone.getDefault();

                        //MTK-ADD-Start: [ALPS01262709] try ot fix timezone by MCC
                        //[ALPS01825832] fix timezone by MCC only if we don't recevice NITZ before
                        if (isAllowFixTimeZone()) {
                            try {
                                String mccTz = getTimeZonesByMcc(mcc);
                                mccTz = (mccTz == null) ?
                                    MccTable.defaultTimeZoneForMcc(Integer.parseInt(mcc)) : mccTz;
                                if (mccTz != null) {
                                    zone = TimeZone.getTimeZone(mccTz);
                                    if (DBG) log("pollStateDone: try to fixTimeZone mcc:" + mcc
                                            + " mccTz:" + mccTz + " zone.getID=" + zone.getID());
                                }
                            } catch (Exception e) {
                                log("pollStateDone: parse error: mcc=" + mcc);
                            }
                        }
                        //MTK-ADD-END: [ALPS01262709] try ot fix timezone by MCC

                        if (mNeedFixZoneAfterNitz) {
                            // For wrong NITZ reporting local time w/ 0 offset,
                            // need adjust time to reflect default timezone setting
                            long ctm = System.currentTimeMillis();
                            long tzOffset = zone.getOffset(ctm);
                            if (DBG) {
                                log("pollStateDone: tzOffset=" + tzOffset + " ltod=" +
                                        TimeUtils.logTimeOfDay(ctm));
                            }
                            if (getAutoTime()) {
                                long adj = ctm - tzOffset;
                                if (DBG) log("pollStateDone: adj ltod=" +
                                        TimeUtils.logTimeOfDay(adj));
                                setAndBroadcastNetworkSetTime(adj);
                            } else {
                                // Adjust the saved NITZ time to account for tzOffset.
                                mSavedTime = mSavedTime - tzOffset;
                            }
                        }
                        if (DBG) log("pollStateDone: using default TimeZone");
                    } else {
                        zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, iso);
                        if (DBG) log("pollStateDone: using getTimeZone(off, dst, time, iso)");
                    }

                    mNeedFixZoneAfterNitz = false;

                    if (zone != null) {
                        log("pollStateDone: zone != null zone.getID=" + zone.getID());
                        if (getAutoTimeZone()) {
                            setAndBroadcastNetworkSetTimeZone(zone.getID());
                        }
                        saveNitzTimeZone(zone.getID());
                    } else {
                        log("pollStateDone: zone == null");
                    }
                }
            }

            tm.setNetworkRoamingForPhone(mPhone.getPhoneId(), mSS.getVoiceRoaming());

            setRoamingType(mSS);
            log("Broadcasting ServiceState : " + mSS);
            mPhone.notifyServiceStateChanged(mSS);
        }

    //ALPS01830723: The states of PS network URC and poll state result might be different,
    //We need to compare these states and notify if reg state change.
/*
        if (hasGprsAttached) {
            mAttachedRegistrants.notifyRegistrants();
            mLastPSRegisteredPLMN = mSS.getOperatorNumeric() ;
            log("mLastPSRegisteredPLMN= "+mLastPSRegisteredPLMN);
        }

        if (hasGprsDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }
*/
        //mNewSS means old state and mSS means new state in this time.
        handlePsRegNotification(mNewSS.getDataRegState(), mSS.getDataRegState());

        if (hasDataRegStateChanged || hasRilDataRadioTechnologyChanged) {
            notifyDataRegStateRilRadioTechnologyChanged();

            if (ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                        == mSS.getRilDataRadioTechnology()) {
                mPhone.notifyDataConnection(Phone.REASON_IWLAN_AVAILABLE);
            } else {
                mPhone.notifyDataConnection(null);
            }
        }

        if (hasVoiceRoamingOn) {
            mVoiceRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasVoiceRoamingOff) {
            mVoiceRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOn) {
            mDataRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOff) {
            mDataRoamingOffRegistrants.notifyRegistrants();
        } else if (((mNewSS.getRilDataRegState() == ServiceState.REGISTRATION_STATE_HOME_NETWORK &&
                    (mSS.getRilDataRegState() == ServiceState.REGISTRATION_STATE_HOME_NETWORK ||
                    mSS.getRilDataRegState() == ServiceState.REGISTRATION_STATE_ROAMING)) ||
                    (mSS.getRilDataRegState() == ServiceState.REGISTRATION_STATE_ROAMING &&
                    mDataRoaming == false)) &&
                    mPsRegStateRaw == ServiceState.RIL_REG_STATE_ROAMING) {
            //Consider
            //1. From home plmn -> roaming URC -> home URC -> home plmnand -> recover setup data
            //2. From home plmn -> roaming URC -> domestic roam -> home plmn -> recover setup data
            //3. From home(domestic) plmn -> roaming URC -> home(domestic) plmn -> recover setup
            //   data
            log("recover setup data for roaming off. OldDataRegState:"
            + mNewSS.getRilDataRegState() + " NewDataRegState:" + mSS.getRilDataRegState() +
            " NewRoamingState:" + mSS.getRoaming() + " NewDataRoamingState:" + mDataRoaming +
            " PsRegState:" + mPsRegStateRaw);

            mPsRegStateRaw = ServiceState.RIL_REG_STATE_HOME;
            if (!mSS.getRoaming()) {
                mDataRoamingOffRegistrants.notifyRegistrants();
            }
        }

        if (hasLocationChanged) {
            mPhone.notifyLocationChanged();
        }

        if (! isGprsConsistent(mSS.getDataRegState(), mSS.getVoiceRegState())) {
            if (!mStartedGprsRegCheck && !mReportedGprsNoReg) {
                mStartedGprsRegCheck = true;

                int check_period = Settings.Global.getInt(
                        mPhone.getContext().getContentResolver(),
                        Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                        DEFAULT_GPRS_CHECK_PERIOD_MILLIS);
                sendMessageDelayed(obtainMessage(EVENT_CHECK_REPORT_GPRS),
                        check_period);
            }
        } else {
            mReportedGprsNoReg = false;
        }
        // TODO: Add GsmCellIdenity updating, see CdmaLteServiceStateTracker.
    }

    /**
     * Check if GPRS got registered while voice is registered.
     *
     * @param dataRegState i.e. CGREG in GSM
     * @param voiceRegState i.e. CREG in GSM
     * @return false if device only register to voice but not gprs
     */
    private boolean isGprsConsistent(int dataRegState, int voiceRegState) {
        return !((voiceRegState == ServiceState.STATE_IN_SERVICE) &&
                (dataRegState != ServiceState.STATE_IN_SERVICE));
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        log("[NITZ],findTimeZone,offset:" + offset + ",dst:" + dst + ",when:" + when);
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                tz.inDaylightTime(d) == dst) {
                guess = tz;
                log("[NITZ],find time zone.");
                break;
            }
        }

        return guess;
    }

    private void queueNextSignalStrengthPoll() {
        if (mDontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        long nextTime;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     * Set restricted state based on the OnRestrictedStateChanged notification
     * If any voice or packet restricted state changes, trigger a UI
     * notification and notify registrants when sim is ready.
     *
     * @param ar an int value of RIL_RESTRICTED_STATE_*
     */
    private void onRestrictedStateChanged(AsyncResult ar) {
        RestrictedState newRs = new RestrictedState();

        if (DBG) log("onRestrictedStateChanged: E rs "+ mRestrictedState);

        if (ar.exception == null) {
            int[] ints = (int[])ar.result;
            int state = ints[0];

            newRs.setCsEmergencyRestricted(
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_EMERGENCY) != 0) ||
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
            //ignore the normal call and data restricted state before SIM READY
            if (mUiccApplcation != null && mUiccApplcation.getState() == AppState.APPSTATE_READY) {
                newRs.setCsNormalRestricted(
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_NORMAL) != 0) ||
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
                newRs.setPsRestricted(
                        (state & RILConstants.RIL_RESTRICTED_STATE_PS_ALL)!= 0);
            } else {
                log("IccCard state Not ready ");
                if (mRestrictedState.isCsNormalRestricted() &&
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_NORMAL) == 0 &&
                    (state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) == 0)) {
                        newRs.setCsNormalRestricted(false);
                }

                if (mRestrictedState.isPsRestricted()
                        && ((state & RILConstants.RIL_RESTRICTED_STATE_PS_ALL) == 0)) {
                    newRs.setPsRestricted(false);
                }
            }

            if (DBG) log("onRestrictedStateChanged: new rs "+ newRs);

            if (!mRestrictedState.isPsRestricted() && newRs.isPsRestricted()) {
                mPsRestrictEnabledRegistrants.notifyRegistrants();
                setNotification(PS_ENABLED);
            } else if (mRestrictedState.isPsRestricted() && !newRs.isPsRestricted()) {
                if (mPollingContext[0] != 0) {
                    mPendingPsRestrictDisabledNotify = true;
                } else {
                    mPsRestrictDisabledRegistrants.notifyRegistrants();
                    setNotification(PS_DISABLED);
                }
            }

            /**
             * There are two kind of cs restriction, normal and emergency. So
             * there are 4 x 4 combinations in current and new restricted states
             * and we only need to notify when state is changed.
             */
            if (mRestrictedState.isCsRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (!newRs.isCsNormalRestricted()) {
                    // remove normal restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (!newRs.isCsEmergencyRestricted()) {
                    // remove emergency restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (mRestrictedState.isCsEmergencyRestricted() &&
                    !mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // remove emergency restriction and enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (!mRestrictedState.isCsEmergencyRestricted() &&
                    mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // remove normal restriction and enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                }
            } else {
                if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            }

            mRestrictedState = newRs;
        }
        log("onRestrictedStateChanged: X rs "+ mRestrictedState);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        switch (code) {
            case 0:
            case 2: // 2 is "searching"
            case 3: // 3 is "registration denied"
            case 4: // 4 is "unknown" no vaild in current baseband
            case 10:// same as 0, but indicates that emergency call is possible.
            case 12:// same as 2, but indicates that emergency call is possible.
            case 13:// same as 3, but indicates that emergency call is possible.
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.STATE_OUT_OF_SERVICE;

            case 1:
                return ServiceState.STATE_IN_SERVICE;

            case 5:
                // in service, roam
                return ServiceState.STATE_IN_SERVICE;

            default:
                loge("regCodeToServiceState: unexpected service state " + code);
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToRegState(int code) {
        switch (code) {
            case 10:// same as 0, but indicates that emergency call is possible.
                return ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING;
            case 12:// same as 2, but indicates that emergency call is possible.
                return ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_SEARCHING;
            case 13:// same as 3, but indicates that emergency call is possible.
                return ServiceState.REGISTRATION_STATE_REGISTRATION_DENIED;
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.REGISTRATION_STATE_UNKNOWN;
            default:
                return code;
        }
    }

    private String getSIMOperatorNumeric() {
        IccRecords r = mIccRecords;
        String mccmnc;
        String imsi;

        if (r != null) {
            mccmnc = r.getOperatorNumeric();

            //M: [ALPS01591758]Try to get HPLMN from IMSI (getOperatorNumeric might response null
            //due to mnc length is not available yet)
            if (mccmnc == null) {
                imsi = r.getIMSI();
                if (imsi != null && !imsi.equals("")) {
                    mccmnc = imsi.substring(0, 5);
                    log("get MCC/MNC from IMSI = " + mccmnc);
                }
            }
            return mccmnc;
        } else {
            return null;
        }
    }
    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming (int code) {
        //M: MTK added
        boolean isRoaming = false;
        String strHomePlmn = getSIMOperatorNumeric();
        String strServingPlmn = mNewSS.getOperatorNumeric();
        boolean isServingPlmnInGroup = false;
        boolean isHomePlmnInGroup = false;
        boolean ignoreDomesticRoaming = false;

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                if ((strServingPlmn != null)
                        && (strHomePlmn != null)
                        && mServiceStateExt.isRoamingForSpecialSIM(strServingPlmn,
                        strHomePlmn)) {
                    return true;
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        if (ServiceState.RIL_REG_STATE_ROAMING == code) {
            isRoaming = true;
        }

        /* ALPS00296372 */
        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                ignoreDomesticRoaming = mServiceStateExt.ignoreDomesticRoaming();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        if ((ignoreDomesticRoaming == true) && (isRoaming == true)
                && (strServingPlmn != null) && (strHomePlmn != null)) {
            log("ServingPlmn = " + strServingPlmn + " HomePlmn = " + strHomePlmn);
            if (strHomePlmn.substring(0, 3).equals(strServingPlmn.substring(0, 3))) {
                log("Same MCC,don't set as roaming");
                isRoaming = false;
            }
        }

        /* ALPS00236452: check manufacturer maintained table for specific operator with
           multiple home PLMN id */
        if ((isRoaming == true) && (strServingPlmn != null) && (strHomePlmn != null)) {
            log("strServingPlmn = " + strServingPlmn + " strHomePlmn = " + strHomePlmn);

            for (int i = 0; i < customEhplmn.length; i++) {
                //reset flag
                isServingPlmnInGroup = false;
                isHomePlmnInGroup = false;

                //check if serving plmn or home plmn in this group
                for (int j = 0; j < customEhplmn[i].length; j++) {
                    if (strServingPlmn.equals(customEhplmn[i][j])) {
                        isServingPlmnInGroup = true;
                    }
                    if (strHomePlmn.equals(customEhplmn[i][j])) {
                        isHomePlmnInGroup = true;
                    }
                }

                //if serving plmn and home plmn both in the same group , do NOT treat it as roaming
                if ((isServingPlmnInGroup == true) && (isHomePlmnInGroup == true)) {
                    isRoaming = false;
                    log("Ignore roaming");
                    break;
                }
            }
        }

        return isRoaming;
        // M : MTK added end

        //return ServiceState.RIL_REG_STATE_ROAMING == code;
    }

    /**
     * Set roaming state if operator mcc is the same as sim mcc
     * and ons is different from spn
     *
     * @param s ServiceState hold current ons
     * @return true if same operator
     */
    private boolean isSameNamedOperators(ServiceState s) {
        String spn = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNameForPhone(getPhoneId());

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        // M: [ALPS02292031] check if the ons is ""
        boolean equalsOnsl = onsl != null && onsl.length() > 0 && spn.equals(onsl);
        boolean equalsOnss = onss != null && onss.length() > 0 && spn.equals(onss);

        if (VDBG) log("isSameNamedOperators(): onsl=" + onsl + ",onss=" + onss + ",spn=" + spn);

        return currentMccEqualsSimMcc(s) && (equalsOnsl || equalsOnss);
    }

    /**
     * Compare SIM MCC with Operator MCC
     *
     * @param s ServiceState hold current ons
     * @return true if both are same
     */
    private boolean currentMccEqualsSimMcc(ServiceState s) {
        String simNumeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(getPhoneId());
        String operatorNumeric = s.getOperatorNumeric();
        boolean equalsMcc = true;

        try {
            equalsMcc = simNumeric.substring(0, 3).
                    equals(operatorNumeric.substring(0, 3));
            if (VDBG) log("currentMccEqualsSimMcc(): equalsMcc=" + equalsMcc + ",simNumeric="
                    + simNumeric + ",operatorNumeric=" + operatorNumeric);
        } catch (Exception e) {
        }
        return equalsMcc;
    }

    /**
     * Do not set roaming state in case of oprators considered non-roaming.
     *
     + Can use mcc or mcc+mnc as item of config_operatorConsideredNonRoaming.
     * For example, 302 or 21407. If mcc or mcc+mnc match with operator,
     * don't set roaming state.
     *
     * @param s ServiceState hold current ons
     * @return false for roaming state set
     */
    private boolean isOperatorConsideredNonRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        String[] numericArray = mPhone.getContext().getResources().getStringArray(
                    com.android.internal.R.array.config_operatorConsideredNonRoaming);

        if (VDBG) log("isOperatorConsideredNonRoaming operatorNumeric= " + operatorNumeric
                + ",legnth= " + numericArray.length);

        if (numericArray.length == 0 || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (VDBG) log("isOperatorConsideredNonRoaming numeric= " + numeric);
            if (operatorNumeric.startsWith(numeric)) {
                if (VDBG) log("isOperatorConsideredNonRoaming return true");
                return true;
            }
        }
        return false;
    }

    /// M: [ALPS02503235] add operator considered roaming configures
    private boolean isOperatorConsideredRoamingMtk(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();

        if (VDBG)
            log("isOperatorConsideredRoaming operatorNumeric= "
                    + operatorNumeric + ",legnth= "
                    + customOperatorConsiderRoamingMcc.length);

        if (customOperatorConsiderRoamingMcc.length == 0
                || operatorNumeric == null) {
            return false;
        }

        for (String[] numerics : customOperatorConsiderRoamingMcc) {
            for (String numeric : numerics) {
                if (VDBG) {
                    log("isOperatorConsideredRoaming numeric= " + numeric);
                }
                if (operatorNumeric.startsWith(numeric)) {
                    if (VDBG) {
                        log("isOperatorConsideredRoaming return true");
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOperatorConsideredRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        String[] numericArray = mPhone.getContext().getResources().getStringArray(
                    com.android.internal.R.array.config_sameNamedOperatorConsideredRoaming);

        if (VDBG) log("isOperatorConsideredRoaming operatorNumeric= " + operatorNumeric
                + ",legnth= " + numericArray.length);

        if (numericArray.length == 0 || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (VDBG) log("isOperatorConsideredRoaming numeric= " + numeric);
            if (operatorNumeric.startsWith(numeric)) {
                if (VDBG) log("isOperatorConsideredRoaming return true");
                return true;
            }
        }
        return false;
    }

    /**
     * @return The current GPRS state. IN_SERVICE is the same as "attached"
     * and OUT_OF_SERVICE is the same as detached.
     */
    @Override
    public int getCurrentDataConnectionState() {
        return mSS.getDataRegState();
    }

    /**
     * @return true if phone is camping on a technology (eg UMTS)
     * that could support voice and data simultaneously.
     */
    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        //return (mSS.getRilVoiceRadioTechnology() >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);

        //[ALPS01520958]-START:Detail HSPA PS bearer information for HSPA DC icon display
        boolean isAllowed = false;
        if (mSS.isVoiceRadioTechnologyHigher(ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) ||
            mSS.getRilVoiceRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) {
            isAllowed = true;
        }
        //[ALPS01520958]-END

        if (DBG) {
            log("isConcurrentVoiceAndDataAllowed(): " + isAllowed);
        }
        return isAllowed;
    }

    /**
     * @return the current cell location information. Prefer Gsm location
     * information if available otherwise return LTE location information
     */
    public CellLocation getCellLocation() {
        if ((mCellLoc.getLac() >= 0) && (mCellLoc.getCid() >= 0)) {
            if (DBG) log("getCellLocation(): X good mCellLoc=" + mCellLoc);
            return mCellLoc;
        } else {
            List<CellInfo> result = getAllCellInfo();
            if (result != null) {
                // A hack to allow tunneling of LTE information via GsmCellLocation
                // so that older Network Location Providers can return some information
                // on LTE only networks, see bug 9228974.
                //
                // We'll search the return CellInfo array preferring GSM/WCDMA
                // data, but if there is none we'll tunnel the first LTE information
                // in the list.
                //
                // The tunnel'd LTE information is returned as follows:
                //   LAC = TAC field
                //   CID = CI field
                //   PSC = 0.
                GsmCellLocation cellLocOther = new GsmCellLocation();
                for (CellInfo ci : result) {
                    if (ci instanceof CellInfoGsm) {
                        CellInfoGsm cellInfoGsm = (CellInfoGsm)ci;
                        CellIdentityGsm cellIdentityGsm = cellInfoGsm.getCellIdentity();
                        cellLocOther.setLacAndCid(cellIdentityGsm.getLac(),
                                cellIdentityGsm.getCid());
                        cellLocOther.setPsc(cellIdentityGsm.getPsc());
                        if (DBG) log("getCellLocation(): X ret GSM info=" + cellLocOther);
                        return cellLocOther;
                    } else if (ci instanceof CellInfoWcdma) {
                        CellInfoWcdma cellInfoWcdma = (CellInfoWcdma)ci;
                        CellIdentityWcdma cellIdentityWcdma = cellInfoWcdma.getCellIdentity();
                        cellLocOther.setLacAndCid(cellIdentityWcdma.getLac(),
                                cellIdentityWcdma.getCid());
                        cellLocOther.setPsc(cellIdentityWcdma.getPsc());
                        if (DBG) log("getCellLocation(): X ret WCDMA info=" + cellLocOther);
                        return cellLocOther;
                    } else if ((ci instanceof CellInfoLte) &&
                            ((cellLocOther.getLac() < 0) || (cellLocOther.getCid() < 0))) {
                        // We'll return the first good LTE info we get if there is no better answer
                        CellInfoLte cellInfoLte = (CellInfoLte)ci;
                        CellIdentityLte cellIdentityLte = cellInfoLte.getCellIdentity();
                        if ((cellIdentityLte.getTac() != Integer.MAX_VALUE)
                                && (cellIdentityLte.getCi() != Integer.MAX_VALUE)) {
                            cellLocOther.setLacAndCid(cellIdentityLte.getTac(),
                                    cellIdentityLte.getCi());
                            cellLocOther.setPsc(0);
                            if (DBG) {
                                log("getCellLocation(): possible LTE cellLocOther=" + cellLocOther);
                            }
                        }
                    }
                }
                if (DBG) {
                    log("getCellLocation(): X ret best answer cellLocOther=" + cellLocOther);
                }
                return cellLocOther;
            } else {
                if (DBG) {
                    log("getCellLocation(): X empty mCellLoc and CellInfo mCellLoc=" + mCellLoc);
                }
                return mCellLoc;
            }
        }
    }

    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */
    private void setTimeFromNITZString (String nitz, long nitzReceiveTime) {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        if (DBG) {log("NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));
        }

        try {
            /* NITZ time (hour:min:sec) will be in UTC but it supplies the timezone
             * offset as well (which we won't worry about until later) */
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

            c.clear();
            c.set(Calendar.DST_OFFSET, 0);

            String[] nitzSubs = nitz.split("[/:,+-]");

            int year = 2000 + Integer.parseInt(nitzSubs[0]);
            c.set(Calendar.YEAR, year);

            // month is 0 based!
            int month = Integer.parseInt(nitzSubs[1]) - 1;
            c.set(Calendar.MONTH, month);

            int date = Integer.parseInt(nitzSubs[2]);
            c.set(Calendar.DATE, date);

            int hour = Integer.parseInt(nitzSubs[3]);
            c.set(Calendar.HOUR, hour);

            int minute = Integer.parseInt(nitzSubs[4]);
            c.set(Calendar.MINUTE, minute);

            int second = Integer.parseInt(nitzSubs[5]);
            c.set(Calendar.SECOND, second);

            boolean sign = (nitz.indexOf('-') == -1);

            int tzOffset = Integer.parseInt(nitzSubs[6]);

            //MTK-START [ALPS00540036]
            int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
                    : getDstForMcc(getMobileCountryCode(), c.getTimeInMillis());

            //int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
            //                                  : 0;
            //MTK-END [ALPS00540036]

            // The zone offset received from NITZ is for current local time,
            // so DST correction is already applied.  Don't add it again.
            //
            // tzOffset += dst * 4;
            //
            // We could unapply it if we wanted the raw offset.

            tzOffset = (sign ? 1 : -1) * tzOffset * 15 * 60 * 1000;

            TimeZone    zone = null;

            // As a special extension, the Android emulator appends the name of
            // the host computer's timezone to the nitz string. this is zoneinfo
            // timezone name of the form Area!Location or Area!Location!SubLocation
            // so we need to convert the ! into /
            if (nitzSubs.length >= 9) {
                String  tzname = nitzSubs[8].replace('!','/');
                zone = TimeZone.getTimeZone( tzname );
                log("[NITZ] setTimeFromNITZString,tzname:" + tzname + " zone:" + zone);
            }

            String iso = ((TelephonyManager) mPhone.getContext().
                    getSystemService(Context.TELEPHONY_SERVICE)).
                    getNetworkCountryIsoForPhone(mPhone.getPhoneId());

            log("[NITZ] setTimeFromNITZString,mGotCountryCode:" + mGotCountryCode);

            if (zone == null) {

                if (mGotCountryCode) {
                    if (iso != null && iso.length() > 0) {
                        zone = TimeUtils.getTimeZone(tzOffset, dst != 0,
                                c.getTimeInMillis(),
                                iso);
                    } else {
                        // We don't have a valid iso country code.  This is
                        // most likely because we're on a test network that's
                        // using a bogus MCC (eg, "001"), so get a TimeZone
                        // based only on the NITZ parameters.
                        zone = getNitzTimeZone(tzOffset, (dst != 0), c.getTimeInMillis());
                    }
                }
            }

            if ((zone == null) || (mZoneOffset != tzOffset) || (mZoneDst != (dst != 0))) {
                // We got the time before the country or the zone has changed
                // so we don't know how to identify the DST rules yet.  Save
                // the information and hope to fix it up later.

                mNeedFixZoneAfterNitz = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();

                //[ALPS01825832] set flag when receive NITZ
                setReceivedNitz(mPhone.getPhoneId(), true);
            }

            if (zone != null) {
                if (getAutoTimeZone()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                log("NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                if (getAutoTime()) {
                    long millisSinceNitzReceived
                            = SystemClock.elapsedRealtime() - nitzReceiveTime;

                    if (millisSinceNitzReceived < 0) {
                        // Sanity check: something is wrong
                        if (DBG) {
                            log("NITZ: not setting time, clock has rolled "
                                            + "backwards since NITZ time was received, "
                                            + nitz);
                        }
                        return;
                    }

                    if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                        // If the time is this far off, something is wrong > 24 days!
                        if (DBG) {
                            log("NITZ: not setting time, processing has taken "
                                        + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                        + " days");
                        }
                        return;
                    }

                    // Note: with range checks above, cast to int is safe
                    c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                    if (DBG) {
                        log("NITZ: Setting time of day to " + c.getTime()
                            + " NITZ receive delay(ms): " + millisSinceNitzReceived
                            + " gained(ms): "
                            + (c.getTimeInMillis() - System.currentTimeMillis())
                            + " from " + nitz);
                    }
                    setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    Rlog.i(LOG_TAG, "NITZ: after Setting time of day");
                }
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                saveNitzTime(c.getTimeInMillis());
                if (VDBG) {
                    long end = SystemClock.elapsedRealtime();
                    log("NITZ: end=" + end + " dur=" + (end - start));
                }
                mNitzUpdatedTime = true;
            } finally {
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            loge("NITZ: Parsing NITZ time " + nitz + " ex=" + ex);
        }
    }

    private boolean isAllowFixTimeZone() {
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            if (sReceiveNitz[i]) {
                log("Phone" + i + " has received NITZ!!");
                return false;
            }
        }
        log("Fix time zone allowed");
        return true;
    }

    private boolean getAutoTime() {
        try {
            return Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private boolean getAutoTimeZone() {
        try {
            return Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.AUTO_TIME_ZONE) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        log("saveNitzTimeZone zoneId:" + zoneId);
        mSavedTimeZone = zoneId;
    }

    private void saveNitzTime(long time) {
        if (DBG) log("saveNitzTime: time=" + time);
        mSavedTime = time;
        mSavedAtTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the timezone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        if (DBG) log("setAndBroadcastNetworkSetTimeZone: setTimeZone=" + zoneId);
        AlarmManager alarm =
            (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
        alarm.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time-zone", zoneId);
        mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        if (DBG) {
            log("setAndBroadcastNetworkSetTimeZone: call alarm.setTimeZone and broadcast zoneId=" +
                zoneId);
        }
    }

    /**
     * Set the time and Send out a sticky broadcast so the system can determine
     * if the time was set by the carrier.
     *
     * @param time time set by network
     */
    private void setAndBroadcastNetworkSetTime(long time) {
        if (DBG) log("setAndBroadcastNetworkSetTime: time=" + time + "ms");
        SystemClock.setCurrentTimeMillis(time);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time", time);
        mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void revertToNitzTime() {
        if (Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AUTO_TIME, 0) == 0) {
            log("[NITZ]:revertToNitz,AUTO_TIME is 0");
            return;
        }
        if (DBG) {
            log("Reverting to NITZ Time: mSavedTime=" + mSavedTime
                + " mSavedAtTime=" + mSavedAtTime + " tz='" + mSavedTimeZone + "'");
        }
        if (mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    private void revertToNitzTimeZone() {
        if (Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE, 0) == 0) {
            return;
        }

        // [ALPS01962013] This phone has received NITZ, so no need to do any fix
        if (getReceivedNitz()) {
            if (DBG) log("Reverting to NITZ TimeZone: tz='" + mSavedTimeZone);
            if (mSavedTimeZone != null) {
                setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
            }
            return;
        }

        // [ALPS01962013] No phone has recieved NITZ, so fix it and update
        if (isAllowFixTimeZone()) {
            fixTimeZone();
            if (DBG) log("Reverting to fixed TimeZone: tz='" + mSavedTimeZone);
        if (mSavedTimeZone != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
        }
            return;
        }

        // [ALPS01962013] This phone did't receive NITZ, but other phone did
        if (DBG) log("Do nothing since other phone has received NITZ, but this phone didn't");
    }

    /**
     * Post a notification to NotificationManager for restricted state
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    private void setNotification(int notifyType) {
    /* ALPS00339508 :Remove restricted access change notification */
    /*
        if (DBG) log("setNotification: create notification " + notifyType);

        // Needed because sprout RIL sends these when they shouldn't?
        boolean isSetNotification = mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_user_notification_of_restrictied_mobile_access);
        if (!isSetNotification) {
            if (DBG) log("Ignore all the notifications");
            return;
        }

        Context context = mPhone.getContext();


        CharSequence details = "";
        CharSequence title = context.getText(com.android.internal.R.string.RestrictedChangedTitle);
        int notificationId = CS_NOTIFICATION;

        switch (notifyType) {
        case PS_ENABLED:
            long dataSubId = SubscriptionManager.getDefaultDataSubId();
            if (dataSubId != mPhone.getSubId()) {
                return;
            }
            notificationId = PS_NOTIFICATION;
            details = context.getText(com.android.internal.R.string.RestrictedOnData);
            break;
        case PS_DISABLED:
            notificationId = PS_NOTIFICATION;
            break;
        case CS_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnAllVoice);
            break;
        case CS_NORMAL_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnNormal);
            break;
        case CS_EMERGENCY_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnEmergency);
            break;
        case CS_DISABLED:
            // do nothing and cancel the notification later
            break;
        }

        if (DBG) log("setNotification: put notification " + title + " / " +details);
        mNotification = new Notification.Builder(context)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                .setTicker(title)
                .setColor(context.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(details)
                .build();

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == PS_DISABLED || notifyType == CS_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(notificationId);
        } else {
            // update restricted state notification
            notificationManager.notify(notificationId, mNotification);
        }
        */
    }

    private UiccCardApplication getUiccCardApplication() {
            return  mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                    UiccController.APP_FAM_3GPP);
    }

    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();

        if (mUiccApplcation != newUiccApplication) {
            if (mUiccApplcation != null) {
                log("Removing stale icc objects.");
                mUiccApplcation.unregisterForReady(this);
                if (mIccRecords != null) {
                    mIccRecords.unregisterForRecordsLoaded(this);
                }
                mIccRecords = null;
                mUiccApplcation = null;
            }
            if (newUiccApplication != null) {
                log("New card found");
                mUiccApplcation = newUiccApplication;
                mIccRecords = mUiccApplcation.getIccRecords();
                mUiccApplcation.registerForReady(this, EVENT_SIM_READY, null);
                if (mIccRecords != null) {
                    mIccRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                }
            }
        }
    }
    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[GsmSST" + mPhone.getPhoneId() + "] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[GsmSST" + mPhone.getPhoneId() + "] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mSS=" + mSS);
        pw.println(" mNewSS=" + mNewSS);
        pw.println(" mCellLoc=" + mCellLoc);
        pw.println(" mNewCellLoc=" + mNewCellLoc);
        pw.println(" mPreferredNetworkType=" + mPreferredNetworkType);
        pw.println(" mMaxDataCalls=" + mMaxDataCalls);
        pw.println(" mNewMaxDataCalls=" + mNewMaxDataCalls);
        pw.println(" mReasonDataDenied=" + mReasonDataDenied);
        pw.println(" mNewReasonDataDenied=" + mNewReasonDataDenied);
        pw.println(" mGsmRoaming=" + mGsmRoaming);
        pw.println(" mDataRoaming=" + mDataRoaming);
        pw.println(" mEmergencyOnly=" + mEmergencyOnly);
        pw.println(" mNeedFixZoneAfterNitz=" + mNeedFixZoneAfterNitz);
        pw.flush();
        pw.println(" mZoneOffset=" + mZoneOffset);
        pw.println(" mZoneDst=" + mZoneDst);
        pw.println(" mZoneTime=" + mZoneTime);
        pw.println(" mGotCountryCode=" + mGotCountryCode);
        pw.println(" mNitzUpdatedTime=" + mNitzUpdatedTime);
        pw.println(" mSavedTimeZone=" + mSavedTimeZone);
        pw.println(" mSavedTime=" + mSavedTime);
        pw.println(" mSavedAtTime=" + mSavedAtTime);
        pw.println(" mStartedGprsRegCheck=" + mStartedGprsRegCheck);
        pw.println(" mReportedGprsNoReg=" + mReportedGprsNoReg);
        pw.println(" mNotification=" + mNotification);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mCurSpn=" + mCurSpn);
        pw.println(" mCurDataSpn=" + mCurDataSpn);
        pw.println(" mCurShowSpn=" + mCurShowSpn);
        pw.println(" mCurPlmn=" + mCurPlmn);
        pw.println(" mCurShowPlmn=" + mCurShowPlmn);
        pw.flush();
    }


    /**
     * Clean up existing voice and data connection then turn off radio power.
     *
     * Hang up the existing voice calls to decrease call drop rate.
     */
    @Override
    public void powerOffRadioSafely(DcTrackerBase dcTracker) {
        synchronized (this) {
            if (!mPendingRadioPowerOffAfterDataOff) {
                int dds = SubscriptionManager.getDefaultDataSubId();
                int phoneSubId = mPhone.getSubId();
                // To minimize race conditions we call cleanUpAllConnections on
                // both if else paths instead of before this isDisconnected test.
                log("powerOffRadioSafely phoneId=" + SubscriptionManager.getPhoneId(dds)
                        + ", dds=" + dds + ", mPhone.getSubId()=" + mPhone.getSubId()
                        + ", phoneSubId=" + phoneSubId);
                if (dds != SubscriptionManager.INVALID_SUBSCRIPTION_ID && (dcTracker.isDisconnected()
                        || dds != phoneSubId)) {
                    // M: remove check peer phone data state
                    // To minimize race conditions we do this after isDisconnected
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);
                    if (DBG) log("Data disconnected, turn off radio right away.");
                    hangupAndPowerOff();
                } else {
                    // hang up all active voice calls first
                    if (mPhone.isInCall()) {
                        mPhone.mCT.mRingingCall.hangupIfAlive();
                        mPhone.mCT.mBackgroundCall.hangupIfAlive();
                        mPhone.mCT.mForegroundCall.hangupIfAlive();
                    }
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);

                    if (dds == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            || SubscriptionManager.getPhoneId(dds)
                            == SubscriptionManager.DEFAULT_PHONE_INDEX) {
                        if (dcTracker.isDisconnected() || dcTracker.isOnlyIMSorEIMSPdnConnected()) {
                            if (DBG) log("Data disconnected (no data sub), " +
                                    "turn off radio right away.");
                            hangupAndPowerOff();
                            return;
                        } else {
                            if (DBG) log("Data is active on.  Wait for all data disconnect");
                            mPhone.registerForAllDataDisconnected(this,
                                    EVENT_ALL_DATA_DISCONNECTED, null);
                            mPendingRadioPowerOffAfterDataOff = true;
                        }
                    }

                    if (dcTracker.isOnlyIMSorEIMSPdnConnected()) {
                        if (DBG) {
                            log("Only IMS or EIMS connected, " +
                                    "turn off radio right away.");
                        }
                        hangupAndPowerOff();
                        return;
                    }

                    Message msg = Message.obtain(this);
                    msg.what = EVENT_SET_RADIO_POWER_OFF;
                    msg.arg1 = ++mPendingRadioPowerOffAfterDataOffTag;
                    if (sendMessageDelayed(msg, 30000)) {
                        if (DBG) log("Wait upto 30s for data to disconnect, then turn off radio.");
                        mPendingRadioPowerOffAfterDataOff = true;
                    } else {
                        log("Cannot send delayed Msg, turn off radio right away.");
                        hangupAndPowerOff();
                        mPendingRadioPowerOffAfterDataOff = false;
                    }
                }
            }
        }

    }

    public void setImsRegistrationState(boolean registered) {
        if (mImsRegistrationOnOff && !registered) {
            if (mAlarmSwitch) {
                mImsRegistrationOnOff = registered;

                Context context = mPhone.getContext();
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                am.cancel(mRadioOffIntent);
                mAlarmSwitch = false;

                sendMessage(obtainMessage(EVENT_CHANGE_IMS_STATE));
                return;
            }
        }
        mImsRegistrationOnOff = registered;
    }

    public void onImsCapabilityChanged() {
        sendMessage(obtainMessage(EVENT_IMS_CAPABILITY_CHANGED));
    }

    private void onNetworkStateChangeResult(AsyncResult ar) {
        String info[];
        int state = -1;
        int lac = -1;
        int cid = -1;
        int Act = -1;
        int cause = -1;

        /* Note: There might not be full +CREG URC info when screen off
                   Full URC format: +CREG:  <stat>, <lac>, <cid>, <Act>,<cause> */
        if (ar.exception != null || ar.result == null) {
           loge("onNetworkStateChangeResult exception");
        } else {
            info = (String[]) ar.result;

            if (info.length > 0) {

                state = Integer.parseInt(info[0]);

                if (info[1] != null && info[1].length() > 0) {
                   lac = Integer.parseInt(info[1], 16);
                }

                if (info[2] != null && info[2].length() > 0) {
                   //TODO: fix JE (java.lang.NumberFormatException: Invalid int: "ffffffff")
                   if (info[2].equals("FFFFFFFF") || info[2].equals("ffffffff")) {
                       log("Invalid cid:" + info[2]);
                       info[2] = "0000ffff";
                   }
                   cid = Integer.parseInt(info[2], 16);
                }

                if (info[3] != null && info[3].length() > 0) {
                   Act = Integer.parseInt(info[3]);
                }

                if (info[4] != null && info[4].length() > 0) {
                   cause = Integer.parseInt(info[4]);
                }

                log("onNetworkStateChangeResult state:" + state + " lac:" + lac + " cid:" + cid
                        + " Act:" + Act + " cause:" + cause);

                //ALPS00267573 CDR-ONS-245
                if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    try {
                        if (mServiceStateExt.needIgnoredState(
                                mSS.getVoiceRegState(), state, cause) == true) {
                            //MTK-ADD START : for CS not registered , PS regsitered (ex: LTE PS only
                            //mode or 2/3G PS only SIM card or CS domain network registeration
                            //temporary failure
                            /* in case of CS not registered but PS regsitered, it will fasle alarm
                               "CS invalid".*/
                            log("onNetworkStateChangeResult isCsInvalidCard:" + isCsInvalidCard);
                            if (!isCsInvalidCard) {
                                if (dontUpdateNetworkStateFlag == false) {
                                    broadcastHideNetworkState("start",
                                            ServiceState.STATE_OUT_OF_SERVICE);
                                }
                                dontUpdateNetworkStateFlag = true;
                            } //end of if (!isCsInvalidCard)
                            return;
                        } else {
                            if (dontUpdateNetworkStateFlag == true) {
                                broadcastHideNetworkState("stop",
                                        ServiceState.STATE_OUT_OF_SERVICE);
                            }
                            dontUpdateNetworkStateFlag = false;
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                    if (mEverPollSignalStrength == false && mDontPollSignalStrength == false &&
                        regCodeToServiceState(state) == ServiceState.STATE_IN_SERVICE &&
                        isOp01Support()) {
                        log("Force Poll SignalStrength ECSQ, Only Once Here");
                        mCi.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH_ONLY));
                        mEverPollSignalStrength = true;
                    }
                }
                /* AT+CREG? result won't include <lac>,<cid> when phone is NOT registered.
                   So we wpdate mNewCellLoc via +CREG URC when phone is not registered to network,
                   so that CellLoc can be updated when pollStateDone  */
                if ((lac != -1) && (cid != -1) && (regCodeToServiceState(state)
                        == ServiceState.STATE_OUT_OF_SERVICE)) {
                    // ignore unknown lac or cid value
                    if (lac == 0xfffe || cid == 0x0fffffff) {
                        log("unknown lac:" + lac + " or cid:" + cid);
                    } else {
                        log("mNewCellLoc Updated, lac:" + lac + " and cid:" + cid);
                        mNewCellLoc.setLacAndCid(lac, cid);
                    }
                }

                if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    try {
                    // ALPS00283696 CDR-NWS-241
                        if (mServiceStateExt.needRejectCauseNotification(cause) == true) {
                            setRejectCauseNotification(cause);
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }

            } else {
                loge("onNetworkStateChangeResult length zero");
            }
        }

        return;
    }

    public void setEverIVSR(boolean value)
    {
        log("setEverIVSR:" + value);
        mEverIVSR = value;

        /* ALPS00376525 notify IVSR start event */
        if (value == true) {
            Intent intent = new Intent(TelephonyIntents.ACTION_IVSR_NOTIFY);
            intent.putExtra(TelephonyIntents.INTENT_KEY_IVSR_ACTION, "start");
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());

            if (TelephonyManager.getDefault().getPhoneCount() == 1) {
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            }

            log("broadcast ACTION_IVSR_NOTIFY intent");

            mPhone.getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    /**
     * Return the current located PLMN string (ex: "46000") or null (ex: flight mode or no signal
     * area)
     */
    public String getLocatedPlmn() {
        return mLocatedPlmn;
    }

    private void updateLocatedPlmn(String plmn) {
        log("updateLocatedPlmn(),previous plmn= " + mLocatedPlmn + " ,update to: " + plmn);

        if (((mLocatedPlmn == null) && (plmn != null)) ||
            ((mLocatedPlmn != null) && (plmn == null)) ||
            ((mLocatedPlmn != null) && (plmn != null) && !(mLocatedPlmn.equals(plmn)))) {
            Intent intent = new Intent(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED);
            if (TelephonyManager.getDefault().getPhoneCount() == 1) {
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            }
            intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);

            if (plmn != null) {
                int mcc;
                try {
                    mcc = Integer.parseInt(plmn.substring(0, 3));
                    intent.putExtra(TelephonyIntents.EXTRA_ISO, MccTable.countryCodeForMcc(mcc));
                } catch (NumberFormatException ex) {
                    loge("updateLocatedPlmn: countryCodeForMcc error" + ex);
                    intent.putExtra(TelephonyIntents.EXTRA_ISO, "");
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("updateLocatedPlmn: countryCodeForMcc error" + ex);
                    intent.putExtra(TelephonyIntents.EXTRA_ISO, "");
                }
                if (SystemProperties.get(PROPERTY_AUTO_RAT_SWITCH).equals("0")) {
                    loge("updateLocatedPlmn: framework auto RAT switch disabled");
                } else {
                    mLocatedPlmn = plmn;  //[ALPS02198932]
                    setDeviceRatMode(mPhone.getPhoneId());
                }
            } else {
                intent.putExtra(TelephonyIntents.EXTRA_ISO, "");
            }

            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        mLocatedPlmn = plmn;
    }

    private void onFemtoCellInfoResult(AsyncResult ar) {
        String info[];
        int isCsgCell = 0;

        if (ar.exception != null || ar.result == null) {
           loge("onFemtoCellInfo exception");
        } else {
            info = (String[]) ar.result;

            if (info.length > 0) {

                if (info[0] != null && info[0].length() > 0) {
                    mFemtocellDomain = Integer.parseInt(info[0]);
                    log("onFemtoCellInfo: mFemtocellDomain set to " + mFemtocellDomain);
                }

                if (info[5] != null && info[5].length() > 0) {
                   isCsgCell = Integer.parseInt(info[5]);
                }

                log("onFemtoCellInfo: domain= " + mFemtocellDomain + ",isCsgCell= " + isCsgCell);

                if (isCsgCell == 1) {
                    if (info[6] != null && info[6].length() > 0) {
                        mCsgId = info[6];
                        log("onFemtoCellInfo: mCsgId set to " + mCsgId);
                    }

                    if (info[8] != null && info[8].length() > 0) {
                        mHhbName = new String(IccUtils.hexStringToBytes(info[8]));
                        log("onFemtoCellInfo: mHhbName set from " + info[8] + " to " + mHhbName);
                    } else {
                        mHhbName = null;
                        log("onFemtoCellInfo: mHhbName is not available ,set to null");
                    }
                } else {
                    mCsgId = null;
                    mHhbName = null;
                    log("onFemtoCellInfo: csgId and hnbName are cleared");
                }
                if ((info[1] != null && info[1].length() > 0)  &&
                    (info[9] != null && info[0].length() > 0)) {
                    int state = Integer.parseInt(info[1]);
                    int cause = Integer.parseInt(info[9]);
                    if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                        try {
                            if (mServiceStateExt.needIgnoreFemtocellUpdate(state, cause) == true) {
                                log("needIgnoreFemtocellUpdate due to state= " + state + ",cause= "
                                    + cause);
                                // return here to prevent update variables and broadcast for CSG
                                return;
                            }
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    }
                }
                Intent intent = new Intent(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
                SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());

                if (TelephonyManager.getDefault().getPhoneCount() == 1) {
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                }

                intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, mCurShowSpn);
                intent.putExtra(TelephonyIntents.EXTRA_SPN, mCurSpn);
                intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, mCurShowPlmn);
                intent.putExtra(TelephonyIntents.EXTRA_PLMN, mCurPlmn);
                // Femtocell (CSG) info
                intent.putExtra(TelephonyIntents.EXTRA_HNB_NAME, mHhbName);
                intent.putExtra(TelephonyIntents.EXTRA_CSG_ID, mCsgId);
                intent.putExtra(TelephonyIntents.EXTRA_DOMAIN, mFemtocellDomain);

                mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);

                int phoneId = mPhone.getPhoneId();
                String plmn = mCurPlmn;
                if((mHhbName == null) && (mCsgId != null)){
                    if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                        try {
                            if (mServiceStateExt.needToShowCsgId() == true) {
                                plmn += " - ";
                                plmn += mCsgId;
                            }
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    } else {
                        plmn += " - ";
                        plmn += mCsgId;
                    }
                } else if(mHhbName != null){
                    plmn += " - ";
                    plmn += mHhbName;
                }
                boolean setResult = mSubscriptionController.setPlmnSpn(phoneId,
                        mCurShowPlmn, plmn, mCurShowSpn, mCurSpn);
                if (!setResult) {
                    mSpnUpdatePending = true;
                }
            }
        }
    }

    /* ALPS01139189 START */
    private void broadcastHideNetworkState(String action, int state) {
        if (DBG) log("broadcastHideNetworkUpdate action=" + action + " state=" + state);
        Intent intent = new Intent(TelephonyIntents.ACTION_HIDE_NETWORK_STATE);
        if (TelephonyManager.getDefault().getPhoneCount() == 1) {
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        }
        intent.putExtra(TelephonyIntents.EXTRA_ACTION, action);
        intent.putExtra(TelephonyIntents.EXTRA_REAL_SERVICE_STATE, state);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
        mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }
    /* ALPS01139189 END */

    //ALPS00248788
    private void onInvalidSimInfoReceived(AsyncResult ar) {
        String[] InvalidSimInfo = (String[]) ar.result;
        String plmn = InvalidSimInfo[0];
        int cs_invalid = Integer.parseInt(InvalidSimInfo[1]);
        int ps_invalid = Integer.parseInt(InvalidSimInfo[2]);
        int cause = Integer.parseInt(InvalidSimInfo[3]);
        int testMode = -1;

        // do NOT apply IVSR when in TEST mode
        testMode = SystemProperties.getInt("gsm.gcf.testmode", 0);
        // there is only one test mode in modem. actually it's not SIM dependent , so remove
        // testmode2 property here

        log("onInvalidSimInfoReceived testMode:" + testMode + " cause:" + cause + " cs_invalid:"
                + cs_invalid + " ps_invalid:" + ps_invalid + " plmn:" + plmn
                + " mEverIVSR:" + mEverIVSR);

        //Check UE is set to test mode or not   (CTA =1,FTA =2 , IOT=3 ...)
        if (testMode != 0) {
            log("InvalidSimInfo received during test mode: " + testMode);
            return;
        }
        if (mServiceStateExt.isNeedDisableIVSR()) {
            log("Disable IVSR");
            return;
        }
         //MTK-ADD Start : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS
         //only SIM card or CS domain network registeration temporary failure
         if (cs_invalid == 1) {
             isCsInvalidCard = true;
         }
         //MTK-ADD END : for CS not registered , PS regsitered (ex: LTE PS only mode or 2/3G PS
         //only SIM card or CS domain network registeration temporary failure

        /* check if CS domain ever sucessfully registered to the invalid SIM PLMN */
        /* Integrate ALPS00286197 with MR2 data only device state update , not to apply CS domain
           IVSR for data only device */
        if (mVoiceCapable) {
            if ((cs_invalid == 1) && (mLastRegisteredPLMN != null)
                    && (plmn.equals(mLastRegisteredPLMN))) {
                log("InvalidSimInfo set TRM due to CS invalid");
                setEverIVSR(true);
                mLastRegisteredPLMN = null;
                mLastPSRegisteredPLMN = null;
                mPhone.setTrm(3, null);
                return;
            }
        }

        /* check if PS domain ever sucessfully registered to the invalid SIM PLMN */
        //[ALPS02261450] - start
        if ((ps_invalid == 1) && (isAllowRecoveryOnIvsr(ar)) &&
                (mLastPSRegisteredPLMN != null) && (plmn.equals(mLastPSRegisteredPLMN))){
        //if ((ps_invalid == 1) && (mLastPSRegisteredPLMN != null) &&
        //              (plmn.equals(mLastPSRegisteredPLMN)))
        //[ALPS02261450] - end
            log("InvalidSimInfo set TRM due to PS invalid ");
            setEverIVSR(true);
            mLastRegisteredPLMN = null;
            mLastPSRegisteredPLMN = null;
            mPhone.setTrm(3, null);
            return;
        }

        /* ALPS00324111: to force trigger IVSR */
        /* ALPS00407923  : The following code is to "Force trigger IVSR even
                  when MS never register to the
                  network before"The code was intended to cover the scenario of "invalid
                  SIM NW issue happen
                  at the first network registeration during boot-up".
                  However, it might cause false alarm IVSR ex: certain sim card only register
                  CS domain network , but PS domain is invalid.
                  For such sim card, MS will receive invalid SIM at the first PS domain
                  network registeration In such case , to trigger IVSR will be a false alarm,
                  which will cause  CS domain network
                  registeration time longer (due to IVSR impact)
                  It's a tradeoff. Please think about the false alarm impact
                  before using the code below.*/
        /*
        if ((mEverIVSR == false) && (gprsState != ServiceState.STATE_IN_SERVICE)
                &&(mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE))
        {
            log("InvalidSimInfo set TRM due to never set IVSR");
            setEverIVSR(true);
            mLastRegisteredPLMN = null;
            mLastPSRegisteredPLMN = null;
            phone.setTRM(3, null);
            return;
        }
        */

    }

    private void onModulationInfoReceived(AsyncResult ar) {
        if (ar.exception != null || ar.result == null) {
           loge("onModulationInfoReceived exception");
        } else {
            int info[];
            int modulation;
            info = (int[]) ar.result;
            modulation = info[0];
            log("[onModulationInfoReceived] modulation:" + modulation);

            Intent intent = new Intent(TelephonyIntents.ACTION_NOTIFY_MODULATION_INFO);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(TelephonyIntents.EXTRA_MODULATION_INFO, modulation);

            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    //[ALPS02261450]
    private boolean isAllowRecoveryOnIvsr(AsyncResult ar) {
        if (mPhone.isInCall()){
            log("[isAllowRecoveryOnIvsr] isInCall()=true");
            Message msg;
            msg = obtainMessage();
            msg.what = EVENT_INVALID_SIM_INFO;
            msg.obj = ar;
            sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
            return false;
        } else {
            log("isAllowRecoveryOnIvsr() return true");
            return true;
        }
    }

    /**
     * Post a notification to NotificationManager for network reject cause
     *
     * @param cause
     */
    private void setRejectCauseNotification(int cause) {
        if (DBG) log("setRejectCauseNotification: create notification " + cause);

        Context context = mPhone.getContext();
        mNotification = new Notification();
        mNotification.when = System.currentTimeMillis();
        mNotification.flags = Notification.FLAG_AUTO_CANCEL;
        mNotification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        mNotification.contentIntent = PendingIntent.
            getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        CharSequence details = "";
        CharSequence title = context.getText(com.mediatek.R.string.RejectCauseTitle);
        int notificationId = REJECT_NOTIFICATION;

        switch (cause) {
            case 2:
                details = context.getText(com.mediatek.R.string.MMRejectCause2);;
                break;
            case 3:
                details = context.getText(com.mediatek.R.string.MMRejectCause3);;
                break;
            case 5:
                details = context.getText(com.mediatek.R.string.MMRejectCause5);;
                break;
            case 6:
                details = context.getText(com.mediatek.R.string.MMRejectCause6);;
                break;
            case 13:
                details = context.getText(com.mediatek.R.string.MMRejectCause13);
                break;
            default:
                break;
        }

        if (DBG) log("setRejectCauseNotification: put notification " + title + " / " + details);
        mNotification.tickerText = title;
        mNotification.setLatestEventInfo(context, title, details,
                mNotification.contentIntent);

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(notificationId, mNotification);
    }

    /**
     * Post a notification to NotificationManager for spcial icc card type
     *
     * @param cause
     */
    //[ALPS01558804] MTK-START: send notification for using some spcial icc card
    private void setSpecialCardTypeNotification(String iccCardType, int titleType, int detailType) {
        if (DBG) log("setSpecialCardTypeNotification: create notification for " + iccCardType);

        //status notification
        Context context = mPhone.getContext();
        int notificationId = SPECIAL_CARD_TYPE_NOTIFICATION;

        mNotification = new Notification();
        mNotification.when = System.currentTimeMillis();
        mNotification.flags = Notification.FLAG_AUTO_CANCEL;
        mNotification.icon = com.android.internal.R.drawable.stat_sys_warning;

        Intent intent = new Intent();
        mNotification.contentIntent = PendingIntent
            .getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        CharSequence title = "";
        switch (titleType) {
            case 0:
                title = context.getText(
                        com.mediatek.R.string.Special_Card_Type_Title_Lte_Not_Available);
                break;
            default:
                break;
        }

        CharSequence details = "";
        switch (detailType) {
            case 0:
                details = context.getText(com.mediatek.R.string.Suggest_To_Change_USIM);
                break;
            default:
                break;
        }

        if (DBG) log("setSpecialCardTypeNotification: put notification " + title + " / " + details);
        mNotification.tickerText = title;
        mNotification.setLatestEventInfo(context, title, details,
                mNotification.contentIntent);

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(notificationId, mNotification);
    }
    //[ALPS01558804] MTK-END: send notification for using some spcial icc card


    //MTK-START [ALPS00540036]
    private int getDstForMcc(int mcc, long when) {
        int dst = 0;

        if (mcc != 0) {
            String tzId = MccTable.defaultTimeZoneForMcc(mcc);
            if (tzId != null) {
                TimeZone timeZone = TimeZone.getTimeZone(tzId);
                Date date = new Date(when);
                boolean isInDaylightTime = timeZone.inDaylightTime(date);
                if (isInDaylightTime) {
                    dst = 1;
                    log("[NITZ] getDstForMcc: dst=" + dst);
                }
            }
        }

        return dst;
    }

    private int getMobileCountryCode() {
        int mcc = 0;

        String operatorNumeric = mSS.getOperatorNumeric();
        if (operatorNumeric != null) {
            try {
                mcc = Integer.parseInt(operatorNumeric.substring(0, 3));
            } catch (NumberFormatException ex) {
                loge("countryCodeForMcc error" + ex);
            } catch (StringIndexOutOfBoundsException ex) {
                loge("countryCodeForMcc error" + ex);
            }
        }

        return mcc;
    }
    //MTK-END [ALPS00540036]

    //MTK-START: update TimeZone by MCC/MNC
    //Find TimeZone in manufacturer maintained table for the country has multiple timezone
    private TimeZone getTimeZonesWithCapitalCity(String iso) {
        TimeZone tz = null;

        //[ALPS01666276]-Start: don't udpate with capital city when we has received nitz before
        if ((mZoneOffset == 0) && (mZoneDst == false)) {
            for (int i = 0; i < mTimeZoneIdOfCapitalCity.length; i++) {
                if (iso.equals(mTimeZoneIdOfCapitalCity[i][0])) {
                    tz = TimeZone.getTimeZone(mTimeZoneIdOfCapitalCity[i][1]);
                    log("uses TimeZone of Capital City:" + mTimeZoneIdOfCapitalCity[i][1]);
                    break;
                }
            }
        } else {
            log("don't udpate with capital city, cause we have received nitz");
        }
        //[ALPS01666276]-End
        return tz;
    }

    // For the case that MccTable.defaultTimeZoneForMcc() returns unexpected timezone
    private String getTimeZonesByMcc(String mcc) {
        String tz = null;

        for (int i = 0; i < mTimeZoneIdByMcc.length; i++) {
            if (mcc.equals(mTimeZoneIdByMcc[i][0])) {
                tz = mTimeZoneIdByMcc[i][1];
                log("uses Timezone of GsmSST by mcc: " + mTimeZoneIdByMcc[i][1]);
                break;
            }
        }
        return tz;
    }

    //MTK-Add-start : [ALPS01267367] fix timezone by MCC
    protected void fixTimeZone() {
        TimeZone zone = null;
        String iso = "";
        String operatorNumeric = mSS.getOperatorNumeric();
        String mcc = null;

        //[ALPS01416062] MTK ADD-START
        if (operatorNumeric != null && !operatorNumeric.equals("") && isNumeric(operatorNumeric)) {
        //if (operatorNumeric != null) {
        //[ALPS01416062] MTK ADD-END
            mcc = operatorNumeric.substring(0, 3);
        } else {
            log("fixTimeZone but not registered and operatorNumeric is null or invalid value");
            return;
        }

        try {
            iso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
        } catch (NumberFormatException ex) {
            loge("fixTimeZone countryCodeForMcc error" + ex);
        }

        if (!mcc.equals("000") && !TextUtils.isEmpty(iso) && getAutoTimeZone()) {

            // Test both paths if ignore nitz is true
            boolean testOneUniqueOffsetPath = SystemProperties.getBoolean(
                        TelephonyProperties.PROPERTY_IGNORE_NITZ, false) &&
                            ((SystemClock.uptimeMillis() & 1) == 0);

            ArrayList<TimeZone> uniqueZones = TimeUtils.getTimeZonesWithUniqueOffsets(iso);
            if ((uniqueZones.size() == 1) || testOneUniqueOffsetPath) {
                zone = uniqueZones.get(0);
                if (DBG) {
                   log("fixTimeZone: no nitz but one TZ for iso-cc=" + iso +
                           " with zone.getID=" + zone.getID() +
                           " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath);
                }
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            //MTK-START: [ALPS01262709] update time with MCC/MNC
            //} else {
            } else if (uniqueZones.size() > 1) {
                log("uniqueZones.size=" + uniqueZones.size());
                zone = getTimeZonesWithCapitalCity(iso);
                //[ALPS01666276]-Start: don't udpate with capital city when we has received nitz
                //before
                if (zone != null) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                //[ALPS01666276]-End
            //MTK-END: [ALPS01262709] update time with MCC/MNC
            } else {
                if (DBG) {
                    log("fixTimeZone: there are " + uniqueZones.size() +
                        " unique offsets for iso-cc='" + iso +
                        " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath +
                        "', do nothing");
                }
            }
        }

        if (zone != null) {
            log("fixTimeZone: zone != null zone.getID=" + zone.getID());
            if (getAutoTimeZone()) {
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            }
            saveNitzTimeZone(zone.getID());
        } else {
            log("fixTimeZone: zone == null");
        }
    }
    //[ALPS01416062] MTK ADD-START
    public boolean isNumeric(String str) {
        //[ALPS01565135] MTK ADD -START for avoide JE on Pattern.Matcher
        //Pattern pattern = Pattern.compile("[0-9]*");
        //Matcher isNum = pattern.matcher(str);
        //if(!isNum.matches()) {
        //    return false;
        //}
        //return true;

        try {
            int testNum = Integer.parseInt(str);
        } catch (NumberFormatException eNFE) {
            log("isNumeric:" + eNFE.toString());
            return false;
        } catch (Exception e) {
            log("isNumeric:" + e.toString());
            return false;
        }
        return true;
        //[ALPS01565135] MTK ADD -END
    }
    //[ALPS01416062] MTK ADD-END

    //MTK-END:  [ALPS01262709]  update TimeZone by MCC/MNC

    @Override
    protected void updateCellInfoRate() {
        log("updateCellInfoRate(),mCellInfoRate= " + mCellInfoRate);
        if ((mCellInfoRate != Integer.MAX_VALUE) && (mCellInfoRate != 0)) {
            if (mCellInfoTimer != null) {
                log("cancel previous timer if any");
                mCellInfoTimer.cancel();
                mCellInfoTimer = null;
            }

            mCellInfoTimer = new Timer(true);

            log("schedule timer with period = " + mCellInfoRate + " ms");
            mCellInfoTimer.schedule(new timerTask(), mCellInfoRate);
        } else if ((mCellInfoRate == 0) || (mCellInfoRate == Integer.MAX_VALUE)) {
            if (mCellInfoTimer != null) {
                log("cancel cell info timer if any");
                mCellInfoTimer.cancel();
                mCellInfoTimer = null;
            }
        }
    }

    public class timerTask extends TimerTask {
        public void run() {
            log("CellInfo Timeout invoke getAllCellInfoByRate()");
            if ((mCellInfoRate != Integer.MAX_VALUE) && (mCellInfoRate != 0)
                    && (mCellInfoTimer != null)) {
                log("timerTask schedule timer with period = " + mCellInfoRate + " ms");
                mCellInfoTimer.schedule(new timerTask(), mCellInfoRate);
            }

            new Thread(new Runnable() {
                public void run() {
                    log("timerTask invoke getAllCellInfoByRate() in another thread");
                    getAllCellInfoByRate();
                }
            }).start();

        }
    };

    //MTK-START [ALPS01830723]

    private void onPsNetworkStateChangeResult(AsyncResult ar) {
        int info[];
        int newUrcState;

        if (ar.exception != null || ar.result == null) {
           loge("onPsNetworkStateChangeResult exception");
        } else {
            info = (int[]) ar.result;
            newUrcState = regCodeToServiceState(info[0]);
            log("mPsRegState:" + mPsRegState + ",new:" + newUrcState + ",result:" + info[0]);
            //get the raw state value for roaming
            mPsRegStateRaw = info[0];

            if (mPsRegState == ServiceState.STATE_IN_SERVICE
                       && newUrcState != ServiceState.STATE_IN_SERVICE) {
                log("set flag for ever detach, may notify attach later");
                bHasDetachedDuringPolling = true;
            }
        }
    }

    private void handlePsRegNotification(int oldState, int newState) {

        boolean hasGprsAttached = false;
        boolean hasGprsDetached = false;
        boolean specificNotify = false;

        log("old:" + oldState + " ,mPsRegState:" + mPsRegState + ",new:" + newState);

        // Compare oldState and mPsRegState
        hasGprsAttached =
                oldState != ServiceState.STATE_IN_SERVICE
                && mPsRegState == ServiceState.STATE_IN_SERVICE;

        hasGprsDetached =
                oldState == ServiceState.STATE_IN_SERVICE
                && mPsRegState != ServiceState.STATE_IN_SERVICE;

        if (hasGprsAttached) {
            mAttachedRegistrants.notifyRegistrants();
            mLastPSRegisteredPLMN = mSS.getOperatorNumeric() ;
            log("mLastPSRegisteredPLMN= " + mLastPSRegisteredPLMN);
            bHasDetachedDuringPolling = false;
        }

        if (hasGprsDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        // Compare mPsRegState and newState
        hasGprsAttached =
                mPsRegState != ServiceState.STATE_IN_SERVICE
                && newState == ServiceState.STATE_IN_SERVICE;

        hasGprsDetached =
                mPsRegState == ServiceState.STATE_IN_SERVICE
                && newState != ServiceState.STATE_IN_SERVICE;


        if (!hasGprsAttached &&
            bHasDetachedDuringPolling && newState == ServiceState.STATE_IN_SERVICE) {
            // M: It means:   attached -> (detached) -> attached, need to compensate for notifying
            // this modification is for "network losing enhancement"
            specificNotify = true;
            log("need to compensate for notifying");
        }

        if (hasGprsAttached || specificNotify) {
            mAttachedRegistrants.notifyRegistrants();
            mLastPSRegisteredPLMN = mSS.getOperatorNumeric() ;
            log("mLastPSRegisteredPLMN= " + mLastPSRegisteredPLMN);
        }

        if (hasGprsDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        mPsRegState = newState;
        bHasDetachedDuringPolling = false; // reset flag
    }
    //MTK-END [ALPS01830723]

    //MTK-START [ALPS00368272]
    private void getEINFO(int eventId) {
        mPhone.invokeOemRilRequestStrings(new String[]{"AT+EINFO?", "+EINFO"},
                this.obtainMessage(eventId));
        log("getEINFO for EMMRRS");
    }

    private void setEINFO(int value, Message onComplete) {
        String Cmd[] = new String[2];
        Cmd[0] = "AT+EINFO=" + value;
        Cmd[1] = "+EINFO";
        mPhone.invokeOemRilRequestStrings(Cmd, onComplete);
        log("setEINFO for EMMRRS, ATCmd[0]=" + Cmd[0]);
    }

    private boolean isCurrentPhoneDataConnectionOn() {
        int defaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        boolean userDataEnabled = true;

        try {
            userDataEnabled = TelephonyManager.getIntWithSubId(
                    mPhone.getContext().getContentResolver(),
                    Settings.Global.MOBILE_DATA, defaultDataSubId) == 1;
        } catch (SettingNotFoundException snfe) {
            if (DBG) log("isCurrentPhoneDataConnectionOn: SettingNofFoundException snfe=" + snfe);
        }
        log("userDataEnabled=" + userDataEnabled + ", defaultDataSubId=" + defaultDataSubId);
        if (userDataEnabled && (defaultDataSubId
                == SubscriptionManager.getSubIdUsingPhoneId(mPhone.getPhoneId()))) {
            return true;
        }
        return false;
    }
    //MTK-END[ALPS00368272]

    //[ALPS01804936]-start:fix JE when change system language to "Burmese"
    protected int updateOperatorAlpha(String operatorAlphaLong) {
        int myPhoneId = mPhone.getPhoneId();
        if (myPhoneId == PhoneConstants.SIM_ID_1) {
            SystemProperties.set(TelephonyProperties.PROPERTY_OPERATOR_ALPHA, operatorAlphaLong);
        } else if (myPhoneId == PhoneConstants.SIM_ID_2) {
            SystemProperties.set(TelephonyProperties.PROPERTY_OPERATOR_ALPHA_2, operatorAlphaLong);
        } else if (myPhoneId == PhoneConstants.SIM_ID_3) {
            SystemProperties.set(TelephonyProperties.PROPERTY_OPERATOR_ALPHA_3, operatorAlphaLong);
        } else if (myPhoneId == PhoneConstants.SIM_ID_4) {
            SystemProperties.set(TelephonyProperties.PROPERTY_OPERATOR_ALPHA_4, operatorAlphaLong);
        }
        return 1;
    }
    //[ALPS01804936]-end

    //[ALPS01810775,ALPS01868743] -Start: update network type at screen off
    private void updateNetworkInfo(int newRegState, int newNetworkType) {
        int displayState = mCi.getDisplayState();

        boolean isRegisted = false;
        if ((newRegState == ServiceState.REGISTRATION_STATE_HOME_NETWORK) ||
                (newRegState == ServiceState.REGISTRATION_STATE_ROAMING)) {
            isRegisted = true;
        } else {
            isRegisted = false;
        }

        //Case1: update network type with new type.
        //
        //       situation 1): The format of CREG is long format when screen is on.
        //       situation 2): mIsForceSendScreenOnForUpdateNwInfo is ture
        //                         means we forec changed format to long at last time.
        //       situation 3): not camp on network when screen is off
        //
        //Case2: change format to update cid , lac and network type
        //       when camp on network after screen off.
        //
        //Case3: update network type with old type.
        //       screen is off and registered before screen off

        if ((displayState != Display.STATE_OFF) ||
                mIsForceSendScreenOnForUpdateNwInfo ||
                ((!isRegisted) && (displayState == Display.STATE_OFF))) {
            mNewSS.setRilVoiceRadioTechnology(newNetworkType);
        } else if ((mSS.getVoiceRegState()
                        == ServiceState.STATE_OUT_OF_SERVICE) &&
                        (isRegisted) && (displayState == Display.STATE_OFF)) {
            if (!mIsForceSendScreenOnForUpdateNwInfo) {
                log("send screen state ON to change format of CREG");
                mIsForceSendScreenOnForUpdateNwInfo = true;
                mCi.sendScreenState(true);
                pollState();
            }
        } else if ((displayState == Display.STATE_OFF) && isRegisted) {
            mNewSS.setRilVoiceRadioTechnology(mSS.getRilVoiceRadioTechnology());
            log("set Voice network type=" + mNewSS.getRilVoiceRadioTechnology() +
                    " update network type with old type.");
        }
    }
    //[ALPS01810775,ALPS01868743] -End

    public boolean isSameRadioTechnologyMode(int nRadioTechnology1, int nRadioTechnology2) {
        if ((nRadioTechnology1 == ServiceState.RIL_RADIO_TECHNOLOGY_LTE &&
                nRadioTechnology2 == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) ||
                (nRadioTechnology1 == ServiceState.RIL_RADIO_TECHNOLOGY_GSM &&
                nRadioTechnology2 == ServiceState.RIL_RADIO_TECHNOLOGY_GSM)) {
            return true;
        } else if (((nRadioTechnology1 >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS &&
                        nRadioTechnology1 <= ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) ||
                        nRadioTechnology1 == ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP) &&
                        ((nRadioTechnology2 >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS &&
                        nRadioTechnology2 <= ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) ||
                        nRadioTechnology2 == ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isHPlmn(String plmn) {
        //follow the behavior of modem, according to the length of plmn to compare mcc/mnc
        //ex: mccmnc: 334030 but plmn:33403 => still be HPLMN
        String mccmnc = getSIMOperatorNumeric();
        if (plmn == null) return false;

        if (mccmnc == null || mccmnc.equals("")) {
            log("isHPlmn getSIMOperatorNumeric error: " + mccmnc);
            return false;
        }

        if (plmn.equals(mccmnc)) {
            return true;
        } else {
            if (plmn.length() == 5 && mccmnc.length() == 6
                && plmn.equals(mccmnc.substring(0, 5))) {
                return true;
            }
        }

        /* ALPS01473952 check if plmn in customized EHPLMN table */
        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            boolean isServingPlmnInGroup = false;
            boolean isHomePlmnInGroup = false;

            if ((plmn != null) && mccmnc == null) {
                for (int i = 0; i < customEhplmn.length; i++) {
                    //reset flag
                    isServingPlmnInGroup = false;
                    isHomePlmnInGroup = false;

                    //check if target plmn or home plmn in this group
                    for (int j = 0; j < customEhplmn[i].length; j++) {
                        if (plmn.equals(customEhplmn[i][j])) {
                            isServingPlmnInGroup = true;
                        }
                        if (mccmnc.equals(customEhplmn[i][j])) {
                            isHomePlmnInGroup = true;
                        }
                    }

                    //if target plmn and home plmn both in the same group
                    if ((isServingPlmnInGroup == true) &&
                            (isHomePlmnInGroup == true)) {
                        log("plmn:" + plmn + "is in customized ehplmn table");
                        return true;
                    }
                }
            }
        }
        /* ALPS01473952 END */

        return false;
    }

    private void setReceivedNitz(int phoneId, boolean receivedNitz) {
        log("setReceivedNitz : phoneId = " + phoneId);
        sReceiveNitz[phoneId] = receivedNitz;
    }

    private boolean getReceivedNitz() {
        return sReceiveNitz[mPhone.getPhoneId()];
    }

    /// M: for power on optimization @{
    // Reuse modem suspend and resum machniasm, means to let modem searching network fristly,
    // then when ap phone process ready, ap sent EMSR to resume modem to start register network.
    // then ap send EMSR=0,0 disable this machniasm.
    // if not handle like this, it will happen a issue, modem already register network,
    // but ap have not ready, so when modem start recevie sms or mms, rild_sms will happen
    // state confusion, so it will cause the phone cannot recevie sms and mms.
    // this api need sync with ril_oem.c bootupSetRadio()
    private boolean needResumeModem() {
        if (isOp01Support()) {
            return (!WorldPhoneUtil.isWorldPhoneSupport() || WorldPhoneUtil.isWorldModeSupport());
        }
        return false;
    }

    private boolean isOp01Support() {
        String optr = SystemProperties.get("ro.operator.optr");
        if (optr != null && optr.equals("OP01")) {
            return true;
        }
        return false;
    }
    /// @}

    private void onNetworkEventReceived(AsyncResult ar) {
        if (ar.exception != null || ar.result == null) {
           loge("onNetworkEventReceived exception");
        } else {
            // result[0]: <Act> not used
            // result[1]: <event_type> 0: for RAU event , 1: for TAU event
            int nwEventType = ((int[]) ar.result)[1];
            log("[onNetworkEventReceived] event_type:" + nwEventType);

            Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_EVENT);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(TelephonyIntents.EXTRA_EVENT_TYPE, nwEventType + 1);

            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }
}
