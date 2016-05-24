/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.qs.QSTile.ResourceIcon;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataController;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataController.DataUsageInfo;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.SignalCallbackAdapter;

/// M: add DataUsage in quicksetting @{
import com.mediatek.systemui.ext.IQuickSettingsPlugin;
import com.mediatek.systemui.PluginManager;
/// add DataUsage in quicksetting @}
/** Quick settings tile: Cellular **/
public class CellularTile extends QSTile<QSTile.SignalState> {

    // / M: For debug @{
    private static final String TAG = "CellularTile";
    private static final boolean DBG = true;
    // @}

    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private final NetworkController mController;
    private final MobileDataController mDataController;
    private final CellularDetailAdapter mDetailAdapter;
    /// M: add DataUsage for operator @{
    private IQuickSettingsPlugin mQuickSettingsPlugin;
    private boolean mDisplayDataUsage;
    private Icon mIcon;
    /// add DataUsage for operator @}

    private final CellSignalCallback mSignalCallback = new CellSignalCallback();

    public CellularTile(Host host) {
        super(host);
        mController = host.getNetworkController();
        mDataController = mController.getMobileDataController();
        mDetailAdapter = new CellularDetailAdapter();
        /// M: add DataUsage for operator @{
        mQuickSettingsPlugin = PluginManager
                .getQuickSettingsPlugin(mContext);
        mDisplayDataUsage = mQuickSettingsPlugin.customizeDisplayDataUsage(false);
        mIcon = ResourceIcon.get(R.drawable.ic_qs_data_usage);
        /// add DataUsage for operator @}
    }

    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addSignalCallback(mSignalCallback);
        } else {
            mController.removeSignalCallback(mSignalCallback);
        }
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory());
        // M: Start setting activity when default SIM isn't setted
        if (mDataController.isMobileDataSupported()
                && mDataController.isDefaultDataSimExist()) {
            showDetail(true);
        } else {
            mHost.startActivityDismissingKeyguard(CELLULAR_SETTINGS);
        }
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        /// M: add DataUsage for operator @{
        if (mDisplayDataUsage) {
            Log.i(TAG, "customize datausage, displayDataUsage = " + mDisplayDataUsage);
            state.visible = true;
            state.icon = mIcon;
            state.label = mContext.getString(R.string.data_usage);
            state.contentDescription = mContext.getString(R.string.data_usage);
            return;
        }
        /// add DataUsage for operator @}
        state.visible = mController.hasMobileDataFeature();
        if (!state.visible) return;
        CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) {
            cb = mSignalCallback.mInfo;
        }

        final Resources r = mContext.getResources();
        final int iconId = cb.noSim ? R.drawable.ic_qs_no_sim
                : !cb.enabled || cb.airplaneModeEnabled ? R.drawable.ic_qs_signal_disabled
                : cb.mobileSignalIconId > 0 ? cb.mobileSignalIconId
                : R.drawable.ic_qs_signal_no_signal;
        state.icon = ResourceIcon.get(iconId);
        state.isOverlayIconWide = cb.isDataTypeIconWide;
        state.autoMirrorDrawable = !cb.noSim;
        // M: Update roaming icon with airplane mode state
        state.overlayIconId = cb.enabled && (cb.dataTypeIconId > 0)
                && !cb.airplaneModeEnabled ? cb.dataTypeIconId : 0;
        state.filter = iconId != R.drawable.ic_qs_no_sim;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;

        state.label = cb.enabled
                ? removeTrailingPeriod(cb.enabledDesc)
                : r.getString(R.string.quick_settings_rssi_emergency_only);

        final String signalContentDesc = cb.enabled && (cb.mobileSignalIconId > 0)
                ? cb.signalContentDescription
                : r.getString(R.string.accessibility_no_signal);
        final String dataContentDesc = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiEnabled
                ? cb.dataContentDescription
                : r.getString(R.string.accessibility_no_data);
        state.contentDescription = r.getString(
                R.string.accessibility_quick_settings_mobile,
                signalContentDesc, dataContentDesc,
                state.label);

        // /M: Change the icon/label when default SIM isn't setted @{
        if (TelephonyManager.from(mContext).getNetworkOperator() != null
                && !cb.noSim && !mDataController.isDefaultDataSimExist()) {
            Log.d(TAG, "Default data sim not exist");
            state.icon = ResourceIcon.get(R.drawable.ic_qs_data_sim_not_set);
            state.label = r.getString(R.string.quick_settings_data_sim_notset);
            state.overlayIconId = 0;
            state.filter = true;
            state.activityIn = false;
            state.activityOut = false;
        }
        // @}
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_CELLULAR;
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        boolean wifiEnabled;
        boolean airplaneModeEnabled;
        int mobileSignalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        String enabledDesc;
        boolean noSim;
        boolean isDataTypeIconWide;
    }

    private final class CellSignalCallback extends SignalCallbackAdapter {
        private final CallbackInfo mInfo = new CallbackInfo();
        @Override
        public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description) {
            mInfo.wifiEnabled = enabled;
            refreshState(mInfo);
        }
        /// M: Modify to support [Network Type on Statusbar], change the implement methods,
        /// add one more parameter for network type.
        @Override
        public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
                int networkIcon, int qsType, boolean activityIn, boolean activityOut,
                String typeContentDescription, String description, boolean isWide, int subId) {
            if (qsIcon == null) {
                // Not data sim, don't display.
                Log.d(TAG, "setMobileDataIndicator qsIcon = null, Not data sim, don't display");
                return;
            }
            mInfo.enabled = qsIcon.visible;
            mInfo.mobileSignalIconId = qsIcon.icon;
            mInfo.signalContentDescription = qsIcon.contentDescription;
            mInfo.dataTypeIconId = qsType;
            mInfo.dataContentDescription = typeContentDescription;
            mInfo.activityIn = activityIn;
            mInfo.activityOut = activityOut;
            mInfo.enabledDesc = description;
            mInfo.isDataTypeIconWide = qsType != 0 && isWide;
            if (DBG) {
                Log.d(TAG, "setMobileDataIndicators info.enabled = " + mInfo.enabled +
                    " mInfo.mobileSignalIconId = " + mInfo.mobileSignalIconId +
                    " mInfo.signalContentDescription = " + mInfo.signalContentDescription +
                    " mInfo.dataTypeIconId = " + mInfo.dataTypeIconId +
                    " mInfo.dataContentDescription = " + mInfo.dataContentDescription +
                    " mInfo.activityIn = " + mInfo.activityIn +
                    " mInfo.activityOut = " + mInfo.activityOut +
                    " mInfo.enabledDesc = " + mInfo.enabledDesc +
                    " mInfo.isDataTypeIconWide = " + mInfo.isDataTypeIconWide);
            }
            refreshState(mInfo);
        }

        @Override
        public void setNoSims(boolean show) {
            Log.d(TAG, "setNoSims, noSim = " + show);
            mInfo.noSim = show;
            if (mInfo.noSim) {
                // Make sure signal gets cleared out when no sims.
                mInfo.mobileSignalIconId = 0;
                mInfo.dataTypeIconId = 0;
                // Show a No SIMs description to avoid emergency calls message.
                mInfo.enabled = true;
                mInfo.enabledDesc = mContext.getString(
                        R.string.keyguard_missing_sim_message_short);
                mInfo.signalContentDescription = mInfo.enabledDesc;
            }
            refreshState(mInfo);
        }

        @Override
        public void setIsAirplaneMode(IconState icon) {
            mInfo.airplaneModeEnabled = icon.visible;
            refreshState(mInfo);
        }

        @Override
        public void setMobileDataEnabled(boolean enabled) {
            mDetailAdapter.setMobileDataEnabled(enabled);
        }
    };

    private final class CellularDetailAdapter implements DetailAdapter {

        @Override
        public int getTitle() {
            return R.string.quick_settings_cellular_detail_title;
        }

        @Override
        public Boolean getToggleState() {
            return mDataController.isMobileDataSupported()
                    ? mDataController.isMobileDataEnabled()
                    : null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CELLULAR_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, MetricsLogger.QS_CELLULAR_TOGGLE, state);
            mDataController.setMobileDataEnabled(state);
        }

        @Override
        public int getMetricsCategory() {
            return MetricsLogger.QS_DATAUSAGEDETAIL;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final DataUsageDetailView v = (DataUsageDetailView) (convertView != null
                    ? convertView
                    : LayoutInflater.from(mContext).inflate(R.layout.data_usage, parent, false));
            final DataUsageInfo info = mDataController.getDataUsageInfo();
            if (info == null) return v;
            v.bind(info);
            return v;
        }

        public void setMobileDataEnabled(boolean enabled) {
            fireToggleStateChanged(enabled);
        }
    }
}
