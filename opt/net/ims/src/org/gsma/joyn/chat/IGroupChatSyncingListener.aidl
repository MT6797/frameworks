package org.gsma.joyn.chat;

import org.gsma.joyn.chat.ConferenceEventData;

/**
 * Conference info listener
 */
interface IGroupChatSyncingListener {
    void onSyncStart(in int count);
    void onSyncInfo(in String chatId, in ConferenceEventData info);
    void onSyncDone(in int result);
}
