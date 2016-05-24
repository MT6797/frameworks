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

import com.android.internal.telephony.Phone;
import com.mediatek.internal.telephony.MmsConfigInfo;
import com.mediatek.internal.telephony.MmsIcpInfo;

import java.util.ArrayList;

/**.
 * Fetch mms sim info.
 */
public final class MmsIccFileFetcher extends IccFileFetcherBase {
    private static final String TAG = "MmsIccFileFetcher";
    private static final String MMS_ICP_INFO = "ef_mms_icp_info";
    private static final String MMS_CONFIG_INFO = "ef_mms_config_info";

    private static final int MMS_ICP_CP_TAG = 0xAB;
    private static final int MMS_ICP_I_TAG = 0x80;
    private static final int MMS_ICP_RS_TAG = 0x81;
    private static final int MMS_ICP_ICBI_TAG = 0x82;
    private static final int MMS_ICP_G_TAG = 0x83;
    private static final int MMS_ICP_AM_TAG = 0x84;
    private static final int MMS_ICP_AI_TAG = 0x85;
    private static final int MMS_ICP_INVALID_TAG = 0XFF;

    ArrayList<String> mFileList  = new ArrayList<String>();

    /**.
     * Constructed function
     * @param c context
     * @param phone phone proxy
     */
    public MmsIccFileFetcher(Context c, Phone phone) {
        super(c, phone);
        mFileList.add(MMS_ICP_INFO);
        mFileList.add(MMS_CONFIG_INFO);
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
        switch(key) {
            case MMS_ICP_INFO:
                return (new IccFileRequest(0x6F67, EF_TYPE_TRANSPARENT, APP_TYPE_ACTIVE, "3F007F25",
                        null, INVALID_INDEX, null));
            case MMS_CONFIG_INFO:
                return (new IccFileRequest(0x6F7E, EF_TYPE_TRANSPARENT, APP_TYPE_ACTIVE, "3F007F25",
                        null, INVALID_INDEX, null));
            default:
                return null;
        }
    }

    String dumpBytes(byte[] data) {
        int i = 0;
        String ret = "";
        for (i = 0; i < data.length; i++) {
            ret += Integer.toHexString((data[i] & 0xf0) >> 4);
            ret += Integer.toHexString((data[i]) & 0x0f);
        }
        return ret;
    }

    void decodeGateWay(MmsIcpInfo info, byte[] data, int start, int len) {
        if (info == null) {
            return;
        }
        int pos = start;
        while (pos < start + len) {
            if ((data[pos] & 0xff) == 0) {
                break;
            } else {
                pos++;
            }
        }
        String gateWay = new String(data, start, pos - start);
        log("parseMmsIcpInfo decodeGateWay gateWay = " + gateWay.trim());
        info.mDomainName = gateWay.trim();
    }

    void decodeMmsImplementation(MmsIcpInfo info, byte[] data, int start, int len) {
        if (info == null) {
            return;
        }
        int type = data[start] & 0xff;
        if ((type & 0x1) == 0x1) {
            info.mImplementation = "WAP";
        } else if ((type & 0x2) == 0x2) {
            info.mImplementation = "M-IMAP";
        } else if ((type & 0x4) == 0x4) {
            info.mImplementation = "SIP";
        } else {
            info.mImplementation = "UNKNOWN";
        }
        log("parseMmsIcpInfo decodeMmsImplementation imp = " + info.mImplementation);
    }

    boolean isValideIcpInfo(MmsIcpInfo info) {
        if (info.mImplementation == null || info.mImplementation.isEmpty()) {
            log("parseMmsIcpInfo isValide = false");
            return false;
        }
        log("parseMmsIcpInfo isValide = true");
        return true;
    }

