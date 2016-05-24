/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaPuller"
#include <utils/Log.h>

#include "MediaPuller.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#ifdef MTK_AOSP_ENHANCEMENT
#include "DataPathTrace.h"
#define ATRACE_TAG ATRACE_TAG_VIDEO
#include <utils/Trace.h>
#define WFD_LOGI(fmt,arg...) ALOGI(fmt,##arg)
#else
#define WFD_LOGI(fmt,arg...)
#endif
namespace android {

MediaPuller::MediaPuller(
        const sp<MediaSource> &source, const sp<AMessage> &notify)
    : mSource(source),
      mNotify(notify),
      mPullGeneration(0),
      mIsAudio(false),
      mPaused(false) {
    sp<MetaData> meta = source->getFormat();
    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    mIsAudio = !strncasecmp(mime, "audio/", 6);
#ifdef MTK_AOSP_ENHANCEMENT
      mFirstDeltaMs = -1;
#endif
}

MediaPuller::~MediaPuller() {
    ALOGD("~MediaPuller");
}

status_t MediaPuller::postSynchronouslyAndReturnError(
        const sp<AMessage> &msg) {
    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);

    if (err != OK) {
        return err;
    }

    if (!response->findInt32("err", &err)) {
        err = OK;
    }

    return err;
}

status_t MediaPuller::start() {
    WFD_LOGI("start++");
    return postSynchronouslyAndReturnError(new AMessage(kWhatStart, this));
}

void MediaPuller::stopAsync(const sp<AMessage> &notify) {
    WFD_LOGI("stopAsync");
    sp<AMessage> msg = new AMessage(kWhatStop, this);
    msg->setMessage("notify", notify);
    msg->post();
}

void MediaPuller::pause() {
    WFD_LOGI("pause++");
    (new AMessage(kWhatPause, this))->post();
}

void MediaPuller::resume() {
    WFD_LOGI("resume++");
    (new AMessage(kWhatResume, this))->post();
}

