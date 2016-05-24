package com.mediatek.recovery;

import java.util.ArrayList;
import android.util.Slog;

public class SettingsProviderExceptionParser extends AbstractExceptionParser {
    private static final String TAG = "SettingsProviderExceptionParser";
    @Override
    public ArrayList<String> parseException(RuntimeException e) {
        // Get the surface exception cause
        Slog.w(TAG, "start parseException e="+e);
        AbstractExceptionParser.ParsedException pe = AbstractExceptionParser.ParsedException
                .getNewInstance(e, false);
        // Get the root cause
        AbstractExceptionParser.ParsedException rpe = AbstractExceptionParser.ParsedException
                .getNewInstance(e, true);
        ArrayList<String> retList = new ArrayList<String>();
        Slog.w(TAG, "parseException setLastError(PARSER_EXCEPTION_MISMATCH)");
        setLastError(PARSER_EXCEPTION_MISMATCH);
        if (pe == null || rpe == null) {
            Slog.w(TAG, "The exception backtrace is null, stop handle progress");
            return retList;
        }
        Slog.w(TAG, "rpe.mThrowMethodName= " + rpe.mThrowMethodName);
        Slog.w(TAG, "rpe.mExceptionClassName= " + rpe.mExceptionClassName);
        Slog.w(TAG, "pe.mThrowClassName= " + pe.mThrowClassName);
        if ("next".equals(rpe.mThrowMethodName)
                && "org.xmlpull.v1.XmlPullParserException".equals(rpe.mExceptionClassName)
                && "com.android.providers.settings.SettingsState".equals(pe.mThrowClassName)) {
            retList.add("/data/system/users/0/settings_global.xml");
            Slog.w(TAG, "add /data/system/users/0/settings_global.xml");
            setLastError(PARSER_EXCEPTION_MATCH);
        }
        Slog.w(TAG, "parseException end");
        return retList;
    }
}