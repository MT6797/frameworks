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
/*****************************************************************************
 *
 * Filename:
 * ---------
 *   PRFakeExtractor.cpp
 *
 * Project:
 * --------
 *   Everest
 *
 * Description:
 * ------------
 *   PlayReady SVP for 4k SVP
 *
 * Note:
 *   Condition to use the extractor:
 *      1, Should special video, mp4 with special head + h264
 *      2, Should setprop playready.fake.mode 1
 *      3, Should open FO MTK_PLAYREADY_SUPPORT && TRUSTONIC_TEE_SUPPORT &&
 *         MTK_SEC_VIDEO_PATH_SUPPORT
 *      4, local option control: MTK_PLAYREADY_FAKEMODE, in Android.mk,
 *         mark it to disable it
 * Author:
 * -------
 *   Xingyu Zhou (mtk80781)
 *
 ****************************************************************************/
#ifdef MTK_PLAYREADY_FAKEMODE
#define LOG_TAG "PRFakeExtractor"
#include "include/PRFakeExtractor.h"

#include <arpa/inet.h>
#include <utils/String8.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaBuffer.h>

#include <utils/Errors.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <cutils/properties.h>

#include <fcntl.h>

namespace android {
static bool isFakeMode() {
    char value[PROPERTY_VALUE_MAX];
    if (property_get("playready.fake.mode", value, "0")) {
        bool _res = atoi(value);
        if (_res) {
            return true;
        }
    }
    return false;
}

class PRFakeSource : public MediaSource {
public:
    PRFakeSource(const sp<MediaSource> &mediaSource);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    virtual status_t setBuffers(const Vector<MediaBuffer *> &buffers);

protected:
    virtual ~PRFakeSource();

private:
    sp<MediaSource> mOriginalMediaSource;
    size_t mTrackId;
    mutable Mutex mPRFakeLock;
    size_t mNALLengthSize;
    bool mWantsNALFragments;

    struct CodecSpecificData {
        size_t mSize;
        uint8_t mData[1];
    };
    Vector<CodecSpecificData *> mCodecSpecificData;
    size_t mCodecSpecificDataIndex;
    status_t parseAVCCodecSpecificData(
            const void *data, size_t size,
            unsigned *profile, unsigned *level);
    void addCodecSpecificData(const void *data, size_t size);
    void clearCodecSpecificData();
    ReadOptions mOptions;

    status_t secureCopy(void *src, size_t len, bool addPrefix = false);

    MediaBufferGroup *mGroup;
    MediaBuffer *mBuffer;
    int mFd;
    PRFakeSource(const PRFakeSource &);
    PRFakeSource &operator=(const PRFakeSource &);
};

////////////////////////////////////////////////////////////////////////////////

PRFakeSource::PRFakeSource(const sp<MediaSource> &mediaSource)
    : mOriginalMediaSource(mediaSource),
    mNALLengthSize(0),
    mWantsNALFragments(false) {
    mBuffer = NULL;
    mGroup = NULL;
    mCodecSpecificDataIndex = 0;

    const char *mime;
    bool success = getFormat()->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mFd = open("/dev/mtk_disp", O_RDWR);
        ALOGI("fake mode use /dev/mtk_disp map (%d)", mFd);
        if (mFd < 0) {
            ALOGE("open /dev/mtk_disp fail");
        }

        uint32_t type;
        const void *data;
        size_t size;
        CHECK(getFormat()->findData(kKeyAVCC, &type, &data, &size));

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ((unsigned)ptr[0], 1u);  // configurationVersion == 1

        // The number of bytes used to encode the length of a NAL unit.
        mNALLengthSize = 1 + (ptr[4] & 3);
        unsigned profile, level;
        status_t err;
        if ((err = parseAVCCodecSpecificData(
                        data, size, &profile, &level)) != OK) {
            ALOGE("Malformed AVC codec specific data.");
        }
    }

    mOptions.clearSeekTo();
    getFormat()->remove(kKeyAVCC);
}

status_t PRFakeSource::parseAVCCodecSpecificData(
        const void *data, size_t size,
        unsigned *profile, unsigned *level) {
    const uint8_t *ptr = (const uint8_t *)data;

    // verify minimum size and configurationVersion == 1.
    if (size < 7 || ptr[0] != 1) {
        return ERROR_MALFORMED;
    }

    *profile = ptr[1];
    *level = ptr[3];

    // There is decodable content out there that fails the following
    // assertion, let's be lenient for now...
    // CHECK((ptr[4] >> 2) == 0x3f);  // reserved

    // size_t lengthSize = 1 + (ptr[4] & 3);

    // commented out check below as H264_QVGA_500_NO_AUDIO.3gp
    // violates it...
    // CHECK((ptr[5] >> 5) == 7);  // reserved

    size_t numSeqParameterSets = ptr[5] & 31;

    ptr += 6;
    size -= 6;

    for (size_t i = 0; i < numSeqParameterSets; ++i) {
        if (size < 2) {
            return ERROR_MALFORMED;
        }

        size_t length = U16_AT(ptr);

        ptr += 2;
        size -= 2;

        if (size < length) {
            return ERROR_MALFORMED;
        }

        addCodecSpecificData(ptr, length);

        ptr += length;
        size -= length;
    }

    if (size < 1) {
        return ERROR_MALFORMED;
    }

    size_t numPictureParameterSets = *ptr;
    ++ptr;
    --size;

    for (size_t i = 0; i < numPictureParameterSets; ++i) {
        if (size < 2) {
            return ERROR_MALFORMED;
        }

        size_t length = U16_AT(ptr);

        ptr += 2;
        size -= 2;

        if (size < length) {
            return ERROR_MALFORMED;
        }

        addCodecSpecificData(ptr, length);

        ptr += length;
        size -= length;
    }

    return OK;
}

