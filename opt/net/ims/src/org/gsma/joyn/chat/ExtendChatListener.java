/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.gsma.joyn.chat;


/**
 * Chat event listener
 *
 * @author Jean-Marc AUFFRET
 */
public abstract class ExtendChatListener extends IExtendChatListener.Stub {
    /**
     * Callback called when a new message has been received
     *
     * @param message Chat message
     * @see ChatMessage
     */
    public abstract void onNewMessage(ExtendMessage message);

    /**
     * Callback called when a message has been delivered to the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageDelivered(String msgId, String contact);

    /**
     * Callback called when a message has been displayed by the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageDisplayed(String msgId, String contact);

    /**
     * Callback called when a message has failed to be delivered to the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageFailed(String msgId, int errtype, String statusCode);

    /**
     * Callback called when a Invite request has failed
     *
     * @param msgId
     *            Message ID
     */
    public void onReportMessageInviteError(String msgId,
            String warningText, boolean isForbidden){

    }

    /**
     * Callback called when a message has been sent to the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageSent(String msgId);
}
