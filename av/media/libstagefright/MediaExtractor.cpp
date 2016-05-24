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
#define LOG_TAG "MediaExtractor"
#include <utils/Log.h>

#include "include/AMRExtractor.h"
#include "include/MP3Extractor.h"
#include "include/MPEG4Extractor.h"
#include "include/WAVExtractor.h"
#include "include/OggExtractor.h"
#include "include/MPEG2PSExtractor.h"
#include "include/MPEG2TSExtractor.h"
#include "include/DRMExtractor.h"
#include "include/WVMExtractor.h"
#include "include/FLACExtractor.h"
#ifndef MTK_AOSP_ENHANCEMENT
#include "include/AACExtractor.h"
#else
#include "MtkAACExtractor.h"
#endif
#include "include/MidiExtractor.h"

#include "matroska/MatroskaExtractor.h"

#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MetaData.h>
#include <utils/String8.h>

#ifdef MTK_AOSP_ENHANCEMENT
#include <MtkSDPExtractor.h>
#ifdef MTK_FLV_PLAYBACK_SUPPORT
#include <MtkFLVExtractor.h>
#endif
#include <dlfcn.h>
#ifdef MTK_AUDIO_APE_SUPPORT
#include "include/APEExtractor.h"
#endif  // MTK_AUDIO_APE_SUPPORT
#ifdef MTK_AUDIO_ALAC_SUPPORT
#include "include/CAFExtractor.h"
#endif  // MTK_AUDIO_ALAC_SUPPORT
#ifdef MTK_AVI_PLAYBACK_SUPPORT
#include <MtkAVIExtractor.h>
#endif  // MTK_AVI_PLAYBACK_SUPPORT
#ifdef MTK_WMV_PLAYBACK_SUPPORT
#include <ASFExtractor.h>
#endif
#ifdef MTK_DRM_APP
#include <drm/drm_framework_common.h>
#include <drm/DrmManagerClient.h>
#endif
#ifdef MTK_OGM_PLAYBACK_SUPPORT
#include <OgmExtractor.h>
#endif
#ifdef MTK_ELEMENT_STREAM_SUPPORT
#include <ESExtractor.h>
#endif

#if defined(MTK_PLAYREADY_SUPPORT) && defined(MTK_PLAYREADY_FAKEMODE)
#include <PRFakeExtractor.h>
#endif
#endif  // #ifdef MTK_AOSP_ENHANCEMENT

