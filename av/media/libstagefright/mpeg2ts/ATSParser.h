/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef A_TS_PARSER_H_

#define A_TS_PARSER_H_

#include <sys/types.h>

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaSource.h>
#include <utils/KeyedVector.h>
#include <utils/Vector.h>
#include <utils/RefBase.h>

namespace android {

class ABitReader;
struct ABuffer;

struct ATSParser : public RefBase {
    enum DiscontinuityType {
        DISCONTINUITY_NONE              = 0,
        DISCONTINUITY_TIME              = 1,
        DISCONTINUITY_AUDIO_FORMAT      = 2,
        DISCONTINUITY_VIDEO_FORMAT      = 4,
        DISCONTINUITY_ABSOLUTE_TIME     = 8,
        DISCONTINUITY_TIME_OFFSET       = 16,

        // For legacy reasons this also implies a time discontinuity.
        DISCONTINUITY_FORMATCHANGE      =
            DISCONTINUITY_AUDIO_FORMAT
                | DISCONTINUITY_VIDEO_FORMAT
                | DISCONTINUITY_TIME,
        DISCONTINUITY_FORMAT_ONLY       =
            DISCONTINUITY_AUDIO_FORMAT
                | DISCONTINUITY_VIDEO_FORMAT,
#ifdef MTK_AOSP_ENHANCEMENT
        DISCONTINUITY_HTTPLIVE_MEDIATIME     = 0x20000000,
        DISCONTINUITY_FLUSH_SOURCE_ONLY       = 0x80000000,
#endif
    };

    enum Flags {
        // The 90kHz clock (PTS/DTS) is absolute, i.e. PTS=0 corresponds to
        // a media time of 0.
        // If this flag is _not_ specified, the first PTS encountered in a
        // program of this stream will be assumed to correspond to media time 0
        // instead.
        TS_TIMESTAMPS_ARE_ABSOLUTE = 1,
        // Video PES packets contain exactly one (aligned) access unit.
        ALIGNED_VIDEO_DATA         = 2,
#ifdef MTK_AOSP_ENHANCEMENT
        TS_SOURCE_IS_LOCAL = 0x40000000,
        TS_SOURCE_IS_STREAMING = 0x80000000,
#endif
    };

    // Event is used to signal sync point event at feedTSPacket().
    struct SyncEvent {
        SyncEvent(off64_t offset);

        void init(off64_t offset, const sp<MediaSource> &source,
                int64_t timeUs);

        bool isInit() { return mInit; }
        off64_t getOffset() { return mOffset; }
        const sp<MediaSource> &getMediaSource() { return mMediaSource; }
        int64_t getTimeUs() { return mTimeUs; }

    private:
        bool mInit;
        /*
         * mInit == false: the current offset
         * mInit == true: the start offset of sync payload
         */
        off64_t mOffset;
        /* The media source object for this event. */
        sp<MediaSource> mMediaSource;
        /* The timestamp of the sync frame. */
        int64_t mTimeUs;
    };

    ATSParser(uint32_t flags = 0);

    // Feed a TS packet into the parser. uninitialized event with the start
    // offset of this TS packet goes in, and if the parser detects PES with
    // a sync frame, the event will be initiailzed with the start offset of the
    // PES. Note that the offset of the event can be different from what we fed,
    // as a PES may consist of multiple TS packets.
    //
    // Even in the case feedTSPacket() returns non-OK value, event still may be
    // initialized if the parsing failed after the detection.
    status_t feedTSPacket(
            const void *data, size_t size, SyncEvent *event = NULL);

#ifdef MTK_AOSP_ENHANCEMENT
    void signalDiscontinuity(DiscontinuityType type, const sp <AMessage> &extra = NULL);
#else
    void signalDiscontinuity(
            DiscontinuityType type, const sp<AMessage> &extra);
#endif

    void signalEOS(status_t finalResult);

    enum SourceType {
        VIDEO = 0,
        AUDIO = 1,
        META  = 2,
        NUM_SOURCE_TYPES = 3
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_AOSP_ENHANCEMENT
        ,SUBTITLE
#endif
#endif
    };
    sp<MediaSource> getSource(SourceType type);
    bool hasSource(SourceType type) const;

    bool PTSTimeDeltaEstablished();

