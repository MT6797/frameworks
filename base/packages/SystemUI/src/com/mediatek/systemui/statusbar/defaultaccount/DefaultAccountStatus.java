package com.mediatek.systemui.statusbar.defaultaccount;

import android.app.StatusBarManager;
import android.telephony.SubscriptionManager;

import com.android.systemui.R;

/**
 * A class to support [SIM Indicator].
 * Wrap subId with under signal icon and special account icon.
 */
public class DefaultAccountStatus {
    private int mSubId;
    private int mDefSignalBackgroundIconId;
    private int mAccountStatusIconId;

    /**
     * Constructor of DefaultAccountStatus.
     * @param subId Account's subId.
     */
    public DefaultAccountStatus(int subId) {
        mSubId = subId;
        mAccountStatusIconId = getIconId(subId);
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            mDefSignalBackgroundIconId = R.drawable.stat_sys_default_sim_indicator;
        }
    }

    public int getSubId() {
        return mSubId;
    }

    public int getAccountStatusIconId() {
        return mAccountStatusIconId;
    }

    private int getIconId(int subId) {
        int iconId = 0;
        switch (subId) {
            case StatusBarManager.STATUS_ALWAYS_ASK:
                iconId = R.drawable.sim_indicator_always_ask;
            break;
            case StatusBarManager.STATUS_SIP:
                iconId = R.drawable.sim_indicator_internet_call;
            break;
            case StatusBarManager.STATUS_SMS_AUTO:
                iconId = R.drawable.sim_indicator_auto;
            break;
            case StatusBarManager.STATUS_OTHER_ACCOUNTS:
                iconId = R.drawable.sim_indicator_others;
            break;
            default:
                iconId = 0;
            break;
        }
        return iconId;
    }

    public int getDefSignalBackgroundIconId() {
        return mDefSignalBackgroundIconId;
    }
}
