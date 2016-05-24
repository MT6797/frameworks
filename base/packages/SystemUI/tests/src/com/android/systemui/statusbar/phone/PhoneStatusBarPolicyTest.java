/*
* Copyright (C) 2014 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.systemui.statusbar.phone;

import java.util.Set;
import android.util.ArraySet;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.test.AndroidTestCase;
import android.telecom.TelecomManager;
import android.app.StatusBarManager;

import static org.mockito.Mockito.mock;

import com.android.systemui.R;

public class PhoneStatusBarPolicyTest extends AndroidTestCase {

    private PhoneStatusBarPolicy mPsbp, mSpy;

    CastController mMockCast;
    HotspotController mMockHotspot;
    UserInfoController mMockUserInfoController;
    BluetoothControllerImpl mMockBluetooth;

    StatusBarManager mService;

    ArraySet<CastDevice> mCastDevices;
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMockCast = mock(CastController.class, "CastController");
        mMockHotspot = mock(HotspotController.class, "HotspotController");
        mMockUserInfoController = new UserInfoController(mContext);
        mMockBluetooth = mock(BluetoothControllerImpl.class,
                "BluetoothController");


        mPsbp = new PhoneStatusBarPolicy(mContext, mMockCast, mMockHotspot,
                mMockUserInfoController, mMockBluetooth);

        mPsbp.mService = mock(StatusBarManager.class, "StatusBarManager");

        mService = mPsbp.mService;
    }

    public void testHeadSetIcon() {
        int expectedIcon = R.drawable.stat_sys_headset_with_mic;

        injectedHsIntent(Intent.ACTION_HEADSET_PLUG, 1, 1);

        ArgumentCaptor<Integer> outArg = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(mService, Mockito.atLeastOnce()).setIcon(
                Mockito.anyString(), outArg.capture(), Mockito.anyInt(),
                Mockito.anyString());

        assertEquals("HeadSet Plug in status bar", expectedIcon,
                (int) outArg.getValue());
    }

    public void testHeadSetIconInVisibility(){
        boolean expectedValue = false;

        injectedHsIntent(Intent.ACTION_HEADSET_PLUG, 0, 1);

        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mService, Mockito.atLeastOnce()).setIconVisibility(
                Mockito.anyString(), outArg.capture());

        assertEquals("TTY changed icon visibility", expectedValue, (boolean) outArg.getValue());
    }

    private void injectedHsIntent(String action, int state, int microphone){
        Intent i = new Intent(Intent.ACTION_HEADSET_PLUG);
        i.putExtra("state", state);
        i.putExtra("microphone", microphone);

        mPsbp.mIntentReceiver.onReceive(mContext, i);
    }

    public void testTTYIcon() {
        int expectedIcon = R.drawable.stat_sys_tty_mode;

        injectedIntentTty(TelecomManager.TTY_MODE_FULL);

        ArgumentCaptor<Integer> outArg = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(mService, Mockito.atLeastOnce()).setIcon(
                Mockito.anyString(), outArg.capture(), Mockito.anyInt(),
                Mockito.anyString());

        assertEquals("TTY changed icon", expectedIcon, (int) outArg.getValue());

    }

    public void testTTYIconInvisiable(){
        boolean expectedValue = false;

        injectedIntentTty(TelecomManager.TTY_MODE_OFF);

        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mService, Mockito.atLeastOnce()).setIconVisibility(
                Mockito.anyString(), outArg.capture());

        assertEquals("TTY changed icon visibility", expectedValue, (boolean) outArg.getValue());
    }

    private void injectedIntentTty(int tty_mode){
        Intent i = new Intent(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        i.putExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE, tty_mode);

        mPsbp.mIntentReceiver.onReceive(mContext, i);
    }

    protected void tearDown() throws Exception {
        super.tearDown();
    }

}
