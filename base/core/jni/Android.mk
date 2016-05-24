LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_CFLAGS += -DHAVE_CONFIG_H -DKHTML_NO_EXCEPTIONS -DGKWQ_NO_JAVA
LOCAL_CFLAGS += -DNO_SUPPORT_JS_BINDING -DQT_NO_WHEELEVENT -DKHTML_NO_XBL
LOCAL_CFLAGS += -U__APPLE__
LOCAL_CFLAGS += -Wno-unused-parameter
LOCAL_CFLAGS += -Wno-non-virtual-dtor
LOCAL_CFLAGS += -Wno-maybe-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null

ifeq ($(TARGET_ARCH), arm)
    LOCAL_CFLAGS += -DPACKED="__attribute__ ((packed))"
else
    LOCAL_CFLAGS += -DPACKED=""
endif

ifneq ($(ENABLE_CPUSETS),)
    LOCAL_CFLAGS += -DENABLE_CPUSETS
endif

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_CFLAGS += -DU_USING_ICU_NAMESPACE=0

LOCAL_SRC_FILES:= \
    AndroidRuntime.cpp \
    com_android_internal_content_NativeLibraryHelper.cpp \
    com_google_android_gles_jni_EGLImpl.cpp \
    com_google_android_gles_jni_GLImpl.cpp.arm \
    com_mediatek_perfservice_PerfServiceWrapper.cpp \
    com_mediatek_aal_AalUtils.cpp \
    android_app_NativeActivity.cpp \
    android_opengl_EGL14.cpp \
    android_opengl_EGLExt.cpp \
    android_opengl_GLES10.cpp \
    android_opengl_GLES10Ext.cpp \
    android_opengl_GLES11.cpp \
    android_opengl_GLES11Ext.cpp \
    android_opengl_GLES20.cpp \
    android_opengl_GLES30.cpp \
    android_opengl_GLES31.cpp \
    android_opengl_GLES31Ext.cpp \
    android_database_CursorWindow.cpp \
    android_database_SQLiteCommon.cpp \
    android_database_SQLiteConnection.cpp \
    android_database_SQLiteGlobal.cpp \
    android_database_SQLiteDebug.cpp \
    android_emoji_EmojiFactory.cpp \
    android_view_DisplayEventReceiver.cpp \
    android_view_DisplayListCanvas.cpp \
    android_view_GraphicBuffer.cpp \
    android_view_HardwareLayer.cpp \
    android_view_InputChannel.cpp \
    android_view_InputDevice.cpp \
    android_view_InputEventReceiver.cpp \
    android_view_InputEventSender.cpp \
    android_view_InputQueue.cpp \
    android_view_KeyCharacterMap.cpp \
    android_view_KeyEvent.cpp \
    android_view_MotionEvent.cpp \
    android_view_PointerIcon.cpp \
    android_view_RenderNode.cpp \
    android_view_RenderNodeAnimator.cpp \
    android_view_Surface.cpp \
    android_view_SurfaceControl.cpp \
    android_view_SurfaceSession.cpp \
    android_view_TextureView.cpp \
    android_view_ThreadedRenderer.cpp \
    android_view_VelocityTracker.cpp \
    android_text_AndroidCharacter.cpp \
    android_text_AndroidBidi.cpp \
    android_text_StaticLayout.cpp \
    android_os_Debug.cpp \
    android_os_MemoryFile.cpp \
    android_os_MessageQueue.cpp \
    android_os_Parcel.cpp \
    android_os_SELinux.cpp \
    android_os_SystemClock.cpp \
    android_os_SystemProperties.cpp \
    android_os_Trace.cpp \
    android_os_UEventObserver.cpp \
    android_net_LocalSocketImpl.cpp \
    android_net_NetUtils.cpp \
    android_net_TrafficStats.cpp \
    android_nio_utils.cpp \
    android_util_AssetManager.cpp \
    android_util_Binder.cpp \
    android_util_EventLog.cpp \
    android_util_Log.cpp \
    android_util_Process.cpp \
    android_util_StringBlock.cpp \
    android_util_XmlBlock.cpp \
    android_graphics_Canvas.cpp \
    android_graphics_Picture.cpp \
    android/graphics/AutoDecodeCancel.cpp \
    android/graphics/AvoidXfermode.cpp \
    android/graphics/Bitmap.cpp \
    android/graphics/BitmapFactory.cpp \
    android/graphics/Camera.cpp \
    android/graphics/CanvasProperty.cpp \
    android/graphics/ColorFilter.cpp \
    android/graphics/DrawFilter.cpp \
    android/graphics/FontFamily.cpp \
    android/graphics/CreateJavaOutputStreamAdaptor.cpp \
    android/graphics/Graphics.cpp \
    android/graphics/HarfBuzzNGFaceSkia.cpp \
    android/graphics/Interpolator.cpp \
    android/graphics/MaskFilter.cpp \
    android/graphics/Matrix.cpp \
    android/graphics/MinikinSkia.cpp \
    android/graphics/MinikinUtils.cpp \
    android/graphics/Movie.cpp \
    android/graphics/NinePatch.cpp \
    android/graphics/NinePatchImpl.cpp \
    android/graphics/NinePatchPeeker.cpp \
    android/graphics/Paint.cpp \
    android/graphics/PaintImpl.cpp \
    android/graphics/Path.cpp \
    android/graphics/PathMeasure.cpp \
    android/graphics/PathEffect.cpp \
    android/graphics/Picture.cpp \
    android/graphics/PorterDuff.cpp \
    android/graphics/BitmapRegionDecoder.cpp \
    android/graphics/Rasterizer.cpp \
    android/graphics/Region.cpp \
    android/graphics/Shader.cpp \
    android/graphics/SurfaceTexture.cpp \
    android/graphics/Typeface.cpp \
    android/graphics/TypefaceImpl.cpp \
    android/graphics/Utils.cpp \
    android/graphics/Xfermode.cpp \
    android/graphics/YuvToJpegEncoder.cpp \
    android/graphics/pdf/PdfDocument.cpp \
    android/graphics/pdf/PdfEditor.cpp \
    android/graphics/pdf/PdfRenderer.cpp \
    android_media_AudioRecord.cpp \
    android_media_AudioSystem.cpp \
    android_media_AudioTrack.cpp \
    android_media_DeviceCallback.cpp \
    android_media_JetPlayer.cpp \
    android_media_RemoteDisplay.cpp \
    android_media_ToneGenerator.cpp \
    android_hardware_Camera.cpp \
    android_hardware_camera2_CameraMetadata.cpp \
    android_hardware_camera2_legacy_LegacyCameraDevice.cpp \
    android_hardware_camera2_legacy_PerfMeasurement.cpp \
    android_hardware_camera2_DngCreator.cpp \
    android_hardware_Radio.cpp \
    android_hardware_SensorManager.cpp \
    android_hardware_SerialPort.cpp \
    android_hardware_SoundTrigger.cpp \
    android_hardware_UsbDevice.cpp \
    android_hardware_UsbDeviceConnection.cpp \
    android_hardware_UsbRequest.cpp \
    android_hardware_location_ActivityRecognitionHardware.cpp \
    android_util_FileObserver.cpp \
    android/opengl/poly_clip.cpp.arm \
    android/opengl/util.cpp \
    android_server_NetworkManagementSocketTagger.cpp \
    android_server_Watchdog.cpp \
    android_ddm_DdmHandleNativeHeap.cpp \
    android_backup_BackupDataInput.cpp \
    android_backup_BackupDataOutput.cpp \
    android_backup_FileBackupHelperBase.cpp \
    android_backup_BackupHelperDispatcher.cpp \
    android_app_backup_FullBackup.cpp \
    android_content_res_ObbScanner.cpp \
    android_content_res_Configuration.cpp \
    android_animation_PropertyValuesHolder.cpp \
    com_android_internal_net_NetworkStatsFactory.cpp \
    com_android_internal_os_Zygote.cpp \
    com_android_internal_util_VirtualRefBasePtr.cpp \
    com_android_internal_view_animation_NativeInterpolatorFactoryHelper.cpp

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    $(LOCAL_PATH)/android/graphics \
    $(LOCAL_PATH)/../../libs/hwui \
    $(LOCAL_PATH)/../../../native/opengl/libs \
    $(call include-path-for, bluedroid) \
    $(call include-path-for, libhardware)/hardware \
    $(call include-path-for, libhardware_legacy)/hardware_legacy \
    $(TOP)/frameworks/av/include \
    $(TOP)/frameworks/base/media/jni \
    $(TOP)/system/media/camera/include \
    $(TOP)/system/netd/include \
    external/pdfium/core/include/fpdfapi \
    external/pdfium/core/include/fpdfdoc \
    external/pdfium/fpdfsdk/include \
    external/pdfium/public \
    external/skia/src/core \
    external/skia/src/effects \
    external/skia/src/images \
    external/sqlite/dist \
    external/sqlite/android \
    external/expat/lib \
    external/tremor/Tremor \
    external/jpeg \
    external/harfbuzz_ng/src \
    frameworks/opt/emoji \
    libcore/include \
    $(MTK_ROOT)/external/sqlite_custom \
    $(call include-path-for, audio-utils) \
    frameworks/minikin/include \
    external/freetype/include
