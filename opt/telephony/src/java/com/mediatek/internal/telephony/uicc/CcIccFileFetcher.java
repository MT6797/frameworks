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
import android.os.SystemProperties;
import android.telephony.Rlog;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.uicc.IccUtils;

import java.util.ArrayList;

/**
 * Fetch CC sim info.
 */
public final class CcIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "CcIccFileFetcher";
    private static final String KEY_ECC = "EF_ECC";
    private static final String CSIM_PATH = "3F007FFF";
    private static final String RUIM_PATH = "3F007F25";
    private static final String[] RUIMRECORDS_PROPERTY_ECC_LIST = {
        "cdma.ril.ecclist",
        "cdma.ril.ecclist1",
        "cdma.ril.ecclist2",
        "cdma.ril.ecclist3",
    };
    private static final String[] UICC_SUPPORT_CARD_TYPE = {
        "gsm.ril.fulluicctype",
        "gsm.ril.fulluicctype.2",
        "gsm.ril.fulluicctype.3",
        "gsm.ril.fulluicctype.4",
    };
    private ArrayList<String> mFileList = new ArrayList<String>();
    /**
     * Constructor.
     * @param c context
     * @param phone phone proxy
     */
    public CcIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(KEY_ECC);
    }

    /**
     * Get file key list.
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
        Rlog.d(TAG, "onGetFilePara, phoneId:" + mPhoneId + ", key:" + key);
        if (KEY_ECC.equals(key)) {
            if (mPhoneId >= 0 && mPhoneId < UICC_SUPPORT_CARD_TYPE.length) {
                String path = "";
                String value = SystemProperties.get(UICC_SUPPORT_CARD_TYPE[mPhoneId], "");
                Rlog.d(TAG, "onGetFilePara, full card type:" + value);
                if (value.indexOf("CSIM") != -1) {
                    path = CSIM_PATH;
                } else if (value.indexOf("RUIM") != -1) {
                    path = RUIM_PATH;
                }
                if (path.length() > 0) {
                    return new IccFileRequest(0x6F47, EF_TYPE_TRANSPARENT, APP_TYPE_3GPP2, path,
                                null, INVALID_INDEX, null);
                }
            }
        }
        return null;
    }

    /**
     * Parse the result.
     * @param key file index
     * @param transparent  file content
     * @param linearfixed  file content
     */
    public void onParseResult(String key, byte[] transparent, ArrayList<byte[]> linearfixed) {
        String eccNum = null;
        String result = "";
        if (transparent != null) {
            for (int i = 0; i + 2 < transparent.length; i += 3) {
                eccNum = IccUtils.cdmaBcdToString(transparent, i, 3);
                if (eccNum != null && !eccNum.equals("") && !result.equals("")) {
                    result = result + ",";
                }
                result = result + eccNum;
            }
        }
        Rlog.d(TAG, "CDMA ECC of card " + mPhoneId + " :" + result);
        if (mPhoneId >= 0 && mPhoneId < RUIMRECORDS_PROPERTY_ECC_LIST.length) {
            SystemProperties.set(RUIMRECORDS_PROPERTY_ECC_LIST[mPhoneId], result);
        }
        mData.put(KEY_ECC, result);
    }
}

