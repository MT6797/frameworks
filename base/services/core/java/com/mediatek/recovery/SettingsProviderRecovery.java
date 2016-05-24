package com.mediatek.recovery;

import java.io.File;
import android.util.Slog;

public class SettingsProviderRecovery extends AbstractRecoveryMethod {
    private static final String TAG = "SettingsProviderRecovery";

    @Override
    public int doRecover(Object param) {
        Slog.d(TAG, "doRecover called, path=" + param);
        return RECOVER_METHOD_SUCCESS;
    }
}