# TODO: clean up Minikin so it doesn't need the freetype include

LOCAL_SHARED_LIBRARIES := \
    libmemtrack \
    libandroidfw \
    libexpat \
    libnativehelper \
    liblog \
    libcutils \
    libutils \
    libbinder \
    libnetutils \
    libui \
    libgui \
    libinput \
    libinputflinger \
    libcamera_client \
    libcamera_metadata \
    libskia \
    libsqlite \
    libEGL \
    libGLESv1_CM \
    libGLESv2 \
    libETC1 \
    libhardware \
    libhardware_legacy \
    libselinux \
    libsonivox \
    libcrypto \
    libssl \
    libicuuc \
    libicui18n \
    libmedia \
    libjpeg \
    libusbhost \
    libharfbuzz_ng \
    libz \
    libaudioutils \
    libpdfium \
    libimg_utils \
    libnetd_client \
    libradio \
    libsoundtrigger \
    libminikin \
    libprocessgroup \
    libnativebridge \
    libradio_metadata

# App-based AAL @{
ifeq ($(strip $(MTK_AAL_SUPPORT)), yes)
  LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/hardware/aal/include
  LOCAL_SHARED_LIBRARIES += libaal
  LOCAL_CFLAGS += -DMTK_AAL_SUPPORT
endif
# App-based AAL @}

