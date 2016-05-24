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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**.
 * Fetch sms broadcase sim info.
 */
public final class SmsbcIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "SmsbcIccFileFetcher";
    ArrayList<String> mFileList  = new ArrayList<String>();

    private static final String BCSMSCFG = "ef_bcsmscfg";
    private static final String BCSMSTABLE = "ef_bcsmstable";
    private static final String BCSMSP = "ef_bcsmsp";

    /**.
     * OMH sms broadcase in R-UIM (See 3gpp2 spec C.S0023-D_v1.0_R-UIM, 3.4.57)
     * Constructed function
     * @param c context
     * @param phone phone proxy
     */
    public SmsbcIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(BCSMSCFG);
        mFileList.add(BCSMSTABLE);
        mFileList.add(BCSMSP);
    }

    /**.
     * Get file key lists.
     * @return ArrayList<String> file key
     */
    public  ArrayList<String> onGetKeys() {
        return mFileList;
    }

    /**.
     * Get file parameter
     * @param key file index
     * @return IccFileRequest file info
     */
    public IccFileRequest onGetFilePara(String key) {
        switch (key) {
            case BCSMSCFG:
                return (new IccFileRequest(0x6F5B, EF_TYPE_TRANSPARENT, APP_TYPE_3GPP2,
                        "3F007F25", null, INVALID_INDEX, null));
            case BCSMSTABLE:
                return (new IccFileRequest(0x6F5D, EF_TYPE_LINEARFIXED, APP_TYPE_3GPP2,
                        "3F007F25", null, INVALID_INDEX, null));
            case BCSMSP:
                return (new IccFileRequest(0x6F5E, EF_TYPE_LINEARFIXED, APP_TYPE_3GPP2,
                        "3F007F25", null, INVALID_INDEX, null));
            default:
                return null;
        }
    }

    /**
     * Parse the result.
     * @param key file index
     * @param transparent  file content
     * @param linearfixed  file content
     */
    public void onParseResult(String key, byte[] transparent,
            ArrayList<byte[]> linearfixed) {

        if (BCSMSCFG.equals(key)) {
            Rlog.d(TAG, "BCSMSCFG = " + Arrays.toString(transparent));
            mData.put(BCSMSCFG, transparent);
        } else if (BCSMSTABLE.equals(key)) {
            Rlog.d(TAG, "BCSMSTABLE = " + linearfixed);
            if (linearfixed != null && linearfixed.size() > 0) {
                HashMap<String, byte[]> tables = new HashMap<String, byte[]>();
                for (int i = 0; i < linearfixed.size(); i++) {
                    byte[] item = linearfixed.get(i);
                    if (item != null && item.length > 0) {
                        Rlog.d(TAG, "BCSMSTABLE = " + Arrays.toString(item));
                        // b1=0: Free space, b1=1: Used space
                        int status = item[0] & 0x01;
                        if (status == 1) {
                            tables.put(String.valueOf(i), item);
                        }
                    }
                }
                mData.put(BCSMSTABLE, tables);
            }
        } else if (BCSMSP.equals(key)) {
            Rlog.d(TAG, "BCSMSP = " + linearfixed);
            if (linearfixed != null && linearfixed.size() > 0) {
                HashMap<String, byte[]> parameters = new HashMap<String, byte[]>();
                for (int i = 0; i < linearfixed.size(); i++) {
                    byte[] item = linearfixed.get(i);
                    if (item != null && item.length > 0) {
                        Rlog.d(TAG, "BCSMSP = " + Arrays.toString(item));
                        // 0=Not selected; 1=selected
                        int select = item[0] & 0x01;
                        if (select == 1) {
                            parameters.put(String.valueOf(i), item);
                        }
                    }
                }
                mData.put(BCSMSP, parameters);
            }
        }
    }

    /**.
     * Get bc sms
     * @param userServiceCategory  service category
     * @param userPriorityIndicator priority indicator
     * @return 0=Disallow, 2=Allow, -1=Unknown
     */
    public int getBcsmsCfgFromRuim(int userServiceCategory, int userPriorityIndicator) {
        int ret = 2;
        byte[] cfg = (byte[]) mData.get(BCSMSCFG);
        //0: Disallow; 1: Allow Table Only; 2 Allow All; 3 Reserved
        if (null == cfg || cfg.length < 1) {
            return -1;
        }
        byte config = cfg[0];
        Rlog.d(TAG, "getBcsmsCfgFromRuim config = " + config);
        if (config != -1) { // FF: -1
            ret = config & 0x03;
        }
        if (ret != 1) { // ret == 1: Allow Table Only
            return ret;
        }
        HashMap<String, byte[]> tables = (HashMap<String, byte[]>) mData.get(BCSMSTABLE);
        HashMap<String, byte[]> parameters = (HashMap<String, byte[]>) mData.get(BCSMSP);

        if (null == tables || null == parameters) {
            ret = -1;
            return ret;
        }

        for (String i: tables.keySet()) {
            byte[] t = tables.get(i);
            byte[] p = parameters.get(i);
            if (t != null && p != null) {
                int status = t[0] & 0x01; // b1=0: Free space, b1=1: Used space
                int select = p[0] & 0x01; // 0=Not selected 1=selected
                Rlog.d(TAG, "getBcsmsCfgFromRuim status=" + status + " select=" + select);
                if (status == 1 && select == 1) { // unuse: FF(-1)
                    byte ch1 = t[1];
                    byte ch2 = t[2];
                    int serviceCategory = (ch1 << 8) + ch2;
                    Rlog.d(TAG, "getBcsmsCfgFromRuim serviceCategory=" + serviceCategory);
                    Rlog.d(TAG, "userServiceCategory=" + userServiceCategory);
                    if (serviceCategory == userServiceCategory) {
                        // 00=Normal, 01=Interactive, 10=Urgent, 11=Emergency
                        int priority = p[1] & 0x03;
                        Rlog.d(TAG, "getBcsmsCfgFromRuim priority=" + priority);
                        if (userPriorityIndicator >= priority) {
                            ret = 2;
                        } else {
                            ret = 0;
                        }
                        break;
                    }
                }
            }
        }
        if (ret == 1) {
            ret = -1;
        }
        return ret;
    }
}
