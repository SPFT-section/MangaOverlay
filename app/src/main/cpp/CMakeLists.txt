cmake_minimum_required(VERSION 3.18.1)
project(mocalc LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(mocalc SHARED calc.cpp jni_bridge.cpp)
target_compile_options(mocalc PRIVATE -O2 -Wall -Wextra -Wshadow -fstack-protector-strong -D_FORTIFY_SOURCE=2)
# 16 KB page size (Android 15+ / Play) และตัดสัญลักษณ์ที่ไม่ใช้
target_link_options(mocalc PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,-z,relro,-z,now)
