package com.android.server.am;

import java.util.HashSet;

public interface IActivityStateNotifier {

    public enum ActivityState {
        Paused,
        Resumed,
        Destroyed,
        Stopped
    }

    /**
     * Notify activity state change.
     *
     * @param packageName The target package name.
     * @param pid The process id package belongs to.
     * @param className The class name of the package.
     * @param actState Current lifecycle state of the package.
     */
    public void notifyActivityState(String packageName, int pid,
            String className, ActivityState actState);

    /**
     * Notify the process of activity has died.
     *
     * @param pid The process id has died.
     * @param packageList The whole packages runs on the process.
     */
    public void notifyAppDied(int pid, HashSet<String> packageList);
}
