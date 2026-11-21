cmake_minimum_required(VERSION 3.22.1)
project(kuheli_native CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(kuheli_native SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(kuheli_native ${log-lib})

# nodejs-mobile is optional. scripts/fetch_nodejs_mobile.sh places it in app/libnode.
set(LIBNODE_DIR ${CMAKE_SOURCE_DIR}/../../../libnode)
if(EXISTS ${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so AND EXISTS ${LIBNODE_DIR}/include/node/node.h)
    message(STATUS "Kuheli: embedding Node.js from ${LIBNODE_DIR}")
    add_library(node SHARED IMPORTED)
    set_target_properties(node PROPERTIES IMPORTED_LOCATION ${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so)
    target_include_directories(kuheli_native PRIVATE ${LIBNODE_DIR}/include/node)
    target_compile_definitions(kuheli_native PRIVATE HAVE_NODE=1)
    target_link_libraries(kuheli_native node)
else()
    message(STATUS "Kuheli: libnode not found, building without embedded Node.js")
endif()
