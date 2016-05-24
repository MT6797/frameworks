/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "StagefrightMetadataRetriever"

#include <inttypes.h>

#include <utils/Log.h>
#include <gui/Surface.h>

#include "include/StagefrightMetadataRetriever.h"

#include <media/ICrypto.h>
#include <media/IMediaHTTPService.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/ColorConverter.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/Utils.h>

#include <CharacterEncodingDetector.h>

#ifdef MTK_AOSP_ENHANCEMENT
#undef ALOGV
#define ALOGV ALOGD
#endif  

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
#include <drm/DrmMtkDef.h>
#include <drm/DrmMtkUtil.h>
#include <utils/String8.h>
#endif
#include "FileSourceProxy.h"
#define ATRACE_TAG ATRACE_TAG_VIDEO
#include <utils/Trace.h>
#endif

namespace android {

static const int64_t kBufferTimeOutUs = 30000ll; // 30 msec
static const size_t kRetryCount = 20; // must be >0

StagefrightMetadataRetriever::StagefrightMetadataRetriever()
    : mParsedMetaData(false),
      mAlbumArt(NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
#endif
    ALOGV("StagefrightMetadataRetriever()");

    DataSource::RegisterDefaultSniffers();
    CHECK_EQ(mClient.connect(), (status_t)OK);
}

StagefrightMetadataRetriever::~StagefrightMetadataRetriever() {
    ALOGV("~StagefrightMetadataRetriever()");
    clearMetadata();
#ifdef MTK_AOSP_ENHANCEMENT
		ATRACE_CALL();
#endif
    mClient.disconnect();
}

status_t StagefrightMetadataRetriever::setDataSource(
        const sp<IMediaHTTPService> &httpService,
        const char *uri,
        const KeyedVector<String8, String8> *headers) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
#endif        
    ALOGV("setDataSource(%s)", uri);

    clearMetadata();
    mSource = DataSource::CreateFromURI(httpService, uri, headers);

