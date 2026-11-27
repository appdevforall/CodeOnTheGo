cmake_minimum_required(VERSION 3.22.1)

project(sqlitedbg C)

# Only the headers: the extension runs inside requery's libsqlite3x.so and reaches SQLite through
# the api table it is given (sqlite3ext.h), so no SQLite is compiled or linked here.
set(SQLITE_HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../sqlite-js/upstream/libs)

add_library(sqlitedbg SHARED
	sqlitedbg.c
	sqlitedbg_jni.c)

target_include_directories(sqlitedbg PRIVATE ${SQLITE_HEADERS} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(sqlitedbg PRIVATE -Wall -Wextra -fvisibility=hidden)
# dlopen/dlsym to find sqlite3_auto_extension in requery's libsqlite3x.so
target_link_libraries(sqlitedbg dl)
# Only JNI_OnLoad is exported, so sqlite3_api and our other symbols cannot bind to another library's.
# A change to the version script alone must relink.
set_target_properties(sqlitedbg PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/sqlitedbg.map)
target_link_options(sqlitedbg PRIVATE
	-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sqlitedbg.map
	-Wl,-z,max-page-size=16384)
