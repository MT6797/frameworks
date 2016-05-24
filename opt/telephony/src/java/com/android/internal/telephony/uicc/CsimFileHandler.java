/*
 * Copyright (C) 2006, 2012 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.os.AsyncResult;
import android.os.Message;

import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;

import com.mediatek.internal.telephony.uicc.CsimPhbStorageInfo;

/**
 * {@hide}
 * This class should be used to access files in CSIM ADF
 */
public final class CsimFileHandler extends IccFileHandler implements IccConstants {
    static final String LOG_TAG = "CsimFH";

    public CsimFileHandler(UiccCardApplication app, String aid, CommandsInterface ci) {
        super(app, aid, ci);
    }

    @Override
    protected String getEFPath(int efid) {
        logd("GetEFPath : " + efid);
        switch(efid) {
        case EF_SMS:
            return MF_SIM + DF_CDMA;
        case EF_CST:
        ///M: FDN@{
        case EF_EST:
        ///M: FDN @}
        case EF_FDN:
        case EF_MSISDN:
        case EF_RUIM_SPN:
        //case EF_CSIM_LI:
        case EF_CSIM_MDN:
        case EF_CSIM_IMSIM:
        case EF_CSIM_CDMAHOME:
        case EF_CSIM_EPRL:
        case EF_CSIM_MIPUPP:
        case EF_CDMA_ECC:
            return MF_SIM + DF_ADF;
        }
        String path = getCommonIccEFPath(efid);
        if (path == null) {
            // The EFids in UICC phone book entries are decided by the card manufacturer.
            // So if we don't match any of the cases above and if its a UICC return
            // the global 3g phone book path.
            return MF_SIM + DF_TELECOM + DF_PHONEBOOK;
        }
        return path;
    }

    /// M: MTK added. @{
    protected String getEFPath(int efid, boolean is7FFF) {
        return getEFPath(efid);
    }
    /// @}



    @Override
    protected String getCommonIccEFPath(int efid) {
        logd("getCommonIccEFPath : " + efid);
        switch(efid) {
        case EF_ADN:
        case EF_FDN:
        case EF_MSISDN:
        case EF_SDN:
        case EF_EXT1:
        case EF_EXT2:
        case EF_EXT3:
            return MF_SIM + DF_TELECOM;

        case EF_ICCID:
        case EF_PL:
            return MF_SIM;
        case EF_PBR:
            // we only support global phonebook.
            return MF_SIM + DF_TELECOM + DF_PHONEBOOK;
        case EF_IMG:
            return  MF_SIM + DF_TELECOM + DF_GRAPHICS;
        default:
            break;
        }
        return null;
    }

    // / M: MTK added. @{
    /**
     * Add RuimFileHandler handlemessage to process different simio response, for UICC card, the
     * get/select command will return different response with normal UIM/SIM card, so we must parse
     * it differently.
     *
     * @param msg SIM IO message
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        IccIoResult result;
        Message response = null;
        String str;
        LoadLinearFixedContext lc;

        byte data[];
        int size;
        int fileid;
        int recordNum;
        switch (msg.what) {
            case EVENT_GET_EF_LINEAR_RECORD_SIZE_DONE:
                ar = (AsyncResult) msg.obj;
                lc = (LoadLinearFixedContext) ar.userObj;
                result = (IccIoResult) ar.result;
                response = lc.mOnLoaded;
                if ((ar.exception != null) || (result.getException() != null)) {
                    super.handleMessage(msg);
                    break;
                }
                data = result.payload;

                if (TYPE_EF != data[RESPONSE_DATA_FILE_TYPE] ||
                        EF_TYPE_LINEAR_FIXED != data[RESPONSE_DATA_STRUCTURE]) {
                    super.handleMessage(msg);
                    break;
                }

                int recordSize = 0;
                recordSize = data[RESPONSE_DATA_RECORD_LENGTH] & 0xFF;
                /*
                 * Refer TS 51.011 Section 9.2.1, data[RESPONSE_DATA_RECORD_LENGTH]is number of DFs
                 * which are a direct child of the current directory.In MTK solution 0x4F3A is the
                 * first file of phb in pbr directoryRefer 3GPP Ts 31.102 section 4.5.6 the length
                 * of data in EF_ADN is X+14X is the alpha identifier and 14 is other tags
                 */
                loge(" recordSize=" + recordSize + " efid=" + lc.mEfid
                        + " data=" + data);
                if (lc.mEfid == 20282) {
                    loge(" recordSize=" + recordSize + " efid=" + lc.mEfid);
                    CsimPhbStorageInfo.setMaxNameLength(recordSize - 14); // see adnRecord
                }
                // }
                super.handleMessage(msg);
                break;
            default:
                super.handleMessage(msg);
                // MTK-END

        }
    }

    @Override
    protected void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    @Override
    protected void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }
}
