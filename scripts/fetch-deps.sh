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
git clone https://www.bamsoftware.com/git/dnstt.git "$TMP/dnstt"
( cd "$TMP/dnstt/dnstt-client" && \
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o "$LIBS/libdnstt.so" . )
chmod 755 "$LIBS/libdnstt.so"

echo "==> OpenVPN (libovpnexec.so dari ics-openvpn, F-Droid) - GPLv2"
(
  set +e
  VC="$(curl -fsSL https://f-droid.org/api/v1/packages/de.blinkt.openvpn | grep -o '"suggestedVersionCode":[0-9]*' | grep -o '[0-9]*$')"
  if [ -z "$VC" ]; then
    VC="$(curl -fsSL https://f-droid.org/packages/de.blinkt.openvpn/ | grep -o 'de.blinkt.openvpn_[0-9]*\.apk' | grep -o '[0-9]*' | sort -n | tail -1)"
  fi
  echo "versionCode ics-openvpn: $VC"
  [ -n "$VC" ] || exit 1
  curl -fL -o "$TMP/ovpn.apk" "https://f-droid.org/repo/de.blinkt.openvpn_${VC}.apk" || exit 1
  mkdir -p "$TMP/ovpn"
  unzip -o -q "$TMP/ovpn.apk" 'lib/arm64-v8a/libovpnexec.so' -d "$TMP/ovpn" || exit 1
  install -m 755 "$TMP/ovpn/lib/arm64-v8a/libovpnexec.so" "$LIBS/libovpnexec.so"
) || echo "PERINGATAN: binary OpenVPN gagal diambil, mode OpenVPN tidak akan jalan (mode lain tetap normal)."

echo "==> selesai"
ls -la "$LIBS"