void PRFakeSource::addCodecSpecificData(const void *data, size_t size) {
    CodecSpecificData *specific =
        (CodecSpecificData *)malloc(sizeof(CodecSpecificData) + size - 1);

    specific->mSize = size;
    memcpy(specific->mData, data, size);

    mCodecSpecificData.push(specific);
}

void PRFakeSource::clearCodecSpecificData() {
    for (size_t i = 0; i < mCodecSpecificData.size(); ++i) {
        free(mCodecSpecificData.editItemAt(i));
    }
    mCodecSpecificData.clear();
    mCodecSpecificDataIndex = 0;
}

PRFakeSource::~PRFakeSource() {
    Mutex::Autolock autoLock(mPRFakeLock);
    clearCodecSpecificData();

    if (mFd > 0) {
        close(mFd);
        ALOGI("close /dev/mtk_disp for mmap (%d)", mFd);
    }
    if (mBuffer != NULL) {
        mBuffer->release();
        mBuffer = NULL;
    }
    if (mGroup != NULL) {
        delete mGroup;
        mGroup = NULL;
    }
}

status_t PRFakeSource::start(MetaData *params) {
    // svp h264 need mWantsNALFragments false
    mWantsNALFragments = false;
    ALOGI("SVP do not use nal fragments");
   return mOriginalMediaSource->start(params);
}

status_t PRFakeSource::stop() {
    return mOriginalMediaSource->stop();
}

sp<MetaData> PRFakeSource::getFormat() {
    return mOriginalMediaSource->getFormat();
}

status_t PRFakeSource::read(MediaBuffer **buffer, const ReadOptions *options) {
    Mutex::Autolock autoLock(mPRFakeLock);

    status_t err;
    int64_t seekTimeUs1;
    ReadOptions::SeekMode mode1;
    if (options && options->getSeekTo(&seekTimeUs1, &mode1) && mCodecSpecificData.size() != 0) {
        mOptions.setSeekTo(seekTimeUs1, mode1);
        mCodecSpecificDataIndex = 0;
        ALOGI("seek should send config data");
    }

    if (mCodecSpecificDataIndex < mCodecSpecificData.size()) {
        ALOGI("set codec info");
        const CodecSpecificData *specific =
            mCodecSpecificData[mCodecSpecificDataIndex];

        if (!mWantsNALFragments) {          // add nal prefix
            err = secureCopy((void *)specific, specific->mSize + 4, true);
            if (err != OK) {
                return err;
            }
        }  // else {    // Todo, default mWantsNALFragments false

        *buffer = mBuffer;
        mBuffer = NULL;
        mCodecSpecificDataIndex++;
        return OK;
    }

    ALOGI("zxy fakeMode %s(),line:%d", __FUNCTION__, __LINE__);
    if (mOptions.getSeekTo(&seekTimeUs1, &mode1)) {
        err = mOriginalMediaSource->read(buffer, &mOptions);
        mOptions.clearSeekTo();
    } else {
        err = mOriginalMediaSource->read(buffer, options);
    }
    if (err != OK) {
        return err;
    }

    if (mGroup != NULL) {           // only video would use mGroup
        err = secureCopy((void *)buffer, (*buffer)->range_length());
        if (err != OK) {
            return err;
        }
        (*buffer)->release();
        *buffer = mBuffer;
        mBuffer = NULL;
    }

    return OK;
}

