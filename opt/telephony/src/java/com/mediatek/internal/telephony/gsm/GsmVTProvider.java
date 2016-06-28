/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
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

package com.mediatek.internal.telephony.gsm;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.VideoProfile.CameraCapabilities;
import android.telecom.VideoProfile;
import android.view.Surface;
import android.util.Log;

import java.util.List;
import java.lang.Integer;
import java.lang.Thread;

import com.mediatek.internal.telephony.gsm.GsmVTProviderUtil.Size;

public class GsmVTProvider extends GsmVideoCallProvider {

    static {
        System.loadLibrary("mtk_vt_wrapper");
    }

    public static final int VT_PROVIDER_INVALIDE_ID                             = -10000;

    public static final int SESSION_EVENT_RECEIVE_FIRSTFRAME                       = 1001;
    public static final int SESSION_EVENT_SNAPSHOT_DONE                            = 1002;
    public static final int SESSION_EVENT_RECORDER_EVENT_INFO_UNKNOWN              = 1003;
    public static final int SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_DURATION   = 1004;
    public static final int SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_FILESIZE   = 1005;
    public static final int SESSION_EVENT_RECORDER_EVENT_INFO_NO_I_FRAME           = 1006;
    public static final int SESSION_EVENT_RECORDER_EVENT_INFO_COMPLETE             = 1007;
    public static final int SESSION_EVENT_CALL_END                                 = 1008;
    public static final int SESSION_EVENT_CALL_ABNORMAL_END                        = 1009;
    public static final int SESSION_EVENT_START_COUNTER                            = 1010;
    public static final int SESSION_EVENT_PEER_CAMERA_OPEN                         = 1011;
    public static final int SESSION_EVENT_PEER_CAMERA_CLOSE                        = 1012;

    public static final int SESSION_EVENT_RECV_SESSION_CONFIG_REQ                  = 4001;
    public static final int SESSION_EVENT_RECV_SESSION_CONFIG_RSP                  = 4002;
    public static final int SESSION_EVENT_HANDLE_CALL_SESSION_EVT                  = 4003;
    public static final int SESSION_EVENT_PEER_SIZE_CHANGED                        = 4004;
    public static final int SESSION_EVENT_LOCAL_SIZE_CHANGED                       = 4005;
    public static final int SESSION_EVENT_DATA_USAGE_CHANGED                       = 4006;
    public static final int SESSION_EVENT_CAM_CAP_CHANGED                          = 4007;
    public static final int SESSION_EVENT_BAD_DATA_BITRATE                         = 4008;
    public static final int SESSION_EVENT_DATA_BITRATE_RECOVER                     = 4009;

    public static final int SESSION_EVENT_ERROR_SERVICE                            = 8001;
    public static final int SESSION_EVENT_ERROR_SERVER_DIED                        = 8002;
    public static final int SESSION_EVENT_ERROR_CAMERA                             = 8003;
    public static final int SESSION_EVENT_ERROR_CODEC                              = 8004;
    public static final int SESSION_EVENT_ERROR_REC                                = 8005;
    public static final int SESSION_EVENT_ERROR_CAMERA_SET_IGNORED                 = 8006;

    public static final int SESSION_EVENT_WARNING_SERVICE_NOT_READY                = 9001;

    static final String                         TAG = "GsmVTProvider";

    private int                                 mId = 1;
    private GsmVTProviderUtil                   mUtil;
    private static int                          mDefaultId = VT_PROVIDER_INVALIDE_ID;

    public GsmVTProvider(int id) {
        super();

        Log.d(TAG, "New GsmVTProvider id = " + id);

        // Check id if exist in map
        // The same id exist mean the last call with the same id
        // does not disconnected yet at native layer.
        int wait_time = 0;
        Log.d(TAG, "New GsmVTProvider check if exist the same id");
        while (null != GsmVTProviderUtil.recordGet(id)) {
            Log.d(TAG, "New GsmVTProvider the same id exist, wait ...");

            try {
                Thread.sleep(1000);                 //1000 milliseconds is one second.
            } catch(InterruptedException ex) {
            }

            wait_time += 1;
            if (wait_time > 10) {
                Log.d(TAG, "New GsmVTProvider the same id exist, break!");
                break;
            }
        }

        mId = id;
        mUtil = new GsmVTProviderUtil();
        GsmVTProviderUtil.recordAdd(mId, this);

        updateEMParam(mId);

        nInitialization(mId);

        if (mDefaultId == VT_PROVIDER_INVALIDE_ID) {
            mDefaultId = mId;
        }
    }

