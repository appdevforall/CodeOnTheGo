# Specifies the minimum version of CMake required.
cmake_minimum_required(VERSION 3.18.1)

# Defines the project name. This is for organizational purposes.
project("androididellm")

# Add the llama.cpp source directory to the include path.
# This allows source files to find header files like "llama.h".
include_directories(llama)

# Use glob to find all C and C++ source files within the llama directory.
# This is a convenient way to include all necessary files without listing them manually.
# NOTE: If llama.cpp source files are added or removed in future updates,
# a Gradle sync will be required to update the file list.
file(GLOB LLAMA_SRC "llama/*.c" "llama/*.cpp")

# Define the native library.
# - NAME: 'android-ide-llm' will be the name of our library.
#         Android build tools will prepend 'lib' and append '.so',
#         resulting in 'libandroid-ide-llm.so'.
# - TYPE: 'SHARED' specifies that we are building a dynamic library that
#         can be loaded by the Android app at runtime.
# - SOURCES: The list of all source files to compile.
add_library(
        android-ide-llm
        SHARED
        ${LLAMA_SRC}
        # The JNI bridge file will be added later.
        jni-bridge.cpp
)

# Find standard Android NDK libraries required by llama.cpp.
# The 'find_library' command locates a prebuilt library and stores its path in a variable.
# - 'log-lib': For using Android's native logging capabilities (Log.d, etc.).
# - 'android': For accessing native Android APIs, such as the Asset Manager.
find_library(log-lib log)
find_library(android-lib android)

# Link the found NDK libraries to our target library.
# This ensures that our 'android-ide-llm' library can call functions
# from the Android logging and core native libraries.
target_link_libraries(
        android-ide-llm
        ${log-lib}
        ${android-lib}
)
