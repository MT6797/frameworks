/*
* Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import static android.Manifest.permission.READ_PHONE_STATE;

import android.app.ActivityManagerNative;
import android.app.IUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.IPackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;

import android.text.TextUtils;

// MTK-START
import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.os.SystemProperties;

import com.android.internal.telephony.uicc.SpnOverride;

import com.mediatek.internal.telephony.DefaultSmsSimSettings;
import com.mediatek.internal.telephony.DefaultVoiceCallSubSettings;

import java.util.concurrent.atomic.AtomicReferenceArray;
// MTK-END
import java.util.List;

/**
 *@hide
 */
public class SubscriptionInfoUpdater extends Handler {
    private static final String LOG_TAG = "SubscriptionInfoUpdater";
    private static final int PROJECT_SIM_NUM = TelephonyManager.getDefault().getPhoneCount();

    private static final int EVENT_SIM_LOCKED_QUERY_ICCID_DONE = 1;
    private static final int EVENT_GET_NETWORK_SELECTION_MODE_DONE = 2;
    private static final int EVENT_SIM_LOADED = 3;
    private static final int EVENT_SIM_ABSENT = 4;
    private static final int EVENT_SIM_LOCKED = 5;
    private static final int EVENT_SIM_IO_ERROR = 6;
    private static final int EVENT_SIM_UNKNOWN = 7;

    // MTK-START
    private static final int EVENT_SIM_READY = 100;
    private static final int EVENT_RADIO_AVAILABLE = 101;
    private static final int EVENT_RADIO_UNAVAILABLE = 102;
    // For the feature SIM Hot Swap with Common Slot
    private static final int EVENT_SIM_NO_CHANGED = 103;
    private static final int EVENT_TRAY_PLUG_IN = 104;
    private static final int EVENT_SIM_PLUG_OUT = 105;
    // MTK-END

    // MTK-START
    private static final String ICCID_STRING_FOR_NO_SIM = "N/A";
    // MTK-END
    /**
     *  int[] sInsertSimState maintains all slots' SIM inserted status currently,
     *  it may contain 4 kinds of values:
     *    SIM_NOT_INSERT : no SIM inserted in slot i now
     *    SIM_CHANGED    : a valid SIM insert in slot i and is different SIM from last time
     *                     it will later become SIM_NEW or SIM_REPOSITION during update procedure
     *    SIM_NOT_CHANGE : a valid SIM insert in slot i and is the same SIM as last time
     *    SIM_NEW        : a valid SIM insert in slot i and is a new SIM
     *    SIM_REPOSITION : a valid SIM insert in slot i and is inserted in different slot last time
     *    positive integer #: index to distinguish SIM cards with the same IccId
     */
    public static final int SIM_NOT_CHANGE = 0;
    public static final int SIM_CHANGED    = -1;
    public static final int SIM_NEW        = -2;
    public static final int SIM_REPOSITION = -3;
    public static final int SIM_NOT_INSERT = -99;

    public static final int STATUS_NO_SIM_INSERTED = 0x00;
    public static final int STATUS_SIM1_INSERTED = 0x01;
    public static final int STATUS_SIM2_INSERTED = 0x02;
    public static final int STATUS_SIM3_INSERTED = 0x04;
    public static final int STATUS_SIM4_INSERTED = 0x08;

    // Key used to read/write the current IMSI. Updated on SIM_STATE_CHANGED - LOADED.
    public static final String CURR_SUBID = "curr_subid";

    private static Phone[] mPhone;
    private static Context mContext = null;
    // MTK-START
    private CommandsInterface[] mCis = null;
    private static IccFileHandler[] sFh = new IccFileHandler[PROJECT_SIM_NUM];
    // MTK-END
    private static String mIccId[] = new String[PROJECT_SIM_NUM];
    private static int[] mInsertSimState = new int[PROJECT_SIM_NUM];
    private SubscriptionManager mSubscriptionManager = null;
    private IPackageManager mPackageManager;
    // The current foreground user ID.
    private int mCurrentlyActiveUserId;
    private CarrierServiceBindHelper mCarrierServiceBindHelper;
    // MTK-START
    private static int[] sIsUpdateAvailable = new int[PROJECT_SIM_NUM];
    // To prevent repeatedly update flow every time receiver SIM_STATE_CHANGE
    protected AtomicReferenceArray<IccRecords> mIccRecords
            = new AtomicReferenceArray<IccRecords>(PROJECT_SIM_NUM);
    private static final int sReadICCID_retry_time = 1000;
    private int mReadIccIdCount = 0;
    protected final Object mLock = new Object();

