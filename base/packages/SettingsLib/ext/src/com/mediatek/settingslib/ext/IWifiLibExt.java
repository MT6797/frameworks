package com.mediatek.settingslib.ext;


/**
 *  Interface for customize operator request.
 */
public interface IWifiLibExt {
    /**
     * should check network capabilities.
     * @return default return true means need do the check.
     * @internal
     */
    boolean shouldCheckNetworkCapabilities();

    /**
     * append reason to access point summary.
     * @param summary current summary
     * @param autoJoinStatus Access point's auto join status
     * @param connectFail the disabled fail string
     * @param disabled the generic fail string
     * @internal
     */
    public void appendApSummary(StringBuilder summary, int autoJoinStatus,
        String connectFail, String disabled);
}
