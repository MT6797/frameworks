
#define LOG_TAG "MtkBSSource"
#define MTK_LOG_ENABLE 1
#include <utils/Log.h>
#include <cutils/log.h>
#include <utils/Condition.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/mediarecorder.h>
#include <cutils/properties.h>
//#include <media/MediaProfiles.h>

#include <MtkBSSource.h>

#define MBSLOGV(fmt, arg...)       ALOGV("[%s] " fmt, __FUNCTION__, ##arg)
#define MBSLOGD(fmt, arg...)       ALOGD("[%s] " fmt, __FUNCTION__, ##arg)
#define MBSLOGI(fmt, arg...)       ALOGI("[%s] " fmt, __FUNCTION__, ##arg)
#define MBSLOGW(fmt, arg...)       ALOGW("[%s] " fmt, __FUNCTION__, ##arg)
#define MBSLOGE(fmt, arg...)       ALOGE("[%s] " fmt, __FUNCTION__, ##arg)

namespace android {



/******************************************************************************
*
*******************************************************************************/
sp<MediaSource> MtkBSSource::Create(const sp<MediaSource> &source, const sp<MetaData> &meta) {
    return new MtkBSSource(source, meta);
}


/******************************************************************************
*
*******************************************************************************/
MtkBSSource::MtkBSSource(const sp<MediaSource> &source,  const sp<MetaData> &meta)
    :mSource(source)
    ,mLock()
    ,mStarted(false)
    ,mCodecConfigReceived(false)
    ,mNeedDropFrame(true)
    ,mOutputFormat(meta)
    ,MetaHandleList(0)
    {
    MBSLOGD("+");
    if (OK != setEncParam(meta))
        CHECK(!"set encoder parameter for direct link failed!");

    MBSLOGD("-");
}

/******************************************************************************
*
*******************************************************************************/
MtkBSSource::~MtkBSSource() {
    MBSLOGD("+");

    stop();

    if(mSource != NULL)
        mSource.clear();

    MBSLOGD("-");
}

/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::setEncParam( const sp<MetaData> &meta) {
    MBSLOGD("+");

    int32_t videoBitRate = 0;
    int32_t videoEncoder = 0;
    const char *mime;
    int32_t IFramesIntervalSec = 1;

    if(!meta->findInt32(kKeyBitRate, &videoBitRate)) {
        MBSLOGE("not set video bit rate");
        return UNKNOWN_ERROR;
    }

    if(!meta->findCString(kKeyMIMEType, &mime)) {
        MBSLOGE("not set video mime type");
        return UNKNOWN_ERROR;
    }

    if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
        videoEncoder = VIDEO_ENCODER_H264;
#ifdef MTK_VIDEO_HEVC_SUPPORT
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_HEVC, mime)) {
        videoEncoder = VIDEO_ENCODER_HEVC;
#endif
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
        videoEncoder = VIDEO_ENCODER_MPEG_4_SP;
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
        videoEncoder = VIDEO_ENCODER_H263;
    } else {
        MBSLOGE("Not a supported video mime type: %s", mime);
        CHECK(!"Should not be here. Not a supported video mime type.");
    }

    if(!meta->findInt32(kKeyIFramesInterval, &IFramesIntervalSec)) {

        MBSLOGE("not set I frames interval");
        return UNKNOWN_ERROR;
    }


    char param[PROPERTY_VALUE_MAX];
    sprintf(param, "%d", videoBitRate);
    MBSLOGD("property_set video bit rate %s", param);
    property_set("dl.vr.set.bit.rate", param);

    sprintf(param, "%d", videoEncoder);
    MBSLOGD("property_set video mime type %s", param);
    property_set("dl.vr.set.encoder", param);

    sprintf(param, "%d", IFramesIntervalSec);
    MBSLOGD("property_set I frames interval %s", param);
    property_set("dl.vr.set.iframes.interval", param);

    MBSLOGD("-");
    return OK;
}


/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::start(MetaData *params) {
    MBSLOGD("+");
    Mutex::Autolock _lock(mLock);

    if(mSource == NULL) {
        MBSLOGE("Failed: mSource is NULL");
        return UNKNOWN_ERROR;
    }
    status_t err = mSource->start(params);
    if (err != OK) {
        MBSLOGE("Failed: source start err(%d)", err);
        return err;
    }
    // when init, called once in each sequence
    err=eVEncDrvGetParam(0, VENC_DRV_GET_TYPE_ALLOC_META_HANDLE_LIST, &MetaHandleList, NULL);
    if(err!=OK){
        MBSLOGE("Failed: eVEncDrvGetParam init err(%d)", err);
        return err;
    }

    mStarted = true;

    MBSLOGD("-");
    return err;
}
/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::stop()
{
    MBSLOGD("+");
    Mutex::Autolock _lock(mLock);

    if(!mStarted)
        return OK;

    mStarted = false;

    status_t err = OK;
    // when de-init, called once in each sequence
    err=eVEncDrvGetParam(MetaHandleList, VENC_DRV_GET_TYPE_FREE_META_HANDLE_LIST, NULL, NULL);
    if(err!=OK){
        MBSLOGE("Failed: eVEncDrvGetParam de-init err(%d)", err);
        return err;
    }
    if(mSource != NULL) {
        MBSLOGD("mSource stop()");
        err = mSource->stop();
    }

    MBSLOGD("-");
    return err;
}

