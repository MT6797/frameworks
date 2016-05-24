package org.gsma.joyn.chat;

import org.gsma.joyn.IJoynServiceRegistrationListener;
import org.gsma.joyn.chat.IChatListener;
import org.gsma.joyn.chat.IChat;
import org.gsma.joyn.chat.IGroupChatListener;
import org.gsma.joyn.chat.IGroupChat;
import org.gsma.joyn.chat.IExtendChat;
import org.gsma.joyn.chat.IExtendChatListener;
import org.gsma.joyn.chat.INewChatListener;
import org.gsma.joyn.chat.IGroupChatSyncingListener;
import org.gsma.joyn.chat.ChatServiceConfiguration;

/**
 * Chat service API
 */
interface IChatService {
	boolean isServiceRegistered();
    
	void addServiceRegistrationListener(IJoynServiceRegistrationListener listener);

	void removeServiceRegistrationListener(IJoynServiceRegistrationListener listener); 

	ChatServiceConfiguration getConfiguration();
    
	IChat openSingleChat(in String contact, in IChatListener listener);
	
	IExtendChat openSingleChatEx(in String contact, in IExtendChatListener listener);

    IExtendChat openMultipleChat(in List<String> participants, in IExtendChatListener listener);

	IGroupChat initiateGroupChat(in List<String> contacts, in String subject, in IGroupChatListener listener);
    
        IGroupChat initiateClosedGroupChat(in List<String> contacts, in String subject, in IGroupChatListener listener);

	IGroupChat rejoinGroupChat(in String chatId);
    
	IGroupChat rejoinGroupChatId(in String chatId, in String rejoinId);
    
	IGroupChat restartGroupChat(in String chatId);

    void syncAllGroupChats(in IGroupChatSyncingListener listener);
    void syncGroupChat(in String chatId, in IGroupChatSyncingListener listener);
    
	void addEventListener(in INewChatListener listener);
    
	void removeEventListener(in INewChatListener listener);
    
	IChat getChat(in String chatId);

    IExtendChat getExtendChat(in String chatId);

	List<IBinder> getChats();

	List<IBinder> getGroupChats();
    
	IGroupChat getGroupChat(in String chatId);
	
	void blockGroupMessages(in String chatId, in boolean flag);
	
	int getServiceVersion();
	
	boolean isImCapAlwaysOn();
}