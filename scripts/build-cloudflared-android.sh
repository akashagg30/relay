#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Akash Agarwal
#
# Build the bundled cloudflared for Android, or check that a given binary is
# such a build.
#
# WHY THIS EXISTS
# ---------------
# Relay ships cloudflared as app/src/main/jniLibs/arm64-v8a/libcloudflared.so.
# Release 1.0.111 bundled a `GOOS=linux CGO_ENABLED=0` build. Go's pure-Go
# resolver reads /etc/resolv.conf, which DOES NOT EXIST on Android; the
# fallback is localhost:53, nothing listens there, and cloudflared exits 1 with
#
#   lookup api.trycloudflare.com on [::1]:53: read: connection refused
#
# On GOOS=android the net package routes lookups through cgo getaddrinfo ->
# bionic -> netd (net/conf.go: goosPrefersCgo() returns true for "android" and
# lookupOrder returns before getSystemDNSConfig()). CGO_ENABLED=1 is therefore
# load-bearing, not cosmetic. This script is the reproducibility guard: it
# refuses to install a binary whose embedded Go build info is not
# GOOS=android + CGO_ENABLED=1.
#
# USAGE
#   scripts/build-cloudflared-android.sh                 # clone + build + install
#   scripts/build-cloudflared-android.sh --check <elf>   # assert only, no build
#
# ENV
#   CLOUDFLARED_REF     git tag to build            (default 2026.8.3)
#   CLOUDFLARED_SRC     reuse an existing clean clone at that tag
#   ANDROID_NDK_HOME    NDK root                    (default ~/android-sdk/ndk/27.3.13750724)
#   ANDROID_ABI         arm64-v8a only              (default arm64-v8a)
#   MIN_SDK             API level for the clang tripple (default 26)
#   GO_BIN              go executable              (default `go`, else ~/.local/go/bin/go)
#   BUILD_DIR           scratch clone dir           (default ./.build)
#
# Exit codes: 0 ok, 1 assertion failure, 2 bad usage / missing toolchain.
#
# REPRODUCIBILITY NOTE
#   The recipe deliberately has no -trimpath (it must match the shipped
#   2026.8.3 artifact), so absolute source paths are embedded in the binary.
#   Two builds from the SAME clone path are byte-identical; builds from
#   different paths differ in bytes while every `go version -m` build setting
#   stays identical. Validate a rebuild by build settings (and preferably by
#   re-running from the same BUILD_DIR), not by sha256 across machines.

set -euo pipefail

CLOUDFLARED_REPO="${CLOUDFLARED_REPO:-https://github.com/cloudflare/cloudflared.git}"
CLOUDFLARED_REF="${CLOUDFLARED_REF:-2026.8.3}"
CLOUDFLARED_SRC="${CLOUDFLARED_SRC:-}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
MIN_SDK="${MIN_SDK:-26}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/27.3.13750724}"
BUILD_DIR="${BUILD_DIR:-$PWD/.build}"

# vcs.revision reported by the currently bundled 2026.8.3 binary.
EXPECTED_REVISION="${EXPECTED_REVISION:-fe70e951a3c52d92abf9f6c4248e32937b2f42fc}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_PATH="$REPO_ROOT/app/src/main/jniLibs/$ANDROID_ABI/libcloudflared.so"

if [ -n "${GO_BIN:-}" ]; then
    GO="$GO_BIN"
elif command -v go >/dev/null 2>&1; then
    GO="$(command -v go)"
elif [ -x "$HOME/.local/go/bin/go" ]; then
    GO="$HOME/.local/go/bin/go"
else
    echo "error: no go toolchain (set GO_BIN or install Go >= 1.27)" >&2
    exit 2
fi

case "$ANDROID_ABI" in
    arm64-v8a) CLANG_TRIPLE="aarch64-linux-android${MIN_SDK}" ;;
    *)
        echo "error: unsupported ANDROID_ABI '$ANDROID_ABI' (this repo ships arm64-v8a only)" >&2
        exit 2
        ;;
esac

fail() { echo "FAIL: $*" >&2; return 1; }

