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

import java.util.Date;

import org.gsma.joyn.Logger;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Geoloc push message
 *
 * @author Jean-Marc AUFFRET
 */
public class ExtendMessage extends ChatMessage implements Parcelable {
    /**
     * MIME type
     */
    public static final String TAG = "TAPI-ExtendChatMessage";

    private int msgType;

    /**
     * Constructor for outgoing message
     *
     * Constructor for outgoing message
     *
     * @param messageId Message Id
     * @param contact Contact
     * @param geoloc Geolocation info
     * @param receiptAt Receipt date
     * @param displayedReportRequested Flag indicating if a displayed report is requested
     * @hide
     */
    public ExtendMessage(
            String messageId, String remote, String message,
            Date receiptAt, boolean imdnDisplayedRequested, String displayName, int msgType) {
        super(messageId, remote, message, receiptAt, imdnDisplayedRequested, displayName);
        this.msgType = msgType;
        Logger.i(TAG, "ExtendChatMessage entry");
    }

    /**
     * Constructor
     *
     * @param source Parcelable source
     * @hide
     */
    public ExtendMessage(Parcel source) {
        super(source);

        this.msgType = source.readInt();
    }

    /**
     * Describe the kinds of special objects contained in this Parcelable's
     * marshalled representation
     *
     * @return Integer
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Write parcelable object
     *
     * @param dest The Parcel in which the object should be written
     * @param flags Additional flags about how the object should be written
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);

        dest.writeInt(msgType);
    }

    /**
     * Parcelable creator
     *
     * @hide
     */
    public static final Parcelable.Creator<ExtendMessage> CREATOR
            = new Parcelable.Creator<ExtendMessage>() {
        public ExtendMessage createFromParcel(Parcel source) {
            return new ExtendMessage(source);
        }

        public ExtendMessage[] newArray(int size) {
            return new ExtendMessage[size];
        }
    };

    /**
     * Get message type
     *
     * @return message type
     */
    public int getMessageType() {
        return msgType;
    }
}
