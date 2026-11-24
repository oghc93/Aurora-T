package com.aurora.tunnel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Beranda + Log dalam satu halaman: tab di atas, bisa digeser kiri/kanan. */
@Composable
fun HomeHost(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: (String) -> Unit,
    goProfiles: () -> Unit,
    onAdd: () -> Unit
) {
    val p = LocalAurora.current
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val names = listOf("Beranda", "Log")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            names.forEachIndexed { i, n ->
                val sel = pager.currentPage == i
                Column(
                    Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(end = 22.dp, top = 6.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(n, color = if (sel) p.text else p.sub, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                    Box(
                        Modifier.padding(top = 4.dp).width(if (sel) 26.dp else 0.dp).height(3.dp)
                            .background(p.accent, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) HomeScreen(onConnect, onDisconnect, onReconnect, goProfiles, goLog = { scope.launch { pager.animateScrollToPage(1) } }, onAdd = onAdd) else LogScreen()
        }
    }
}
