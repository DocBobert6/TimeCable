package com.timecable.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 2000s digital-cable palette: deep royal blue guide, gold highlight, white OSD type with a hard drop shadow.
private val NavyTop = Color(0xFF16248F)
private val NavyBot = Color(0xFF050B3A)
private val Cell = Color(0xFF2A3DB8)
private val LabelBg = Color(0xFF0E1A6B)
private val Edge = Color(0xFF8E9CFF)
private val Gold = Color(0xFFF4D21F)
private val Ink = Color(0xFF05082B)

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun t(ms: Long): String = clockFmt.format(Date(ms))

@Composable
private fun T(
    s: String,
    size: Int,
    color: Color = Color.White,
    lines: Int = 1,
    mod: Modifier = Modifier,
) = Text(
    s,
    mod,
    color = color,
    fontSize = size.sp,
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    maxLines = lines,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(shadow = Shadow(Color.Black, Offset(3f, 3f), 4f)),
)

@Composable
fun CableUi(box: CableBox) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val guide = box.mode == Mode.GUIDE

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (guide) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NavyTop, NavyBot))))

        // One PlayerView for both modes: full screen when watching, framed preview in the guide.
        val videoMod = if (guide) {
            Modifier.align(Alignment.TopEnd).fillMaxWidth(0.6f).fillMaxHeight(0.4f)
                .padding(14.dp).border(3.dp, Color.White).background(Color.Black)
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = box.player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = videoMod,
        )

        if (guide) {
            GuideScreen(box, now)
        } else {
            if (!box.videoReady) TuneOsd(box)
            Banner(box, now)
        }

        if (box.digits.isNotEmpty()) {
            T(box.digits, 80, Gold, mod = Modifier.align(Alignment.TopEnd).padding(48.dp))
        }

        if (box.crtMenu) {
            CrtMenu(box)
        }
    }
}

/** Classic 2000s-style tune / loading OSD. Matches existing palette and type treatment. */
@Composable
private fun TuneOsd(box: CableBox) {
    val ch = box.channels.first { it.number == box.tuned }
    val items = box.schedules[box.tuned]
    val none = items.isNullOrEmpty()
    val loading = none // still filling from archive on first run
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.align(Alignment.TopStart).padding(48.dp)) {
            T("${ch.number}", 72, Gold)
            T(
                when {
                    loading -> "TUNING…"
                    else -> ch.callsign
                },
                28,
            )
            if (loading) {
                Spacer(Modifier.height(12.dp))
                T("ACQUIRING SIGNAL", 20, Edge)
            }
        }
    }
}

@Composable
private fun BoxScope.Banner(box: CableBox, now: Long) {
    if (now - box.bannerAt > 4500) return
    val ch = box.channels.first { it.number == box.tuned }
    val slot = Schedule.at(box.schedules[box.tuned].orEmpty(), now)
    val crtLabel = when (box.crtMode) {
        CrtMode.OFF -> null
        CrtMode.LOTTES_FAST -> "CRT FAST"
        CrtMode.LOTTES -> "CRT LOTTES"
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC16248F), Color(0xF005082B))))
            .padding(horizontal = 48.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${ch.number}", 54, Gold)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                T(slot?.item?.title ?: "NO PROGRAM DATA", 26)
                T(
                    ch.callsign + (slot?.let { "   ${t(it.start)} - ${t(it.end)}" } ?: "") +
                        (crtLabel?.let { "   ·  $it" } ?: ""),
                    18,
                    Edge,
                )
            }
        }
        if (slot != null) {
            val frac = ((now - slot.start).toFloat() / (slot.end - slot.start)).coerceIn(0f, 1f)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Gold))
            }
        }
    }
}

