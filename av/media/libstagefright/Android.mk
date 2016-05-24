LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

include frameworks/av/media/libstagefright/codecs/common/Config.mk

LOCAL_SRC_FILES:=                         \
        ACodec.cpp                        \
        AACExtractor.cpp                  \
        AACWriter.cpp                     \
        AMRExtractor.cpp                  \
        AMRWriter.cpp                     \
        AudioPlayer.cpp                   \
        AudioSource.cpp                   \
        AwesomePlayer.cpp                 \
        CallbackDataSource.cpp            \
        CameraSource.cpp                  \
        CameraSourceTimeLapse.cpp         \
        ClockEstimator.cpp                \
        CodecBase.cpp                     \
        DataSource.cpp                    \
        DataURISource.cpp                 \
        DRMExtractor.cpp                  \
        ESDS.cpp                          \
        FileSource.cpp                    \
        FrameRenderTracker.cpp            \
        HTTPBase.cpp                      \
        JPEGSource.cpp                    \
        MP3Extractor.cpp                  \
        MPEG2TSWriter.cpp                 \
        MPEG4Extractor.cpp                \
        MPEG4Writer.cpp                   \
        MediaAdapter.cpp                  \
        MediaBuffer.cpp                   \
        MediaBufferGroup.cpp              \
        MediaClock.cpp                    \
        MediaCodec.cpp                    \
        MediaCodecList.cpp                \
        MediaCodecListOverrides.cpp       \
        MediaCodecSource.cpp              \
        MediaDefs.cpp                     \
        MediaExtractor.cpp                \
        MediaSync.cpp                     \
        MidiExtractor.cpp                 \
        http/MediaHTTP.cpp                \
        MediaMuxer.cpp                    \
        MediaSource.cpp                   \
        MetaData.cpp                      \
        NuCachedSource2.cpp               \
        NuMediaExtractor.cpp              \
        OMXClient.cpp                     \
        OMXCodec.cpp                      \
        OggExtractor.cpp                  \
        ProcessInfo.cpp                   \
        SampleIterator.cpp                \
        SampleTable.cpp                   \
        SkipCutBuffer.cpp                 \
        StagefrightMediaScanner.cpp       \
        StagefrightMetadataRetriever.cpp  \
        SurfaceMediaSource.cpp            \
        SurfaceUtils.cpp                  \
        ThrottledSource.cpp               \
        TimeSource.cpp                    \
        TimedEventQueue.cpp               \
        Utils.cpp                         \
        VBRISeeker.cpp                    \
        VideoFrameScheduler.cpp           \
        WAVExtractor.cpp                  \
        WVMExtractor.cpp                  \
        XINGSeeker.cpp                    \
        avc_utils.cpp                     \

LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/include/media/ \
        $(TOP)/frameworks/av/include/media/stagefright/timedtext \
        $(TOP)/frameworks/native/include/media/hardware \
        $(TOP)/external/tremolo \
        $(TOP)/external/libvpx/libwebm \
        $(TOP)/system/netd/include \

LOCAL_SHARED_LIBRARIES := \
        libbinder \
        libcamera_client \
        libcutils \
        libdl \
        libdrmframework \
        libexpat \
        libgui \
        libicui18n \
        libicuuc \
        liblog \
        libmedia \
        libmediautils \
        libnetd_client \
        libopus \
        libsonivox \
        libssl \
        libstagefright_omx \
        libstagefright_yuv \
        libsync \
        libui \
        libutils \
        libvorbisidec \
        libz \
        libpowermanager

LOCAL_STATIC_LIBRARIES := \
        libstagefright_color_conversion \
        libstagefright_aacenc \
        libstagefright_matroska \
        libstagefright_mediafilter \
        libstagefright_webm \
        libstagefright_timedtext \
        libvpx \
        libwebm \
        libstagefright_mpeg2ts \
        libstagefright_id3 \
        libmedia_helper \

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
# some google code would be instead by mtk code
LOCAL_C_INCLUDES +=  \
        $(TOP)/external/flac/include \
        $(TOP)/frameworks/native/include/media/openmax
LOCAL_SRC_FILES += \
        FLACExtractor.cpp
LOCAL_STATIC_LIBRARIES += libFLAC
endif  # MTK_USE_ANDROID_MM_DEFAULT_CODE

LOCAL_SHARED_LIBRARIES += \
        libstagefright_enc_common \
        libstagefright_avc_common \
        libstagefright_foundation \
        libdl \
        libRScpp \

LOCAL_CFLAGS += -Wno-multichar -Werror -Wno-error=deprecated-declarations -Wall

# enable experiments only in userdebug and eng builds
ifneq (,$(filter userdebug eng,$(TARGET_BUILD_VARIANT)))
LOCAL_CFLAGS += -DENABLE_STAGEFRIGHT_EXPERIMENTS
endif

