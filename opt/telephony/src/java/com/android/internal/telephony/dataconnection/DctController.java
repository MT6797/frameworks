/*
 * Copyright (C) 2014 The Android Open Source Project
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2014 MediaTek Inc.
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

package com.android.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.MultiSimVariants;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.SparseArray;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.RequestInfo;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;

import com.mediatek.internal.telephony.dataconnection.DataSubSelector;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
/** M: start */
/** M: end */

public class DctController extends Handler {
    private static final String LOG_TAG = "DctController";
    private static final boolean DBG = true;

    private static final int EVENT_PROCESS_REQUESTS = 100;
    private static final int EVENT_EXECUTE_REQUEST = 101;
    private static final int EVENT_EXECUTE_ALL_REQUESTS = 102;
    private static final int EVENT_RELEASE_REQUEST = 103;
    private static final int EVENT_RELEASE_ALL_REQUESTS = 104;
    private static final int EVENT_RETRY_ATTACH = 105;
    private static final int EVENT_SETTINGS_CHANGED = 106;
    private static final int EVENT_SUBSCRIPTIONS_CHANGED = 107;

    //MTK START
    private static final int EVENT_TRANSIT_TO_ATTACHING = 200;
    private static final int EVENT_CONFIRM_PREDETACH = 201;
    private static final int EVENT_RADIO_AVAILABLE = 202;
    //MTK END

    private static final int EVENT_DATA_ATTACHED = 500;
    private static final int EVENT_DATA_DETACHED = 600;
    private static final int EVENT_EMERGENCY_CALL_TOGGLED = 700;

    //M
    private static final int EVENT_SET_DATA_ALLOWED = 5000;
    private static final int EVENT_RESTORE_PENDING = 5100;
    private static final int EVENT_VOICE_CALL_STARTED = 5200;
    private static final int EVENT_VOICE_CALL_ENDED = 5300;


