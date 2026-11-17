# Desktop runner for Basalt over Marco recordings (see basalt_runner.cpp).
#
#   cmake -S vision/tools/basalt_runner -B build/runner -DBASALT_LIB=/path/to/libbasalt.so
#   cmake --build build/runner
cmake_minimum_required(VERSION 3.20)
project(basalt_runner CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(OpenCV REQUIRED COMPONENTS core imgcodecs)
set(BASALT_LIB "" CACHE FILEPATH "libbasalt.so built from vision/third_party/basalt (library preset)")
if(NOT BASALT_LIB)
  message(FATAL_ERROR "set BASALT_LIB to the libbasalt.so to run")
endif()

add_executable(basalt_runner basalt_runner.cpp)
target_include_directories(basalt_runner PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../third_party/basalt/thirdparty/vit ${OpenCV_INCLUDE_DIRS})
target_link_libraries(basalt_runner PRIVATE ${BASALT_LIB} ${OpenCV_LIBS})
install(TARGETS basalt_runner DESTINATION bin)
