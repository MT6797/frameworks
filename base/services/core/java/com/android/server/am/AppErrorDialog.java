/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSION;
import android.content.Context;
import android.content.DialogInterface;
///M:for low storage feature @{
import android.content.Intent;
///@}
/// M:Permission exception dialog, @{
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
/// @}
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
///M:for low storage feature @{
import android.provider.Settings;
///@}
import android.util.Slog;
import android.view.WindowManager;

///M:for low storage feature @{
import com.android.server.LocalServices;
import com.android.server.storage.DeviceStorageMonitorInternal;
///@}

/// M:Permission exception dialog, @{
import com.mediatek.common.mom.MobileManagerUtils;
/// @}

final class AppErrorDialog extends BaseErrorDialog {
    private final ActivityManagerService mService;
    private final static String TAG = "AppErrorDialog";
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;

    // Event 'what' codes
    static final int FORCE_QUIT = 0;
    static final int FORCE_QUIT_AND_REPORT = 1;
    private static final int FREE_SPACE = 10;
    /// M:Permission exception dialog, @{
    private static final int PERMISSION_SETTINGS = 11;
    /// @}

    private Context mContext;

    /// M: ALPS00944212 prevent binder wait forever until 5mins timeout @{
    private int mResultType = FORCE_QUIT;
    /// @}

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;
    ///M:For low storage, do not show target process error,when Peocess crash @{
    private boolean mTargetProcess = false;
    ///@}

    /// M:Permission exception dialog, @{
    private static final String SECURITY_EXCEPTION = "SecurityException";
    private static final String SECURITY_SUB_PERMISSION_DENIAL = "Permission Denial";
    private static final String SECURITY_SUB_REQUIRES = " requires ";
    private static final String SECURITY_SUB_OR = " or ";
    private static final int CRASH_BY_PERMISSION_NONE = 0;
    private static final int CRASH_BY_PERMISSION_DETAIL = 1;
    private static final int CRASH_BY_PERMISSION_TRY = 2;
    private String mExceptionMsg;
    /// @}

    public AppErrorDialog(Context context, ActivityManagerService service,
            AppErrorResult result, ProcessRecord app) {
        super(context);
        mContext = context;
        Resources res = context.getResources();
        
        mService = service;
        mProc = app;
        mResult = result;
        CharSequence name;
        ///M:For low storage, show the difference dialog,when APP&Peocess crash @{
        CharSequence message;
        /// M:Permission exception dialog, @{
        mExceptionMsg = mResult.getExceptionMsg();
        /// @}

        final DeviceStorageMonitorInternal
                    dsm = LocalServices.getService(DeviceStorageMonitorInternal.class);
        boolean criticalLow = dsm.isMemoryCriticalLow();
        /// M:Permission exception dialog, @{
        int crashByMustHavePermission = CRASH_BY_PERMISSION_NONE;
        String permissionTitled = null;
        if (!MobileManagerUtils.isSupported() && mExceptionMsg != null
                && mExceptionMsg.contains(SECURITY_EXCEPTION)) {
            if (DEBUG_PERMISSION) {
                Slog.v(TAG, "AppErrorDialog mExceptionMsg = " + mExceptionMsg);
            }
            if (mExceptionMsg.contains(SECURITY_SUB_PERMISSION_DENIAL)) {
                int startIndex = mExceptionMsg.indexOf(SECURITY_SUB_REQUIRES)
                        + SECURITY_SUB_REQUIRES.length();
                String parseResult = mExceptionMsg.substring(startIndex, mExceptionMsg.length());
                if (parseResult.contains(SECURITY_SUB_OR)) {
                    startIndex = parseResult.indexOf(SECURITY_SUB_OR) +
                            SECURITY_SUB_OR.length();
                    parseResult = parseResult.substring(startIndex, parseResult.length());
                }

                permissionTitled = getPermissionTitle(context.getPackageManager(),
                        parseResult);

                if (DEBUG_PERMISSION) {
                    Slog.v(TAG, "AppErrorDialog parseResult = " + parseResult +
                            "and permissionTitled = " + permissionTitled);
                }
                if (permissionTitled != null) {
                    crashByMustHavePermission = CRASH_BY_PERMISSION_DETAIL;
                }
            }
            if (crashByMustHavePermission == CRASH_BY_PERMISSION_NONE) {
                crashByMustHavePermission = CRASH_BY_PERMISSION_TRY;
            }
        }
        /// @}
        if ((app.pkgList.size() == 1) &&
                (name=context.getPackageManager().getApplicationLabel(app.info)) != null) {
                mTargetProcess = false;
                if (crashByMustHavePermission != CRASH_BY_PERMISSION_NONE) {
                    /// M:Permission exception dialog
                    if (crashByMustHavePermission == CRASH_BY_PERMISSION_DETAIL) {
                        message = res.getString(
                                com.mediatek.internal.R.string.aerr_application_permission,
                                name.toString(), permissionTitled);
                    } else {
                        message = res.getString(
                                com.mediatek.internal.R.string.aerr_application_unknown_permission,
                                name.toString());
                    }
                } else if (criticalLow == true) {
                    message = res.getString(
                            com.mediatek.internal.R.string.aerr_application_lowstorage,
                            name.toString(), app.info.processName);
                } else {
                    message = res.getString(
                        com.android.internal.R.string.aerr_application,
                        name.toString(), app.info.processName);
                }
                setMessage(message);
        } else {
            name = app.processName;
            //these process will restart when killed
            if (((name.toString().indexOf("com.mediatek.bluetooth")) != -1) ||
                ((name.toString().indexOf("android.process.acore")) != -1)) {
                Slog.v(TAG, "got target error process");
                mTargetProcess = true;
            } else {
                mTargetProcess = false;
            }

            if (crashByMustHavePermission != CRASH_BY_PERMISSION_NONE) {
                /// M:Permission exception dialog
                if (crashByMustHavePermission == CRASH_BY_PERMISSION_DETAIL) {
                    message = res.getString(
                            com.mediatek.internal.R.string.aerr_application_permission,
                            name.toString(), permissionTitled);
                } else {
                    message = res.getString(
                            com.mediatek.internal.R.string.aerr_application_unknown_permission,
                            name.toString());
                }
            } else if (criticalLow == true) {
                message = res.getString(
                        com.mediatek.internal.R.string.aerr_process_lowstorage,
                        name.toString());
            } else {
                message = res.getString(
                        com.android.internal.R.string.aerr_process,
                        name.toString());
            }
            setMessage(message);
        }
        ///@}
        setCancelable(false);

        /// M:Permission exception dialog, @{
        if (crashByMustHavePermission != CRASH_BY_PERMISSION_NONE) {
            setButton(DialogInterface.BUTTON_POSITIVE,
                    res.getText(com.android.internal.R.string.force_close),
                    mHandler.obtainMessage(PERMISSION_SETTINGS, app.info.packageName));
        } else {
            setButton(DialogInterface.BUTTON_POSITIVE,
                    res.getText(com.android.internal.R.string.force_close),
                    mHandler.obtainMessage(FORCE_QUIT));
        }
        /// @}

        if (app.errorReportReceiver != null) {
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    res.getText(com.android.internal.R.string.report),
                    mHandler.obtainMessage(FORCE_QUIT_AND_REPORT));
        }
        ///M:For Low storage,add button in error dialog @{
        if (crashByMustHavePermission != CRASH_BY_PERMISSION_NONE) {
            ///M:Permission exception dialog
            setButton(DialogInterface.BUTTON_NEUTRAL,
                    res.getText(com.android.internal.R.string.cancel),
                    mHandler.obtainMessage(FORCE_QUIT));
        } else if (criticalLow == true) {
            setButton(DialogInterface.BUTTON_NEUTRAL,
                       res.getText(com.mediatek.R.string.free_memory_btn),
                       mHandler.obtainMessage(FREE_SPACE));
        }