ifeq ($(MTK_SEARCH_DB_SUPPORT), yes)
	LOCAL_SHARED_LIBRARIES += libmtksqlite3_android
endif

ifneq ($(strip $(TARGET_BUILD_VARIANT)), eng)
	LOCAL_CFLAGS += -DMTK_USER_BUILD
endif

ifeq ($(strip $(MTK_GMO_RAM_OPTIMIZE)), yes)
	LOCAL_CFLAGS += -DMTK_HWUI_RAM_OPTIMIZE
endif

# For MTK Sink feature
ifeq ($(strip $(MTK_WFD_SINK_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_SUPPORT

# For MTK Sink UIBC feature
ifeq ($(strip $(MTK_WFD_SINK_UIBC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_UIBC_SUPPORT
endif
endif

ifeq ($(MTK_MIRAVISION_IMAGE_DC_SUPPORT),yes)
  LOCAL_CFLAGS += -DMTK_IMAGE_DC_SUPPORT
endif

#!++
#ifeq "yes" "$(strip $(MTK_CAMERA_BSP_SUPPORT))"
    LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/hardware/mtkcam/ext/include
    LOCAL_SHARED_LIBRARIES += libmtkcamera_client
#endif
#!--

ifeq ($(MTK_SEARCH_DB_SUPPORT),yes)
LOCAL_CFLAGS += -D MTK_DIALER_SEARCH_SUPPORT
LOCAL_C_INCLUDES += mediatek/external/sqlite/custom
endif

LOCAL_SHARED_LIBRARIES += \
    libhwui \
    libdl

# we need to access the private Bionic header
# <bionic_tls.h> in com_google_android_gles_jni_GLImpl.cpp
LOCAL_C_INCLUDES += bionic/libc/private

LOCAL_CFLAGS += -DMTK_SKIA_MULTI_THREAD_JPEG_REGION


ifeq ($(MTK_AUDIO),yes)
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/hardware/audio/common/include
endif

LOCAL_MODULE:= libandroid_runtime

# -Wno-unknown-pragmas: necessary for Clang as the GL bindings need to turn
#                       off a GCC warning that Clang doesn't know.
LOCAL_CFLAGS += -Wall -Werror -Wno-error=deprecated-declarations -Wunused -Wunreachable-code \
        -Wno-unknown-pragmas

# -Wno-c++11-extensions: Clang warns about Skia using the C++11 override keyword, but this project
#                        is not being compiled with that level. Remove once this has changed.
LOCAL_CFLAGS += -Wno-c++11-extensions

# b/22414716: thread_local (android/graphics/Paint.cpp) and Clang don't like each other at the
#             moment.
LOCAL_CLANG := false

ifneq (true, $(WITH_DEXPREOPT))
  LOCAL_CFLAGS += -DMTK_CHECK_OAT_BUILD_FLOW
endif

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
