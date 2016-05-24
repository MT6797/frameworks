/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2015. All rights reserved.
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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.Rlog;

import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.IccFileHandler;

import java.util.ArrayList;

/**
 * This class is used to get Csim phonebook storage information.
 */
public class CsimPhbStorageInfo extends Handler {
    static final String LOG_TAG = "CsimphbStorageInfo";
    static IccFileHandler sFh;
    // static AdnCache mAdnCache;
    static int[] sAdnRecordSize = {
            -1, -1, -1, -1
    };
    static int sMaxNameLength = 0;

    static int sMaxnumberLength = 20; // see AdnRecord

    /**
     * Construction method.
     */
    public CsimPhbStorageInfo() {
        // mFh = fh;
        // mAdnCache = cache;
        Rlog.d(LOG_TAG, " CsimphbStorageInfo constructor finished. ");
    }

    /**
     * set the right MaxNameLength.
     *
     * @param mMaxNameLength the right size of MaxNameLength
     */
    public static void setMaxNameLength(int mMaxNameLength) {

        sMaxNameLength = mMaxNameLength;
        // add new code
        Rlog.d(LOG_TAG, " [setMaxNameLength] sMaxNameLength = " + sMaxNameLength);
    }
    /**
     * set the right phb storage informtion after the check.
     *
     * @param totalSize the size phb support to store
     * @param usedRecord the used Record size in phb
     */
    public static void setPhbRecordStorageInfo(int totalSize, int usedRecord) {

        sAdnRecordSize[0] = usedRecord;
        sAdnRecordSize[1] = totalSize;
        // add new code
        Rlog.d(LOG_TAG, " [setPhbRecordStorageInfo] usedRecord = " + usedRecord
                + " | totalSize =" + totalSize);
    }

    /**
     * check the current phb storage informtion and send it to target.
     *
     * @param response message to be posted when done check
     */
    public static void checkPhbRecordInfo(Message response) {
        // it may return -1 values when query SIM process not finished.
        // so please show loading PHB title on APP when adnRecordSize[0] return -1.
        sAdnRecordSize[2] = 20;
        sAdnRecordSize[3] = sMaxNameLength;

        Rlog.d(LOG_TAG, " [getPhbRecordInfo] sAdnRecordSize[0] = " + sAdnRecordSize[0]
                + " sAdnRecordSize[1] = " + sAdnRecordSize[1]
                + " sAdnRecordSize[2] = " + sAdnRecordSize[2]
                + " sAdnRecordSize[3] = " + sAdnRecordSize[3]);
        if (null != response) {
            AsyncResult.forMessage(response).result = sAdnRecordSize;

            response.sendToTarget();
        }

    }

    /**
     * get the RecordStorageInfo.
     *
     * @return the phb storage of record sizes information
     */
    public static int[] getPhbRecordStorageInfo() {
        return sAdnRecordSize;
    }

    /**
     * clear the phb storage informtion.
     */
    public static void clearAdnRecordSize() {
        // if (mFh instanceof CsimFileHandler) {
        Rlog.d(LOG_TAG, " clearAdnRecordSize");
        if (null != sAdnRecordSize) {
            for (int i = 0; i < sAdnRecordSize.length; i++) {
                sAdnRecordSize[i] = -1;
            }
        }

        // }
    }

    /**
     * Update the phb storage informtion according to the operation.
     *
     * @param update mark the operation,if insert update is 1, if delete update is -1
     * @return true if update successful and false if fail
     */
    public static boolean updatePhbStorageInfo(int update) {
        // if (mFh instanceof CsimFileHandler) {
        int[] stroageInfo = getPhbRecordStorageInfo();

        int used = stroageInfo[0];
        int total = stroageInfo[1];

        Rlog.d(LOG_TAG, " [updatePhbStorageInfo] used " + used + " | total : "
                + total + " | update : " + update);
        if (used > -1) {
            int newUsed = used + update;
            setPhbRecordStorageInfo(total, newUsed);
            return true;
        } else {
            Rlog.d(LOG_TAG, " the storage info is not ready return false");
            return false;
        }
        // }
        // Rlog.d(LOG_TAG," not csim object return false");
        // return false;

    }

    /**
     * check if read all the adn entries and set the right storage information.
     *
     * @param adnList the whole adn load from sim
     */
    public static void checkPhbStorage(ArrayList<AdnRecord> adnList) {
        // if (mFh instanceof CsimFileHandler) {
        int[] stroageInfo = getPhbRecordStorageInfo();
        int usedStorage = stroageInfo[0]; // Used storage
        int totalStorage = stroageInfo[1]; // Total storage
        int totalSize = -1;
        int usedRecord = -1;
        if (adnList != null) {
            totalSize = adnList.size();
            usedRecord = 0;
            int i = 0;

            for (i = 0; i < totalSize; i++) {
                if (!adnList.get(i).isEmpty()) {
                    usedRecord++;
                    Rlog.d(LOG_TAG, " print userRecord: " + adnList.get(i));
                }
            }

            Rlog.d(LOG_TAG, " checkPhbStorage totalSize = " + totalSize + " usedRecord = "
                    + usedRecord);
            Rlog.d(LOG_TAG, " checkPhbStorage totalStorage = " + totalStorage
                    + " usedStorage = " + usedStorage);
            // check the storage, if it not -1. means there already has value.
            // we need add two values for count total size.
            if (totalStorage > -1) {
                int newUsed = usedRecord + usedStorage;
                int newTotal = totalStorage + totalSize;
                setPhbRecordStorageInfo(newTotal, newUsed);
            } else {
                setPhbRecordStorageInfo(totalSize, usedRecord);
            }

            // }
        }
    }

}
