package org.gsma.joyn.chat;

import java.util.List;

import org.gsma.joyn.chat.IGroupChatListener;
import org.gsma.joyn.chat.Geoloc;
import org.gsma.joyn.ft.IFileTransfer;
import org.gsma.joyn.ft.IFileTransferListener;


/**
 * Group chat interface
 */
interface IGroupChat {
	String getChatId();
	
	String getChatSessionId();

	int getDirection();
	
	int getState();	

	String getSubject();	

	List<String> getParticipants();
	
	List<String> getAllParticipants();
	
	void acceptInvitation();
	
	void rejectInvitation();
	
	String sendMessage(in String text);
	
	String sendMessageEx(in String text, in int msgType);

	void sendIsComposingEvent(in boolean status);
	
	void sendDisplayedDeliveryReport(in String msgId);

	void addParticipants(in List<String> participants);
	
	int getMaxParticipants();
	
	int getMessageState(in String messageId);
	
	int resendMessage(in String msgId);
	
	void quitConversation();
	
	void addEventListener(in IGroupChatListener listener);
	
	void removeEventListener(in IGroupChatListener listener);
	
	String getRemoteContact();

	String sendGeoloc(in Geoloc geoloc);

	IFileTransfer sendFile(in String filename, in String fileicon, in IFileTransferListener listener);
	
	void transferChairman(in String newChairman);
	
	void modifySubject(in String newSubject);
	
	void modifyMyNickName(in String newNickname);
	
	void removeParticipants(in List<String> participants);
	
	void abortConversation();
	
	boolean isMeChairman();
}