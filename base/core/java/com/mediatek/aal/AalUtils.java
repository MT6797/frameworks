/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2015. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */
package com.mediatek.aal;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Slog;

import java.util.HashMap;
import java.util.Map;

/**
 * App-based AAL.
 */
public final class AalUtils {
    public static final int AAL_MODE_PERFORMANCE = 0;
    public static final int AAL_MODE_BALANCE = 1;
    public static final int AAL_MODE_LOWPOWER = 2;
    public static final int AAL_MODE_SIZE = AAL_MODE_LOWPOWER + 1;

    private static final String TAG = "AalUtils";
    private static boolean sDebug = false;

    private static boolean sIsAalSupported =
            SystemProperties.get("ro.mtk_aal_support").equals("1");
    private static boolean sEnabled = sIsAalSupported &&
            SystemProperties.get("persist.sys.mtk_app_aal_support").equals("1");

    private int mAalMode = AalUtils.AAL_MODE_BALANCE;

    private Map<AalIndex, Integer> mConfig = new HashMap<AalIndex, Integer>();
    private AalIndex mIndex = new AalIndex();

    private static AalUtils sInstance = null;

    AalUtils(boolean init) {
        if (!sIsAalSupported) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not supported");
            }
            return;
        }

        if (init == false) {
            return;
        }

        // You can customized AAL configuration for packages here.
        // Format:
        //     mConfig.put(
        //         new AalIndex(<mode>, <packageName>), <backlightLevel>);

        // AAL_MODE_PERFORMANCE
        mConfig.put(
            new AalIndex(AAL_MODE_PERFORMANCE, "com.android.launcher3"), 160);
        mConfig.put(
            new AalIndex(AAL_MODE_PERFORMANCE, "com.rovio.angrybirds"), 160);
        mConfig.put(
            new AalIndex(AAL_MODE_PERFORMANCE, "com.vectorunit.yellow"), 160);
        mConfig.put(
            new AalIndex(AAL_MODE_PERFORMANCE, "nl.dotsightsoftware.pacificfighter.release"), 128);

        // AAL_MODE_BALANCE
        mConfig.put(
            new AalIndex(AAL_MODE_BALANCE, "com.android.launcher3"), 192);
        mConfig.put(
            new AalIndex(AAL_MODE_BALANCE, "com.rovio.angrybirds"), 192);
        mConfig.put(
            new AalIndex(AAL_MODE_BALANCE, "com.vectorunit.yellow"), 192);
        mConfig.put(
            new AalIndex(AAL_MODE_BALANCE, "nl.dotsightsoftware.pacificfighter.release"), 160);

        // AAL_MODE_LOWPOWER
        mConfig.put(
            new AalIndex(AAL_MODE_LOWPOWER, "com.android.launcher3"), 240);
        mConfig.put(
            new AalIndex(AAL_MODE_LOWPOWER, "com.rovio.angrybirds"), 240);
        mConfig.put(
            new AalIndex(AAL_MODE_LOWPOWER, "com.vectorunit.yellow"), 240);
        mConfig.put(
            new AalIndex(AAL_MODE_LOWPOWER, "nl.dotsightsoftware.pacificfighter.release"), 192);
    }

    private static final int AAL_MIN_LEVEL = 0;
    private static final int AAL_MAX_LEVEL = 256;
    private static final int AAL_DEFAULT_LEVEL = 128;
    private AalConfig mCurrentConfig = null;

    /**
     * The device is support AAL or not.
     *
     * @return Support/Not support
     */
    public static boolean isSupported() {
        if (sDebug) {
            Slog.d(TAG, "isSupported = " + sIsAalSupported);
        }
        return sIsAalSupported;
    }

    /**
     * Get the instance of AalUtils.
     *
     * @return The instance of AalUtils
     */
    public static AalUtils getInstance() {
        return getInstance(false);
    }

    /**
     * Set AAL mode.
     *
     * @param context Context
     * @param mode AAL mode
     */
    public void setAalMode(Context context, int mode) {
        if (!sIsAalSupported) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not supported");
            }
            return;
        }

        if (sDebug) {
            Slog.d(TAG, "setAalMode " + mode + "(" + modeToString(mode) + ")");
        }

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        am.setAalMode(mode);
    }

    /**
     * Enable/Disable App-based AAL.
     *
     * @param context Context
     * @param enabled Enable/Disable
     */
    public void setEnabled(Context context, boolean enabled) {
        if (!sIsAalSupported) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not supported");
            }
            return;
        }

        if (sDebug) {
            Slog.d(TAG, "setEnabled(" + enabled + ")");
        }

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        am.setAalEnabled(enabled);
    }

    /**
     * Get the instance of AalUtils.
     *
     * @param init True for initializing configurations
     * @return The instance of AalUtils
     */
    public static AalUtils getInstance(boolean init) {
        if (sInstance == null) {
            sInstance = new AalUtils(init);
        }
        return sInstance;
    }

    /**
     * Set AAL mode.
     *
     * @param mode AAL mode
     * @return Message for the operation result
     */
    synchronized public String setAalModeInternal(int mode) {
        if (!sEnabled) {
            String msg = "AAL is not enabled";
            if (sDebug) {
                Slog.d(TAG, msg);
            }
            return msg;
        }

        String msg = null;
        if (mode >= 0 && mode < AAL_MODE_SIZE) {
            mAalMode = mode;
            msg = "setAalModeInternal " + mAalMode + "(" + modeToString(mAalMode) + ")";
        } else {
            msg = "unknown mode " + mode;
        }

        Slog.d(TAG, msg);
        return msg;
    }

    /**
     * Enable/Disable App-based AAL.
     *
     * @param enabled Enable/Disable
     */
    synchronized public void setEnabledInternal(boolean enabled) {
        if (!sIsAalSupported) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not supported");
            }
            return;
        }

        sEnabled = enabled;
        if (!sEnabled) {
            setDefaultSmartBacklightInternal("disabled");
            SystemProperties.set("persist.sys.mtk_app_aal_support", "0");
        } else {
            SystemProperties.set("persist.sys.mtk_app_aal_support", "1");
        }

        Slog.d(TAG, "setEnabledInternal(" + sEnabled + ")");
    }

    /**
     * Enable/Disable debug log.
     *
     * @param debug Enable/Disable
     * @return Message for the operation result
     */
    synchronized public String setDebugInternal(boolean debug) {
        String msg = "Set Debug: " + debug;
        Slog.d(TAG, msg);
        sDebug = debug;
        return msg;
    }

    /**
     * Set AAL config for the package.
     *
     * @param pkg Package name
     * @param value AAL level
     * @return Message for the operation result
     */
    synchronized public String setSmartBacklightTableInternal(String pkg, int value) {
        if (!sEnabled) {
            String msg = "AAL is not enabled";
            if (sDebug) {
                Slog.d(TAG, msg);
            }
            return msg;
        }

        return setSmartBacklightTableInternal(pkg, value, mAalMode);
    }

    /**
     * Set AAL config for the package and AAL mode.
     *
     * @param pkg Package name
     * @param value AAL level
     * @param mode AAL mode
     * @return Message for the operation result
     */
    synchronized public String setSmartBacklightTableInternal(String pkg, int value, int mode) {
        if (!sEnabled) {
            String msg = "AAL is not enabled";
            if (sDebug) {
                Slog.d(TAG, msg);
            }
            return msg;
        }

        if (mode < 0 || mode >= AAL_MODE_SIZE) {
            String msg = "Unknown mode: " + mode;
            if (sDebug) {
                Slog.d(TAG, msg);
            }
            return msg;
        }

        AalIndex index = new AalIndex(mode, pkg);
        if (sDebug) {
            Slog.d(TAG, "setSmartBacklightTable(" + value + ") for " + index);
        }
        mConfig.put(index, value);
        return "Set(" + value + ") for " + index;
    }

    /**
     * Set AAL level for the package.
     *
     * @param pkg Package name
     */
    synchronized public void setSmartBacklightInternal(String pkg) {
        if (!sEnabled) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not enabled");
            }
            return;
        }

        setSmartBacklightInternal(pkg, mAalMode);
    }

    /**
     * Set AAL level for the package and AAL mode.
     *
     * @param pkg Package name
     * @param mode AAL mode
     */
    synchronized public void setSmartBacklightInternal(String pkg, int mode) {
        if (!sEnabled) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not enabled");
            }
            return;
        }

        if (mode < 0 || mode >= AAL_MODE_SIZE) {
            if (sDebug) {
                Slog.d(TAG, "Unknown mode: " + mode);
            }
            return;
        }

        AalIndex index = new AalIndex(mode, pkg);
        if (mCurrentConfig == null) {
            if (sDebug) {
                Slog.d(TAG, "mCurrentConfig == null");
            }
            mCurrentConfig = new AalConfig(null, AAL_DEFAULT_LEVEL);
        }

        // get level by pkg
        AalConfig config = getAalConfig(index);
        int validLevel = ensureBacklightLevel(config.mLevel);
        if (sDebug) {
            Slog.d(TAG, "setSmartBacklight current level: " + mCurrentConfig.mLevel +
                " for " + index);
        }

        if (mCurrentConfig.mLevel != validLevel) {
            Slog.d(TAG, "setSmartBacklightStrength(" + validLevel + ") for " + index);
            mCurrentConfig.mLevel = validLevel;
            mCurrentConfig.mPackageName = pkg;
            setSmartBacklightStrength(validLevel);
        }
    }

    /**
     * Set AAL level to default value.
     *
     * @param reason Reason
     */
    synchronized public void setDefaultSmartBacklightInternal(String reason) {
        if (!sEnabled) {
            if (sDebug) {
                Slog.d(TAG, "AAL is not enabled");
            }
            return;
        }

        if (mCurrentConfig != null && mCurrentConfig.mLevel != AAL_DEFAULT_LEVEL) {
            Slog.d(TAG, "setSmartBacklightStrength(" + AAL_DEFAULT_LEVEL + ") " + reason);
            mCurrentConfig.mLevel = AAL_DEFAULT_LEVEL;
            mCurrentConfig.mPackageName = null;
            setSmartBacklightStrength(AAL_DEFAULT_LEVEL);
        }
    }

    private native void setSmartBacklightStrength(int level);

    private int ensureBacklightLevel(int level) {
        if (level < AAL_MIN_LEVEL) {
            if (sDebug) {
                Slog.e(TAG, "Invalid AAL backlight level: " + level);
            }
            return AAL_MIN_LEVEL;
        } else if (level > AAL_MAX_LEVEL) {
            if (sDebug) {
                Slog.e(TAG, "Invalid AAL backlight level: " + level);
            }
            return AAL_MAX_LEVEL;
        }

        return level;
    }

    private AalConfig getAalConfig(AalIndex index) {
        int level = AAL_DEFAULT_LEVEL;
        if (mConfig.containsKey(index)) {
            level = mConfig.get(index);
        } else {
            if (sDebug) {
                Slog.d(TAG, "No config for " + index);
            }
        }
        return new AalConfig(index.getPackageName(), level);
    }

    /**
     * Class for AAL config.
     */
    private class AalConfig {
        public String mPackageName = null;
        public int mLevel = AAL_DEFAULT_LEVEL;

        public AalConfig(String packageName, int level) {
            mPackageName = packageName;
            mLevel = level;
        }
    }

    /**
     * Dump AAL settings.
     *
     * @return AAL settings
     */
    synchronized public String dumpInternal() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nApp-based AAL Mode: " + mAalMode + "(" + modeToString(mAalMode) +
            "), Supported: " + sIsAalSupported + ", Enabled: " + sEnabled +
            ", Debug: " + sDebug + "\n");

        int i = 1;
        for (AalIndex index : mConfig.keySet()) {
            String level = Integer.toString(mConfig.get(index));
            sb.append("\n" + i + ". " + index + " - " + level);
            i++;
        }
        if (i == 1) {
            sb.append("\nThere is no App-based AAL configuration.\n");
            sb.append(dumpDebugUsageInternal());
        }
        if (sDebug) {
            Slog.d(TAG, "dump config: " + sb.toString());
        }
        return sb.toString();
    }

    /**
     * Dump usage of debug commnads.
     *
     * @return Usage of debug commnads
     */
    synchronized public String dumpDebugUsageInternal() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nUsage:\n\n");
        sb.append("1. App-based AAL help:\n\n");
        sb.append("    adb shell dumpsys activity aal\n\n");
        sb.append("2. Dump App-based AAL settings:\n\n");
        sb.append("    adb shell dumpsys activity aal dump\n\n");
        sb.append("1. App-based AAL debug on:\n\n");
        sb.append("    adb shell dumpsys activity aal debugon\n\n");
        sb.append("1. App-based AAL debug off:\n\n");
        sb.append("    adb shell dumpsys activity aal debugoff\n\n");
        sb.append("3. Enable App-based AAL:\n\n");
        sb.append("    adb shell dumpsys activity aal on\n\n");
        sb.append("4. Disable App-based AAL:\n\n");
        sb.append("    adb shell dumpsys activity aal off\n\n");
        sb.append("5. Set App-based AAL mode:\n\n");
        sb.append("    adb shell dumpsys activity aal mode <mode>\n\n");
        sb.append("6. Set App-based AAL config for current mode:\n\n");
        sb.append("    adb shell dumpsys activity aal set <pacakge> <value>\n\n");
        sb.append("7. Set App-based AAL config for the mode:\n\n");
        sb.append("    adb shell dumpsys activity aal set <pacakge> <value> <mode>\n\n");
        return sb.toString();
    }

    /**
     * Class for mapping AAL settings.
     */
    private class AalIndex {
        private int mMode;
        private String mPackageName;

        AalIndex() {
            set(mAalMode, null);
        }

        AalIndex(int mode, String name) {
            set(mode, name);
        }

        private void set(int mode, String name) {
            mMode = mode;
            mPackageName = name;
        }

        public int getMode() {
            return mMode;
        }

        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public String toString() {
            return "(" + mMode + ": " + modeToString(mMode) + ", " + mPackageName + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (sDebug) {
                Slog.d(TAG, this + ", " + obj.getClass().getName() + "@" +
                    Integer.toHexString(obj.hashCode()));
            }

            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof AalIndex)) {
                return false;
            }

            AalIndex index = (AalIndex) obj;
            if (sDebug) {
                Slog.d(TAG, this + ", " + index);
            }
            if (mPackageName == null && index.mPackageName == null) {
                return mMode == index.mMode;
            }
            return mMode == index.mMode && mPackageName.equals(index.mPackageName);
        }

        @Override
        public int hashCode() {
            String hashString = Integer.toString(mMode) + ":";
            if (mPackageName != null) {
                hashString = hashString + Integer.toString(mPackageName.hashCode());
            }
            if (sDebug) {
                Slog.d(TAG, this + " hashCode: " + hashString.hashCode());
            }
            return hashString.hashCode();
        }
    }

    private String modeToString(int mode) {
        switch (mode) {
        case AAL_MODE_PERFORMANCE:
            return "AAL_MODE_PERFORMANCE";
        case AAL_MODE_BALANCE:
            return "AAL_MODE_BALANCE";
        case AAL_MODE_LOWPOWER:
            return "AAL_MODE_LOWPOWER";
        default:
        }

        return "Unknown mode: " + mode;
    }
}

