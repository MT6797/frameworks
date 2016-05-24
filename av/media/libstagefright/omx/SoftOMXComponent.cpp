/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "SoftOMXComponent"
#include <utils/Log.h>
#include "include/SoftOMXComponent.h"
#include <media/stagefright/foundation/ADebug.h>

#ifdef MTK_AOSP_ENHANCEMENT
#include "OMX_Core.h"
#include <cutils/properties.h>
#endif

namespace android {

#ifdef MTK_AOSP_ENHANCEMENT

#define ID_RIFF 0x46464952
#define ID_WAVE 0x45564157
#define ID_FMT  0x20746d66
#define ID_DATA 0x61746164
#define FORMAT_PCM 1

struct wav_header
{
    int riff_id;
    int riff_sz;
    int riff_fmt;
    int fmt_id;
    int fmt_sz;
    short audio_format;
    short num_channels;
    int sample_rate;
    int byte_rate;       /* sample_rate * num_channels * bps / 8 */
    short block_align;     /* num_channels * bps / 8 */
    short bits_per_sample;
    int data_id;
    int data_sz;
};

char mCompRole[128] ={0};
char inPutFilePath[128] = {0};
char outPutFilePath[128] = {0};
OMX_S32 mFileDumpCtrl = 0;
bool mDumpStarted = false;
bool mDumpPrepared = false;
int mRecSize = 0;
int mSampleRate = 0;
int mChannelCnt = 0;
FILE *fp = NULL;
struct wav_header mWavHeader;

void setupAudioDumpFile(int32_t mFileDumpCtrl);
void dumpOutputBuffer(OMX_BUFFERHEADERTYPE *header);
void dumpInputBuffer(OMX_BUFFERHEADERTYPE *header);
void setComponentName(OMX_PTR params);
#endif

SoftOMXComponent::SoftOMXComponent(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : mName(name),
      mCallbacks(callbacks),
      mComponent(new OMX_COMPONENTTYPE),
      mLibHandle(NULL) {
    mComponent->nSize = sizeof(*mComponent);
    mComponent->nVersion.s.nVersionMajor = 1;
    mComponent->nVersion.s.nVersionMinor = 0;
    mComponent->nVersion.s.nRevision = 0;
    mComponent->nVersion.s.nStep = 0;
    mComponent->pComponentPrivate = this;
    mComponent->pApplicationPrivate = appData;

    mComponent->GetComponentVersion = NULL;
    mComponent->SendCommand = SendCommandWrapper;
    mComponent->GetParameter = GetParameterWrapper;
    mComponent->SetParameter = SetParameterWrapper;
    mComponent->GetConfig = GetConfigWrapper;
    mComponent->SetConfig = SetConfigWrapper;
    mComponent->GetExtensionIndex = GetExtensionIndexWrapper;
    mComponent->GetState = GetStateWrapper;
    mComponent->ComponentTunnelRequest = NULL;
    mComponent->UseBuffer = UseBufferWrapper;
    mComponent->AllocateBuffer = AllocateBufferWrapper;
    mComponent->FreeBuffer = FreeBufferWrapper;
    mComponent->EmptyThisBuffer = EmptyThisBufferWrapper;
    mComponent->FillThisBuffer = FillThisBufferWrapper;
    mComponent->SetCallbacks = NULL;
    mComponent->ComponentDeInit = NULL;
    mComponent->UseEGLImage = NULL;
    mComponent->ComponentRoleEnum = NULL;

    *component = mComponent;
}

SoftOMXComponent::~SoftOMXComponent() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mFileDumpCtrl != 0)
    {
        mDumpPrepared = false;
        mDumpStarted = false;
    if(fp != NULL)
    {
        fflush(fp);
        fclose(fp);
        fp = NULL;
    }
    ALOGD("Dump audio file done");
}
#endif
    delete mComponent;
    mComponent = NULL;
}

void SoftOMXComponent::setLibHandle(void *libHandle) {
    CHECK(libHandle != NULL);
    mLibHandle = libHandle;
}

void *SoftOMXComponent::libHandle() const {
    return mLibHandle;
}