LOCAL_CLANG := true
######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

ifeq ($(MTK_OGM_PLAYBACK_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_OGM_PLAYBACK_SUPPORT
endif
ifeq ($(strip $(MTK_VIDEO_VP8ENC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_VIDEO_VP8ENC_SUPPORT
endif

LOCAL_C_INCLUDES +=  \
        $(TOP)/$(MTK_ROOT)/frameworks/native/include/media/openmax \
        $(TOP)/vendor/mediatek/proprietary/hardware/include

#for rtsp local sdp
LOCAL_C_INCLUDES += $(TOP)/frameworks/av/media/libstagefright/rtsp
LOCAL_SRC_FILES += \
			MtkSDPExtractor.cpp
LOCAL_STATIC_LIBRARIES += libstagefright_rtsp



LOCAL_SRC_FILES += \
    	OggWriter.cpp   \
        PCMWriter.cpp

#LOCAL_STATIC_LIBRARIES += libwriter_mtk

LOCAL_SRC_FILES += \
	TableOfContentThread.cpp \
    FileSourceProxy.cpp

LOCAL_SRC_FILES += \
	MtkAACExtractor.cpp \
	MMReadIOThread.cpp
            
ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

ifeq ($(strip $(MTK_AUDIO_DDPLUS_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_AUDIO_DDPLUS_SUPPORT
LOCAL_C_INCLUDES += $(TOP)/vendor/dolby/ds/include
endif
LOCAL_SRC_FILES += hevc_utils.cpp

LOCAL_SRC_FILES += MPEG4FileCacheWriter.cpp     \
                   MtkBSSource.cpp

LOCAL_SHARED_LIBRARIES += \
libvcodecdrv

LOCAL_SRC_FILES += MtkFLVExtractor.cpp

LOCAL_SHARED_LIBRARIES += \
	libhardware \
	libskia \
	libgralloc_extra

ifeq ($(MTK_AUDIO_APE_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_AUDIO_APE_SUPPORT

LOCAL_SRC_FILES += \
        APEExtractor.cpp \
        apetag.cpp

endif
ifeq ($(MTK_AUDIO_ALAC_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_AUDIO_ALAC_SUPPORT

LOCAL_SRC_FILES += \
        CAFExtractor.cpp
endif

ifeq ($(strip $(MTK_BSP_PACKAGE)),no)
    $(warning "not bsp - mtk flac parser")
 	LOCAL_SRC_FILES += MtkFLACExtractor.cpp
else
    ifneq (,$(findstring arm,$(TARGET_ARCH)))
        $(warning "bsp && arm - mtk flac parser")
 	    LOCAL_SRC_FILES += MtkFLACExtractor.cpp
    else
        $(warning "bsp && not arm - google flac parser")
    	LOCAL_SRC_FILES += FLACExtractor.cpp
    endif
endif

LOCAL_C_INCLUDES += $(TOP)/external/flac/include
LOCAL_STATIC_LIBRARIES += libFLAC

LOCAL_C_INCLUDES += \
        $(MTK_PATH_SOURCE)/frameworks/av/media/libstagefright/include \
        LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/hardware/mtkcam/ext/include \
        $(TOP)/frameworks/av/media/libstagefright/include \
        $(MTK_PATH_SOURCE)/kernel/include \
        $(TOP)/external/skia/include/images \
        $(TOP)/external/skia/include/core \
        $(TOP)/frameworks/native/include/media/editor \
        $(TOP)/$(MTK_ROOT)/hardware/dpframework/inc \
        $(TOP)/frameworks/av/include \
        $(TOP)/$(MTK_ROOT)/frameworks-ext/native/include


ifeq ($(strip $(MTK_DP_FRAMEWORK)),yes)
LOCAL_SHARED_LIBRARIES += \
    libdpframework
endif
ifeq ($(strip $(MTK_BSP_PACKAGE)),no)
LOCAL_SHARED_LIBRARIES += \
        libcustom_prop
endif
LOCAL_SRC_FILES += NuCachedWrapperSource.cpp
ifeq ($(strip $(HAVE_ADPCMENCODE_FEATURE)),yes)
    LOCAL_CFLAGS += -DHAVE_ADPCMENCODE_FEATURE
    LOCAL_SRC_FILES += \
        ADPCMWriter.cpp
endif

ifeq ($(strip $(MTK_AVI_PLAYBACK_SUPPORT)), yes)
	LOCAL_CFLAGS += -DMTK_AVI_PLAYBACK_SUPPORT
	LOCAL_SRC_FILES += MtkAVIExtractor.cpp
endif

ifeq ($(MTK_OGM_PLAYBACK_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_OGM_PLAYBACK_SUPPORT
LOCAL_SRC_FILES += \
        OgmExtractor.cpp
endif

ifeq ($(strip $(MTK_WMV_PLAYBACK_SUPPORT)), yes)
        LOCAL_SRC_FILES += ASFExtractor.cpp
        LOCAL_C_INCLUDES += $(TOP)/frameworks/av/media/libstagefright/libasf/inc
        LOCAL_STATIC_LIBRARIES += libasf
endif

ifeq ($(strip $(HAVE_XLOG_FEATURE)),yes)
	LOCAL_CFLAGS += -DMTK_STAGEFRIGHT_USE_XLOG
endif

ifeq ($(strip $(MTK_DRM_APP)),yes)
    LOCAL_CFLAGS += -DMTK_DRM_APP
    LOCAL_C_INCLUDES += \
        $(TOP)/$(MTK_ROOT)/frameworks/av/include \
        bionic
    LOCAL_SHARED_LIBRARIES += \
        libdrmmtkutil
endif

LOCAL_C_INCLUDES += \
	$(TOP)/external/aac/libAACdec/include \
	$(TOP)/external/aac/libPCMutils/include \
	$(TOP)/external/aac/libFDK/include \
	$(TOP)/external/aac/libMpegTPDec/include \
	$(TOP)/external/aac/libSBRdec/include \
	$(TOP)/external/aac/libSYS/include

LOCAL_STATIC_LIBRARIES += libFraunhoferAAC
LOCAL_CFLAGS += -DUSE_FRAUNHOFER_AAC

#MediaRecord CameraSource  OMXCodec
LOCAL_SHARED_LIBRARIES += libmtkcamera_client
ifeq ($(HAVE_AEE_FEATURE),yes)
LOCAL_SHARED_LIBRARIES += libaed
LOCAL_C_INCLUDES += $(MTK_ROOT)/external/aee/binary/inc
LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

MTK_CUSTOM_UASTRING_FROM_PROPERTY := yes
ifeq ($(strip $(MTK_BSP_PACKAGE)),yes)   # BSP would not have CUSTOM_UASTRING, confirm with yong.ding
	MTK_CUSTOM_UASTRING_FROM_PROPERTY := no
endif
ifeq ($(strip $(MTK_CUSTOM_UASTRING_FROM_PROPERTY)), yes)
LOCAL_CFLAGS += -DCUSTOM_UASTRING_FROM_PROPERTY
LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/frameworks/base/custom/inc
LOCAL_SHARED_LIBRARIES += libcustom_prop
endif

LOCAL_CFLAGS += -DMTK_ELEMENT_STREAM_SUPPORT
LOCAL_SRC_FILES += ESExtractor.cpp


# playready
PLAYREADY_TPLAY:=yes
#LOCAL_CFLAGS += -DMTK_PLAYREADY_SUPPORT
#LOCAL_CFLAGS += -DPLAYREADY_SVP_UT
ifneq (yes, $(strip $(PLAYREADY_TPLAY)))
LOCAL_CFLAGS += -DUT_NO_SVP_DRM
else
LOCAL_CFLAGS += -DPLAYREADY_SVP_TPLAY
LOCAL_C_INCLUDES += $(TOP)/mediatek/kernel/drivers/video
endif
#TRUSTONIC_TEE_SUPPORT:=yes
#MTK_SEC_VIDEO_PATH_SUPPORT:=yes
ifeq ($(TRUSTONIC_TEE_SUPPORT), yes)
LOCAL_CFLAGS += -DTRUSTONIC_TEE_SUPPORT
endif
ifeq ($(MTK_SEC_VIDEO_PATH_SUPPORT), yes)
LOCAL_CFLAGS += -DMTK_SEC_VIDEO_PATH_SUPPORT
# playready fake mode for svp
ifeq ($(TRUSTONIC_TEE_SUPPORT), yes)
LOCAL_CFLAGS += -DMTK_PLAYREADY_FAKEMODE
LOCAL_SRC_FILES += PRFakeExtractor.cpp
endif
endif

ifeq ($(strip $(MTK_VIDEO_HEVC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_VIDEO_HEVC_SUPPORT
endif
#for EIS2.5
ifeq ($(MTK_CAM_ADVANCED_EIS_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_CAM_ADVANCED_EIS_SUPPORT
endif
#====== VSDoF ======
ifeq ($(MTK_CAM_VSDOF_SUPPORT),yes)
    LOCAL_CFLAGS += -DVSDOF_SUPPORTED=1
endif

#MTK format
LOCAL_C_INCLUDES += $(TOP)/vendor/mediatek/proprietary/hardware/include

ifeq ($(MTK_AUDIO),yes)
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/hardware/audio/common/include
endif
endif    # MTK_USE_ANDROID_MM_DEFAULT_CODE
######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################

LOCAL_MODULE:= libstagefright

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
