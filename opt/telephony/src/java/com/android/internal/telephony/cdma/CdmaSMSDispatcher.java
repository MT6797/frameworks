/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Telephony.Sms;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.GsmAlphabet;
// MTK-START
import com.android.internal.telephony.IccUtils;
// MTK-END
import com.android.internal.telephony.ImsSMSDispatcher;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsUsageMonitor;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.sms.UserData;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// MTK-START
import java.util.ArrayList;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.SmsRawData;
import java.util.List;

import static android.telephony.SmsManager.STATUS_ON_ICC_READ;
import static android.telephony.SmsManager.STATUS_ON_ICC_UNREAD;
import static android.telephony.SmsManager.STATUS_ON_ICC_SENT;
import static android.telephony.SmsManager.STATUS_ON_ICC_UNSENT;
import static android.telephony.SmsManager.RESULT_ERROR_SUCCESS;
import static android.telephony.SmsManager.RESULT_ERROR_SIM_MEM_FULL;
import static android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE;
import static android.telephony.SmsManager.RESULT_ERROR_NULL_PDU;
import static android.telephony.SmsManager.RESULT_ERROR_INVALID_ADDRESS;
import static android.telephony.SmsManager.EXTRA_PARAMS_VALIDITY_PERIOD;
import static android.telephony.SmsManager.EXTRA_PARAMS_ENCODING_TYPE;
// add for OMH
import com.mediatek.internal.telephony.uicc.IccFileAdapter;
// MTK-END

