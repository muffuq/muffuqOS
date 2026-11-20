cmake_minimum_required(VERSION 3.22.1)
project(muffuq_native C CXX)

# playfair/: FairPlay (SAP) key decryption for AirPlay screen mirroring, unchanged from UxPlay (GPL-3.0,
#            see playfair/LICENSE.md). Only the AES session key of a mirroring session goes through it.
# alac/:     Apple Lossless decoder, unchanged from Apple's open-source release (Apache-2.0, see file headers),
#            for AirPlay audio sent from the Music app & co.
file(GLOB PLAYFAIR_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/playfair/*.c)
set(ALAC_SOURCES
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c)

add_library(muffuq_native SHARED fairplay_jni.c alac_jni.cpp ${PLAYFAIR_SOURCES} ${ALAC_SOURCES})
target_include_directories(muffuq_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/playfair ${CMAKE_CURRENT_SOURCE_DIR}/alac)
target_compile_options(muffuq_native PRIVATE -O2 -Wno-unused-parameter -Wno-pointer-sign -Wno-deprecated-declarations)
# Apple's EndianPortable.c only recognises x86 as little-endian; on ARM it would skip the byte swaps and read the
# ALAC config as garbage. Every Android ABI is little-endian.
target_compile_definitions(muffuq_native PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
