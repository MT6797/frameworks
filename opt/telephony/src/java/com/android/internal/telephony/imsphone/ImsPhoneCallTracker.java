/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.CallLog;
import android.provider.Settings;
import android.preference.PreferenceManager;
import android.telecom.ConferenceParticipant;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
///M: ALPS02037830. @{
import android.telephony.TelephonyManager;
/// @}
import android.telephony.ServiceState;

import com.android.ims.ImsCall;
import com.android.ims.ImsCallProfile;
import com.android.ims.ImsConfig;
import com.android.ims.ImsConnectionStateListener;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsServiceClass;
import com.android.ims.ImsUtInterface;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.ims.internal.ImsVideoCallProviderWrapper;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_TERMINAL_BASED_CALL_WAITING_MODE;
///M: ALPS02037830. @{
import static com.android.internal.telephony.TelephonyProperties.TERMINAL_BASED_CALL_WAITING_DISABLED;
/// @}
import static com.android.internal.telephony.TelephonyProperties.TERMINAL_BASED_CALL_WAITING_ENABLED_OFF;

/// M: ALPS02136981. Prints debug logs for ImsPhone. @{
import com.mediatek.telecom.FormattedLog;
/// @}
import com.mediatek.telecom.TelecomManagerEx;

/**
 * {@hide}
 */
public final class ImsPhoneCallTracker extends CallTracker {
    static final String LOG_TAG = "ImsPhoneCallTracker";

    private static final boolean DBG = true;

    // When true, dumps the state of ImsPhoneCallTracker after changes to foreground and background
    // calls.  This is helpful for debugging.
    private static final boolean VERBOSE_STATE_LOGGING = false; /* stopship if true */

