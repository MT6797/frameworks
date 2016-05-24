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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings.Global;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.ResourceIcon;
import com.mediatek.internal.telephony.ITelephonyEx;

/** Quick settings tile: Airplane mode **/
public class AirplaneModeTile extends QSTile<QSTile.BooleanState> {
    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_airplane_enable_animation);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_airplane_disable_animation);
    private final GlobalSetting mSetting;

    private boolean mListening;

    public AirplaneModeTile(Host host) {
        super(host);

        mSetting = new GlobalSetting(mContext, mHandler, Global.AIRPLANE_MODE_ON) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        // Log.d(TAG, "handleClick() mSwitching= " + mSwitching);
        if (mSwitching) {
            return;
        } else {
            startAnimation();
        }
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
        setEnabled(!mState.value);
        // M: Maybe airplane mode need more time to turn on/off
        // mEnable.setAllowAnimation(true);
        mDisable.setAllowAnimation(true);
    }

    private void setEnabled(boolean enabled) {
        Log.d(TAG, "setEnabled = " + enabled);
        final ConnectivityManager mgr =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mgr.setAirplaneMode(enabled);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean airplaneMode = value != 0;
        // Log.d(TAG, "handleUpdateState() mSetting.getValue()= " + airplaneMode);
        state.value = airplaneMode;
        state.visible = true;
        state.label = mContext.getString(R.string.airplane_mode);
        if (airplaneMode) {
            state.icon = mEnable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_airplane_on);
        } else {
            state.icon = mDisable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_airplane_off);
        }
        // /M: Maybe airplane mode need more time to turn on/off @{
        handleAnimationState(state, arg);
        // @}
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_AIRPLANEMODE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_off);
        }
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        // Log.d(TAG, "setListening() mListening= " + mListening);
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            // /M: Maybe airplane mode need more time to turn on/off @{
            filter.addAction(INTENT_ACTION_AIRPLANE_CHANGE_DONE);
            // @}
            mContext.registerReceiver(mReceiver, filter);
            if (!isAirplanemodeAvailableNow()) {
                Log.d(TAG, "setListening() Airplanemode not Available, start anim.");
                startAnimation();
            }
        } else {
            mContext.unregisterReceiver(mReceiver);
            stopAnimation();
        }
        mSetting.setListening(listening);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                // M: Maybe airplane mode need more time to turn on/off
                // refreshState();
            }
            // /M: Maybe airplane mode need more time to turn on/off @{
            else if (INTENT_ACTION_AIRPLANE_CHANGE_DONE.equals(intent.getAction())) {
                boolean airplaneModeOn = intent.getBooleanExtra(EXTRA_AIRPLANE_MODE, false);
                Log.d(TAG, "onReceive() AIRPLANE_CHANGE_DONE,  mAirplaneModeOn= " + airplaneModeOn);
                stopAnimation();
                refreshState();
            // @}
            }
        }
    };

    // /M: Maybe airplane mode need more time to turn on/off @{
    private static final String INTENT_ACTION_AIRPLANE_CHANGE_DONE =
                                "com.mediatek.intent.action.AIRPLANE_CHANGE_DONE";
    private static final String EXTRA_AIRPLANE_MODE = "airplaneMode";
    private int mCount;
    private boolean mSwitching;
    private static final int EMPTY_MSG = 0;
    private static final int ANIM_COUNT = 2;
    private static final int ANIM_DELAY = 400;
    private static final int ANIM_LIMITATE = ANIM_COUNT * 50;
    private Icon[] mAnimMembers = new Icon[] {
            // ResourceIcon.get(R.drawable.ic_signal_airplane_swiching_1),
            ResourceIcon.get(R.drawable.ic_signal_airplane_swiching_2),
            ResourceIcon.get(R.drawable.ic_signal_airplane_swiching_3) };
    private AnimationHandler mAnimHandler = new AnimationHandler();

    private class AnimationHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Log.d(TAG, "AnimationHandler handleMessage()");
            refreshState();
            mAnimHandler.sendEmptyMessageDelayed(EMPTY_MSG, ANIM_DELAY);
            if (mCount++ >= ANIM_LIMITATE) mCount = 0;
        }
    }

    private void startAnimation() {
        stopAnimation();
        mSwitching = true;
        mAnimHandler.sendEmptyMessage(EMPTY_MSG);
        Log.d(TAG, "startAnimation()");
    }

    private void stopAnimation() {
        mSwitching = false;
        mCount = 0;
        if (mAnimHandler.hasMessages(EMPTY_MSG)) {
            mAnimHandler.removeMessages(EMPTY_MSG);
        }
        Log.d(TAG, "stopAnimation()");
    }

    private void handleAnimationState(BooleanState state, Object arg) {
        Log.d(TAG, "handleAnimationState() mSwitching= " + mSwitching
                + ", mCount= " + mCount);
        if (mSwitching) {
            state.icon = mAnimMembers[mCount % ANIM_COUNT];
        }
    }

    private boolean isAirplanemodeAvailableNow() {
        ITelephonyEx telephonyEx = ITelephonyEx.Stub.asInterface(ServiceManager
                .getService(Context.TELEPHONY_SERVICE_EX));
        boolean isAvailable = false;
        try {
            if (telephonyEx != null) {
                isAvailable = telephonyEx.isAirplanemodeAvailableNow();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "isAirplaneModeAvailable = " + isAvailable);
        return isAvailable;
    }
    // @}
}
