LOCAL_PATH:= $(call my-dir)

ifneq ($(BOARD_USE_CUSTOM_MEDIASERVEREXTENSIONS),true)
include $(CLEAR_VARS)
LOCAL_SRC_FILES := register.cpp

################################################################################
# mmsdk related, TODO: should add feature option
################################################################################
ifeq ($(MTK_CAM_MMSDK_SUPPORT),$(filter $(MTK_CAM_MMSDK_SUPPORT),yes no))
-include $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/mtkcam.mk
LOCAL_C_INCLUDES += \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/services/mmsdk/libmmsdkservice \
    $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/ext/include \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/services/mmsdk/include/ \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks-ext/av/include/ \
    $(TOP)/$(MTKCAM_C_INCLUDES) \
    $(TOP)/$(MTKCAM_C_INCLUDES)/.. \
    $(TOP)/$(MTK_PATH_SOURCE)/hardware/gralloc_extra/include \
    $(TOP)/$(MTK_PATH_SOURCE)/hardware/mmsdk/include

ifeq ($(IS_LEGACY), 0)
LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/middleware/common/include
LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/utils/include
else
LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/legacy/include/mtkcam/utils
endif

LOCAL_CFLAGS += -DMTK_CAM_MMSDK_SUPPORT
endif
LOCAL_C_INCLUDES += system/media/camera/include

LOCAL_MODULE := libregistermsext
LOCAL_MODULE_TAGS := optional
include $(BUILD_STATIC_LIBRARY)
endif

include $(CLEAR_VARS)

-include $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/mtkcam.mk

LOCAL_SRC_FILES:= \
	main_mediaserver.cpp

LOCAL_SHARED_LIBRARIES := \
	libaudioflinger \
	libaudiopolicyservice \
	libcamera_metadata\
	libcameraservice \
	libicuuc \
	libmedialogservice \
	libresourcemanagerservice \
	libcutils \
	libnbaio \
	libmedia \
	libmediaplayerservice \
	libutils \
	liblog \
	libmemorydumper \
	libdl \
	libbinder \
	libsoundtriggerservice \
	libradioservice


LOCAL_STATIC_LIBRARIES := \
        libicuandroid_utils \
        libregistermsext

LOCAL_C_INCLUDES += \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/memorydumper/include \
    frameworks/av/media/libmediaplayerservice \
    frameworks/av/services/medialog \
    frameworks/av/services/audioflinger \
    frameworks/av/services/audiopolicy \
    frameworks/av/services/audiopolicy/common/managerdefinitions/include \
    frameworks/av/services/audiopolicy/common/include \
    frameworks/av/services/audiopolicy/engine/interface \
    frameworks/av/services/camera/libcameraservice \
    frameworks/av/services/mediaresourcemanager \
    $(call include-path-for, audio-utils) \
    frameworks/av/services/soundtrigger \
    frameworks/av/services/radio \
    external/sonic


LOCAL_C_INCLUDES += \
        frameworks/av/include/media \
        $(TOP)/$(MTK_ROOT)/frameworks-ext/av/services/audioflinger \
        $(MTK_PATH_SOURCE)/external/audiodcremoveflt \
        $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
        $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
        $(MTK_PATH_SOURCE)/hardware/audio/common/aud_drv \
        $(MTK_PATH_SOURCE)/hardware/audio/common/ \
        $(MTK_PATH_SOURCE)/hardware/audio/common/include \
        $(MTK_PATH_SOURCE)/hardware/audio/common/V3/include \
        $(MTK_PATH_PLATFORM)/hardware/audio/aud_drv \
        $(MTK_PATH_PLATFORM)/hardware/audio/aud_drv/include \
        $(MTK_PATH_PLATFORM)/hardware/audio \
        $(TOP)/$(MTKCAM_C_INCLUDES) \
        $(MTK_PATH_SOURCE)/external/AudioDCRemoval \
        $(MTK_PATH_SOURCE)/external/blisrc/blisrc32 \
        $(MTK_PATH_SOURCE)/external/limiter \
        $(MTK_PATH_SOURCE)/external/shifter \
        $(MTK_PATH_SOURCE)/external/bessound_HD \
        $(MTK_PATH_SOURCE)/external/bessound \
        $(LOCAL_MTK_PATH)

ifeq ($(IS_LEGACY), 0)
LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/hardware/mtkcam/middleware/common/include
endif

LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/audiocustparam \


ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
LOCAL_CFLAGS += -DMTK_AUDIO
endif

LOCAL_SHARED_LIBRARIES += \
    libmmsdkservice \

LOCAL_MODULE:= mediaserver
LOCAL_32_BIT_ONLY := true

include $(BUILD_EXECUTABLE)
