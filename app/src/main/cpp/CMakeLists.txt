cmake_minimum_required(VERSION 3.22)
project(graden_softenc CXX)

# x264 and x265 are built for arm64 from source (see third_party/*-COPYING.txt: both are GPL).
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
add_library(x264 STATIC IMPORTED)
set_target_properties(x264 PROPERTIES IMPORTED_LOCATION ${TP}/lib/${ANDROID_ABI}/libx264.a)
add_library(x265 STATIC IMPORTED)
set_target_properties(x265 PROPERTIES IMPORTED_LOCATION ${TP}/lib/${ANDROID_ABI}/libx265.a)

add_library(softenc SHARED softenc.cpp)
target_include_directories(softenc PRIVATE ${TP}/include)
target_compile_options(softenc PRIVATE -O3 -ffast-math)
target_link_libraries(softenc x265 x264 log -static-libstdc++)
target_link_options(softenc PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s)
