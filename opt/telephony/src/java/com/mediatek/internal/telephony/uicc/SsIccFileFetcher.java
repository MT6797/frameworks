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

/**
 * Fetch ss sim info.
 */
public final class SsIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "SsIccFileFetcher";
    ArrayList<String> mFileList = new ArrayList<String>();
    private static final String SSFC = "ef_ssfc";

    /**
     * OMH SS feature code in R-UIM (See 3gpp2 spec C.S0023-D_v1.0_R-UIM,
     * 3.4.30).
     */
    public static final String[] FCNAME = {
            "Number", "CD", "dCD", "CFB", "vCFB", "dCFB", "actCFB", "dactCFB",
            "CFD", "vCFD", "dCFD", "actCFD", "dactCFD",
            "CFNA", "vCFNA", "dCFNA", "actCFNA", "dactCFNA",
            "CFU", "vCFU", "dCFU", "actCFU", "dactCFU", "CW", "dCW", "CCW",
            "CNIR", "dCNIR", "CC", "dCC", "DND", "dDND",
            "aMWN", "daMWN", "MWN", "dMWN", "CMWN", "PACA", "VMR", "CNAP", "dCNAP",
            "CNAR", "dCNAR", "AC", "dAC", "AR", "dAR", "USCF", "RUAC", "dRUAC",
            "AOC", "COT"
    };

    /**
     * Constructed function.
     * @param c context
     * @param phone phone proxy
     */
    public SsIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(SSFC);
    }

    /**
     * Get file key lists.
     * @return ArrayList<String> file key
     */
    public ArrayList<String> onGetKeys() {
        return mFileList;
    }

    /**
     * Get file parameter.
     * @param key file index
     * @return IccFileRequest file info
     */
    public IccFileRequest onGetFilePara(String key) {
        switch (key) {
            case SSFC:
                return (new IccFileRequest(0x6F3F, EF_TYPE_TRANSPARENT, APP_TYPE_ACTIVE,
                        "3F007F25", null, INVALID_INDEX, null));
            default:
                return null;
        }
    }

    /**
     * Parse the result.
     * @param key file index
     * @param transparent file content
     * @param linearfixed file content
     */
    public void onParseResult(String key, byte[] transparent,
            ArrayList<byte[]> linearfixed) {

        if (SSFC.equals(key)) {
            Rlog.d(TAG, "featureCode = " + Arrays.toString(transparent));
            if (transparent == null || transparent.length != 103) {
                return;
            }
            HashMap<String, Object> map = new HashMap<String, Object>();

            String number = String.valueOf(transparent[0]);
            if ("-1".equals(number)) { // there is not feature code
                return;
            }
            map.put(FCNAME[0], number);
            String featureCode = "-1";
            for (int i = 1; i < transparent.length; i += 2) {
                featureCode = getFcFromRuim(transparent[i], transparent[i + 1]);
                if (!"-1".equals(featureCode)) {
                    Rlog.d(TAG, "onParseResult featureCode = " + featureCode);
                    map.put(FCNAME[i / 2 + 1], featureCode);
                }
            }
            mData = map;
        }
    }

    private static String getFcFromRuim(byte b1, byte b2) {
        String featureCode = "-1";
        String fc1 = getFcFromRuim(b1);
        String fc2 = getFcFromRuim(b2);

        if ("".equals(fc1) && "".equals(fc2)) {
            featureCode = "-1";
        } else {
            featureCode = fc1 + fc2;
        }
        return featureCode;
    }

    private static String getFcFromRuim(byte b) {
        String ret = "";
        if ((b & 0xff) == 0xff) { // FF
            ret = "";
        } else if ((b & 0xf0) == 0xf0) { // Fx, no data like xF
            if ((b & 0x0f) <= 0x09) { // x <= 9, no data like FA
                ret += (b & 0xf);
            }
        } else { // xx
            int tmp = 0;
            if ((b & 0x0f) <= 0x09) {
                tmp = (b & 0xf);
            }
            if ((b & 0xf0) <= 0x90) {
                int temp = ((b >> 4) & 0xf);
                if (temp == 0) {
                    ret = "0" + tmp;
                } else {
                    tmp += temp * 10;
                    ret += tmp;
                }
            }
        }
        return ret;
    }

    /**.
     * @param start index
     * @param end  index
     * @param subId  sub id
     * @return int[]
     */
    public int[] getFcsForApp(int start, int end, int subId) {
        Rlog.d(TAG, "getFcsForApp start=" + start + ";end=" + end);
        if (mData == null || mData.size() < 1) {
            return null;
        }
        if (end < start) {
            return null;
        }

        int size = end - start + 1;
        int[] featureCodes = new int[size];
        for (int i = 0; i < size; i++) {
            int index = start + i;
            featureCodes[i] = getFcForApp(index, mData);
            Rlog.d(TAG, "getFcsForApp featureCodes=" + featureCodes[i]);
        }
        return featureCodes;
    }

    private int getFcForApp(int index, HashMap<String, Object> fcs) {
        int fc = -1;
        Object code = fcs.get(FCNAME[index]);
        if (code != null) {
            fc = Integer.parseInt((String) code);
        }
        return fc;
    }
}