    MmsIcpInfo parseMmsIcpInfo(byte[] data) {
        if (data == null) {
            return null;
        }
        log("parseMmsIcpInfo data = " + dumpBytes(data));

        int pos = 0;
        int infoLen = 0;
        String tempStr = null;
        MmsIcpInfo icpInfo = new MmsIcpInfo();

        while (pos < data.length) {
            int tagParam = data[pos] & 0xff;
            int paramLen = data[pos + 1] & 0xff;
            log("parseMmsIcpInfo tagParam = " + Integer.toHexString(tagParam));
            log("parseMmsIcpInfo, the Tag's value len = " + paramLen);
            if (tagParam == MMS_ICP_INVALID_TAG) {
                break;
            }
            switch (tagParam) {
                case MMS_ICP_CP_TAG:
                    infoLen = paramLen;
                    //skip the length byte.
                    pos = pos + 2;
                    break;
                case MMS_ICP_I_TAG:
                    //skip a length and an content byte
                    decodeMmsImplementation(icpInfo, data, pos + 2, 1);
                    pos = pos + 3;
                    break;
                case MMS_ICP_RS_TAG:
                    tempStr = new String(data, pos + 2, paramLen);
                    icpInfo.mRelayOrServerAddress = tempStr;
                    pos = (pos + 2) + paramLen;
                    log("parseMmsIcpInfo, MMS_ICP_RS_TAG value = " + tempStr);
                    break;
                case MMS_ICP_ICBI_TAG:
                    tempStr = new String(data, pos + 2, paramLen);
                    pos = (pos + 2) + paramLen;
                    log("parseMmsIcpInfo, MMS_ICP_ICBI_TAG value = " + tempStr);
                    break;
                case MMS_ICP_G_TAG:
                    decodeGateWay(icpInfo, data, pos + 2, paramLen);
                    pos = (pos + 2) + paramLen;
                    break;
                case MMS_ICP_AM_TAG:
                    //Just for sip or m-imap
                    tempStr = new String(data, pos + 2, paramLen);
                    pos = (pos + 2) + paramLen;
                    log("parseMmsIcpInfo, MMS_ICP_AM_TAG value = " + tempStr);
                    break;
                case MMS_ICP_AI_TAG:
                    //Just for sip or m-imap
                    tempStr = new String(data, pos + 2, paramLen);
                    pos = (pos + 2) + paramLen;
                    log("parseMmsIcpInfo, MMS_ICP_AI_TAG value = " + tempStr);
                    break;
                default:
                    log("unkonwn tag.");
                    break;
            }
        }

        return (isValideIcpInfo(icpInfo) ? icpInfo : null);
    }

    MmsConfigInfo parseMmsConfigInfo(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }
        log("parseMmsConfigInfo data = " + dumpBytes(data));
        MmsConfigInfo info = new MmsConfigInfo();
        info.mMessageMaxSize = (((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16)
                | ((data[2] & 0xff)) << 8 | (data[3] & 0xff));

        info.mRetryTimes = data[4] & 0xff;
        info.mRetryInterval = data[5] & 0xff;
        info.mCenterTimeout = ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        log("parseMmsConfigInfo: mMessageMaxSize = " + info.mMessageMaxSize
                + " mRetryTimes = " + info.mRetryTimes + " mRetryInterval = " + info.mRetryInterval
                + " mCenterTimeout = " + info.mCenterTimeout);
        return info;
    }

    /**
     * Parse the result.
     * @param key file index
     * @param transparent  file content
     * @param linearfixed  file content
     */
    public void onParseResult(String key, byte[] transparent, ArrayList<byte[]> linearfixed) {
        log("KEY = " + key + " transparent = " + transparent + " linearfixed = " + linearfixed);

        switch (key) {
            case MMS_ICP_INFO:
                mData.put(MMS_ICP_INFO, parseMmsIcpInfo(transparent));
                break;
            case MMS_CONFIG_INFO:
                mData.put(MMS_CONFIG_INFO, parseMmsConfigInfo(transparent));
                break;
            default:
                loge("unknown key type.");
        }
    }

    protected MmsConfigInfo getMmsConfigInfo() {
        if (mData.containsKey(MMS_CONFIG_INFO)) {
            return (MmsConfigInfo) mData.get(MMS_CONFIG_INFO);
        }
        return null;
    }

    protected MmsIcpInfo getMmsIcpInfo() {
        if (mData.containsKey(MMS_ICP_INFO)) {
            return (MmsIcpInfo) mData.get(MMS_ICP_INFO);
        }
        return null;
    }
}