OMX_ERRORTYPE SoftOMXComponent::initCheck() const {
    return OMX_ErrorNone;
}

const char *SoftOMXComponent::name() const {
    return mName.c_str();
}

void SoftOMXComponent::notify(
        OMX_EVENTTYPE event,
        OMX_U32 data1, OMX_U32 data2, OMX_PTR data) {
    (*mCallbacks->EventHandler)(
            mComponent,
            mComponent->pApplicationPrivate,
            event,
            data1,
            data2,
            data);
}

void SoftOMXComponent::notifyEmptyBufferDone(OMX_BUFFERHEADERTYPE *header) {
    (*mCallbacks->EmptyBufferDone)(
            mComponent, mComponent->pApplicationPrivate, header);
}

void SoftOMXComponent::notifyFillBufferDone(OMX_BUFFERHEADERTYPE *header) {
#ifdef MTK_AOSP_ENHANCEMENT
    if ((mFileDumpCtrl & 0x02) == 0x02)
    {
        dumpOutputBuffer(header);
    }
#endif
    (*mCallbacks->FillBufferDone)(
            mComponent, mComponent->pApplicationPrivate, header);
}

// static
OMX_ERRORTYPE SoftOMXComponent::SendCommandWrapper(
        OMX_HANDLETYPE component,
        OMX_COMMANDTYPE cmd,
        OMX_U32 param,
        OMX_PTR data) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->sendCommand(cmd, param, data);
}

// static
OMX_ERRORTYPE SoftOMXComponent::GetParameterWrapper(
        OMX_HANDLETYPE component,
        OMX_INDEXTYPE index,
        OMX_PTR params) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

#ifdef MTK_AOSP_ENHANCEMENT
    OMX_ERRORTYPE err = me->getParameter(index, params);
    if (mFileDumpCtrl != 0) {
        if(index == OMX_IndexParamAudioPcm)
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams = (OMX_AUDIO_PARAM_PCMMODETYPE *)params;
            mSampleRate = pcmParams->nSamplingRate;
            mChannelCnt = pcmParams->nChannels;
            ALOGD("GetParameterWrapper mSampleRate = %d,mChannelCnt = %d",mSampleRate,mChannelCnt);
        }
    }
    return err;
#else
    return me->getParameter(index, params);
#endif
}

// static
OMX_ERRORTYPE SoftOMXComponent::SetParameterWrapper(
        OMX_HANDLETYPE component,
        OMX_INDEXTYPE index,
        OMX_PTR params) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;
#ifdef MTK_AOSP_ENHANCEMENT
    char value[PROPERTY_VALUE_MAX];
    property_get("soft_omx_audio_dump", value, "0");
    mFileDumpCtrl = atoi(value);
    if (mFileDumpCtrl != 0)
    {
        if(index == OMX_IndexParamStandardComponentRole)
        {
            setComponentName(params);
            mDumpPrepared = true;
        }
    }
#endif
    return me->setParameter(index, params);
}

// static
OMX_ERRORTYPE SoftOMXComponent::GetConfigWrapper(
        OMX_HANDLETYPE component,
        OMX_INDEXTYPE index,
        OMX_PTR params) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->getConfig(index, params);
}

// static
OMX_ERRORTYPE SoftOMXComponent::SetConfigWrapper(
        OMX_HANDLETYPE component,
        OMX_INDEXTYPE index,
        OMX_PTR params) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->setConfig(index, params);
}

// static
OMX_ERRORTYPE SoftOMXComponent::GetExtensionIndexWrapper(
        OMX_HANDLETYPE component,
        OMX_STRING name,
        OMX_INDEXTYPE *index) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->getExtensionIndex(name, index);
}

// static
OMX_ERRORTYPE SoftOMXComponent::UseBufferWrapper(
        OMX_HANDLETYPE component,
        OMX_BUFFERHEADERTYPE **buffer,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size,
        OMX_U8 *ptr) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;
#ifdef MTK_AOSP_ENHANCEMENT
    if(mDumpPrepared== true)
    {
        setupAudioDumpFile(mFileDumpCtrl);
        mDumpPrepared= false;
    }
