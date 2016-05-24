package org.gsma.joyn.chat;

import org.gsma.joyn.chat.ChatMessage;
import org.gsma.joyn.chat.GeolocMessage;

/**
 * Chat event listener
 */
interface IChatListener {
	void onNewMessage(in ChatMessage message);

	void onNewGeoloc(in GeolocMessage message);

	void onReportMessageDelivered(in String msgId);

	void onReportMessageDisplayed(in String msgId);

	void onReportMessageFailed(in String msgId);
	
	void onReportFailedMessage(in String msgId, in int errorCode, in String statusCode);

	void onReportMessageInviteFailed(in String msgId);
	
	void onReportMessageInviteForbidden(in String msgId, in String text);

	void onReportMessageSent(in String msgId);

	void onComposingEvent(in boolean status);
}