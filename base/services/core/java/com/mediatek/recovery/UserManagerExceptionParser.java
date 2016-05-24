package com.mediatek.recovery;

import java.util.ArrayList;
import android.util.Slog;

public class UserManagerExceptionParser extends AbstractExceptionParser {
    private static final String TAG = "UserManagerExceptionParser";
    @Override
    public ArrayList<String> parseException(RuntimeException e) {
        // Get the surface exception cause
        AbstractExceptionParser.ParsedException pe = AbstractExceptionParser.ParsedException
                .getNewInstance(e, false);
        // Get the root cause
        AbstractExceptionParser.ParsedException rpe = AbstractExceptionParser.ParsedException
                .getNewInstance(e, true);
        ArrayList<String> retList = new ArrayList<String>();
        setLastError(PARSER_EXCEPTION_MISMATCH);
        if (pe == null || rpe == null) {
            Slog.w(TAG, "The exception backtrace is null, stop handle progress");
            return retList;
        }
        if (rpe.mThrowMethodName.equals("getRequiredInstallerLPr")
                && rpe.mExceptionClassName.equals("java.lang.RuntimeException")
                && pe.mThrowClassName.equals("com.android.server.pm.PackageManagerService")) {
            retList.add("/data/system/users/0.xml");
            setLastError(PARSER_EXCEPTION_MATCH);
        }
        return retList;
    }
}
