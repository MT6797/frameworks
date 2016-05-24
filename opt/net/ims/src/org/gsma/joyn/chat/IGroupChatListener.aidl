package org.gsma.joyn.chat;

import java.util.List;

import org.gsma.joyn.chat.ChatMessage;
import org.gsma.joyn.chat.ExtendMessage;
import org.gsma.joyn.chat.GeolocMessage;
import org.gsma.joyn.chat.ConferenceEventData.ConferenceUser;

/**
 * Group chat event listener
 */
interface IGroupChatListener {
	void onSessionStarted();
	
	void onSessionAborted();

	void onSessionAbortedbyChairman();

	void onSessionError(in int reason);
		
	void onNewMessage(in ChatMessage message);

    void onNewExtendMessage(in ExtendMessage message);

	void onNewGeoloc(in GeolocMessage message);

	void onReportMessageDeliveredContact(in String msgId,in String contact);

	void onReportMessageDisplayedContact(in String msgId,in String contact);

	void onReportMessageFailedContact(in String msgId,in String contact);
	
	void onReportMessageDelivered(in String msgId);

	void onReportMessageDisplayed(in String msgId);

	void onReportMessageFailed(in String msgId);
	
	void onReportFailedMessage(in String msgId,in int errtype,in String statusCode);
		
	void onReportSentMessage(in String msgId);

	void onGroupChatDissolved();
	
	void onInviteParticipantsResult(in int errType,in String statusCode);
	
	void onComposingEvent(in String contact, in boolean status);
	
	void onParticipantJoined(in String contact, in String contactDisplayname);
	
	void onParticipantLeft(in String contact);

	void onParticipantDisconnected(in String contact);

    void onSetChairmanResult(in int errType, in int statusCode);
	
	void onChairmanChanged(in String newChairman);
	
	void onModifySubjectResult(in int errType, in int statusCode);
	
	void onSubjectChanged(in String newSubject);
	
	void onModifyNickNameResult(in int errType, in int statusCode);
    
    void onNickNameChanged(in String contact, in String newNickName);
	
	void onRemoveParticipantResult(in int errType, in int statusCode, in String participant);
	
	void onReportMeKickedOut(in String from);
	
	void onReportParticipantKickedOut(in String contact);
	
	void onAbortConversationResult(in int errType, in int statusCode);
	
	void onQuitConversationResult(in int errType, in int statusCode);
	
	void onConferenceNotify(in String confState, in List<ConferenceUser> users);
}
