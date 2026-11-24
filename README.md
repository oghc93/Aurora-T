# Cocote Tunnel

Aplikasi VPN/tunnel Android native (Kotlin + Jetpack Compose).

## Mode
| Mode | Cara kerja |
|---|---|
| SSH | JSch + payload HTTP/WebSocket, SNI/TLS kustom, proxy HTTP, SOCKS5 lokal |
| SlowDNS | `dnstt-client` (DNS tunnel) lalu SSH di dalamnya |
| UDP Custom | SSH + badvpn-udpgw (UDP lewat channel SSH) |
| Xray | Xray-core: vmess / vless / trojan / shadowsocks, ws/grpc/tls/reality, fragment, mux, atau JSON kustom |
| OpenVPN | Belum aktif (tahap 2) |

Lapisan TUN: `hev-socks5-tunnel` (tun2socks native) dengan `mapdns`, jadi DNS diselesaikan di sisi tunnel.

## Tema
Aurora Night (gelap solid) dan Aurora Glass (kartu kaca transparan di atas aurora bergerak). Ganti di tab Pengaturan.

## Build lewat GitHub Actions
1. Push seluruh isi folder ini ke repo GitHub (branch `main`).
2. Tab **Actions** → workflow **Build APK** berjalan otomatis (atau jalankan manual).
3. Unduh artifact `AuroraTunnel-apk`.

Workflow memanggil `scripts/fetch-deps.sh` yang mengambil hev-socks5-tunnel, Xray-core (android arm64),
dan membangun dnstt-client. Hanya ABI `arm64-v8a`.

## Build lokal
Butuh JDK 17, Android SDK + NDK, Go, Gradle 8.10:
```
bash scripts/fetch-deps.sh
gradle assembleRelease
```
