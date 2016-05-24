package org.gsma.joyn.chat;

import org.gsma.joyn.chat.IExtendChatListener;

/**
 * Chat interface
 */
interface IExtendChat {
    List<String> getRemoteContacts();
    
    String sendMessage(in String message, in int msgType);
    
    void sendDisplayedDeliveryReport(in String msgId);

    void sendBurnedDeliveryReport(in String msgId);

    String prosecuteMessage(in String msgId);

    void addEventListener(in IExtendChatListener listener);
    
    void removeEventListener(in IExtendChatListener listener);

    int resendMessage(in String msgId);
}