    if (mSource == NULL) {
        ALOGE("Unable to create data source for '%s'.", uri);
        return UNKNOWN_ERROR;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    const char * sniffedMIME = NULL;
    if ((!strncasecmp("/system/media/audio/", uri, 20)) && (strcasestr(uri,".ogg") != NULL))
    {
         sniffedMIME = MEDIA_MIMETYPE_CONTAINER_OGG;
    }
    mExtractor = MediaExtractor::Create(mSource, sniffedMIME);
#else
    mExtractor = MediaExtractor::Create(mSource);
#endif

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
    // after it attempts to create extractor: for .dcf file with invalid rights,
    //   the mExtractor will be NULL. We need to return OK here directly.
    if ((mSource->flags() & OMADrmFlag) != 0
        || (mExtractor == NULL && DrmMtkUtil::isDcf(String8(uri)))) {
        // we assume it's file path name - for OMA DRM v1
        ALOGD("setDataSource() : it is a OMA DRM v1 .dcf file. return OK");
        return OK;
    }
#endif
#endif 

    if (mExtractor == NULL) {
        ALOGE("Unable to instantiate an extractor for '%s'.", uri);

        mSource.clear();

        return UNKNOWN_ERROR;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    if (mExtractor->countTracks() == 0) {
	    	ALOGW("Track number is 0");
	        return UNKNOWN_ERROR;
    }
#endif

    return OK;
}

// Warning caller retains ownership of the filedescriptor! Dup it if necessary.
status_t StagefrightMetadataRetriever::setDataSource(
        int fd, int64_t offset, int64_t length) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
	int64_t tracetime = systemTime()/1000;
#endif         
    fd = dup(fd);

    ALOGV("setDataSource(%d, %" PRId64 ", %" PRId64 ")", fd, offset, length);
#ifdef MTK_AOSP_ENHANCEMENT
	char buffer[256];
	char linkto[256];
	memset(buffer, 0, 256);
	memset(linkto, 0, 256);
	sprintf(buffer, "/proc/%d/fd/%d", gettid(), fd);
	int len = 0;
	len = readlink(buffer, linkto, sizeof(linkto)-1);
	if(len >= 5)
	{
		linkto[len]=0;
		ALOGD("fd=%d,path=%s",fd,linkto);
	}

#endif

    clearMetadata();
    mSource = new FileSource(fd, offset, length);
/// M: add for the stop file cache ALPS01481978 @{
#ifdef MTK_AOSP_ENHANCEMENT
    extern FileSourceProxy gFileSourceProxy;
    gFileSourceProxy.unregisterFd(fd);
#endif
/// }@

    status_t err;
    if ((err = mSource->initCheck()) != OK) {
        mSource.clear();
#ifdef MTK_AOSP_ENHANCEMENT
				ALOGW("mSource initCheck fail err=%d",err);
#endif
        return err;
    }

#ifdef MTK_AOSP_ENHANCEMENT
		String8 tmp;
		if( mSource->fastsniff(fd, &tmp))
		{
			const char *sniffedMIME = tmp.string();
			mExtractor = MediaExtractor::Create(mSource, sniffedMIME);
		}
		else
#endif
    mExtractor = MediaExtractor::Create(mSource);

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
	// OMA DRM v1 implementation:
    // after it attempts to create extractor: for .dcf file with invalid rights,
    //   the mExtractor will be NULL. We need to return OK here directly.
    if ((mSource->flags() & OMADrmFlag) != 0
        || (mExtractor == NULL && DrmMtkUtil::isDcf(fd))) {
        ALOGD("setDataSource() : it is a OMA DRM v1 .dcf file. return OK");
        return OK;
    }
#endif
#endif //#ifndef ANDROID_DEFAULT_CODE
    if (mExtractor == NULL) {
        mSource.clear();
#ifdef MTK_AOSP_ENHANCEMENT
				 ALOGE("Unable to instantiate an extractor for '%d'.", fd);
#endif
        return UNKNOWN_ERROR;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    if (mExtractor->countTracks() == 0) {
    	ALOGW("Track number is 0");
        return UNKNOWN_ERROR;
    }
	int64_t tracetime_end = systemTime()/1000;
    ALOGD("setdatasource time %lld ms",(long long)(tracetime_end-tracetime)/1000);
#endif

    return OK;
}

status_t StagefrightMetadataRetriever::setDataSource(
        const sp<DataSource>& source) {
    ALOGV("setDataSource(DataSource)");

    clearMetadata();
    mSource = source;
    mExtractor = MediaExtractor::Create(mSource);

    if (mExtractor == NULL) {
        ALOGE("Failed to instantiate a MediaExtractor.");
        mSource.clear();
        return UNKNOWN_ERROR;
    }

    return OK;
}

static VideoFrame *extractVideoFrame(
        const char *componentName,
        const sp<MetaData> &trackMeta,
        const sp<MediaSource> &source,
        int64_t frameTimeUs,
        int seekMode) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
	int64_t tracetime_0 = systemTime()/1000;
    ALOGD("extractVideoFrame");
#endif

    sp<MetaData> format = source->getFormat();

    sp<AMessage> videoFormat;
    convertMetaDataToMessage(trackMeta, &videoFormat);

#ifdef MTK_AOSP_ENHANCEMENT
	int64_t tracetime_1 = systemTime()/1000;

    if(source == NULL || format == NULL) {
		ALOGV("MetaData is NULL.");
		return NULL;
	}
    if(videoFormat == NULL) {
        ALOGV("videoFormat is NULL.");
        return NULL;
    }
#endif
    // TODO: Use Flexible color instead
    videoFormat->setInt32("color-format", OMX_COLOR_FormatYUV420Planar);

    status_t err;
    sp<ALooper> looper = new ALooper;
    looper->start();
    sp<MediaCodec> decoder = MediaCodec::CreateByComponentName(
            looper, componentName, &err);

    if (decoder.get() == NULL || err != OK) {
        ALOGW("Failed to instantiate decoder [%s]", componentName);
        return NULL;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    err = decoder->configure(videoFormat, NULL /* surface */, NULL /* crypto */,
                             MediaCodec::CONFIGURE_FLAG_ENABLE_THUMBNAIL_OPTIMIZATION /* flags */);
#else
    err = decoder->configure(videoFormat, NULL /* surface */, NULL /* crypto */, 0 /* flags */);
#endif
#ifdef MTK_AOSP_ENHANCEMENT	
	int64_t tracetime_2 = systemTime()/1000;
#endif
    if (err != OK) {
        ALOGW("configure returned error %d (%s)", err, asString(err));
        decoder->release();
        return NULL;
    }

    err = decoder->start();
    if (err != OK) {
        ALOGW("start returned error %d (%s)", err, asString(err));
        decoder->release();
        return NULL;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_3 = systemTime()/1000;
#endif
    MediaSource::ReadOptions options;
    if (seekMode < MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC ||
        seekMode > MediaSource::ReadOptions::SEEK_CLOSEST) {

        ALOGE("Unknown seek mode: %d", seekMode);
        decoder->release();
        return NULL;
    }

    MediaSource::ReadOptions::SeekMode mode =
            static_cast<MediaSource::ReadOptions::SeekMode>(seekMode);

    int64_t thumbNailTime;
    if (frameTimeUs < 0) {
        if (!trackMeta->findInt64(kKeyThumbnailTime, &thumbNailTime)
                || thumbNailTime < 0) {
            thumbNailTime = 0;
        }
        options.setSeekTo(thumbNailTime, mode);
    } else {
        thumbNailTime = -1;
        options.setSeekTo(frameTimeUs, mode);
    }

    err = source->start();
    if (err != OK) {
        ALOGW("source failed to start: %d (%s)", err, asString(err));
        decoder->release();
        return NULL;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_4 = systemTime()/1000;
#endif
    Vector<sp<ABuffer> > inputBuffers;
    err = decoder->getInputBuffers(&inputBuffers);
    if (err != OK) {
        ALOGW("failed to get input buffers: %d (%s)", err, asString(err));
#ifdef MTK_AOSP_ENHANCEMENT
        source->stop();
#endif
        decoder->release();
        return NULL;
    }

    Vector<sp<ABuffer> > outputBuffers;
    err = decoder->getOutputBuffers(&outputBuffers);
    if (err != OK) {
        ALOGW("failed to get output buffers: %d (%s)", err, asString(err));
#ifdef MTK_AOSP_ENHANCEMENT
        source->stop();
#endif
        decoder->release();
        return NULL;
    }
#ifdef MTK_AOSP_ENHANCEMENT	
    int64_t tracetime_5 = systemTime()/1000;
    ALOGD("get input and output buffer time %lld",(long long)(tracetime_5- tracetime_4));
#endif

    sp<AMessage> outputFormat = NULL;
    bool haveMoreInputs = true;
    size_t index, offset, size;
    int64_t timeUs;
    size_t retriesLeft = kRetryCount;
    bool done = false;

    do {
        size_t inputIndex = -1;
        int64_t ptsUs = 0ll;
        uint32_t flags = 0;
        sp<ABuffer> codecBuffer = NULL;

        while (haveMoreInputs) {
            err = decoder->dequeueInputBuffer(&inputIndex, kBufferTimeOutUs);
            if (err != OK) {
                ALOGW("Timed out waiting for input");
                if (retriesLeft) {
                    err = OK;
                }
                break;
            }
            codecBuffer = inputBuffers[inputIndex];

            MediaBuffer *mediaBuffer = NULL;

            err = source->read(&mediaBuffer, &options);
            options.clearSeekTo();
            if (err != OK) {
                ALOGW("Input Error or EOS");
                haveMoreInputs = false;

                break;
            }

            if (mediaBuffer->range_length() > codecBuffer->capacity()) {
                ALOGE("buffer size (%zu) too large for codec input size (%zu)",
                        mediaBuffer->range_length(), codecBuffer->capacity());
                err = BAD_VALUE;
            } else {
                codecBuffer->setRange(0, mediaBuffer->range_length());

                CHECK(mediaBuffer->meta_data()->findInt64(kKeyTime, &ptsUs));
                memcpy(codecBuffer->data(),
                        (const uint8_t*)mediaBuffer->data() + mediaBuffer->range_offset(),
                        mediaBuffer->range_length());
            }

            mediaBuffer->release();
            break;
        }

#ifdef MTK_AOSP_ENHANCEMENT
        if ((err == OK || err == ERROR_END_OF_STREAM) && inputIndex < inputBuffers.size()) {
            if(err == OK){
                ALOGV("QueueInput: size=%zu ts=%" PRId64 " us flags=%x",
                        codecBuffer->size(), ptsUs, flags);
                err = decoder->queueInputBuffer(
                        inputIndex,
                        codecBuffer->offset(),
                        codecBuffer->size(),
                        ptsUs,
                        flags);
            }else{
                ALOGV("Input EOS buffer");
                err = decoder->queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        0,
                        MediaCodec::BUFFER_FLAG_EOS);
            }

            // we don't expect an output from codec config buffer
            if (flags & MediaCodec::BUFFER_FLAG_CODECCONFIG) {
                continue;
            }
        }
#else
        if (err == OK && inputIndex < inputBuffers.size()) {
            ALOGV("QueueInput: size=%zu ts=%" PRId64 " us flags=%x",
                    codecBuffer->size(), ptsUs, flags);
            err = decoder->queueInputBuffer(
                    inputIndex,
                    codecBuffer->offset(),
                    codecBuffer->size(),
                    ptsUs,
                    flags);

            // we don't expect an output from codec config buffer
            if (flags & MediaCodec::BUFFER_FLAG_CODECCONFIG) {
                continue;
            }
        }
#endif

        while (err == OK) {
            // wait for a decoded buffer
            err = decoder->dequeueOutputBuffer(
                    &index,
                    &offset,
                    &size,
                    &timeUs,
                    &flags,
                    kBufferTimeOutUs);

            if (err == INFO_FORMAT_CHANGED) {
                ALOGV("Received format change");
                err = decoder->getOutputFormat(&outputFormat);
            } else if (err == INFO_OUTPUT_BUFFERS_CHANGED) {
                ALOGV("Output buffers changed");
                err = decoder->getOutputBuffers(&outputBuffers);
            } else {
                if (err == -EAGAIN /* INFO_TRY_AGAIN_LATER */ && --retriesLeft > 0) {
                    ALOGV("Timed-out waiting for output.. retries left = %zu", retriesLeft);
                    err = OK;
                } else if (err == OK) {
                    ALOGV("Received an output buffer");
                    done = true;
                } else {
                    ALOGW("Received error %d (%s) instead of output", err, asString(err));
                    done = true;
                }
                break;
            }
        }
    } while (err == OK && !done);

#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_6 = systemTime()/1000;
    ALOGD("decode frame time %lld",(long long)(tracetime_6- tracetime_5));
#endif
    if (err != OK || size <= 0 || outputFormat == NULL) {
        ALOGE("Failed to decode thumbnail frame");
        source->stop();
        decoder->stop();
        decoder->release();
        return NULL;
    }

    ALOGV("successfully decoded video frame.");
    sp<ABuffer> videoFrameBuffer = outputBuffers.itemAt(index);

    if (thumbNailTime >= 0) {
        if (timeUs != thumbNailTime) {
            AString mime;
            CHECK(outputFormat->findString("mime", &mime));

            ALOGV("thumbNailTime = %lld us, timeUs = %lld us, mime = %s",
                    (long long)thumbNailTime, (long long)timeUs, mime.c_str());
        }
    }

    int32_t width, height;
    CHECK(outputFormat->findInt32("width", &width));
    CHECK(outputFormat->findInt32("height", &height));

#ifdef MTK_AOSP_ENHANCEMENT
	int32_t Stridewidth,SliceHeight;
    CHECK(outputFormat->findInt32("stride", &Stridewidth));
    CHECK(outputFormat->findInt32("slice-height", &SliceHeight));
	ALOGD("kKeyWidth=%d,kKeyHeight=%d",width,height);
	ALOGD("Stridewidth=%d,SliceHeight=%d",Stridewidth,SliceHeight);
#endif
    int32_t crop_left, crop_top, crop_right, crop_bottom;
    if (!outputFormat->findRect("crop", &crop_left, &crop_top, &crop_right, &crop_bottom)) {
        crop_left = crop_top = 0;
        crop_right = width - 1;
        crop_bottom = height - 1;
    }

    int32_t rotationAngle;
    if (!trackMeta->findInt32(kKeyRotation, &rotationAngle)) {
        rotationAngle = 0;  // By default, no rotation
    }

    VideoFrame *frame = new VideoFrame;
    frame->mWidth = crop_right - crop_left + 1;
    frame->mHeight = crop_bottom - crop_top + 1;
    frame->mDisplayWidth = frame->mWidth;
    frame->mDisplayHeight = frame->mHeight;

#ifdef MTK_HIGH_QUALITY_THUMBNAIL
    frame->mSize = frame->mWidth * frame->mHeight * 4;
#else
    frame->mSize = frame->mWidth * frame->mHeight * 2;
#endif

    frame->mData = new uint8_t[frame->mSize];
    frame->mRotationAngle = rotationAngle;

    int32_t sarWidth, sarHeight;
    if (trackMeta->findInt32(kKeySARWidth, &sarWidth)
            && trackMeta->findInt32(kKeySARHeight, &sarHeight)
            && sarHeight != 0) {
        frame->mDisplayWidth = (frame->mDisplayWidth * sarWidth) / sarHeight;
    }

    int32_t srcFormat;
    CHECK(outputFormat->findInt32("color-format", &srcFormat));

#ifdef MTK_AOSP_ENHANCEMENT
		width=Stridewidth;
		height=SliceHeight;
		//crop_right = width - 1;
		//crop_bottom = height - 1;
    int64_t tracetime_7 = systemTime()/1000;
#endif
#if 0
#ifdef MTK_AOSP_ENHANCEMENT
    int32_t crop_padding_left, crop_padding_top, crop_padding_right, crop_padding_bottom;
    if (!outputFormat->findRect(
        kKeyCropPaddingRect,
        &crop_padding_left, &crop_padding_top, &crop_padding_right, &crop_padding_bottom)) {
        ALOGE("kKeyCropPaddingRect not found\n");
        crop_padding_left = crop_padding_top = 0;
        crop_padding_right = width - 1;
        crop_padding_bottom = height - 1;
    }
    sp<MetaData> inputFormat = source->getFormat();
    const char *mime;
    if (inputFormat->findCString(kKeyMIMEType, &mime)) {
        ALOGD("width=%d, height=%d", width, height);
        if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_VP9)) {
            crop_left = crop_padding_left;
            crop_right = crop_padding_right;
            crop_top = crop_padding_top;
            crop_bottom = crop_padding_bottom;
        }
    }
#endif
#endif
#ifndef MTK_HIGH_QUALITY_THUMBNAIL
    ColorConverter converter((OMX_COLOR_FORMATTYPE)srcFormat, OMX_COLOR_Format16bitRGB565);
#else
    ColorConverter converter((OMX_COLOR_FORMATTYPE)srcFormat, OMX_COLOR_Format32bitARGB8888);
#endif

    if (converter.isValid()) {
        err = converter.convert(
                (const uint8_t *)videoFrameBuffer->data(),
                width, height,
                crop_left, crop_top, crop_right, crop_bottom,
                frame->mData,
                frame->mWidth,
                frame->mHeight,
                0, 0, frame->mWidth - 1, frame->mHeight - 1);
    } else {
        ALOGE("Unable to convert from format 0x%08x to RGB565", srcFormat);

        err = ERROR_UNSUPPORTED;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_8 = systemTime()/1000;
#endif

    videoFrameBuffer.clear();
    source->stop();
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_9 = systemTime()/1000;
#endif
    decoder->releaseOutputBuffer(index);
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_10= systemTime()/1000;
#endif
    decoder->stop();
    decoder->release();

    if (err != OK) {
        ALOGE("Colorconverter failed to convert frame.");

        delete frame;
        frame = NULL;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_11 = systemTime()/1000;
    ALOGD("decoderframe time summary(us): getformat: %lld,decoder create: %lld,decoder start: %lld, source start: %lld,\
        colorconvert: %lld,source stop: %lld,buffer release:%lld,decoder stop: %lld,total: %lld",(long long)(tracetime_1-tracetime_0),(long long)(tracetime_2-tracetime_1),\
        (long long)(tracetime_3-tracetime_2),(long long)(tracetime_4-tracetime_3),(long long)(tracetime_8-tracetime_7),(long long)(tracetime_9-tracetime_8),(long long)(tracetime_10-tracetime_9),\
        (long long)(tracetime_11-tracetime_10),(long long)(tracetime_11-tracetime_0));
#endif

    return frame;
}

VideoFrame *StagefrightMetadataRetriever::getFrameAtTime(
        int64_t timeUs, int option) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
    int64_t tracetime_0 = systemTime()/1000;
#endif 
    ALOGV("getFrameAtTime: %" PRId64 " us option: %d", timeUs, option);

    if (mExtractor.get() == NULL) {
        ALOGV("no extractor.");
        return NULL;
    }

    sp<MetaData> fileMeta = mExtractor->getMetaData();

    if (fileMeta == NULL) {
        ALOGV("extractor doesn't publish metadata, failed to initialize?");
        return NULL;
    }

    int32_t drm = 0;
    if (fileMeta->findInt32(kKeyIsDRM, &drm) && drm != 0) {
        ALOGE("frame grab not allowed.");
        return NULL;
    }

    size_t n = mExtractor->countTracks();
    size_t i;
    for (i = 0; i < n; ++i) {
        sp<MetaData> meta = mExtractor->getTrackMetaData(i);
#ifdef MTK_AOSP_ENHANCEMENT
        if(meta.get() == NULL) return NULL;
#endif // #ifndef ANDROID_DEFAULT_CODE
        const char *mime;
#ifdef MTK_AOSP_ENHANCEMENT
         /* temp workaround, will back to CHECK */
        if(!meta->findCString(kKeyMIMEType, &mime)){
			ALOGE("kKeyMIMEType is not setted");
			return NULL;
        }
#else
        CHECK(meta->findCString(kKeyMIMEType, &mime));
#endif
        if (!strncasecmp(mime, "video/", 6)) {
            break;
        }
    }

    if (i == n) {
        ALOGV("no video track found.");
        return NULL;
    }

    sp<MetaData> trackMeta = mExtractor->getTrackMetaData(
            i, MediaExtractor::kIncludeExtensiveMetaData);

    sp<MediaSource> source = mExtractor->getTrack(i);

    if (source.get() == NULL) {
        ALOGV("unable to instantiate video track.");
        return NULL;
    }

    const void *data;
    uint32_t type;
    size_t dataSize;
    if (fileMeta->findData(kKeyAlbumArt, &type, &data, &dataSize)
            && mAlbumArt == NULL) {
        mAlbumArt = MediaAlbumArt::fromData(dataSize, data);
    }

    const char *mime;
    CHECK(trackMeta->findCString(kKeyMIMEType, &mime));

    Vector<OMXCodec::CodecNameAndQuirks> matchingCodecs;
#ifdef MTK_AOSP_ENHANCEMENT
    OMXCodec::findMatchingCodecs(
            mime,
            false, /* encoder */
            NULL, /* matchComponentName */
            0,
            &matchingCodecs);
    ALOGD("matchingCodecs size is %zu", matchingCodecs.size());
#else
    OMXCodec::findMatchingCodecs(
            mime,
            false, /* encoder */
            NULL, /* matchComponentName */
            OMXCodec::kPreferSoftwareCodecs,
            &matchingCodecs);
#endif
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_1 = systemTime()/1000;
#endif

    for (size_t i = 0; i < matchingCodecs.size(); ++i) {
        const char *componentName = matchingCodecs[i].mName.string();
        VideoFrame *frame =
            extractVideoFrame(componentName, trackMeta, source, timeUs, option);

        if (frame != NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
    int64_t tracetime_2 = systemTime()/1000;
        ALOGD("getframeattime time summary(us),extract time: %lld,get frame time %lld,total time: %lld",
            (long long)(tracetime_1-tracetime_0),(long long)(tracetime_2-tracetime_1),(long long)(tracetime_2-tracetime_0));
#endif
            return frame;
        }
        ALOGV("%s failed to extract thumbnail, trying next decoder.", componentName);
    }

    return NULL;
}

MediaAlbumArt *StagefrightMetadataRetriever::extractAlbumArt() {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
#endif 	
    ALOGV("extractAlbumArt (extractor: %s)", mExtractor.get() != NULL ? "YES" : "NO");

    if (mExtractor == NULL) {
        return NULL;
    }

    if (!mParsedMetaData) {
        parseMetaData();

        mParsedMetaData = true;
    }

    if (mAlbumArt) {
        return mAlbumArt->clone();
    }

    return NULL;
}

const char *StagefrightMetadataRetriever::extractMetadata(int keyCode) {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
#endif 	
    if (mExtractor == NULL) {
        return NULL;
    }

    if (!mParsedMetaData) {
        parseMetaData();

        mParsedMetaData = true;
    }

    ssize_t index = mMetaData.indexOfKey(keyCode);

    if (index < 0) {
        return NULL;
    }

    // const char, return strdup will cause a memory leak
    return mMetaData.valueAt(index).string();

}

void StagefrightMetadataRetriever::parseMetaData() {
#ifdef MTK_AOSP_ENHANCEMENT
	ATRACE_CALL();
#ifdef MTK_DRM_APP
    // OMA DRM v1 implementation: NULL extractor means .dcf without valid rights
    if (mExtractor.get() == NULL) {
        ALOGD("Invalid rights for OMA DRM v1 file. NULL extractor and cannot parse meta data.");
        return;
    }
#endif
#endif 

    sp<MetaData> meta = mExtractor->getMetaData();

    if (meta == NULL) {
        ALOGV("extractor doesn't publish metadata, failed to initialize?");
        return;
    }

    struct Map {
        int from;
        int to;
        const char *name;
    };
    static const Map kMap[] = {
        { kKeyMIMEType, METADATA_KEY_MIMETYPE, NULL },
        { kKeyCDTrackNumber, METADATA_KEY_CD_TRACK_NUMBER, "tracknumber" },
        { kKeyDiscNumber, METADATA_KEY_DISC_NUMBER, "discnumber" },
        { kKeyAlbum, METADATA_KEY_ALBUM, "album" },
        { kKeyArtist, METADATA_KEY_ARTIST, "artist" },
        { kKeyAlbumArtist, METADATA_KEY_ALBUMARTIST, "albumartist" },
        { kKeyAuthor, METADATA_KEY_AUTHOR, NULL },
        { kKeyComposer, METADATA_KEY_COMPOSER, "composer" },
        { kKeyDate, METADATA_KEY_DATE, NULL },
        { kKeyGenre, METADATA_KEY_GENRE, "genre" },
        { kKeyTitle, METADATA_KEY_TITLE, "title" },
        { kKeyYear, METADATA_KEY_YEAR, "year" },
        { kKeyWriter, METADATA_KEY_WRITER, "writer" },
        { kKeyCompilation, METADATA_KEY_COMPILATION, "compilation" },
        { kKeyLocation, METADATA_KEY_LOCATION, NULL },
    };

    static const size_t kNumMapEntries = sizeof(kMap) / sizeof(kMap[0]);

    CharacterEncodingDetector *detector = new CharacterEncodingDetector();

    for (size_t i = 0; i < kNumMapEntries; ++i) {
        const char *value;
        if (meta->findCString(kMap[i].from, &value)) {
            if (kMap[i].name) {
                // add to charset detector
                detector->addTag(kMap[i].name, value);
            } else {
                // directly add to output list
                mMetaData.add(kMap[i].to, String8(value));
            }
        }
    }

    detector->detectAndConvert();
    int size = detector->size();
    if (size) {
        for (int i = 0; i < size; i++) {
            const char *name;
            const char *value;
            detector->getTag(i, &name, &value);
            for (size_t j = 0; j < kNumMapEntries; ++j) {
                if (kMap[j].name && !strcmp(kMap[j].name, name)) {
                    mMetaData.add(kMap[j].to, String8(value));
                }
            }
        }
    }
    delete detector;

    const void *data;
    uint32_t type;
    size_t dataSize;
    if (meta->findData(kKeyAlbumArt, &type, &data, &dataSize)
            && mAlbumArt == NULL) {
        mAlbumArt = MediaAlbumArt::fromData(dataSize, data);
    }

    size_t numTracks = mExtractor->countTracks();

    char tmp[32];
    sprintf(tmp, "%zu", numTracks);

    mMetaData.add(METADATA_KEY_NUM_TRACKS, String8(tmp));

    float captureFps;
    if (meta->findFloat(kKeyCaptureFramerate, &captureFps)) {
        sprintf(tmp, "%f", captureFps);
        mMetaData.add(METADATA_KEY_CAPTURE_FRAMERATE, String8(tmp));
    }

    bool hasAudio = false;
    bool hasVideo = false;
    int32_t videoWidth = -1;
    int32_t videoHeight = -1;
    int32_t audioBitrate = -1;
    int32_t rotationAngle = -1;

#ifdef MTK_AOSP_ENHANCEMENT
	int32_t is_livephoto = 0;
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	int32_t slowmotion_speed = 0;
#endif
#endif
    // The overall duration is the duration of the longest track.
    int64_t maxDurationUs = 0;
    String8 timedTextLang;
    for (size_t i = 0; i < numTracks; ++i) {
        sp<MetaData> trackMeta = mExtractor->getTrackMetaData(i);

#ifdef MTK_AOSP_ENHANCEMENT
        if (trackMeta.get() == NULL) return ;
#endif
        int64_t durationUs;
        if (trackMeta->findInt64(kKeyDuration, &durationUs)) {
            if (durationUs > maxDurationUs) {
                maxDurationUs = durationUs;
            }
        }

        const char *mime;
        if (trackMeta->findCString(kKeyMIMEType, &mime)) {
            if (!hasAudio && !strncasecmp("audio/", mime, 6)) {
                hasAudio = true;

                if (!trackMeta->findInt32(kKeyBitRate, &audioBitrate)) {
                    audioBitrate = -1;
                }
            } else if (!hasVideo && !strncasecmp("video/", mime, 6)) {
                hasVideo = true;

                CHECK(trackMeta->findInt32(kKeyWidth, &videoWidth));
                CHECK(trackMeta->findInt32(kKeyHeight, &videoHeight));
                if (!trackMeta->findInt32(kKeyRotation, &rotationAngle)) {
                    rotationAngle = 0;
                }
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT			
				trackMeta->findInt32(kKeySlowMotionSpeedValue,&slowmotion_speed);
				ALOGD("parseMetaData: slowmotion_speed=%d",slowmotion_speed);
#endif
				trackMeta->findInt32(kKeyIsLivePhoto,&is_livephoto);
				ALOGD("parseMetaData: kKeyIsLivePhoto =%s",is_livephoto==false?"false":"true");
#endif
            } else if (!strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP)) {
                const char *lang;
                trackMeta->findCString(kKeyMediaLanguage, &lang);
                timedTextLang.append(String8(lang));
                timedTextLang.append(String8(":"));
            }
        }
    }

    // To save the language codes for all timed text tracks
    // If multiple text tracks present, the format will look
    // like "eng:chi"
    if (!timedTextLang.isEmpty()) {
        mMetaData.add(METADATA_KEY_TIMED_TEXT_LANGUAGES, timedTextLang);
    }

    // The duration value is a string representing the duration in ms.
    sprintf(tmp, "%" PRId64, (maxDurationUs + 500) / 1000);
    mMetaData.add(METADATA_KEY_DURATION, String8(tmp));

    if (hasAudio) {
        mMetaData.add(METADATA_KEY_HAS_AUDIO, String8("yes"));
    }

    if (hasVideo) {
        mMetaData.add(METADATA_KEY_HAS_VIDEO, String8("yes"));

        sprintf(tmp, "%d", videoWidth);
        mMetaData.add(METADATA_KEY_VIDEO_WIDTH, String8(tmp));

        sprintf(tmp, "%d", videoHeight);
        mMetaData.add(METADATA_KEY_VIDEO_HEIGHT, String8(tmp));

        sprintf(tmp, "%d", rotationAngle);
        mMetaData.add(METADATA_KEY_VIDEO_ROTATION, String8(tmp));
#ifdef MTK_AOSP_ENHANCEMENT
		sprintf(tmp,"%d",is_livephoto);
		mMetaData.add(METADATA_KEY_Is_LivePhoto, String8(tmp));
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT		
//		sprintf(tmp,"%d",slowmotion_speed);
        if(slowmotion_speed == 8)
            sprintf(tmp,"%d",8);
        else if(slowmotion_speed > 0)
            sprintf(tmp,"%d",4);
        else
            sprintf(tmp,"%d",0);
        mMetaData.add(METADATA_KEY_SlowMotion_SpeedValue, String8(tmp));
#endif		
#endif
    }

    if (numTracks == 1 && hasAudio && audioBitrate >= 0) {
        sprintf(tmp, "%d", audioBitrate);
        mMetaData.add(METADATA_KEY_BITRATE, String8(tmp));
    } else {
        off64_t sourceSize;
        if (mSource->getSize(&sourceSize) == OK) {
            int64_t avgBitRate = (int64_t)(sourceSize * 8E6 / maxDurationUs);

            sprintf(tmp, "%" PRId64, avgBitRate);
            mMetaData.add(METADATA_KEY_BITRATE, String8(tmp));
        }
    }

    if (numTracks == 1) {
        const char *fileMIME;
        CHECK(meta->findCString(kKeyMIMEType, &fileMIME));

        if (!strcasecmp(fileMIME, "video/x-matroska")) {
            sp<MetaData> trackMeta = mExtractor->getTrackMetaData(0);
#ifdef MTK_AOSP_ENHANCEMENT
            if (trackMeta.get() == NULL) return ;
#endif
            const char *trackMIME;
            CHECK(trackMeta->findCString(kKeyMIMEType, &trackMIME));

            if (!strncasecmp("audio/", trackMIME, 6)) {
                // The matroska file only contains a single audio track,
                // rewrite its mime type.
                mMetaData.add(
                        METADATA_KEY_MIMETYPE, String8("audio/x-matroska"));
            }
        }
    }

    // To check whether the media file is drm-protected
    if (mExtractor->getDrmFlag()) {
        mMetaData.add(METADATA_KEY_IS_DRM, String8("1"));
    }
}

void StagefrightMetadataRetriever::clearMetadata() {
    mParsedMetaData = false;
    mMetaData.clear();
    delete mAlbumArt;
    mAlbumArt = NULL;
}

}  // namespace android
