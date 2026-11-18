#!/bin/zsh
# Gets PRoot for arm64 from the Termux package repository and puts it in app/src/main/jniLibs.
# DESIGN §3: Android runs programs only from the native library folder of the APK, so each
# program has a lib*.so name. The script checks each package with a pinned SHA-256.
# The script also changes the name "libtalloc.so.2" to "libtalloc.so" in two ELF string tables
# (same place, shorter string), because the APK can hold only lib*.so files.
# Sources: https://github.com/termux/proot (GPL-2.0), https://talloc.samba.org (LGPL-3.0),
# https://github.com/termux/libandroid-shmem (BSD-3-Clause).
set -euo pipefail

ROOT="${0:A:h:h}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
REPO="https://packages-cf.termux.dev/apt/termux-main"
PREFIX="data/data/com.termux/files/usr"

# name|pool path|sha256
PACKAGES=(
  "proot|pool/main/p/proot/proot_5.1.107.102_aarch64.deb|10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"
  "libtalloc|pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb|556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "libandroid-shmem|pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb|0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
)

WORK="$(mktemp -d /tmp/automa-proot.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

for entry in "${PACKAGES[@]}"; do
  IFS='|' read -r name pool sha <<< "$entry"
  mkdir -p "$WORK/$name/x"
  curl -fsSL --proto '=https' --tlsv1.2 -o "$WORK/$name/pkg.deb" "$REPO/$pool"
  print "$sha  $WORK/$name/pkg.deb" | shasum -a 256 -c - > /dev/null
  # bsdtar reads the ar container of a .deb.
  tar -xf "$WORK/$name/pkg.deb" -C "$WORK/$name"
  tar -xf "$WORK/$name"/data.tar.* -C "$WORK/$name/x"
done

mkdir -p "$OUT"
install -m 644 "$WORK/proot/x/$PREFIX/bin/proot" "$OUT/libproot.so"
install -m 644 "$WORK/proot/x/$PREFIX/libexec/proot/loader" "$OUT/libproot-loader.so"
install -m 644 "$WORK/libtalloc/x/$PREFIX/lib/libtalloc.so.2.5.0" "$OUT/libtalloc.so"
install -m 644 "$WORK/libandroid-shmem/x/$PREFIX/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"

# DT_NEEDED of proot and DT_SONAME of talloc: "libtalloc.so.2\0" -> "libtalloc.so\0\0\0".
python3 -I - "$OUT/libproot.so" "$OUT/libtalloc.so" <<'EOF'
import sys
old, new = b"libtalloc.so.2\0", b"libtalloc.so\0\0\0"
for path in sys.argv[1:]:
    data = open(path, "rb").read()
    if data.count(old) != 1:
        sys.exit(f"{path}: expected one '{old!r}', found {data.count(old)}")
    open(path, "wb").write(data.replace(old, new))
EOF

# The files in git must be the output of this recipe: the SHA-256 of each file after the patch.
shasum -a 256 -c - <<EOF2
84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731  $OUT/libandroid-shmem.so
1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5  $OUT/libproot-loader.so
aad737c5b546ee7471f133be90ef20f765aa98a303522557ceed3dff76605085  $OUT/libproot.so
160d39bc0d7ed946f22310d1d6f20addda21b02b0972dffe929fe49951543941  $OUT/libtalloc.so
EOF2