    enum {
        // From ISO/IEC 13818-1: 2000 (E), Table 2-29
        STREAMTYPE_RESERVED             = 0x00,
        STREAMTYPE_MPEG1_VIDEO          = 0x01,
        STREAMTYPE_MPEG2_VIDEO          = 0x02,
        STREAMTYPE_MPEG1_AUDIO          = 0x03,
        STREAMTYPE_MPEG2_AUDIO          = 0x04,
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_AOSP_ENHANCEMENT
        STREAMTYPE_SUBTITLE = 0x06,
#endif
#endif
        STREAMTYPE_MPEG2_AUDIO_ADTS     = 0x0f,
        STREAMTYPE_MPEG4_VIDEO          = 0x10,
        STREAMTYPE_METADATA             = 0x15,
        STREAMTYPE_H264                 = 0x1b,
#ifdef MTK_AOSP_ENHANCEMENT
        STREAMTYPE_HEVC                 = 0x24,
        STREAMTYPE_AUDIO_PSLPCM = 0xa0,
        STREAMTYPE_AUDIO_BDLPCM = 0x80,
        STREAMTYPE_VC1_VIDEO = 0xea,
#endif
        // From ATSC A/53 Part 3:2009, 6.7.1
        STREAMTYPE_AC3                  = 0x81,
        STREAMTYPE_LPCM_AC3             = 0x83,
        // Stream type 0x83 is non-standard,
        // it could be LPCM or TrueHD AC3
        STREAMTYPE_EC3                  = 0x87,
    };

        sp<MediaSource> getSource(unsigned PID, unsigned index);

    bool       isParsedPIDEmpty();
    unsigned   parsedPIDSize();
    void       removeParsedPID(unsigned index);
    void       addParsedPID(unsigned elemPID);
    unsigned   getParsedPID(unsigned index);
    size_t getPlayProgramPID(size_t playindex);

protected:
    virtual ~ATSParser();

private:
        Vector<unsigned> mParsedPID;
    struct Program;
    struct Stream;
    struct PSISection;

    uint32_t mFlags;
    bool isSourceFromWFD;
    Vector<sp<Program> > mPrograms;

    // Keyed by PID
    KeyedVector<unsigned, sp<PSISection> > mPSISections;

    int64_t mAbsoluteTimeAnchorUs;

    bool mTimeOffsetValid;
    int64_t mTimeOffsetUs;
    int64_t mLastRecoveredPTS;

    size_t mNumTSPacketsParsed;

#ifdef MTK_AOSP_ENHANCEMENT
    status_t parseProgramAssociationTable(ABitReader *br);
#else
    void parseProgramAssociationTable(ABitReader *br);
#endif
    void parseProgramMap(ABitReader *br);
    // Parse PES packet where br is pointing to. If the PES contains a sync
    // frame, set event with the time and the start offset of this PES.
    // Note that the method itself does not touch event.
    void parsePES(ABitReader *br, SyncEvent *event);

    // Strip remaining packet headers and pass to appropriate program/stream
    // to parse the payload. If the payload turns out to be PES and contains
    // a sync frame, event shall be set with the time and start offset of the
    // PES.
    // Note that the method itself does not touch event.
    status_t parsePID(
        ABitReader *br, unsigned PID,
        unsigned continuity_counter,
        unsigned payload_unit_start_indicator,
        SyncEvent *event);

    status_t parseAdaptationField(ABitReader *br, unsigned PID);
    // see feedTSPacket().
    status_t parseTS(ABitReader *br, SyncEvent *event);

    void updatePCR(unsigned PID, uint64_t PCR, size_t byteOffsetFromStart);

    uint64_t mPCR[2];
    size_t mPCRBytes[2];
    int64_t mSystemTimeUs[2];
    size_t mNumPCRs;

    DISALLOW_EVIL_CONSTRUCTORS(ATSParser);
#ifdef MTK_AOSP_ENHANCEMENT
public:
    void setQueue(bool isQueue);
    int64_t getMaxPTS();
    bool firstPTSIsValid();
    bool findPAT(const void *data, size_t size);
    bool getDequeueState();
    void setDequeueState(bool needDequeuePES);
    void useFrameBase();
    bool isFrameBase();
    size_t getPlayIndex(){return currentPlayIndex;}
    void setPlayIndex(size_t playindex){currentPlayIndex = playindex;}
    void setFirstPTSIsValid();
    void DumpTS(const void *data);//mtk08123 for tsdump
    void configureTSDump();//mtk08123 for tsdump
private:
    FILE* mDumptsfile;
    bool Dumptsfile;
    bool mNeedDequeuePES;
    bool mUseFrameBase;
    size_t currentPlayIndex;
#endif
};

}  // namespace android

#endif  // A_TS_PARSER_H_