#endif
    return me->useBuffer(buffer, portIndex, appPrivate, size, ptr);
}

// static
OMX_ERRORTYPE SoftOMXComponent::AllocateBufferWrapper(
        OMX_HANDLETYPE component,
        OMX_BUFFERHEADERTYPE **buffer,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->allocateBuffer(buffer, portIndex, appPrivate, size);
}

// static
OMX_ERRORTYPE SoftOMXComponent::FreeBufferWrapper(
        OMX_HANDLETYPE component,
        OMX_U32 portIndex,
        OMX_BUFFERHEADERTYPE *buffer) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->freeBuffer(portIndex, buffer);
}

// static
OMX_ERRORTYPE SoftOMXComponent::EmptyThisBufferWrapper(
        OMX_HANDLETYPE component,
        OMX_BUFFERHEADERTYPE *buffer) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;
#ifdef MTK_AOSP_ENHANCEMENT
    if ((mFileDumpCtrl & 0x01) == 0x01)
    {
        dumpInputBuffer(buffer);
    }
#endif
    return me->emptyThisBuffer(buffer);
}

// static
OMX_ERRORTYPE SoftOMXComponent::FillThisBufferWrapper(
        OMX_HANDLETYPE component,
        OMX_BUFFERHEADERTYPE *buffer) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->fillThisBuffer(buffer);
}

// static
OMX_ERRORTYPE SoftOMXComponent::GetStateWrapper(
        OMX_HANDLETYPE component,
        OMX_STATETYPE *state) {
    SoftOMXComponent *me =
        (SoftOMXComponent *)
            ((OMX_COMPONENTTYPE *)component)->pComponentPrivate;

    return me->getState(state);
}

////////////////////////////////////////////////////////////////////////////////