# true when $1 embeds GOOS=android + CGO_ENABLED=1 and neither GOOS=linux nor
# CGO_ENABLED=0. Prints the evidence either way.
check_binary() {
    local elf="$1" info

    [ -f "$elf" ] || { fail "$elf does not exist"; return 1; }

    if ! info="$("$GO" version -m "$elf" 2>/dev/null)"; then
        fail "$elf is not a Go binary (go version -m failed)"
        return 1
    fi

    echo "--- go version -m $(basename "$elf")" >&2
    printf '%s\n' "$info" | grep -E '^\s*build\s+(GOOS|GOARCH|CGO_ENABLED|vcs\.revision|-ldflags)=' >&2 || true

    local rc=0
    if ! printf '%s\n' "$info" | grep -q 'GOOS=android'; then
        echo "FAIL: missing GOOS=android" >&2
        rc=1
    fi
    if ! printf '%s\n' "$info" | grep -q 'CGO_ENABLED=1'; then
        echo "FAIL: missing CGO_ENABLED=1" >&2
        rc=1
    fi
    if printf '%s\n' "$info" | grep -q 'GOOS=linux'; then
        echo "FAIL: GOOS=linux present" >&2
        rc=1
    fi
    if printf '%s\n' "$info" | grep -q 'CGO_ENABLED=0'; then
        echo "FAIL: CGO_ENABLED=0 present" >&2
        rc=1
    fi
    return "$rc"
}

if [ "${1:-}" = "--check" ]; then
    target="${2:-}"
    [ -n "$target" ] || { echo "usage: $0 --check <path-to-elf>" >&2; exit 2; }
    if check_binary "$target"; then
        echo "OK: $target is a GOOS=android CGO_ENABLED=1 build"
        exit 0
    fi
    echo "NOT OK: $target is not a GOOS=android CGO_ENABLED=1 build" >&2
    exit 1
fi

case "${1:-}" in
    ""|--build) ;;
    *) echo "usage: $0 [--build | --check <elf>]" >&2; exit 2 ;;
esac

CLANG="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG_TRIPLE}-clang"
[ -x "$CLANG" ] || { echo "error: NDK clang not found at $CLANG (set ANDROID_NDK_HOME)" >&2; exit 2; }

WORKTREE="$BUILD_DIR/cloudflared-$CLOUDFLARED_REF"
if [ -n "$CLOUDFLARED_SRC" ]; then
    WORKTREE="$CLOUDFLARED_SRC"
    echo "== reusing clone at $WORKTREE"
elif [ ! -d "$WORKTREE/.git" ]; then
    echo "== cloning cloudflared $CLOUDFLARED_REF"
    mkdir -p "$BUILD_DIR"
    git clone --depth 1 --branch "$CLOUDFLARED_REF" "$CLOUDFLARED_REPO" "$WORKTREE"
else
    echo "== reusing existing clone at $WORKTREE"
fi

REVISION="$(git -C "$WORKTREE" rev-parse HEAD)"
[ "$REVISION" = "$EXPECTED_REVISION" ] || {
    echo "error: $CLOUDFLARED_REF is at $REVISION, expected $EXPECTED_REVISION" >&2
    exit 1
}
if [ -n "$(git -C "$WORKTREE" status --porcelain)" ]; then
    echo "error: $WORKTREE is dirty; the binary must report vcs.modified=false" >&2
    exit 1
fi
OUT="$BUILD_DIR/libcloudflared-$ANDROID_ABI.so"
echo "== building GOOS=android GOARCH=arm64 CGO_ENABLED=1 ($CLOUDFLARED_REF @ ${REVISION:0:12})"
(
    cd "$WORKTREE"
    GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$CLANG" \
        "$GO" build -ldflags "-X main.Version=$CLOUDFLARED_REF" -o "$OUT" ./cmd/cloudflared
)

check_binary "$OUT" || { echo "refusing to install $OUT" >&2; exit 1; }

install -D -m 0755 "$OUT" "$INSTALL_PATH"
echo "== installed $INSTALL_PATH"
ls -l "$INSTALL_PATH"
sha256sum "$INSTALL_PATH"
file "$INSTALL_PATH"
