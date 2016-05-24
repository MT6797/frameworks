package com.mediatek.systemui.ext;

import com.mediatek.systemui.statusbar.extcb.IconIdWrapper;


/**
 * M: the interface for Plug-in definition of QuickSettings.
 */
public interface IQuickSettingsPlugin {

    /**
     * Customize create the data usage tile.
     * @param isDisplay The default value.
     * @return if true create data usage; If false not create.
     * @internal
     */
    boolean customizeDisplayDataUsage(boolean isDisplay);

    /**
     * Customize the quick settings tile order.
     * @param defaultString the default String;
     * @return the tiles strings splited by comma.
     * @internal
     */
    String customizeQuickSettingsTileOrder(String defaultString);

    /**
     * Customize additional quick settings tile.
     * @param qsTile The default QSTile.
     * @return customize QSTile instance
     * @internal
     */
    Object customizeAddQSTile(Object qsTile);

    /**
     * Customize the data connection tile view.
     * @param dataState The data state.
     * @param icon The icon wrapper.
     * @param orgLabelStr The dual data connection tile label.
     * @return the tile label.
     * @internal
     */
    String customizeDataConnectionTile(int dataState, IconIdWrapper icon, String orgLabelStr);

    /**
     * Customize the dual Sim Settings.
     * @param enable true is enable.
     * @param icon The icon wrapper.
     * @param labelStr The dual sim quick settings icon label
     * @return the tile label.
     * @internal
     */
    String customizeDualSimSettingsTile(boolean enable, IconIdWrapper icon, String labelStr);

    /**
     * Customize the sim data connection tile.
     * @param state The sim data state.
     * @param icon The icon wrapper.
     * @internal
     */
    void customizeSimDataConnectionTile(int state, IconIdWrapper icon);

    /**
     * Customize the apn settings tile.
     *
     * @param enable true is enable.
     * @param icon The icon wrapper.
     * @param orgLabelStr The apn settings tile label.
     * @return the tile label.
     * @internal
     */
    String customizeApnSettingsTile(boolean enable, IconIdWrapper icon, String orgLabelStr);
}
