/*
 * Copyright (C) 2007 The Android Open Source Project
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
 */

package com.android.internal.telephony.cat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.uicc.IccFileHandler;

import java.util.Iterator;
import java.util.List;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.USER_ACTIVITY_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.IDLE_SCREEN_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.LANGUAGE_SELECTION_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.BROWSER_TERMINATION_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.BROWSING_STATUS_EVENT;
/// M: BIP {
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.DATA_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.CHANNEL_STATUS_EVENT;
/// M: BIP }
/**
 * Factory class, used for decoding raw byte arrays, received from baseband,
 * into a CommandParams object.
 *
 */
class CommandParamsFactory extends Handler {
    private static CommandParamsFactory sInstance = null;
    private IconLoader mIconLoader;
    private CommandParams mCmdParams = null;
    private int mIconLoadState = LOAD_NO_ICON;
    private RilMessageDecoder mCaller = null;

    private Context mContext;
    // used to mark the index of tlv object in a tlv list
    int tlvIndex = -1;

    // constants
    static final int MSG_ID_LOAD_ICON_DONE = 1;

    // loading icons state parameters.
    static final int LOAD_NO_ICON           = 0;
    static final int LOAD_SINGLE_ICON       = 1;
    static final int LOAD_MULTI_ICONS       = 2;

    // Command Qualifier values for refresh command
    static final int REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE  = 0x00;
    static final int REFRESH_NAA_INIT_AND_FILE_CHANGE       = 0x02;
    static final int REFRESH_NAA_INIT                       = 0x03;
    static final int REFRESH_UICC_RESET                     = 0x04;

    // Command Qualifier values for PLI command
    static final int DTTZ_SETTING                           = 0x03;
    static final int LANGUAGE_SETTING                       = 0x04;
    static final int BATTERY_STATE                          = 0x0A;

    // As per TS 102.223 Annex C, Structure of CAT communications,
    // the APDU length can be max 255 bytes. This leaves only 239 bytes for user
    // input string. CMD details TLV + Device IDs TLV + Result TLV + Other
    // details of TextString TLV not including user input take 16 bytes.
    //
    // If UCS2 encoding is used, maximum 118 UCS2 chars can be encoded in 238 bytes.
    // Each UCS2 char takes 2 bytes. Byte Order Mask(BOM), 0xFEFF takes 2 bytes.
    //
    // If GSM 7 bit default(use 8 bits to represent a 7 bit char) format is used,
    // maximum 239 chars can be encoded in 239 bytes since each char takes 1 byte.
    //
    // No issues for GSM 7 bit packed format encoding.

    private static final int MAX_GSM7_DEFAULT_CHARS = 239;
    private static final int MAX_UCS2_CHARS = 118;

    static synchronized CommandParamsFactory getInstance(RilMessageDecoder caller,
            IccFileHandler fh) {
        if (sInstance != null) {
            return sInstance;
        }
        if (fh != null) {
            return new CommandParamsFactory(caller, fh);
        }
        return null;
    }

    static synchronized CommandParamsFactory getInstance(RilMessageDecoder caller,
            IccFileHandler fh, Context context) {
        if (sInstance != null) {
            return sInstance;
        }

        if (fh != null && context != null) {
            return new CommandParamsFactory(caller, fh, context);
        }

        return null;
    }

    private CommandParamsFactory(RilMessageDecoder caller, IccFileHandler fh, Context context) {
        mCaller = caller;
        mIconLoader = IconLoader.getInstance(this, fh, mCaller.getSlotId());
        mContext = context;
    }

    private CommandParamsFactory(RilMessageDecoder caller, IccFileHandler fh) {
        mCaller = caller;
        mIconLoader = IconLoader.getInstance(this, fh, mCaller.getSlotId());
    }