    /** M: start */
    static final String PROPERTY_RIL_DATA_ICCID = "persist.radio.data.iccid";
    private String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };
    static final String PROPERTY_DATA_ALLOW_SIM = "ril.data.allow";
    static final String PROPERTY_IA_APN_SET_ICCID = "ril.ia.iccid";
    static final String PROPERTY_TEMP_IA = "ril.radio.ia";
    static final String PROPERTY_TEMP_IA_APN = "ril.radio.ia-apn";
    // SVLTE support system property
    static final String MTK_SVLTE_SUPPORT = "ro.mtk_svlte_support";
    // SRLTE support system property
    static final String MTK_SRLTE_SUPPORT = "ro.mtk_srlte_support";
    static final String SUPPORT_YES = "1";
    static final String SUPPORT_NO = "0";
    static final String ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO";
    private static final String INVALID_ICCID = "N/A";
    /** M: end */

    private static DctController sDctController;


    private int mPhoneNum;
    private PhoneProxy[] mPhones;
    private DcSwitchStateMachine[] mDcSwitchStateMachine;
    private DcSwitchAsyncChannel[] mDcSwitchAsyncChannel;
    private Handler[] mDcSwitchStateHandler;
    private HashMap<Integer, RequestInfo> mRequestInfos = new HashMap<Integer, RequestInfo>();
    private Context mContext;

    //M: Pre-Detach Check State
    protected int mUserCnt;
    protected int mTransactionId;

    /** Used to send us NetworkRequests from ConnectivityService.  Remeber it so we can
     * unregister on dispose. */
    private Messenger[] mNetworkFactoryMessenger;
    private NetworkFactory[] mNetworkFactory;
    private NetworkCapabilities[] mNetworkFilter;

    private RegistrantList mNotifyDataSwitchInfo = new RegistrantList();
    private SubscriptionController mSubController = SubscriptionController.getInstance();

    /*private SubscriptionManager mSubMgr;

    private OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            DctController.this.obtainMessage(EVENT_SUBSCRIPTIONS_CHANGED).sendToTarget();
        }
    };*/

    /** M: setup default data sub */
    private DataSubSelector mDataSubSelector;

    /** M: allow data service or not, check setDataAllowed */
    private static boolean mDataAllowed = true;

    // M: The use of return current calling phone id.
    private int mCallingPhone = -1;
    protected ConcurrentHashMap<Handler, DcStateParam> mDcSwitchStateChange
            = new ConcurrentHashMap<Handler, DcStateParam>();
    private Runnable mDataNotAllowedTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            logd("disable data service timeout and enable data service again");
            setDataAllowed(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, true, null, 0);
        }
    };

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            logd("Settings change");
            DctController.this.obtainMessage(EVENT_SETTINGS_CHANGED).sendToTarget();
        }
    };

    public void updatePhoneObject(PhoneProxy phone) {
        if (phone == null) {
            loge("updatePhoneObject phone = null");
            return;
        }

        PhoneBase phoneBase = (PhoneBase)phone.getActivePhone();
        if (phoneBase == null) {
            loge("updatePhoneObject phoneBase = null");
            return;
        }

        logd("updatePhoneObject:" + phone);
        for (int i = 0; i < mPhoneNum; i++) {
            if (mPhones[i] == phone) {
                /// M: Remove NetworkRequests whose specifier is not empty.
                /// ex, There maybe NetworkRequest(like Mms request) in mReqInfos when plugout
                /// and the following flow maybe not normal.
                /// Here we remove the request and when ConnectivityManager reregister
                /// the request will be resend.
                Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
                while (iterator.hasNext()) {
                    RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                    logd("updatePhoneObject requestInfo = " + requestInfo);
                    if (requestInfo != null && getRequestPhoneId(requestInfo.request) == phone.getPhoneId()) {
                        String specifier = requestInfo.request.networkCapabilities.getNetworkSpecifier();
                        logd("updatePhoneObject specifier = " + specifier);
                        if (!TextUtils.isEmpty(specifier)) {
                            iterator.remove();
                        }
                    }
                }
                updatePhoneBaseForIndex(i, phoneBase);
                break;
            }
        }

        // M: for switching phone case, need to re-register
        phoneBase = (PhoneBase) mPhones[0].getActivePhone();
        phoneBase.mCi.unregisterForAvailable(this);
        phoneBase.mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);

    }

    private void updatePhoneBaseForIndex(int index, PhoneBase phoneBase) {
        logd("updatePhoneBaseForIndex for phone index=" + index);

        phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                   EVENT_DATA_ATTACHED + index, null);
        phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                   EVENT_DATA_DETACHED + index, null);
        phoneBase.registerForEmergencyCallToggle(mRspHandler,
                EVENT_EMERGENCY_CALL_TOGGLED + index, null);

        ConnectivityManager cm = (ConnectivityManager)mPhones[index].getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE);

        if (mNetworkFactoryMessenger != null) {
            logd("unregister TelephonyNetworkFactory for phone index=" + index);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[index]);
            mNetworkFactoryMessenger[index] = null;
            mNetworkFactory[index] = null;
            mNetworkFilter[index] = null;
        }

        mNetworkFilter[index] = new NetworkCapabilities();
        mNetworkFilter[index].addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        /** M: start */
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_DM);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_WAP);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_NET);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_RCSE);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_VSIM);
        /** M: end */

        mNetworkFactory[index] = new TelephonyNetworkFactory(this.getLooper(),
                mPhones[index].getContext(), "TelephonyNetworkFactory", phoneBase,
                mNetworkFilter[index]);
        mNetworkFactory[index].setScoreFilter(50);
        mNetworkFactoryMessenger[index] = new Messenger(mNetworkFactory[index]);
        cm.registerNetworkFactory(mNetworkFactoryMessenger[index], "Telephony");

        //M: Register for call state.
        //   We not un-register the events, phone object will be disposed when phone object update,
        //   and will unregister from ci so we will not receive events from it anymore.
        phoneBase.getCallTracker().registerForVoiceCallStarted (mRspHandler,
                EVENT_VOICE_CALL_STARTED + index, null);
        phoneBase.getCallTracker().registerForVoiceCallEnded (mRspHandler,
                EVENT_VOICE_CALL_ENDED + index, null);
    }

    private Handler mRspHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            if (msg.what >= EVENT_VOICE_CALL_ENDED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_VOICE_CALL_ENDED + 1)
                        + "_VOICE_CALL_ENDED.");
                // FIXME: Should take thread safe of mCallingPhone into account in the case of
                //        two incoming voice call at the same time.
                mCallingPhone = -1;
                logd("mCallingPhone = " + mCallingPhone);
                onVoiceCallEnded();
            } else if (msg.what >= EVENT_VOICE_CALL_STARTED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_VOICE_CALL_STARTED + 1)
                        + "_VOICE_CALL_STARTED.");
                // In terms of mCallingPHone,  0 = SIM1 while 1 = SIM2
                mCallingPhone = msg.what - EVENT_VOICE_CALL_STARTED;
                logd("mCallingPhone = " + mCallingPhone);
                onVoiceCallStarted();
            } else if (msg.what >= EVENT_RESTORE_PENDING) {
                logd("EVENT_SIM" + (msg.what - EVENT_RESTORE_PENDING + 1) + "_RESTORE.");
                restorePendingRequest(msg.what - EVENT_RESTORE_PENDING);
            } else if (msg.what >= EVENT_SET_DATA_ALLOWED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_SET_DATA_ALLOWED + 1) + "_SET_DATA_ALLOWED");
                transitToAttachingState(msg.what - EVENT_SET_DATA_ALLOWED);
            } else if (msg.what >= EVENT_EMERGENCY_CALL_TOGGLED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_EMERGENCY_CALL_TOGGLED + 1)
                        + "_EMERGENCY_CALL_END.");
                ar = (AsyncResult) msg.obj;
                Integer toggle = (Integer) ar.result;
                mDcSwitchAsyncChannel[msg.what - EVENT_EMERGENCY_CALL_TOGGLED].
                        notifyEmergencyCallToggled(toggle.intValue());
            } else if (msg.what >= EVENT_DATA_DETACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_DETACHED + 1)
                        + "_DATA_DETACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_DETACHED].notifyDataDetached();

            } else if (msg.what >= EVENT_DATA_ATTACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_ATTACHED + 1) + "_DATA_ATTACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_ATTACHED].notifyDataAttached();
            }
        }
    };

    public static DctController getInstance() {
       if (sDctController == null) {
        throw new RuntimeException(
            "DctController.getInstance can't be called before makeDCTController()");
        }
       return sDctController;
    }

    public static DctController makeDctController(PhoneProxy[] phones) {
        if (sDctController == null) {
            logd("makeDctController: new DctController phones.length=" + phones.length);
            sDctController = new DctController(phones);
        }
        logd("makeDctController: X sDctController=" + sDctController);
        return sDctController;
    }

    private DctController(PhoneProxy[] phones) {
        logd("DctController(): phones.length=" + phones.length);
        if (phones == null || phones.length == 0) {
            if (phones == null) {
                loge("DctController(phones): UNEXPECTED phones=null, ignore");
            } else {
                loge("DctController(phones): UNEXPECTED phones.length=0, ignore");
            }
            return;
        }
        mPhoneNum = phones.length;
        mPhones = phones;

        mDcSwitchStateMachine = new DcSwitchStateMachine[mPhoneNum];
        mDcSwitchAsyncChannel = new DcSwitchAsyncChannel[mPhoneNum];
        mDcSwitchStateHandler = new Handler[mPhoneNum];
        mNetworkFactoryMessenger = new Messenger[mPhoneNum];
        mNetworkFactory = new NetworkFactory[mPhoneNum];
        mNetworkFilter = new NetworkCapabilities[mPhoneNum];

        for (int i = 0; i < mPhoneNum; ++i) {
            int phoneId = i;
            mDcSwitchStateMachine[i] = new DcSwitchStateMachine(mPhones[i],
                    "DcSwitchStateMachine-" + phoneId, phoneId);
            mDcSwitchStateMachine[i].start();
            mDcSwitchAsyncChannel[i] = new DcSwitchAsyncChannel(mDcSwitchStateMachine[i], phoneId);
            mDcSwitchStateHandler[i] = new Handler();

            int status = mDcSwitchAsyncChannel[i].fullyConnectSync(mPhones[i].getContext(),
                mDcSwitchStateHandler[i], mDcSwitchStateMachine[i].getHandler());

            if (status == AsyncChannel.STATUS_SUCCESSFUL) {
                logd("DctController(phones): Connect success: " + i);
            } else {
                loge("DctController(phones): Could not connect to " + i);
            }

            // Register for radio state change
            PhoneBase phoneBase = (PhoneBase)mPhones[i].getActivePhone();
            // M: CI based events doesn't need to update during phone update
            //    since they will share the same RIL.
            // Register for Combine attach {@
            phoneBase.mCi.registerSetDataAllowed(mRspHandler, EVENT_SET_DATA_ALLOWED + i, null);
            // @}
            phoneBase.mCi.registerForSimPlugOut(mRspHandler, EVENT_RESTORE_PENDING + i, null);
            phoneBase.mCi.registerForNotAvailable(mRspHandler, EVENT_RESTORE_PENDING + i, null);
            updatePhoneBaseForIndex(i, phoneBase);
        }

        mContext = mPhones[0].getContext();

        //MTK: Fix google issue, if we use this listener, we will get notify even if carrier change.
        //mSubMgr = SubscriptionManager.from(mContext);
        //mSubMgr.addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHUTDOWN_IPO);
        mContext.registerReceiver(mShutDownIpoIntentReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.registerReceiver(mSubInfoUpdateIntentReceiver, filter);

        // M: C2K SVLTE DSDA support, register Radio Capability change.
        if (isSvlteSupport()) {
            filter = new IntentFilter();
            filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);
            filter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            mContext.registerReceiver(mRadioStateChangeIntentReceiver, filter);
        }

        PhoneBase phoneBase = (PhoneBase) mPhones[0].getActivePhone();
        phoneBase.mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);

        //Register for settings change.
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
                false, mObserver);

        /** M: start */
        mDataSubSelector = new DataSubSelector(mContext, mPhoneNum);

        // M: SVLTE use dynamic attach, keep idle at beginning.
        if (!isSvlteSupport()) {
            setAlwaysAttachSim();
        }
        /** M: end */
    }

    public void dispose() {
        logd("DctController.dispose");
        for (int i = 0; i < mPhoneNum; ++i) {
            ConnectivityManager cm = (ConnectivityManager) mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[i]);
            mNetworkFactoryMessenger[i] = null;

            // M: Register for Combine attach
            PhoneBase phoneBase = (PhoneBase) mPhones[i].getActivePhone();
            phoneBase.mCi.unregisterForNotAvailable(mRspHandler);

            phoneBase.getServiceStateTracker().unregisterForDataConnectionAttached(mRspHandler);
            phoneBase.getServiceStateTracker().unregisterForDataConnectionDetached(mRspHandler);
            phoneBase.mCi.unregisterSetDataAllowed(mRspHandler);
            phoneBase.mCi.unregisterForSimPlugOut(mRspHandler);

            //M: unregisterfor voice call
            phoneBase.getCallTracker().unregisterForVoiceCallStarted(mRspHandler);
            phoneBase.getCallTracker().unregisterForVoiceCallEnded(mRspHandler);
        }

        PhoneBase phoneBase = (PhoneBase) mPhones[0].getActivePhone();
        phoneBase.mCi.unregisterForAvailable(this);

        //MTK: Fix google issue, if we use this listener, we will get notify even if carrier change
        //mSubMgr.removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mContext.unregisterReceiver(mShutDownIpoIntentReceiver);
        mContext.unregisterReceiver(mSubInfoUpdateIntentReceiver);
        // M: C2K SVLTE DSDA support, unregister Radio Capability change.
        if (isSvlteSupport()) {
            mContext.unregisterReceiver(mRadioStateChangeIntentReceiver);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        logd("handleMessage msg=" + msg);
        switch (msg.what) {
            case EVENT_PROCESS_REQUESTS:
                onProcessRequest();
                break;
            case EVENT_EXECUTE_REQUEST:
                onExecuteRequest((RequestInfo) msg.obj);
                break;
            case EVENT_EXECUTE_ALL_REQUESTS:
                onExecuteAllRequests(msg.arg1);
                break;
            case EVENT_RELEASE_REQUEST:
                onReleaseRequest((RequestInfo)msg.obj);
                break;
            case EVENT_RELEASE_ALL_REQUESTS:
                onReleaseAllRequests(msg.arg1);
                break;
            case EVENT_RETRY_ATTACH:
                onRetryAttach(msg.arg1);
                break;
            case EVENT_SETTINGS_CHANGED:
                onSettingsChanged();
                break;
            case EVENT_SUBSCRIPTIONS_CHANGED:
                onSubInfoReady();
                break;

            //MTK-Start
            case EVENT_TRANSIT_TO_ATTACHING:
                int phoneId = (int) msg.arg1;
                logd("EVENT_TRANSIT_TO_ATTACHING: phone" + phoneId);
                transitToAttachingState(phoneId);
                break;
            case EVENT_CONFIRM_PREDETACH:
                logd("EVENT_CONFIRM_PREDETACH");
                handleConfirmPreDetach(msg);
                break;
            case EVENT_RADIO_AVAILABLE:
                if (mSubController.isReady()) {
                    onSubInfoReady();
                }
                break;
            //MTK-End

            default:
                loge("Un-handled message [" + msg.what + "]");
        }
    }

    private int requestNetwork(NetworkRequest request, int priority, LocalLog l, int phoneId
        , int gid) {
        logd("requestNetwork request=" + request
                + ", priority=" + priority + ", gid = " + gid);
        l.log("Dctc.requestNetwork, priority=" + priority);

        RequestInfo requestInfo = new RequestInfo(request, priority, l, phoneId, gid);
        mRequestInfos.put(request.requestId, requestInfo);
        processRequests();

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    private int releaseNetwork(NetworkRequest request) {
        RequestInfo requestInfo = mRequestInfos.get(request.requestId);
        logd("releaseNetwork request=" + request + ", requestInfo=" + requestInfo);
        if (requestInfo != null) requestInfo.log("DctController.releaseNetwork");

        mRequestInfos.remove(request.requestId);
        //VoLTE: ECC without SIM
        String specifier = request.networkCapabilities.getNetworkSpecifier();
        boolean bToAttachingState = false;
        int phoneId = -1;
        if (specifier != null && !specifier.equals("")) {
            int subId =  Integer.parseInt(specifier);
            if (subId < SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                request.networkCapabilities.
                hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
                bToAttachingState = true;
                phoneId = mSubController.getPhoneId(subId);
            }
        }

        releaseRequest(requestInfo);
        processRequests();

        if (bToAttachingState) {
            logd("ECC w/o SIM, disconnectAll to transit to attaching state: "
                + bToAttachingState + ", Set phoneId: " + phoneId + " to attaching state");
            mDcSwitchAsyncChannel[phoneId].disconnectAll();
        }

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    void processRequests() {
        logd("processRequests");
        // remove redundant messages firstly, this situation happens offen.
        removeMessages(EVENT_PROCESS_REQUESTS);
        sendMessage(obtainMessage(EVENT_PROCESS_REQUESTS));
    }

    void executeRequest(RequestInfo request) {
        logd("executeRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_EXECUTE_REQUEST, request));
    }

    void executeAllRequests(int phoneId) {
        logd("executeAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_EXECUTE_ALL_REQUESTS, phoneId, 0));
    }

    void releaseRequest(RequestInfo request) {
        logd("releaseRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_RELEASE_REQUEST, request));
    }

    void releaseAllRequests(int phoneId) {
        logd("releaseAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_RELEASE_ALL_REQUESTS, phoneId, 0));
    }

    public void retryAttach(int phoneId) {
        logd("retryAttach, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_RETRY_ATTACH, phoneId, 0));
    }

    private void onProcessRequest() {
        for (int i = 0; i < getGroupNumbers(); i++) {
            onProcessGroup(i);
        }
    }

    private void onProcessGroup(int group) {
        //process all requests
        //1. Check all requests and find subscription of the top priority
        //   request
        //2. Is current data allowed on the selected subscription
        //2-1. If yes, execute all the requests of the sub
        //2-2. If no, set data not allow on the current PS subscription
        //2-2-1. Set data allow on the selected subscription

        int phoneId = getTopPriorityRequestPhoneId(group);
        int activePhoneId = -1;

        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (getGroupId(i) == group && !mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        logd("onProcessGroup phoneId=" + phoneId + ", groupId=" + group
                + ", activePhoneId=" + activePhoneId);

        /** M: handle data not allowed that all state should be set to IDLD */
        if (mDataAllowed) {
            if (activePhoneId == -1 || activePhoneId == phoneId) {
                Iterator<Integer> iterator = mRequestInfos.keySet().iterator();

                if (activePhoneId == -1 && !iterator.hasNext()) {
                    logd("No active phone, set phone" + phoneId + " to attaching state");
                    transitToAttachingState(phoneId);
                }

                while (iterator.hasNext()) {
                    RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                    if (requestInfo != null && requestInfo.phoneId == phoneId
                            && requestInfo.mGId == group
                            && !requestInfo.executed) {
                        mDcSwitchAsyncChannel[phoneId].connect(requestInfo);
                    }
                }
            } else {
                mDcSwitchAsyncChannel[activePhoneId].disconnectAll();
            }
        } else {
            if (activePhoneId != -1) {
                logd("onProcessRequest data is not allowed, release all requests");
                onReleaseAllRequests(activePhoneId);
            } else {
                logd("onProcessRequest data is not allowed and already in IDLE state");
            }
        }
    }

    private void onExecuteRequest(RequestInfo requestInfo) {

        //MTK: Fix a timing issue if we already restore the request to pending queue.
        // Check the request which we want to execute is still in the requestInfo or not.
        //if (!requestInfo.executed && mRequestInfos.containsKey(requestInfo.request.requestId)) {
        if (needExecuteRequest(requestInfo)) {
            //MTK Fix google issue
            //requestInfo.executed = true;
            logd("onExecuteRequest request=" + requestInfo);
            requestInfo.log("DctController.onExecuteRequest - executed=" + requestInfo.executed);
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = requestInfo.phoneId;
            //TODO: Remove?
            if (!SubscriptionManager.isValidPhoneId(phoneId)) {
                logd("onExecuteRequest invalid phoneId:" + phoneId);
                return;
            }
            logd("onExecuteRequest apn = " + apn + " phoneId=" + phoneId);

            PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.incApnRefCount(apn, requestInfo.getLog());
            requestInfo.executed = true;
        }
    }

    private void onExecuteAllRequests(int phoneId) {
        logd("onExecuteAllRequests phoneId=" + phoneId + ",request size = " + mRequestInfos.size());
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (requestInfo.phoneId == phoneId) {
                onExecuteRequest(requestInfo);
            }
        }
    }

    private void onReleaseRequest(RequestInfo requestInfo) {
        logd("onReleaseRequest request=" + requestInfo);
        if (requestInfo != null) {
            requestInfo.log("DctController.onReleaseRequest");
            if (requestInfo.executed) {
                String apn = apnForNetworkRequest(requestInfo.request);
                int phoneId = requestInfo.phoneId;
                PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
                DcTrackerBase dcTracker = phoneBase.mDcTracker;
                dcTracker.decApnRefCount(apn, requestInfo.getLog());
                requestInfo.executed = false;
            }
        }
    }

    private void onReleaseAllRequests(int phoneId) {
        logd("onReleaseAllRequests phoneId=" + phoneId + ",request size = " + mRequestInfos.size());
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (requestInfo.phoneId == phoneId) {
                String apn = apnForNetworkRequest(requestInfo.request);
                PhoneBase phoneBase = (PhoneBase) mPhones[phoneId].getActivePhone();
                DcTrackerBase dcTracker = phoneBase.mDcTracker;
                if (PhoneConstants.APN_TYPE_IMS.equals(apn) &&
                    Phone.REASON_QUERY_PLMN.equals(dcTracker.mSetDataAllowedReason)) {
                    logd("onReleaseAllRequests, not release ims pdn for plmn searching");
                } else {
                    onReleaseRequest(requestInfo);
                }
            }
        }
    }

    private void onRetryAttach(int phoneId) {
        //M: For nSmA
        int groupId = getGroupId(phoneId);
        final int topPriPhone = getTopPriorityRequestPhoneId(groupId);
        logd("onRetryAttach phoneId=" + phoneId + " topPri phone = " + topPriPhone);

        if (phoneId != -1 && phoneId == topPriPhone) {
            mDcSwitchAsyncChannel[phoneId].retryConnect();
        }
    }


    private void onSettingsChanged() {
        //Sub Selection
        int dataSubId = mSubController.getDefaultDataSubId();

        /** M: Set data ICCID for combination attach */
        int dataPhoneId = SubscriptionManager.getPhoneId(dataSubId);
        String defaultIccid = "";
        if (dataPhoneId >= 0) {
            if (dataPhoneId >= PROPERTY_ICCID_SIM.length) {
                loge("onSettingsChange, phoneId out of boundary:" + dataPhoneId);
            } else {
                defaultIccid = SystemProperties.get(PROPERTY_ICCID_SIM[dataPhoneId]);
                logd("onSettingsChange, Iccid = " + defaultIccid + ", dataPhoneId:" + dataPhoneId);
                if (defaultIccid.equals("") || defaultIccid.equals(INVALID_ICCID)) {
                    logd("onSettingsChange, get iccid fail");
                    SystemProperties.set(PROPERTY_RIL_DATA_ICCID, "");
                    return;
                }
            }
        } else {
            logd("onSettingsChange, default data unset");
        }
        SystemProperties.set(PROPERTY_RIL_DATA_ICCID, defaultIccid);

        logd("onSettingsChange, data sub: " + dataSubId
                + ", defaultIccid: " + defaultIccid);

        int i = 0;
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            String specifier = requestInfo.request.networkCapabilities.getNetworkSpecifier();
            //M: Fix Google's Issue
            //String apn = apnForNetworkRequest(requestInfo.request);
            if (specifier == null || specifier.equals("")) {
                onReleaseRequest(requestInfo);

                for (i = 0; i < mPhoneNum; ++i) {
                    ((DctController.TelephonyNetworkFactory) mNetworkFactory[i])
                           .addPendingRequest(requestInfo.request);
                }
                iterator.remove();
            }
        }

        for (i = 0; i < mPhoneNum; ++i) {
            ((DctController.TelephonyNetworkFactory) mNetworkFactory[i])
                .evalPendingRequest();
        }

        processRequests();
    }

    private void onVoiceCallStarted() {
        for (int i = 0; i < mPhoneNum; i++) {
            PhoneBase pb = (PhoneBase) mPhones[i].getActivePhone();
            logd("onVoiceCallStarted: mPhone[ " + i +"]");
            DcTrackerBase dcTracker = pb.mDcTracker;
            dcTracker.onVoiceCallStarted();
        }
    }

    private void onVoiceCallEnded() {
        for (int i = 0; i < mPhoneNum; i++) {
            PhoneBase pb = (PhoneBase) mPhones[i].getActivePhone();
            logd("onVoiceCallEnded: mPhone[ " + i +"]");
            DcTrackerBase dcTracker = pb.mDcTracker;
            dcTracker.onVoiceCallEnded();
        }
    }

    protected boolean isDataSupportConcurrent(int phoneId) {
        logd("isDataSupportConcurrent: phoneId= " + phoneId + " mCallingPhone = " + mCallingPhone);

        // PS & CS on the same phone
        if (mCallingPhone != -1 && phoneId == mCallingPhone) {
            // Use sender's phone id (e.g. DcTracker) to query its services state.
            PhoneBase pb = (PhoneBase) mPhones[phoneId].getActivePhone();
            boolean isConcurrent = pb.getServiceStateTracker().isConcurrentVoiceAndDataAllowed();
            logd("isDataSupportConcurrent:(PS&CS on the same phone) isConcurrent= " + isConcurrent);
            return isConcurrent;
        } else {
             /* PS & CS not on the same phone
             *    For common project, return dual talk support or not.
             *    For SRLTE, return false.
             *    For SVLTE, if CS and PS phone is the same type(data is on LTE, voice is on GSM),
             *        return false, else return true.
             */
            // FIXME: The use of get system properties below can be revised.
            //        For example: a query API or variables.
            if (SystemProperties.getInt("ro.mtk_srlte_support", 0) == 1) {
                logd("isDataSupportConcurrent: support SRLTE ");
                return false;
            } else if (SystemProperties.getInt("ro.mtk_svlte_support", 0) == 1) {
                logd("isDataSupportConcurrent: support SVLTE ");
                if (isPsCsPhoneTypeEqual()) {
                    return false;
                } else {
                    return true;
                }
            } else {
                if (SystemProperties.getInt("ro.mtk_dt_support", 0) == 1) {
                    logd("isDataSupportConcurrent: support Dual Talk ");
                    return true;
                } else {
                    logd("isDataSupportConcurrent: NOT support Dual Talk ");
                    return false;
                }
            }
        }
    }

    private boolean isPsCsPhoneTypeEqual() {
        // Return if the PS & CS phone type equally, only used in the case of SVLTE.
        // TODO, need to check GSM/CDMA phone type,API: getPhoneType()
        //      Data(GSM) & Call(CDMA) -->Can concurrent
        //      Data(GSM) & Call(GSM) -->Can't concurrent
        return true;
    }

    protected boolean isAllCallingStateIdle() {
        PhoneConstants.State [] state = new PhoneConstants.State[mPhoneNum];
        boolean allCallingState = false;
        for (int i = 0; i < mPhoneNum; i++) {
            PhoneBase pb = (PhoneBase)mPhones[i].getActivePhone();
            state[i] = pb.getCallTracker().getState();

            if (state[i] != null && state[i] == PhoneConstants.State.IDLE) {
                allCallingState = true;
            } else {
                allCallingState =false;
            }
        }

        if (!allCallingState && DBG) {
            // In case of print too much log, only log if call state not IDLE.
            for (int i = 0; i < mPhoneNum; i++) {
                logd("isAllCallingStateIdle: state[" + i + "]=" + state[i] +
                        " allCallingState = " + allCallingState);
            }
        }
        return allCallingState;
    }

    private int getTopPriorityRequestPhoneId(int group) {
        RequestInfo retRequestInfo = null;
        int phoneId = 0;
        int priority = -1;

        //TODO: Handle SIM Switch
        for (int i = 0; i < mPhoneNum; i++) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                logd("selectExecPhone requestInfo = " + requestInfo);
                if (requestInfo.phoneId == i &&
                        priority < requestInfo.priority &&
                        requestInfo.mGId == group) {
                    priority = requestInfo.priority;
                    retRequestInfo = requestInfo;
                }
            }
        }

        if (retRequestInfo != null) {
            phoneId = getRequestPhoneId(retRequestInfo.request);
        //MTK: Enhancement
        //} else {
        //    int defaultDds = mSubController.getDefaultDataSubId();
        //    phoneId = mSubController.getPhoneId(defaultDds);
        //    logd("getTopPriorityRequestPhoneId: RequestInfo list is empty, " +
        //            "use Dds sub phone id");
        } else {
            phoneId = getPreferPhoneId(group);
        }

        logd("getTopPriorityRequestPhoneId = " + phoneId + ", priority = " + priority
                + ", gruop = " + group);

        return phoneId;
    }

    private boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < mPhoneNum;
    }

    private void onSubInfoReady() {
        logd("onSubInfoReady mPhoneNum=" + mPhoneNum);
        for (int i = 0; i < mPhoneNum; ++i) {

            int phoneId = getPreferPhoneId(i);
            if (isValidPhoneId(phoneId)) {
                if (getDcSwitchState(phoneId) == DcSwitchStateMachine.DCSTATE_ATTACHING) {
                    retryAttach(phoneId);
                    logd("retry attach: " + phoneId);
                }
            }

            int subId = mPhones[i].getSubId();
            logd("onSubInfoReady handle pending requests subId=" + subId);
            mNetworkFilter[i].setNetworkSpecifier(String.valueOf(subId));
            ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).evalPendingRequest();
        }
        processRequests();
    }

    private String apnForNetworkRequest(NetworkRequest nr) {
        NetworkCapabilities nc = nr.networkCapabilities;
        // For now, ignore the bandwidth stuff
        if (nc.getTransportTypes().length > 0 &&
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == false) {
            return null;
        }

        // in the near term just do 1-1 matches.
        // TODO - actually try to match the set of capabilities
        int type = -1;
        String name = null;

        boolean error = false;
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_MMS;
            type = ConnectivityManager.TYPE_MOBILE_MMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_SUPL;
            type = ConnectivityManager.TYPE_MOBILE_SUPL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DUN;
            type = ConnectivityManager.TYPE_MOBILE_DUN;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_FOTA;
            type = ConnectivityManager.TYPE_MOBILE_FOTA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IMS;
            type = ConnectivityManager.TYPE_MOBILE_IMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CBS;
            type = ConnectivityManager.TYPE_MOBILE_CBS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IA;
            type = ConnectivityManager.TYPE_MOBILE_IA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_RCS;
            type = ConnectivityManager.TYPE_MOBILE_RCS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_XCAP;
            type = ConnectivityManager.TYPE_MOBILE_XCAP;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_EMERGENCY;
            type = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
            logd("### EIMS type support");
        }

        /** M: start */
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DM)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DM;
            type = ConnectivityManager.TYPE_MOBILE_DM;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_WAP)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_WAP;
            type = ConnectivityManager.TYPE_MOBILE_WAP;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_NET;
            type = ConnectivityManager.TYPE_MOBILE_NET;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CMMAIL;
            type = ConnectivityManager.TYPE_MOBILE_CMMAIL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_TETHERING;
            type = ConnectivityManager.TYPE_MOBILE_TETHERING;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCSE)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_RCSE;
            type = ConnectivityManager.TYPE_MOBILE_RCSE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VSIM)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        /** M: end */

        if (error) {
            // TODO: If this error condition is removed, the framework's handling of
            // NET_CAPABILITY_NOT_RESTRICTED will need to be updated so requests for
            // say FOTA and INTERNET are marked as restricted.  This is not how
            // NetworkCapabilities.maybeMarkCapabilitiesRestricted currently works.
            loge("Multiple apn types specified in request - result is unspecified!");
        }
        if (type == -1 || name == null) {
            loge("Unsupported NetworkRequest in Telephony: nr=" + nr);
            return null;
        }
        return name;
    }

    private int getRequestPhoneId(NetworkRequest networkRequest) {
        String specifier = networkRequest.networkCapabilities.getNetworkSpecifier();
        String apn = apnForNetworkRequest(networkRequest);
        logd("getRequestPhoneId apn = " + apn);

        int subId;
        if (specifier == null || specifier.equals("")) {
            subId = mSubController.getDefaultDataSubId();
        } else {
            subId = Integer.parseInt(specifier);
        }
        int phoneId = mSubController.getPhoneId(subId);
        logd("getRequestPhoneId:specifier=" + specifier + " sub=" + subId + " phoneId=" + phoneId);

        // Google design in the case of invalid phone id would go establish SIM1 (phoneId=0).
        // It might to handle issue like request data with no specifier or using startUsingNewtork
        // API.
        // However, this resulting issue that data icon appear and disappear again in some case.
        // Scenario:
        //     SIM A at slot1 and set it as default data,
        //     Remove SIMA and insert SIMB at slot1 and set it as default data.
        //     Re-insert SIM A back to slot1, reboot and do not click the default data pop-up.
        if (!SubscriptionManager.isValidPhoneId(phoneId)
                && !PhoneConstants.APN_TYPE_DEFAULT.equals(apn)) {
            phoneId = 0;
            if (!SubscriptionManager.isValidPhoneId(phoneId)) {
                throw new RuntimeException("Should not happen, no valid phoneId");
            }
        }
        return phoneId;
    }

    private BroadcastReceiver mShutDownIpoIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            if (action != null && action.equals(ACTION_SHUTDOWN_IPO)) {
                logd("IPO Shutdown, clear PROPERTY_DATA_ALLOW_SIM, PROPERTY_IA_APN_SET_ICCID");
                SystemProperties.set(PROPERTY_DATA_ALLOW_SIM, "");
                SystemProperties.set(PROPERTY_IA_APN_SET_ICCID, "");
                SystemProperties.set(PROPERTY_TEMP_IA, "");
                SystemProperties.set(PROPERTY_TEMP_IA_APN, "");
            }
        }
    };

    private boolean mIsSubInfoUpdateReceiverRegistered = false;
    private BroadcastReceiver mSubInfoUpdateIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                        SubscriptionManager.EXTRA_VALUE_NOCHANGE);
                logd("detectedType:" + detectedType);
                if (detectedType != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                    mDataSubSelector.onSubInfoReady(intent);
                }
                onSubInfoReady();
            }
        }
    };

    private static void logv(String s) {
        if (DBG) Rlog.v(LOG_TAG, "[DctController] " + s);
    }

    private static void logd(String s) {
        if (DBG) Rlog.d(LOG_TAG, "[DctController] " + s);
    }

    private static void logw(String s) {
        if (DBG) Rlog.w(LOG_TAG, "[DctController] " + s);
    }

    private static void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, "[DctController] " + s);
    }

    private class TelephonyNetworkFactory extends NetworkFactory {
        private final SparseArray<NetworkRequest> mPendingReq = new SparseArray<NetworkRequest>();
        private Phone mPhone;
        private int mGroupId;

        private class RequestLogger {
            public NetworkRequest request;
            public LocalLog log;

            public RequestLogger(NetworkRequest r, LocalLog log) {
                request = r;
                this.log = log;
            }
        }

        private static final int MAX_REQUESTS_LOGGED = 20;
        private static final int MAX_LOG_LINES_PER_REQUEST = 50;

        private ArrayDeque<RequestLogger> mRequestLogs = new ArrayDeque<RequestLogger>();

        public TelephonyNetworkFactory(Looper l, Context c, String TAG, Phone phone,
                NetworkCapabilities nc) {
            super(l, c, TAG, nc);
            mPhone = phone;
            int phoneId = mPhone.getPhoneId();
            mGroupId = getGroupId(phoneId);
            log("NetworkCapabilities: " + nc + ", mGroupId = " + mGroupId);
        }

        public LocalLog requestLog(int requestId, String l) {
            synchronized(mRequestLogs) {
                for (RequestLogger r : mRequestLogs) {
                    if (r.request.requestId == requestId) {
                        r.log.log(l);
                        return r.log;
                    }
                }
            }
            return null;
        }

        private LocalLog addLogger(NetworkRequest request) {
            synchronized(mRequestLogs) {
                for (RequestLogger r : mRequestLogs) {
                    if (r.request.requestId == request.requestId) {
                        return r.log;
                    }
                }
                LocalLog l = new LocalLog(MAX_LOG_LINES_PER_REQUEST);
                RequestLogger logger = new RequestLogger(request, l);
                while (mRequestLogs.size() >= MAX_REQUESTS_LOGGED) {
                    mRequestLogs.removeFirst();
                }
                mRequestLogs.addLast(logger);
                return l;
            }
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            log("Cellular needs Network for " + networkRequest);

            final LocalLog l = addLogger(networkRequest);

            /// M: Not put NetworkRequest to mReqInfos when sim not inserted.
            /// Ex, when hot plug out sim card, the sim is not inserted but the sub
            /// maybe valid as rild and framework has sync time.
            final boolean simInserted = isSimInserted(mPhone.getPhoneId());
            if (!simInserted || !SubscriptionManager.isUsableSubIdValue(mPhone.getSubId()) ||
                    getRequestPhoneId(networkRequest) != mPhone.getPhoneId()) {
                if (isSkipEimsCheck(networkRequest, mPhone.getPhoneId())) {
                    log("Sub Info not ready but EIMS request, not put into pending request!!");
                } else {
                    final String str = "Request not useable, pending request,"
                            + " simInserted = " + simInserted;
                    log(str);
                    l.log(str);
                    mPendingReq.put(networkRequest.requestId, networkRequest);
                    return;
                }
            }

            DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
            String apn = apnForNetworkRequest(networkRequest);
            if (dcTracker.isApnSupported(apn)) {
                requestNetwork(networkRequest, dcTracker.getApnPriority(apn), l,
                        mPhone.getPhoneId(), mGroupId);
            } else {
                final String str = "Unsupported APN";
                log(str);
                l.log(str);
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            String str = "Cellular releasing Network for ";
            log(str + networkRequest);
            final LocalLog l = requestLog(networkRequest.requestId, str);

            if (mPendingReq.get(networkRequest.requestId) != null) {
                str = "Sub Info has not been ready, remove request.";
                log(str);
                if (l != null) l.log(str);
                mPendingReq.remove(networkRequest.requestId);
                return;
            }

            releaseNetwork(networkRequest);
        }

        @Override
        protected void log(String s) {
            if (DBG) Rlog.d(LOG_TAG, "[TNF " + mPhone.getSubId() + "]" + s);
        }

        public void addPendingRequest(NetworkRequest networkRequest) {
            log("addPendingRequest, request:" + networkRequest);
            mPendingReq.put(networkRequest.requestId, networkRequest);
        }

        public void evalPendingRequest() {
            log("evalPendingRequest, pending request size is " + mPendingReq.size());
            int key = 0;
            int pendingReqSize = mPendingReq.size();
            // The use of list to keep all reqeusts
            List<NetworkRequest> processList = new ArrayList<NetworkRequest>();

            // Add requests to processList, below implementation is to avoid error while process
            // 2 or more requests in the pending queue at the same time.
            for (int i = 0; i < pendingReqSize; i++) {
                key = mPendingReq.keyAt(i);
                log("evalPendingRequest:mPendingReq= " + mPendingReq + " i=" + i + " Key = " + key);
                NetworkRequest request = mPendingReq.get(key);
                processList.add(request);
            }

            // Remove all request and needNetworkFor will add it to pending if necessary.
            mPendingReq.clear();
            log("evalPendingRequest:mPendingReq clear");

            // Remove processed request.
            for (NetworkRequest request : processList) {
                log("evalPendingRequest:ready to do needNetworkFor and request = " + request);
                needNetworkFor(request, 0);
            }
        }

        /**
         * Update group ID for network factory.
         */
        public void updateGroupId() {
            int phoneId = mPhone.getPhoneId();
            mGroupId = getGroupId(phoneId);
            log("updateGroupId: mGroupId = " + mGroupId);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(fd, writer, args);
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            pw.increaseIndent();
            pw.println("Pending Requests:");
            pw.increaseIndent();
            for (int i = 0; i < mPendingReq.size(); i++) {
                NetworkRequest request = mPendingReq.valueAt(i);
                pw.println(request);
            }
            pw.decreaseIndent();

            pw.println("Request History:");
            pw.increaseIndent();
            synchronized(mRequestLogs) {
                for (RequestLogger r : mRequestLogs) {
                    pw.println(r.request);
                    pw.increaseIndent();
                    r.log.dump(fd, pw, args);
                    pw.decreaseIndent();
                }
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DctController:");
        try {
            for (DcSwitchStateMachine dssm : mDcSwitchStateMachine) {
                dssm.dump(fd, pw, args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            for (Entry<Integer, RequestInfo> entry : mRequestInfos.entrySet()) {
                pw.println("mRequestInfos[" + entry.getKey() + "]=" + entry.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
        pw.println("TelephonyNetworkFactories:");
        for (NetworkFactory tnf : mNetworkFactory) {
            tnf.dump(fd, pw, args);
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }

    //MTK START
    /** M: get acive data phone Id */
    public int getActiveDataPhoneId() {
        int activePhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }
        return activePhoneId;
    }

    /** M: allow data service or not and can set a max timeout for setting data not allowed */
    public void setDataAllowed(long subId, boolean allowed, String reason, long timeout) {
        logd("setDataAllowed subId=" + subId + ", allowed=" + allowed + ", reason="
            + reason + ", timeout=" + timeout);
        mDataAllowed = allowed;
        if (mDataAllowed) {
            mRspHandler.removeCallbacks(mDataNotAllowedTimeoutRunnable);
        }

        setDataAllowedReasonToDct(reason);
        processRequests();

        if (!mDataAllowed && timeout > 0) {
            logd("start not allow data timer and timeout=" + timeout);
            mRspHandler.postDelayed(mDataNotAllowedTimeoutRunnable, timeout);
        }
    }

    private void setDataAllowedReasonToDct(String reason) {
        logd("setDataAllowedReasonToDct reason: " + reason);
        for (int i = 0; i < mPhoneNum; i++) {
            PhoneBase phoneBase = (PhoneBase) mPhones[i].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.mSetDataAllowedReason = reason;
        }
    }

    /** M: try to do re-attach
     *  Disconnect all data connections and do detach if current state is ATTACHING or ATTACHED
     *  Once detach is done, all requests would be processed again when entering IDLE state
     *  That means re-attach will be triggered
     */
    void disconnectAll() {
        int activePhoneId = -1;
        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        if (activePhoneId >= 0) {
            logd("disconnectAll, active phone:" + activePhoneId);
            mDcSwitchAsyncChannel[activePhoneId].disconnectAll();
        } else {
            logd("disconnectAll but no active phone, process requests");
        }
    }

    public synchronized void registerForDcSwitchStateChange(Handler h, int what,
            Object obj, DcStateParam param) {

        Registrant r = new Registrant(h, what, obj);
        DcStateParam dcState = null;

        if (param == null) {
            dcState = new DcStateParam("Don't care", false);
        } else {
            dcState = param;
        }

        if (DBG) {
            logd("registerForDcSwitchStateChange: dcState = " + dcState);
        }

        dcState.mRegistrant = r;
        mDcSwitchStateChange.put(h, dcState);
    }

    public synchronized void unregisterForDcSwitchStateChange(Handler h) {
        if (DBG) {
            logd("unregisterForDcSwitchStateChange");
        }
        mDcSwitchStateChange.remove(h);
    }

    synchronized void notifyDcSwitchStateChange(String state, int phoneId, String reason) {
        mUserCnt = 0;
        mTransactionId++;

        for (DcStateParam param : mDcSwitchStateChange.values()) {
            String user = param.mUser;
            Registrant r = param.mRegistrant;
            Message msg = null;

            if (state.equals(DcSwitchStateMachine.DCSTATE_PREDETACH_CHECK) && param.mNeedCheck) {
                msg = obtainMessage(EVENT_CONFIRM_PREDETACH, mTransactionId, phoneId, user);
                mUserCnt++;
            }

            DcStateParam dcState = new DcStateParam(state, phoneId, reason, msg);
            AsyncResult ar = new AsyncResult(null, dcState, null);
            r.notifyRegistrant(ar);
        }

        if (DBG) {
            logd("notifyDcSwitchStateChange: user:" + mUserCnt + ", ID:" + mTransactionId);
        }

        if (state.equals(DcSwitchStateMachine.DCSTATE_PREDETACH_CHECK) && mUserCnt == 0) {
            obtainMessage(EVENT_CONFIRM_PREDETACH, mTransactionId, phoneId,
                    "No User").sendToTarget();
        }
    }

    private synchronized void handleConfirmPreDetach(Message msg) {
        int transAct = msg.arg1;
        int phoneId = msg.arg2;
        String user = (String) msg.obj;

        if (mTransactionId != transAct) {
            if (DBG) {
                logd("unExcept transAct: " + transAct);
            }
        }

        if (mUserCnt > 0) {
            mUserCnt--;
        }

        if (DBG) {
            logd("handleConfirmPreDetach: user:" + user + ", ID:" + transAct + ", phone" + phoneId
                + ", Remain User:" + mUserCnt);
        }

        if (mUserCnt == 0) {
            mDcSwitchAsyncChannel[phoneId].confirmPreDetach();
        }
    }

    public String getDcSwitchState(int phoneId) {
        String ret = mDcSwitchAsyncChannel[phoneId].requestDcSwitchStateSync();
        logd("getDcSwitchState: Phone" + phoneId + " state = " + ret);
        return ret;
    }

    private void setAlwaysAttachSim() {
        MultiSimVariants config = TelephonyManager.getDefault()
                .getMultiSimConfiguration();
        // We divide phones into different groups according to multi sim config.
        if (config == MultiSimVariants.DSDS
                || config == MultiSimVariants.TSTS) {
            String attachPhone = "";
            attachPhone = SystemProperties.get(PROPERTY_DATA_ALLOW_SIM, "");
            logd(" attachPhone: " + attachPhone);
            if (attachPhone != null && !attachPhone.equals("")) {
                int phoneId = Integer.parseInt(attachPhone);
                if (phoneId >= 0 && phoneId < mPhoneNum) {
                    logd("Set phone" + phoneId + " to attaching state");
                    sendMessage(obtainMessage(EVENT_TRANSIT_TO_ATTACHING, phoneId, 0));
                }
            }
        } else if (config == MultiSimVariants.DSDA) {
            //TODO: Extend to nSmA
            //FIXME: Need to get total group numbers
            for (int i = 0; i < mPhoneNum; i++) {
                sendMessage(obtainMessage(EVENT_TRANSIT_TO_ATTACHING, i, 0));
            }
        }
    }

    /** M: transit to attaching state. */
    private void transitToAttachingState(int targetPhoneId) {
        int groupId = getGroupId(targetPhoneId);
        int topPriorityPhoneId = getTopPriorityRequestPhoneId(groupId);
        if (!SubscriptionManager.isValidPhoneId(topPriorityPhoneId)) {
            topPriorityPhoneId = getPreferPhoneId(groupId);
            logd("transitToAttachingState getPreferPhoneId = " + topPriorityPhoneId);
        }
        int activePhoneId = -1;
        if (topPriorityPhoneId == targetPhoneId) {
            for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
                if (!mDcSwitchAsyncChannel[i].isIdleSync() && groupId == getGroupId(i)) {
                    activePhoneId = i;
                    break;
                }
            }
            if (activePhoneId != -1 && activePhoneId != targetPhoneId) {
                logd("transitToAttachingState: disconnect other phone");
                mDcSwitchAsyncChannel[activePhoneId].disconnectAll();
            } else {
                logd("transitToAttachingState: connect");
                mDcSwitchAsyncChannel[targetPhoneId].connect(null);
            }
        } else {
            logd("transitToAttachingState: disconnect target phone");
            mDcSwitchAsyncChannel[targetPhoneId].connect(null);
            mDcSwitchAsyncChannel[targetPhoneId].disconnectAll();
        }
    }

    protected ConcurrentHashMap<String, ApnContext> getApnContexts(int phoneId) {
        PhoneBase phoneBase = (PhoneBase) mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker = phoneBase.mDcTracker;
        ConcurrentHashMap<String, ApnContext> apnContexts = null;
        if (dcTracker != null) {
            apnContexts = dcTracker.getApnContexts();
        } else {
            loge("DcTracker is null");
        }
        return apnContexts;
    }

    private void restorePendingRequest(int phoneId) {
        // Update group ID for mode change.
        ((DctController.TelephonyNetworkFactory) mNetworkFactory[phoneId])
                .updateGroupId();

        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            logd("restorePendingRequest requestInfo = " + requestInfo);
            if (requestInfo != null && getRequestPhoneId(requestInfo.request) == phoneId) {
                ((DctController.TelephonyNetworkFactory) mNetworkFactory[phoneId])
                        .addPendingRequest(requestInfo.request);
                onReleaseRequest(requestInfo);
                iterator.remove();
            }
        }
    }

    private boolean needExecuteRequest(RequestInfo requestInfo) {
        RequestInfo checkInfo = mRequestInfos.get(requestInfo.request.requestId);
        boolean ret = false;

        if (!requestInfo.executed && checkInfo != null
                && mRequestInfos.containsKey(requestInfo.request.requestId)) {
            ret = true;
        }

        logd("needExecuteRequest return " + ret + ", checkInfo = " + checkInfo);

        return ret;
    }

    public boolean isActivePhone(int phoneId) {
        return !mDcSwitchAsyncChannel[phoneId].isIdleSync();
    }

    public class DcStateParam {
        private String mState;
        private int mPhoneId;
        private Message mMessage;

        private String mUser;
        private boolean mNeedCheck;
        private Registrant mRegistrant;
        private String mReason;

        public DcStateParam(String state, int phoneId, String reason, Message msg) {
            mState = state;
            mPhoneId = phoneId;
            mReason = reason;
            mMessage = msg;
        }

        public DcStateParam(String user, boolean needCheckDisconnect) {
            mUser = user;
            mNeedCheck = needCheckDisconnect;
        }

        public String getState() {
            return mState;
        }

        public int getPhoneId() {
            return mPhoneId;
        }

        public String getReason() {
            return mReason;
        }
        public boolean confirmPreCheckDetach() {
            logd("confirmPreCheckDetach, msg = " + mMessage);
            if (mMessage == null) {
                return false;
            } else {
                mMessage.sendToTarget();
                return true;
            }
        }

        @Override
        public String toString() {
            return "[ mState=" + mState + ", mReason=" + mReason + ", mPhoneId =" + mPhoneId
                    + ", user = " + mUser + ", needCheck = " + mNeedCheck
                    + ", message = " + mMessage + ", Registrant = " + mRegistrant + "]";
        }
    }

    //Multi-Group START
    private int getGroupId(int phoneId) {
        MultiSimVariants config = getPsMode();
        int groupId = 0;

        // We divide phones into different groups according to multi sim config.
        if (config == MultiSimVariants.DSDA) {
            groupId = phoneId;
        }

        logd("getGroupId phone = " + phoneId + ", groupId = "  + groupId);
        return groupId;
    }

    private int getGroupNumbers() {
        MultiSimVariants config = getPsMode();
        int groupNumber = 1;

        //TODO: Enhance to nSmA
        if (config == MultiSimVariants.DSDA) {
            groupNumber = mPhoneNum;
        }

        logd("getGroupNumbers groupNumber = " + groupNumber);
        return groupNumber;
    }

    private int getPreferPhoneId(int groupId) {
        //TODO: Enhance to nSmA
        int dataPhoneId = getDefaultDataPhoneId();
        if (dataPhoneId >= 0 && dataPhoneId < mPhoneNum && getGroupId(dataPhoneId) == groupId) {
            logd("getPreferPhoneId: return default data phone Id = " + dataPhoneId);
            return dataPhoneId;
        } else {
            String curr3GSim = SystemProperties.get("persist.radio.simswitch", "");
            int curr3GPhoneId = -1;
            logd("current 3G Sim = " + curr3GSim);
            if (curr3GSim != null && !curr3GSim.equals("")) {
                curr3GPhoneId = Integer.parseInt(curr3GSim) - 1;
            }
            if (curr3GPhoneId != -1 && getGroupId(curr3GPhoneId) == groupId) {
                if (isSimInserted(curr3GPhoneId)) {
                    logd("getPreferPhoneId return current 3G SIM: " + curr3GSim);
                    return curr3GPhoneId;
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (groupId == getGroupId(i) && isSimInserted(i)) {
                            logd("3G sim is not inserted, return first existed sim = " + i);
                            return i;
                        }
                    }
                    logd("getPreferPhoneId no sim inserted, 3G sim is valid,"
                            + " return 3G SIM: " + curr3GSim);
                    return curr3GPhoneId;
                }
            }
        }
        logd("getPreferPhoneId: no prefer phone found, return default value: " + groupId);
        return groupId;
    }
    //Multi-Group END

    private int getDefaultDataPhoneId() {
        int dataPhoneId = mSubController.getPhoneId(mSubController.getDefaultDataSubId());
        String dataIccid = "";
        String simIccid = "";
        if (dataPhoneId < 0 || dataPhoneId > mPhoneNum) {
            logd("getDefaultDataPhoneId: invalid phone ID " + dataPhoneId + " ,find property");
            dataIccid = SystemProperties.get(PROPERTY_RIL_DATA_ICCID);
            if (dataIccid != null && !dataIccid.equals("")) {
                for (int i = 0; i < mPhoneNum; i++) {
                    simIccid = SystemProperties.get(PROPERTY_ICCID_SIM[i]);
                    if (simIccid != null && dataIccid.equals(simIccid)) {
                        logd("getDefaultDataPhoneId: Sim" + i + " iccid matched: " + simIccid);
                        dataPhoneId = i;
                        break;
                    }
                }
            }
        }
        logd("getDefaultDataPhoneId: dataPhoneId = " + dataPhoneId);

        return dataPhoneId;
    }

    private boolean isSimInserted(int phoneId) {
        String iccid = SystemProperties.get(PROPERTY_ICCID_SIM[phoneId], "");
        return !TextUtils.isEmpty(iccid) && !INVALID_ICCID.equals(iccid);
    }

    private final BroadcastReceiver mRadioStateChangeIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            if (TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED.equals(action) ||
                TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE.equals(action)) {
                // M: C2K SVLTE mode switch, group maybe changed, update group id
                for (int i = 0; i < mPhoneNum; i++) {
                    ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).updateGroupId();
                }
                logd("receive set radio tech done, update group DSDS/DSDA mode");
            }
        }
    };

    private MultiSimVariants getSvlteModeConfig() {
        MultiSimVariants config = MultiSimVariants.DSDS;
        // major slot index from 1.
        String majorString = SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1");
        int majorSlot = Integer.parseInt(majorString) - 1;
        int cdmaSlot = -1;
        for (int i = 0; i< mPhoneNum; i++) {
            if (mPhones[i].getActivePhone().getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
               cdmaSlot = i;
            }
        }
        logd(" getSvlteModeConfig majorSlot:" + majorSlot + "cdmaSlot:" + cdmaSlot);
        // C2K SVLTE when mode is C+LG or LG+C is DSDA.
        if (cdmaSlot >= 0 && cdmaSlot != majorSlot) {
            config = MultiSimVariants.DSDA;
        }
        return config;
    }

    private MultiSimVariants getPsMode() {
        MultiSimVariants config;
        if (isSrlteSupport()) {
            config = MultiSimVariants.DSDS;
        } else if (isSvlteSupport()) {
            config = getSvlteModeConfig();
        } else {
            config = TelephonyManager.getDefault().getMultiSimConfiguration();
        }
        logd(" getPsMode : " + config);
        return config;
    }

    private int get4gCapPhoneId() {
        String curr3GSim = SystemProperties.get("persist.radio.simswitch", "");
        int curr3GPhoneId = -1;
        logd("current 3G Sim = " + curr3GSim);
        if (curr3GSim != null && !curr3GSim.equals("")) {
            curr3GPhoneId = Integer.parseInt(curr3GSim) - 1;
        }
        return curr3GPhoneId;
    }

    //MTK START for EIMS
    private boolean isSkipEimsCheck(NetworkRequest networkRequest, int phoneId) {

        if (networkRequest.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_EIMS)) {

            //Check if sim inserted
            for (int i = 0; i < mPhoneNum; i++) {
                if (isSimInserted(i)) {
                    logd("isSkipEimsCheck: not without sim case, don't ignore.");
                    return false;
                }
            }

            int curr4gCapPhone = get4gCapPhoneId();

            if (phoneId == curr4gCapPhone) {
                logd("isSkipEimsCheck: pass phone" + phoneId);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    //MTK END: for EIMS
    private boolean isSrlteSupport() {
        return SUPPORT_YES.equals(SystemProperties.get(MTK_SRLTE_SUPPORT, SUPPORT_NO));
    }

    private boolean isSvlteSupport() {
        return SUPPORT_YES.equals(SystemProperties.get(MTK_SVLTE_SUPPORT, SUPPORT_NO));
    }
    //MTK END

}

