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
import android.telephony.Rlog;

import com.android.internal.telephony.Phone;

import java.util.HashMap;

/**.
 * Sim info adapter
 */
public class IccFileAdapter {
    private static final String TAG = "IccFileAdapter";
    private static IccFileAdapter sInstance;
    private Context mContext;
    private Phone mPhone;
    private int mPhoneId;
    private MmsIccFileFetcher mMmsIccFileFetcher;
    private CcIccFileFetcher mCcIccFileFetcher;
    private OmhIccFileFetcher mOmhIccFileFetcher;
    private SsIccFileFetcher mSsIccFileFetcher;
    private SmsbcIccFileFetcher mSmsbcIccFileFetcher;
    private SmsIccFileFetcher mSmsIccFileFetcher;

    /**.
     * Constructed function
     * @param c context
     * @param phone phone proxy
     */
    public IccFileAdapter(Context c, Phone phone) {
        log("IccFileAdapter Creating!");
        mContext = c;
        mPhone = phone;
        mPhoneId = mPhone.getPhoneId();
        mMmsIccFileFetcher = new MmsIccFileFetcher(mContext, phone);
        mCcIccFileFetcher = new CcIccFileFetcher(mContext, phone);
        mOmhIccFileFetcher = new OmhIccFileFetcher(mContext, phone);
        mSsIccFileFetcher = new SsIccFileFetcher(mContext, phone);
        mSmsbcIccFileFetcher = new SmsbcIccFileFetcher(mContext, phone);
        mSmsIccFileFetcher = new SmsIccFileFetcher(mContext, phone);
    }

    protected void log(String msg) {
        Rlog.d(TAG, msg  + " (phoneId " + mPhoneId + ")");
    }
    protected void loge(String msg) {
        Rlog.e(TAG, msg + " (phoneId " + mPhoneId + ")");
    }

    public boolean isOmhCard() {
        return mOmhIccFileFetcher.isOmhCard();
    }

    public Object getMmsConfigInfo() {
        return mMmsIccFileFetcher.getMmsConfigInfo();
    }

    public Object getMmsIcpInfo() {
        return mMmsIccFileFetcher.getMmsIcpInfo();
    }

    /**.
     * @param subId  sub id
     * @return HashMap<String, Object>
     */
    public HashMap<String, Object> getSsFeatureCode(int subId) {
        return mSsIccFileFetcher.mData;
    }

    /**.
     * @param start index
     * @param end  index
     * @param subId  sub id
     * @return int[]
     */
    public int[] getFcsForApp(int start, int end, int subId) {
        return mSsIccFileFetcher.getFcsForApp(start, end, subId);
    }

    public SmsbcIccFileFetcher getSmsbcIccFileFetcher() {
        return mSmsbcIccFileFetcher;
    }

    public SmsIccFileFetcher getSmsIccFileFetcher() {
        return mSmsIccFileFetcher;
    }

    public int getBcsmsCfgFromRuim(int userCategory, int userPriority) {
        return mSmsbcIccFileFetcher.getBcsmsCfgFromRuim(userCategory, userPriority);
    }

    public int getNextMessageId() {
        return mSmsIccFileFetcher.getNextMessageId();
    }

    public int getWapMsgId() {
        return mSmsIccFileFetcher.getWapMsgId();
    }
}

