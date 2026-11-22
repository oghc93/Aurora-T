#!/usr/bin/env bash
# Mengambil dependensi native: tun2socks (hev-socks5-tunnel), Xray-core, dnstt-client.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/app/src/main/jni"
LIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
mkdir -p "$LIBS"

echo "==> hev-socks5-tunnel"
if [ ! -f "$JNI/hev-socks5-tunnel/Android.mk" ]; then
  git clone --depth 1 --recursive https://github.com/heiher/hev-socks5-tunnel "$JNI/hev-socks5-tunnel"
fi

echo "==> Xray-core (android arm64)"
curl -fL -o "$TMP/xray.zip" \
  "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-android-arm64-v8a.zip"
unzip -o -q "$TMP/xray.zip" xray -d "$TMP/xray"
install -m 755 "$TMP/xray/xray" "$LIBS/libxray.so"

echo "==> dnstt-client (SlowDNS)"
git clone --depth 1 https://www.bamsoftware.com/git/dnstt.git "$TMP/dnstt"
( cd "$TMP/dnstt/dnstt-client" && \
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o "$LIBS/libdnstt.so" . )
chmod 755 "$LIBS/libdnstt.so"

echo "==> selesai"
ls -la "$LIBS"