    //Indices map to ImsConfig.FeatureConstants
    private boolean[] mImsFeatureEnabled = {false, false, false, false};
    private final String[] mImsFeatureStrings = {"VoLTE", "ViLTE", "VoWiFi", "ViWiFi"};

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ImsManager.ACTION_IMS_INCOMING_CALL)) {
                if (DBG) log("onReceive : incoming call intent");

                if (mImsManager == null) return;

                if (mServiceId < 0) return;

                try {
                    // Network initiated USSD will be treated by mImsUssdListener
                    boolean isUssd = intent.getBooleanExtra(ImsManager.EXTRA_USSD, false);
                    if (isUssd) {
                        if (DBG) log("onReceive : USSD");
                        mUssdSession = mImsManager.takeCall(mServiceId, intent, mImsUssdListener);
                        if (mUssdSession != null) {
                            mUssdSession.accept(ImsCallProfile.CALL_TYPE_VOICE);
                        }
                        return;
                    }

                    // Normal MT call
                    ImsCall imsCall = mImsManager.takeCall(mServiceId, intent, mImsCallListener);
                    ImsPhoneConnection conn = new ImsPhoneConnection(mPhone.getContext(), imsCall,
                            ImsPhoneCallTracker.this, mRingingCall);
                    addConnection(conn);

                    setVideoCallProvider(conn, imsCall);

                    if ((mForegroundCall.getState() != ImsPhoneCall.State.IDLE) ||
                            (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE)) {
                        conn.update(imsCall, ImsPhoneCall.State.WAITING);
                    }

                    /// M: ALPS02136981. Prints debug logs for ImsPhone.
                    logDebugMessagesWithDumpFormat("CC", conn, "");

                    mPhone.notifyNewRingingConnection(conn);
                    mPhone.notifyIncomingRing();

                    updatePhoneState();
                    mPhone.notifyPreciseCallStateChanged();
                } catch (ImsException e) {
                    loge("onReceive : exception " + e);
                } catch (RemoteException e) {
                }
            }
        }
    };

    //***** Constants

    static final int MAX_CONNECTIONS = 7;
    static final int MAX_CONNECTIONS_PER_CALL = 5;

    private static final int EVENT_HANGUP_PENDINGMO = 18;
    private static final int EVENT_RESUME_BACKGROUND = 19;
    private static final int EVENT_DIAL_PENDINGMO = 20;

    private static final int TIMEOUT_HANGUP_PENDINGMO = 500;

    //***** Instance Variables
    private ArrayList<ImsPhoneConnection> mConnections = new ArrayList<ImsPhoneConnection>();
    private RegistrantList mVoiceCallEndedRegistrants = new RegistrantList();
    private RegistrantList mVoiceCallStartedRegistrants = new RegistrantList();

    final ImsPhoneCall mRingingCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_RINGING);
    final ImsPhoneCall mForegroundCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_FOREGROUND);
    final ImsPhoneCall mBackgroundCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_BACKGROUND);
    final ImsPhoneCall mHandoverCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_HANDOVER);

    private ImsPhoneConnection mPendingMO;
    private int mClirMode = CommandsInterface.CLIR_DEFAULT;
    private Object mSyncHold = new Object();

    private ImsCall mUssdSession = null;
    private Message mPendingUssd = null;

    ImsPhone mPhone;

    private boolean mDesiredMute = false;    // false = mute off
    private boolean mOnHoldToneStarted = false;

    PhoneConstants.State mState = PhoneConstants.State.IDLE;

    private ImsManager mImsManager;
    private int mServiceId = -1;

    private Call.SrvccState mSrvccState = Call.SrvccState.NONE;

    private boolean mIsInEmergencyCall = false;

    private int pendingCallClirMode;
    private int mPendingCallVideoState;
    private boolean pendingCallInEcm = false;
    private boolean mSwitchingFgAndBgCalls = false;
    private ImsCall mCallExpectedToResume = null;

    /// M: Redial as ECC @{
    private boolean mDialAsECC = false;
    /// @}

    /// M: ALPS02023277. @{
    /// Set this flag to true when there is a resume request not finished.
    /// Prevent to accept a waiting call during resuming a backdground call.
    private boolean mHasPendingResumeRequest = false;
    /// @}

    /// M: ALPS02261962. For IMS registration state and capability informaion.
    private int mImsRegistrationErrorCode;

    /// M: ALPS02462990 @{
    /// For OP01 requirement: Only one video call allowed.
    private static final int INVALID_CALL_MODE = 0xFF;
    private static final int IMS_VOICE_CALL = 20;
    private static final int IMS_VIDEO_CALL = 21;
    private static final int IMS_VOICE_CONF = 22;
    private static final int IMS_VIDEO_CONF = 23;
    private static final int IMS_VOICE_CONF_PARTS = 24;
    private static final int IMS_VIDEO_CONF_PARTS = 25;
    /// @}

    //***** Events


    //***** Constructors

    ImsPhoneCallTracker(ImsPhone phone) {
        this.mPhone = phone;

        IntentFilter intentfilter = new IntentFilter();
        intentfilter.addAction(ImsManager.ACTION_IMS_INCOMING_CALL);
        mPhone.getContext().registerReceiver(mReceiver, intentfilter);

        /// M: register the indication receiver. @{
        registerIndicationReceiver();
        /// @}

        Thread t = new Thread() {
            public void run() {
                getImsService();
            }
        };
        t.start();
    }

    private PendingIntent createIncomingCallPendingIntent() {
        Intent intent = new Intent(ImsManager.ACTION_IMS_INCOMING_CALL);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void getImsService() {
        if (DBG) log("getImsService");
        mImsManager = ImsManager.getInstance(mPhone.getContext(), mPhone.getPhoneId());
        /// M: ALPS02395308, thread safe for mImsManager. @{
        synchronized (mImsManager) {
        /// @}
            try {
                mServiceId = mImsManager.open(ImsServiceClass.MMTEL,
                        createIncomingCallPendingIntent(),
                        mImsConnectionStateListener);

                // Get the ECBM interface and set IMSPhone's listener object for notifications
                getEcbmInterface().setEcbmStateListener(mPhone.mImsEcbmStateListener);
                if (mPhone.isInEcm()) {
                    // Call exit ECBM which will invoke onECBMExited
                    mPhone.exitEmergencyCallbackMode();
                }
                int mPreferredTtyMode = Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
               mImsManager.setUiTTYMode(mPhone.getContext(), mServiceId, mPreferredTtyMode, null);

            } catch (ImsException e) {
                loge("getImsService: " + e);
                //Leave mImsManager as null, then CallStateException will be thrown when dialing
                mImsManager = null;
            }
        }
    }

    public void dispose() {
        if (DBG) log("dispose");
        mRingingCall.dispose();
        mBackgroundCall.dispose();
        mForegroundCall.dispose();
        mHandoverCall.dispose();

        clearDisconnected();
        mPhone.getContext().unregisterReceiver(mReceiver);
        /// M: It needs to unregister Indication receivers here.  @{
        unregisterIndicationReceiver();

        // close ImsService.
        if (mImsManager != null && mServiceId != -1) {
            /// M: ALPS02395308, thread safe for mImsManager. @{
            synchronized (mImsManager) {
            /// @}
                try {
                    mImsManager.close(mServiceId);
                } catch (ImsException e) {
                    loge("getImsService: " + e);
                }
                mServiceId = -1;
                mImsManager = null;
            }
        }
        /// @}

        /// M: for WFC-DS, ImsPhone is disposed when SIM-Switch. @{
        mPhone.setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
        mPhone.setImsRegistered(false);
        for (int i = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                 i <= ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI; i++) {
            mImsFeatureEnabled[i] = false;
        }
        mPhone.onFeatureCapabilityChanged();
        broadcastImsStatusChange();
        /// @}
    }

    @Override
    protected void finalize() {
        log("ImsPhoneCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    @Override
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallStartedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallStarted(Handler h) {
        mVoiceCallStartedRegistrants.remove(h);
    }

    @Override
    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallEndedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallEnded(Handler h) {
        mVoiceCallEndedRegistrants.remove(h);
    }

    Connection
    dial(String dialString, int videoState, Bundle intentExtras) throws CallStateException {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        /// M: Add phone id to support dual sim
        int oirMode = sp.getInt(PhoneBase.CLIR_KEY + mPhone.getPhoneId(),
        /// M: ALPS02275804 @{
        /// CommandsInterface.CLIR_DEFAULT will translate to PhoneConstants.PRESENTATION_UNKNOWN,
        /// it will cause unknown number displayed in call log. Change default value to
        /// CommandsInterface.CLIR_SUPPRESSION.
                CommandsInterface.CLIR_SUPPRESSION);
        /// @}
        return dial(dialString, oirMode, videoState, intentExtras);
    }

    /**
     * oirMode is one of the CLIR_ constants
     */
    synchronized Connection
    dial(String dialString, int clirMode, int videoState, Bundle intentExtras)
            throws CallStateException {
        boolean isPhoneInEcmMode = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_INECM_MODE, false);
        /// M: ALPS02579826, In DS architecture, IMS and CDMA shared the ECM system property,
        /// IMS can not dial out while UE entered ECM via CDMA.
        /// Set isPhoneInEcmMode as false and let IMS can dial out.@{
        isPhoneInEcmMode = false;
        /// @}
        boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(dialString);

        if (DBG) log("dial clirMode=" + clirMode);

        // note that this triggers call state changed notif
        clearDisconnected();

        if (mImsManager == null) {
            throw new CallStateException("service not available");
        }

        /// M: ALPS02015368, it should use GSMPhone to dial during SRVCC. @{
        /// When SRVCC, GSMPhone will continue the handover calls until disconnected.
        /// It should use GSMPhone to dial to prevent PS/CS call conflict problem.
        /// Throw CS_FALLBACK exception here will let GSMPhone to dial.
        if (mHandoverCall.mConnections.size() > 0) {
            log("SRVCC: there are connections during handover, trigger CSFB!");
            throw new CallStateException(ImsPhone.CS_FALLBACK);
        }
        /// @}

        /// M: ALPS02015368 and ALPS02298554. @{
        /*
         * In ALPS02015368, we should use GSMPhone to dial after SRVCC happened, until the
         * handover calls end.
         * In ALPS02298554, even the IMS over ePDG is connected, but there is a CS MT call happened
         * so we still need to use GSMPhone to dial.
         */
        if (mPhone != null && mPhone.getDefaultPhone() != null) {
            // default phone is GSMPhone or CDMAPhone, which owns the ImsPhone.
            Phone defaultPhone = mPhone.getDefaultPhone();
            if (defaultPhone.getState() != PhoneConstants.State.IDLE
                    && getState() == PhoneConstants.State.IDLE) {
                log("There are CS connections, trigger CSFB!");
                throw new CallStateException(ImsPhone.CS_FALLBACK);
            }
        }
        /// @}

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        if (isPhoneInEcmMode && isEmergencyNumber) {
            handleEcmTimer(ImsPhone.CANCEL_ECM_TIMER);
        }

        boolean holdBeforeDial = false;

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            if (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE) {
                //we should have failed in !canDial() above before we get here
                throw new CallStateException("cannot dial in current state");
            }
            // foreground call is empty for the newly dialed connection
            holdBeforeDial = true;
            // Cache the video state for pending MO call.
            mPendingCallVideoState = videoState;
            switchWaitingOrHoldingAndActive();
        }

        ImsPhoneCall.State fgState = ImsPhoneCall.State.IDLE;
        ImsPhoneCall.State bgState = ImsPhoneCall.State.IDLE;

        mClirMode = clirMode;

        synchronized (mSyncHold) {
            if (holdBeforeDial) {
                fgState = mForegroundCall.getState();
                bgState = mBackgroundCall.getState();

                //holding foreground call failed
                if (fgState == ImsPhoneCall.State.ACTIVE) {
                    throw new CallStateException("cannot dial in current state");
                }

                //holding foreground call succeeded
                if (bgState == ImsPhoneCall.State.HOLDING) {
                    holdBeforeDial = false;
                }
            }

            mPendingMO = new ImsPhoneConnection(mPhone.getContext(),
                    checkForTestEmergencyNumber(dialString), this, mForegroundCall);
        }
        addConnection(mPendingMO);

        /// M: add for debug @{
        log("IMS: dial() holdBeforeDial = " + holdBeforeDial + " isPhoneInEcmMode = " + isPhoneInEcmMode + " isEmergencyNumber = " + isEmergencyNumber);
        /// @}

        /// M: ALPS02136981. Prints debug logs for ImsPhone. @{
        logDebugMessagesWithOpFormat("CC", "Dial", mPendingMO, "");
        logDebugMessagesWithDumpFormat("CC", mPendingMO, "");
        /// @}

        if (!holdBeforeDial) {
            if ((!isPhoneInEcmMode) || (isPhoneInEcmMode && isEmergencyNumber)) {
                dialInternal(mPendingMO, clirMode, videoState);
            } else {
                try {
                    getEcbmInterface().exitEmergencyCallbackMode();
                } catch (ImsException e) {
                    e.printStackTrace();
                    throw new CallStateException("service not available");
                }
                mPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                pendingCallClirMode = clirMode;
                mPendingCallVideoState = videoState;
                pendingCallInEcm = true;
            }
        }

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();

        return mPendingMO;
    }

    private void handleEcmTimer(int action) {
        mPhone.handleTimerInEmergencyCallbackMode(action);
        switch (action) {
            case ImsPhone.CANCEL_ECM_TIMER:
                break;
            case ImsPhone.RESTART_ECM_TIMER:
                break;
            default:
                log("handleEcmTimer, unsupported action " + action);
        }
    }

    private void dialInternal(ImsPhoneConnection conn, int clirMode, int videoState) {
        if (conn == null) {
            return;
        }

        /// M:  For VoLTE enhanced conference call. @{
        if (conn.getConfDialStrings() == null) {
            /// @}
            if (conn.getAddress() == null || conn.getAddress().length() == 0
                    || conn.getAddress().indexOf(PhoneNumberUtils.WILD) >= 0) {
                // Phone number is invalid
                conn.setDisconnectCause(DisconnectCause.INVALID_NUMBER);
                sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                return;
            }
        }

        // Always unmute when initiating a new call
        setMute(false);
        int serviceType = PhoneNumberUtils.isEmergencyNumber(conn.getAddress()) ?
                ImsCallProfile.SERVICE_TYPE_EMERGENCY : ImsCallProfile.SERVICE_TYPE_NORMAL;

        /// M: @{
        // for some operation's request, we need to dial ECC with ATD. At these cases,
        // we will put the special ECC numbers in specialECCnumber list
        if (serviceType == ImsCallProfile.SERVICE_TYPE_EMERGENCY &&
                PhoneNumberUtils.isSpecialEmergencyNumber(conn.getAddress())) {
            serviceType = ImsCallProfile.SERVICE_TYPE_NORMAL;
        }
        /// @}

        /// M: Redial as ECC @{
        if (mDialAsECC) {
            serviceType = ImsCallProfile.SERVICE_TYPE_EMERGENCY;
            log("Dial as ECC: conn.getAddress(): " + conn.getAddress());
            mDialAsECC = false;
        }
        /// @}
        int callType = ImsCallProfile.getCallTypeFromVideoState(videoState);
        //TODO(vt): Is this sufficient?  At what point do we know the video state of the call?
        conn.setVideoState(videoState);

        try {
            /// M:  For VoLTE enhanced conference call. @{
            String[] callees = null;
            if (conn.getConfDialStrings() != null) {
                ArrayList<String> dialStrings = conn.getConfDialStrings();
                callees = (String[]) dialStrings.toArray(new String[dialStrings.size()]);
            } else {
                /// @}
                callees = new String[] { conn.getAddress() };
            }
            ImsCallProfile profile = mImsManager.createCallProfile(mServiceId,
                    serviceType, callType);
            profile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, clirMode);
            /// M:  For VoLTE enhanced conference call. @{
            if (conn.getConfDialStrings() != null) {
                profile.setCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, true);
            }
            /// @}

            ImsCall imsCall = mImsManager.makeCall(mServiceId, profile,
                    callees, mImsCallListener);
            conn.setImsCall(imsCall);

            setVideoCallProvider(conn, imsCall);
        } catch (ImsException e) {
            loge("dialInternal : " + e);
            conn.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
            sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
        } catch (RemoteException e) {
        }
    }

    /**
     * Accepts a call with the specified video state.  The video state is the video state that the
     * user has agreed upon in the InCall UI.
     *
     * @param videoState The video State
     * @throws CallStateException
     */
    void acceptCall (int videoState) throws CallStateException {
        /// M: ALPS02136981. Prints debug logs for ImsPhone.
        logDebugMessagesWithOpFormat("CC", "Answer", mRingingCall.getFirstConnection(), "");

        if (DBG) log("acceptCall");

        if (mForegroundCall.getState().isAlive()
                && mBackgroundCall.getState().isAlive()) {
            throw new CallStateException("cannot accept call");
        }

        if ((mRingingCall.getState() == ImsPhoneCall.State.WAITING)
                && mForegroundCall.getState().isAlive()) {
            setMute(false);
            // Cache video state for pending MT call.
            mPendingCallVideoState = videoState;
            switchWaitingOrHoldingAndActive();
        } else if (mRingingCall.getState().isRinging()) {
            if (DBG) log("acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            try {
                ImsCall imsCall = mRingingCall.getImsCall();
                if (imsCall != null) {
                    imsCall.accept(ImsCallProfile.getCallTypeFromVideoState(videoState));
                } else {
                    throw new CallStateException("no valid ims call");
                }
            } catch (ImsException e) {
                throw new CallStateException("cannot accept call");
            }
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void
    rejectCall () throws CallStateException {
        /// M: ALPS02136981. Prints debug logs for ImsPhone.
        logDebugMessagesWithOpFormat("CC", "Reject", mRingingCall.getFirstConnection(), "");

        if (DBG) log("rejectCall");

        if (mRingingCall.getState().isRinging()) {
            hangup(mRingingCall);
        } else {
            throw new CallStateException("phone not ringing");
        }
    }


    private void switchAfterConferenceSuccess() {
        if (DBG) log("switchAfterConferenceSuccess fg =" + mForegroundCall.getState() +
                ", bg = " + mBackgroundCall.getState());

        if (mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING) {
            log("switchAfterConferenceSuccess");
            mForegroundCall.switchWith(mBackgroundCall);
        }
    }

    void
    switchWaitingOrHoldingAndActive() throws CallStateException {
        if (DBG) log("switchWaitingOrHoldingAndActive");

        /// M: ALPS02136981. Prints debug logs for ImsPhone. @{
        ImsPhoneConnection conn = null;
        String msg = null;
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            conn = mForegroundCall.getFirstConnection();
            if (mBackgroundCall.getState().isAlive()) {
                msg = "switch with background connection:" + mBackgroundCall.getFirstConnection();
            } else {
                msg = "hold to background";
            }
        } else {
            conn = mBackgroundCall.getFirstConnection();
            msg = "unhold to foreground";
        }
        logDebugMessagesWithOpFormat("CC", "Swap", conn, msg);
        /// @}

        if (mRingingCall.getState() == ImsPhoneCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        }

        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            ImsCall imsCall = mForegroundCall.getImsCall();
            if (imsCall == null) {
                throw new CallStateException("no ims call");
            }

            // Swap the ImsCalls pointed to by the foreground and background ImsPhoneCalls.
            // If hold or resume later fails, we will swap them back.
            mSwitchingFgAndBgCalls = true;
            mCallExpectedToResume = mBackgroundCall.getImsCall();
            mForegroundCall.switchWith(mBackgroundCall);

            // Hold the foreground call; once the foreground call is held, the background call will
            // be resumed.
            try {
                imsCall.hold();

                // If there is no background call to resume, then don't expect there to be a switch.
                if (mCallExpectedToResume == null) {
                    mSwitchingFgAndBgCalls = false;
                }
            } catch (ImsException e) {
                mForegroundCall.switchWith(mBackgroundCall);
                /// M: ALPS02661571 @{
                /// Google issue. When we hold call just before merge completed, will got
                /// ImsException. Need to reset some variables or else someting strange might
                /// happen, such as two active may be exist.
                mSwitchingFgAndBgCalls = false;
                mCallExpectedToResume = null;
                /// @}
                throw new CallStateException(e.getMessage());
            }
        } else if (mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING) {
            resumeWaitingOrHolding();
        }
    }

    void
    conference() {
        /// M: ALPS02136981. Prints debug logs for ImsPhone. @{
        logDebugMessagesWithOpFormat("CC", "Conference", mForegroundCall.getFirstConnection(),
                " merge with " + mBackgroundCall.getFirstConnection());
        /// @}

        if (DBG) log("conference");

        ImsCall fgImsCall = mForegroundCall.getImsCall();
        if (fgImsCall == null) {
            log("conference no foreground ims call");
            return;
        }

        ImsCall bgImsCall = mBackgroundCall.getImsCall();
        if (bgImsCall == null) {
            log("conference no background ims call");
            return;
        }

       // Keep track of the connect time of the earliest call so that it can be set on the
        // {@code ImsConference} when it is created.
        long foregroundConnectTime = mForegroundCall.getEarliestConnectTime();
        long backgroundConnectTime = mBackgroundCall.getEarliestConnectTime();
        long conferenceConnectTime;
        if (foregroundConnectTime > 0 && backgroundConnectTime > 0) {
            conferenceConnectTime = Math.min(mForegroundCall.getEarliestConnectTime(),
                    mBackgroundCall.getEarliestConnectTime());
            log("conference - using connect time = " + conferenceConnectTime);
        } else if (foregroundConnectTime > 0) {
            log("conference - bg call connect time is 0; using fg = " + foregroundConnectTime);
            conferenceConnectTime = foregroundConnectTime;
        } else {
            log("conference - fg call connect time is 0; using bg = " + backgroundConnectTime);
            conferenceConnectTime = backgroundConnectTime;
        }

        ImsPhoneConnection foregroundConnection = mForegroundCall.getFirstConnection();
        if (foregroundConnection != null) {
            foregroundConnection.setConferenceConnectTime(conferenceConnectTime);
        }


        try {
            fgImsCall.merge(bgImsCall);
        } catch (ImsException e) {
            log("conference " + e.getMessage());
        }
    }

    void
    explicitCallTransfer() {
        //TODO : implement
    }

    void
    clearDisconnected() {
        if (DBG) log("clearDisconnected");

        internalClearDisconnected();

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();
    }

    boolean
    canConference() {
        return mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE
            && mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING
            && !mBackgroundCall.isFull()
            && !mForegroundCall.isFull();
    }

    boolean
    canDial() {
        boolean ret;
        int serviceState = mPhone.getServiceState().getState();
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");

        ret = (serviceState != ServiceState.STATE_POWER_OFF)
            && mPendingMO == null
            && !mRingingCall.isRinging()
            && !disableCall.equals("true")
            && (!mForegroundCall.getState().isAlive()
                    || !mBackgroundCall.getState().isAlive());

        /// M: add for debug @{
        log("IMS: canDial() serviceState = " + serviceState + ", disableCall = " + disableCall + " , mPendingMO = " + mPendingMO + ", mRingingCall.isRinging() = "
            + mRingingCall.isRinging() + ", mForegroundCall.getState().isAlive() = " + mForegroundCall.getState().isAlive() +
            ", mBackgroundCall.getState().isAlive() = " + mBackgroundCall.getState().isAlive());
        /// @}

        return ret;
    }

    boolean
    canTransfer() {
        return mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE
            && mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING;
    }

    //***** Private Instance Methods

    private void
    internalClearDisconnected() {
        mRingingCall.clearDisconnected();
        mForegroundCall.clearDisconnected();
        mBackgroundCall.clearDisconnected();
        mHandoverCall.clearDisconnected();
    }

    private void
    updatePhoneState() {
        PhoneConstants.State oldState = mState;

        if (mRingingCall.isRinging()) {
            mState = PhoneConstants.State.RINGING;
        } else if (mPendingMO != null ||
                !(mForegroundCall.isIdle() && mBackgroundCall.isIdle())) {
            mState = PhoneConstants.State.OFFHOOK;
        } else {
            mState = PhoneConstants.State.IDLE;
        }

        if (mState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallEndedRegistrants.notifyRegistrants(
                    new AsyncResult(null, null, null));
        } else if (oldState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallStartedRegistrants.notifyRegistrants (
                    new AsyncResult(null, null, null));
        }

        if (DBG) log("updatePhoneState oldState=" + oldState + ", newState=" + mState);

        if (mState != oldState) {
            mPhone.notifyPhoneStateChanged();
        }
    }

    private void
    handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void
    dumpState() {
        List l;

        log("Phone State:" + mState);

        log("Ringing call: " + mRingingCall.toString());

        l = mRingingCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

        log("Foreground call: " + mForegroundCall.toString());

        l = mForegroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

        log("Background call: " + mBackgroundCall.toString());

        l = mBackgroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

    }

    //***** Called from ImsPhone

    void setUiTTYMode(int uiTtyMode, Message onComplete) {
        try {
            mImsManager.setUiTTYMode(mPhone.getContext(), mServiceId, uiTtyMode, onComplete);
        } catch (ImsException e) {
            loge("setTTYMode : " + e);
            mPhone.sendErrorResponse(onComplete, e);
        }
    }

    /*package*/ void setMute(boolean mute) {
        mDesiredMute = mute;
        mForegroundCall.setMute(mute);
    }

    /*package*/ boolean getMute() {
        return mDesiredMute;
    }

    /* package */ void sendDtmf(char c, Message result) {
        if (DBG) log("sendDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.sendDtmf(c, result);
        }
    }

    /*package*/ void
    startDtmf(char c) {
        if (DBG) log("startDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.startDtmf(c);
        } else {
            loge("startDtmf : no foreground call");
        }
    }

    /*package*/ void
    stopDtmf() {
        if (DBG) log("stopDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.stopDtmf();
        } else {
            loge("stopDtmf : no foreground call");
        }
    }

    //***** Called from ImsPhoneConnection

    /*package*/ void
    hangup (ImsPhoneConnection conn) throws CallStateException {
        if (DBG) log("hangup connection");

        if (conn.getOwner() != this) {
            throw new CallStateException ("ImsPhoneConnection " + conn
                    + "does not belong to ImsPhoneCallTracker " + this);
        }

        hangup(conn.getCall());
    }

    //***** Called from ImsPhoneCall

    /* package */ void
    hangup (ImsPhoneCall call) throws CallStateException {
        if (DBG) log("hangup call");

        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections");
        }

        ImsCall imsCall = call.getImsCall();
        boolean rejectCall = false;

        if (call == mRingingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup incoming");
            rejectCall = true;
        } else if (call == mForegroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
            } else {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup foreground");
                }
                //held call will be resumed by onCallTerminated
            }
        } else if (call == mBackgroundCall) {
            if (Phone.DEBUG_PHONE) {
                log("(backgnd) hangup waiting or background");
            }
        } else {
            throw new CallStateException ("ImsPhoneCall " + call +
                    "does not belong to ImsPhoneCallTracker " + this);
        }

        call.onHangupLocal();

        try {
            if (imsCall != null) {
                if (rejectCall) imsCall.reject(ImsReasonInfo.CODE_USER_DECLINE);
                else imsCall.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
            } else if (mPendingMO != null && call == mForegroundCall) {
                // is holding a foreground call
                mPendingMO.update(null, ImsPhoneCall.State.DISCONNECTED);
                mPendingMO.onDisconnect();
                removeConnection(mPendingMO);
                mPendingMO = null;
                updatePhoneState();
                removeMessages(EVENT_DIAL_PENDINGMO);
            }
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }

        mPhone.notifyPreciseCallStateChanged();
    }

    void callEndCleanupHandOverCallIfAny() {
        if (mHandoverCall.mConnections.size() > 0) {
            if (DBG) log("callEndCleanupHandOverCallIfAny, mHandoverCall.mConnections="
                    + mHandoverCall.mConnections);
            /// M: ALPS01979162 make sure all connections of mHandoverCall are removed from
            /// mConnections to prevent leak @{
            for (Connection conn : mHandoverCall.mConnections) {
                log("SRVCC: remove connection=" + conn);
                removeConnection((ImsPhoneConnection) conn);
            }
            /// @}
            mHandoverCall.mConnections.clear();
            mState = PhoneConstants.State.IDLE;

            /// M: ALPS02192901. @{
            if (mPhone != null && mPhone.mDefaultPhone != null
                    && mPhone.mDefaultPhone.getState() == PhoneConstants.State.IDLE) {
                // If the call is disconnected before GSMPhone poll calls, the phone state of
                // GSMPhone keeps in idle state, so it will not notify phone state changed. In this
                // case, ImsPhone needs to notify by itself.
                log("SRVCC: notify ImsPhone state as idle.");
                mPhone.notifyPhoneStateChanged();
            }
            /// @}
        }
    }

    /// M: @{
    void hangupAll() throws CallStateException {
        if (DBG) {
            log("hangupAll");
        }

        if (mImsManager == null) {
            throw new CallStateException("No ImsManager Instance");
        }

        try {
            mImsManager.hangupAllCall();
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }

        if (!mRingingCall.isIdle()) {
            mRingingCall.onHangupLocal();
        }
        if (!mForegroundCall.isIdle()) {
            mForegroundCall.onHangupLocal();
        }
        if (!mBackgroundCall.isIdle()) {
            mBackgroundCall.onHangupLocal();
        }
    }
    /// @}

    /* package */
    void resumeWaitingOrHolding() throws CallStateException {
        if (DBG) log("resumeWaitingOrHolding");

        try {
            if (mForegroundCall.getState().isAlive()) {
                //resume foreground call after holding background call
                //they were switched before holding
                ImsCall imsCall = mForegroundCall.getImsCall();
                if (imsCall != null) imsCall.resume();
            } else if (mRingingCall.getState() == ImsPhoneCall.State.WAITING) {
                /// M: ALPS02023277. @{
                /// Skip this accept since there is a resume operation not finished.
                if (mHasPendingResumeRequest) {
                    log("there is a pending resume background request, ignore accept()!");
                    return;
                }
                /// @}
                //accept waiting call after holding background call
                ImsCall imsCall = mRingingCall.getImsCall();
                if (imsCall != null) {
                    imsCall.accept(
                        ImsCallProfile.getCallTypeFromVideoState(mPendingCallVideoState));
                }
            } else {
                //Just resume background call.
                //To distinguish resuming call with swapping calls
                //we do not switch calls.here
                //ImsPhoneConnection.update will chnage the parent when completed
                ImsCall imsCall = mBackgroundCall.getImsCall();
                if (imsCall != null) imsCall.resume();
                /// M: ALPS02023277. @{
                /// Enable this flag to block the operation of accepting call.
                mHasPendingResumeRequest = true;
                log("turn on the resuem pending request lock!");
                /// @}
            }
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }
    }

    /* package */
    void sendUSSD (String ussdString, Message response) {
        if (DBG) log("sendUSSD");

        try {
            if (mUssdSession != null) {
                mUssdSession.sendUssd(ussdString);
                AsyncResult.forMessage(response, null, null);
                response.sendToTarget();
                return;
            }

            String[] callees = new String[] { ussdString };
            ImsCallProfile profile = mImsManager.createCallProfile(mServiceId,
                    ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
            profile.setCallExtraInt(ImsCallProfile.EXTRA_DIALSTRING,
                    ImsCallProfile.DIALSTRING_USSD);

            /// M: Assign return message for UE initiated USSI @{
            mPendingUssd = response;
            /// @}
            mUssdSession = mImsManager.makeCall(mServiceId, profile,
                    callees, mImsUssdListener);
        } catch (ImsException e) {
            loge("sendUSSD : " + e);
            mPhone.sendErrorResponse(response, e);
        }
    }

    /* package */
    void cancelUSSD() {
        if (mUssdSession == null) return;

        try {
            mUssdSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
        } catch (ImsException e) {
        }

    }

    private synchronized ImsPhoneConnection findConnection(final ImsCall imsCall) {
        for (ImsPhoneConnection conn : mConnections) {
            if (conn.getImsCall() == imsCall) {
                return conn;
            }
        }
        return null;
    }

    private synchronized void removeConnection(ImsPhoneConnection conn) {
        mConnections.remove(conn);
    }

    private synchronized void addConnection(ImsPhoneConnection conn) {
        mConnections.add(conn);
    }

    private void processCallStateChange(ImsCall imsCall, ImsPhoneCall.State state, int cause) {
        if (DBG) log("processCallStateChange " + imsCall + " state=" + state + " cause=" + cause);
        // This method is called on onCallUpdate() where there is not necessarily a call state
        // change. In these situations, we'll ignore the state related updates and only process
        // the change in media capabilities (as expected).  The default is to not ignore state
        // changes so we do not change existing behavior.
        processCallStateChange(imsCall, state, cause, false /* do not ignore state update */);
    }

    private void processCallStateChange(ImsCall imsCall, ImsPhoneCall.State state, int cause,
            boolean ignoreState) {
        if (DBG) {
            log("processCallStateChange state=" + state + " cause=" + cause
                    + " ignoreState=" + ignoreState);
        }

        if (imsCall == null) return;

        boolean changed = false;
        ImsPhoneConnection conn = findConnection(imsCall);

        if (conn == null) {
            // TODO : what should be done?
            return;
        }

        // processCallStateChange is triggered for onCallUpdated as well.
        // onCallUpdated should not modify the state of the call
        // It should modify only other capabilities of call through updateMediaCapabilities
        // State updates will be triggered through individual callbacks
        // i.e. onCallHeld, onCallResume, etc and conn.update will be responsible for the update
        if (ignoreState) {
            conn.updateMediaCapabilities(imsCall);
            return;
        }

        changed = conn.update(imsCall, state);
        if (state == ImsPhoneCall.State.DISCONNECTED) {
            changed = conn.onDisconnect(cause) || changed;
            //detach the disconnected connections
            conn.getCall().detach(conn);
            removeConnection(conn);
        }

        /// M: ALPS02136981. Prints debug logs for ImsPhone.
        logDebugMessagesWithDumpFormat("CC", conn, "");

        if (changed) {
            if (conn.getCall() == mHandoverCall) return;
            updatePhoneState();
            mPhone.notifyPreciseCallStateChanged();
        }
    }

    private int getDisconnectCauseFromReasonInfo(ImsReasonInfo reasonInfo) {
        int cause = DisconnectCause.ERROR_UNSPECIFIED;

        //int type = reasonInfo.getReasonType();
        int code = reasonInfo.getCode();
        switch (code) {
            case ImsReasonInfo.CODE_SIP_BAD_ADDRESS:
            case ImsReasonInfo.CODE_SIP_NOT_REACHABLE:
                return DisconnectCause.NUMBER_UNREACHABLE;

            case ImsReasonInfo.CODE_SIP_BUSY:
                return DisconnectCause.BUSY;

            case ImsReasonInfo.CODE_USER_TERMINATED:
                return DisconnectCause.LOCAL;

            case ImsReasonInfo.CODE_LOCAL_CALL_DECLINE:
                return DisconnectCause.INCOMING_REJECTED;

            case ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE:
                return DisconnectCause.NORMAL;

            case ImsReasonInfo.CODE_SIP_REDIRECTED:
            case ImsReasonInfo.CODE_SIP_BAD_REQUEST:
            case ImsReasonInfo.CODE_SIP_FORBIDDEN:
            case ImsReasonInfo.CODE_SIP_NOT_ACCEPTABLE:
            case ImsReasonInfo.CODE_SIP_USER_REJECTED:
            case ImsReasonInfo.CODE_SIP_GLOBAL_ERROR:
                return DisconnectCause.SERVER_ERROR;

            case ImsReasonInfo.CODE_SIP_SERVICE_UNAVAILABLE:
            case ImsReasonInfo.CODE_SIP_NOT_FOUND:
            /// M: ALPS02112553 for WFC, extend more reason handling. @{
            //case ImsReasonInfo.CODE_SIP_SERVER_ERROR:
                /// @}
                return DisconnectCause.SERVER_UNREACHABLE;

            case ImsReasonInfo.CODE_LOCAL_NETWORK_ROAMING:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_IP_CHANGED:
            case ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN:
            case ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE:
            case ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_NO_LTE_COVERAGE:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_NO_SERVICE:
            case ImsReasonInfo.CODE_LOCAL_CALL_VCC_ON_PROGRESSING:
                return DisconnectCause.OUT_OF_SERVICE;

            case ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT:
            case ImsReasonInfo.CODE_TIMEOUT_1XX_WAITING:
            case ImsReasonInfo.CODE_TIMEOUT_NO_ANSWER:
            case ImsReasonInfo.CODE_TIMEOUT_NO_ANSWER_CALL_UPDATE:
                return DisconnectCause.TIMED_OUT;

            case ImsReasonInfo.CODE_LOCAL_LOW_BATTERY:
            case ImsReasonInfo.CODE_LOCAL_POWER_OFF:
                return DisconnectCause.POWER_OFF;
            /// M: @{
            case ImsReasonInfo.CODE_SIP_REDIRECTED_EMERGENCY:
                return DisconnectCause.IMS_EMERGENCY_REREG;
            /// @}

            case ImsReasonInfo.CODE_SIP_SERVER_ERROR:
                return DisconnectCause.SERVER_UNREACHABLE;

            case ImsReasonInfo.CODE_SIP_WIFI_SIGNAL_LOST:
                 return DisconnectCause.WFC_WIFI_SIGNAL_LOST;
            case ImsReasonInfo.CODE_SIP_WFC_ISP_PROBLEM:
                 return DisconnectCause.WFC_ISP_PROBLEM;
            case ImsReasonInfo.CODE_SIP_HANDOVER_WIFI_FAIL:
                 return DisconnectCause.WFC_HANDOVER_WIFI_FAIL;
            case ImsReasonInfo.CODE_SIP_HANDOVER_LTE_FAIL:
                 return DisconnectCause.WFC_HANDOVER_LTE_FAIL;

            /// @}

            default:
        }

        return cause;
    }

    /**
     * Listen to the IMS call state change
     */
    private ImsCall.Listener mImsCallListener = new ImsCall.Listener() {
        @Override
        public void onCallProgressing(ImsCall imsCall) {
            if (DBG) log("onCallProgressing");

            mPendingMO = null;
            processCallStateChange(imsCall, ImsPhoneCall.State.ALERTING,
                    DisconnectCause.NOT_DISCONNECTED);
        }

        @Override
        public void onCallStarted(ImsCall imsCall) {
            if (DBG) log("onCallStarted");

            mPendingMO = null;
            processCallStateChange(imsCall, ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);
        }

        @Override
        public void onCallUpdated(ImsCall imsCall) {
            if (DBG) log("onCallUpdated");
            if (imsCall == null) {
                return;
            }
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                processCallStateChange(imsCall, conn.getCall().mState,
                        DisconnectCause.NOT_DISCONNECTED, true /*ignore state update*/);
            }
        }

        /**
         * onCallStartFailed will be invoked when:
         * case 1) Dialing fails
         * case 2) Ringing call is disconnected by local or remote user
         */
        @Override
        public void onCallStartFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallStartFailed reasonCode=" + reasonInfo.getCode());

            if (mPendingMO != null) {
                // To initiate dialing circuit-switched call
                if (reasonInfo.getCode() == ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED
                        && mBackgroundCall.getState() == ImsPhoneCall.State.IDLE
                        && mRingingCall.getState() == ImsPhoneCall.State.IDLE) {
                    mForegroundCall.detach(mPendingMO);
                    removeConnection(mPendingMO);
                    mPendingMO.finalize();
                    mPendingMO = null;
                    mPhone.initiateSilentRedial();
                    return;
                } else {
                    int cause = getDisconnectCauseFromReasonInfo(reasonInfo);
                    /// M: Redial as ECC @{
                    if (cause == DisconnectCause.IMS_EMERGENCY_REREG) {
                        mDialAsECC = true;
                    }
                    /// @}

                    /// M: ALPS02082337. Reset pendingMO before disconnect the connection.
                    mPendingMO = null;

                    processCallStateChange(imsCall, ImsPhoneCall.State.DISCONNECTED, cause);
                }
            }
        }

        @Override
        public void onCallTerminated(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallTerminated reasonCode=" + reasonInfo.getCode());

            ImsPhoneCall.State oldState = mForegroundCall.getState();
            int cause = getDisconnectCauseFromReasonInfo(reasonInfo);
            ImsPhoneConnection conn = findConnection(imsCall);
            if (DBG) log("cause = " + cause + " conn = " + conn);

            if (conn != null && conn.isIncoming() && conn.getConnectTime() == 0) {
                // Missed
                if (cause == DisconnectCause.NORMAL) {
                    cause = DisconnectCause.INCOMING_MISSED;
                }
                if (DBG) log("Incoming connection of 0 connect time detected - translated cause = "
                        + cause);

            }
            /// M: ALPS02315648 @{
            /// Google issue. When merge complete, it will set ImsReasonInfo to
            /// ImsReasonInfo.CODE_UNSPECIFIED in ImsCall.maybeMarkPeerAsMerged().
            /// ImsReasonInfo.CODE_UNSPECIFIED will be translated to
            /// DisconnectCause.ERROR_UNSPECIFIED. If doesn't set cause to IMS_MERGED_SUCCESSFULLY
            /// here, it will show end call toast.
            // if (cause == DisconnectCause.NORMAL && conn != null
            //         && conn.getImsCall().isMerged()) {
            if (cause == DisconnectCause.ERROR_UNSPECIFIED && conn != null
                    && conn.getImsCall().isMerged()) {
            /// @}
                // Call was terminated while it is merged instead of a remote disconnect.
                cause = DisconnectCause.IMS_MERGED_SUCCESSFULLY;
            }

            /// M: Redial as ECC @{
            if (cause == DisconnectCause.IMS_EMERGENCY_REREG) {
                mDialAsECC = true;
            }
            /// @}

            processCallStateChange(imsCall, ImsPhoneCall.State.DISCONNECTED, cause);
        }

        @Override
        public void onCallHeld(ImsCall imsCall) {
            if (DBG) {
                if (mForegroundCall.getImsCall() == imsCall) {
                    log("onCallHeld (fg) " + imsCall);
                } else if (mBackgroundCall.getImsCall() == imsCall) {
                    log("onCallHeld (bg) " + imsCall);
                }
            }

            synchronized (mSyncHold) {
                ImsPhoneCall.State oldState = mBackgroundCall.getState();
                processCallStateChange(imsCall, ImsPhoneCall.State.HOLDING,
                        DisconnectCause.NOT_DISCONNECTED);

                // Note: If we're performing a switchWaitingOrHoldingAndActive, the call to
                // processCallStateChange above may have caused the mBackgroundCall and
                // mForegroundCall references below to change meaning.  Watch out for this if you
                // are reading through this code.
                if (oldState == ImsPhoneCall.State.ACTIVE) {
                    // Note: This case comes up when we have just held a call in response to a
                    // switchWaitingOrHoldingAndActive.  We now need to resume the background call.
                    // The EVENT_RESUME_BACKGROUND causes resumeWaitingOrHolding to be called.
                    if ((mForegroundCall.getState() == ImsPhoneCall.State.HOLDING)
                            || (mRingingCall.getState() == ImsPhoneCall.State.WAITING)) {

                            sendEmptyMessage(EVENT_RESUME_BACKGROUND);
                    } else {
                        //when multiple connections belong to background call,
                        //only the first callback reaches here
                        //otherwise the oldState is already HOLDING
                        if (mPendingMO != null) {
                            sendEmptyMessage(EVENT_DIAL_PENDINGMO);
                        }

                        // In this case there will be no call resumed, so we can assume that we
                        // are done switching fg and bg calls now.
                        // This may happen if there is no BG call and we are holding a call so that
                        // we can dial another one.
                        mSwitchingFgAndBgCalls = false;
                    }
                }
            }
        }

        @Override
        public void onCallHoldFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallHoldFailed reasonCode=" + reasonInfo.getCode());

            synchronized (mSyncHold) {
                ImsPhoneCall.State bgState = mBackgroundCall.getState();
                if (reasonInfo.getCode() == ImsReasonInfo.CODE_LOCAL_CALL_TERMINATED) {
                    // disconnected while processing hold
                    /// M: ALPS02147333 Although call is held failed, but we treat it like call
                    /// held succeeded if the call has been terminated @{
                    if ((mForegroundCall.getState() == ImsPhoneCall.State.HOLDING)
                            || (mRingingCall.getState() == ImsPhoneCall.State.WAITING)) {
                        if (DBG) {
                            log("onCallHoldFailed resume background");
                        }
                        sendEmptyMessage(EVENT_RESUME_BACKGROUND);
                    } else {
                        if (mPendingMO != null) {
                            sendEmptyMessage(EVENT_DIAL_PENDINGMO);
                        }
                    }
                    /// @}
                } else if (bgState == ImsPhoneCall.State.ACTIVE) {
                    mForegroundCall.switchWith(mBackgroundCall);

                    if (mPendingMO != null) {
                        mPendingMO.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
                        sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                    /// M: Notify Telecom to remove pending action by onActionFailed(). @{
                    } else {
                        mPhone.notifySuppServiceFailed(Phone.SuppService.SWITCH);
                    }
                    /// @}
                }
            }
        }

        @Override
        public void onCallResumed(ImsCall imsCall) {
            if (DBG) log("onCallResumed");

            // If we are the in midst of swapping FG and BG calls and the call we end up resuming
            // is not the one we expected, we likely had a resume failure and we need to swap the
            // FG and BG calls back.
            if (mSwitchingFgAndBgCalls && imsCall != mCallExpectedToResume) {
                if (DBG) {
                    log("onCallResumed : switching " + mForegroundCall + " with "
                            + mBackgroundCall);
                }
                mForegroundCall.switchWith(mBackgroundCall);
                mSwitchingFgAndBgCalls = false;
                mCallExpectedToResume = null;
            }

            /// M: ALPS02023277. @{
            /// Reset this flag since call is resumed.
            /// This flag is used to prevent to accept a waiting call,
            /// during resuming a backdground call.
            mHasPendingResumeRequest = false;
            /// @}

            processCallStateChange(imsCall, ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);
        }

        @Override
        public void onCallResumeFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            /// M: ALPS02023277. For debug purpose. @{
            if (DBG) {
                log("onCallResumeFailed");
            }
            /// @}

            // TODO : What should be done?
            // If we are in the midst of swapping the FG and BG calls and we got a resume fail, we
            // need to swap back the FG and BG calls.
            if (mSwitchingFgAndBgCalls && imsCall == mCallExpectedToResume) {
                if (DBG) {
                    log("onCallResumeFailed : switching " + mForegroundCall + " with "
                            + mBackgroundCall);
                }
                mForegroundCall.switchWith(mBackgroundCall);
                mCallExpectedToResume = null;
                mSwitchingFgAndBgCalls = false;
            }

            /// M: ALPS02023277. @{
            /// Reset this flag since resume operation is done.
            /// This flag is used to prevent to accept a waiting call,
            /// during resuming a backdground call.
            mHasPendingResumeRequest = false;
            /// @}

            mPhone.notifySuppServiceFailed(Phone.SuppService.RESUME);
        }

        @Override
        public void onCallResumeReceived(ImsCall imsCall) {
            if (DBG) log("onCallResumeReceived");

            if (mOnHoldToneStarted) {
                mPhone.stopOnHoldTone();
                mOnHoldToneStarted = false;
            }
        }

        @Override
        public void onCallHoldReceived(ImsCall imsCall) {
            if (DBG) log("onCallHoldReceived");

            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null && conn.getState() == ImsPhoneCall.State.ACTIVE) {
                if (!mOnHoldToneStarted && ImsPhoneCall.isLocalTone(imsCall)) {
                    mPhone.startOnHoldTone();
                    mOnHoldToneStarted = true;
                }
            }
        }

        @Override
        public void onCallMerged(final ImsCall call, final ImsCall peerCall, boolean swapCalls) {
            if (DBG) log("onCallMerged");

            ImsPhoneCall foregroundImsPhoneCall = findConnection(call).getCall();
            ImsPhoneConnection peerConnection = findConnection(peerCall);
            ImsPhoneCall peerImsPhoneCall = peerConnection == null ? null
                    : peerConnection.getCall();

            if (swapCalls) {
                switchAfterConferenceSuccess();
            }
            foregroundImsPhoneCall.merge(peerImsPhoneCall, ImsPhoneCall.State.ACTIVE);

            // TODO Temporary code. Remove the try-catch block from the runnable once thread
            // synchronization is fixed.
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        final ImsPhoneConnection conn = findConnection(call);
                        log("onCallMerged: ImsPhoneConnection=" + conn);
                        log("onCallMerged: CurrentVideoProvider=" + conn.getVideoProvider());
                        setVideoCallProvider(conn, call);
                        log("onCallMerged: CurrentVideoProvider=" + conn.getVideoProvider());
                    } catch (Exception e) {
                        loge("onCallMerged: exception " + e);
                    }
                }
            };

            ImsPhoneCallTracker.this.post(r);

            // After merge complete, update foreground as Active
            // and background call as Held, if background call exists
            processCallStateChange(mForegroundCall.getImsCall(), ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);
            if (peerConnection != null) {
                processCallStateChange(mBackgroundCall.getImsCall(), ImsPhoneCall.State.HOLDING,
                    DisconnectCause.NOT_DISCONNECTED);
            }

            // Check if the merge was requested by an existing conference call. In that
            // case, no further action is required.
            if (!call.isMergeRequestedByConf()) {
                log("onCallMerged :: calling onMultipartyStateChanged()");
                onMultipartyStateChanged(call, true);
            } else {
                log("onCallMerged :: Merge requested by existing conference.");
                // Reset the flag.
                call.resetIsMergeRequestedByConf(false);
            }
            logState();

            /// M: ALPS02136981. For formatted log, workaround for merge case. @{
            ImsPhoneConnection hostConn = findConnection(call);
            if (hostConn != null) {
                FormattedLog formattedLog = new FormattedLog.Builder()
                        .setCategory("CC").setServiceName("ImsPhone")
                        .setOpType(FormattedLog.OpType.DUMP)
                        .setCallNumber(hostConn.getAddress())
                        .setCallId(getConnectionCallId(hostConn))
                        .setStatusInfo("state", "disconnected")
                        .setStatusInfo("isConfCall", "No")
                        .setStatusInfo("isConfChildCall", "No")
                        .setStatusInfo("parent", hostConn.getParentCallName())
                        .buildDumpInfo();

                if (formattedLog != null) {
                    log(formattedLog.toString());
                }
            }
            /// @}
        }

        @Override
        public void onCallMergeFailed(ImsCall call, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallMergeFailed reasonInfo=" + reasonInfo);

            // TODO: the call to notifySuppServiceFailed throws up the "merge failed" dialog
            // We should move this into the InCallService so that it is handled appropriately
            // based on the user facing UI.
            mPhone.notifySuppServiceFailed(Phone.SuppService.CONFERENCE);

            // Start plumbing this even through Telecom so other components can take
            // appropriate action.
            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.onConferenceMergeFailed();
            }
        }

        /**
         * Called when the state of IMS conference participant(s) has changed.
         *
         * @param call the call object that carries out the IMS call.
         * @param participants the participant(s) and their new state information.
         */
        @Override
        public void onConferenceParticipantsStateChanged(ImsCall call,
                List<ConferenceParticipant> participants) {
            if (DBG) log("onConferenceParticipantsStateChanged");

            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.updateConferenceParticipants(participants);
            }
        }

       @Override
        public void onCallSessionTtyModeReceived(ImsCall call, int mode) {
            mPhone.onTtyModeReceived(mode);
        }

        @Override
        public void onCallHandover(ImsCall imsCall, int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
            if (DBG) {
                log("onCallHandover ::  srcAccessTech=" + srcAccessTech + ", targetAccessTech=" +
                    targetAccessTech + ", reasonInfo=" + reasonInfo);
            }
        }

        @Override
        public void onCallHandoverFailed(ImsCall imsCall, int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
            if (DBG) {
                log("onCallHandoverFailed :: srcAccessTech=" + srcAccessTech +
                    ", targetAccessTech=" + targetAccessTech + ", reasonInfo=" + reasonInfo);
            }
        }

        /**
         * Handles a change to the multiparty state for an {@code ImsCall}.  Notifies the associated
         * {@link ImsPhoneConnection} of the change.
         *
         * @param imsCall The IMS call.
         * @param isMultiParty {@code true} if the call became multiparty, {@code false}
         *      otherwise.
         */
        @Override
        public void onMultipartyStateChanged(ImsCall imsCall, boolean isMultiParty) {
            if (DBG) log("onMultipartyStateChanged to " + (isMultiParty ? "Y" : "N"));

            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.updateMultipartyState(isMultiParty);
            }
        }

        /// M: @{
        @Override
        public void onCallInviteParticipantsRequestDelivered(ImsCall call) {
            if (DBG) {
                log("onCallInviteParticipantsRequestDelivered");
            }

            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.notifyConferenceParticipantsInvited(true);
            }
        }

        @Override
        public void onCallInviteParticipantsRequestFailed(ImsCall call, ImsReasonInfo reasonInfo) {
            if (DBG) {
                log("onCallInviteParticipantsRequestFailed reasonCode=" +
                    reasonInfo.getCode());
            }
            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.notifyConferenceParticipantsInvited(false);
            }
        }
        /// @}

        /// M: ALPS02256671. For PAU information changed. @{
        @Override
        public void onPauInfoChanged(ImsCall call) {
            if (DBG) {
                log("onPauInfoChanged");
            }
            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.updatePauInfo(call);
            }
        }
        /// @}
    };

    /**
     * Listen to the IMS call state change
     */
    private ImsCall.Listener mImsUssdListener = new ImsCall.Listener() {
        @Override
        public void onCallStarted(ImsCall imsCall) {
            if (DBG) log("mImsUssdListener onCallStarted");

            if (imsCall == mUssdSession) {
                if (mPendingUssd != null) {
                    AsyncResult.forMessage(mPendingUssd);
                    mPendingUssd.sendToTarget();
                    mPendingUssd = null;
                }
            }
        }

        @Override
        public void onCallStartFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("mImsUssdListener onCallStartFailed reasonCode=" + reasonInfo.getCode());

            onCallTerminated(imsCall, reasonInfo);
        }

        @Override
        public void onCallTerminated(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("mImsUssdListener onCallTerminated reasonCode=" + reasonInfo.getCode());

            if (imsCall == mUssdSession) {
                mUssdSession = null;
                if (mPendingUssd != null) {
                    CommandException ex =
                            new CommandException(CommandException.Error.GENERIC_FAILURE);
                    AsyncResult.forMessage(mPendingUssd, null, ex);
                    mPendingUssd.sendToTarget();
                    mPendingUssd = null;
                }
            }
            imsCall.close();
        }

        @Override
        public void onCallUssdMessageReceived(ImsCall call,
                int mode, String ussdMessage) {
            if (DBG) log("mImsUssdListener onCallUssdMessageReceived mode=" + mode);

            int ussdMode = -1;

            switch(mode) {
                case ImsCall.USSD_MODE_REQUEST:
                    ussdMode = CommandsInterface.USSD_MODE_REQUEST;
                    break;

                case ImsCall.USSD_MODE_NOTIFY:
                    ussdMode = CommandsInterface.USSD_MODE_NOTIFY;
                    /// M: After USSI notify, close this USSI session. @{
                    if (call == mUssdSession) {
                        mUssdSession = null;
                        call.close();
                    }
                    /// @}
                    break;
            }

            mPhone.onIncomingUSSD(ussdMode, ussdMessage);
        }
    };

    /**
     * Listen to the IMS service state change
     *
     */
    private ImsConnectionStateListener mImsConnectionStateListener =
        new ImsConnectionStateListener() {
        @Override
        public void onImsConnected() {
            if (DBG) log("onImsConnected");
            mPhone.setServiceState(ServiceState.STATE_IN_SERVICE);
            mPhone.setImsRegistered(true);
        }

        @Override
        public void onImsDisconnected(ImsReasonInfo imsReasonInfo) {
            if (DBG) log("onImsDisconnected imsReasonInfo=" + imsReasonInfo);
            mPhone.setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
            mPhone.setImsRegistered(false);
            mPhone.processDisconnectReason(imsReasonInfo);
            /// M: ALPS02261962. For IMS registration state and capability informaion. @{
            if (imsReasonInfo != null && imsReasonInfo.getExtraMessage() != null
                    && !imsReasonInfo.getExtraMessage().equals("")) {
                mImsRegistrationErrorCode = Integer.parseInt(imsReasonInfo.getExtraMessage());
            }
            /// @}
        }

        @Override
        public void onImsProgressing() {
            if (DBG) log("onImsProgressing");
        }

        @Override
        public void onImsResumed() {
            if (DBG) log("onImsResumed");
            mPhone.setServiceState(ServiceState.STATE_IN_SERVICE);
        }

        @Override
        public void onImsSuspended() {
            if (DBG) log("onImsSuspended");
            mPhone.setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
        }

        @Override
        public void onFeatureCapabilityChanged(int serviceClass,
                int[] enabledFeatures, int[] disabledFeatures) {
            if (serviceClass == ImsServiceClass.MMTEL) {
                boolean tmpIsVideoCallEnabled = isVideoCallEnabled();
                // Check enabledFeatures to determine capabilities. We ignore disabledFeatures.
                for (int  i = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                        i <= ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI; i++) {
                    if (enabledFeatures[i] == i) {
                        // If the feature is set to its own integer value it is enabled.
                        if (DBG) log("onFeatureCapabilityChanged(" + i + ", " + mImsFeatureStrings[i] + "): value=true");
                        mImsFeatureEnabled[i] = true;
                    } else if (enabledFeatures[i]
                            == ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN) {
                        // FEATURE_TYPE_UNKNOWN indicates that a feature is disabled.
                        if (DBG) log("onFeatureCapabilityChanged(" + i + ", " + mImsFeatureStrings[i] + "): value=false");
                        mImsFeatureEnabled[i] = false;
                    } else {
                        // Feature has unknown state; it is not its own value or -1.
                        if (DBG) {
                            loge("onFeatureCapabilityChanged(" + i + ", " +mImsFeatureStrings[i] + "): unexpectedValue="
                                + enabledFeatures[i]);
                        }
                    }
                }
                if (tmpIsVideoCallEnabled != isVideoCallEnabled()) {
                    mPhone.notifyForVideoCapabilityChanged(isVideoCallEnabled());
                }

                // TODO: Use the ImsCallSession or ImsCallProfile to tell the initial Wifi state and
                // {@link ImsCallSession.Listener#callSessionHandover} to listen for changes to
                // wifi capability caused by a handover.
                if (DBG) log("onFeatureCapabilityChanged: isVowifiEnabled=" + isVowifiEnabled());
                for (ImsPhoneConnection connection : mConnections) {
                    connection.updateWifiState();
                }

                mPhone.onFeatureCapabilityChanged();

                /// M: ALPS02261962. For IMS registration state and capability informaion.
                broadcastImsStatusChange();
            }
        }
    };

    /* package */
    ImsUtInterface getUtInterface() throws ImsException {
        if (mImsManager == null) {
            throw new ImsException("no ims manager", ImsReasonInfo.CODE_UNSPECIFIED);
        }

        ImsUtInterface ut = mImsManager.getSupplementaryServiceConfiguration(mServiceId);
        return ut;
    }

    private void transferHandoverConnections(ImsPhoneCall call) {
        if (call.mConnections != null) {
            for (Connection c : call.mConnections) {
                c.mPreHandoverState = call.mState;
                log ("Connection state before handover is " + c.getStateBeforeHandover());

                /// M: for conference SRVCC. @{
                c.mPreMultipartyState = c.isMultiparty();
                c.mPreMultipartyHostState = c instanceof ImsPhoneConnection
                        && ((ImsPhoneConnection)c).isConferenceHost();
                log("SRVCC: Connection isMultiparty is " + c.mPreMultipartyState +
                        "and isConfHost is " + c.mPreMultipartyHostState + " before handover");
                /// @}
            }
        }
        if (mHandoverCall.mConnections == null ) {
            mHandoverCall.mConnections = call.mConnections;
        } else { // Multi-call SRVCC
            mHandoverCall.mConnections.addAll(call.mConnections);
        }
        if (mHandoverCall.mConnections != null) {
            if (call.getImsCall() != null) {
                call.getImsCall().close();
            }
            for (Connection c : mHandoverCall.mConnections) {
                ((ImsPhoneConnection)c).changeParent(mHandoverCall);
                ((ImsPhoneConnection)c).releaseWakeLock();
            }
        }
        if (call.getState().isAlive()) {
            log ("Call is alive and state is " + call.mState);
            mHandoverCall.mState = call.mState;
        }
        call.mConnections.clear();
        call.mState = ImsPhoneCall.State.IDLE;
        /// M: ALPS02589783 @{
        // If ringback tone flag is set for foreground call, after SRVCC it has no chance to reset.
        // We reset manually when handover happened.
        call.resetRingbackTone();
        /// @}
    }

    /* package */
    void notifySrvccState(Call.SrvccState state) {
        if (DBG) log("notifySrvccState state=" + state);

        mSrvccState = state;

        if (mSrvccState == Call.SrvccState.COMPLETED) {
            transferHandoverConnections(mForegroundCall);
            transferHandoverConnections(mBackgroundCall);
            transferHandoverConnections(mRingingCall);
            /// M: ALPS02015368 mPendingMO should be cleared when fake SRVCC/bSRVCC happens,
            /// or dial function will fail @{
            if (mPendingMO != null) {
                log("SRVCC: reset mPendingMO");
                removeConnection(mPendingMO);
                mPendingMO = null;
            }
            /// @}
        }
    }

    //****** Overridden from Handler

    @Override
    public void
    handleMessage (Message msg) {
        AsyncResult ar;
        if (DBG) log("handleMessage what=" + msg.what);

        switch (msg.what) {
            case EVENT_HANGUP_PENDINGMO:
                if (mPendingMO != null) {
                    mPendingMO.onDisconnect();
                    removeConnection(mPendingMO);
                    mPendingMO = null;
                }

                updatePhoneState();
                mPhone.notifyPreciseCallStateChanged();
                break;
            case EVENT_RESUME_BACKGROUND:
                try {
                    resumeWaitingOrHolding();
                } catch (CallStateException e) {
                    if (Phone.DEBUG_PHONE) {
                        loge("handleMessage EVENT_RESUME_BACKGROUND exception=" + e);
                    }
                }
                break;
            case EVENT_DIAL_PENDINGMO:
                dialInternal(mPendingMO, mClirMode, mPendingCallVideoState);
                break;

            case EVENT_EXIT_ECM_RESPONSE_CDMA:
                // no matter the result, we still do the same here
                if (pendingCallInEcm) {
                    dialInternal(mPendingMO, pendingCallClirMode,
                            mPendingCallVideoState);
                    pendingCallInEcm = false;
                }
                mPhone.unsetOnEcbModeExitResponse(this);
                break;
        }
    }

    @Override
    protected void log(String msg) {
        Rlog.d(LOG_TAG, "[ImsPhoneCallTracker] " + msg);
    }

    protected void loge(String msg) {
        Rlog.e(LOG_TAG, "[ImsPhoneCallTracker] " + msg);
    }

    /**
     * Logs the current state of the ImsPhoneCallTracker.  Useful for debugging issues with
     * call tracking.
     */
    /* package */
    void logState() {
        if (!VERBOSE_STATE_LOGGING) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Current IMS PhoneCall State:\n");
        sb.append(" Foreground: ");
        sb.append(mForegroundCall);
        sb.append("\n");
        sb.append(" Background: ");
        sb.append(mBackgroundCall);
        sb.append("\n");
        sb.append(" Ringing: ");
        sb.append(mRingingCall);
        sb.append("\n");
        sb.append(" Handover: ");
        sb.append(mHandoverCall);
        sb.append("\n");
        Rlog.v(LOG_TAG, sb.toString());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ImsPhoneCallTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mVoiceCallEndedRegistrants=" + mVoiceCallEndedRegistrants);
        pw.println(" mVoiceCallStartedRegistrants=" + mVoiceCallStartedRegistrants);
        pw.println(" mRingingCall=" + mRingingCall);
        pw.println(" mForegroundCall=" + mForegroundCall);
        pw.println(" mBackgroundCall=" + mBackgroundCall);
        pw.println(" mHandoverCall=" + mHandoverCall);
        pw.println(" mPendingMO=" + mPendingMO);
        //pw.println(" mHangupPendingMO=" + mHangupPendingMO);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mDesiredMute=" + mDesiredMute);
        pw.println(" mState=" + mState);
    }

    @Override
    protected void handlePollCalls(AsyncResult ar) {
    }

    /* package */
    ImsEcbm getEcbmInterface() throws ImsException {
        if (mImsManager == null) {
            throw new ImsException("no ims manager", ImsReasonInfo.CODE_UNSPECIFIED);
        }

        ImsEcbm ecbm = mImsManager.getEcbmInterface(mServiceId);
        return ecbm;
    }

    public boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    }

    public boolean isVolteEnabled() {
        return mImsFeatureEnabled[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE];
    }

    public boolean isVowifiEnabled() {
        return mImsFeatureEnabled[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI];
    }

    public boolean isVideoCallEnabled() {
        return (mImsFeatureEnabled[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                || mImsFeatureEnabled[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]);
    }

    @Override
    public PhoneConstants.State getState() {
        return mState;
    }

    private void setVideoCallProvider(ImsPhoneConnection conn, ImsCall imsCall)
            throws RemoteException {
        IImsVideoCallProvider imsVideoCallProvider =
                imsCall.getCallSession().getVideoCallProvider();
        if (imsVideoCallProvider != null) {
            ImsVideoCallProviderWrapper imsVideoCallProviderWrapper =
                    new ImsVideoCallProviderWrapper(imsVideoCallProvider);
            conn.setVideoProvider(imsVideoCallProviderWrapper);
        }
    }

    /// M: For ACTION_IMS_INCOMING_CALL_INDICATION, mIndicationReceiver is
    /// responsible to handle it @{

    private BroadcastReceiver mIndicationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ImsManager.ACTION_IMS_INCOMING_CALL_INDICATION)) {
                if (DBG) log("onReceive : indication call intent");

                if (mImsManager == null) {
                    if (DBG) log("no ims manager");
                    return;
                }

                boolean isAllow = true; /// default value is always allowed to take call
                int serviceId = intent.getIntExtra(ImsManager.EXTRA_SERVICE_ID, -1);

                /// M: ALPS02037830. Needs to use Phone-Id to query call waiting setting.
                String callWaitingSetting = TelephonyManager.getTelephonyProperty(
                        mPhone.getPhoneId(),
                        PROPERTY_TERMINAL_BASED_CALL_WAITING_MODE,
                        TERMINAL_BASED_CALL_WAITING_DISABLED);
                /// @}

                if (callWaitingSetting.equals(
                        TERMINAL_BASED_CALL_WAITING_ENABLED_OFF) == true
                        && mForegroundCall != null
                        && mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
                    log("PROPERTY_TERMINAL_BASED_CALL_WAITING_MODE = "
                            + "TERMINAL_BASED_CALL_WAITING_ENABLED_OFF."
                            + " Reject the call as UDUB ");
                    isAllow = false;
                }

                // Now we handle ECC/MT conflict issue.
                if (isEccExist()) {
                    log("there is an ECC call, dis-allow this incoming call!");
                    isAllow = false;
                }

                if (hasVideoCallRestriction(context, intent)) {
                    isAllow = false;
                    addCallLog(context, intent);
                }

                if (DBG) log("setCallIndication : serviceId = " + serviceId
                        + ", intent = " + intent
                        + ", isAllow = " + isAllow);
                try {
                    mImsManager.setCallIndication(mServiceId, intent, isAllow);
                } catch (ImsException e) {
                    loge("setCallIndication ImsException " + e);
                }
            }
        }
    };

    private void registerIndicationReceiver() {
        if (DBG) log("registerIndicationReceiver");
        IntentFilter intentfilter = new IntentFilter();
        intentfilter.addAction(ImsManager.ACTION_IMS_INCOMING_CALL_INDICATION);
        mPhone.getContext().registerReceiver(mIndicationReceiver, intentfilter);

    }
    private void unregisterIndicationReceiver() {
        if (DBG) log("unregisterIndicationReceiver");
        mPhone.getContext().unregisterReceiver(mIndicationReceiver);
    }

    /**
     * for ALPS01987345, workaround for Modem, block incoming call when ECC exist.
     * @return true if there is ECC.
     */
    private boolean isEccExist() {
        ImsPhoneCall[] allCalls = {mForegroundCall, mBackgroundCall, mRingingCall, mHandoverCall};

        for (int i = 0; i < allCalls.length; i++) {
            if (!allCalls[i].getState().isAlive()) {
                continue;
            }

            ImsCall imsCall = allCalls[i].getImsCall();
            if (imsCall == null) {
                continue;
            }

            ImsCallProfile callProfile = imsCall.getCallProfile();
            if (callProfile != null &&
                    callProfile.mServiceType == ImsCallProfile.SERVICE_TYPE_EMERGENCY) {
                return true;
            }
        }

        log("isEccExist(): no ECC!");
        return false;
    }

    private boolean hasVideoCallRestriction(Context context, Intent intent) {
        if (mPhone == null
                || mPhone.isFeatureSupported(Phone.FeatureType.VIDEO_RESTRICTION) == false) {
            return false;
        }
        if (mForegroundCall.isIdle() && mBackgroundCall.isIdle()) {
            return false;
        }

        // check if already has video call
        boolean hasVideoCall = false;
        ImsPhoneConnection fgConn = mForegroundCall.getFirstConnection();
        ImsPhoneConnection bgConn = mBackgroundCall.getFirstConnection();
        if (fgConn != null) {
            hasVideoCall |= VideoProfile.isVideo(fgConn.getVideoState());
        }
        if (bgConn != null) {
            hasVideoCall |= VideoProfile.isVideo(bgConn.getVideoState());
        }

        // is incoming video call
        boolean incomingVideoCall = isIncomingVideoCall(intent);
        return hasVideoCall | incomingVideoCall;
    }

    private void addCallLog(Context context, Intent intent) {
        // get PhoneAccountHandle
        PhoneAccountHandle phoneAccountHandle = null;
        final TelecomManager telecomManager = TelecomManager.from(context);
        final Iterator<PhoneAccountHandle> phoneAccounts =
                telecomManager.getCallCapablePhoneAccounts().listIterator();
        while (phoneAccounts.hasNext()) {
            final PhoneAccountHandle handle = phoneAccounts.next();
            String id = handle.getId();
            if (id != null && id.equals(mPhone.getIccSerialNumber())) {
                log("iccid matches");
                phoneAccountHandle = handle;
                break;
            }
        }
        // call number
        String number = intent.getStringExtra(ImsManager.EXTRA_DIAL_STRING);
        if (number == null) {
            number = "";
        }

        // presentation type
        int presentationMode;
        if (number == null || number == "") {
            presentationMode = PhoneConstants.PRESENTATION_RESTRICTED;
        } else {
            presentationMode = PhoneConstants.PRESENTATION_ALLOWED;
        }

        // check is video incoming call
        boolean isVideoIncoming = isIncomingVideoCall(intent);
        int features = 0;
        if (isVideoIncoming) {
            features |= CallLog.Calls.FEATURES_VIDEO;
        }

        // add call log
        CallLog.Calls.addCall(null, context, number,
                presentationMode, CallLog.Calls.MISSED_TYPE, features,
                phoneAccountHandle,
                new Date().getTime(), 0, new Long(0));
    }

    private boolean isIncomingVideoCall(Intent intent) {
        if (intent == null) {
            return false;
        }
        int callMode = intent.getIntExtra(ImsManager.EXTRA_CALL_MODE, 0);
        if (callMode == IMS_VIDEO_CALL || callMode == IMS_VIDEO_CONF
                || callMode == IMS_VIDEO_CONF_PARTS) {
            return true;
        } else {
            return false;
        }
    }
    /// @}

    /// M: For VoLTE enhanced conference call. @{
    Connection
    dial(List<String> numbers, int videoState) throws CallStateException {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        int oirMode = sp.getInt(PhoneBase.CLIR_KEY + mPhone.getPhoneId(),
        // ALPS02275804
        // CommandsInterface.CLIR_DEFAULT will translate to PhoneConstants.PRESENTATION_UNKNOWN,
        // it will cause unknown number displayed in call log. Change default value to
        // CommandsInterface.CLIR_SUPPRESSION.
                CommandsInterface.CLIR_SUPPRESSION);
        return dial(numbers, oirMode, videoState);
    }

    synchronized Connection
    dial(List<String> numbers, int clirMode, int videoState) throws CallStateException {
        if (DBG) {
            log("dial clirMode=" + clirMode);
        }

        // note that this triggers call state changed notif
        clearDisconnected();

        if (mImsManager == null) {
            throw new CallStateException("service not available");
        }

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        boolean holdBeforeDial = false;

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            if (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE) {
                //we should have failed in !canDial() above before we get here
                throw new CallStateException("cannot dial in current state");
            }
            // foreground call is empty for the newly dialed connection
            holdBeforeDial = true;
            switchWaitingOrHoldingAndActive();
        }

        ImsPhoneCall.State fgState = ImsPhoneCall.State.IDLE;
        ImsPhoneCall.State bgState = ImsPhoneCall.State.IDLE;

        mClirMode = clirMode;

        synchronized (mSyncHold) {
            if (holdBeforeDial) {
                fgState = mForegroundCall.getState();
                bgState = mBackgroundCall.getState();

                //holding foreground call failed
                if (fgState == ImsPhoneCall.State.ACTIVE) {
                    throw new CallStateException("cannot dial in current state");
                }

                //holding foreground call succeeded
                if (bgState == ImsPhoneCall.State.HOLDING) {
                    holdBeforeDial = false;
                }
            }

            // Create IMS conference host connection
            mPendingMO = new ImsPhoneConnection(mPhone.getContext(), "",
                ImsPhoneCallTracker.this, mForegroundCall);

            ArrayList<String> dialStrings = new ArrayList<String>();
            for (String str : numbers) {
                dialStrings.add(PhoneNumberUtils.extractNetworkPortionAlt(str));
            }
            mPendingMO.setConfDialStrings(dialStrings);
        }
        addConnection(mPendingMO);

        /// M: ALPS02136981. Prints debug logs for ImsPhone. @{
        StringBuilder sb = new StringBuilder();
        for (String number : numbers) {
            sb.append(number);
            sb.append(", ");
        }
        logDebugMessagesWithOpFormat("CC", "DialConf", mPendingMO, " numbers=" + sb.toString());
        logDebugMessagesWithDumpFormat("CC", mPendingMO, "");
        /// @}

        if (!holdBeforeDial) {
            dialInternal(mPendingMO, clirMode, videoState);
        }

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();

        return mPendingMO;
    }
    /// @}

    /// M: ALPS02261962. For IMS registration state and capability informaion. @{
    /**
     * Notifies upper application about ims registration and feature capability information
     * via intent.
     *
     * @param enabledFeatures Enabled Feature array
     */
    private void broadcastImsStatusChange() {
        if (mPhone == null) {
            return;
        }

        Intent intent = new Intent(ImsManager.ACTION_IMS_STATE_CHANGED);
        int serviceState = mPhone.getServiceState().getState();
        int errorCode = mImsRegistrationErrorCode;
        boolean[] enabledFeatures = mImsFeatureEnabled;

        if (DBG) {
            log("broadcastImsStateChange state= " + serviceState + " errorCode= " + errorCode
                + " enabledFeatures= " + enabledFeatures);
        }

        intent.putExtra(ImsManager.EXTRA_IMS_REG_STATE_KEY, serviceState);
        if (serviceState != ServiceState.STATE_IN_SERVICE
                && errorCode > ImsReasonInfo.CODE_UNSPECIFIED) {
            intent.putExtra(ImsManager.EXTRA_IMS_REG_ERROR_KEY, errorCode);
        }
        intent.putExtra(ImsManager.EXTRA_IMS_ENABLE_CAP_KEY, enabledFeatures);
        intent.putExtra(ImsManager.EXTRA_PHONE_ID, mPhone.getPhoneId());
        mPhone.getContext().sendBroadcast(intent);
    }
    /// @}

    /// M: ALPS02136981. Prints debug logs for ImsPhone. @{
    /**
     * Logs unified debug log messages for "OP".
     * Format: [category][Module][OP][Action][call-number][local-call-ID] Msg. String.
     * P.S. uses the RIL call ID as the local call ID.
     *
     * @param category currently we only have 'CC' category.
     * @param action the action name. (e.q. Dial, Hold, etc.)
     * @param conn the connection instance.
     * @param msg the optional messages
     * @hide
     */
    void logDebugMessagesWithOpFormat(
            String category, String action, ImsPhoneConnection conn, String msg) {
        if (category == null || action == null || conn == null) {
            // return if no mandatory tags.
            return;
        }

        FormattedLog formattedLog = new FormattedLog.Builder()
                .setCategory(category)
                .setServiceName("ImsPhone")
                .setOpType(FormattedLog.OpType.OPERATION)
                .setActionName(action)
                .setCallNumber(getCallNumber(conn))
                .setCallId(getConnectionCallId(conn))
                .setExtraMessage(msg)
                .buildDebugMsg();

        if (formattedLog != null) {
            log(formattedLog.toString());
        }
    }

    /**
     * Logs unified debug log messages, for "Dump".
     * format: [CC][Module][Dump][call-number][local-call-ID]-[name:value],[name:value]-Msg. String
     * P.S. uses the RIL call ID as the local call ID.
     *
     * @param category currently we only have 'CC' category.
     * @param conn the ImsPhoneConnection to be dumped.
     * @param msg the optional messages
     * @hide
     */
    void logDebugMessagesWithDumpFormat(String category, ImsPhoneConnection conn, String msg) {
        if (category == null || conn == null) {
            // return if no mandatory tags.
            return;
        }

        FormattedLog formattedLog = new FormattedLog.Builder()
                .setCategory("CC")
                .setServiceName("ImsPhone")
                .setOpType(FormattedLog.OpType.DUMP)
                .setCallNumber(getCallNumber(conn))
                .setCallId(getConnectionCallId(conn))
                .setExtraMessage(msg)
                .setStatusInfo("state", conn.getState().toString())
                .setStatusInfo("isConfCall", conn.isMultiparty() ? "Yes" : "No")
                .setStatusInfo("isConfChildCall", "No")
                .setStatusInfo("parent", conn.getParentCallName())
                .buildDumpInfo();

        if (formattedLog != null) {
            log(formattedLog.toString());
        }
    }

    /**
    * get call ID of the imsPhoneConnection. (the same as RIL code ID)
    *
    * @param conn imsPhoneConnection
    * @return call ID string.
    * @hide
    */
    private String getConnectionCallId(ImsPhoneConnection conn) {
        if (conn == null) {
            return "";
        }

        int callId = conn.getCallId();
        if (callId == -1) {
            callId = conn.getCallIdBeforeDisconnected();
            if (callId == -1) {
                return "";
            }
        }
        return String.valueOf(callId);
    }

    /**
     * get call number of the imsPhoneConnection.
     *
     * @param conn imsPhoneConnection
     * @return call ID number.
     * @hide
     */
    private String getCallNumber(ImsPhoneConnection conn) {
        if (conn == null) {
            return null;
        }

        if (conn.isMultiparty()) {
            return "conferenceCall";
        } else {
            return conn.getAddress();
        }
    }
    /// @}
}