public class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CdmaSMSDispatcher";
    private static final boolean VDBG = false;

    // MTK-START: add for OMH
    private IccFileAdapter mIccFileAdapter = null;
    // MTK-END

    public CdmaSMSDispatcher(PhoneBase phone, SmsUsageMonitor usageMonitor,
            ImsSMSDispatcher imsSMSDispatcher) {
        super(phone, usageMonitor, imsSMSDispatcher);
        Rlog.d(TAG, "CdmaSMSDispatcher created");
        // MTK-START: add for OMH
        mIccFileAdapter = new IccFileAdapter(mContext, phone);
        // MTK-END
    }

    @Override
    protected String getFormat() {
        return SmsConstants.FORMAT_3GPP2;
    }

    /**
     * Send the SMS status report to the dispatcher thread to process.
     * @param sms the CDMA SMS message containing the status report
     */
    void sendStatusReportMessage(SmsMessage sms) {
        if (VDBG) Rlog.d(TAG, "sending EVENT_HANDLE_STATUS_REPORT message");
        sendMessage(obtainMessage(EVENT_HANDLE_STATUS_REPORT, sms));
    }

    @Override
    protected void handleStatusReport(Object o) {
        if (o instanceof SmsMessage) {
            if (VDBG) Rlog.d(TAG, "calling handleCdmaStatusReport()");
            handleCdmaStatusReport((SmsMessage) o);
        } else {
            Rlog.e(TAG, "handleStatusReport() called for object type " + o.getClass().getName());
        }
    }

    /**
     * Called from parent class to handle status report from {@code CdmaInboundSmsHandler}.
     * @param sms the CDMA SMS message to process
     */
    void handleCdmaStatusReport(SmsMessage sms) {
        for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
            SmsTracker tracker = deliveryPendingList.get(i);
            if (tracker.mMessageRef == sms.mMessageRef) {
                // Found it.  Remove from list and broadcast.
                deliveryPendingList.remove(i);
                // Update the message status (COMPLETE)
                tracker.updateSentMessageStatus(mContext, Sms.STATUS_COMPLETE);

                PendingIntent intent = tracker.mDeliveryIntent;
                Intent fillIn = new Intent();
                fillIn.putExtra("pdu", sms.getPdu());
                fillIn.putExtra("format", getFormat());
                try {
                    intent.send(mContext, Activity.RESULT_OK, fillIn);
                } catch (CanceledException ex) {}
                break;  // Only expect to see one tracker matching this message.
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {

        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));
        if (pdu != null) {
            HashMap map = getSmsTrackerMap(destAddr, scAddr, destPort, data, pdu);
            SmsTracker tracker = getSmsTracker(map, sentIntent, deliveryIntent, getFormat(),
                    null /*messageUri*/, false /*isExpectMore*/, null /*fullMessageText*/,
                    false /*isText*/, true /*persistMessage*/);

            String carrierPackage = getCarrierAppPackageName();
            if (carrierPackage != null) {
                Rlog.d(TAG, "Found carrier package.");
                DataSmsSender smsSender = new DataSmsSender(tracker);
                smsSender.sendSmsByCarrierApp(carrierPackage, new SmsSenderCallback(smsSender));
            } else {
                Rlog.v(TAG, "No carrier package.");
                sendSubmitPdu(tracker);
            }
        } else {
            Rlog.e(TAG, "CdmaSMSDispatcher.sendData(): getSubmitPdu() returned null");
            if (sentIntent != null) {
                try {
                    sentIntent.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE);
                } catch (CanceledException ex) {
                    Rlog.e(TAG, "Intent has been canceled!");
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendText(String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, Uri messageUri, String callingPkg,
            boolean persistMessage) {
        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null), null);
        if (pdu != null) {
            HashMap map = getSmsTrackerMap(destAddr, scAddr, text, pdu);
            SmsTracker tracker = getSmsTracker(map, sentIntent, deliveryIntent, getFormat(),
                    messageUri, false /*isExpectMore*/, text, true /*isText*/, persistMessage);

            String carrierPackage = getCarrierAppPackageName();
            if (carrierPackage != null) {
                Rlog.d(TAG, "Found carrier package.");
                TextSmsSender smsSender = new TextSmsSender(tracker);
                smsSender.sendSmsByCarrierApp(carrierPackage, new SmsSenderCallback(smsSender));
            } else {
                Rlog.v(TAG, "No carrier package.");
                sendSubmitPdu(tracker);
            }
        } else {
            Rlog.e(TAG, "CdmaSMSDispatcher.sendText(): getSubmitPdu() returned null");
            if (sentIntent != null) {
                try {
                    sentIntent.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE);
                } catch (CanceledException ex) {
                    Rlog.e(TAG, "Intent has been canceled!");
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void injectSmsPdu(byte[] pdu, String format, PendingIntent receivedIntent) {
        throw new IllegalStateException("This method must be called only on ImsSMSDispatcher");
    }

    /** {@inheritDoc} */
    @Override
    protected GsmAlphabet.TextEncodingDetails calculateLength(CharSequence messageBody,
            boolean use7bitOnly) {
        return SmsMessage.calculateLength(messageBody, use7bitOnly, false);
    }

    /** {@inheritDoc} */
    @Override
    protected SmsTracker getNewSubmitPduTracker(String destinationAddress, String scAddress,
            String message, SmsHeader smsHeader, int encoding,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean lastPart,
            AtomicInteger unsentPartCount, AtomicBoolean anyPartFailed, Uri messageUri,
            String fullMessageText) {
        UserData uData = new UserData();
        uData.payloadStr = message;
        uData.userDataHeader = smsHeader;
        if (encoding == SmsConstants.ENCODING_7BIT) {
            // MTK-START: according to the spec of China Telecom, we need to use
            // 7BIT_ASCII as the 7bit encoding type, otherwise, we may meet the issue
            // that the long sms cannot be received in Shenzhen
            /*
            uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
            */
            uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
            // MTK-END
        } else { // assume UTF-16
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        }
        uData.msgEncodingSet = true;

        /* By setting the statusReportRequested bit only for the
         * last message fragment, this will result in only one
         * callback to the sender when that last fragment delivery
         * has been acknowledged. */
        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destinationAddress,
                uData, (deliveryIntent != null) && lastPart);

        HashMap map = getSmsTrackerMap(destinationAddress, scAddress,
                message, submitPdu);
        return getSmsTracker(map, sentIntent, deliveryIntent,
                getFormat(), unsentPartCount, anyPartFailed, messageUri, smsHeader,
                false /*isExpextMore*/, fullMessageText, true /*isText*/,
                true /*persistMessage*/);
    }

    @Override
    protected void sendSubmitPdu(SmsTracker tracker) {
        if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false)) {
            if (VDBG) {
                Rlog.d(TAG, "Block SMS in Emergency Callback mode");
            }
            tracker.onFailed(mContext, SmsManager.RESULT_ERROR_NO_SERVICE, 0/*errorCode*/);
            return;
        }
        sendRawPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSms(SmsTracker tracker) {
        HashMap<String, Object> map = tracker.mData;

        // byte[] smsc = (byte[]) map.get("smsc");  // unused for CDMA
        byte[] pdu = (byte[]) map.get("pdu");

        // MTK-START
        boolean isReadySend = false;
        synchronized (mSTrackersQueue) {
            if (mSTrackersQueue.isEmpty() || mSTrackersQueue.get(0) != tracker) {
                Rlog.d(TAG, "Add tracker into the list: " + tracker);
                mSTrackersQueue.add(tracker);
            }
            if (mSTrackersQueue.get(0) == tracker) {
                isReadySend = true;
            }
        }

        if (!isReadySend) {
            Rlog.d(TAG, "There is another tracker in-queue and is sending");
            return;
        }
        // MTK-END

        Rlog.d(TAG, "sendSms: "
                + " isIms()=" + isIms()
                + " mRetryCount=" + tracker.mRetryCount
                + " mImsRetry=" + tracker.mImsRetry
                + " mMessageRef=" + tracker.mMessageRef
                + " SS=" + mPhone.getServiceState().getState());

        sendSmsByPstn(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSmsByPstn(SmsTracker tracker) {
        int ss = mPhone.getServiceState().getState();
        // if sms over IMS is not supported on data and voice is not available...
        if (!isIms() && ss != ServiceState.STATE_IN_SERVICE) {
            tracker.onFailed(mContext, getNotInServiceError(ss), 0/*errorCode*/);
            return;
        }

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);
        byte[] pdu = (byte[]) tracker.mData.get("pdu");

        int currentDataNetwork = mPhone.getServiceState().getDataNetworkType();
        boolean imsSmsDisabled = (currentDataNetwork == TelephonyManager.NETWORK_TYPE_EHRPD
                    || (currentDataNetwork == TelephonyManager.NETWORK_TYPE_LTE
                    && !mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()))
                    && mPhone.getServiceState().getVoiceNetworkType()
                    == TelephonyManager.NETWORK_TYPE_1xRTT
                    && ((CDMAPhone) mPhone).mCT.mState != PhoneConstants.State.IDLE;

        // sms over cdma is used:
        //   if sms over IMS is not supported AND
        //   this is not a retry case after sms over IMS failed
        //     indicated by mImsRetry > 0
        if (0 == tracker.mImsRetry && !isIms() || imsSmsDisabled) {
            mCi.sendCdmaSms(pdu, reply);
        } else {
            mCi.sendImsCdmaSms(pdu, tracker.mImsRetry, tracker.mMessageRef, reply);
            // increment it here, so in case of SMS_FAIL_RETRY over IMS
            // next retry will be sent using IMS request again.
            tracker.mImsRetry++;
        }
    }

    // MTK-START Added for turnkey features.
    /** {@inheritDoc} */
    protected void sendData(String destAddr, String scAddr, int destPort, int originalPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        // impl
        Rlog.d(TAG, "CdmaSMSDispatcher, implemented for interfaces needed." +
                " sendData");
        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, originalPort, data, (deliveryIntent != null));
        if (pdu == null) {
            Rlog.d(TAG, "sendData error: invalid paramters, pdu == null.");
            return;
        }
        HashMap map =  getSmsTrackerMap(destAddr, scAddr, destPort, data, pdu);
        SmsTracker tracker = getSmsTracker(map, sentIntent, deliveryIntent, getFormat(),
                null/*messageUri*/, false /*isExpectMore*/, null /*fullMessageText*/,
                false /*isText*/, true /*persistMessage*/);

        String carrierPackage = getCarrierAppPackageName();
        if (carrierPackage != null) {
            Rlog.d(TAG, "Found carrier package. w/op");
            DataSmsSender smsSender = new DataSmsSender(tracker);
            smsSender.sendSmsByCarrierApp(carrierPackage, new SmsSenderCallback(smsSender));
        } else {
            Rlog.v(TAG, "No carrier package. w/op");
            sendRawPdu(tracker);
        }
    }

    /** {@inheritDoc} */
    protected void sendMultipartData(
            String destAddr, String scAddr, int destPort,
            ArrayList<SmsRawData> data, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents) {
        // impl
        Rlog.e(TAG, "Error! The functionality sendMultipartData is not implemented for CDMA.");
    }

    public int copyTextMessageToIccCard(String scAddress, String address, List<String> text,
                    int status, long timestamp) {
        Rlog.d(TAG, "CDMASMSDispatcher: copy text message to icc card");
        /*
         * if(checkPhoneNumber(scAddress)) { Rlog.d(TAG,
         * "[copyText invalid sc address"); scAddress = null; }
         * if(checkPhoneNumber(address) == false) { Rlog.d(TAG,
         * "[copyText invalid dest address"); return
         * RESULT_ERROR_INVALID_ADDRESS; }
         */

        mSuccess = true;

        int msgCount = text.size();
        // we should check the available storage of SIM here,
        // but now we suppose it always be true
        if (true) {
            Rlog.d(TAG, "[copyText storage available");
        } else {
            Rlog.d(TAG, "[copyText storage unavailable");
            return RESULT_ERROR_SIM_MEM_FULL;
        }

        if (status == STATUS_ON_ICC_READ || status == STATUS_ON_ICC_UNREAD) {
            Rlog.d(TAG, "[copyText to encode deliver pdu");
        } else if (status == STATUS_ON_ICC_SENT || status == STATUS_ON_ICC_UNSENT) {
            Rlog.d(TAG, "[copyText to encode submit pdu");
        } else {
            Rlog.d(TAG, "[copyText invalid status, default is deliver pdu");
            return RESULT_ERROR_GENERIC_FAILURE;
        }

        Rlog.d(TAG, "[copyText msgCount " + msgCount);
        if (msgCount > 1) {
            Rlog.d(TAG, "[copyText multi-part message");
        } else if (msgCount == 1) {
            Rlog.d(TAG, "[copyText single-part message");
        } else {
            Rlog.d(TAG, "[copyText invalid message count");
            return RESULT_ERROR_GENERIC_FAILURE;
        }

        for (int i = 0; i < msgCount; ++i) {
            if (mSuccess == false) {
                Rlog.d(TAG, "[copyText Exception happened when copy message");
                return RESULT_ERROR_GENERIC_FAILURE;
            }

            SmsMessage.SubmitPdu pdu = SmsMessage.createEfPdu(address, text.get(i), timestamp);

            if (pdu != null) {
                Rlog.d(TAG, "[copyText write submit pdu into UIM");
                mCi.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu.encodedMessage),
                        obtainMessage(EVENT_COPY_TEXT_MESSAGE_DONE));
            } else {
                return RESULT_ERROR_GENERIC_FAILURE;
            }

            synchronized (mLock) {
                try {
                    Rlog.d(TAG, "[copyText wait until the message be wrote in UIM");
                    mLock.wait();
                } catch (InterruptedException e) {
                    Rlog.d(TAG, "[copyText interrupted while trying to copy text message into UIM");
                    return RESULT_ERROR_GENERIC_FAILURE;
                }
            }
            Rlog.d(TAG, "[copyText thread is waked up");
        }

        if (mSuccess == true) {
            Rlog.d(TAG, "[copyText all messages have been copied into UIM");
            return RESULT_ERROR_SUCCESS;
        }

        Rlog.d(TAG, "[copyText copy failed");
        return RESULT_ERROR_GENERIC_FAILURE;
    }

    /** {@inheritDoc} */
    protected void sendTextWithEncodingType(String destAddr, String scAddr, String text,
            int encodingType, PendingIntent sentIntent, PendingIntent deliveryIntent,
            Uri messageUri, String callingPkg, boolean persistMessage) {
        // impl
        Rlog.d(TAG, "CdmaSMSDispatcher, implemented for interfaces needed." +
                " sendTextWithEncodingType");

        int encoding = encodingType;
        Rlog.d(TAG, "want to use encoding = " + encoding);

        // check is a valid encoding type
        if (encoding < 0x00 || encoding > 0x0A) {
            Rlog.w(TAG, "unavalid encoding = " + encoding);
            Rlog.w(TAG, "to use the unkown default.");
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        }

        if (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN) {
            Rlog.d(TAG, "unkown encoding, to find one best.");
            TextEncodingDetails details = calculateLength(text, false);
            if (encoding != details.codeUnitSize
                    && (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN
                            || encoding == android.telephony.SmsMessage.ENCODING_7BIT)) {
                encoding = details.codeUnitSize;
            }
        }

        UserData uData = new UserData();
        uData.payloadStr = text;
        if (encoding == android.telephony.SmsMessage.ENCODING_7BIT) {
            uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
        } else if (encoding == android.telephony.SmsMessage.ENCODING_8BIT) {
            uData.msgEncoding = UserData.ENCODING_OCTET;
        } else {
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        }
        uData.msgEncodingSet = true;

        /* By setting the statusReportRequested bit only for the
         * last message fragment, this will result in only one
         * callback to the sender when that last fragment delivery
         * has been acknowledged. */
        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destAddr,
                uData, (deliveryIntent != null));

        if (submitPdu != null) {
            HashMap map = getSmsTrackerMap(destAddr, scAddr, text, submitPdu);
            SmsTracker tracker = getSmsTracker(map, sentIntent, deliveryIntent, getFormat(),
                    messageUri, false /*isExpectMore*/, text, true /*isText*/,
                    true);

            String carrierPackage = getCarrierAppPackageName();
            if (carrierPackage != null) {
                Rlog.d(TAG, "sendTextWithEncodingType: Found carrier package.");
                TextSmsSender smsSender = new TextSmsSender(tracker);
                smsSender.sendSmsByCarrierApp(carrierPackage, new SmsSenderCallback(smsSender));
            } else {
                Rlog.v(TAG, "sendTextWithEncodingType: No carrier package.");
                sendSubmitPdu(tracker);
            }
        } else {
            Rlog.d(TAG, "sendTextWithEncodingType: submitPdu is null");
            if (sentIntent != null) {
                try {
                    sentIntent.send(RESULT_ERROR_NULL_PDU);
                } catch (CanceledException ex) {
                    Rlog.e(TAG, "failed to send back RESULT_ERROR_NULL_PDU");
                }
            }
        }
    }

    /** {@inheritDoc} */
    protected void sendMultipartTextWithEncodingType(String destAddr, String scAddr,
            ArrayList<String> parts, int encodingType, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, Uri messageUri, String callingPkg,
            boolean persistMessage) {

        final String fullMessageText = getMultipartMessageText(parts);

        // impl
        Rlog.d(TAG, "CdmaSMSDispatcher, implemented by for interfaces needed." +
                " sendMultipartTextWithEncodingType");
        int refNumber = getNextConcatenatedRef() & 0x00FF;
        int msgCount = parts.size();
        int encoding = encodingType;
        Rlog.d(TAG, "want to use encoding = " + encoding);

        // check is a valid encoding type
        if (encoding < 0x00 || encoding > 0x0A) {
            Rlog.w(TAG, "unavalid encoding = " + encoding);
            Rlog.w(TAG, "to use the unkown default.");
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        }

        TextEncodingDetails[] encodingForParts = new TextEncodingDetails[msgCount];
        if (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN) {
            Rlog.d(TAG, "unkown encoding, to find one best.");
            for (int i = 0; i < msgCount; i++) {
                TextEncodingDetails details = calculateLength(parts.get(i), false);
                if (encoding != details.codeUnitSize
                        && (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN
                                || encoding == android.telephony.SmsMessage.ENCODING_7BIT)) {
                    encoding = details.codeUnitSize;
                }
                encodingForParts[i] = details;
            }
        } else {
            Rlog.d(TAG, "APP want use specified encoding type.");
            for (int i = 0; i < msgCount; i++) {
                TextEncodingDetails details =
                        SmsMessage.calculateLength(parts.get(i), false, encoding);
                details.codeUnitSize = encoding;
                encodingForParts[i] = details;
            }
        }

         SmsTracker[] trackers = new SmsTracker[msgCount];

        // States to track at the message level (for all parts)
        final AtomicInteger unsentPartCount = new AtomicInteger(msgCount);
        final AtomicBoolean anyPartFailed = new AtomicBoolean(false);

        Rlog.d(TAG, "now to send one by one, msgCount = " + msgCount);
        for (int i = 0; i < msgCount; i++) {
            SmsHeader.ConcatRef concatRef = new SmsHeader.ConcatRef();
            concatRef.refNumber = refNumber;
            concatRef.seqNumber = i + 1;  // 1-based sequence
            concatRef.msgCount = msgCount;
            // TODO: We currently set this to true since our messaging app will never
            // send more than 255 parts (it converts the message to MMS well before that).
            // However, we should support 3rd party messaging apps that might need 16-bit
            // references
            // Note:  It's not sufficient to just flip this bit to true; it will have
            // ripple effects (several calculations assume 8-bit ref).
            concatRef.isEightBits = true;
            SmsHeader smsHeader = new SmsHeader();
            smsHeader.concatRef = concatRef;

            PendingIntent sentIntent = null;
            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }

            PendingIntent deliveryIntent = null;
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            Rlog.d(TAG, "to send the " + i + " part");
            trackers[i] =
                getNewSubmitPduTracker(destAddr, scAddr, parts.get(i), smsHeader,
                        encodingForParts[i].codeUnitSize,
                        sentIntent, deliveryIntent, (i == (msgCount - 1)),
                        unsentPartCount, anyPartFailed, messageUri, fullMessageText);
        }

        if (parts == null || trackers == null || trackers.length == 0
                    || trackers[0] == null) {
            Rlog.e(TAG, "sendMultipartTextWithEncodingType:" +
                    " Cannot send multipart text. parts=" + parts + " trackers=" + trackers);
            return;
        }

        String carrierPackage = getCarrierAppPackageName();
        if (carrierPackage != null) {
            Rlog.d(TAG, "sendMultipartTextWithEncodingType: Found carrier package.");
            MultipartSmsSender smsSender = new MultipartSmsSender(parts, trackers);
            smsSender.sendSmsByCarrierApp(carrierPackage,
                    new MultipartSmsSenderCallback(smsSender));
        } else {
            Rlog.v(TAG, "sendMultipartTextWithEncodingType: No carrier package.");
            for (SmsTracker tracker : trackers) {
                if (tracker != null) {
                    sendSubmitPdu(tracker);
                } else {
                    Rlog.e(TAG, "sendMultipartTextWithEncodingType: Null tracker.");
                }
            }
        }
    }

    /** {@inheritDoc} */
    public void sendTextWithExtraParams(String destAddr, String scAddr, String text,
            Bundle extraParams, PendingIntent sentIntent, PendingIntent deliveryIntent,
            Uri messageUri, String callingPkg, boolean persistMessage) {
        // impl
        Rlog.d(TAG, "CdmaSMSDispatcher, implemented by for interfaces needed." +
                " sendTextWithExtraParams");

        int validityPeriod;
        int priority;
        int encoding;

        if (extraParams == null) {
            Rlog.d(TAG, "extraParams == null, will encoding with no extra feature.");
            validityPeriod = -1;
            priority = -1;
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        } else {
            validityPeriod = extraParams.getInt(EXTRA_PARAMS_VALIDITY_PERIOD, -1);
            priority = extraParams.getInt("priority", -1);
            encoding = extraParams.getInt(EXTRA_PARAMS_ENCODING_TYPE, 0);
        }

        Rlog.d(TAG, "validityPeriod is " + validityPeriod);
        Rlog.d(TAG, "priority is " + priority);
        Rlog.d(TAG, "want to use encoding = " + encoding);

        // check is a valid encoding type
        if (encoding < 0x00 || encoding > 0x0A) {
            Rlog.w(TAG, "unavalid encoding = " + encoding);
            Rlog.w(TAG, "to use the unkown default.");
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        }

        if (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN) {
            Rlog.d(TAG, "unkown encoding, to find one best.");
            TextEncodingDetails details = calculateLength(text, false);
            if (encoding != details.codeUnitSize
                    && (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN
                            || encoding == android.telephony.SmsMessage.ENCODING_7BIT)) {
                encoding = details.codeUnitSize;
            }
        }

        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu submitPdu =
                SmsMessage.getSubmitPdu(scAddr, destAddr, text,
                (deliveryIntent != null), null, encoding, validityPeriod, priority);

        if (submitPdu != null) {
            HashMap map = getSmsTrackerMap(destAddr, scAddr, text, submitPdu);
            SmsTracker tracker = getSmsTracker(map, sentIntent, deliveryIntent, getFormat(),
                    messageUri, false /*isExpectMore*/, text, true /*isText*/, true);

            String carrierPackage = getCarrierAppPackageName();
            if (carrierPackage != null) {
                Rlog.d(TAG, "sendTextWithExtraParams: Found carrier package.");
                TextSmsSender smsSender = new TextSmsSender(tracker);
                smsSender.sendSmsByCarrierApp(carrierPackage, new SmsSenderCallback(smsSender));
            } else {
                Rlog.v(TAG, "sendTextWithExtraParams: No carrier package.");
                sendSubmitPdu(tracker);
            }
        } else {
            Rlog.d(TAG, "sendTextWithExtraParams: submitPdu is null");
            if (sentIntent != null) {
                try {
                    sentIntent.send(RESULT_ERROR_NULL_PDU);
                } catch (CanceledException ex) {
                    Rlog.e(TAG, "failed to send back RESULT_ERROR_NULL_PDU");
                }
            }
        }
    }

    /** {@inheritDoc} */
    public void sendMultipartTextWithExtraParams(String destAddr, String scAddr,
            ArrayList<String> parts, Bundle extraParams, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, Uri messageUri, String callingPkg,
            boolean persistMessage) {

        final String fullMessageText = getMultipartMessageText(parts);

        // impl
        Rlog.d(TAG, "CdmaSMSDispatcher, implemented by for interfaces needed." +
                " sendMultipartTextWithExtraParams");
        int validityPeriod;
        int priority;
        int encoding;

        if (extraParams == null) {
            Rlog.d(TAG, "extraParams == null, will encoding with no extra feature.");
            validityPeriod = -1;
            priority = -1;
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        } else {
            validityPeriod = extraParams.getInt(EXTRA_PARAMS_VALIDITY_PERIOD, -1);
            priority = extraParams.getInt("priority", -1);
            encoding = extraParams.getInt(EXTRA_PARAMS_ENCODING_TYPE, 0);
        }

        Rlog.d(TAG, "validityPeriod is " + validityPeriod);
        Rlog.d(TAG, "priority is " + priority);
        Rlog.d(TAG, "want to use encoding = " + encoding);

        int refNumber = getNextConcatenatedRef() & 0x00FF;
        int msgCount = parts.size();

        // check is a valid encoding type
        if (encoding < 0x00 || encoding > 0x0A) {
            Rlog.w(TAG, "unavalid encoding = " + encoding);
            Rlog.w(TAG, "to use the unkown default.");
            encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;
        }

        TextEncodingDetails[] encodingForParts = new TextEncodingDetails[msgCount];
        if (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN) {
            Rlog.d(TAG, "unkown encoding, to find one best.");
            for (int i = 0; i < msgCount; i++) {
                TextEncodingDetails details = calculateLength(parts.get(i), false);
                if (encoding != details.codeUnitSize
                        && (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN
                        || encoding == android.telephony.SmsMessage.ENCODING_7BIT)) {
                    encoding = details.codeUnitSize;
                }
                encodingForParts[i] = details;
            }
        } else {
            Rlog.d(TAG, "APP want use specified encoding type.");
            for (int i = 0; i < msgCount; i++) {
                TextEncodingDetails details =
                        SmsMessage.calculateLength(parts.get(i), false, encoding);
                details.codeUnitSize = encoding;
                encodingForParts[i] = details;
            }
        }

        SmsTracker[] trackers = new SmsTracker[msgCount];

        // States to track at the message level (for all parts)
        final AtomicInteger unsentPartCount = new AtomicInteger(msgCount);
        final AtomicBoolean anyPartFailed = new AtomicBoolean(false);

        Rlog.d(TAG, "now to send one by one, msgCount = " + msgCount);
        for (int i = 0; i < msgCount; i++) {
            SmsHeader.ConcatRef concatRef = new SmsHeader.ConcatRef();
            concatRef.refNumber = refNumber;
            concatRef.seqNumber = i + 1;  // 1-based sequence
            concatRef.msgCount = msgCount;
            // TODO: We currently set this to true since our messaging app will never
            // send more than 255 parts (it converts the message to MMS well before that).
            // However, we should support 3rd party messaging apps that might need 16-bit
            // references
            // Note:  It's not sufficient to just flip this bit to true; it will have
            // ripple effects (several calculations assume 8-bit ref).
            concatRef.isEightBits = true;
            SmsHeader smsHeader = new SmsHeader();
            smsHeader.concatRef = concatRef;

            PendingIntent sentIntent = null;
            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }

            PendingIntent deliveryIntent = null;
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            trackers[i] =
                getNewSubmitPduTracker(destAddr, scAddr, parts.get(i), smsHeader,
                        encodingForParts[i].codeUnitSize,
                        sentIntent, deliveryIntent, (i == (msgCount - 1)),
                        unsentPartCount, anyPartFailed, messageUri, fullMessageText,
                        validityPeriod, priority);
        }

        if (parts == null || trackers == null || trackers.length == 0
                || trackers[0] == null) {
            Rlog.e(TAG, "sendMultipartTextWithExtraParams: Cannot send multipart text. parts=" +
                    parts + " trackers=" + trackers);
            return;
        }

        String carrierPackage = getCarrierAppPackageName();
        if (carrierPackage != null) {
            Rlog.d(TAG, "sendMultipartTextWithExtraParams: Found carrier package.");
            MultipartSmsSender smsSender = new MultipartSmsSender(parts, trackers);
            smsSender.sendSmsByCarrierApp(carrierPackage,
                    new MultipartSmsSenderCallback(smsSender));
        } else {
            Rlog.v(TAG, "sendMultipartTextWithExtraParams: No carrier package.");
            for (SmsTracker tracker : trackers) {
                if (tracker != null) {
                    sendSubmitPdu(tracker);
                } else {
                    Rlog.e(TAG, "sendMultipartTextWithExtraParams: Null tracker.");
                }
            }
         }
    }

    /** {@inheritDoc} */
    protected SmsTracker getNewSubmitPduTracker(String destinationAddress, String scAddress,
            String message, SmsHeader smsHeader, int encoding,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean lastPart,
            AtomicInteger unsentPartCount, AtomicBoolean anyPartFailed, Uri messageUri,
            String fullMessageText,int validityPeriod, int priority) {
        // MTK-START: add for OMH
        if (mIccFileAdapter != null && mIccFileAdapter.isOmhCard()) {
            mIccFileAdapter.getNextMessageId();
        }
        // MTK-END
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                    message, (deliveryIntent != null) && lastPart, smsHeader,
                    encoding, validityPeriod, priority);
        if (submitPdu != null) {
            HashMap map =  getSmsTrackerMap(destinationAddress, scAddress,
                    message, submitPdu);
            return getSmsTracker(map, sentIntent, deliveryIntent,
                    getFormat(), unsentPartCount, anyPartFailed, messageUri, smsHeader,
                    false /*isExpextMore*/, fullMessageText, true /*isText*/,
                    true);
        } else {
            Rlog.e(TAG, "CDMASMSDispatcher.getNewSubmitPduTracker(), returned null, B");
            return null;
        }
    }
    // MTK-END
}
