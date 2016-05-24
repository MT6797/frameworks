LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    service/AudioPolicyService.cpp \
    service/AudioPolicyEffects.cpp

ifeq ($(USE_LEGACY_AUDIO_POLICY), 1)
LOCAL_SRC_FILES += \
    service/AudioPolicyInterfaceImplLegacy.cpp \
    service/AudioPolicyClientImplLegacy.cpp

    LOCAL_CFLAGS += -DUSE_LEGACY_AUDIO_POLICY
else
LOCAL_SRC_FILES += \
    service/AudioPolicyInterfaceImpl.cpp \
    service/AudioPolicyClientImpl.cpp
endif

ifneq ($(MTK_AUDIO_TUNING_TOOL_VERSION),)
  ifneq ($(strip $(MTK_AUDIO_TUNING_TOOL_VERSION)),V1)
    MTK_AUDIO_TUNING_TOOL_V2_PHASE:=$(shell echo $(MTK_AUDIO_TUNING_TOOL_VERSION) | sed 's/V2.//g')

    ifneq ($(MTK_AUDIO_TUNING_TOOL_V2_PHASE),1)
      LOCAL_CFLAGS += -DMTK_NEW_VOL_CONTROL
    endif
  endif
endif

LOCAL_C_INCLUDES := \
    $(TOPDIR)frameworks/av/services/audioflinger \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils) \
    $(TOPDIR)frameworks/av/services/audiopolicy/common/include \
    $(TOPDIR)frameworks/av/services/audiopolicy/engine/interface \

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    liblog \
    libbinder \
    libmedia \
    libhardware \
    libhardware_legacy \
    libserviceutility

ifneq ($(USE_LEGACY_AUDIO_POLICY), 1)
LOCAL_SHARED_LIBRARIES += \
    libaudiopolicymanager
endif

LOCAL_STATIC_LIBRARIES := \
    libmedia_helper \
    libaudiopolicycomponents

LOCAL_MODULE:= libaudiopolicyservice

LOCAL_CFLAGS += -fvisibility=hidden

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO

ifeq ($(strip $(MTK_CROSSMOUNT_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/frameworks/opt/crossmountlib/libcrossmount/include \
    $(MTK_PATH_SOURCE)/hardware/include/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/common/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/utils/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/middleware/common/include \
    $(MTK_PATH_SOURCE)/hardware/gralloc_extra/include \
    $(TOP)/frameworks/av/include/camera \
    $(MTK_PATH_SOURCE)/hardware/include/ \
    $(TOP)/system/media/camera/include/
LOCAL_SHARED_LIBRARIES += libcrossmount
endif

ifeq ($(strip $(MTK_BSP_PACKAGE)),yes)
    LOCAL_CFLAGS += -DMTK_BSP_PACKAGE
endif

ifeq ($(strip $(TARGET_BUILD_VARIANT)),eng)
  LOCAL_CFLAGS += -DCONFIG_MT_ENG_BUILD
endif

ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
endif

ifeq ($(DISABLE_EARPIECE),yes)
    LOCAL_CFLAGS += -DDISABLE_EARPIECE
endif

ifeq ($(MTK_SUPPORT_TC1_TUNNING),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO_GAIN_TABLE
endif

ifeq ($(strip $(MTK_FM_SUPPORT)),yes)
    ifeq ($(strip $(MTK_FM_TX_SUPPORT)),yes)
        ifeq ($(strip $(MTK_FM_TX_AUDIO)),FM_DIGITAL_OUTPUT)
            LOCAL_CFLAGS += -DFM_DIGITAL_OUT_SUPPORT
        endif
    endif
endif

ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

ifeq ($(HAVE_AEE_FEATURE),yes)
    LOCAL_SHARED_LIBRARIES += libaed
    LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/aee/binary/inc
    LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

ifeq ($(MTK_DOLBY_DAP_SUPPORT), yes)
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES_MOVE_EFFECT
        LOCAL_C_INCLUDES += $(TOP)/vendor/dolby/ds1/libds/include/
endif

LOCAL_SHARED_LIBRARIES += \
    libmedia \
    libaudiocustparam

endif

LOCAL_C_INCLUDES += \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/V3/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

include $(BUILD_SHARED_LIBRARY)


ifneq ($(USE_LEGACY_AUDIO_POLICY), 1)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    managerdefault/AudioPolicyManager.cpp \

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    liblog \
    libsoundtrigger

ifeq ($(USE_CONFIGURABLE_AUDIO_POLICY), 1)

LOCAL_REQUIRED_MODULES := \
    parameter-framework.policy \
    audio_policy_criteria.conf \

LOCAL_C_INCLUDES += \
    $(TOPDIR)frameworks/av/services/audiopolicy/engineconfigurable/include \

LOCAL_SHARED_LIBRARIES += libaudiopolicyengineconfigurable

else

LOCAL_SHARED_LIBRARIES += libaudiopolicyenginedefault

endif

LOCAL_C_INCLUDES += \
    $(TOPDIR)frameworks/av/services/audiopolicy/common/include \
    $(TOPDIR)frameworks/av/services/audiopolicy/engine/interface \

LOCAL_STATIC_LIBRARIES := \
    libmedia_helper \
    libaudiopolicycomponents

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO

ifeq ($(strip $(MTK_CROSSMOUNT_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/protect-bsp/frameworks/av/media/libstagefright/cross-mount \
    $(MTK_PATH_SOURCE)/frameworks/opt/crossmountlib/libcrossmount \
    $(MTK_PATH_SOURCE)/hardware/include/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/common/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/utils/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/middleware/common/include \
    $(MTK_PATH_SOURCE)/hardware/gralloc_extra/include \
    $(TOP)/frameworks/av/include/camera \
    $(MTK_PATH_SOURCE)/hardware/include/ \
    $(TOP)/system/media/camera/include/
LOCAL_SHARED_LIBRARIES += libcrossmount
endif

ifeq ($(strip $(MTK_BSP_PACKAGE)),yes)
    LOCAL_CFLAGS += -DMTK_BSP_PACKAGE
endif

ifeq ($(strip $(TARGET_BUILD_VARIANT)),eng)
  LOCAL_CFLAGS += -DCONFIG_MT_ENG_BUILD
endif

ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
endif

ifeq ($(DISABLE_EARPIECE),yes)
    LOCAL_CFLAGS += -DDISABLE_EARPIECE
endif

ifeq ($(MTK_SUPPORT_TC1_TUNNING),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO_GAIN_TABLE
endif

ifeq ($(strip $(MTK_FM_SUPPORT)),yes)
    ifeq ($(strip $(MTK_FM_TX_SUPPORT)),yes)
        ifeq ($(strip $(MTK_FM_TX_AUDIO)),FM_DIGITAL_OUTPUT)
            LOCAL_CFLAGS += -DFM_DIGITAL_OUT_SUPPORT
        endif
    endif
endif

ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

ifeq ($(HAVE_AEE_FEATURE),yes)
    LOCAL_SHARED_LIBRARIES += libaed
    LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/aee/binary/inc
    LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

ifeq ($(MTK_DOLBY_DAP_SUPPORT), yes)
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES_MOVE_EFFECT
        LOCAL_C_INCLUDES += $(TOP)/vendor/dolby/ds1/libds/include/
endif

# MTK Audio Tuning Tool Version
ifneq ($(MTK_AUDIO_TUNING_TOOL_VERSION),)
  ifneq ($(strip $(MTK_AUDIO_TUNING_TOOL_VERSION)),V1)
    MTK_AUDIO_TUNING_TOOL_V2_PHASE:=$(shell echo $(MTK_AUDIO_TUNING_TOOL_VERSION) | sed 's/V2.//g')
    LOCAL_CFLAGS += -DMTK_AUDIO_HIERARCHICAL_PARAM_SUPPORT
    LOCAL_CFLAGS += -DMTK_AUDIO_TUNING_TOOL_V2_PHASE=$(MTK_AUDIO_TUNING_TOOL_V2_PHASE)

    ifneq ($(MTK_AUDIO_TUNING_TOOL_V2_PHASE),1)
      LOCAL_CFLAGS += -DMTK_AUDIO_GAIN_TABLE
      LOCAL_CFLAGS += -DMTK_NEW_VOL_CONTROL
      LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/external/AudioParamParser
      LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/external/AudioParamParser/include
      LOCAL_SHARED_LIBRARIES += libaudio_param_parser
    endif
  endif
endif
# MTK Audio Tuning Tool Version

LOCAL_SHARED_LIBRARIES += \
    libmedia \
    libaudiocustparam

ifneq ($(MTK_AUDIO_TUNING_TOOL_VERSION),)
  ifneq ($(strip $(MTK_AUDIO_TUNING_TOOL_VERSION)),V1)
    MTK_AUDIO_TUNING_TOOL_V2_PHASE:=$(shell echo $(MTK_AUDIO_TUNING_TOOL_VERSION) | sed 's/V2.//g')

    ifneq ($(MTK_AUDIO_TUNING_TOOL_V2_PHASE),1)
      LOCAL_CFLAGS += -DMTK_NEW_VOL_CONTROL
    endif
  endif
endif

endif
LOCAL_C_INCLUDES += \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/V3/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

LOCAL_MODULE:= libaudiopolicymanagerdefault

include $(BUILD_SHARED_LIBRARY)

ifneq ($(USE_CUSTOM_AUDIO_POLICY), 1)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    manager/AudioPolicyFactory.cpp

LOCAL_SHARED_LIBRARIES := \
    libaudiopolicymanagerdefault

LOCAL_STATIC_LIBRARIES := \
    libaudiopolicycomponents

LOCAL_C_INCLUDES += \
    $(TOPDIR)frameworks/av/services/audiopolicy/common/include \
    $(TOPDIR)frameworks/av/services/audiopolicy/engine/interface \

LOCAL_MODULE:= libaudiopolicymanager

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO

ifeq ($(strip $(MTK_CROSSMOUNT_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/protect-bsp/frameworks/av/media/libstagefright/cross-mount \
    $(MTK_PATH_SOURCE)/frameworks/opt/crossmountlib/libcrossmount \
    $(MTK_PATH_SOURCE)/hardware/include/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/common/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/utils/include \
    $(MTK_PATH_SOURCE)/hardware/mtkcam/middleware/common/include \
    $(MTK_PATH_SOURCE)/hardware/gralloc_extra/include \
    $(TOP)/frameworks/av/include/camera \
    $(MTK_PATH_SOURCE)/hardware/include/ \
    $(TOP)/system/media/camera/include/
LOCAL_SHARED_LIBRARIES += libcrossmount
endif


ifeq ($(strip $(MTK_BSP_PACKAGE)),yes)
    LOCAL_CFLAGS += -DMTK_BSP_PACKAGE
endif

ifeq ($(strip $(TARGET_BUILD_VARIANT)),eng)
  LOCAL_CFLAGS += -DCONFIG_MT_ENG_BUILD
endif

ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
endif

ifeq ($(DISABLE_EARPIECE),yes)
    LOCAL_CFLAGS += -DDISABLE_EARPIECE
endif

ifeq ($(MTK_SUPPORT_TC1_TUNNING),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO_GAIN_TABLE
endif

ifeq ($(strip $(MTK_FM_SUPPORT)),yes)
    ifeq ($(strip $(MTK_FM_TX_SUPPORT)),yes)
        ifeq ($(strip $(MTK_FM_TX_AUDIO)),FM_DIGITAL_OUTPUT)
            LOCAL_CFLAGS += -DFM_DIGITAL_OUT_SUPPORT
        endif
    endif
endif

ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

ifeq ($(HAVE_AEE_FEATURE),yes)
    LOCAL_SHARED_LIBRARIES += libaed
    LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/aee/binary/inc
    LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

ifeq ($(MTK_DOLBY_DAP_SUPPORT), yes)
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES_MOVE_EFFECT
        LOCAL_C_INCLUDES += $(TOP)/vendor/dolby/ds1/libds/include/
endif

LOCAL_SHARED_LIBRARIES += \
    libmedia \
    libaudiocustparam

endif
LOCAL_C_INCLUDES += \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/include \
    $(MTK_PATH_SOURCE)/hardware/audio/common/V3/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

include $(BUILD_SHARED_LIBRARY)

endif
endif

#######################################################################
# Recursive call sub-folder Android.mk
#
include $(call all-makefiles-under,$(LOCAL_PATH))