OMX_ERRORTYPE SoftOMXComponent::sendCommand(
        OMX_COMMANDTYPE /* cmd */, OMX_U32 /* param */, OMX_PTR /* data */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::getParameter(
        OMX_INDEXTYPE /* index */, OMX_PTR /* params */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::setParameter(
        OMX_INDEXTYPE /* index */, const OMX_PTR /* params */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::getConfig(
        OMX_INDEXTYPE /* index */, OMX_PTR /* params */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::setConfig(
        OMX_INDEXTYPE /* index */, const OMX_PTR /* params */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::getExtensionIndex(
        const char * /* name */, OMX_INDEXTYPE * /* index */) {
    return OMX_ErrorUnsupportedIndex;
}

OMX_ERRORTYPE SoftOMXComponent::useBuffer(
        OMX_BUFFERHEADERTYPE ** /* buffer */,
        OMX_U32 /* portIndex */,
        OMX_PTR /* appPrivate */,
        OMX_U32 /* size */,
        OMX_U8 * /* ptr */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::allocateBuffer(
        OMX_BUFFERHEADERTYPE ** /* buffer */,
        OMX_U32 /* portIndex */,
        OMX_PTR /* appPrivate */,
        OMX_U32 /* size */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::freeBuffer(
        OMX_U32 /* portIndex */,
        OMX_BUFFERHEADERTYPE * /* buffer */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::emptyThisBuffer(
        OMX_BUFFERHEADERTYPE * /* buffer */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::fillThisBuffer(
        OMX_BUFFERHEADERTYPE * /* buffer */) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE SoftOMXComponent::getState(OMX_STATETYPE * /* state */) {
    return OMX_ErrorUndefined;
}

#ifdef MTK_AOSP_ENHANCEMENT
void setComponentName(OMX_PTR params)
{
    OMX_PARAM_COMPONENTROLETYPE *pRoleParams = (OMX_PARAM_COMPONENTROLETYPE *)params;
    if(!strncmp((char *)pRoleParams->cRole,"audio",5))
    {
        strcpy((char *)mCompRole, (char *)pRoleParams->cRole);
        ALOGD("setParameter audio mCompRole = %s",mCompRole);
    }

}

void setupAudioDumpFile(int32_t mFileDumpCtrl) {
    ALOGD("setupAudioDumpFile");
    struct tm *timeinfo;
    time_t rawtime;
    time(&rawtime);
    timeinfo = localtime(&rawtime);

    if((mFileDumpCtrl & 0x01) == 0x01)
    {
        sprintf(inPutFilePath, "/sdcard/%s", (char *)mCompRole);

        if ((mFileDumpCtrl & 0x10) == 0x10)
        {
            strftime(inPutFilePath + 8 + strlen((char *)mCompRole), 60, "_%Y_%m_%d_%H_%M_%S_in.bs", timeinfo);
        }
        else
        {
            sprintf(inPutFilePath + 8 + strlen((char *)mCompRole), "%s", ".bs");
        }
    }

    if ((mFileDumpCtrl & 0x02) == 0x02)
    {
        sprintf(outPutFilePath, "/sdcard/%s", (char *)mCompRole);

        if ((mFileDumpCtrl & 0x10) == 0x10)
        {
            strftime(outPutFilePath + 8 + strlen((char *)mCompRole), 60, "_%Y_%m_%d_%H_%M_%S_out.wav", timeinfo);
        }
        else
        {
            sprintf(outPutFilePath + 8 + strlen((char *)mCompRole), "%s", ".wav");
        }
        fp = fopen(outPutFilePath, "wb");
    }

    ALOGD("Ctrl %x, total path is in %s,out %s", mFileDumpCtrl, inPutFilePath, outPutFilePath);
}

void initWavHeader()
{
    mWavHeader.riff_id = ID_RIFF;
    mWavHeader.riff_sz = 0;
    mWavHeader.riff_fmt = ID_WAVE;
    mWavHeader.fmt_id = ID_FMT;
    mWavHeader.fmt_sz = 16;
    mWavHeader.audio_format = FORMAT_PCM;
    mWavHeader.num_channels = mChannelCnt;
    mWavHeader.sample_rate = mSampleRate;
    mWavHeader.byte_rate = mWavHeader.sample_rate * mWavHeader.num_channels * 2;
    mWavHeader.block_align = mWavHeader.num_channels * 2;
    mWavHeader.bits_per_sample = 16;
    mWavHeader.data_id = ID_DATA;
    mWavHeader.data_sz = 0;
    fwrite(&mWavHeader, sizeof(mWavHeader), 1, fp);
}

void updateWavHeader()
{
    fseek(fp,0,SEEK_SET);
    mWavHeader.riff_sz = mRecSize + 8 + 16 + 8;
    mWavHeader.num_channels = mChannelCnt;
    mWavHeader.sample_rate = mSampleRate;
    mWavHeader.byte_rate = mWavHeader.sample_rate * mWavHeader.num_channels * 2;
    mWavHeader.block_align = mWavHeader.num_channels * 2;
    mWavHeader.data_sz = mRecSize;
    fwrite(&mWavHeader, sizeof(mWavHeader), 1, fp);
    fseek(fp,0,SEEK_END);

}
void dumpOutputBuffer(OMX_BUFFERHEADERTYPE *header) {
    ALOGV("dumpOutputBuffer");
    if(mDumpStarted == false)
    {
        if (fp != NULL)
        {
            initWavHeader();
            ALOGD("write wav header");

            fseek(fp, 0, SEEK_SET);
            if (fwrite(&mWavHeader, sizeof(mWavHeader), 1, fp) != 1)
            {
                ALOGD("write wav header to dump file error");
            }

            mDumpStarted = true;
        }
    }

    if (fp != NULL)
    {
        int n = fwrite(header->pBuffer+header->nOffset , 1 , header->nFilledLen, fp);
        mRecSize += n;
        updateWavHeader();
    }
    else
    {
        ALOGE("FileDump can't open output file");
    }
}
void dumpInputBuffer(OMX_BUFFERHEADERTYPE *header) {
    ALOGV("dumpInputBuffer");
    FILE *fp = NULL;
    fp = fopen(inPutFilePath, "ab");

    if (fp != NULL)
    {
        fwrite(header->pBuffer+header->nOffset, 1 , header->nFilledLen, fp);
        fclose(fp);
    }
    else
    {
        ALOGE("FileDump can't open input file");
    }
}
#endif
}  // namespace android
