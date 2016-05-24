LOCAL_PATH:= $(call my-dir)

LOCAL_MODULE_TAGS := optional

# Set up common build variables
# =====================================================

MCLD_C_INCLUDES := $(LOCAL_PATH)/include

MCLD_SRC_FILES := \
  Main.cpp


MCLD_WHOLE_STATIC_LIBRARIES := \
  libmcldADT \
  libmcldCore \
  libmcldFragment \
  libmcldLD \
  libmcldLDVariant \
  libmcldMC \
  libmcldObject \
  libmcldScript \
  libmcldSupport \
  libmcldTarget

MCLD_SHARED_LIBRARIES := libLLVM

# Collect target specific code generation libraries
MCLD_ARM_LIBS := libmcldARMTarget libmcldARMInfo
MCLD_AARCH64_LIBS := libmcldAArch64Target libmcldAArch64Info
MCLD_MIPS_LIBS := libmcldMipsTarget libmcldMipsInfo
MCLD_X86_LIBS := libmcldX86Target libmcldX86Info

MCLD_MODULE:= ld.mc

# Executable for the device
# =====================================================
include $(CLEAR_VARS)
include $(CLEAR_TBLGEN_VARS)

LOCAL_C_INCLUDES := $(MCLD_C_INCLUDES)
LOCAL_SRC_FILES := $(MCLD_SRC_FILES)
LOCAL_WHOLE_STATIC_LIBRARIES := $(MCLD_WHOLE_STATIC_LIBRARIES)

# Add target specific code generation libraries
LOCAL_WHOLE_STATIC_LIBRARIES_arm += $(MCLD_ARM_LIBS)

# Include ARM libs to enable 32-bit linking on AARCH64 targets
LOCAL_WHOLE_STATIC_LIBRARIES_arm64 += $(MCLD_AARCH64_LIBS) \
                                      $(MCLD_ARM_LIBS)

LOCAL_WHOLE_STATIC_LIBRARIES_mips += $(MCLD_MIPS_LIBS)

# Add x86 libraries for both x86 and x86_64 targets
LOCAL_WHOLE_STATIC_LIBRARIES_x86 += $(MCLD_X86_LIBS)
ifeq ($(BUILD_ARM_FOR_X86),true)
LOCAL_WHOLE_STATIC_LIBRARIES_x86 += $(MCLD_ARM_LIBS) \
                                    $(MCLD_AARCH64_LIBS)
endif

ifeq ($(MTK_GMO_RAM_OPTIMIZE)-$(TARGET_PREFER_32_BIT),yes-yes)
# Build 64-bit executable if there are multiple target architectures.  We need
# 64-bit executable so that mcld can cross-compile different targets.  For
# example, 64-bit mcld is able to generate both arm and arm64 binaries; on the
# contrary, 32-bit mcld is not able to generate arm64 binaries.
LOCAL_MULTILIB := 64
endif

# zlib's libnames are different for the host and target.
# For the target, it is the standard libz
LOCAL_SHARED_LIBRARIES := $(MCLD_SHARED_LIBRARIES) libz

LOCAL_MODULE := $(MCLD_MODULE)
LOCAL_MODULE_CLASS := EXECUTABLES

# Build Options.inc from Options.td for the device
intermediates := $(call local-generated-sources-dir)
LOCAL_GENERATED_SOURCES += $(intermediates)/Options.inc
$(intermediates)/Options.inc: $(LOCAL_PATH)/Options.td $(LLVM_ROOT_PATH)/include/llvm/Option/OptParser.td $(LLVM_TBLGEN)
	$(call transform-device-td-to-out,opt-parser-defs)

include $(MCLD_DEVICE_BUILD_MK)
include $(BUILD_EXECUTABLE)

# Executable for the host
# =====================================================
include $(CLEAR_VARS)
include $(CLEAR_TBLGEN_VARS)

LOCAL_C_INCLUDES := $(MCLD_C_INCLUDES)
LOCAL_SRC_FILES := $(MCLD_SRC_FILES)

LOCAL_WHOLE_STATIC_LIBRARIES := $(MCLD_WHOLE_STATIC_LIBRARIES)
LOCAL_WHOLE_STATIC_LIBRARIES += $(MCLD_ARM_LIBS) \
                                $(MCLD_AARCH64_LIBS) \
                                $(MCLD_MIPS_LIBS) \
                                $(MCLD_X86_LIBS)

# zlib's libnames are different for the host and target.
# For the host, it is libz-host
LOCAL_SHARED_LIBRARIES := $(MCLD_SHARED_LIBRARIES) libz-host

LOCAL_MODULE := $(MCLD_MODULE)
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_IS_HOST_MODULE := true

# Build Options.inc from Options.td for the host
intermediates := $(call local-generated-sources-dir)
LOCAL_GENERATED_SOURCES += $(intermediates)/Options.inc
$(intermediates)/Options.inc: $(LOCAL_PATH)/Options.td $(LLVM_ROOT_PATH)/include/llvm/Option/OptParser.td $(LLVM_TBLGEN)
	$(call transform-host-td-to-out,opt-parser-defs)

include $(MCLD_HOST_BUILD_MK)
include $(BUILD_HOST_EXECUTABLE)
