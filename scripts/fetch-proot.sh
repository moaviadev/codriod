#!/usr/bin/env bash
# Puts proot (and the talloc library it needs), built for Android by the Termux project, into
# app/src/main/jniLibs/<abi>/ as lib*.so files. Android installs those in the app's native library
# folder, the only app folder it lets an app execute programs from (Android 10+ "W^X" rule).
#
#   libproot.so          proot itself
#   libproot-loader.so   proot's loader (runs the Ubuntu programs)
#   libproot-loader32.so loader for 32-bit programs (64-bit phones only)
#   libtalloc.so         talloc, which proot links against
#   libandroid-shmem.so  System V shared memory for Android (proot --sysvipc)
#
# proot is GPL-2.0 (https://github.com/termux/proot), talloc LGPL-3.0 (https://talloc.samba.org).
# Needs: bash, curl, ar (binutils), tar with xz, sha256sum, patchelf, readelf.
set -euo pipefail

REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
OUT="${1:-app/src/main/jniLibs}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { curl -fsSL --retry 5 --retry-delay 3 --connect-timeout 20 "$@"; }

# field <Packages file> <package> -> "Filename SHA256"
field() {
  awk -v p="$2" 'BEGIN { RS = ""; FS = "\n" }
    { name = ""; f = ""; s = ""
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^Package: /) name = substr($i, 10)
        else if ($i ~ /^Filename: /) f = substr($i, 11)
        else if ($i ~ /^SHA256: /) s = substr($i, 9)
      }
      if (name == p) { print f, s; exit }
    }' "$1"
}

for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch="${pair%%:*}"; abi="${pair##*:}"
  dir="$WORK/$arch"; mkdir -p "$dir"
  echo "== $arch ($abi)"
  fetch "$REPO/dists/stable/main/binary-$arch/Packages" -o "$dir/Packages"
  for pkg in proot libtalloc libandroid-shmem; do
    read -r file sha < <(field "$dir/Packages" "$pkg")
    if [ -z "${file:-}" ] || [ -z "${sha:-}" ]; then echo "error: $pkg not found for $arch" >&2; exit 1; fi
    echo "   $pkg: $file"
    fetch "$REPO/$file" -o "$dir/$pkg.deb"
    echo "$sha  $dir/$pkg.deb" | sha256sum -c --quiet -
    mkdir -p "$dir/$pkg"
    (cd "$dir/$pkg" && ar x "../$pkg.deb" && tar -xf data.tar.*)
  done
  usr="$dir/proot/data/data/com.termux/files/usr"
  tusr="$dir/libtalloc/data/data/com.termux/files/usr"
  mkdir -p "$OUT/$abi"
  install -m 0755 "$usr/bin/proot" "$OUT/$abi/libproot.so"
  install -m 0755 "$usr/libexec/proot/loader" "$OUT/$abi/libproot-loader.so"
  if [ -f "$usr/libexec/proot/loader32" ]; then install -m 0755 "$usr/libexec/proot/loader32" "$OUT/$abi/libproot-loader32.so"; fi
  talloc="$(ls "$tusr"/lib/libtalloc.so.2* | head -n 1)"
  install -m 0644 "$(readlink -f "$talloc")" "$OUT/$abi/libtalloc.so"
  # Android only loads lib*.so names from the APK, so link proot against "libtalloc.so" and drop
  # Termux's library path; the app sets LD_LIBRARY_PATH to its native library folder.
  patchelf --set-soname libtalloc.so "$OUT/$abi/libtalloc.so"
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$OUT/$abi/libproot.so"
  shm="$dir/libandroid-shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so"
  install -m 0644 "$(readlink -f "$shm")" "$OUT/$abi/libandroid-shmem.so"
  patchelf --remove-rpath "$OUT/$abi/libproot.so" || true
  patchelf --remove-rpath "$OUT/$abi/libandroid-shmem.so" || true
  for so in libproot.so libtalloc.so libandroid-shmem.so; do
    needed="$(readelf -d "$OUT/$abi/$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
    echo "   $so needs: $needed"
    for lib in $needed; do
      case "$lib" in libc.so|libdl.so|libm.so|liblog.so|libtalloc.so|libandroid-shmem.so) ;;
        *) echo "error: $so needs $lib, which is not bundled" >&2; exit 1 ;;
      esac
    done
  done
done
echo "proot ready in $OUT"
