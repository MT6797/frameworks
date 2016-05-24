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
import com.android.internal.telephony.TelephonyProperties;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fetch ss sim info.
 */
public final class SmsIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "SmsIccFileFetcher";
    ArrayList<String> mFileList = new ArrayList<String>();
    private static final String SMSS = "ef_smss";
    private static final String SMSP = "ef_smsp";

    /**
     * OMH SMS in R-UIM (See 3gpp2 spec C.S0023-D_v1.0_R-UIM, 3.4.28).
     * Constructed function.
     * @param c context
     * @param phone phone proxy
     */
    public SmsIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(SMSS);
        mFileList.add(SMSP);
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
            case SMSS:
                return (new IccFileRequest(0x6F3D, EF_TYPE_TRANSPARENT, APP_TYPE_3GPP2,
                        "3F007F25", null, INVALID_INDEX, null));
            case SMSP:
                return (new IccFileRequest(0x6F3E, EF_TYPE_LINEARFIXED, APP_TYPE_3GPP2,
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

        if (SMSS.equals(key)) {
            Rlog.d(TAG, "SMSS = " + Arrays.toString(transparent));
            mData.put(SMSS, transparent);
        } else if (SMSP.equals(key)) {
            Rlog.d(TAG, "SMSP = " + linearfixed);
            mData.put(SMSP, linearfixed);
            if (linearfixed != null && linearfixed.size() > 0) {
                for (byte[] item : linearfixed) {
                    Rlog.d(TAG, "SMSP = " + Arrays.toString(item));
                }
            }
        }

    }

    /**
     * Calculate the next message id, starting at 1 and iteratively incrementing
     * within the range 1..65535 remembering the state via a persistent system
     * property. (See C.S0015-B, v2.0, 4.3.1.5) Since this routine is expected
     * to be accessed via via binder-call, and hence should be thread-safe, it
     * has been synchronized. save the message id in R-UIM card, (See
     * C.S0023-D_v1.0 3.4.29)
     * @throws IOException
     * @return int
     */
    synchronized public int getNextMessageId() {

        Rlog.d(TAG, "getNextMessageId");
        byte[] bytes = (byte[]) mData.get(SMSS);
        int nextMsgId = -1; //
        if (bytes != null && bytes.length > 4) {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);

            try {
                int msgId = dis.readUnsignedShort();
                dis.close();
                nextMsgId = (msgId % 0xFFFF) + 1;
                String str = Integer.toString(nextMsgId);
                SystemProperties.set(TelephonyProperties.PROPERTY_CDMA_MSG_ID, str);
            } catch (IOException e) {
                Rlog.e(TAG, "getNextMessageId IOException");
            }
            Rlog.d(TAG, "getmWapMsgId nextMsgId = " + nextMsgId);
            byte[] bs = ByteBuffer.allocate(4).putInt(nextMsgId).array();
            bytes[0] = bs[2];
            bytes[1] = bs[3];
            IccFileRequest simInfo = new IccFileRequest(0x6F3E, EF_TYPE_TRANSPARENT,
                    APP_TYPE_3GPP2, "3F007F25", bytes, INVALID_INDEX, null);
            updateSimInfo(simInfo);
        }
        return nextMsgId;
    }

    /**
     * @return int
     */
    synchronized public int getWapMsgId() {

        Rlog.d(TAG, "getmWapMsgId");
        byte[] bytes = (byte[]) mData.get(SMSS);
        int mWapMsgId = -1;

        if (bytes != null && bytes.length > 4 && mWapMsgId > 0) {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);
            try {
                int msgId = dis.readUnsignedShort();
                msgId = dis.readUnsignedShort();
                dis.close();
                mWapMsgId = (msgId % 0xFFFF) + 1;
            } catch (IOException e) {
                Rlog.e(TAG, "getmWapMsgId IOException");
            }

            Rlog.d(TAG, "getmWapMsgId mWapMsgId = " + mWapMsgId);
            byte[] bs = ByteBuffer.allocate(4).putInt(mWapMsgId).array();
            bytes[2] = bs[2];
            bytes[3] = bs[3];
            IccFileRequest simInfo = new IccFileRequest(0x6F3E, EF_TYPE_TRANSPARENT,
                    APP_TYPE_3GPP2, "3F007F25", bytes, INVALID_INDEX, null);
            updateSimInfo(simInfo);
        }
        return mWapMsgId;
    }

    /**.
     * @return List<byte[]>
     */
    public List<byte[]> getSmsPara() {
        Rlog.d(TAG, "getSmsPara");
        return (ArrayList<byte[]>) mData.get(SMSP);
    }
}
