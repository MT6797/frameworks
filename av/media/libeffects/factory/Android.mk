LOCAL_PATH:= $(call my-dir)

# Effect factory library
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	EffectsFactory.c

LOCAL_SHARED_LIBRARIES := \
	libcutils liblog

LOCAL_MODULE:= libeffects

LOCAL_SHARED_LIBRARIES += libdl

LOCAL_C_INCLUDES := \
    $(call include-path-for, audio-effects)
#ifeq ($(MTK_AUDIO),yes)
#LOCAL_CFLAGS += -DMTK_AUDIO
    LOCAL_C_INCLUDES+= \
      $(MTK_PATH_SOURCE)/hardware/audio/common/include/
#endif

include $(BUILD_SHARED_LIBRARY)
