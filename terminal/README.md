# terminal

This module is a copy of the `terminal-emulator` and `terminal-view` libraries of
[termux-app](https://github.com/termux/termux-app), tag `v0.118.3`
(source archive SHA-256 `5511ed1b9470be051181173d7571b6b66faea56c79a5fe7e2fc613cc432dd394`).

Licence: Apache License 2.0. These two libraries come from
[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator). The other
parts of termux-app are GPL-3.0 only, and Automa does not use them.

Changes from upstream:

- One module for the two libraries. CMake builds `termux.c` (upstream uses ndk-build).
- The `androidx.annotation` imports and annotations are removed, so that the module has no
  dependencies.
- Changes after the first copy are marked with `Automa:` comments.
