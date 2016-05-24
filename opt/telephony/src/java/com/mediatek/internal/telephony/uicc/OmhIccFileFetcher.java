/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2015. All rights reserved.
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

package com.mediatek.internal.telephony.uicc;

import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.os.SystemProperties;

import com.android.internal.telephony.Phone;

import java.util.ArrayList;

/**.
 * Fetch mms sim info.
 */
public final class OmhIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "OmhIccFileFetcher";
    private static final String OMH_INFO_READY = "omh_info_ready";
    private static final String OMH_CARD_UNKNOWN = "-1";
    private static final String OMH_CARD_NO = "0";
    private static final String OMH_CARD_YES = "1";

    private static final int OMH_CARD_QUERY = 1001;
    private static final int OMH_CARD_RETRY_COUNT = 5;
    private static final int OMH_CARD_RETRY_INTERVAL = 1000;
    private int mRetryTimes = 0;

    ArrayList<String> mFileList  = new ArrayList<String>();

    /**.
     * Constructed function
     * @param c context
     * @param phone phone proxy
     */
    public OmhIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(OMH_INFO_READY);
    }

    /**
     * Get file key lists.
     * @return ArrayList<String> file key
     */
    public  ArrayList<String> onGetKeys() {
        return mFileList;
    }

    /**
     * Get file parameter.
     * @param key file index
     * @return IccFileRequest file info
     */
    public IccFileRequest onGetFilePara(String key) {
        return null;
    }

    /**
     * Parse the result.
     * @param key file index
     * @param transparent  file content
     * @param linearfixed  file content
     */
    public void onParseResult(String key, byte[] transparent, ArrayList<byte[]> linearfixed) {
    }

    void retryCheckOmhCard() {
        String omh = SystemProperties.get("ril.cdma.card.omh", OMH_CARD_UNKNOWN);
        log("retryCheckOmhCard with omh = " + omh + " mRetryTimes = " + mRetryTimes);
        if (OMH_CARD_UNKNOWN.equals(omh) && mRetryTimes < OMH_CARD_RETRY_COUNT) {
            mRetryTimes++;
            sendEmptyMessageDelayed(OMH_CARD_QUERY, OMH_CARD_RETRY_INTERVAL);
            log("retryCheckOmhCard, retry again.");
        } else {
            //Stop the retry and tell application the omh ready
            if (OMH_CARD_UNKNOWN.equals(omh)) {
                //can't get the exactly info, so consider the card isn't omh card
                mData.put(OMH_INFO_READY, OMH_CARD_NO);
            } else {
                mData.put(OMH_INFO_READY, omh);
            }
            //Notify application can get the state for omh card.
            notifyOmhCardDone(true);
            log("retryCheckOmhCard, notify app the check is ready.");
        }
    }

    private void notifyOmhCardDone(boolean state) {
        log("notifyOmhCardDone, check omh card is done with state = " + state);
        Intent intent = new Intent("com.mediatek.internal.omh.cardcheck");
        intent.putExtra("subid", mPhone.getSubId());
        intent.putExtra("is_ready", state ? "yes" : "no");
        mContext.sendBroadcast(intent);
    }

    protected void exchangeSimInfo() {
        log("exchangeSimInfo, just check the property.");

        String omh = SystemProperties.get("ril.cdma.card.omh", OMH_CARD_UNKNOWN);
        log("exchangeSimInfo, ril.cdma.card.omh = " + omh);
        if (OMH_CARD_UNKNOWN.equals(omh)) {
            retryCheckOmhCard();
            mRetryTimes = 0;
        } else {
            mData.put(OMH_INFO_READY, omh);
            notifyOmhCardDone(true);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case OMH_CARD_QUERY:
                retryCheckOmhCard();
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    protected boolean isOmhCard() {
        if (mData.containsKey(OMH_INFO_READY)) {
            String omhState = (String) mData.get(OMH_INFO_READY);
            return OMH_CARD_YES.equals(omhState);
        } else {
            // In some case, app expects that know the card type as soon as possible, so
            // add this workaround way to avoid some timing issue.
            String omhCard = SystemProperties.get("ril.cdma.card.omh", OMH_CARD_UNKNOWN);
            if (!OMH_CARD_UNKNOWN.equals(omhCard)) {
                log("isOmhCard(), omh info maybe not ready, but the card check is done!!!!!!");
                return OMH_CARD_YES.equals(omhCard);
            }
        }
        return false;
    }
}

