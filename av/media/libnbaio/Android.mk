LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    AudioBufferProviderSource.cpp   \
    AudioStreamOutSink.cpp          \
    AudioStreamInSource.cpp         \
    NBAIO.cpp                       \
    MonoPipe.cpp                    \
    MonoPipeReader.cpp              \
    Pipe.cpp                        \
    PipeReader.cpp                  \
    SourceAudioBufferProvider.cpp

LOCAL_SRC_FILES += NBLog.cpp

# libsndfile license is incompatible; uncomment to use for local debug only
#LOCAL_SRC_FILES += LibsndfileSink.cpp LibsndfileSource.cpp
#LOCAL_C_INCLUDES += path/to/libsndfile/src
#LOCAL_STATIC_LIBRARIES += libsndfile

# uncomment for systrace
# LOCAL_CFLAGS += -DATRACE_TAG=ATRACE_TAG_AUDIO

ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
  ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),yes)
     LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
  endif
endif

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
LOCAL_C_INCLUDES+= \
   $(MTK_PATH_SOURCE)/hardware/audio/common/include
endif

LOCAL_MODULE := libnbaio

LOCAL_SHARED_LIBRARIES := \
    libaudioutils \
    libbinder \
    libcommon_time_client \
    libcutils \
    libutils \
    liblog

LOCAL_C_INCLUDES := $(call include-path-for, audio-utils)

include $(BUILD_SHARED_LIBRARY)