void MediaPuller::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatStart:
        {
            status_t err;
            WFD_LOGI("start mIsAudio=%d",mIsAudio);
            if (mIsAudio) {
                // This atrocity causes AudioSource to deliver absolute
                // systemTime() based timestamps (off by 1 us).
#ifdef MTK_AOSP_ENHANCEMENT
                ATRACE_BEGIN("AudioPuller, kWhatStart");
#endif
                sp<MetaData> params = new MetaData;
                params->setInt64(kKeyTime, 1ll);
                err = mSource->start(params.get());
            } else {
#ifdef MTK_AOSP_ENHANCEMENT
                ATRACE_BEGIN("VideoPuller, kWhatStart");
#endif
                err = mSource->start();
                if (err != OK) {
                    ALOGE("source failed to start w/ err %d", err);
                }
            }

            if (err == OK) {
                   WFD_LOGI("start done, start to schedulePull data");
                schedulePull();
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);

            sp<AReplyToken> replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            response->postReply(replyID);
#ifdef MTK_AOSP_ENHANCEMENT
            ATRACE_END();
#endif
            break;
        }

        case kWhatStop:
        {
            sp<MetaData> meta = mSource->getFormat();
            const char *tmp;
            CHECK(meta->findCString(kKeyMIMEType, &tmp));
            AString mime = tmp;

            ALOGI("MediaPuller(%s) stopping.", mime.c_str());
            mSource->stop();
            ALOGI("MediaPuller(%s) stopped.", mime.c_str());
            ++mPullGeneration;

            sp<AMessage> notify;
            CHECK(msg->findMessage("notify", &notify));
            notify->post();
            break;
        }

        case kWhatPull:
        {
            int32_t generation;
#ifdef MTK_AOSP_ENHANCEMENT
            mIsAudio?
            ATRACE_BEGIN("AudioPuller, kWhatPull"):
            ATRACE_BEGIN("VideoPuller, kWhatPull");
#endif
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mPullGeneration) {
                break;
            }

            MediaBuffer *mbuf;
            status_t err = mSource->read(&mbuf);

            if (mPaused) {
                if (err == OK) {
                    mbuf->release();
                    mbuf = NULL;
                }

                schedulePull();
                break;
            }

            if (err != OK) {
#ifdef MTK_AOSP_ENHANCEMENT
                if(err == 1){
                    //ALOGI("VP Repeat Buffer, do not pass it to encoder");
                    schedulePull();
                    break;
                }
#endif
                if (err == ERROR_END_OF_STREAM) {
                    ALOGI("stream ended.");
                } else {
                    ALOGE("error %d reading stream.", err);
                }
                WFD_LOGI("err=%d.post kWhatEOS",err);
                sp<AMessage> notify = mNotify->dup();
                notify->setInt32("what", kWhatEOS);
                notify->post();
            } else {
                int64_t timeUs;
                CHECK(mbuf->meta_data()->findInt64(kKeyTime, &timeUs));

                sp<ABuffer> accessUnit = new ABuffer(mbuf->range_length());

                memcpy(accessUnit->data(),
                       (const uint8_t *)mbuf->data() + mbuf->range_offset(),
                       mbuf->range_length());

                accessUnit->meta()->setInt64("timeUs", timeUs);

                if(mIsAudio){
                    static int64_t lasttime = 0;
                    int64_t ntime = ALooper::GetNowUs();
                    if((ntime - lasttime) > 50000)
                        ALOGE("#### now lasttime=%lld time=%lld  timeUs=%lld\n",
                             (long long)lasttime, (long long)ntime, (long long)timeUs);
                    lasttime = ntime;
                }

#ifdef MTK_AOSP_ENHANCEMENT
                read_pro(!mIsAudio,timeUs,mbuf,accessUnit);
#endif
                if (mIsAudio) {
                    mbuf->release();
                    mbuf = NULL;
                    //WFD_LOGI("[WFDP][%s] ,timestamp=%lld ms",mIsAudio?"audio":"video",timeUs/1000);
                    //Rock, remove log in L
                } else {
                    // video encoder will release MediaBuffer when done
                    // with underlying data.
                    //Rock, L migration 0930
                    //WFD_LOGI("[WFDP][%s] ,mediaBuffer=%p,timestamp=%lld ms",mIsAudio?"audio":"video",mbuf,timeUs/1000);
                    //Rock, remove log in L
                    accessUnit->setMediaBufferBase(mbuf);
                }

                sp<AMessage> notify = mNotify->dup();

                notify->setInt32("what", kWhatAccessUnit);
                notify->setBuffer("accessUnit", accessUnit);
                notify->post();

                if (mbuf != NULL) {
                    ALOGV("posted mbuf %p", mbuf);
                }

                schedulePull();
#ifdef MTK_AOSP_ENHANCEMENT
                ATRACE_END();
#endif
            }
            break;
        }

        case kWhatPause:
        {
            mPaused = true;
            break;
        }

        case kWhatResume:
        {
            mPaused = false;
            break;
        }

        default:
            TRESPASS();
    }
}

void MediaPuller::schedulePull() {
    sp<AMessage> msg = new AMessage(kWhatPull, this);
    msg->setInt32("generation", mPullGeneration);
    msg->post();
}

#ifdef MTK_AOSP_ENHANCEMENT
void MediaPuller::read_pro(bool /*isVideo*/,int64_t timeUs, MediaBuffer *mbuf,sp<ABuffer>& Abuf){
    mIsAudio?
    ATRACE_INT64("AudioPuller, TS:  ms", timeUs/1000):
    ATRACE_INT64("VideoPuller, TS:  ms", timeUs/1000);

    int32_t latencyToken = 0;
    if(mbuf->meta_data()->findInt32(kKeyWFDLatency, &latencyToken)){
        Abuf->meta()->setInt32("LatencyToken", latencyToken);
    }


    sp<WfdDebugInfo> debugInfo= defaultWfdDebugInfo();
    int64_t MpMs = ALooper::GetNowUs();
    debugInfo->addTimeInfoByKey(!mIsAudio , timeUs, "MpIn", MpMs/1000);

    int64_t NowMpDelta =0;

    NowMpDelta = (MpMs - timeUs)/1000;

    if(mFirstDeltaMs == -1){
        mFirstDeltaMs = NowMpDelta;
        ALOGE("[check Input ts and nowUs delta][%s],timestamp=%lld ms,[1th delta]=%lld ms",
        mIsAudio?"audio":"video",(long long)(timeUs/1000), (long long)NowMpDelta);
    }
    NowMpDelta = NowMpDelta - mFirstDeltaMs;

    if(NowMpDelta > 30ll || NowMpDelta < -30ll ){
        ALOGE("[check Input ts and nowUs delta][%s] ,timestamp=%lld ms,[delta]=%lld ms",
        mIsAudio?"audio":"video",(long long)(timeUs/1000),(long long)NowMpDelta);
    }

}

#endif
}  // namespace android

