cmake_minimum_required(VERSION 3.22.1)
project("portmawer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    portmawer
    SHARED
    port_selector.cpp
    connection_tester.cpp
    tunnel_manager.cpp
    jni_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    portmawer
    ${log-lib}
    ${android-lib}
)