    private CommandDetails processCommandDetails(List<ComprehensionTlv> ctlvs)
        throws ResultException {

        CommandDetails cmdDet = null;

        if (ctlvs != null) {
            // Search for the Command Details object.
            ComprehensionTlv ctlvCmdDet = searchForTag(
                    ComprehensionTlvTag.COMMAND_DETAILS, ctlvs);
            if (ctlvCmdDet != null) {
                try {
                    cmdDet = ValueParser.retrieveCommandDetails(ctlvCmdDet);
                } catch (ResultException e) {
                    CatLog.d(this, "Failed to procees command details");
                    throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            }
        }
        return cmdDet;
    }

    void make(BerTlv berTlv) {
        if (berTlv == null) {
            return;
        }
        // reset global state parameters.
        mCmdParams = null;
        mIconLoadState = LOAD_NO_ICON;
        // only proactive command messages are processed.
        if (berTlv.getTag() != BerTlv.BER_PROACTIVE_COMMAND_TAG) {
            CatLog.e(this, "CPF-make: Ununderstood proactive command tag");
            sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
            return;
        }
        boolean cmdPending = false;
        List<ComprehensionTlv> ctlvs = berTlv.getComprehensionTlvs();
        // process command dtails from the tlv list.
        CommandDetails cmdDet = null;
        try {
            cmdDet = processCommandDetails(ctlvs);
        } catch (ResultException e) {
            CatLog.e(this, "CPF-make: Except to procees command details : " + e.result());
            sendCmdParams(e.result());
            return;
        }
        if (cmdDet == null) {
            CatLog.e(this, "CPF-make: No CommandDetails object");
            sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
            return;
        }

        // extract command type enumeration from the raw value stored inside
        // the Command Details object.
        AppInterface.CommandType cmdType = AppInterface.CommandType
                .fromInt(cmdDet.typeOfCommand);
        if (cmdType == null) {
            CatLog.d(this, "CPF-make: Command type can't be found");
            // Different from 2.3.5
            // This PROACTIVE COMMAND is presently not handled. Hence set
            // result code as BEYOND_TERMINAL_CAPABILITY in TR.
            mCmdParams = new CommandParams(cmdDet);
            // sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
            sendCmdParams(ResultCode.BEYOND_TERMINAL_CAPABILITY);
            return;
        }

        // proactive command length is incorrect.
        if (!berTlv.isLengthValid()) {
            mCmdParams = new CommandParams(cmdDet);
            sendCmdParams(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            return;
        }

        try {
            switch (cmdType) {
            case SET_UP_MENU:
                cmdPending = processSelectItem(cmdDet, ctlvs);
                break;
            case SELECT_ITEM:
                cmdPending = processSelectItem(cmdDet, ctlvs);
                break;
            case DISPLAY_TEXT:
                cmdPending = processDisplayText(cmdDet, ctlvs);
                break;
             case SET_UP_IDLE_MODE_TEXT:
                 cmdPending = processSetUpIdleModeText(cmdDet, ctlvs);
                 break;
             case GET_INKEY:
                cmdPending = processGetInkey(cmdDet, ctlvs);
                break;
             case GET_INPUT:
                 cmdPending = processGetInput(cmdDet, ctlvs);
                 break;
             case SEND_DTMF:
             case SEND_SMS:
             case SEND_SS:
             case SEND_USSD:
                 cmdPending = processEventNotify(cmdDet, ctlvs);
                 break;
             case GET_CHANNEL_STATUS:
             case SET_UP_CALL:
                 cmdPending = processSetupCall(cmdDet, ctlvs);
                 break;
             case REFRESH:
                processRefresh(cmdDet, ctlvs);
                cmdPending = false;
                break;
             case LAUNCH_BROWSER:
                 cmdPending = processLaunchBrowser(cmdDet, ctlvs);
                 break;
             case PLAY_TONE:
                cmdPending = processPlayTone(cmdDet, ctlvs);
                break;
             case PROVIDE_LOCAL_INFORMATION:
                cmdPending = processProvideLocalInfo(cmdDet, ctlvs);
                CatLog.d(this, "process ProvideLocalInformation");
                break;
            case SET_UP_EVENT_LIST:
                cmdPending = processSetUpEventList(cmdDet, ctlvs);
                break;
                /*
                 * case PROVIDE_LOCAL_INFORMATION: mCmdParams = new
                 * CommandParams(cmdDet); StkLog.d(this,
                 * "process ProvideLocalInformation"); break;
                 */
            case OPEN_CHANNEL:
            case CLOSE_CHANNEL:
            case RECEIVE_DATA:
            case SEND_DATA:
                cmdPending = processBIPClient(cmdDet, ctlvs);
                break;
            case ACTIVATE:
                cmdPending = processActivate(cmdDet, ctlvs);
                break;
            default:
                // unsupported proactive commands
                mCmdParams = new CommandParams(cmdDet);
                CatLog.d(this, "CPF-make: default case");
                // sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
                sendCmdParams(ResultCode.BEYOND_TERMINAL_CAPABILITY);
                return;
            }
        } catch (ResultException e) {
            CatLog.d(this, "make: caught ResultException e=" + e);
            mCmdParams = new CommandParams(cmdDet);
            sendCmdParams(e.result());
            return;
        }
        if (!cmdPending) {
            sendCmdParams(ResultCode.OK);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_ID_LOAD_ICON_DONE:
            sendCmdParams(setIcons(msg.obj));
            break;
        }
    }

    private ResultCode setIcons(Object data) {
        Bitmap[] icons = null;
        int iconIndex = 0;

        if (data == null) {
            return ResultCode.PRFRMD_ICON_NOT_DISPLAYED;
        }
        switch(mIconLoadState) {
        case LOAD_SINGLE_ICON:
            mCmdParams.setIcon((Bitmap) data);
            break;
        case LOAD_MULTI_ICONS:
            icons = (Bitmap[]) data;
            // set each item icon.
            for (Bitmap icon : icons) {
                mCmdParams.setIcon(icon);
            }
            break;
        }
        return ResultCode.OK;
    }

    private void sendCmdParams(ResultCode resCode) {
        if (mCaller != null)
            mCaller.sendMsgParamsDecoded(resCode, mCmdParams);
    }

    /**
     * Search for a COMPREHENSION-TLV object with the given tag from a list
     *
     * @param tag A tag to search for
     * @param ctlvs List of ComprehensionTlv objects used to search in
     *
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForTag(ComprehensionTlvTag tag,
            List<ComprehensionTlv> ctlvs) {
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        return searchForNextTag(tag, iter);
    }

    /**
     * Search for the next COMPREHENSION-TLV object with the given tag from a
     * list iterated by {@code iter}. {@code iter} points to the object next to
     * the found object when this method returns. Used for searching the same
     * list for similar tags, usually item id.
     *
     * @param tag A tag to search for
     * @param iter Iterator for ComprehensionTlv objects used for search
     *
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForNextTag(ComprehensionTlvTag tag,
            Iterator<ComprehensionTlv> iter) {
        int tagValue = tag.value();
        while (iter.hasNext()) {
            ComprehensionTlv ctlv = iter.next();
            if (ctlv.getTag() == tagValue) {
                return ctlv;
            }
        }
        return null;
    }

    private void resetTlvIndex() {
        tlvIndex = -1;
    }

    /**
     * Search for the next COMPREHENSION-TLV object with the given tag from a
     * list iterated by {@code iter}. {@code iter} points to the object next to
     * the found object when this method returns. Used for searching the same
     * list for similar tags, usually item id. At the same time, this method
     * will update a index to mark the position of the tlv object in the
     * comprehension- tlv.
     *
     * @param tag A tag to search for
     * @param iter Iterator for ComprehensionTlv objects used for search
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForNextTagAndIndex(ComprehensionTlvTag tag,
            Iterator<ComprehensionTlv> iter) {
        if (tag == null || iter == null) {
            CatLog.d(this, "CPF-searchForNextTagAndIndex: Invalid params");
            return null;
        }

        int tagValue = tag.value();

        while (iter.hasNext()) {
            ++tlvIndex;
            ComprehensionTlv ctlv = iter.next();
            if (ctlv.getTag() == tagValue) {
                return ctlv;
            }
        }

        // tlvIndex = -1;
        return null;
    }

    /**
     * Search for a COMPREHENSION-TLV object with the given tag from a list and
     * provide the index of searched tlv object
     *
     * @param tag A tag to search for
     * @param ctlvs List of ComprehensionTlv objects used to search in
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForTagAndIndex(ComprehensionTlvTag tag,
            List<ComprehensionTlv> ctlvs) {
        // tlvIndex = -1;
        resetTlvIndex();
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        return searchForNextTagAndIndex(tag, iter);
    }

    /**
     * Processes DISPLAY_TEXT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processDisplayText(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs)
            throws ResultException {

        CatLog.d(this, "process DisplayText");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveTextString(ctlv);
        }
        // If the tlv object doesn't exist or the it is a null object reply
        // with command not understood.
        if (textMsg.text == null) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        ctlv = searchForTag(ComprehensionTlvTag.IMMEDIATE_RESPONSE, ctlvs);
        if (ctlv != null) {
            textMsg.responseNeeded = false;
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            try {
            iconId = ValueParser.retrieveIconId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveIconId ResultException: " + e.result());
            }
            try {
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
            } catch (NullPointerException ne) {
                CatLog.e(this, "iconId is null.");
            }
        }
        // parse tone duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            try {
            textMsg.duration = ValueParser.retrieveDuration(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveDuration ResultException: " + e.result());
            }
        }

        // Parse command qualifier parameters.
        textMsg.isHighPriority = (cmdDet.commandQualifier & 0x01) != 0;
        textMsg.userClear = (cmdDet.commandQualifier & 0x80) != 0;

        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SET_UP_IDLE_MODE_TEXT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSetUpIdleModeText(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process SetUpIdleModeText");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveTextString(ctlv);
        }
        // load icons only when text exist.

        // if (textMsg.text != null) {
            ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
            if (ctlv != null) {
                iconId = ValueParser.retrieveIconId(ctlv);
                textMsg.iconSelfExplanatory = iconId.selfExplanatory;
            }
        // }

        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes GET_INKEY proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInkey(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process GetInkey");

        Input input = new Input();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            input.text = ValueParser.retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            try {
            iconId = ValueParser.retrieveIconId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveIconId ResultException: " + e.result());
            }

            try {
            input.iconSelfExplanatory = iconId.selfExplanatory;
            } catch (NullPointerException ne) {
                CatLog.e(this, "iconId is null.");
            }
        }

        // parse duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            try {
            input.duration = ValueParser.retrieveDuration(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveDuration ResultException: " + e.result());
            }
        }

        input.minLen = 1;
        input.maxLen = 1;

        input.digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        input.ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        input.yesNo = (cmdDet.commandQualifier & 0x04) != 0;
        input.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;
        input.echo = true;

        mCmdParams = new GetInputParams(cmdDet, input);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes GET_INPUT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInput(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process GetInput");

        Input input = new Input();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            input.text = ValueParser.retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.RESPONSE_LENGTH, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                // The maximum input lenght is 239, because the
                // maximum length of proactive command is 255
                input.minLen = rawValue[valueIndex] & 0xff;
                if (input.minLen > 239) {
                    input.minLen = 239;
                }

                input.maxLen = rawValue[valueIndex + 1] & 0xff;
                if (input.maxLen > 239) {
                    input.maxLen = 239;
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.DEFAULT_TEXT, ctlvs);
        if (ctlv != null) {
            try {
            input.defaultText = ValueParser.retrieveTextString(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveTextString ResultException: " + e.result());
            }
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            try {
            iconId = ValueParser.retrieveIconId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveIconId ResultException: " + e.result());
            }

            try {
            input.iconSelfExplanatory = iconId.selfExplanatory;
            } catch (NullPointerException ne) {
                CatLog.e(this, "iconId is null.");
            }
        }

        input.digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        input.ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        input.echo = (cmdDet.commandQualifier & 0x04) == 0;
        input.packed = (cmdDet.commandQualifier & 0x08) != 0;
        input.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        // Truncate the maxLen if it exceeds the max number of chars that can
        // be encoded. Limit depends on DCS in Command Qualifier.
        if (input.ucs2 && input.maxLen > MAX_UCS2_CHARS) {
            CatLog.d(this, "UCS2: received maxLen = " + input.maxLen +
                  ", truncating to " + MAX_UCS2_CHARS);
            input.maxLen = MAX_UCS2_CHARS;
        } else if (!input.packed && input.maxLen > MAX_GSM7_DEFAULT_CHARS) {
            CatLog.d(this, "GSM 7Bit Default: received maxLen = " + input.maxLen +
                  ", truncating to " + MAX_GSM7_DEFAULT_CHARS);
            input.maxLen = MAX_GSM7_DEFAULT_CHARS;
        }

        mCmdParams = new GetInputParams(cmdDet, input);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes REFRESH proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     */
    private boolean processRefresh(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) {

        CatLog.d(this, "process Refresh");
        TextMessage textMsg = new TextMessage();

        // REFRESH proactive command is rerouted by the baseband and handled by
        // the telephony layer. IDLE TEXT should be removed for a REFRESH command
        // with "initialization" or "reset"
        switch (cmdDet.commandQualifier) {
        case REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE:
        case REFRESH_NAA_INIT_AND_FILE_CHANGE:
        case REFRESH_NAA_INIT:
        case REFRESH_UICC_RESET:
            textMsg.text = null;
            mCmdParams = new DisplayTextParams(cmdDet, textMsg);
            break;
        }
        return false;
    }

    /**
     * Processes SELECT_ITEM proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSelectItem(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process SelectItem");

        Menu menu = new Menu();
        IconId titleIconId = null;
        ItemsIconId itemsIconId = null;
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        if (ctlv != null) {
            try {
            menu.title = ValueParser.retrieveAlphaId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveAlphaId ResultException: " + e.result());
            }
            CatLog.d(this, "add AlphaId: " + menu.title);
        }

        while (true) {
            ctlv = searchForNextTag(ComprehensionTlvTag.ITEM, iter);
            if (ctlv != null) {
                Item item = ValueParser.retrieveItem(ctlv);
                CatLog.d(this, "add menu item: " + ((item == null) ? "" : item.toString()));
                menu.items.add(item);
            } else {
                break;
            }
        }

        // We must have at least one menu item.
        if (menu.items.size() == 0) {
            CatLog.d(this, "no menu item");
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.NEXT_ACTION_INDICATOR, ctlvs);
        if (ctlv != null) {
            try {
            menu.nextActionIndicator = ValueParser.retrieveNextActionIndicator(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveNextActionIndicator ResultException: " + e.result());
            }
            try {
            if (menu.nextActionIndicator.length != menu.items.size()) {
                CatLog.d(this, "nextActionIndicator.length != number of menu items");
                menu.nextActionIndicator = null;
            }
            } catch (NullPointerException ne) {
                CatLog.e(this, "nextActionIndicator is null.");
            }
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ID, ctlvs);
        if (ctlv != null) {
            // CAT items are listed 1...n while list start at 0, need to
            // subtract one.
            try {
            menu.defaultItem = ValueParser.retrieveItemId(ctlv) - 1;
            } catch (ResultException e) {
                CatLog.e(this, "retrieveItemId ResultException: " + e.result());
            }
            CatLog.d(this, "default item: " + menu.defaultItem);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            try {
            titleIconId = ValueParser.retrieveIconId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveIconId ResultException: " + e.result());
            }
            try {
            menu.titleIconSelfExplanatory = titleIconId.selfExplanatory;
            } catch (NullPointerException ne) {
                CatLog.e(this, "titleIconId is null.");
            }
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ICON_ID_LIST, ctlvs);
        if (ctlv != null) {
            mIconLoadState = LOAD_MULTI_ICONS;
            try {
            itemsIconId = ValueParser.retrieveItemsIconId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveItemsIconId ResultException: " + e.result());
            }
            try {
            menu.itemsIconSelfExplanatory = itemsIconId.selfExplanatory;
            } catch (NullPointerException ne) {
                CatLog.e(this, "itemsIconId is null.");
            }
        }

        boolean presentTypeSpecified = (cmdDet.commandQualifier & 0x01) != 0;
        if (presentTypeSpecified) {
            if ((cmdDet.commandQualifier & 0x02) == 0) {
                menu.presentationType = PresentationType.DATA_VALUES;
            } else {
                menu.presentationType = PresentationType.NAVIGATION_OPTIONS;
            }
        }
        menu.softKeyPreferred = (cmdDet.commandQualifier & 0x04) != 0;
        menu.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        mCmdParams = new SelectItemParams(cmdDet, menu, titleIconId != null);

        // Load icons data if needed.
        switch(mIconLoadState) {
        case LOAD_NO_ICON:
            return false;
        case LOAD_SINGLE_ICON:
            if (titleIconId != null && titleIconId.recordNumber > 0) {
                mIconLoader.loadIcon(titleIconId.recordNumber, this
                        .obtainMessage(MSG_ID_LOAD_ICON_DONE));
                break;
            } else {
                return false;
            }
        case LOAD_MULTI_ICONS:
            if (itemsIconId != null) {
                int[] recordNumbers = itemsIconId.recordNumbers;
                // Create a new array for all the icons (title and items).
                recordNumbers = new int[itemsIconId.recordNumbers.length + 1];
                if (titleIconId != null) {
                    recordNumbers[0] = titleIconId.recordNumber;
                }
                System.arraycopy(itemsIconId.recordNumbers, 0, recordNumbers,
                        1, itemsIconId.recordNumbers.length);
                mIconLoader.loadIcons(recordNumbers, this
                        .obtainMessage(MSG_ID_LOAD_ICON_DONE));
                break;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Processes EVENT_NOTIFY message from baseband.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processEventNotify(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process EventNotify");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveAlphaId(ctlv);
        } else {
            // throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
            textMsg.text = null;
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        textMsg.responseNeeded = false;
        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SET_UP_EVENT_LIST proactive command from the SIM card.
     *
     * @param cmdDet Command Details object retrieved.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return false. This function always returns false meaning that the command
     *         processing is  not pending and additional asynchronous processing
     *         is not required.
     */
/* L-MR1
    private boolean processSetUpEventList(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) {

        CatLog.d(this, "process SetUpEventList");
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.EVENT_LIST, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();
                int[] eventList = new int[valueLen];
                int eventValue = -1;
                int i = 0;
                while (valueLen > 0) {
                    eventValue = rawValue[valueIndex] & 0xff;
                    valueIndex++;
                    valueLen--;

                    switch (eventValue) {
                        case USER_ACTIVITY_EVENT:
                        case IDLE_SCREEN_AVAILABLE_EVENT:
                        case LANGUAGE_SELECTION_EVENT:
                        case BROWSER_TERMINATION_EVENT:
                        case BROWSING_STATUS_EVENT:
                        /// M: BIP {
                        case DATA_AVAILABLE_EVENT:
                        case CHANNEL_STATUS_EVENT:
                        /// M: BIP }
                            eventList[i] = eventValue;
                            i++;
                            break;
                        default:
                            break;
                    }

                }
                mCmdParams = new SetEventListParams(cmdDet, eventList);
            } catch (IndexOutOfBoundsException e) {
                CatLog.e(this, " IndexOutofBoundException in processSetUpEventList");
            }
        }
        return false;
    }
*/
    /**
     *            object and Device Identities object within the proactive
     *            command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processSetUpEventList(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        //
        // ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.EVENT_LIST,
        // ctlvs);
        // if (ctlv != null) {
        // try {
        // byte[] rawValue = ctlv.getRawValue();
        // int valueIndex = ctlv.getValueIndex();
        // int valueLen = ctlv.getLength();
        //
        // } catch (IndexOutOfBoundsException e) {}
        // }
        // return true;

        CatLog.d(this, "process SetUpEventList");

        byte[] eventList;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.EVENT_LIST, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();

                eventList = new byte[valueLen];
                for (int index = 0; index < valueLen; ) {
                    eventList[index] = rawValue[valueIndex];
                    CatLog.d(this, "CPF-processSetUpEventList: eventList[" + index + "] = "
                            + eventList[index]);
                    if (rawValue[valueIndex] == CatService.EVENT_LIST_ELEMENT_IDLE_SCREEN_AVAILABLE) {
                        CatLog.d(this, "CPF-processSetUpEventList: sent intent with idle = true");
                        Intent intent = new Intent(CatService.IDLE_SCREEN_INTENT_NAME);
                        intent.putExtra(CatService.IDLE_SCREEN_ENABLE_KEY, true);
                        mContext.sendBroadcast(intent);
                        // IWindowManager wm =
                        // IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
                        /*
                         * try { wm.setEventDownloadNeeded(true); } catch
                         * (RemoteException e) { CatLog.d(this,
                         * "Exception when set EventDownloadNeeded flag in WindowManager"
                         * ); } catch (NullPointerException e2) { StkLog.d(this,
                         * "wm is null"); }
                         */
                    } else if (rawValue[valueIndex] == CatService.EVENT_LIST_ELEMENT_USER_ACTIVITY) {
                        CatLog.d(this, "CPF-processSetUpEventList: sent intent for user activity");
                        Intent intent = new Intent(CatService.USER_ACTIVITY_INTENT_NAME);
                        intent.putExtra(CatService.USER_ACTIVITY_ENABLE_KEY, true);
                        mContext.sendBroadcast(intent);
                    }
                    index++;
                    valueIndex++;
                }
                mCmdParams = new SetupEventListParams(cmdDet, eventList);
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }

        return false;
    }

    /**
     * Processes LAUNCH_BROWSER proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processLaunchBrowser(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process LaunchBrowser");

        TextMessage confirmMsg = new TextMessage();
        IconId iconId = null;
        String url = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.URL, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();
                if (valueLen > 0) {
                    url = GsmAlphabet.gsm8BitUnpackedToString(rawValue,
                            valueIndex, valueLen);
                } else {
                    url = null;
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }

        // parse alpha identifier.
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            confirmMsg.text = ValueParser.retrieveAlphaId(ctlv);
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            confirmMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        // parse command qualifier value.
        LaunchBrowserMode mode;
        switch (cmdDet.commandQualifier) {
        case 0x00:
        default:
            mode = LaunchBrowserMode.LAUNCH_IF_NOT_ALREADY_LAUNCHED;
            break;
        case 0x02:
            mode = LaunchBrowserMode.USE_EXISTING_BROWSER;
            break;
        case 0x03:
            mode = LaunchBrowserMode.LAUNCH_NEW_BROWSER;
            break;
        }

        mCmdParams = new LaunchBrowserParams(cmdDet, confirmMsg, url, mode);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

     /**
     * Processes PLAY_TONE proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.t
     * @throws ResultException
     */
    private boolean processPlayTone(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process PlayTone");

        Tone tone = null;
        TextMessage textMsg = new TextMessage();
        Duration duration = null;
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TONE, ctlvs);
        if (ctlv != null) {
            // Nothing to do for null objects.
            if (ctlv.getLength() > 0) {
                try {
                    byte[] rawValue = ctlv.getRawValue();
                    int valueIndex = ctlv.getValueIndex();
                    int toneVal = rawValue[valueIndex];
                    tone = Tone.fromInt(toneVal);
                } catch (IndexOutOfBoundsException e) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            }
        }
        // parse alpha identifier
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            try {
            textMsg.text = ValueParser.retrieveAlphaId(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveAlphaId ResultException: " + e.result());
            }
        }
        // parse tone duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            try {
            duration = ValueParser.retrieveDuration(ctlv);
            } catch (ResultException e) {
                CatLog.e(this, "retrieveDuration ResultException: " + e.result());
            }
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        boolean vibrate = (cmdDet.commandQualifier & 0x01) != 0x00;

        textMsg.responseNeeded = false;
        mCmdParams = new PlayToneParams(cmdDet, textMsg, tone, duration, vibrate);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SETUP_CALL proactive command from the SIM card.
     *
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processSetupCall(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process SetupCall");

        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        ComprehensionTlv ctlv = null;
        // User confirmation phase message.
        TextMessage confirmMsg = new TextMessage();
        // Call set up phase message.
        TextMessage callMsg = new TextMessage();
        IconId confirmIconId = null;
        IconId callIconId = null;

        // The structure of SET UP CALL
        // alpha id -> address -> icon id -> alpha id -> icon id
        // We use the index of alpha id to judge the type of alpha id:
        // confirm or call
        final int addrIndex = getAddrIndex(ctlvs);
        if (-1 == addrIndex) {
            CatLog.d(this, "fail to get ADDRESS data object");
            return false;
        }

        final int alpha1Index = getConfirmationAlphaIdIndex(ctlvs, addrIndex);
        final int alpha2Index = getCallingAlphaIdIndex(ctlvs, addrIndex);

        ctlv = getConfirmationAlphaId(ctlvs, addrIndex);
        if (ctlv != null) {
            confirmMsg.text = ValueParser.retrieveAlphaId(ctlv);
        }

        ctlv = getConfirmationIconId(ctlvs, alpha1Index, alpha2Index);
        if (ctlv != null) {
            confirmIconId = ValueParser.retrieveIconId(ctlv);
            confirmMsg.iconSelfExplanatory = confirmIconId.selfExplanatory;
        }

        ctlv = getCallingAlphaId(ctlvs, addrIndex);
        if (ctlv != null) {
            callMsg.text = ValueParser.retrieveAlphaId(ctlv);
        }

        ctlv = getCallingIconId(ctlvs, alpha2Index);
        if (ctlv != null) {
            callIconId = ValueParser.retrieveIconId(ctlv);
            callMsg.iconSelfExplanatory = callIconId.selfExplanatory;
        }

        mCmdParams = new CallSetupParams(cmdDet, confirmMsg, callMsg);

        if (confirmIconId != null || callIconId != null) {
            mIconLoadState = LOAD_MULTI_ICONS;
            int[] recordNumbers = new int[2];
            recordNumbers[0] = confirmIconId != null
                    ? confirmIconId.recordNumber : -1;
            recordNumbers[1] = callIconId != null ? callIconId.recordNumber
                    : -1;

            mIconLoader.loadIcons(recordNumbers, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    private boolean processProvideLocalInfo(CommandDetails cmdDet, List<ComprehensionTlv> ctlvs)
            throws ResultException {
        CatLog.d(this, "process ProvideLocalInfo");
        switch (cmdDet.commandQualifier) {
            case DTTZ_SETTING:
                CatLog.d(this, "PLI [DTTZ_SETTING]");
                mCmdParams = new CommandParams(cmdDet);
                break;
            case LANGUAGE_SETTING:
                CatLog.d(this, "PLI [LANGUAGE_SETTING]");
                mCmdParams = new CommandParams(cmdDet);
                break;
            default:
                CatLog.d(this, "PLI[" + cmdDet.commandQualifier + "] Command Not Supported");
                mCmdParams = new CommandParams(cmdDet);
                throw new ResultException(ResultCode.BEYOND_TERMINAL_CAPABILITY);
        }
        return false;
    }

    /**
     * Processes Activate proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processActivate(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process Activate");

        ComprehensionTlv ctlv = null;
        int target = 0;

        ctlv = searchForTag(ComprehensionTlvTag.ACTIVATE_DESCRIPTOR, ctlvs);
        if (ctlv != null) {
            try {
                target = ValueParser.retrieveTarget(ctlv);
            } catch (ResultException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
            CatLog.d(this, "target: " + target);
        }

        mCmdParams = new ActivateParams(cmdDet, target);
        return false;
    }

    /**
     * Get the index of ADDRESS data object.
     *
     * @param list List of ComprehensionTlv
     * @return the index of ADDRESS data object.
     */
    private int getAddrIndex(final List<ComprehensionTlv> list) {
        int addrIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ADDRESS.value()) {
                return addrIndex;
            }
            ++addrIndex;
        } // end while

        return -1;
    }

    /**
     * Get the index of ALPHA_ID data object in confirmation phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param addrIndex The index of ADDRESS data object
     * @return the index of ALPHA_ID data object.
     */
    private int getConfirmationAlphaIdIndex(final List<ComprehensionTlv> list,
            final int addrIndex) {
        int alphaIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ALPHA_ID.value()
                    && alphaIndex < addrIndex) {
                return alphaIndex;
            }
            ++alphaIndex;
        } // end while

        return -1;
    }

    /**
     * Get the index of ALPHA_ID data object in call phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param addrIndex The index of ADDRESS data object
     * @return the index of ALPHA_ID data object.
     */
    private int getCallingAlphaIdIndex(final List<ComprehensionTlv> list,
            final int addrIndex) {
        int alphaIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ALPHA_ID.value()
                    && alphaIndex > addrIndex) {
                return alphaIndex;
            }
            ++alphaIndex;
        } // end while

        return -1;
    }

    /**
     * Get the ALPHA_ID data object in confirmation phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param addrIndex The index of ADDRESS data object
     * @return ALPHA_ID data object.
     */
    private ComprehensionTlv getConfirmationAlphaId(final List<ComprehensionTlv> list,
            final int addrIndex) {
        int alphaIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ALPHA_ID.value()
                    && alphaIndex < addrIndex) {
                return temp;
            }
            ++alphaIndex;
        } // end while

        return null;
    }

    /**
     * Get the ALPHA_ID data object in call phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param addrIndex The index of ADDRESS data object
     * @return ALPHA_ID data object.
     */
    private ComprehensionTlv getCallingAlphaId(final List<ComprehensionTlv> list,
            final int addrIndex) {
        int alphaIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ALPHA_ID.value()
                    && alphaIndex > addrIndex) {
                return temp;
            }
            ++alphaIndex;
        } // end while

        return null;
    }

    /**
     * Get the ICON_ID data object in confirmation phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param alpha1Index The index of ALPHA_ID data object of confirmation
     *            phase
     * @param alpha2Index The index of ALPHA_ID data object of call phase
     * @return ICON_ID data object.
     */
    private ComprehensionTlv getConfirmationIconId(final List<ComprehensionTlv> list,
            final int alpha1Index,
            final int alpha2Index) {
        if (-1 == alpha1Index) {
            return null;
        }

        int iconIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ICON_ID.value()
                    && (-1 == alpha2Index || iconIndex < alpha2Index)) {
                return temp;
            }
            ++iconIndex;
        } // end while

        return null;
    }

    /**
     * Get the ICON_ID data object in call phase data object.
     *
     * @param list List of ComprehensionTlv
     * @param alpha2Index The index of ALPHA_ID data object of call phase
     * @return ICON_ID data object.
     */
    private ComprehensionTlv getCallingIconId(final List<ComprehensionTlv> list,
            final int alpha2Index) {
        if (-1 == alpha2Index) {
            return null;
        }

        int iconIndex = 0;

        ComprehensionTlv temp = null;
        Iterator<ComprehensionTlv> iter = list.iterator();
        while (iter.hasNext()) {
            temp = iter.next();
            if (temp.getTag() == ComprehensionTlvTag.ICON_ID.value()
                    && iconIndex > alpha2Index) {
                return temp;
            }
            ++iconIndex;
        } // end while

        return null;
    }

    private boolean processBIPClient(CommandDetails cmdDet,
                                     List<ComprehensionTlv> ctlvs) throws ResultException {
        AppInterface.CommandType commandType =
                                    AppInterface.CommandType.fromInt(cmdDet.typeOfCommand);
        if (commandType != null) {
            CatLog.d(this, "process "+ commandType.name());
        }

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;
        ComprehensionTlv ctlv = null;
        boolean has_alpha_id = false;

        // parse alpha identifier
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveAlphaId(ctlv);
            CatLog.d(this, "alpha TLV text=" + textMsg.text);
            has_alpha_id = true;
        }

        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        textMsg.responseNeeded = false;
        mCmdParams = new BIPClientParams(cmdDet, textMsg, has_alpha_id);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    public void dispose() {
        mIconLoader.dispose();
        mIconLoader = null;
        mCmdParams = null;
        mCaller = null;
        sInstance = null;
    }
}
