cmake_minimum_required(VERSION 3.22.1)

project(tree-sitter-javascript C CXX)

# tree-sitter-javascript's own sources, unmodified; see ../../../README.md.
set(GRAMMAR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../grammar)

add_library(tree-sitter-javascript SHARED
	${GRAMMAR_DIR}/src/parser.c
	${GRAMMAR_DIR}/src/scanner.c
	tree-sitter-javascript.cpp)

set_target_properties(tree-sitter-javascript PROPERTIES C_STANDARD 11 CXX_STANDARD 17)
target_include_directories(tree-sitter-javascript PRIVATE ${GRAMMAR_DIR}/src)
target_compile_options(tree-sitter-javascript PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)

find_library(log-lib log)
target_link_libraries(tree-sitter-javascript ${log-lib})
