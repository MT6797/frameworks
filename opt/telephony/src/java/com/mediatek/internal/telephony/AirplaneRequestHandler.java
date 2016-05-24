package com.mediatek.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.Rlog;
import android.net.ConnectivityManager;

import com.android.internal.telephony.CommandsInterface.RadioState;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.mediatek.internal.telephony.worldphone.WorldMode;
import com.mediatek.internal.telephony.worldphone.WorldPhoneUtil;

import java.util.concurrent.atomic.AtomicBoolean;
/**
 * This class fix the bug turn on/off flightmode frenquently.
 */
public class AirplaneRequestHandler extends Handler {
    private static final String LOG_TAG = "AirplaneRequestHandler";
    private Context mContext;
    private Boolean mPendingAirplaneModeRequest;
    private int mPhoneCount;
    private boolean mNeedIgnoreMessageForChangeDone;
    private boolean mForceSwitch;
    private boolean mNeedIgnoreMessageForWait;

    private static final int EVENT_GSM_RADIO_CHANGE_FOR_OFF = 100;
    private static final int EVENT_GSM_RADIO_CHANGE_FOR_AVALIABLE = 101;
    private static final int EVENT_WAIT_RADIO_CHANGE_FOR_AVALIABLE = 102;
    private static final String INTENT_ACTION_AIRPLANE_CHANGE_DONE =
            "com.mediatek.intent.action.AIRPLANE_CHANGE_DONE";
    private static final String EXTRA_AIRPLANE_MODE = "airplaneMode";

    private static AtomicBoolean sInSwitching = new AtomicBoolean(false);

