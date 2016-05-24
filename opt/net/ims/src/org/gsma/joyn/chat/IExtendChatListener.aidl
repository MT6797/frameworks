package org.gsma.joyn.chat;

import org.gsma.joyn.chat.ExtendMessage;

/**
 * Chat event listener
 */
interface IExtendChatListener {
    void onNewMessage(in ExtendMessage message);

    void onReportMessageDelivered(in String msgId, in String contact);

    void onReportMessageDisplayed(in String msgId, in String contact);

    void onReportMessageFailed(in String msgId, in int errCode, in String statusCode);

    void onReportMessageInviteError(in String msgId, in String warningText, in boolean isForbidden);

    void onReportMessageSent(in String msgId);
}