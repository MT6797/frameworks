package com.android.server.am;

import android.util.Log;

import java.util.ArrayList;

/**
 * Use to dump activityStack when ActivityStack has changed.
 */
public class ActivityStackListener {
    private static final String TAG = "ActivityStackListener";
    final ArrayList mStacks = new ArrayList(15);
    private volatile boolean mRun = true;

    /**
     * The thread is used to dump stack.
     */
    public class DumpHistoryThread implements Runnable {
        public DumpHistoryThread() {}
        @Override
        public void run() {
            while (mRun) {
                dumpHistory();
            }
        }
    }

    /**
     * The constructor of ActivityStackListener.
     */
    public ActivityStackListener() {
        new Thread(new DumpHistoryThread()).start();
    }

    /**
     * Stop to dump stack.
     */
    public void closeStackListener() {
        mRun = false;
    }

    /**
     * The detail method to dump stack.
     */
    public synchronized void dumpHistory() {
        while (mStacks.size() == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "Dump History Start:");
        ActivityStack mActivityStack = (ActivityStack) mStacks.get(0);
        ArrayList mTaskHistory = mActivityStack.getTaskHistory();
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            TaskRecord task = (TaskRecord) mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int i = activities.size() - 1; i >= 0; --i) {
                ActivityRecord ar = activities.get(i);
                Log.v(TAG, "DisplayId(Display): " + mActivityStack.mDisplayId);
                Log.v(TAG, "StackId(Stack): " + mActivityStack.mStackId);
                Log.v(TAG, "TaskId(Task): " + task.taskId);
                Log.v(TAG, "RealActivity(Task): " + task.realActivity);
                Log.v(TAG, "Intent(Task): " + task.intent);
                Log.v(TAG, "NumActivities(Task): " + task.mActivities.size());
                Log.v(TAG, "LaunchedFromUid: " + ar.launchedFromUid);
                Log.v(TAG, "LaunchedFromPackage: " + ar.launchedFromPackage);
                Log.v(TAG, "Intent: " + ar.intent);
                Log.v(TAG, "LaunchMode: " + ar.launchMode);
                Log.v(TAG, "TaskAffinity: " + ar.taskAffinity);
                Log.v(TAG, "State: " + ar.state);
                Log.v(TAG, "Finishing: " + ar.finishing);
                Log.v(TAG, "StringName: " + ar.stringName);
                Log.v(TAG, "MActivityType: " + ar.mActivityType);
                Log.v(TAG, "FrontOfTask: " + ar.frontOfTask);
            }
        }
        Log.v(TAG, "Dump History End.");
        mStacks.remove(0);
    }

    /**
     * The api is used to trigger dump stack.
     *
     * @param mStack the focus stack.
     */
    public synchronized void dumpStack(ActivityStack mStack) {
        if (mStack.getTaskHistory().size() == 0) {
            return;
        }
        mStacks.add(mStack);
        if (mStacks.size() == 1) {
            notifyAll();
        }
    }
}