    private boolean mHasRegisterWorldModeReceiver = false;
    private BroadcastReceiver mWorldModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int wmState = WorldMode.MD_WM_CHANGED_UNKNOWN;
            log("mWorldModeReceiver: action = " + action);
            if (TelephonyIntents.ACTION_WORLD_MODE_CHANGED.equals(action)) {
                wmState = intent.getIntExtra(TelephonyIntents.EXTRA_WORLD_MODE_CHANGE_STATE,
                        WorldMode.MD_WM_CHANGED_UNKNOWN);
                log("mWorldModeReceiver: wmState = " + wmState);
                if (mHasRegisterWorldModeReceiver) {
                    if (wmState == WorldMode.MD_WM_CHANGED_END) {
                        unRegisterWorldModeReceiver();
                        sInSwitching.set(false);
                        checkPendingRequest();
                    }
                }
            }
        }
    };

    protected boolean allowSwitching() {
        if (sInSwitching.get()  && !mForceSwitch) {
            return false;
        }
        return true;
    }

    protected void pendingAirplaneModeRequest(boolean enabled) {
        log("pendingAirplaneModeRequest, enabled = " + enabled);
        mPendingAirplaneModeRequest = new Boolean(enabled);
    }

    /**
     * The Airplane mode change request handler.
     * @param context the context
     * @param phoneCount the phone count
     */
    public AirplaneRequestHandler(Context context, int phoneCount) {
        mContext = context;
        mPhoneCount = phoneCount;
    }

    protected void monitorAirplaneChangeDone(boolean power) {
        mNeedIgnoreMessageForChangeDone = false;
        log("monitorAirplaneChangeDone, power = " + power
            + " mNeedIgnoreMessageForChangeDone = " + mNeedIgnoreMessageForChangeDone);
        sInSwitching.set(true);
        int phoneId = 0;
        for (int i = 0; i < mPhoneCount; i++) {
            phoneId = i;
            if (power) {
                ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).getActivePhone())
                        ).mCi.registerForRadioStateChanged(this,
                        EVENT_GSM_RADIO_CHANGE_FOR_AVALIABLE , null);
            } else {
                ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).getActivePhone())
                        ).mCi.registerForRadioStateChanged(this, EVENT_GSM_RADIO_CHANGE_FOR_OFF
                        , null);
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        ///M: Add for wifi only device. @{
        boolean isWifiOnly = isWifiOnly();
        ///  @}
        switch (msg.what) {
        case EVENT_GSM_RADIO_CHANGE_FOR_OFF:
            if (!mNeedIgnoreMessageForChangeDone) {
                if (msg.what == EVENT_GSM_RADIO_CHANGE_FOR_OFF) {
                    log("handle EVENT_GSM_RADIO_CHANGE_FOR_OFF");
                }
                for (int i = 0; i < mPhoneCount; i++) {
                    int phoneId = i;
                    ///M: Add for wifi only device, don't judge radio off. @{
                    if (isWifiOnly) {
                        log("wifi-only, don't judge radio off");
                        break;
                    }
                    ///  @}
                    if (!isRadioOff(phoneId)) {
                        log("radio state change, radio not off, phoneId = "
                                + phoneId);
                        return;
                    }
                }
                log("All radio off");
                sInSwitching.set(false);
                unMonitorAirplaneChangeDone(true);
                checkPendingRequest();
            }
            break;
        case EVENT_GSM_RADIO_CHANGE_FOR_AVALIABLE:
            if (!mNeedIgnoreMessageForChangeDone) {
                if (msg.what == EVENT_GSM_RADIO_CHANGE_FOR_AVALIABLE) {
                    log("handle EVENT_GSM_RADIO_CHANGE_FOR_AVALIABLE");
                }
                for (int i = 0; i < mPhoneCount; i++) {
                    int phoneId = i;
                    ///M: Add for wifi only device, don't judge radio avaliable. @{
                    if (isWifiOnly) {
                        log("wifi-only, don't judge radio avaliable");
                        break;
                    }
                    ///  @}
                    if (!isRadioAvaliable(phoneId)) {
                        log("radio state change, radio not avaliable, phoneId = "
                                + phoneId);
                        return;
                    }
                }
                log("All radio avaliable");
                sInSwitching.set(false);
                unMonitorAirplaneChangeDone(false);
                checkPendingRequest();
            }
            break;
        case EVENT_WAIT_RADIO_CHANGE_FOR_AVALIABLE:
            if (!mNeedIgnoreMessageForWait) {
                log("handle EVENT_WAIT_RADIO_CHANGE_FOR_AVALIABLE");
                if (!isRadioAvaliable()) {
                    return;
                }
                log("All radio avaliable");
                unWaitRadioAvaliable();
                sInSwitching.set(false);
                checkPendingRequest();
            }
            break;
          default:
            break;
        }
    }

    private boolean isRadioAvaliable(int phoneId) {
        log("phoneId = " + phoneId);
        return ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).
                getActivePhone())).mCi.getRadioState() != RadioState.RADIO_UNAVAILABLE;
    }

    private boolean isRadioOff(int phoneId) {
        log("phoneId = " + phoneId);
        return ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).
                getActivePhone())).mCi.getRadioState() == RadioState.RADIO_OFF;
    }

    private void checkPendingRequest() {
        log("checkPendingRequest, mPendingAirplaneModeRequest = " + mPendingAirplaneModeRequest);
        if (mPendingAirplaneModeRequest != null) {
            Boolean pendingAirplaneModeRequest = mPendingAirplaneModeRequest;
            mPendingAirplaneModeRequest = null;
            RadioManager.getInstance().notifyAirplaneModeChange(
                    pendingAirplaneModeRequest.booleanValue());
        }
    }

    protected void unMonitorAirplaneChangeDone(boolean airplaneMode) {
        mNeedIgnoreMessageForChangeDone = true;
        Intent intent = new Intent(INTENT_ACTION_AIRPLANE_CHANGE_DONE);
        intent.putExtra(EXTRA_AIRPLANE_MODE, airplaneMode);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        int phoneId = 0;
        for (int i = 0; i < mPhoneCount; i++) {
            phoneId = i;
            ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).getActivePhone())
                    ).mCi.unregisterForRadioStateChanged(this);
            log("unMonitorAirplaneChangeDone, for gsm phone,  phoneId = " + phoneId);
        }
    }

    /**
     * Set Whether force allow airplane mode change.
     * @return true or false
     */
    public void setForceSwitch(boolean forceSwitch) {
        mForceSwitch = forceSwitch;
        log("setForceSwitch, forceSwitch =" + forceSwitch);
    }

    protected boolean waitForReady(boolean enabled) {
        if (waitRadioAvaliable(enabled)) {
            log("waitForReady, wait radio avaliable");
            return true;
        } else if (waitWorlModeSwitching(enabled)) {
            log("waitForReady, wait world mode switching");
            return true;
        } else {
            return false;
        }
    }

    private boolean waitRadioAvaliable(boolean enabled) {
        final boolean wait = isCdmaLteDcSupport() && !isWifiOnly() && !isRadioAvaliable();
        log("waitRadioAvaliable, enabled=" + enabled + ", wait=" + wait);
        if (wait) {
            // pending
            pendingAirplaneModeRequest(enabled);

            // wait for radio avaliable
            mNeedIgnoreMessageForWait = false;
            sInSwitching.set(true);
            int phoneId = 0;
            for (int i = 0; i < mPhoneCount; i++) {
                phoneId = i;
                ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).getActivePhone())).mCi
                        .registerForRadioStateChanged(this, EVENT_WAIT_RADIO_CHANGE_FOR_AVALIABLE,
                                null);
            }
        }
        return wait;
    }

    private void unWaitRadioAvaliable() {
        mNeedIgnoreMessageForWait = true;
        int phoneId = 0;
        for (int i = 0; i < mPhoneCount; i++) {
            phoneId = i;
            ((PhoneBase) (((PhoneProxy) (PhoneFactory.getPhone(phoneId))).getActivePhone())
                    ).mCi.unregisterForRadioStateChanged(this);
            log("unWaitRadioAvaliable, for gsm phone,  phoneId = " + phoneId);
        }
    }

    private boolean isRadioAvaliable() {
        boolean isRadioAvaliable = true;
        for (int i = 0; i < mPhoneCount; i++) {
            int phoneId = i;
            if (!isRadioAvaliable(phoneId)) {
                log("isRadioAvaliable=false, phoneId = " + phoneId);
                isRadioAvaliable = false;
                break;
            }
        }
        return isRadioAvaliable;
    }

    private boolean waitWorlModeSwitching(boolean enabled) {
        final boolean wait = isCdmaLteDcSupport() && !isWifiOnly()
                && WorldPhoneUtil.isWorldPhoneSwitching();
        log("waitWorlModeSwitching, enabled=" + enabled + ", wait=" + wait);
        if (wait) {
            // pending
            pendingAirplaneModeRequest(enabled);

            // wait for world mode switching
            sInSwitching.set(true);

            if (!mHasRegisterWorldModeReceiver) {
                registerWorldModeReceiver();
            }
        }
        return wait;
    }

    private void registerWorldModeReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_WORLD_MODE_CHANGED);
        mContext.registerReceiver(mWorldModeReceiver, filter);
        mHasRegisterWorldModeReceiver = true;
    }

    private void unRegisterWorldModeReceiver() {
        mContext.unregisterReceiver(mWorldModeReceiver);
        mHasRegisterWorldModeReceiver = false;
    }

    private boolean isWifiOnly() {
        final ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        final boolean isWifiOnly = !cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
        return isWifiOnly;
    }

    private static final boolean isCdmaLteDcSupport() {
        if (SystemProperties.get("ro.mtk_svlte_support").equals("1") ||
                SystemProperties.get("ro.mtk_srlte_support").equals("1")) {
            return true;
        } else {
            return false;
        }
    }

    private static void log(String s) {
        Rlog.d(LOG_TAG, "[RadioManager] " + s);
    }
}