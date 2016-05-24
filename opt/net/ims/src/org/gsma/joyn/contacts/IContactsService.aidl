package org.gsma.joyn.contacts;

import org.gsma.joyn.contacts.JoynContact;

/**
 * Contacts service API
 */
interface IContactsService {
	JoynContact getJoynContact(String contactId);

	List<JoynContact> getJoynContacts();

	List<JoynContact> getJoynContactsOnline();

	List<JoynContact> getJoynContactsSupporting(in String tag);
	
	int getServiceVersion();
	
	List<String> getImBlockedContactsFromLocal();
	
	boolean isImBlockedForContact(String contact);
	
	List<String> getImBlockedContacts();
	
	String getTimeStampForBlockedContact(in String contact);
	
	void setImBlockedForContact(in String contact, in boolean flag);
	
	void setFtBlockedForContact(in String contact, in boolean flag);
	
	boolean isRcsValidNumber(in String number);
	
	int getRegistrationState(in String contact);
	
	void loadImBlockedContactsToLocal();
	
}
