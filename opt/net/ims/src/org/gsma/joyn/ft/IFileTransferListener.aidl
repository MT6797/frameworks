package org.gsma.joyn.ft;

/**
 * Callback methods for file transfer events
 */
interface IFileTransferListener {
	void onTransferStarted();
	
	void onTransferAborted();

	void onTransferPaused();
	
	void onTransferResumed(in String oldFileTransferId, in String newFileTransferId);

	void onTransferError(in int error);
	
	void onTransferProgress(in long currentSize, in long totalSize);

	void onFileTransferred(in String filename);
}