status_t PRFakeSource::secureCopy(void *src, size_t len, bool addPrefix) {
    status_t err = mGroup->acquire_buffer(&mBuffer);
    if (err != OK) {
        CHECK(mBuffer == NULL);
        return err;
    }

    if (len > mBuffer->range_length()) {
        ALOGE("len:%zu is too large", len);
        return -1;
    }

    unsigned long sec_pa = (unsigned long)(mBuffer->data());
    uint64_t sec_pa_64 = (uint64_t)sec_pa;

    int *map_va = (int *)mmap64(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, mFd, sec_pa_64);
    ALOGI("sec_pa_64:0x%llx, va:0x%p mFd:%d", (long long)sec_pa_64, map_va, mFd);

    if (map_va == MAP_FAILED) {
        ALOGE("mmap fail (%s)", strerror(errno));
        return UNKNOWN_ERROR;
    }

    uint8_t *src1 = (uint8_t *)(map_va);
    MediaBuffer **buffer = NULL;
    if (addPrefix) {
        CodecSpecificData *specific = (CodecSpecificData *)src;
        memcpy(src1, "\x00\x00\x00\x01", 4);
        memcpy(src1+4, specific->mData, specific->mSize);
        mBuffer->set_range(0, specific->mSize+4);

        const sp<MetaData> bufmeta = mBuffer->meta_data();
        bufmeta->clear();
        bufmeta->setInt64(kKeyTime, 0);
        return OK;
    } else {
        buffer = (MediaBuffer **)src;
        uint8_t *src0 = (uint8_t *)((*buffer)->data());
        ALOGI("len:%zu, before map dec:%02x %02x %02x %02x %02x", len, src0[0], src0[1], src0[2], src0[3], src0[4]);
        memcpy(src1, (*buffer)->data(), len);
        mBuffer->set_range(0, len);
        ALOGI("len:%zu, dec:%02x %02x %02x %02x %02x %02x %02x", mBuffer->range_length(),
              src1[0], src1[1], src1[2], src1[3], src1[4], src1[5], src1[6]);
    }

    // get meta info
    int64_t lastBufferTimeUs, targetSampleTimeUs;
    int32_t isSyncFrame;
    mBuffer->meta_data()->clear();
    CHECK((*buffer)->meta_data()->findInt64(kKeyTime, &lastBufferTimeUs));
    mBuffer->meta_data()->setInt64(kKeyTime, lastBufferTimeUs);

    if ((*buffer)->meta_data()->findInt64(kKeyTargetTime, &targetSampleTimeUs)) {
        mBuffer->meta_data()->setInt64(
                kKeyTargetTime, targetSampleTimeUs);
    }
    if ((*buffer)->meta_data()->findInt32(kKeyIsSyncFrame, &isSyncFrame)) {
        mBuffer->meta_data()->setInt32(kKeyIsSyncFrame, isSyncFrame);
    }
    return OK;
}

status_t PRFakeSource::setBuffers(const Vector<MediaBuffer *> &buffers) {
    mGroup = new MediaBufferGroup;
    for (size_t i = 0; i < buffers.size(); ++i) {
        ALOGI("mGroup add buffer:%zu, 0x%p", i, (buffers.itemAt(i))->data());
        mGroup->add_buffer(buffers.itemAt(i));
    }
    return OK;
}
////////////////////////////////////////////////////////////////////////////////

PRFakeExtractor::PRFakeExtractor(const sp<DataSource> &source, const char* mime)
    : mDataSource(source) {
    mOriginalExtractor = MediaExtractor::Create(source, mime);
    ALOGI("mime:%s", mime);
    if (mOriginalExtractor == NULL) {
        ALOGE("origi extractor is NULL");
    }
}

PRFakeExtractor::~PRFakeExtractor() {
}

size_t PRFakeExtractor::countTracks() {
    return mOriginalExtractor->countTracks();
}

sp<MediaSource> PRFakeExtractor::getTrack(size_t index) {
    sp<MediaSource> originalMediaSource = mOriginalExtractor->getTrack(index);
        const char *mime = NULL;
        CHECK(originalMediaSource->getFormat()->findCString(kKeyMIMEType, &mime));
        if (!strncasecmp("video/", mime, 6)) {
            ALOGI("playReady video track set kKeyRequiresSecureBuffers");
            originalMediaSource->getFormat()->setInt32(kKeyRequiresSecureBuffers, true);
        }
        return new PRFakeSource(originalMediaSource);
}

sp<MetaData> PRFakeExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    return mOriginalExtractor->getTrackMetaData(index, flags);
}

sp<MetaData> PRFakeExtractor::getMetaData() {
    return mOriginalExtractor->getMetaData();
}

bool SniffPRFake(
    const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    if (isFakeMode()) {
        uint8_t header[32];

        ssize_t n = source->readAt(0, header, sizeof(header));
        if (n < (ssize_t)sizeof(header)) {
            return false;
        }

        const uint8_t PlayReadyFakeID[12] = {
            '_', 'P', '_', 'R', '_', 'F', '_', 'M', '_', 'S', '_', 'V'};
        if (!memcmp((uint8_t *)header+4, "ftyp", 4) &&
            !memcmp((uint8_t *)header+8, PlayReadyFakeID, sizeof(PlayReadyFakeID))) {
            *mimeType = String8("prfakemode+") + MEDIA_MIMETYPE_CONTAINER_MPEG4;
            *confidence = 10.0f;
            ALOGI("Sniff PlayReady Fake File %s(),line:%d", __FUNCTION__, __LINE__);
            return true;
        }
    }

    return false;
}
}  // namespace android
#endif
