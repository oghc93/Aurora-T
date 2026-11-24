package com.aurora.tunnel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Settings

private val GUIDE_ID = listOf(
        "1. Mulai cepat" to "Buka tab Profil, ketuk “+ Tambah”, isi data server, lalu Simpan. Kembali ke Beranda, pilih profil di kolom Profil, tekan KONEK. Setelah status TERHUBUNG, internet lewat tunnel.",
        "2. Beranda dan Log" to "Di bagian atas ada tab Beranda dan Log. Geser layar ke kiri atau kanan untuk berpindah. Setelah konek, Log hanya menampilkan status ping dan error supaya tidak ramai. Tombol Tes ping mengukur ke server profil terpilih.",
        "3. Menambah profil" to "SSH: isi host, port, username, password; Payload, Proxy, dan SNI opsional. SlowDNS: NS domain, public key, dan akun SSH. UDP Custom: SSH dengan UDPGW. Xray: tempel link vmess/vless/trojan/ss lewat tombol Tempel atau Link. OpenVPN: pilih file .ovpn atau tempel isinya, isi akun bila perlu.",
        "4. OpenVPN" to "Mendukung TLS dan non-TLS. Payload, Proxy, dan SNI hanya berlaku untuk OpenVPN TCP; untuk UDP akan diabaikan. OpenVPN selalu memakai mode VPN dan tidak ikut Load Balance.",
        "5. Mengelola profil" to "Ketuk kartu untuk memilih. Ikon pensil mengedit, ikon tempat sampah menghapus. Titik tiga berisi Bagikan, Tandai LB, Tes ping, dan Duplikat. “Ping semua” menguji semua profil sekaligus.",
        "6. Load Balance" to "Aktifkan di Pengaturan, lalu tandai minimal 2 profil dengan LB (profil terpilih ikut otomatis). Koneksi TCP dibagi bergiliran ke semua node, UDP hanya DNS.",
        "7. Pengaturan penting" to "Mode: VPN untuk seluruh perangkat atau Proxy SOCKS5 lokal. DNS: isi DNS utama dan cadangan. Tunnel Engine: HevSocks5Tunnel (otomatis) atau BadVPN UDPGW (port manual). Sambung ulang otomatis dan Auto Ping menjaga koneksi. Hapus log otomatis, Per-app proxy, Tema, dan Bahasa juga ada di sini.",
        "8. Jika bermasalah" to "Buka tab Log dan baca baris error terakhir. Coba turunkan MTU di Pengaturan bila koneksi sering macet, ganti Tunnel Engine, atau tes ping server. Pastikan izin VPN sudah diberikan saat pertama konek."
)

private val GUIDE_EN = listOf(
        "1. Quick start" to "Open the Profile tab, tap “+ Add”, fill in the server details, then Save. Go back to Home, choose the profile in the Profile field and press CONNECT. Once the status is CONNECTED, your internet goes through the tunnel.",
        "2. Home and Log" to "At the top there are Home and Log tabs. Swipe left or right to switch. After connecting, the Log only shows ping status and errors to keep it quiet. The Test ping button measures the selected profile's server.",
        "3. Adding profiles" to "SSH: host, port, username, password; Payload, Proxy and SNI are optional. SlowDNS: NS domain, public key and an SSH account. UDP Custom: SSH with UDPGW. Xray: paste a vmess/vless/trojan/ss link with the Paste or Link button. OpenVPN: choose an .ovpn file or paste its contents, and fill in the account if needed.",
        "4. OpenVPN" to "Supports TLS and non-TLS. Payload, Proxy and SNI only apply to OpenVPN TCP; they are ignored for UDP. OpenVPN always uses VPN mode and does not join Load Balance.",
        "5. Managing profiles" to "Tap a card to select it. The pencil edits, the trash can deletes. The three-dot menu has Share, Mark as LB, Test ping and Duplicate. “Ping all” tests every profile at once.",
        "6. Load Balance" to "Turn it on in Settings, then mark at least 2 profiles with LB (the selected profile joins automatically). TCP connections are shared round-robin across all nodes, UDP is DNS only.",
        "7. Key settings" to "Mode: VPN for the whole device or a local SOCKS5 proxy. DNS: set a primary and secondary DNS. Tunnel Engine: HevSocks5Tunnel (automatic) or BadVPN UDPGW (manual port). Auto reconnect and Auto Ping keep the connection alive. Auto-clear log, Per-app proxy, Theme and Language are here too.",
        "8. Troubleshooting" to "Open the Log tab and read the last error line. Lower the MTU in Settings if the connection often stalls, switch the Tunnel Engine, or test the server ping. Make sure VPN permission was granted on the first connect."
)

/** Tab Panduan: cara memakai aplikasi (mengikuti bahasa terpilih). */
@Composable
fun GuideScreen() {
    val p = LocalAurora.current
    val items = if (Settings.lang.v == "en") GUIDE_EN else GUIDE_ID
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (Settings.lang.v == "en") "Guide" else "Panduan",
            if (Settings.lang.v == "en") "How to use Cocote Tunnel" else "Cara memakai Cocote Tunnel")
        items.forEach { (title, body) ->
            AuroraCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                androidx.compose.material3.Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Text(body, color = p.sub, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Box(Modifier.height(24.dp))
    }
}
