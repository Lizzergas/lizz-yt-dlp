cmake_minimum_required(VERSION 3.22.1)
project(ytdl_android_media)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)

if(NOT DEFINED LAME_ROOT)
    message(FATAL_ERROR "LAME_ROOT must be provided by the Gradle build")
endif()

add_library(
    ytdl_android_media
    SHARED
    ytdl_android_media.cpp
    ${LAME_ROOT}/libmp3lame/bitstream.c
    ${LAME_ROOT}/libmp3lame/encoder.c
    ${LAME_ROOT}/libmp3lame/fft.c
    ${LAME_ROOT}/libmp3lame/gain_analysis.c
    ${LAME_ROOT}/libmp3lame/id3tag.c
    ${LAME_ROOT}/libmp3lame/lame.c
    ${LAME_ROOT}/libmp3lame/newmdct.c
    ${LAME_ROOT}/libmp3lame/presets.c
    ${LAME_ROOT}/libmp3lame/psymodel.c
    ${LAME_ROOT}/libmp3lame/quantize.c
    ${LAME_ROOT}/libmp3lame/quantize_pvt.c
    ${LAME_ROOT}/libmp3lame/reservoir.c
    ${LAME_ROOT}/libmp3lame/set_get.c
    ${LAME_ROOT}/libmp3lame/tables.c
    ${LAME_ROOT}/libmp3lame/takehiro.c
    ${LAME_ROOT}/libmp3lame/util.c
    ${LAME_ROOT}/libmp3lame/vbrquantize.c
    ${LAME_ROOT}/libmp3lame/VbrTag.c
    ${LAME_ROOT}/libmp3lame/version.c
)

target_include_directories(
    ytdl_android_media
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LAME_ROOT}/include
    ${LAME_ROOT}/libmp3lame
)

target_compile_definitions(
    ytdl_android_media
    PRIVATE
    HAVE_CONFIG_H=1
)

target_link_options(
    ytdl_android_media
    PRIVATE
    -Wl,-z,max-page-size=16384
)

find_library(log-lib log)
target_link_libraries(ytdl_android_media PRIVATE ${log-lib})