/** Simple on-screen CRT parameter panel (same navy/gold language as the guide). */
@Composable
private fun BoxScope.CrtMenu(box: CableBox) {
    val labels = listOf(
        "HARD SCAN", "HARD PIX", "WARP X", "WARP Y",
        "MASK DARK", "MASK LIGHT", "SHADOW MASK", "BRIGHT BOOST",
        "BLOOM", "SHAPE",
    )
    val values = with(box.crtParams) {
        listOf(
            "%.1f".format(hardScan),
            "%.1f".format(hardPix),
            "%.3f".format(warpX),
            "%.3f".format(warpY),
            "%.2f".format(maskDark),
            "%.2f".format(maskLight),
            "%.0f".format(shadowMask),
            "%.2f".format(brightBoost),
            "%.2f".format(bloomAmount),
            "%.1f".format(shape),
        )
    }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .padding(32.dp)
            .width(340.dp)
            .background(Brush.verticalGradient(listOf(NavyTop, NavyBot)))
            .border(2.dp, Edge)
            .padding(20.dp),
    ) {
        T(
            when (box.crtMode) {
                CrtMode.LOTTES -> "CRT LOTTES"
                CrtMode.LOTTES_FAST -> "CRT LOTTES FAST"
                else -> "CRT"
            },
            26,
            Gold,
        )
        Spacer(Modifier.height(12.dp))
        labels.forEachIndexed { i, label ->
            val on = i == box.crtParamIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) Gold else Color.Transparent)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(label, 16, if (on) Ink else Color.White, mod = Modifier.weight(1f))
                T(values[i], 16, if (on) Ink else Edge)
            }
        }
        Spacer(Modifier.height(10.dp))
        T("← → adjust   ↑ ↓ select   OK close", 14, Edge)
    }
}

@Composable
private fun GuideScreen(box: CableBox, now: Long) {
    val half = box.guideHalfMinutes * 60_000L
    val ws = (now / half) * half + box.shift * half
    val ch = box.channels[box.sel]
    val cur = Schedule.at(box.schedules[ch.number].orEmpty(), maxOf(now, ws))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(0.4f).fillMaxWidth()) {
            Column(Modifier.weight(0.4f).fillMaxHeight().padding(start = 40.dp, top = 28.dp, end = 12.dp)) {
                T("${ch.number}  ${ch.callsign}", 38, Gold)
                Spacer(Modifier.height(8.dp))
                T(cur?.item?.title ?: "NO DATA", 24, lines = 3)
                Spacer(Modifier.height(6.dp))
                T(cur?.let { "${t(it.start)} - ${t(it.end)}" } ?: "", 20, Edge)
                Spacer(Modifier.weight(1f))
                T(t(now), 28, Color.White, mod = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.weight(0.6f))
        }
        Grid(box, now, ws, half, Modifier.weight(0.6f))
    }
}

@Composable
private fun Grid(box: CableBox, now: Long, ws: Long, half: Long, modifier: Modifier) {
    val cols = box.guideColumns
    val we = ws + cols * half
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        val label = 150.dp
        val totalMinutes = cols * box.guideHalfMinutes
        val mpw = (maxWidth - label) / totalMinutes.toFloat()
        Column {
            Row(Modifier.height(36.dp)) {
                Spacer(Modifier.width(label))
                for (i in 0 until cols) {
                    Box(
                        Modifier.width(mpw * box.guideHalfMinutes).fillMaxHeight().border(1.dp, Edge).padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { T(t(ws + i * half), 18, Color.White) }
                }
            }
            val state = rememberLazyListState()
            LaunchedEffect(box.sel) { state.animateScrollToItem((box.sel - 2).coerceAtLeast(0)) }
            LazyColumn(state = state) {
                items(box.channels.size) { r ->
                    val c = box.channels[r]
                    val on = r == box.sel
                    Row(Modifier.height(46.dp)) {
                        Box(
                            Modifier.width(label).fillMaxHeight()
                                .background(if (on) Gold else LabelBg).border(1.dp, Edge).padding(start = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { T("${c.number} ${c.callsign}", 20, if (on) Ink else Color.White) }

                        val slots = Schedule.between(box.schedules[c.number].orEmpty(), ws, we)
                        if (slots.isEmpty()) {
                            Box(
                                Modifier.width(mpw * totalMinutes).fillMaxHeight().background(LabelBg).border(1.dp, Edge).padding(start = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { T("NO SIGNAL", 18, Edge) }
                        } else {
                            slots.forEach { s ->
                                val a = maxOf(s.start, ws)
                                val b = minOf(s.end, we)
                                if (b > a) {
                                    Box(
                                        Modifier.width(mpw * ((b - a) / 60000f)).fillMaxHeight()
                                            .background(if (on) Gold else Cell).border(1.dp, Edge)
                                            .padding(horizontal = 8.dp).clipToBounds(),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        T((if (s.start < ws) "< " else "") + s.item.title, 18, if (on) Ink else Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
