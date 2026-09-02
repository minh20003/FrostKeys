# SPDX-License-Identifier: GPL-3.0-only
#
# Builds the only compiled Boost component used by the locked librime core.
# All other Boost facilities used by librime 1.16.1 are header-only.  Keeping
# this tiny project separate from the upstream checkout lets the Rime builder
# cross-compile a clean source tree without patching it or relying on b2.

cmake_minimum_required(VERSION 3.22)
project(frostkeys_rime_boost_regex LANGUAGES CXX)

if(NOT DEFINED BOOST_SOURCE_ROOT OR NOT IS_DIRECTORY "${BOOST_SOURCE_ROOT}")
  message(FATAL_ERROR "BOOST_SOURCE_ROOT must name an extracted Boost source tree")
endif()

if(NOT DEFINED BOOST_INCLUDE_ROOT OR NOT IS_DIRECTORY "${BOOST_INCLUDE_ROOT}")
  message(FATAL_ERROR "BOOST_INCLUDE_ROOT must name a prepared aggregate Boost header tree")
endif()

if(NOT EXISTS "${BOOST_INCLUDE_ROOT}/boost/version.hpp")
  message(FATAL_ERROR "BOOST_INCLUDE_ROOT does not contain boost/version.hpp")
endif()

file(TO_CMAKE_PATH "${BOOST_SOURCE_ROOT}" boost_source_root_normalized)
file(TO_CMAKE_PATH "${BOOST_INCLUDE_ROOT}" boost_include_root_normalized)
file(GLOB boost_regex_sources CONFIGURE_DEPENDS "${boost_source_root_normalized}/libs/regex/src/*.cpp")
if(NOT boost_regex_sources)
  message(FATAL_ERROR "Boost.Regex sources are missing below ${BOOST_SOURCE_ROOT}")
endif()

add_library(boost_regex STATIC ${boost_regex_sources})
target_include_directories(boost_regex PUBLIC "${boost_include_root_normalized}")
target_compile_definitions(boost_regex PRIVATE BOOST_REGEX_NO_LIB BOOST_REGEX_NO_ICU)
set_target_properties(boost_regex PROPERTIES
  OUTPUT_NAME boost_regex
  POSITION_INDEPENDENT_CODE ON)

include(GNUInstallDirs)
install(TARGETS boost_regex ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(DIRECTORY "${boost_include_root_normalized}/boost/" DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/boost")
