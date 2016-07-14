LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

ifeq ($(strip $(MTK_CAM_VSDOF_SUPPORT)), yes)
LOCAL_CFLAGS += -DMTK_CAM_VSDOF_SUPPORT
endif

ifeq ($(strip $(MTK_VIDEO_HEVC_SUPPORT)), yes)
LOCAL_CFLAGS += -DMTK_VIDEO_HEVC_SUPPORT
endif
ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/hardware/audio/common/include
endif

LOCAL_SRC_FILES:= \
    AudioParameter.cpp
LOCAL_MODULE:= libmedia_helper
LOCAL_MODULE_TAGS := optional

LOCAL_C_FLAGS += -Werror -Wno-error=deprecated-declarations -Wall
LOCAL_CLANG := true

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)



ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
  LOCAL_CFLAGS += -DMTK_HDMI_MULTI_CHANNEL_SUPPORT
else
  LOCAL_CFLAGS += -DGENERIC_AUDIO
endif

ifeq ($(strip $(TARGET_BUILD_VARIANT)),eng)
    LOCAL_CFLAGS += -DCONFIG_MT_ENG_BUILD
else ifeq ($(strip $(TARGET_BUILD_VARIANT)), user)
    LOCAL_CFLAGS += -DCONFIG_MT_USER_BUILD
endif

# For MTK Sink feature
ifeq ($(strip $(MTK_WFD_SINK_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_SUPPORT

# For MTK Sink UIBC feature
ifeq ($(strip $(MTK_WFD_SINK_UIBC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_UIBC_SUPPORT
endif
endif

ifeq ($(MTK_AUDIO),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO
endif

ifeq ($(strip $(HAVE_AACENCODE_FEATURE)),yes)
    LOCAL_CFLAGS += -DHAVE_AACENCODE_FEATURE
endif

ifeq ($(strip $(MTK_AUDIO_HD_REC_SUPPORT)), yes)
	LOCAL_CFLAGS += -DMTK_AUDIO_HD_REC_SUPPORT
endif

ifeq ($(strip $(MTK_CROSSMOUNT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_CROSSMOUNT_SUPPORT
endif

LOCAL_SRC_FILES:= \
    AudioTrack.cpp \
    AudioTrackShared.cpp \
    IAudioFlinger.cpp \
    IAudioFlingerClient.cpp \
    IAudioTrack.cpp \
    IAudioRecord.cpp \
    ICrypto.cpp \
    IDataSource.cpp \
    IDrm.cpp \
    IDrmClient.cpp \
    IHDCP.cpp \
    AudioRecord.cpp \
    AudioSystem.cpp \
    mediaplayer.cpp \
    IMediaCodecList.cpp \
    IMediaHTTPConnection.cpp \
    IMediaHTTPService.cpp \
    IMediaLogService.cpp \
    IMediaPlayerService.cpp \
    IMediaPlayerClient.cpp \
    IMediaRecorderClient.cpp \
    IMediaPlayer.cpp \
    IMediaRecorder.cpp \
    IRemoteDisplay.cpp \
    IRemoteDisplayClient.cpp \
    IResourceManagerClient.cpp \
    IResourceManagerService.cpp \
    IStreamSource.cpp \
    MediaCodecInfo.cpp \
    MediaUtils.cpp \
    Metadata.cpp \
    mediarecorder.cpp \
    IMediaMetadataRetriever.cpp \
    mediametadataretriever.cpp \
    MidiIoWrapper.cpp \
    ToneGenerator.cpp \
    JetPlayer.cpp \
    IOMX.cpp \
    IAudioPolicyService.cpp \
    IAudioPolicyServiceClient.cpp \
    MediaScanner.cpp \
    MediaScannerClient.cpp \
    CharacterEncodingDetector.cpp \
    IMediaDeathNotifier.cpp \
    MediaProfiles.cpp \
    MediaResource.cpp \
    MediaResourcePolicy.cpp \
    IEffect.cpp \
    IEffectClient.cpp \
    AudioEffect.cpp \
    Visualizer.cpp \
    MemoryLeakTrackUtil.cpp \
    StringArray.cpp \
    AudioPolicy.cpp


ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_SRC_FILES += \
    AudioPCMxWay.cpp \
    ATVCtrl.cpp \
    IATVCtrlClient.cpp \
    IATVCtrlService.cpp \
    AudioTrackCenter.cpp
endif


LOCAL_SHARED_LIBRARIES := \
        libui liblog libcutils libutils libbinder libsonivox libicuuc libicui18n libexpat \
        libcamera_client libstagefright_foundation \
        libgui libdl libaudioutils libnbaio


LOCAL_STATIC_LIBRARIES += \
        libmedia_helper
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

LOCAL_SHARED_LIBRARIES += \
        libvcodecdrv
endif

LOCAL_WHOLE_STATIC_LIBRARIES := libmedia_helper

LOCAL_MODULE:= libmedia

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_C_INCLUDES := \
    $(TOP)/frameworks/native/include/media/openmax  \
    $(TOP)/frameworks/av/include/media/ \
    $(TOP)/frameworks/av/media/libstagefright \
    $(TOP)/frameworks/av/media/libstagefright   \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils)

LOCAL_CFLAGS += -Werror -Wno-error=deprecated-declarations -Wall
LOCAL_CLANG := true

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES+= \
	 $(TOP)/$(MTK_PATH_PLATFORM)/hardware/vcodec/inc \
	 $(TOP)/$(MTK_ROOT)/external/mhal/src/core/drv/inc \
	 $(TOP)/$(MTK_ROOT)/frameworks/av/include
endif

LOCAL_SRC_FILES+= \
    IRemoteMountClient.cpp \
    IRemoteMount.cpp

LOCAL_C_INCLUDES+= \
    system/media/camera/include

ifeq ($(MTK_AUDIO),yes)
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/hardware/audio/common/include
endif

# For Game Detection
$(info MTK_GAS_SERVICE_SUPPORT=$(MTK_GAS_SERVICE_SUPPORT))
ifeq ($(strip $(MTK_GAS_SERVICE_SUPPORT)),yes)
    LOCAL_CFLAGS            += -DMTK_GAS_SERVICE_SUPPORT
    LOCAL_C_INCLUDES        += $(TOP)/$(MTK_ROOT)/hardware/gpu_ext/gas/
    LOCAL_SHARED_LIBRARIES  += libgas
endif

include $(BUILD_SHARED_LIBRARY)

