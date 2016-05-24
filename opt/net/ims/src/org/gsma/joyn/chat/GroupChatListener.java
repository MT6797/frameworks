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

import java.util.List;
import org.gsma.joyn.chat.ConferenceEventData.ConferenceUser;


/**
 * Group chat event listener

 * @author Jean-Marc AUFFRET
 */
public abstract class GroupChatListener extends IGroupChatListener.Stub {
    /**
     * Callback called when the session is well established and messages
     * may be exchanged with the group of participants
     */
    public abstract void onSessionStarted();

    /**
     * Callback called when the session has been aborted or terminated
     */
    public abstract void onSessionAborted();

    /**
     * Callback called when the session has failed
     *
     * @param error Error
     * @see GroupChat.Error
     */
    public abstract void onSessionError(int error);

    /**
     * Callback called when a new message has been received
     *
     * @param message New chat message
     * @see ChatMessage
     */
    public abstract void onNewMessage(ChatMessage message);


    public void onNewExtendMessage(ExtendMessage message) {
    }

    /**
     * Callback called when a new geoloc has been received
     *
     * @param message Geoloc message
     * @see GeolocMessage
     */
    public abstract void onNewGeoloc(GeolocMessage message);

    /**
     * Callback called when a message has been delivered to the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageDelivered(String msgId);

    /**
     * Callback called when a message has been displayed by the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageDisplayed(String msgId);

    /**
     * Callback called when a message has failed to be delivered to the remote
     *
     * @param msgId Message ID
     */
    public abstract void onReportMessageFailed(String msgId);

    /**
     * Callback called when an Is-composing event has been received. If the
     * remote is typing a message the status is set to true, else it is false.
     *
     * @param contact Contact
     * @param status Is-composing status
     */
    public abstract void onComposingEvent(String contact, boolean status);

    /**
     * Callback called when a new participant has joined the group chat
     *
     * @param contact Contact
     * @param contactDisplayname Remote Contact display name
     */
    public abstract void onParticipantJoined(String contact, String contactDisplayname);

    /**
     * Callback called when a participant has left voluntary the group chat
     *
     * @param contact Contact
     */
    public abstract void onParticipantLeft(String contact);

    /**
     * Callback called when a participant is disconnected from the group chat
     *
     * @param contact Contact
     */
    public abstract void onParticipantDisconnected(String contact);

    /**
     * Callback called when the session has been aborted by chairman
     */
    public void onSessionAbortedbyChairman() {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a message has been delivered to the remote
     *
     * @param msgId Message ID
     * @param contact Remote contact
     */
    public void onReportMessageDeliveredContact(String msgId,String contact) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a message has been displayed by the remote
     *
     * @param msgId Message ID
     * @param contact Remote contact
     */
    public void onReportMessageDisplayedContact(String msgId,String contact) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a message has failed to be delivered to the remote
     *
     * @param msgId Message ID
     * @param contact Remote contact
     */
    public void onReportMessageFailedContact(String msgId,String contact) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a message has failed to be delivered to the remote
     *
     * @param msgId Message ID
     * @param errtype Error cause for failure
     * @param statusCode Status code associated with failure
     */
    public void onReportFailedMessage(String msgId, int errtype, String statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a message has been sent to remote
     *
     * @param msgId Message ID
     */
    public void onReportSentMessage(String msgId) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when a group chat is dissolved
     *
     */
    public void onGroupChatDissolved() {
        // default implementation for TAPI extension
    }

    /**
     * Callback called to inform the result of invite participants
     *
     * @param errtype Error cause for failure
     * @param statusCode Status code associated with failure
     */
    public void onInviteParticipantsResult(int errType, String statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when new chairman is successfully changed by current chairman
     * (Callback only received by chairman)
     * @param errType errorType
     */
    public void onSetChairmanResult(int errType, int statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when chairman is changed by current chairman
     * (Callback received by every user of group)
     * @param newChairman new chairman
     */
    public void onChairmanChanged(String newChairman) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when subject is modified
     * (Callback only received by chairman)
     * @param errType errorType
     */
    public void onModifySubjectResult(int errType, int statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when subject is changed
     * (Callback received by every user of group)
     * @param newSubject new subject
     */
    public void onSubjectChanged(String newSubject) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when nickname is modified
     * (callback received only by user who modified the nickname)
     * @param errType errorType
     */
    public void onModifyNickNameResult(int errType, int statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when nickname is changed
     * (callback received by every group member)
     * @param contact contact who modified nick name
     */
    public void onNickNameChanged(String contact, String newNickName) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when participants are removed
     * (Callback only received by chairman)
     * @param errType errorType
     * @param statusCode status Code
     * @param participant participant removed
     */
    public void onRemoveParticipantResult(int errType, int statusCode, String participant) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called participant is kicked out(removed) by chairman
     * (Callback received by removed participant)
     * @param from who kicked out
     */
    public void onReportMeKickedOut(String from) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called participant is kicked out(removed) by chairman
     * (Callback received by other than removed participants)
     * @param contact kicked out participant
     */
    public void onReportParticipantKickedOut(String contact) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called chairman has successfully aborted the group
     * (Callback only received by chairman)
     * @param errType errorType
     */
    public void onAbortConversationResult(int errType, int statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called user has left the group successfully
     * (Callback received by user who left the group)
     * @param errType errorType
     */
    public void onQuitConversationResult(int errType, int statusCode) {
        // default implementation for TAPI extension
    }

    /**
     * Callback called when SIP notify is received in group conference
     * (Callback received for add participant, remove participant, User left, nick name change)
     * @param confState conference state
     */
    public void onConferenceNotify(String confState, List<ConferenceUser> users) {
        // default implementation for TAPI extension
    }
}