    public GsmVTProvider() {
        super();
        Log.d(TAG, "New GsmVTProvider without id");
        mId = VT_PROVIDER_INVALIDE_ID;
    }

    public void setId(int id) {
        Log.d(TAG, "setId id = " + id);
        Log.d(TAG, "setId mId = " + mId);

        if (mId == VT_PROVIDER_INVALIDE_ID) {

            // Check id if exist in map
            // The same id exist mean the last call with the same id
            // does not disconnected yet at native layer.
            int wait_time = 0;
            Log.d(TAG, "New GsmVTProvider check if exist the same id");
            while (null != GsmVTProviderUtil.recordGet(id)) {
                Log.d(TAG, "New GsmVTProvider the same id exist, wait ...");

                try {
                    Thread.sleep(1000);                 //1000 milliseconds is one second.
                } catch(InterruptedException ex) {
                }

                wait_time += 1;
                if (wait_time > 10) {
                    Log.d(TAG, "New GsmVTProvider the same id exist, break!");
                    break;
                }
            }

            mId = id;
            mUtil = new GsmVTProviderUtil();
            GsmVTProviderUtil.recordAdd(mId, this);
            nInitialization(mId);

            updateEMParam(mId);

            if (mDefaultId == VT_PROVIDER_INVALIDE_ID) {
                mDefaultId = mId;
            }
        }
    }

    public int getId() {
        return mId;
    }

    private static void updateDefaultId() {

        if (!GsmVTProviderUtil.recordContain(mDefaultId)) {
            if (GsmVTProviderUtil.recordSize() != 0) {
                mDefaultId = GsmVTProviderUtil.recordPopId();
                return;
            }
            mDefaultId = VT_PROVIDER_INVALIDE_ID;
        }
        return;
    }

    public static native int nInitialization(int id);
    public static native int nFinalization(int id);
    public static native int nSetCamera(int id, int cam);
    public static native int nSetPreviewSurface(int id, Surface surface);
    public static native int nSetDisplaySurface(int id, Surface surface);
    public static native int nSetCameraParameters(int id, String config);
    public static native int nSetDeviceOrientation(int id, int rotation);
    public static native int nSetUIMode(int id, int mode);
    public static native String nGetCameraParameters(int id);
    public static native int nGetCameraSensorCount(int id);
    public static native int nRequestPeerConfig(int id, String config);
    public static native int nResponseLocalConfig(int id, String config);
    public static native int nRequestCameraCapabilities(int id);
    public static native int nRequestCallDataUsage(int id);
    public static native int nSnapshot(int id, int type, String uri);
    public static native int nStartRecording(int id, int type, String url, long maxSize);
    public static native int nStopRecording(int id);
    public static native int nSetEM(int id, int item, int arg1, int arg2);

    private void updateEMParam(int id) {
        nSetEM(id, 0, 0, 0);
        nSetEM(id, 1, 0, 0);
        nSetEM(id, 1, 1, 0);
        nSetEM(id, 1, 2, 0);
        nSetEM(id, 1, 3, 0);
        nSetEM(id, 1, 4, 0);
        nSetEM(id, 1, 5, 0);
        nSetEM(id, 1, 6, 0);
        nSetEM(id, 3, 0, 0);
        nSetEM(id, 3, 1, 0);
        nSetEM(id, 4, 0, 0);
        nSetEM(id, 4, 1, 0);
        nSetEM(id, 5, 0, 0);
        nSetEM(id, 6, 0, 0);
        nSetEM(id, 7, 1, 0);
        nSetEM(id, 8, 0, 28);
        nSetEM(id, 8, 1, 28);
        nSetEM(id, 8, 2, 28);
        nSetEM(id, 8, 3, 28);
        nSetEM(id, 9, 0, 0);
    }

    public void onSetCamera(String cameraId) {

        if (mId == VT_PROVIDER_INVALIDE_ID) {
            handleCallSessionEvent(SESSION_EVENT_WARNING_SERVICE_NOT_READY);
            return;
        }

        if (cameraId != null) {
            nSetCamera(mId, Integer.valueOf(cameraId));
        } else {
            nSetCamera(mId, GsmVTProviderUtil.TURN_OFF_CAMERA);
        }
    }

    public void onSetPreviewSurface(Surface surface) {
        nSetPreviewSurface(mId, surface);

        if(surface == null) {
            GsmVTProviderUtil.surfaceSet(mId, true, false);
        } else {
            GsmVTProviderUtil.surfaceSet(mId, true, true);
        }

        if (GsmVTProviderUtil.surfaceGet(mId) == 0) {
            GsmVTProvider vp = GsmVTProviderUtil.recordGet(mId);
            if (vp != null) {
                vp.handleCallSessionEvent(SESSION_EVENT_CALL_END);
            }
        }
    }

