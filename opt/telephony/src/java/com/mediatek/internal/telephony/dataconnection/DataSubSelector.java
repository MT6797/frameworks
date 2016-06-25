package com.mediatek.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;
import com.mediatek.internal.telephony.worldphone.WorldMode;
import com.mediatek.internal.telephony.worldphone.WorldPhoneUtil;

import java.util.ArrayList;
import java.util.Arrays;

public class DataSubSelector {
    private static final boolean DBG = true;

    private int mPhoneNum;
    private boolean mIsNeedWaitImsi = false;
    //private boolean mIsNeedWaitUnlock = false;
    private static final String PROPERTY_DEFAULT_DATA_ICCID = "persist.radio.data.iccid";
    private static final String PROPERTY_DEFAULT_SIMSWITCH_ICCID = "persist.radio.simswitch.iccid";
    private String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };
    private static final String NO_SIM_VALUE = "N/A";

    private static final boolean BSP_PACKAGE =
            SystemProperties.getBoolean("ro.mtk_bsp_package", false);
    //Add by MTK,@{
    private static final String MTK_C2K_SUPPORT = "ro.mtk_c2k_support";
    private static boolean m6mProject;
    //}@
    private static String mOperatorSpec;
    private static final String OPERATOR_OM = "OM";
    private static final String OPERATOR_OP01 = "OP01";
    private static final String OPERATOR_OP02 = "OP02";
    private static final String OPERATOR_OP09 = "OP09";
    private static final String OPERATOR_OP18 = "OP18";
    private static final String SEGDEFAULT = "SEGDEFAULT";
    private static final String SEGC = "SEGC";

    private static final String PROPERTY_3G_SIM = "persist.radio.simswitch";
    private static final String PROPERTY_CAPABILITY_SWITCH_POLICY = "ro.mtk_sim_switch_policy";
    public static final String ACTION_MOBILE_DATA_ENABLE
            = "android.intent.action.ACTION_MOBILE_DATA_ENABLE";
    public static final String EXTRA_MOBILE_DATA_ENABLE_REASON = "reason";

    public static final String REASON_MOBILE_DATA_ENABLE_USER = "user";
    public static final String REASON_MOBILE_DATA_ENABLE_SYSTEM = "system";

    private static final String PROPERTY_MOBILE_DATA_ENABLE = "persist.radio.mobile.data";
    private String[] PROPERTY_ICCID = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };

    private final static String OLD_ICCID = "old_iccid";
    private final static String FIRST_TIME_ROAMING = "first_time_roaming";
    private final static String NEED_TO_EXECUTE_ROAMING = "need_to_execute_roaming";
    private final static String NEED_TO_WAIT_UNLOCKED = "persist.radio.unlock";
    private final static String NEED_TO_WAIT_UNLOCKED_ROAMING = "persist.radio.unlock.roaming";
    private final static String SIM_STATUS = "persist.radio.sim.status";
    private final static String NEW_SIM_SLOT = "persist.radio.new.sim.slot";
    private final static int HOME_POLICY = 0;
    private final static int ROAMING_POLICY = 1;
    private boolean mIsNeedWaitImsiRoaming = false;
    //private boolean mIsNeedWaitUnlockRoaming = false;
    private Context mContext = null;
    private Intent mIntent = null;
    private boolean mIsNeedWaitAirplaneModeOff = false;
    private boolean mIsNeedWaitAirplaneModeOffRoaming = false;
    private boolean mAirplaneModeOn = false;
    private boolean mHasRegisterWorldModeReceiver = false;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private int mPrevDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mLastValidDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mIsUserConfirmDefaultData = false;
    // Add for multi policies for single operator
    private final static int POLICY_NO_AUTO = 0;
    private final static int POLICY_DEFAULT = 1;
    private final static int POLICY_POLICY1 = 2;
    private final static int capability_switch_policy =
            SystemProperties.getInt(PROPERTY_CAPABILITY_SWITCH_POLICY, POLICY_DEFAULT);

    protected BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            log("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                log("slotId: " + slotId + " simStatus: " + simStatus + " mIsNeedWaitImsi: "
                        + mIsNeedWaitImsi + " mIsNeedWaitUnlock: " +
                        isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED));
                if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_IMSI)) {
                    RadioCapabilitySwitchUtil.updateSimImsiStatus(slotId,
                            RadioCapabilitySwitchUtil.IMSI_READY);
                    RadioCapabilitySwitchUtil.clearRilMccMnc(slotId);
                    if (OPERATOR_OP01.equals(mOperatorSpec)) {
                        log("get imsi so check op01 again");
                        mIntent = intent; // this will be used in
                                          //   checkOp01CapSwitch6m->
                                          //      handleDefaultDataOp01MultiSims() ->
                                          //        turnOffNewSimData(intent)
                        subSelectorForOp01(mIntent);
                        if (mIsNeedWaitImsi == true) {
                            mIsNeedWaitImsi = false;
                        }
                        if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)) {
                            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
                        }
                    } else if (mIsNeedWaitImsi == true || mIsNeedWaitImsiRoaming == true) {
                        if (mIsNeedWaitImsi == true) {
                            mIsNeedWaitImsi = false;
                            if (OPERATOR_OP02.equals(mOperatorSpec)) {
                                log("get imsi and need to check op02 again");
                                if (checkOp02CapSwitch(HOME_POLICY) == false) {
                                    mIsNeedWaitImsi = true;
                                }
                            } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                                 log("get imsi and need to check op18 again");
                                 if (checkOp18CapSwitch() == false) {
                                     mIsNeedWaitImsi = true;
                                 }
                             }
                        }
                        if (mIsNeedWaitImsiRoaming == true) {
                            mIsNeedWaitImsiRoaming = false;
                            log("get imsi and need to check op02Roaming again");
                            if (checkOp02CapSwitch(ROAMING_POLICY) == false) {
                                mIsNeedWaitImsiRoaming = true;
                            }
                        }
                    } else if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)||
                                isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING)) {
                        log("get imsi because unlock");

                        ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                            ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
                        if (iTelEx != null) {
                            try {
                                if (iTelEx.isCapabilitySwitching()) {
                                    // wait complete intent
                                } else {
                                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)) {
                                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
                                        if (OPERATOR_OP02.equals(mOperatorSpec)) {
                                            if (SystemProperties.getBoolean(
                                                    "ro.mtk_disable_cap_switch", false) == true) {
                                                subSelectorForOp02(mIntent);
                                            } else {
                                                subSelectorForOp02();
                                            }
                                        } else if (OPERATOR_OM.equals(mOperatorSpec)) {
                                            subSelectorForOm(mIntent);
                                        } else if (isOP09Support()) {
                                            subSelectorForOp09(mIntent);
                                        } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                                            subSelectorForOp18(mIntent);
                                        }
                                    }
                                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING)) {
                                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "false");
                                        checkOp02CapSwitch(ROAMING_POLICY);
                                    }
                                }
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)) {
                    RadioCapabilitySwitchUtil.updateSimImsiStatus(slotId,
                            RadioCapabilitySwitchUtil.IMSI_NOT_READY);
                    RadioCapabilitySwitchUtil.clearRilMccMnc(slotId);
                } else if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                    RadioCapabilitySwitchUtil.updateSimImsiStatus(slotId,
                            RadioCapabilitySwitchUtil.IMSI_NOT_READY);
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE)
                    || action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED)) {
                log("mIsNeedWaitUnlock = " + isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED) +
                        ", mIsNeedWaitUnlockRoaming = " + isNeedWaitUnlock(
                        NEED_TO_WAIT_UNLOCKED_ROAMING));
                if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)||
                        isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING)) {
                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)) {
                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
                        if (OPERATOR_OP01.equals(mOperatorSpec)) {
                            subSelectorForOp01(mIntent);
                        } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch",
                                    false) == true) {
                                subSelectorForOp02(mIntent);
                            } else {
                                subSelectorForOp02();
                            }
                        } else if (OPERATOR_OM.equals(mOperatorSpec)) {
                            subSelectorForOm(mIntent);
                        } else if (isOP09Support()) {
                            subSelectorForOp09(mIntent);
                        } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                            subSelectorForOp18(mIntent);
                        }
                    }
                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING)) {
                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "false");
                        checkOp02CapSwitch(ROAMING_POLICY);
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED)) {
                log("ACTION_LOCATED_PLMN_CHANGED");
                if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false) == false) {
                    if (OPERATOR_OP02.equals(mOperatorSpec)) {
                        String plmn = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
                        if ((plmn != null) && !("".equals(plmn))) {
                            log("plmn = " + plmn);
                            SharedPreferences preference = context.getSharedPreferences(
                                    FIRST_TIME_ROAMING, Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = preference.edit();
                            boolean firstTimeRoaming = preference.getBoolean(
                                    NEED_TO_EXECUTE_ROAMING, true);
                            if (!(plmn.startsWith(RadioCapabilitySwitchUtil.CN_MCC))) {
                                if (firstTimeRoaming == true) {
                                    if (mIsNeedWaitImsi == false) {
                                        checkOp02CapSwitch(ROAMING_POLICY);
                                    } else {
                                        // If onSubInfoReady doesn't get IMSI, we assume
                                        // checkOp02CapSwitch get the same result
                                        mIsNeedWaitImsiRoaming = true;
                                    }
                                }
                            } else {
                                // Plmn is in home location.
                                if (firstTimeRoaming == false) {
                                    // Reset first_time_roaming flag
                                    log("reset roaming flag");
                                    editor.clear();
                                    editor.commit();
                                }
                            }
                        }
                    }
                }
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mAirplaneModeOn = intent.getBooleanExtra("state", false) ? true : false;
                log("ACTION_AIRPLANE_MODE_CHANGED, enabled = " + mAirplaneModeOn);
                if (!mAirplaneModeOn) {
                    if (mIsNeedWaitAirplaneModeOff) {
                        mIsNeedWaitAirplaneModeOff = false;
                        if (OPERATOR_OP01.equals(mOperatorSpec)) {
                            subSelectorForOp01(mIntent);
                        } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false)
                                    == true) {
                                subSelectorForOp02(mIntent);
                            } else {
                                subSelectorForOp02();
                            }
                        } else if (OPERATOR_OM.equals(mOperatorSpec)) {
                            subSelectorForOm(mIntent);
                        } else if (isOP09Support()) {
                            subSelectorForOp09(mIntent);
                        }
                    }
                    if (mIsNeedWaitAirplaneModeOffRoaming) {
                        mIsNeedWaitAirplaneModeOffRoaming = false;
                        checkOp02CapSwitch(ROAMING_POLICY);
                    }
                }
            } else if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                int nDefaultDataSubId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                log("mIsUserConfirmDefaultData/nDefaultDataSubId:" + mIsUserConfirmDefaultData
                        + "/" + nDefaultDataSubId);
                if (mIsUserConfirmDefaultData
                        && SubscriptionManager.isValidSubscriptionId(nDefaultDataSubId)) {
                    handleDataEnableForOp02(2);
                    mIsUserConfirmDefaultData = false;
                }

                setLastValidDefaultDataSub(nDefaultDataSubId);
            } else if (TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED.equals(action)) {
                //For C2K OM 6M,to trigger capability
                //For OP09C,if sub detectedType is no change,the function onSubInfoReady
                //cannot be called, so need to receive this intent to handle this case
                //other wise the capability switch will not be called.
                if (SystemProperties.get(MTK_C2K_SUPPORT).equals("1")){
                    int detectedType = intent.getIntExtra(
                            SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                            SubscriptionManager.EXTRA_VALUE_NOCHANGE);
                    log("DataSubSelector detectedType:" + detectedType);
                    if (mOperatorSpec.equals(OPERATOR_OP01)) {
                        if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                            subSelectorForOp01(intent);
                        }
                    }
                    if (mOperatorSpec.equals(OPERATOR_OM) || isOP09CSupport()){
                        if (isCanSwitch()){
                            if (isOP09CSupport()){
                                if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                                    subSelectorForC2k6m(mIntent);
                                }
                            } else {
                                subSelectorForC2k6m(mIntent);
                            }
                        }
                    }
                }
            }
        }
    };

    private BroadcastReceiver mWorldModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int wmState = WorldMode.MD_WM_CHANGED_UNKNOWN;
            log("mWorldModeReceiver: action = " + action);
            if (TelephonyIntents.ACTION_WORLD_MODE_CHANGED.equals(action)) {
                wmState = intent.getIntExtra(TelephonyIntents.EXTRA_WORLD_MODE_CHANGE_STATE,
                        WorldMode.MD_WM_CHANGED_UNKNOWN);
                log("wmState: " + wmState);
                if (wmState == WorldMode.MD_WM_CHANGED_END) {
                    setCapability(mPhoneId);
                }
            }
        }
    };

    public DataSubSelector(Context context, int phoneNum) {
        log("DataSubSelector is created");
        mPhoneNum = phoneNum;
        mOperatorSpec = SystemProperties.get("ro.operator.optr", OPERATOR_OM);
        m6mProject = SystemProperties.get("ro.mtk_c2k_support").equals("1");
        log("Operator Spec:" + mOperatorSpec + ", c2k_support:" + m6mProject);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED);
        filter.addAction(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        //For sim switch add,to handle C2k card change case ,@{
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        //}@
        context.registerReceiver(mBroadcastReceiver, filter);
        mContext = context;
        mAirplaneModeOn = (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1) ? true : false;

        // Op02 hotplug
        int defaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        log("defaultDataSub:" + defaultDataSubId);
        setLastValidDefaultDataSub(defaultDataSubId);

    }

    /**
     * Receive ACTION_SUBINFO_RECORD_UPDATED.
     * @param intent the intent conveys info.
     */
    public void onSubInfoReady(Intent intent) {
        mIsNeedWaitImsi = false;

        if (BSP_PACKAGE) {
            log("Don't support BSP Package.");
            return;
        }

        if (mOperatorSpec.equals(OPERATOR_OP01)) {
            subSelectorForOp01(intent);
        } else if (mOperatorSpec.equals(OPERATOR_OP02)) {
            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false) == true) {
                subSelectorForOp02(intent);
            } else {
                subSelectorForOp02();
            }
        } else if (isOP09Support()) {
            subSelectorForOp09(intent);
        } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
            // for skip auto switch after user set manually.
            int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                SubscriptionManager.EXTRA_VALUE_NOCHANGE);
            log("detectedType:" + detectedType);
            if (detectedType != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                subSelectorForOp18(intent);
            } else {
                log("skip auto switch when detectedType is NOCHANGE for OP18 when user may set");
            }
        }else if("yes".equals(SystemProperties.get("persist.radio.data.first", "yes")) && "yes".equals(SystemProperties.get("ro.mobile.data.enable", "no")))
	{
		subSelectorForOmExt(intent);
	} else {
            subSelectorForOm(intent);
        }
    }

    private void subSelectorForSolution15(Intent intent) {
        log("DataSubSelector for C2K om solution 1.5: capability maybe diff with default data");
        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];
        turnOffNewSimData(intent);
        //Get previous sim switch data
        String capabilityIccid = SystemProperties.get(PROPERTY_DEFAULT_SIMSWITCH_ICCID);
        log("capability Iccid = " + capabilityIccid);
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i]) || "N/A".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (capabilityIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }

        }
        log("capability  phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }
    }

    private void subSelectorForOm(Intent intent) {
        if (SystemProperties.get(MTK_C2K_SUPPORT).equals("1")){
            //For C2K project,do not trigger switch only for data,
            //Capability switch will trigger by sub record update
            turnOffNewSimData(intent);
            updateDataEnableProperty();
            return;
        }
        log("DataSubSelector for OM: only for capability switch; for default data, use google");

        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];

        turnOffNewSimData(intent);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (defaultIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }

            if (NO_SIM_VALUE.equals(currIccId[i])) {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OM: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            mIntent = intent;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        } else {
            log("DataSubSelector for OM: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OM: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            mIntent = intent;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Default data phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }

        updateDataEnableProperty();
        // clean system property
        resetSimStatus();
        resetNewSimSlot();
    }

    private void subSelectorForC2k6m(Intent intent) {
        log("DataSubSelector for C2K6M: only for capability switch;");
        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];
        //Get previous default data
        //Modify the way for get defaultIccid,
        //because the SystemProperties may not update on time
        String defaultIccid = "";
        int defDataSubId = SubscriptionController.getInstance().getDefaultDataSubId();
        int defDataPhoneId = SubscriptionManager.getPhoneId(defDataSubId);
        if (defDataPhoneId >= 0) {
            if (defDataPhoneId >= PROPERTY_ICCID_SIM.length) {
               log("phoneId out of boundary :" + defDataPhoneId);
            } else {
               log("default data phoneId from sub = " + defDataPhoneId);
               defaultIccid = SystemProperties.get(PROPERTY_ICCID_SIM[defDataPhoneId]);
            }
        }
        log("Default data Iccid = " + defaultIccid);
        //defaultIccid is empty or slot is empty,return
        if (("".equals(defaultIccid)) || ("N/A".equals(defaultIccid))){
            return;
        }
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (defaultIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }
        }
        log("Default data phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }
    }
    private void subSelectorForOmExt(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for subSelectorForOmExt");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("subSelectorForOmExt Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("subSelectorForOmExt Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            log("==subSelectorForOmExt=C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount >= 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
             }
	      log("subSelectorForOmExt  phoneId = " + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
		    setDataEnabled(phoneId,true);
		log("subSelectorForOmExt setDataEnabled phoneId = " + phoneId);
                }

		SystemProperties.set("persist.radio.data.first", "no");                    
        } 
        
    }

    /*private void subSelectorForOm(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OM");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID + (i + 1));
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnable(false);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnable(false);
                    }
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: OFF
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    if (setCapability(PhoneConstants.SIM_ID_1)) {
                        setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: OFF
                        // 3. 34G: No Change
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                        setDataEnable(false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: No Change
                            // 3. 34G: Default SIM
                            log("C5: Multi SIM + New SIM + Default SIM: Set Default data to "
                                + "phone:" + phoneId);
                            if (setCapability(phoneId)) {
                                setDefaultData(phoneId);
                            }
                        } else {
                            // Case 6: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: Unset
                            // 2. Data Enable: OFF
                            // 3. 34G: No Change
                            log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                            setDataEnable(false);
                        }
                    }
                }
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //Case 8: Multi SIM + All Old SIM + No Default SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: No Change
                    // 3. 34G: No change
                    //Do nothing
                    log("C8: Do nothing");
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (defaultIccid.equals(currIccId[i])) {
                            phoneId = i;
                            break;
                        }
                    }
                    if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                        // Case 7: Multi SIM + All Old SIM + Default SIM:
                        // 1. Default Data: Default SIM
                        // 2. Data Enable: No Change
                        // 3. 34G: Default SIM
                        log("C7: Multi SIM + New SIM + Default SIM: Set Default data to phone:"
                                + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        //Case 8: Multi SIM + All Old SIM + No Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: No Change
                        // 3. 34G: No change
                        //Do nothing
                        log("C8: Do nothing");
                    }
                }
            }
        }
    }*/

    private void subSelectorForOp02(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OP02");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP02: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        } else {
            log("DataSubSelector for OP02: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            // OP02 Case 0: No SIM change, do nothing
            log("OP02 C0: Inserted status no change, do nothing");
        } else if (insertedSimCount == 0) {
            // OP02 Case 1: No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C1: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }
            //OP02 Case 2: Single SIM
            // 1. Default Data: This sub
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C2: Single SIM: Set Default data to phone:" + phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(insertedSimCount);
        } else if (insertedSimCount >= 2) {
            //OP02 Case 3: Multi SIM
            // 1. Default Data: Always SIM1
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C3: Multi SIM: Set Default data to phone1");
            setDefaultData(PhoneConstants.SIM_ID_1);
            handleDataEnableForOp02(insertedSimCount);
        }

        updateDataEnableProperty();
        resetSimStatus();
        resetNewSimSlot();
    }

    private void subSelectorForOp02() {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op02 (subSelectorForOp02)");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            log("currIccid[" + i + "] : " + currIccId[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                insertedSimCount++;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
                log("sim index: " + i + " not inserted");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP02: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            return;
        } else {
            log("DataSubSelector for OP02: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: Unset
            // 2. Data Enable: off
            // 3. 34G: Slot1
            log("C0: No SIM inserted: set default data unset");
            setDefaultData(phoneId);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }
            // Case1: Single SIM
            // 1. Default Data: This SIM
            // 2. Data Enable: No change
            // 3. 34G: This SIM
            log("C1: Single SIM inserted: set default data to phone: " + phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(insertedSimCount);
        } else if (insertedSimCount >= 2) {
            if (checkOp02CapSwitch(HOME_POLICY) == false) {
                mIsNeedWaitImsi = true;
            }
        }

        updateDataEnableProperty();
    }

    private void subSelectorForOp01(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op01");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP01: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            mIntent = intent;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        } else {
            log("DataSubSelector for OP01: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OP01: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            mIntent = intent;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            log("OP01 C0: No SIM inserted, do nothing");
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            log("OP01 C1: Single SIM: Set Default data to phone:" + phoneId);
            if (setCapability(phoneId)) {
                //Avoid setting default data as invalid when receive
                //ACTION_SUBINFO_RECORD_UPDATED but sim info not ready yet.
                if (detectedType != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                    setDefaultData(phoneId);
                }
            }

            turnOffNewSimData(intent);
        } else if (insertedSimCount >= 2) {
            // default data policy for this case will be executed
            //     in checkOp01CapSwitch6m->handleDefaultDataOp01MultiSims()
            mIntent = intent; // this will be used in
                              //   checkOp01CapSwitch6m->
                              //      handleDefaultDataOp01MultiSims() ->
                              //        turnOffNewSimData(intent)
            if (checkOp01CapSwitch6m() == false) {
                // need wait imsi ready
                mIsNeedWaitImsi = true;
                mIntent = intent;
                setSimStatus(intent);
                setNewSimSlot(intent);
                return;
            }
        }

        // clean system property
        resetSimStatus();
        resetNewSimSlot();
    }

    private void handleDefaultDataOp01MultiSims() {

        // Force default data to CMCC if CMCC + other
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int insertedStatus = 0;

        log("OP01 C2: Multi SIM: E");

        if (RadioCapabilitySwitchUtil.getSimInfo(
                simOpInfo, simType, insertedStatus)) {
            //disable data on CT sim card on CMCC+CT sims.
            if (SystemProperties.get(MTK_C2K_SUPPORT).equals("1")) {
                boolean hasCMCC = false;
                boolean hasCT = false;
                int iCT = -1;
                int preDataSub = SubscriptionManager.getDefaultDataSubId();
                log("OP01 C2: Multi SIM: preDataSub="
                    + preDataSub);
                for (int i = 0; i < mPhoneNum; i++) {
                    if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                        if (!hasCMCC) {
                            hasCMCC = true;
                            phoneId = i;
                        }
                    } else if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP09) {
                        if (!hasCT) {
                            hasCT = true;
                            iCT = i;
                        }
                    }
                    int sub = SubscriptionManager.getSubIdUsingPhoneId(i);
                    log("OP01 C2: sub=" + sub);
                    if (preDataSub != sub && sub > SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        setDataEnabled(i, false);
                    }
                }
                if (hasCMCC && hasCT) {
                    int sub = SubscriptionManager.getSubIdUsingPhoneId(phoneId);
                    if (sub > SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        log("OP01 C2: Multi SIM: CMCC + CT, set default data to CMCC");
                        setDefaultData(phoneId);
                    }
                }
            }

            //OP01 MSIM: Follow OM policy
            log("OP01 C2: Multi SIM: Turn off non default data");
            if (mIntent != null) {
                turnOffNewSimData(mIntent);
            }

            updateDataEnableProperty();
        }
    }

    private void subSelectorForOp18(Intent intent) {
        // op18 has multi policy, decide here
        switch(capability_switch_policy) {
            case POLICY_NO_AUTO:
                log("subSelectorForOp18: no auto policy, skip");
                return;
            case POLICY_DEFAULT:
                // default policy of op18 is to follow OM.
                subSelectorForOm(intent);
                return;
            case POLICY_POLICY1:
                // keep run op18 policy
                break;
            default:
                log("subSelectorForOp18: Unknow policy, skip");
                return;
        }
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op18");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP18: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            mIntent = intent;
            return;
        } else {
            log("DataSubSelector for OP18: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnabled(phoneId, true);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnabled(phoneId, true);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnabled(phoneId, true);
                    }
                }
            }
        } else if (insertedSimCount >= 2) {
            // data switching
            if (checkOp18CapSwitch() == false) {
                // need wait imsi ready
                mIsNeedWaitImsi = true;
                mIntent = intent;
                return;
            }
        }
    }

    private boolean checkOp01CapSwitch() {
        // check if need to switch capability
        // op01 USIM > op01 SIM > oversea USIM > oversea SIM > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetSim = -1;
        int insertedSimCount = 0;
        int csimRuimCount = 0;
        int nonCsimRuimPhoneId = -1;
        int insertedStatus = 0;
        boolean[] op01Usim = new boolean[mPhoneNum];
        boolean[] op01Sim = new boolean[mPhoneNum];
        boolean[] overseaUsim = new boolean[mPhoneNum];
        boolean[] overseaSim = new boolean[mPhoneNum];
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];

        log("checkOp01CapSwitch start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        // check pin lock
        log("checkOp01CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("checkOp01CapSwitch: sim locked");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
        } else {
            log("checkOp01CapSwitch: no sim locked");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }

        int capabilitySimId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;
        log("op01: capabilitySimIccid:" + capabilitySimIccid
                + "capabilitySimId:" + capabilitySimId);
        for (int i = 0; i < mPhoneNum; i++) {
            // update SIM status
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    op01Usim[i] = true;
                } else {
                    op01Sim[i] = true;
                }
            } else if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OVERSEA) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    overseaUsim[i] = true;
                } else {
                    overseaSim[i] = true;
                }
            }
            // update CSIM/RUIM counter and non-CSIM/RUIM phone id
            if (simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_SIM
                    || simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_USIM) {
                nonCsimRuimPhoneId = i;
            } else {
                csimRuimCount++;
            }
        }
        // dump sim op info
        log("op01Usim: " + Arrays.toString(op01Usim));
        log("op01Sim: " + Arrays.toString(op01Sim));
        log("overseaUsim: " + Arrays.toString(overseaUsim));
        log("overseaSim: " + Arrays.toString(overseaSim));
        // dump CSIM/RUIM counter and non-CSIM/RUIM phone id
        log("csimRuimCount: " + csimRuimCount);
        log("nonCsimRuimPhoneId: " + nonCsimRuimPhoneId);

        // set capability phone to non-CSIM/RUIM phone
        if (RadioCapabilitySwitchUtil.isOp01LCProject()) {
            if (insertedSimCount == 2 && csimRuimCount == 1) {
                if (nonCsimRuimPhoneId != capabilitySimId) {
                    log("op01-L+C: current capability SIM is on CSIM/RUIM, change!");
                    setCapability(nonCsimRuimPhoneId);
                }
                return true;
            }
        }
        for (int i = 0; i < mPhoneNum; i++) {
            if (capabilitySimIccid.equals(currIccId[i])) {
                targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(i, op01Usim
                        , op01Sim, overseaUsim, overseaSim);
                log("op01: i = " + i + ", currIccId : " + currIccId[i] + ", targetSim : "
                        + targetSim);
                // default capability SIM is inserted
                if (op01Usim[i] == true) {
                    log("op01-C1: cur is old op01 USIM, no change");
                    if (capabilitySimId != i) {
                        log("op01-C1a: old op01 USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (op01Sim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C2: cur is old op01 SIM but find op01 USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C2a: old op01 SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaUsim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C3: cur is old OS USIM but find op01 SIMs, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C3a: old OS USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaSim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C4: cur is old OS SIM but find op01 SIMs/OS USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C4a: old OS SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (targetSim != -1) {
                    log("op01-C5: cur is old non-op01 SIM/USIM but find higher SIM, change!");
                    setCapability(targetSim);
                    return true;
                }
                log("op01-C6: no higher priority SIM, no cahnge");
                return true;
            }
        }
        // cannot find default capability SIM, check if higher priority SIM exists
        targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(capabilitySimId,
                op01Usim, op01Sim, overseaUsim, overseaSim);
        log("op01: target SIM :" + targetSim);
        if (op01Usim[capabilitySimId] == true) {
            log("op01-C7: cur is new op01 USIM, no change");
            return true;
        } else if (op01Sim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C8: cur is new op01 SIM but find op01 USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaUsim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C9: cur is new OS USIM but find op01 SIMs, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaSim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C10: cur is new OS SIM but find op01 SIMs/OS USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (targetSim != -1) {
            log("op01-C11: cur is non-op01 but find higher priority SIM, change!");
            setCapability(targetSim);
        } else {
            log("op01-C12: no higher priority SIM, no cahnge");
        }
        return true;
    }

    private boolean checkOp01CapSwitch6m() {
        // check if need to switch capability
        // op01 USIM > op01 SIM > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        String[] currIccId = new String[mPhoneNum];
        int[] priority = new int[mPhoneNum];
        int defDataSubId = SubscriptionController.getInstance().getDefaultDataSubId();
        int defDataPhoneId = SubscriptionManager.getPhoneId(defDataSubId);
        if (defDataPhoneId >= 0 && defDataPhoneId < mPhoneNum) {
            // used to determine capability phone when two SIM cards have the same priority
            log("default data phoneId from sub = " + defDataPhoneId);
        } else {
            log("phoneId out of boundary :" + defDataPhoneId);
            defDataPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        }

        log("checkOp01CapSwitch6m start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        // check pin lock
        log("checkOp01CapSwitch6m : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("checkOp01CapSwitch6m: sim locked");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
        } else {
            log("checkOp01CapSwitch6m: no sim locked");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }

        for (int i = 0; i < mPhoneNum; i++) {
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                if (simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_USIM) {
                    priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OP01_USIM;
                } else if (simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OP01_SIM;
                }
            } else {
                priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OTHER;
            }
        }
        // dump priority info
        log("priority: " + Arrays.toString(priority));

        // get the highest priority SIM
        targetPhoneId = RadioCapabilitySwitchUtil.getHighestPriorityPhone(defDataPhoneId,
                priority);
        log("op01-6m: target phone: " + targetPhoneId);
        if (targetPhoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            log("op01-6m: highest priority SIM determined, change!");
            setCapability(targetPhoneId);
        } else {
            log("op01-6m: can't determine highest priority SIM, no cahnge");
        }

        // special DEFAULT DATA handle for 2+ SIMs inserted case
        if (insertedSimCount >= 2) {
            handleDefaultDataOp01MultiSims();
        }

        return true;
    }

    private boolean checkOp18CapSwitch() {
        // op18 has multi policies, check configuration is default
        if (capability_switch_policy != POLICY_POLICY1) {
            log("checkOp18CapSwitch: config is not policy1, do nothing");
            return true;
        }
        // check if need to switch capability
        // op18 > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetSim = -1;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        boolean[] op18Usim = new boolean[mPhoneNum];
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];

        log("checkOp18CapSwitch start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp18CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }
        // check pin lock
        String propStr;
        for (int i = 0; i < mPhoneNum; i++) {
            if (i == 0) {
                propStr = "gsm.sim.ril.mcc.mnc";
            } else {
                propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
            }
            if (SystemProperties.get(propStr, "").equals("sim_lock")) {
                log("checkOp18CapSwitch : phone " + i + " is sim lock");
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            }
        }
        int capabilitySimId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;
        log("op18: capabilitySimIccid:" + capabilitySimIccid
                + "capabilitySimId:" + capabilitySimId);
        for (int i = 0; i < mPhoneNum; i++) {
            // update SIM status
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP18) {
                op18Usim[i] = true;
            }
        }
        // dump sim op info
        log("op18Usim: " + Arrays.toString(op18Usim));

        for (int i = 0; i < mPhoneNum; i++) {
            if (capabilitySimIccid.equals(currIccId[i])) {
                if (op18Usim[i] == true) {
                    targetSim = i;
                } else {
                    for (int j = 0; j < mPhoneNum; j++) {
                        if (op18Usim[j] == true) {
                            targetSim = j;
                        }
                    }
                }

                log("op18: i = " + i +
                    ", currIccId : " +
                    currIccId[i] + ", targetSim : " + targetSim);

                // default capability SIM is inserted
                if (op18Usim[i] == true) {
                    log("op18-C1: cur is old op18 USIM, no change");
                    if (capabilitySimId != i) {
                        log("op18-C1a: old op18 SIM change slot, change!");
                        setCapability(i);
                    }
                    setDefaultData(i);
                    setDataEnabled(i, true);
                    return true;
                } else if (targetSim != -1) {
                    log("op18-C2: cur is not op18 SIM but find op18 SIM, change!");
                    setCapability(targetSim);
                    setDefaultData(targetSim);
                    setDataEnabled(targetSim, true);
                    return true;
                }
                setDefaultData(capabilitySimId);
                setDataEnabled(capabilitySimId, true);
                log("op18-C6: no higher priority SIM, no cahnge");
                return true;
            }
        }
        // cannot find default capability SIM, check if higher priority SIM exists
        if (op18Usim[capabilitySimId] == true) {
            targetSim = capabilitySimId;
        } else {
            for (int i = 0; i < mPhoneNum; i++) {
                if (op18Usim[i] == true) {
                    targetSim = i;
                }
            }
        }
        log("op18: target SIM :" + targetSim);
        if (op18Usim[capabilitySimId] == true) {
            log("op18-C7: cur is new op18 USIM, no change");
            setDefaultData(capabilitySimId);
            setDataEnabled(capabilitySimId, true);
            return true;
        } else if (targetSim != -1) {
            log("op18-C8: find op18 USIM, change!");
            setCapability(targetSim);
            setDefaultData(targetSim);
            setDataEnabled(targetSim, true);
            return true;
        } else {
            setDefaultData(capabilitySimId);
            setDataEnabled(capabilitySimId, true);
            log("op18-C12: no higher priority SIM, no cahnge");
        }
        return true;
    }

    private void subSelectorForOp09(Intent intent) {
        if (intent == null) {
            log("OP09: intent is null, ignore!");
            return;
        }

        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, -1);
        int detectedCount = intent.getIntExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, -1);
        SubscriptionController subController = SubscriptionController.getInstance();
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int[] subList = subController.getActiveSubIdList();
        int defaultSub = subController.getDefaultDataSubId();
        int insertedSimCount = subList.length;
        log("OP09: Inserted SIM count: " + insertedSimCount + " Intent count: " + detectedCount +
                " detectedType = " + detectedType + " defaultSub = " + defaultSub);
        if (detectedCount > -1 && detectedCount != insertedSimCount) {
            log("OP09: Intent count and latest sub count not match, ignore and wait next.");
            return;
        }

        if (insertedSimCount == 0) {
            log("OP09 C0: No SIM inserted, do nothing.");
        } else if (insertedSimCount == 1) {
            switch (detectedType) {
            case SubscriptionManager.EXTRA_VALUE_NEW_SIM:
                // only one new sim card is detected, make it as default and data on.
                phoneId = subController.getPhoneId(subList[0]);
                log("OP09 C1: a new sim detected, Set Default slot: " + phoneId);
                setDefaultData(phoneId);
                setDataEnabled(phoneId, true);
                setCapabilityIfNeeded(phoneId);
                break;
            case SubscriptionManager.EXTRA_VALUE_REMOVE_SIM:
                // remove one sim and left one.
                if (subList[0] != defaultSub) {
                    // the left sim is not default, make it as default
                    phoneId = subController.getPhoneId(subList[0]);
                    log("OP09 C2.1: left a sim not default, Set Default: " + phoneId);
                    // before change default data, get the switch status and set to new default.
                    setDataEnabled(phoneId, getDataEnabledFromSetting(defaultSub));
                    setDefaultData(phoneId);
                    setCapabilityIfNeeded(phoneId);
                } else {
                    log("OP09 C2.2: a sim left and it's default sub, do nothing.");
                }
                break;
            case SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM:
                if (subList[0] != defaultSub) {
                    log("OP09 C3.1: a sim left and is not default data sim,"
                            + " set it as default data sim.");
                    phoneId = subController.getPhoneId(subList[0]);
                    setDataEnabled(phoneId, getDataEnabledFromSetting(defaultSub));
                    setDefaultData(phoneId);
                } else {
                    log("OP09 C3.2: a sim left with default data on it, do nothing.");
                }
                // TODO: sim switch need do something. But we plan seperate it from data logic.
                break;
            case SubscriptionManager.EXTRA_VALUE_NOCHANGE:
                // boot up and detect an old sim, do nothing.
                log("OP09 C4: a sim exist and is old, do nothing.");
                break;
            default:
                log("OP09 C5: ignore unknown detectedType: " + detectedType);
                break;
            }
        } else if (insertedSimCount == 2) {
            switch (detectedType) {
            case SubscriptionManager.EXTRA_VALUE_NEW_SIM:
                int newSimStatus =
                        intent.getIntExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);
                log("OP09 C6.0: newSimStatus = " + newSimStatus + " subList[0] = " +
                        subList[0] + " subList[1] = " + subList[1]);
                // 1: slot 0 insert a new sim, 2: slot 1 insert a new sim.
                // NOTE: Sub module report new sim cards after hot plug.
                if (defaultSub == subList[0]) {
                    // default sub still on sim1, keep default data.
                    log("OP09 C6.1: data on old sim1, turn off SIM2, set capability to SIM1.");
                    if (newSimStatus == 3) {
                        setDataEnabled(PhoneConstants.SIM_ID_1, true);
                    }
                    setDataEnabled(PhoneConstants.SIM_ID_2, false);
                    setCapabilityIfNeeded(PhoneConstants.SIM_ID_1);
                } else if (defaultSub == subList[1]) {
                    // default sub still on sim2, keep default data.
                    log("OP09 C6.2: data on old sim2, turn off SIM1, set capability to SIM2.");
                    if (newSimStatus == 3) {
                        setDataEnabled(PhoneConstants.SIM_ID_2, true);
                    }
                    setDataEnabled(PhoneConstants.SIM_ID_1, false);
                    setCapabilityIfNeeded(PhoneConstants.SIM_ID_2);
                } else {
                    log("OP09 C6.3: new + new or new + old, no default, set sim1 as default.");
                    if (newSimStatus == 1 || newSimStatus == 2) {
                        setDataEnabled(PhoneConstants.SIM_ID_1,
                                getDataEnabledFromSetting(defaultSub));
                    } else {
                        setDataEnabled(PhoneConstants.SIM_ID_1, true);
                    }
                    // force set sim2 data off to ensure OP09 only one switch is on,
                    // the record maybe on if it is inserted before.
                    setDataEnabled(PhoneConstants.SIM_ID_2, false);
                    setDefaultData(PhoneConstants.SIM_ID_1);
                    setCapabilityIfNeeded(PhoneConstants.SIM_ID_1);
                }
                break;
            case SubscriptionManager.EXTRA_VALUE_REMOVE_SIM:
                // remove one sim and left two? impossible.
                log("OP09 C7: a sim removed and two sim left, not support yet!");
                break;
            case SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM:
                // two sims swap slot don't change settings.
                log("OP09 C8: two sims swap slot location, do nothing.");
                // TODO: sim switch need do something. But we plan seperate it from data logic.
                break;
            case SubscriptionManager.EXTRA_VALUE_NOCHANGE:
                // boot up and detect two old sims, do nothing.
                log("OP09 C9: two sims exist and are old, do nothing.");
                break;
            default:
                log("OP09 C10: ignore unknown detectedType: " + detectedType);
                break;
            }
        } else {
            log("OP09 C11: sim count bigger than 2, not support yet!");
        }

        updateDataEnableProperty();
    }

    private void setDataEnabled(int phoneId, boolean enable) {
        log("setDataEnabled: phoneId=" + phoneId + ", enable=" + enable);

        TelephonyManager telephony = TelephonyManager.getDefault();
        if (telephony != null) {
            if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
                telephony.setDataEnabled(enable);
            } else {
                int phoneSubId = 0;
                if (enable == false) {
                    phoneSubId = PhoneFactory.getPhone(phoneId).getSubId();
                    log("Set Sub" + phoneSubId + " to disable");
                    telephony.setDataEnabled(phoneSubId, enable);
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        phoneSubId = PhoneFactory.getPhone(i).getSubId();
                        if (i != phoneId) {
                            log("Set Sub" + phoneSubId + " to disable");
                            telephony.setDataEnabled(phoneSubId, false);
                        } else {
                            log("Set Sub" + phoneSubId + " to enable");
                            telephony.setDataEnabled(phoneSubId, true);
                        }
                    }
                }
            }
        }
    }

    private void updateDataEnableProperty() {
        TelephonyManager telephony = TelephonyManager.getDefault();
        boolean dataEnabled = false;
        String dataOnIccid = "0";
        int subId = 0;

        for (int i = 0; i < mPhoneNum; i++) {

            subId = PhoneFactory.getPhone(i).getSubId();

            if (telephony != null) {
               dataEnabled = telephony.getDataEnabled(subId);
            }

            if (dataEnabled) {
                dataOnIccid = SystemProperties.get(PROPERTY_ICCID[i], "0");
            } else {
                dataOnIccid = "0";
            }

            log("setUserDataProperty:" + dataOnIccid);
            TelephonyManager.getDefault().setTelephonyProperty(i, PROPERTY_MOBILE_DATA_ENABLE,
                    dataOnIccid);
        }
    }

    private void setDefaultData(int phoneId) {
        SubscriptionController subController = SubscriptionController.getInstance();
        int sub = SubscriptionManager.getSubIdUsingPhoneId(phoneId);
        int currSub = SubscriptionManager.getDefaultDataSubId();
        mPrevDefaultDataSubId = currSub;
        // op02 hotplug
        setLastValidDefaultDataSub(currSub);

        log("setDefaultData: " + sub + ", current default sub:" + currSub +
                "last valid default sub:" + mLastValidDefaultDataSubId);
        if (sub != currSub) {
            if (!isOP09Support() || sub >= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subController.setDefaultDataSubIdWithoutCapabilitySwitch(sub);
            }
        } else {
            log("setDefaultData: default data unchanged");
        }
    }

    private void turnOffNewSimData(Intent intent) {
        // For single SIM phones, this is a per phone property.
        if (TelephonyManager.getDefault().getSimCount() == 1
                && !mOperatorSpec.equals(OPERATOR_OP09)) {
            log("[turnOffNewSimData] Single SIM project, don't change data enable setting");
            return;
        }

        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        log("turnOffNewSimData detectedType = " + detectedType);

        //L MR1 Spec, turn off data if new sim inserted.
        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
            int newSimSlot = (intent == null) ? getNewSimSlot() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

            log("newSimSlot = " + newSimSlot);
            log("default iccid = " + SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID));

            for (int i = 0; i < mPhoneNum; i++) {
                if ((newSimSlot & (1 << i)) != 0) {
                    String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
                    String newSimIccid = SystemProperties.get(PROPERTY_ICCID[i]);
                    if (!newSimIccid.equals(defaultIccid)) {
                        log("Detect NEW SIM, turn off phone " + i + " data.");
                        setDataEnabled(i, false);
                    }
                }
            }
        }
    }

    private boolean setCapabilityIfNeeded(int phoneId) {
        if (isOP09ASupport()) {
            return true;
        }

        return setCapability(phoneId);
    }

    private boolean setCapability(int phoneId) {
        if (isOP09CSupport()){
            if (!isCanSwitch()){
                log("setCapability: isCanSwitch = false");
                return true;
            }
        }
        int[] phoneRat = new int[mPhoneNum];
        boolean isSwitchSuccess = true;

        log("setCapability: " + phoneId);

        String curr3GSim = SystemProperties.get(PROPERTY_3G_SIM, "");
        log("current 3G Sim = " + curr3GSim);

        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));
            ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
            if (null == iTel || null == iTelEx) {
                loge("Can not get phone service");
                return false;
            }

            boolean isSimSwitching = false;
            isSimSwitching = iTelEx.isCapabilitySwitching();
            log("current capability switch status = " + isSimSwitching);
            if (!isSimSwitching && curr3GSim != null && !curr3GSim.equals("")) {
                int curr3GPhoneId = Integer.parseInt(curr3GSim);
                if (curr3GPhoneId == (phoneId + 1)) {
                    log("Current 3G phone equals target phone, don't trigger switch");
                    return isSwitchSuccess;
                }
            }

            int currRat = iTel.getRadioAccessFamily(phoneId, "phone");
            log("Current phoneRat:" + currRat);

            RadioAccessFamily[] rat = new RadioAccessFamily[mPhoneNum];
            for (int i = 0; i < mPhoneNum; i++) {
                if (phoneId == i) {
                    log("SIM switch to Phone" + i);
                    phoneRat[i] = ProxyController.getInstance().getMaxRafSupported();
                } else {
                    phoneRat[i] = ProxyController.getInstance().getMinRafSupported();
                }
                rat[i] = new RadioAccessFamily(i, phoneRat[i]);
            }
            if (false  == iTelEx.setRadioCapability(rat)) {
                log("Set phone rat fail!!!");
                isSwitchSuccess = false;
            }
        } catch (RemoteException ex) {
            log("Set phone rat fail!!!");
            ex.printStackTrace();
            isSwitchSuccess = false;
        }

        if (!isSwitchSuccess) {
            if (WorldPhoneUtil.isWorldPhoneSwitching()) {
                log("world mode switching!");
                registerWorldModeReceiver();
                mPhoneId = phoneId;
            }
        } else {
            if (mHasRegisterWorldModeReceiver) {
                unRegisterWorldModeReceiver();
                mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
            }
        }

        return isSwitchSuccess;
    }

    private boolean checkOp02CapSwitch(int policy) {
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int insertedStatus = 0;
        int insertedSimCount = 0;
        String currIccId[] = new String[mPhoneNum];
        ArrayList<Integer> usimIndexList = new ArrayList<Integer>();
        ArrayList<Integer> simIndexList = new ArrayList<Integer>();
        ArrayList<Integer> op02IndexList = new ArrayList<Integer>();
        ArrayList<Integer> otherIndexList = new ArrayList<Integer>();

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp02CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);

        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("checkOp02CapSwitch: sim locked");
            if (HOME_POLICY == policy) {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            } else {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "true");
            }
        } else {
            log("checkOp02CapSwitch: no sim locked");
            if (HOME_POLICY == policy) {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
            } else {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "false");
            }
        }
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }

        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            if (HOME_POLICY == policy) {
                mIsNeedWaitAirplaneModeOff = true;
            } else if (ROAMING_POLICY == policy) {
                mIsNeedWaitAirplaneModeOffRoaming = true;
            }
        }

        for (int i = 0; i < mPhoneNum; i++) {
            if (RadioCapabilitySwitchUtil.SIM_OP_INFO_OP02 == simOpInfo[i]) {
                op02IndexList.add(i);
            } else {
                otherIndexList.add(i);
            }
            if (RadioCapabilitySwitchUtil.SIM_TYPE_USIM == simType[i]) {
                usimIndexList.add(i);
            } else {
                simIndexList.add(i);
            }
        }
        log("usimIndexList size = " + usimIndexList.size());
        log("op02IndexList size = " + op02IndexList.size());
        log("policy = " + policy);

        mIsUserConfirmDefaultData = false;
        switch(policy) {
            case HOME_POLICY:
                executeOp02HomePolicy(usimIndexList, op02IndexList, simIndexList);
                break;
            case ROAMING_POLICY:
                executeOp02RoamingPolocy(usimIndexList, op02IndexList, otherIndexList);
                break;
            default:
                loge("Should NOT be here");
                break;
        }
        return true;
    }

    private void executeOp02HomePolicy(ArrayList<Integer> usimIndexList,
        ArrayList<Integer> op02IndexList, ArrayList<Integer> simIndexList) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int op02CardCount = 0;

        log("Enter op02HomePolicy");
        // Home policy: OP02 USIM > other USIM > OP02 SIM > other SIM
        if (usimIndexList.size() >= 2) {
            // check OP02USIM count
            for (int i = 0; i < usimIndexList.size(); i++) {
                if (op02IndexList.contains(usimIndexList.get(i))) {
                    op02CardCount++;
                    phoneId = i;
                }
            }

            if (op02CardCount == 1) {
                // Case2: OP02 USIM + other USIMs/ OP02 USIM + other SIMs
                // 1. Default Data: OP02
                // 2. Data Enable: No change
                // 3. 34G: OP02
                log("C2: Only one OP02 USIM inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case3: More than two OP02 cards or other operator cards
                // Display dialog
                log("C3: More than two OP02 cards or other operator cards inserted," +
                        "Display dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                log("mPrevDefaultDataSubId:" + mPrevDefaultDataSubId);
                mIsUserConfirmDefaultData = true;
            }
        } else if (usimIndexList.size() == 1) {
            // Case4: USIM + SIMs
            // 1. Default Data: USIM
            // 2. Data Enable: No change
            // 3. 34G: USIM
            phoneId = usimIndexList.get(0);
            log("C4: Only one USIM inserted, set default data to phone: " +
                    phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(2);
        } else {
            // usimCount = 0 (Case: all SIMs)
            // check OP02SIM count
            for (int i = 0; i < simIndexList.size(); i++) {
                if (op02IndexList.contains(simIndexList.get(i))) {
                    op02CardCount++;
                    phoneId = i;
                }
            }

            if (op02CardCount == 1) {
                // Case5: OP02 card + otehr op cards
                // 1. Default Data: OP02 card
                // 2. Data Enable: No change
                // 3. 34G: OP02 card
                log("C5: OP02 card + otehr op cards inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // case6: More than two OP02 cards or other operator cards
                // Display dialog
                log("C6: More than two OP02 cards or other operator cards inserted," +
                        "display dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        }
    }

    private void executeOp02RoamingPolocy(ArrayList<Integer> usimIndexList,
            ArrayList<Integer> op02IndexList, ArrayList<Integer> otherIndexList) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int usimCount = 0;

        log("Enter op02RoamingPolocy");

        if (mContext == null) {
            loge("mContext is null, return");
        }

        // Roaming policy: OP02 USIM > OP02 SIM > other UISM > other SIM
        if (op02IndexList.size() >= 2) {
            // check OP02USIM count
            for (int i = 0; i < op02IndexList.size(); i++) {
                if (usimIndexList.contains(op02IndexList.get(i))) {
                    usimCount++;
                    phoneId = i;
                }
            }

            if (usimCount == 1) {
                // Case2: OP02 USIM + other USIMs / OP02 USIM + other SIMs
                // 1.Deafult Data: USIM
                // 2.Data Enable: No change
                // 3.34G: USIM
                log("C2: Only one OP02 USIM inserted, set default data to phone: "
                        + phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case3: More than two USIM cards or other SIM cards
                // Display dialog
                log("C3: More than two USIM cards or other SIM cards inserted, show dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        } else if (op02IndexList.size() == 1) {
            // Case4: OP02 card + other cards
            // Default Data: OP02
            // Data Enable: No change
            // 34G: OP02
            phoneId = op02IndexList.get(0);
            log("C4: OP02 card + other cards inserted, set default data to phone: "
                    + phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(2);
        } else {
            // op02CardCount = 0 (Case: other operator cards)
            // Check otherUSIM count
            for (int i = 0; i < otherIndexList.size(); i++) {
                if (usimIndexList.contains(otherIndexList.get(i))) {
                    usimCount++;
                    phoneId = i;
                }
            }

            if (usimCount == 1) {
                // Case5: other USIM + other SIM cards
                // Default Data: USIM
                // Data Enable: No change
                // 34G: USIM
                log("C5: Other USIM + other SIM cards inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case6: More than two USIM cards or all SIM cards
                // Display dialog
                log("C6: More than two USIM cards or all SIM cards inserted, diaplay dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        }

        SharedPreferences preferenceRoaming = mContext.getSharedPreferences(FIRST_TIME_ROAMING,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorRoaming = preferenceRoaming.edit();
        editorRoaming.putBoolean(NEED_TO_EXECUTE_ROAMING, false);
        if (!editorRoaming.commit()) {
            loge("write sharedPreference ERROR");
        }
    }

    private void handleDataEnableForOp02(int insertedSimCount) {
        log("handleDataEnableForOp02: insertedSimCount = " + insertedSimCount);

        TelephonyManager telephony = TelephonyManager.getDefault();
        if (telephony == null) {
            loge("TelephonyManager.getDefault() return null");
            return;
        }

        int nDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        if (!SubscriptionManager.isValidSubscriptionId(mPrevDefaultDataSubId)) {
            if (SubscriptionManager.isValidSubscriptionId(mLastValidDefaultDataSubId)) {
                boolean enable = getDataEnabledFromSetting(mLastValidDefaultDataSubId);
                log("setEnable by lastValidDataSub's setting = " + enable);
                setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), enable);
            } else {
                setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), true);
            }
        } else if (SubscriptionManager.isValidSubscriptionId(mPrevDefaultDataSubId)
                && SubscriptionManager.isValidSubscriptionId(nDefaultDataSubId)) {
            if (mPrevDefaultDataSubId != nDefaultDataSubId) {
                if (getDataEnabledFromSetting(mPrevDefaultDataSubId)) {
                    setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), true);
                } else {
                    setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), false);
                }
            } else if (insertedSimCount == 2) {
                int nonDefaultPhoneId = 0;
                if (SubscriptionManager.getPhoneId(nDefaultDataSubId) == 0) {
                    nonDefaultPhoneId = 1;
                } else {
                    nonDefaultPhoneId = 0;
                }
                if (getDataEnabledFromSetting(nDefaultDataSubId)) {
                    setDataEnabled(nonDefaultPhoneId, false);
                }
            }
        }
    }

    private boolean getDataEnabledFromSetting(int nSubId) {
        log("getDataEnabledFromSetting, nSubId = " + nSubId);

        if (mContext == null || mContext.getContentResolver() == null) {
            log("getDataEnabledFromSetting, context or resolver is null, return");
            return false;
        }

        boolean retVal = false;
        try {
            retVal = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA + nSubId) != 0;
        } catch (SettingNotFoundException snfe) {
            retVal = false;
        }

        log("getDataEnabledFromSetting, retVal = " + retVal);
        return retVal;
    }

    private boolean isNeedWaitUnlock(String prop) {
        return (SystemProperties.getBoolean(prop, false));
    }

    private void setNeedWaitUnlock(String prop, String value) {
        SystemProperties.set(prop, value);
    }

    private void setSimStatus(Intent intent) {
        if (intent == null) {
            log("setSimStatus, intent is null => return");
            return;
        }
        log("setSimStatus");
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        SystemProperties.set(SIM_STATUS, Integer.toString(detectedType));
    }

    private void resetSimStatus() {
        log("resetSimStatus");
        SystemProperties.set(SIM_STATUS, "");
    }

    private int getSimStatus() {
        log("getSimStatus");
        return SystemProperties.getInt(SIM_STATUS, 0);
    }

    private void setNewSimSlot(Intent intent) {
        if (intent == null) {
            log("setNewSimSlot, intent is null => return");
            return;
        }
        log("setNewSimSlot");
        int newSimStatus = intent.getIntExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);
        SystemProperties.set(NEW_SIM_SLOT, Integer.toString(newSimStatus));
    }

    private void resetNewSimSlot() {
        log("resetNewSimSlot");
        SystemProperties.set(NEW_SIM_SLOT, "");
    }

    private int getNewSimSlot() {
        log("getNewSimSlot");
        return SystemProperties.getInt(NEW_SIM_SLOT, 0);
    }

    private void registerWorldModeReceiver() {
        if (mContext == null) {
            log("registerWorldModeReceiver, context is null => return");
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_WORLD_MODE_CHANGED);
        mContext.registerReceiver(mWorldModeReceiver, filter);
        mHasRegisterWorldModeReceiver = true;
    }

    private void unRegisterWorldModeReceiver() {
        if (mContext == null) {
            log("unRegisterWorldModeReceiver, context is null => return");
            return;
        }

        mContext.unregisterReceiver(mWorldModeReceiver);
        mHasRegisterWorldModeReceiver = false;
    }

    private void log(String txt) {
        if (DBG) {
            Rlog.d("DataSubSelector", txt);
        }
    }

    private void loge(String txt) {
        if (DBG) {
            Rlog.e("DataSubSelector", txt);
        }
    }

    /**
     * Return enable, whether the data of the Last card is enabled.
     */
    private int getLastDataEnabled() {
        int subId = SubscriptionManager.getDefaultDataSubId();
        log("DataSubselector getLastDataEnable subId = " + subId);
        int enabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MOBILE_DATA + subId, 0);
        return enabled;
    }

    private void setLastValidDefaultDataSub(int subId) {
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            log("setLastValidDefaultDataSub = " + subId);
            mLastValidDefaultDataSubId = subId;
        } else {
            log("because invalid id to set, keep LastValidDefaultDataSub = "
                    + mLastValidDefaultDataSubId);
        }
    }

    private boolean isOP09ASupport() {
        return OPERATOR_OP09.equals(SystemProperties.get("ro.operator.optr", ""))
                && SEGDEFAULT.equals(SystemProperties.get("ro.operator.seg", ""));
    }

    private boolean isOP09Support() {
        return OPERATOR_OP09.equals(SystemProperties.get("ro.operator.optr", ""));
    }

    private boolean isOP09CSupport() {
        return OPERATOR_OP09.equals(SystemProperties.get("ro.operator.optr", ""))
                && SEGC.equals(SystemProperties.get("ro.operator.seg", ""));
    }

    private boolean isCanSwitch() {
        boolean ret = true;
        if (mAirplaneModeOn){
            ret = false ;
            log("DataSubselector,isCanSwitch mAirplaneModeOn = " + mAirplaneModeOn);
            return ret;
        }
        int simState = 0;
        int simNum = TelephonyManager.getDefault().getPhoneCount();
        for (int i = 0; i < simNum; i++) {
            simState = TelephonyManager.from(mContext).getSimState(i);
            if (simState == TelephonyManager.SIM_STATE_PIN_REQUIRED ||
                    simState == TelephonyManager.SIM_STATE_PUK_REQUIRED ||
                    simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED ||
                    simState == TelephonyManager.SIM_STATE_NOT_READY){
                log("DataSubselector,sim locked ,isCanSwitch simState = " + simState);
                ret = false ;
                return ret;
            }
        }
        return ret;
    }

}