        ///@}
        setTitle(res.getText(com.android.internal.R.string.aerr_title));
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + app.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (app.persistent) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }


        if ((criticalLow == true) && (mTargetProcess)) {
           Slog.v(TAG, "do not show the error dialog!");
           mHandler.sendMessageDelayed(
                mHandler.obtainMessage(FORCE_QUIT),
                0);
        } else {
        // After the timeout, pretend the user clicked the quit button
            mHandler.sendMessageDelayed(
                mHandler.obtainMessage(FORCE_QUIT),
                DISMISS_TIMEOUT);
        }

        /// M: ALPS00944212 prevent binder wait forever until 5mins timeout @{
        this.setOnDismissListener(mDismissListener);
        /// @}
    }
    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == FREE_SPACE) {
                Intent mIntent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                mContext.startActivity(mIntent);
            } else if (msg.what == PERMISSION_SETTINGS) {
                ///M:Permission exception dialog
                Intent mIntent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
                mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, (String) msg.obj);
                mContext.startActivity(mIntent);
            }
            /// M: ALPS00944212 prevent binder wait forever until 5mins timeout @{
            //synchronized (mService) {
            //    if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
            //        mProc.crashDialog = null;
            //    }
            //}
            //mResult.set(msg.what);
            mResultType = msg.what;
            /// @}
            // Make sure we don't have time timeout still hanging around.
            removeMessages(FORCE_QUIT);

            // If this is a timeout we won't be automatically closed, so go
            // ahead and explicitly dismiss ourselves just in case.
            dismiss();
        }
    };

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            mResult.set(FORCE_QUIT);
        }
        super.dismiss();
    }

    /// M: ALPS00944212 prevent binder wait forever until 5mins timeout @{
    private final DialogInterface.OnDismissListener mDismissListener = new DialogInterface.OnDismissListener() {
        public void onDismiss(DialogInterface dialog) {
            synchronized (mService) {
                if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
                    mProc.crashDialog = null;
                }
            }
            mResult.set(mResultType);
        }
    };
    /// @}

    /// M:Permission exception dialog, @{
    private String getPermissionTitle(PackageManager pm, String permission) {
        PackageItemInfo info = null;
        if (DEBUG_PERMISSION) {
            Slog.v(TAG, "getPermissionTitle, permission = " + permission);
        }
        try {
            PermissionInfo permissionInfo = pm.getPermissionInfo(permission, 0);
            if (permissionInfo != null) {
                int level = permissionInfo.protectionLevel;
                if ((level & PermissionInfo.PROTECTION_DANGEROUS) != 0) {
                    String group = permissionInfo.group;
                    info = pm.getPermissionGroupInfo(group, 0);
                } else {
                    if (DEBUG_PERMISSION) {
                        Slog.w(TAG, permission + " is not a runtime permission");
                    }
                }
            } else {
                if (DEBUG_PERMISSION) {
                    Slog.w(TAG, "permissionInfo is null, permission = " + permission);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG_PERMISSION) {
                Slog.w(TAG, "Can't find permission: " + permission);
            }
            return null;
        }
        if (info == null || info.loadLabel(pm) == null) {
            if (DEBUG_PERMISSION) {
                Slog.w(TAG, "Can't find info: " + info);
            }
            return null;
        }
        return info.loadLabel(pm).toString();
    }
    /// @}
}