    public void onSetDisplaySurface(Surface surface) {
        nSetDisplaySurface(mId, surface);

        if(surface == null) {
            GsmVTProviderUtil.surfaceSet(mId, false, false);
        } else {
            GsmVTProviderUtil.surfaceSet(mId, false, true);
        }

        if (GsmVTProviderUtil.surfaceGet(mId) == 0) {
            GsmVTProvider vp = GsmVTProviderUtil.recordGet(mId);
            if (vp != null) {
                vp.handleCallSessionEvent(SESSION_EVENT_CALL_END);
            }
        }
    }

    public void onSetDeviceOrientation(int rotation) {
        nSetDeviceOrientation(mId, rotation);
    }

    public void onSetZoom(float value) {
        mUtil.getSetting().set(GsmVTProviderUtil.ParameterSet.KEY_ZOOM, (int) value);
        String currentSeeting = mUtil.getSetting().flatten();
        nSetCameraParameters(mId, currentSeeting);
    }

    public void onSendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
        /*
        VideoProfile.STATE_AUDIO_ONLY = 0x0;
        VideoProfile.STATE_TX_ENABLED = 0x1;
        VideoProfile.STATE_RX_ENABLED = 0x2;
        VideoProfile.STATE_BIDIRECTIONAL = VideoProfile.STATE_TX_ENABLED |
            VideoProfile.STATE_RX_ENABLED;
        VideoProfile.STATE_PAUSED = 0x4;
        */
        nRequestPeerConfig(mId, GsmVTProviderUtil.packFromVdoProfile(toProfile));
    }

    public void onSendSessionModifyResponse(VideoProfile responseProfile) {
        /*
        VideoProfile.STATE_AUDIO_ONLY = 0x0;
        VideoProfile.STATE_TX_ENABLED = 0x1;
        VideoProfile.STATE_RX_ENABLED = 0x2;
        VideoProfile.STATE_BIDIRECTIONAL = VideoProfile.STATE_TX_ENABLED |
            VideoProfile.STATE_RX_ENABLED;
        VideoProfile.STATE_PAUSED = 0x4;
        */
        nResponseLocalConfig(mId, GsmVTProviderUtil.packFromVdoProfile(responseProfile));
    }

    public void onRequestCameraCapabilities() {

        //while (mId == VT_PROVIDER_INVALIDE_ID) {
            //try {
                //Thread.sleep(500);
            //} catch(InterruptedException ex) {
            //}
        //}

        nRequestCameraCapabilities(mId);
    }

    public void onRequestCallDataUsage() {
        nRequestCallDataUsage(mId);
    }

    public void onSetPauseImage(Uri uri) {
    }

    public void onSetUIMode(int mode) {
        if (mode == GsmVTProviderUtil.UI_MODE_DESTROY) {
            nFinalization(mId);
        } else {
            nSetUIMode(mId, mode);
        }
    }

    public static void postEventFromNative(
            int msg,
            int id,
            int arg1,
            int arg2,
            int arg3,
            Object obj1,
            Object obj2,
            Object obj3) {

        GsmVTProvider vp = GsmVTProviderUtil.recordGet(id);

        if (null == vp) {
            Log.e(TAG, "Error: post event to Call is already release or has happen error before!");
            return;
        }

        Log.i(TAG, "postEventFromNative [" + msg + "]");
        switch (msg) {
            case SESSION_EVENT_RECEIVE_FIRSTFRAME:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_RECEIVE_FIRSTFRAME");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_SNAPSHOT_DONE:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_SNAPSHOT_DONE");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECORDER_EVENT_INFO_UNKNOWN:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_RECORDER_EVENT_INFO_UNKNOWN");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_DURATION:
                Log.d(TAG, "postEventFromNative : msg = " +
                     "SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_DURATION");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_FILESIZE:
                Log.d(TAG, "postEventFromNative : msg = " +
                     "SESSION_EVENT_RECORDER_EVENT_INFO_REACH_MAX_FILESIZE");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECORDER_EVENT_INFO_NO_I_FRAME:
                Log.d(TAG, "postEventFromNative : msg = " +
                    "SESSION_EVENT_RECORDER_EVENT_INFO_NO_I_FRAME");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECORDER_EVENT_INFO_COMPLETE:
                Log.d(TAG, "postEventFromNative : msg = " +
                    "SESSION_EVENT_RECORDER_EVENT_INFO_COMPLETE");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_CALL_END:
            case SESSION_EVENT_CALL_ABNORMAL_END:
                Log.d(TAG, "postEventFromNative : msg = " +
                      "SESSION_EVENT_CALL_END / SESSION_EVENT_CALL_ABNORMAL_END");

                GsmVTProviderUtil.recordRemove(id);
                updateDefaultId();

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_START_COUNTER:
                Log.d(TAG, "postEventFromNative : msg = MSG_START_COUNTER");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_PEER_CAMERA_OPEN:
                Log.d(TAG, "postEventFromNative : msg = MSG_PEER_CAMERA_OPEN");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_PEER_CAMERA_CLOSE:
                Log.d(TAG, "postEventFromNative : msg = MSG_PEER_CAMERA_CLOSE");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_RECV_SESSION_CONFIG_REQ:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_RECV_SESSION_CONFIG_REQ");

                vp.receiveSessionModifyRequest(
                        GsmVTProviderUtil.unPackToVdoProfile((String) obj1));
                break;

            case SESSION_EVENT_RECV_SESSION_CONFIG_RSP:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_RECV_SESSION_CONFIG_RSP");

                vp.receiveSessionModifyResponse(
                        arg1,
                        GsmVTProviderUtil.unPackToVdoProfile((String) obj1),
                        GsmVTProviderUtil.unPackToVdoProfile((String) obj2));
                break;

            case SESSION_EVENT_HANDLE_CALL_SESSION_EVT:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_HANDLE_CALL_SESSION_EVT");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_PEER_SIZE_CHANGED:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_PEER_SIZE_CHANGED");

                vp.changePeerDimensionsWithAngle(arg1, arg2, arg3);
                break;

            case SESSION_EVENT_LOCAL_SIZE_CHANGED:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_LOCAL_SIZE_CHANGED");

                break;

            case SESSION_EVENT_DATA_USAGE_CHANGED:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_DATA_USAGE_CHANGED");

                vp.changeCallDataUsage(arg1);
                break;

            case SESSION_EVENT_CAM_CAP_CHANGED:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_CAM_CAP_CHANGED");

                Log.d(TAG, (String) obj1);

                GsmVTProviderUtil.getSetting().unflatten((String) obj1);
                GsmVTProviderUtil.ParameterSet set = GsmVTProviderUtil.getSetting();

                int zoom_max = set.getInt(GsmVTProviderUtil.ParameterSet.KEY_MAX_ZOOM, 0);
                boolean zoom_support =
                  "true".equals(set.get(GsmVTProviderUtil.ParameterSet.KEY_ZOOM_SUPPORTED));
                List<Size> size = set.getSizeList(GsmVTProviderUtil.ParameterSet.KEY_PREVIEW_SIZE);

                // default size
                int width = 176;
                int height = 144;

                if (size != null) {
                    width = size.get(0).width;
                    height = size.get(0).height;
                }

                //There is 90 degree of difference between camera and portrait mode,
                //we need to swap width and height here.
                int temp = 0;
                temp = width;
                width = height;
                height = temp;

                CameraCapabilities camCap =
                  new CameraCapabilities(width, height, zoom_support, zoom_max);

                vp.changeCameraCapabilities(camCap);
                break;

            case SESSION_EVENT_BAD_DATA_BITRATE:
                Log.d(TAG, "postEventFromNative : msg = SESSION_EVENT_BAD_DATA_BITRATE");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_ERROR_SERVICE:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_SERVICE");
                GsmVTProviderUtil.recordRemove(id);
                updateDefaultId();

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_ERROR_SERVER_DIED:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_SERVER_DIED");
                GsmVTProviderUtil.recordRemove(id);
                updateDefaultId();

                // because the event may happen when no call exist
                // need to check firstly
                if (vp != null) {
                    vp.handleCallSessionEvent(msg);
                }
                break;

            case SESSION_EVENT_ERROR_CAMERA:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_CAMERA");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_ERROR_CODEC:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_CODEC");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_ERROR_REC:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_REC");

                vp.handleCallSessionEvent(msg);
                break;

            case SESSION_EVENT_ERROR_CAMERA_SET_IGNORED:
                Log.d(TAG, "postEventFromNative : msg = MSG_ERROR_CAMERA_SET_IGNORED");

                vp.handleCallSessionEvent(msg);
                break;

            default:
                Log.d(TAG, "postEventFromNative : msg = UNKNOWB");
                break;
        }
    }
}
