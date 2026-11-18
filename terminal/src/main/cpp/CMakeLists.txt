cmake_minimum_required(VERSION 3.22.1)
project(termux C)

# JNI part of the Termux terminal-emulator: opens a pseudo-terminal and starts the child process.
add_library(termux SHARED termux.c)
target_link_options(termux PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
