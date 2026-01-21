LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)
