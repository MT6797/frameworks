LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := SettingsLib

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
# M: Add for MTK resource
LOCAL_RESOURCE_DIR += $(LOCAL_PATH)/res_ext
LOCAL_AAPT_FLAGS := \
--auto-add-overlay

LOCAL_JAVA_LIBRARIES := ims-common \
                        mediatek-framework \

LOCAL_STATIC_JAVA_LIBRARIES := com.mediatek.settingslib.ext

LOCAL_SRC_FILES := $(call all-java-files-under, src)

include $(BUILD_STATIC_JAVA_LIBRARY)

# This finds and builds ext as well.
include $(call all-makefiles-under,$(LOCAL_PATH))