/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::read(MediaBuffer **buffer, const ReadOptions *options) {
    MBSLOGD("+");

    *buffer = NULL;
    if(options != NULL) {
        MBSLOGE("Failed: MtkBSSource dose not support read options");
        return ERROR_UNSUPPORTED;
    }

    {
        Mutex::Autolock _lock(mLock);

        if(!mStarted || mSource == NULL)
            return UNKNOWN_ERROR;

        status_t err = OK;
        if(mCodecConfigReceived && mNeedDropFrame) {
            err = dropFrame(buffer);
            mNeedDropFrame = false;
        }
        else {
            err = mSource->read(buffer);
        }

        if(err != OK)
            return err;

        err=passMetadatatoBuffer(buffer);
        if(err != OK)
            return err;

        if (!mCodecConfigReceived)
        {
            if(rBufInfo.fgIsConfigData){
                MBSLOGD("got codec config data, size=%d", (*buffer)->range_length());
                (*buffer)->meta_data()->setInt32(kKeyIsCodecConfig, true);

                mCodecConfigReceived = true;
            }
        }
        else {
            CHECK_EQ(rBufInfo.fgIsConfigData,0);
            MBSLOGV("got bitstream, size=%d", (*buffer)->range_length());
            bool bitstreamState = (bool)(rBufInfo.fgBSStatus);

            if(!bitstreamState) {
                MBSLOGE("get failed bitstream, return UNKNOWN_ERROR");
                (*buffer)->release();
                (*buffer) = NULL;
                return UNKNOWN_ERROR;
            }
            bool isSyncFrame = (bool)(rBufInfo.fgIsKeyFrame);
            if (isSyncFrame) {
                MBSLOGD("Got an I frame");
                (*buffer)->meta_data()->setInt32(kKeyIsSyncFrame, true);
            }

        }
        MBSLOGD("-");
        return OK;
    }
}

/******************************************************************************
*
*******************************************************************************/
sp<MetaData> MtkBSSource::getFormat() {
    return mOutputFormat;
}

/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::dropFrame(MediaBuffer **buffer) { // drop frame before 2nd I Frame
    int32_t iFframeCnt = 0;

    while(1) {
        status_t err = mSource->read(buffer);
        if(err != OK)
            return err;

        err=passMetadatatoBuffer(buffer);
        if(err != OK)
            return err;

        bool isSyncFrame = (bool)(rBufInfo.fgIsKeyFrame);
        if (isSyncFrame) {
            MBSLOGD("Got an I frame");
            iFframeCnt++;
        }

        if(iFframeCnt < 1) {
            MBSLOGD("drop the frame before 2nd I Frame");
            (*buffer)->release();
            (*buffer) = NULL;
        }
        else
            break;
    }

    return OK;
}
/******************************************************************************
*
*******************************************************************************/
status_t MtkBSSource::passMetadatatoBuffer(MediaBuffer **buffer){
            //Only when type is kMetadataBufferTypeGrallocSource,the metadata is followed by the buffer_handle_t that is a handle to the
        // GRalloc buffer.

            char *data = (char *)(*buffer)->data();
			/*OMX_U32 type;
			memcpy(&type, data, 4);
			CHECK_EQ(type, kMetadataBufferTypeGrallocSource);*/

            buffer_handle_t _handle;

            memcpy(&_handle, data + 4, sizeof(buffer_handle_t));
            // when encoding, called each frame in each sequence
            status_t err = OK;
            err=eVEncDrvGetParam(MetaHandleList, VENC_DRV_GET_TYPE_GET_BUF_INFO_FROM_META_HANDLE, &_handle, &rBufInfo);
            if(err!=OK){
            MBSLOGE("Failed: eVEncDrvGetParam encoding err(%d)", err);
            return err;
        }
        (*buffer)->release();
        (*buffer) = NULL;

        (*buffer) = new MediaBuffer((void *)(rBufInfo.u4BSVA), (size_t)(rBufInfo.u4BSSize));
        return OK;
        }

}