    static String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };

    // For the feature SIM Hot Swap with Common Slot
    private static final String COMMON_SLOT_PROPERTY = "ro.mtk_sim_hot_swap_common_slot";
    private static boolean mCommonSlotResetDone = false;
    private static final boolean MTK_FLIGHTMODE_POWEROFF_MD_SUPPORT
            = "1".equals(SystemProperties.get("ro.mtk_flight_mode_power_off_md"));
    // MTK-END
    public SubscriptionInfoUpdater(Context context, Phone[] phoneProxy, CommandsInterface[] ci) {
        logd("Constructor invoked");

        mContext = context;
        mPhone = phoneProxy;
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

        // MTK-START
        mCis = ci;

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sIsUpdateAvailable[i] = 0;
            mIccId[i] = SystemProperties.get(PROPERTY_ICCID_SIM[i], "");
            if (mIccId[i].length() == 3) {
                logd("No SIM insert :" + i);
            }
            logd("mIccId[" + i + "]:" + mIccId[i]);
        }

        if (isAllIccIdQueryDone()) {
            new Thread() {
                public void run() {
                    updateSubscriptionInfoByIccId();
                }
            } .start();
        }
        // MTK-END

        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(IccCardProxy.ACTION_INTERNAL_SIM_STATE_CHANGED);
        // MTK-START
        intentFilter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        intentFilter.addAction(TelephonyIntents.ACTION_COMMON_SLOT_NO_CHANGED);
        if ("OP09".equals(SystemProperties.get("ro.operator.optr"))
                && ("SEGDEFAULT".equals(SystemProperties.get("ro.operator.seg"))
                    || "SEGC".equals(SystemProperties.get("ro.operator.seg")))) {
            intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        }

        for (int i = 0; i < mCis.length; i++) {
            Integer index = new Integer(i);
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            mCis[i].registerForAvailable(this, EVENT_RADIO_AVAILABLE, index);
            if (SystemProperties.get(COMMON_SLOT_PROPERTY).equals("1")) {
                mCis[i].registerForTrayPlugIn(this, EVENT_TRAY_PLUG_IN, index);
                mCis[i].registerForSimPlugOut(this, EVENT_SIM_PLUG_OUT, index);
            }
        }
        // MTK-END
        mContext.registerReceiver(sReceiver, intentFilter);

        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        initializeCarrierApps();
    }

    private void initializeCarrierApps() {
        // Initialize carrier apps:
        // -Now (on system startup)
        // -Whenever new carrier privilege rules might change (new SIM is loaded)
        // -Whenever we switch to a new user
        mCurrentlyActiveUserId = 0;
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                @Override
                public void onUserSwitching(int newUserId, IRemoteCallback reply)
                        throws RemoteException {
                    mCurrentlyActiveUserId = newUserId;
                    CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                            mPackageManager, TelephonyManager.getDefault(), mCurrentlyActiveUserId);

                    if (reply != null) {
                        try {
                            reply.sendResult(null);
                        } catch (RemoteException e) {
                        }
                    }
                }

                @Override
                public void onUserSwitchComplete(int newUserId) {
                    // Ignore.
                }

                @Override
                public void onForegroundProfileSwitch(int newProfileId) throws RemoteException {
                    // Ignore.
                }
            });
            mCurrentlyActiveUserId = ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            logd("Couldn't get current user ID; guessing it's 0: " + e.getMessage());
        }
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                mPackageManager, TelephonyManager.getDefault(), mCurrentlyActiveUserId);
    }

    private final BroadcastReceiver sReceiver = new  BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logd("[Receiver]+");
            String action = intent.getAction();
            logd("Action: " + action);

            if (!action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED) &&
                !action.equals(IccCardProxy.ACTION_INTERNAL_SIM_STATE_CHANGED) &&
                // MTK-START
                !action.equals("android.intent.action.ACTION_SHUTDOWN_IPO") &&
                !action.equals(TelephonyIntents.ACTION_COMMON_SLOT_NO_CHANGED) &&
                !action.equals(Intent.ACTION_LOCALE_CHANGED)) {
                // MTK-END
                return;
            }

            int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                    SubscriptionManager.INVALID_SIM_SLOT_INDEX);
            logd("slotId: " + slotId);
            if (slotId == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                // MTK-START
                // Other MTK intents might has invalid sim slot index, such as IPO related intents.
                if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED) ||
                        action.equals(IccCardProxy.ACTION_INTERNAL_SIM_STATE_CHANGED))
                // MTK-END
                return;
            }

            String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            logd("simStatus: " + simStatus);

            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
                    sendMessage(obtainMessage(EVENT_SIM_ABSENT, slotId, -1));
                } else if (IccCardConstants.INTENT_VALUE_ICC_UNKNOWN.equals(simStatus)) {
                    sendMessage(obtainMessage(EVENT_SIM_UNKNOWN, slotId, -1));
                } else if (IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(simStatus)) {
                    sendMessage(obtainMessage(EVENT_SIM_IO_ERROR, slotId, -1));
                // MTK-START
                } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)) {
                    sendMessage(obtainMessage(EVENT_SIM_READY, slotId, -1));
                // MTK-END
                } else {
                    logd("Ignoring simStatus: " + simStatus);
                }
            } else if (action.equals(IccCardProxy.ACTION_INTERNAL_SIM_STATE_CHANGED)) {
                if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)) {
                    String reason = intent.getStringExtra(
                        IccCardConstants.INTENT_KEY_LOCKED_REASON);
                    sendMessage(obtainMessage(EVENT_SIM_LOCKED, slotId, -1, reason));
                } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
                    sendMessage(obtainMessage(EVENT_SIM_LOADED, slotId, -1));
                    // MTK-START
                    mReadIccIdCount = 10;
                    // MTK-END
                } else {
                    logd("Ignoring simStatus: " + simStatus);
                }
            // MTK-START
            } else if (action.equals("android.intent.action.ACTION_SHUTDOWN_IPO")) {
                for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                    clearIccId(i);
                }
                mSubscriptionManager.clearSubscriptionInfo();
                SubscriptionController.getInstance().removeStickyIntent();
            } else if (action.equals(Intent.ACTION_LOCALE_CHANGED)) {
                int[] subIdList = mSubscriptionManager.getActiveSubscriptionIdList();
                for (int subId : subIdList) {
                    updateSubName(subId);
                }
            } else if (action.equals(TelephonyIntents.ACTION_COMMON_SLOT_NO_CHANGED)) {
                slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                logd("[Common Slot] NO_CHANTED, slotId: " + slotId);
                sendMessage(obtainMessage(EVENT_SIM_NO_CHANGED, slotId, -1));
            }
            // MTK-END
            logd("[Receiver]-");
        }
    };

    private boolean isAllIccIdQueryDone() {
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            // MTK-START
            if (mIccId[i] == null || mIccId[i].equals("")) {
            // MTK-END
                logd("Wait for SIM" + (i + 1) + " IccId");
                return false;
            }
        }
        logd("All IccIds query complete");

        return true;
    }

    public void setDisplayNameForNewSub(String newSubName, int subId, int newNameSource) {
        SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
        if (subInfo != null) {
            // overwrite SIM display name if it is not assigned by user
            int oldNameSource = subInfo.getNameSource();
            CharSequence oldSubName = subInfo.getDisplayName();
            logd("[setDisplayNameForNewSub] subId = " + subInfo.getSubscriptionId()
                    + ", oldSimName = " + oldSubName + ", oldNameSource = " + oldNameSource
                    + ", newSubName = " + newSubName + ", newNameSource = " + newNameSource);
            if (oldSubName == null ||
                (oldNameSource ==
                    SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE && newSubName != null) ||
                (oldNameSource == SubscriptionManager.NAME_SOURCE_SIM_SOURCE && newSubName != null
                        && !newSubName.equals(oldSubName))) {
                mSubscriptionManager.setDisplayName(newSubName, subInfo.getSubscriptionId(),
                        newNameSource);
            }
        } else {
            logd("SUB" + (subId + 1) + " SubInfo not created yet");
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_SIM_LOCKED_QUERY_ICCID_DONE: {
                AsyncResult ar = (AsyncResult)msg.obj;
                QueryIccIdUserObj uObj = (QueryIccIdUserObj) ar.userObj;
                int slotId = uObj.slotId;
                logd("handleMessage : <EVENT_SIM_LOCKED_QUERY_ICCID_DONE> SIM" + (slotId + 1));
                if (ar.exception == null) {
                    if (ar.result != null) {
                        byte[] data = (byte[])ar.result;
                        // MTK-START ???
                        //mIccId[slotId] = IccUtils.bcdToString(data, 0, data.length);
                        mIccId[slotId] = IccUtils.parseIccIdToString(data, 0, data.length);
                        // MTK-END
                    } else {
                        logd("Null ar");
                        mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    }
                } else {
                    // MTK-START ???
                    if (ar.exception instanceof CommandException &&
                        ((CommandException) (ar.exception)).getCommandError() ==
                                CommandException.Error.RADIO_NOT_AVAILABLE) {
                        mIccId[slotId] = "";
                    } else {
                    // MTK-END
                        mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    // MTK-START
                    }
                    // MTK-END
                    logd("Query IccId fail: " + ar.exception);
                }
                logd("sIccId[" + slotId + "] = " + mIccId[slotId]);
                if (isAllIccIdQueryDone()) {
                    updateSubscriptionInfoByIccId();
                }
                broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED,
                                         uObj.reason);
                if (!ICCID_STRING_FOR_NO_SIM.equals(mIccId[slotId])) {
                    updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED);
                }
                // MTK-START
                //if (isAllIccIdQueryDone()) {
                //    updateSubscriptionInfoByIccId();
                //}
                //broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED,
                //                     uObj.reason);
                SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                        new QueryIccIdUserObj(uObj.reason, slotId),
                        SubscriptionUpdatorThread.SIM_LOCKED);
                updatorThread.start();
                // MTK-END
                break;
            }
            // MTK-START
            case EVENT_RADIO_UNAVAILABLE:
                Integer index = getCiIndex(msg);
                logd("handleMessage : <EVENT_RADIO_UNAVAILABLE> SIM" + (index + 1));
                sIsUpdateAvailable[index] = 0;
                if (SystemProperties.get(COMMON_SLOT_PROPERTY).equals("1")) {
                    logd("[Common slot] reset mCommonSlotResetDone in EVENT_RADIO_UNAVAILABLE");
                    mCommonSlotResetDone = false;
                }
                break;
            case EVENT_RADIO_AVAILABLE:
                index = getCiIndex(msg);
                logd("handleMessage : <EVENT_RADIO_AVAILABLE> SIM" + (index + 1));
                sIsUpdateAvailable[index] = 1;

                if (checkIsAvailable()) {
                    mReadIccIdCount = 0;
                    if (!checkAllIccIdReady()) {
                        postDelayed(mReadIccIdPropertyRunnable, sReadICCID_retry_time);
                    } else {
                        updateSubscriptionInfoIfNeed();
                    }
                }
                break;
            // MTK-END
            case EVENT_GET_NETWORK_SELECTION_MODE_DONE: {
                AsyncResult ar = (AsyncResult)msg.obj;
                Integer slotId = (Integer)ar.userObj;
                if (ar.exception == null && ar.result != null) {
                    int[] modes = (int[])ar.result;
                    if (modes[0] == 1) {  // Manual mode.
                        mPhone[slotId].setNetworkSelectionModeAutomatic(null);
                    }
                } else {
                    logd("EVENT_GET_NETWORK_SELECTION_MODE_DONE: error getting network mode.");
                }
                break;
            }

            case EVENT_SIM_LOADED: {
                // MTK-START
                //handleSimLoaded(msg.arg1);

                // Execute updateSubscriptionInfoByIccId by another thread might cause
                // broadcast intent sent before update done.
                // Need to make updateSubscriptionInfoByIccId and send broadcast as a wrapper
                // with the same thread to avoid broadcasting before update done.
                SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                        new QueryIccIdUserObj(null, msg.arg1),
                        SubscriptionUpdatorThread.SIM_LOADED);
                updatorThread.start();
                // MTK-END
                break;
            }
            case EVENT_SIM_ABSENT: {
                // MTK-START
                //handleSimAbsent(msg.arg1);
                SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                        new QueryIccIdUserObj(null, msg.arg1),
                        SubscriptionUpdatorThread.SIM_ABSENT);
                updatorThread.start();
                // MTK-END
                break;
            }
            case EVENT_SIM_LOCKED:
                handleSimLocked(msg.arg1, (String) msg.obj);
                break;

            case EVENT_SIM_UNKNOWN:
                updateCarrierServices(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_UNKNOWN);
                break;

            case EVENT_SIM_IO_ERROR:
                updateCarrierServices(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR);
                break;

            // MTK-START
            case EVENT_SIM_READY: {
                SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                        new QueryIccIdUserObj(null, msg.arg1),
                        SubscriptionUpdatorThread.SIM_READY);
                updatorThread.start();
                break;
            }
            case EVENT_SIM_NO_CHANGED: {
                SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                        new QueryIccIdUserObj(null, msg.arg1),
                        SubscriptionUpdatorThread.SIM_NO_CHANGED);
                updatorThread.start();
                break;
            }

            case EVENT_TRAY_PLUG_IN: {
                logd("[Common Slot] handle EVENT_TRAY_PLUG_IN " + mCommonSlotResetDone);
                if (!mCommonSlotResetDone) {
                    mCommonSlotResetDone = true;
                    for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                        mIccId[i] = "";
                    }
                }
                break;
            }
            case EVENT_SIM_PLUG_OUT: {
                logd("[Common Slot] handle EVENT_SIM_PLUG_OUT " + mCommonSlotResetDone);
                mCommonSlotResetDone = false;
                break;
            }
            // MTK-END
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private static class QueryIccIdUserObj {
        public String reason;
        public int slotId;

        QueryIccIdUserObj(String reason, int slotId) {
            this.reason = reason;
            this.slotId = slotId;
        }
    };

    // MTK-START
    private class SubscriptionUpdatorThread extends Thread {
        public static final int SIM_ABSENT = 0;
        public static final int SIM_LOADED = 1;
        public static final int SIM_LOCKED = 2;
        public static final int SIM_READY  = 3;
        public static final int SIM_NO_CHANGED = 4;

        private QueryIccIdUserObj mUserObj;
        private int mEventId;

        SubscriptionUpdatorThread(QueryIccIdUserObj userObj, int eventId) {
            mUserObj = userObj;
            mEventId = eventId;
        }

        @Override
        public void run() {
            switch (mEventId) {
                case SIM_ABSENT:
                    handleSimAbsent(mUserObj.slotId);
                    break;

                case SIM_LOADED:
                    handleSimLoaded(mUserObj.slotId);
                    break;

                case SIM_LOCKED:
                    if (isAllIccIdQueryDone()) {
                          updateSubscriptionInfoByIccId();
                    }
                    broadcastSimStateChanged(mUserObj.slotId,
                            IccCardConstants.INTENT_VALUE_ICC_LOCKED, mUserObj.reason);
                    break;
                case SIM_READY:
                    if (checkAllIccIdReady()) {
                        updateSubscriptionInfoIfNeed();
                    }
                    break;
                case SIM_NO_CHANGED:
                    logd("[Common Slot]SubscriptionUpdatorThread run for SIM_NO_CHANGED.");
                    //mIccId[mUserObj.slotId] = ICCID_STRING_FOR_NO_SIM;
                    if (checkAllIccIdReady()) {
                        updateSubscriptionInfoIfNeed();
                    } else {
                        mIccId[mUserObj.slotId] = ICCID_STRING_FOR_NO_SIM;
                        logd("case SIM_NO_CHANGED: set N/A for slot" + mUserObj.slotId);
                    }
                    break;
                default:
                    logd("SubscriptionUpdatorThread run with invalid event id.");
                    break;
            }
        }
    };
    // MTK-END

    private void handleSimLocked(int slotId, String reason) {
        // MTK-START
        // [ALPS01981366] Since MTK add new thread for updateSubscriptionInfoByIccId,
        // it might cause NullPointerException if we set mIccId to null without synchronized block.
        synchronized (mLock) {
        // MTK-END
        if (mIccId[slotId] != null && mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " hot plug in");
            mIccId[slotId] = null;
        }


        IccFileHandler fileHandler = mPhone[slotId].getIccCard() == null ? null :
                mPhone[slotId].getIccCard().getIccFileHandler();

        if (fileHandler != null) {
            String iccId = mIccId[slotId];
            // MTK-START
            if (iccId == null || iccId.equals("")) {
                // [ALPS02006863]
                // 1.Execute updateSubscriptionInfoByIccId by another thread might cause
                //   broadcast intent sent before update done.
                //   Need to make updateSubscriptionInfoByIccId and send broadcast as a wrapper
                //   with the same thread to avoid broadcasting before update done.
                // 2.Use Icc id system property istead SIM IO to query to enhance
                //   update database performance.
                mIccId[slotId] = SystemProperties.get(PROPERTY_ICCID_SIM[slotId], "");

                if (mIccId[slotId] != null && !mIccId[slotId].equals("")) {
                    logd("Use Icc ID system property for performance enhancement");
                    SubscriptionUpdatorThread updatorThread = new SubscriptionUpdatorThread(
                            new QueryIccIdUserObj(reason, slotId),
                            SubscriptionUpdatorThread.SIM_LOCKED);
                    updatorThread.start();
                } else {
            // MTK-END
                    logd("Querying IccId");
                    fileHandler.loadEFTransparent(IccConstants.EF_ICCID,
                            obtainMessage(EVENT_SIM_LOCKED_QUERY_ICCID_DONE,
                                    new QueryIccIdUserObj(reason, slotId)));
            // MTK-START
                }
            // MTK-END

            } else {
                logd("NOT Querying IccId its already set sIccid[" + slotId + "]=" + iccId);
                // MTK-START
                String tempIccid = SystemProperties.get(PROPERTY_ICCID_SIM[slotId], "");
                logd("tempIccid:" + tempIccid + ", mIccId[slotId]:" + mIccId[slotId]);
                if (MTK_FLIGHTMODE_POWEROFF_MD_SUPPORT
                        && !checkAllIccIdReady() && (!tempIccid.equals(mIccId[slotId]))) {
                    logd("All iccids are not ready and iccid changed");
                    mSubscriptionManager.clearSubscriptionInfo();
                }
                // MTK-END
                updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED);
                broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED, reason);
            }
        } else {
            logd("sFh[" + slotId + "] is null, ignore");
            // In the case, SIM card may be removed.
        }
        // MTK-START
        }
        // MTK-END
    }

    private void handleSimLoaded(int slotId) {
        logd("handleSimStateLoadedInternal: slotId: " + slotId);
        // MTK-START
        boolean needUpdate = false;
        // MTK-END

        // The SIM should be loaded at this state, but it is possible in cases such as SIM being
        // removed or a refresh RESET that the IccRecords could be null. The right behavior is to
        // not broadcast the SIM loaded.
        IccRecords records = mPhone[slotId].getIccCard().getIccRecords();
        if (records == null) {  // Possibly a race condition.
            logd("onRecieve: IccRecords null");
            return;
        }
        if (records.getIccId() == null) {
            logd("onRecieve: IccID null");
            return;
        }

        // MTK-START
        String iccId = SystemProperties.get(PROPERTY_ICCID_SIM[slotId], "");
        if (!iccId.equals(mIccId[slotId])) {
            logd("NeedUpdate");
            needUpdate = true;
            mIccId[slotId] = iccId;
        }

        //sIccId[slotId] = records.getIccId();
        // MTK-END

        // MTK-START
        if (isAllIccIdQueryDone() && needUpdate) {
        // MTK-END
            updateSubscriptionInfoByIccId();
        }

        int subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        int[] subIds = SubscriptionController.getInstance().getSubId(slotId);
        if (subIds != null) {   // Why an array?
            subId = subIds[0];
        }

        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            String operator = records.getOperatorNumeric();
            if (operator != null) {
                if (subId == SubscriptionController.getInstance().getDefaultSubId()) {
                    MccTable.updateMccMncConfiguration(mContext, operator, false);
                }
                SubscriptionController.getInstance().setMccMnc(operator,subId);
            } else {
                logd("EVENT_RECORDS_LOADED Operator name is null");
            }
            // MTK-START
            // Shoudn't use getDefault, it will suffer permission issue and can't get
            // line1 number correctly.
            //TelephonyManager tm = TelephonyManager.getDefault();
            TelephonyManager tm = TelephonyManager.from(mContext);
            // MTK-END
            String msisdn = tm.getLine1NumberForSubscriber(subId);
            ContentResolver contentResolver = mContext.getContentResolver();

            if (msisdn != null) {
                // MTK-START
                //ContentValues number = new ContentValues(1);
                //number.put(SubscriptionManager.NUMBER, msisdn);
                //contentResolver.update(SubscriptionManager.CONTENT_URI, number,
                //        SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "="
                //        + Long.toString(subId), null);
                SubscriptionController.getInstance().setDisplayNumber(msisdn, subId);
                // MTK-END
            }

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            String nameToSet;
            String simCarrierName = tm.getSimOperatorNameForSubscription(subId);
            ContentValues name = new ContentValues(1);

            if (subInfo != null && subInfo.getNameSource() !=
                    SubscriptionManager.NAME_SOURCE_USER_INPUT) {
                // MTK-START
                // Take MVNO into account.
                String simNumeric = tm.getSimOperatorNumericForSubscription(
                        subIds[0]);
                String simMvnoName = SpnOverride.getInstance().lookupOperatorNameForDisplayName(
                        subIds[0], simNumeric, true, mContext);
                logd("[handleSimLoaded]- simNumeric: " + simNumeric +
                            ", simMvnoName: " + simMvnoName);
                if (!TextUtils.isEmpty(simMvnoName)) {
                    nameToSet = simMvnoName;
                } else {
                    if (!TextUtils.isEmpty(simCarrierName)) {
                        nameToSet = simCarrierName;
                    } else {
                        nameToSet = "CARD " + Integer.toString(slotId + 1);
                    }
                }
                // MTK-END
                // MTK-START
                //name.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                //logd("sim name = " + nameToSet);
                //contentResolver.update(SubscriptionManager.CONTENT_URI, name,
                //        SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID
                //        + "=" + Long.toString(subId), null);
                mSubscriptionManager.setDisplayName(nameToSet, subId);
                logd("[handleSimLoaded] subId = " + subId + ", sim name = " + nameToSet);
                // MTK-END
            }

            /* Update preferred network type and network selection mode on SIM change.
             * Storing last subId in SharedPreference for now to detect SIM change. */
            SharedPreferences sp =
                    PreferenceManager.getDefaultSharedPreferences(mContext);
            int storedSubId = sp.getInt(CURR_SUBID + slotId, -1);

            if (storedSubId != subId) {
                // MTK-START
                int networkType = Settings.Global.getInt(mPhone[slotId].getContext().getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE + subId,
                        RILConstants.PREFERRED_NETWORK_MODE);
                // MTK-END

                // MTK-START
                // Set the modem network mode
                // MTK network mode logic is central controled by GsmSST
                logd("Possibly a new IMSI. Set sub(" + subId + ") networkType to " + networkType);
                //mPhone[slotId].setPreferredNetworkType(networkType, null);
                // MTK-END

                Settings.Global.putInt(mPhone[slotId].getContext().getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE + subId,
                        networkType);

                // Only support automatic selection mode on SIM change.
                mPhone[slotId].getNetworkSelectionMode(
                        obtainMessage(EVENT_GET_NETWORK_SELECTION_MODE_DONE, new Integer(slotId)));

                // Update stored subId
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt(CURR_SUBID + slotId, subId);
                editor.apply();
            }
        } else {
            logd("Invalid subId, could not update ContentResolver");
        }

        // Update set of enabled carrier apps now that the privilege rules may have changed.
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                mPackageManager, TelephonyManager.getDefault(), mCurrentlyActiveUserId);

        broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_LOADED, null);
        updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_LOADED);
    }

    private void updateCarrierServices(int slotId, String simState) {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        configManager.updateConfigForPhoneId(slotId, simState);
        mCarrierServiceBindHelper.updateForPhoneId(slotId, simState);
    }

    private void handleSimAbsent(int slotId) {
        if (mIccId[slotId] != null && !mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " hot plug out");
        }

        // MTK-START
        // If card inserted state no changed, no need to update.
        // Return directly to avoid unneccessary update cause timing issue.
        if (mIccId[slotId] != null && mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " absent - card state no changed.");
            updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_ABSENT);
            return;
        }
        // MTK-END

        // MTK-START
        if (SystemProperties.get(COMMON_SLOT_PROPERTY).equals("1")) {
            if (checkAllIccIdReady()) {
                updateSubscriptionInfoIfNeed();
            }
        } else {
            mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
            if (isAllIccIdQueryDone()) {
                updateSubscriptionInfoByIccId();
            }
        }
        // MTK-END
        updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_ABSENT);
    }

    /**
     * TODO: Simplify more, as no one is interested in what happened
     * only what the current list contains.
     */
    synchronized private void updateSubscriptionInfoByIccId() {
    // MTK-START
    synchronized (mLock) {
    // MTK-END
        logd("updateSubscriptionInfoByIccId:+ Start");

        // MTK-START
        // ALPS01933839 timing issue, JE after receiving IPO shutdown
        // do this update
        if (!isAllIccIdQueryDone()) {
            return;
        }
        // MTK-END

        mSubscriptionManager.clearSubscriptionInfo();

        // Reset the flag because all sIccId are ready.
        mCommonSlotResetDone = false;

        // MTK-START
        int simState = 0;
        boolean skipCapabilitySwitch = false;
        // MTK-END
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            mInsertSimState[i] = SIM_NOT_CHANGE;
            // MTK-START
            simState = TelephonyManager.from(mContext).getSimState(i);
            if (simState == TelephonyManager.SIM_STATE_PIN_REQUIRED ||
                simState == TelephonyManager.SIM_STATE_PUK_REQUIRED ||
                simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED ||
                simState == TelephonyManager.SIM_STATE_NOT_READY) {
                logd("skipCapabilitySwitch = " + skipCapabilitySwitch);
                skipCapabilitySwitch = true;
            }
            // MTK-END
        }

        int insertedSimCount = PROJECT_SIM_NUM;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (ICCID_STRING_FOR_NO_SIM.equals(mIccId[i])) {
                insertedSimCount--;
                mInsertSimState[i] = SIM_NOT_INSERT;
            }
        }
        logd("insertedSimCount = " + insertedSimCount);

        int index = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (mInsertSimState[i] == SIM_NOT_INSERT) {
                continue;
            }
            index = 2;
            for (int j = i + 1; j < PROJECT_SIM_NUM; j++) {
                if (mInsertSimState[j] == SIM_NOT_CHANGE && mIccId[i].equals(mIccId[j])) {
                    mInsertSimState[i] = 1;
                    mInsertSimState[j] = index;
                    index++;
                }
            }
        }

        ContentResolver contentResolver = mContext.getContentResolver();
        String[] oldIccId = new String[PROJECT_SIM_NUM];
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            oldIccId[i] = null;
            List<SubscriptionInfo> oldSubInfo =
                    SubscriptionController.getInstance().getSubInfoUsingSlotIdWithCheck(i, false,
                    mContext.getOpPackageName());
            if (oldSubInfo != null) {
                oldIccId[i] = oldSubInfo.get(0).getIccId();
                logd("updateSubscriptionInfoByIccId: oldSubId = "
                        + oldSubInfo.get(0).getSubscriptionId());
                if (mInsertSimState[i] == SIM_NOT_CHANGE && !mIccId[i].equals(oldIccId[i])) {
                    mInsertSimState[i] = SIM_CHANGED;
                }
                if (mInsertSimState[i] != SIM_NOT_CHANGE) {
                    ContentValues value = new ContentValues(1);
                    value.put(SubscriptionManager.SIM_SLOT_INDEX,
                            SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                    contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "="
                            + Integer.toString(oldSubInfo.get(0).getSubscriptionId()), null);
                }
            } else {
                if (mInsertSimState[i] == SIM_NOT_CHANGE) {
                    // no SIM inserted last time, but there is one SIM inserted now
                    mInsertSimState[i] = SIM_CHANGED;
                }
                oldIccId[i] = ICCID_STRING_FOR_NO_SIM;
                logd("updateSubscriptionInfoByIccId: No SIM in slot " + i + " last time");
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            logd("updateSubscriptionInfoByIccId: oldIccId[" + i + "] = " + oldIccId[i] +
                    ", sIccId[" + i + "] = " + mIccId[i]);
        }

        //check if the inserted SIM is new SIM
        int nNewCardCount = 0;
        int nNewSimStatus = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (mInsertSimState[i] == SIM_NOT_INSERT) {
                logd("updateSubscriptionInfoByIccId: No SIM inserted in slot " + i + " this time");
            } else {
                if (mInsertSimState[i] > 0) {
                    //some special SIMs may have the same IccIds, add suffix to distinguish them
                    //FIXME: addSubInfoRecord can return an error.
                    mSubscriptionManager.addSubscriptionInfoRecord(mIccId[i]
                            + Integer.toString(mInsertSimState[i]), i);
                    logd("SUB" + (i + 1) + " has invalid IccId");
                } else /*if (sInsertSimState[i] != SIM_NOT_INSERT)*/ {
                    mSubscriptionManager.addSubscriptionInfoRecord(mIccId[i], i);
                }
                if (isNewSim(mIccId[i], oldIccId)) {
                    nNewCardCount++;
                    switch (i) {
                        case PhoneConstants.SUB1:
                            nNewSimStatus |= STATUS_SIM1_INSERTED;
                            break;
                        case PhoneConstants.SUB2:
                            nNewSimStatus |= STATUS_SIM2_INSERTED;
                            break;
                        case PhoneConstants.SUB3:
                            nNewSimStatus |= STATUS_SIM3_INSERTED;
                            break;
                        //case PhoneConstants.SUB3:
                        //    nNewSimStatus |= STATUS_SIM4_INSERTED;
                        //    break;
                    }

                    mInsertSimState[i] = SIM_NEW;
                }
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (mInsertSimState[i] == SIM_CHANGED) {
                mInsertSimState[i] = SIM_REPOSITION;
            }
            logd("updateSubscriptionInfoByIccId: sInsertSimState[" + i + "] = "
                    + mInsertSimState[i]);
        }

        List<SubscriptionInfo> subInfos = mSubscriptionManager.getActiveSubscriptionInfoList();
        int nSubCount = (subInfos == null) ? 0 : subInfos.size();
        logd("updateSubscriptionInfoByIccId: nSubCount = " + nSubCount);
        for (int i=0; i < nSubCount; i++) {
            SubscriptionInfo temp = subInfos.get(i);

            // MTK-START
            // Shoudn't use getDefault, it will suffer permission issue and can't get
            // line1 number correctly.
            //String msisdn = TelephonyManager.getDefault().getLine1NumberForSubscriber(
            //        temp.getSubscriptionId());
            String msisdn = TelephonyManager.from(mContext).getLine1NumberForSubscriber(
                    temp.getSubscriptionId());
            // MTK-END

            if (msisdn != null) {
                // MTK-START
                //ContentValues value = new ContentValues(1);
                //value.put(SubscriptionManager.NUMBER, msisdn);
                //contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                //        SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "="
                //        + Integer.toString(temp.getSubscriptionId()), null);
                SubscriptionController.getInstance().setDisplayNumber(
                        msisdn, temp.getSubscriptionId());
                // MTK-END
            }
        }

        // MTK-START
        setAllDefaultSub(subInfos);

        // true if any slot has no SIM this time, but has SIM last time
        boolean hasSimRemoved = false;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (mIccId[i] != null && mIccId[i].equals(ICCID_STRING_FOR_NO_SIM)
                    && !oldIccId[i].equals(ICCID_STRING_FOR_NO_SIM)) {
                hasSimRemoved = true;
                break;
            }
        }

        Intent intent = null;
        if (nNewCardCount == 0) {
            int i;
            if (hasSimRemoved) {
                // no new SIM, at least one SIM is removed, check if any SIM is repositioned
                for (i = 0; i < PROJECT_SIM_NUM; i++) {
                    if (mInsertSimState[i] == SIM_REPOSITION) {
                        logd("No new SIM detected and SIM repositioned");
                        intent = setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM,
                                nSubCount, nNewSimStatus);
                        break;
                    }
                }
                if (i == PROJECT_SIM_NUM) {
                    // no new SIM, no SIM is repositioned => at least one SIM is removed
                    logd("No new SIM detected and SIM removed");
                    intent = setUpdatedData(SubscriptionManager.EXTRA_VALUE_REMOVE_SIM,
                            nSubCount, nNewSimStatus);
                }
            } else {
                // no SIM is removed, no new SIM, just check if any SIM is repositioned
                for (i = 0; i < PROJECT_SIM_NUM; i++) {
                    if (mInsertSimState[i] == SIM_REPOSITION) {
                        logd("No new SIM detected and SIM repositioned");
                        intent = setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM,
                                nSubCount, nNewSimStatus);
                        break;
                    }
                }
                if (i == PROJECT_SIM_NUM) {
                    // all status remain unchanged
                    logd("[updateSimInfoByIccId] All SIM inserted into the same slot");
                    intent = setUpdatedData(SubscriptionManager.EXTRA_VALUE_NOCHANGE,
                            nSubCount, nNewSimStatus);
                }
            }
        } else {
            logd("New SIM detected");
            intent = setUpdatedData(SubscriptionManager.EXTRA_VALUE_NEW_SIM, nSubCount, nNewSimStatus);
        }
        // MTK-END

        // MTK-START
        if (!skipCapabilitySwitch) {
        // MTK-END
            // Ensure the modems are mapped correctly
            mSubscriptionManager.setDefaultDataSubId(mSubscriptionManager.getDefaultDataSubId());
        // MTK-START
        } else {
            // We only set default data subId here due to capability switch cause RADIO_UNAVAILABLE
            // on our platform. Radio module will do capability switch after
            mSubscriptionManager.setDefaultDataSubIdWithoutCapabilitySwitch(
                    mSubscriptionManager.getDefaultDataSubId());
        }
        // MTK-END

        // MTK-START
        //SubscriptionController.getInstance().notifySubscriptionInfoChanged();
        SubscriptionController.getInstance().notifySubscriptionInfoChanged(intent);
        // MTK-END
        logd("updateSubscriptionInfoByIccId:- SsubscriptionInfo update complete");
    // MTK-STRAT
    }
    // MTK-END
    }

    // MTK-START
    private Intent setUpdatedData(int detectedType, int subCount, int newSimStatus) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);

        logd("[setUpdatedData]+ ");

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_NEW_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
            intent.putExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, newSimStatus);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REMOVE_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_REMOVE_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_NOCHANGE);
        }

        logd("[setUpdatedData]- [" + detectedType + ", " + subCount + ", " + newSimStatus + "]");
        return intent;
    }
    // MTK-END

    private boolean isNewSim(String iccId, String[] oldIccId) {
        boolean newSim = true;
        for(int i = 0; i < PROJECT_SIM_NUM; i++) {
            // MTK-START
            // Modify for special SIMs have the same IccIds
            if (iccId != null && oldIccId[i] != null) {
                if (oldIccId[i].indexOf(iccId) == 0) {
                    newSim = false;
                    break;
                }
            }
            // MTK-END
        }
        logd("newSim = " + newSim);

        return newSim;
    }

    private void broadcastSimStateChanged(int slotId, String state, String reason) {
        Intent i = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        // TODO - we'd like this intent to have a single snapshot of all sim state,
        // but until then this should not use REPLACE_PENDING or we may lose
        // information
        // i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
        //         | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        i.putExtra(PhoneConstants.PHONE_NAME_KEY, "Phone");
        i.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE, state);
        i.putExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON, reason);
        SubscriptionManager.putPhoneIdAndSubIdExtra(i, slotId);
        logd("Broadcasting intent ACTION_SIM_STATE_CHANGED " +
             state  + " reason " + reason +
             " for mCardIndex : " + slotId);
        ActivityManagerNative.broadcastStickyIntent(i, READ_PHONE_STATE,
                UserHandle.USER_ALL);
    }

    public void dispose() {
        logd("[dispose]");
        mContext.unregisterReceiver(sReceiver);
    }

    private void logd(String message) {
        Rlog.d(LOG_TAG, message);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SubscriptionInfoUpdater:");
        mCarrierServiceBindHelper.dump(fd, pw, args);
    }

    // MTK-START
    private void setAllDefaultSub(List<SubscriptionInfo> subInfos) {
        logd("[setAllDefaultSub]+ ");
        DefaultSmsSimSettings.setSmsTalkDefaultSim(subInfos, mContext);
        logd("[setSmsTalkDefaultSim]- ");
        DefaultVoiceCallSubSettings.setVoiceCallDefaultSub(subInfos);
        logd("[setVoiceCallDefaultSub]- ");
    }

    private void clearIccId(int slotId) {
        synchronized (mLock) {
            logd("[clearIccId], slotId = " + slotId);
            sFh[slotId] = null;
            mIccId[slotId] = null;
        }
    }

    private Runnable mReadIccIdPropertyRunnable = new Runnable() {
        public void run() {
            ++mReadIccIdCount;
            if (mReadIccIdCount <= 10) {
                if (!checkAllIccIdReady()) {
                    postDelayed(mReadIccIdPropertyRunnable, sReadICCID_retry_time);
                } else {
                    updateSubscriptionInfoIfNeed();
                }
            }
        }
    };

    private boolean checkAllIccIdReady() {
        String iccId = "";
        logd("checkAllIccIdReady +, retry_count = " + mReadIccIdCount);
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            iccId = SystemProperties.get(PROPERTY_ICCID_SIM[i], "");
            if (iccId.length() == 3) {
                logd("No SIM insert :" + i);
            }
            if (iccId == null || iccId.equals("")) {
                return false;
            }
            logd("iccId[" + i + "] = " + iccId);
        }

        return true;
    }

    private void updateSubscriptionInfoIfNeed() {
        logd("[updateSubscriptionInfoIfNeed]+");
        boolean needUpdate = false;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (mIccId[i] == null ||
                    !mIccId[i].equals(SystemProperties.get(PROPERTY_ICCID_SIM[i], ""))) {
                logd("[updateSubscriptionInfoIfNeed] icc id change, slot[" + i + "]");
                mIccId[i] = SystemProperties.get(PROPERTY_ICCID_SIM[i], "");
                needUpdate = true;
            }
        }

        if (isAllIccIdQueryDone()) {
            if (needUpdate) {
                // MTK-START
                new Thread() {
                    public void run() {
                // MTK-END
                        updateSubscriptionInfoByIccId();
                // MTK-START
                    }
                } .start();
                // MTK-END
            }
        }
        logd("[updateSubscriptionInfoIfNeed]- return: " + needUpdate);
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer) msg.obj;
            } else if (msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer) ar.userObj;
                }
            }
        }
        return index;
    }

    private boolean checkIsAvailable() {
        boolean result = true;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIsUpdateAvailable[i] <= 0) {
                logd("sIsUpdateAvailable[" + i + "] = " + sIsUpdateAvailable[i]);
                result = false;
                break;
            }
        }
        logd("checkIsAvailable result = " + result);
        return result;
    }

    private void updateSubName(int subId) {
        SubscriptionInfo subInfo =
                mSubscriptionManager.getSubscriptionInfo(subId);
        if (subInfo != null
                && subInfo.getNameSource() != SubscriptionManager.NAME_SOURCE_USER_INPUT) {
            SpnOverride spnOverride = SpnOverride.getInstance();
            String nameToSet;
            String carrierName = TelephonyManager.getDefault().getSimOperator(subId);
            int slotId = SubscriptionManager.getSlotId(subId);
            logd("updateSubName, carrierName = " + carrierName + ", subId = " + subId);
            if (SubscriptionManager.isValidSlotId(slotId)) {
                if (spnOverride.containsCarrierEx(carrierName)) {
                    nameToSet = spnOverride.lookupOperatorName(subId, carrierName,
                        true, mContext);
                    logd("SPN found, name = " + nameToSet);
                } else {
                    nameToSet = "CARD " + Integer.toString(slotId + 1);
                    logd("SPN not found, set name to " + nameToSet);
                }
                mSubscriptionManager.setDisplayName(nameToSet, subId);
            }
        }
    }
    // MTK-END
}

