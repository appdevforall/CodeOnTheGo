cmake_minimum_required(VERSION 3.22.1)

project(sqlitejs C)

# sqlite-js with our patches applied; see ../../../README.md and ../../../vendor.sh
set(UPSTREAM ${CMAKE_CURRENT_SOURCE_DIR}/../../../upstream)

add_library(sqlitejs SHARED
	${UPSTREAM}/src/sqlitejs.c
	${UPSTREAM}/libs/quickjs.c
	sqlitejs_android.c)

target_include_directories(sqlitejs PRIVATE ${UPSTREAM}/src ${UPSTREAM}/libs)
target_compile_definitions(sqlitejs PRIVATE QJS_BUILD_LIBC)
target_link_libraries(sqlitejs m)
# Only the entry points and JNI_OnLoad: SQLite opens extensions with RTLD_GLOBAL, so an exported
# QuickJS function or sqlite3_api could bind to another extension's copy.
target_link_options(sqlitejs PRIVATE
	-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sqlitejs_android.map
	-Wl,-z,max-page-size=16384)
