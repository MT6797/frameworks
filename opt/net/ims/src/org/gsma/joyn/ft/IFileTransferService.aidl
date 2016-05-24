package org.gsma.joyn.ft;

import org.gsma.joyn.IJoynServiceRegistrationListener;
import org.gsma.joyn.ft.IFileTransfer;
import org.gsma.joyn.ft.IFileTransferListener;
import org.gsma.joyn.ft.INewFileTransferListener;
import org.gsma.joyn.ft.FileTransferServiceConfiguration;

/**
 * File transfer service API
 */
interface IFileTransferService {
	boolean isServiceRegistered();

	void addServiceRegistrationListener(IJoynServiceRegistrationListener listener);

	void removeServiceRegistrationListener(IJoynServiceRegistrationListener listener); 

	FileTransferServiceConfiguration getConfiguration();

	List<IBinder> getFileTransfers();
	
	IFileTransfer getFileTransfer(in String transferId);

	IFileTransfer transferFile(in String contact, in String filename, in String fileicon, in IFileTransferListener listener);

	IFileTransfer transferFileEx(in String contact, in String filename, in String fileicon, in int duration, in String type, in IFileTransferListener listener);

	IFileTransfer transferFileToMultiple(in List<String> contacts, in String filename, in String fileicon, in int duration, in String type, in IFileTransferListener listener);

    IFileTransfer transferFileToGroup(in String chatId, in List<String> contacts, in String filename, in String fileicon, in int duration, in IFileTransferListener listener);

    IFileTransfer transferFileToGroupEx(in String chatId, in String filename, in String fileicon, in int duration, in String type, in IFileTransferListener listener);

	IFileTransfer resumeFileTransfer(in String fileTranferId, in IFileTransferListener listener);
	
	IFileTransfer prosecuteFile(in String contact, in String transferId, in IFileTransferListener listener);

	void addNewFileTransferListener(in INewFileTransferListener listener);

	void removeNewFileTransferListener(in INewFileTransferListener listener);

	int getServiceVersion();

    int getMaxFileTransfers();
  
}