namespace android {

sp<MetaData> MediaExtractor::getMetaData() {
    return new MetaData;
}

uint32_t MediaExtractor::flags() const {
    return CAN_SEEK_BACKWARD | CAN_SEEK_FORWARD | CAN_PAUSE | CAN_SEEK;
}

// static
sp<MediaExtractor> MediaExtractor::Create(
        const sp<DataSource> &source, const char *mime) {
    sp<AMessage> meta;
    ALOGD("JB +MediaExtractor::Create");
    String8 tmp;
    if (mime == NULL) {
        float confidence;
        if (!source->sniff(&tmp, &confidence, &meta)) {
            ALOGV("FAILED to autodetect media content.");

            return NULL;
        }

        mime = tmp.string();
        ALOGI("Autodetected media content as '%s' with confidence %.2f",
             mime, confidence);
    }

    bool isDrm = false;
    // DRM MIME type syntax is "drm+type+original" where
    // type is "es_based" or "container_based" and
    // original is the content's cleartext MIME type
    if (!strncmp(mime, "drm+", 4)) {
        const char *originalMime = strchr(mime+4, '+');
        if (originalMime == NULL) {
            // second + not found
            return NULL;
        }
        ++originalMime;
        if (!strncmp(mime, "drm+es_based+", 13)) {
            // DRMExtractor sets container metadata kKeyIsDRM to 1
            return new DRMExtractor(source, originalMime);
        } else if (!strncmp(mime, "drm+container_based+", 20)) {
            mime = originalMime;
            isDrm = true;
        } else {
            return NULL;
        }
    }

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_PLAYREADY_SUPPORT) && defined(MTK_PLAYREADY_FAKEMODE)
    if (!strncmp(mime, "prfakemode+", 11)) {
        const char *originalMime = mime+11;
        if (originalMime == NULL) {
            return NULL;
        }
        return new PRFakeExtractor(source, originalMime);
    }
#endif
    // M: add for OMA DRM v1.0 implementation
    // explanation:
    // for an OMA DRM v1.0 file, if it can be sniffed successfully, the mime type
    // would be replaced by the actual type (e.g. image/jpeg) instead of drm+containder_based+<original_mime>
    // Thus, the following code checks for decrypt handle and set isDrm to true
    // so that it's handled correctly in AwesomePlayer.
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
    DrmManagerClient *drmManagerClient = NULL;
    sp<DecryptHandle> decryptHandle;
    source->getDrmInfo(decryptHandle, &drmManagerClient);
    // mark isDrm as true for OMA DRM v1.0 file
    // the same judgement as in FileSource::flags()
    if (decryptHandle.get() != NULL
            && DecryptApiType::CONTAINER_BASED == decryptHandle->decryptApiType) {
        isDrm = true;
    }
#endif
#endif

#ifdef MTK_AOSP_ENHANCEMENT
    sp<MediaExtractor> ret;
#else
    MediaExtractor *ret = NULL;
#endif
#ifdef  MTK_PLAYREADY_SUPPORT
    if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG4)
        || !strcasecmp(mime, "audio/mp4") || !strcasecmp(mime, "video/ismv")
        || !strcasecmp(mime, "audio/isma")) {
#else
    if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG4)
            || !strcasecmp(mime, "audio/mp4")) {
#endif
        ret = new MPEG4Extractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_MPEG)) {
        ret = new MP3Extractor(source, meta);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_NB)
            || !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_WB)) {
        ret = new AMRExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_FLAC)) {
        ret = new FLACExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_WAV)) {
        ret = new WAVExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_OGG)) {
        ret = new OggExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MATROSKA)) {
        ret = new MatroskaExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG2TS)) {
        ret = new MPEG2TSExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_WVM)) {
        // Return now.  WVExtractor should not have the DrmFlag set in the block below.
        return new WVMExtractor(source);
#ifndef MTK_AOSP_ENHANCEMENT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC_ADTS)) {
        ret = new AACExtractor(source, meta);
#else
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC) ||
            !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC_ADTS)) {
        ret = new MtkAACExtractor(source, meta);
#endif
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG2PS)) {
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_MTKPS_PLAYBACK_SUPPORT
        ret = new MPEG2PSExtractor(source);
#else  // MTK_MTKPS_PLAYBACK_SUPPORT
        ALOGD(" MediaExtractor::is PS file, not support playing now");
        ret = NULL;
#endif  // MTK_MTKPS_PLAYBACK_SUPPORT
#else   // #ifdef MTK_AOSP_ENHANCEMENT
        ret = new MPEG2PSExtractor(source);
#endif  // #ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_FLV_PLAYBACK_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_FLV)) {
        ret = new FLVExtractor(source);
#endif
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_APPLICATION_SDP)) {
        ret = new MtkSDPExtractor(source);
#ifdef MTK_AVI_PLAYBACK_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_AVI)) {
        ret = new MtkAVIExtractor(source);
#endif
#ifdef MTK_WMV_PLAYBACK_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_ASF)) {
        ret  = new ASFExtractor(source);
#endif
#ifdef MTK_AUDIO_APE_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_APE)) {
        ret = new APEExtractor(source, meta);
#endif
#ifdef MTK_AUDIO_ALAC_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_ALAC)) {
        ret = new CAFExtractor(source, meta);
#endif
#ifdef MTK_OGM_PLAYBACK_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_OGM)) {
        ret = new OgmExtractor(source);
#endif  // #ifdef MTK_OGM_PLAYBACK_SUPPORT
#ifdef MTK_ELEMENT_STREAM_SUPPORT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_ELEMENT_STREAM)) {
        ret = new ESExtractor(source);
#endif
#endif  // MTK_AOSP_ENHANCEMENT
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_MIDI)) {
        ret = new MidiExtractor(source);
    }
    if (ret != NULL) {
       if (isDrm) {
           ret->setDrmFlag(true);
       } else {
           ret->setDrmFlag(false);
       }
    }
    ALOGD("JB -MediaExtractor::Create");
    return ret;
}

}  // namespace android
