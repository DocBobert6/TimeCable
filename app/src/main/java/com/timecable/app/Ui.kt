package com.timecable.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * TimeCable's own look, inspired by mid-2000s (2004-2008) digital cable boxes:
 * glossy royal-blue bars with a top highlight, rounded bevelled cells, gold selection,
 * picture-in-picture preview, and white type with a hard drop shadow.
 * Touch first (phones); the TV remote still works as a secondary input.
 */
private object Pal {
    val BgTop = Color(0xFF0B2468)
    val BgBot = Color(0xFF020924)
    val BarHi = Color(0xFF5E8AE6)
    val BarMid = Color(0xFF2149A8)
    val BarLo = Color(0xFF0F2A73)
    val CellHi = Color(0xFF3563C9)
    val CellLo = Color(0xFF17337F)
    val Label = Color(0xFF0D215F)
    val Edge = Color(0xFF86A9FF)
    val Soft = Color(0xFFBCD0FF)
    val GoldHi = Color(0xFFFFEC8A)
    val GoldLo = Color(0xFFF0B400)
    val Ink = Color(0xFF071034)
    val Scrim = Color(0xE6061445)
    val Track = Color(0x55FFFFFF)
}

private val bg = Brush.verticalGradient(listOf(Pal.BgTop, Pal.BgBot))
private val bar = Brush.verticalGradient(0f to Pal.BarHi, 0.48f to Pal.BarMid, 0.52f to Pal.BarLo, 1f to Pal.BarLo)
private val cell = Brush.verticalGradient(listOf(Pal.CellHi, Pal.CellLo))
private val gold = Brush.verticalGradient(listOf(Pal.GoldHi, Pal.GoldLo))
private val label = Brush.verticalGradient(listOf(Pal.Label, Pal.Label))
private val R = RoundedCornerShape(6.dp)

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateFmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())
private fun t(ms: Long): String = clockFmt.format(Date(ms))

/** Text sizes are designed for phones and scaled up on tablets / TVs. */
@Composable
private fun scale(): Float = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) 1.45f else 1f

@Composable
private fun T(
    s: String,
    size: Int,
    color: Color = Color.White,
    lines: Int = 1,
    mod: Modifier = Modifier,
    bold: Boolean = true,
) = Text(
    s,
    mod,
    color = color,
    fontSize = (size * scale()).sp,
    fontFamily = FontFamily.SansSerif,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
    maxLines = lines,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(shadow = if (color == Pal.Ink) null else Shadow(Color.Black, Offset(2f, 2f), 3f)),
)

/** Glossy rounded button in the period style. */
@Composable
private fun Btn(text: String, mod: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    Box(
        mod.heightIn(min = 44.dp).clip(R).background(if (on) gold else bar).border(1.dp, Pal.Edge, R)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 14, if (on) Pal.Ink else Color.White) }
}

@Composable
fun CableUi(box: CableBox) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val guide = box.mode == Mode.GUIDE

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        if (guide) Box(Modifier.fillMaxSize().background(bg))

        // One PlayerView for both modes: full screen when watching, picture-in-picture in the guide.
        val videoMod = when {
            !guide -> Modifier.fillMaxSize()
            portrait -> Modifier.padding(top = HEADER).fillMaxWidth().aspectRatio(16f / 9f)
                .padding(10.dp).border(2.dp, Pal.Edge).background(Color.Black)
            else -> Modifier.align(Alignment.TopEnd).padding(top = HEADER).fillMaxWidth(0.45f)
                .aspectRatio(16f / 9f).padding(10.dp).border(2.dp, Pal.Edge).background(Color.Black)
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
            GuideScreen(box, now, portrait)
        } else {
            if (!box.videoReady) TuneOsd(box)
            WatchTouch(box, now)
        }

        if (box.digits.isNotEmpty()) {
            T(box.digits, 56, Pal.GoldHi, mod = Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        if (box.crtMenu) CrtMenu(box, portrait)
    }
}

private val HEADER: Dp = 52.dp

/** Tune / loading screen shown until the first video frame arrives. */
@Composable
private fun TuneOsd(box: CableBox) {
    val ch = box.channels.first { it.number == box.tuned }
    val loading = box.schedules[box.tuned].isNullOrEmpty() // still filling from the archive on first run
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            T("${ch.number}", 64, Pal.GoldHi)
            T(if (loading) "TUNING…" else ch.callsign, 22)
            if (loading) {
                Spacer(Modifier.height(10.dp))
                T("ACQUIRING SIGNAL", 14, Pal.Soft)
            }
        }
    }
}

/**
 * Full-screen watching: tap to show the info banner and control dock,
 * swipe up / down to change channel.
 */
@Composable
private fun BoxScope.WatchTouch(box: CableBox, now: Long) {
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { box.bannerAt = if (System.currentTimeMillis() - box.bannerAt < 4500) 0L else System.currentTimeMillis() }
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (acc < -120f) box.step(1) else if (acc > 120f) box.step(-1)
                        acc = 0f
                    },
                ) { _, d -> acc += d }
            },
    )
    if (now - box.bannerAt > 4500) return
    InfoBanner(box, now, Modifier.align(Alignment.TopCenter))
    Dock(box, Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun InfoBanner(box: CableBox, now: Long, mod: Modifier) {
    val ch = box.channels.first { it.number == box.tuned }
    val slot = Schedule.at(box.schedules[box.tuned].orEmpty(), now)
    Column(
        mod.fillMaxWidth().padding(10.dp).clip(R).background(Pal.Scrim).border(1.dp, Pal.Edge, R),
    ) {
        Row(
            Modifier.fillMaxWidth().background(bar).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("${ch.number}", 26, Pal.GoldHi)
            Spacer(Modifier.width(10.dp))
            T(ch.callsign, 18, mod = Modifier.weight(1f))
            if (box.crtOn) T("CRT ${box.crtStyle.label}", 11, Pal.GoldHi)
            Spacer(Modifier.width(10.dp))
            T(t(now), 16)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            T(slot?.item?.title ?: "NO PROGRAM DATA", 18, lines = 2)
            if (slot != null) {
                T("${t(slot.start)} - ${t(slot.end)}", 13, Pal.Soft, bold = false)
                val frac = ((now - slot.start).toFloat() / (slot.end - slot.start)).coerceIn(0f, 1f)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(R).background(Pal.Track)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(gold))
                }
            }
            Spacer(Modifier.height(4.dp))
            T("SWIPE ↑↓ TO CHANGE CHANNEL", 10, Pal.Soft, bold = false)
        }
    }
}

@Composable
private fun Dock(box: CableBox, mod: Modifier) {
    Row(
        mod.fillMaxWidth().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Btn("CH ▼", Modifier.weight(1f)) { box.step(-1) }
        Btn("CH ▲", Modifier.weight(1f)) { box.step(1) }
        Btn("LAST", Modifier.weight(1f)) { box.tuneLast() }
        Btn("GUIDE", Modifier.weight(1.2f), on = true) { box.openGuide() }
        Btn("CRT", Modifier.weight(1f), on = box.crtOn) { box.openCrtMenu() }
    }
}

@Composable
private fun Header(box: CableBox, now: Long) {
    Row(
        Modifier.fillMaxWidth().height(HEADER).background(bar).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(R).background(gold).padding(horizontal = 8.dp, vertical = 3.dp)) {
            T("TIMECABLE", 13, Pal.Ink)
        }
        Spacer(Modifier.width(10.dp))
        T("GUIDE", 16, mod = Modifier.weight(1f))
        T(dateFmt.format(Date(now)).uppercase(), 12, Pal.Soft)
        Spacer(Modifier.width(10.dp))
        T(t(now), 16)
        Spacer(Modifier.width(10.dp))
        Btn("✕") { box.mode = Mode.WATCH }
    }
}

@Composable
private fun GuideScreen(box: CableBox, now: Long, portrait: Boolean) {
    val half = box.guideHalfMinutes * 60_000L
    val ws = (now / half) * half + box.shift * half
    val ch = box.channels[box.sel]
    val cur = Schedule.at(box.schedules[ch.number].orEmpty(), maxOf(now, ws))

    Column(Modifier.fillMaxSize()) {
        Header(box, now)
        val info = @Composable { m: Modifier ->
            Column(m.padding(horizontal = 14.dp, vertical = 8.dp)) {
                T("${ch.number}  ${ch.callsign}", 22, Pal.GoldHi)
                T(cur?.item?.title ?: "NO DATA", 16, lines = 2)
                T(cur?.let { "${t(it.start)} - ${t(it.end)}" } ?: "", 13, Pal.Soft, bold = false)
                T("TAP A SHOW TO WATCH", 10, Pal.Soft, bold = false)
            }
        }
        if (portrait) {
            Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            info(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth()) {
                info(Modifier.weight(0.55f))
                Spacer(Modifier.weight(0.45f).aspectRatio(16f / 9f))
            }
        }
        Grid(box, now, ws, half, Modifier.weight(1f))
    }
}

@Composable
private fun Grid(box: CableBox, now: Long, ws: Long, half: Long, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val narrow = maxWidth < 560.dp
        val cols = if (narrow) 3 else box.guideColumns
        val we = ws + cols * half
        val lab = if (narrow) 88.dp else 140.dp
        val totalMinutes = cols * box.guideHalfMinutes
        val mpw = (maxWidth - lab) / totalMinutes.toFloat()
        val rowH = 52.dp
        Column {
            // Time bar: the label cell holds the ◀ ▶ time buttons.
            Row(Modifier.height(40.dp).clip(R).background(bar)) {
                Row(Modifier.width(lab).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { box.shift = (box.shift - 1).coerceAtLeast(0) },
                        contentAlignment = Alignment.Center,
                    ) { T("◀", 16, if (box.shift == 0) Pal.Soft else Color.White) }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { box.shift = (box.shift + 1).coerceAtMost(12) },
                        contentAlignment = Alignment.Center,
                    ) { T("▶", 16) }
                }
                for (i in 0 until cols) {
                    Box(
                        Modifier.width(mpw * box.guideHalfMinutes).fillMaxHeight().padding(start = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { T(t(ws + i * half), 13) }
                }
            }
            Spacer(Modifier.height(4.dp))
            val state = rememberLazyListState()
            LaunchedEffect(box.sel) { state.animateScrollToItem((box.sel - 2).coerceAtLeast(0)) }
            LazyColumn(state = state) {
                items(box.channels.size) { r ->
                    val c = box.channels[r]
                    val on = r == box.sel
                    Row(Modifier.height(rowH).padding(vertical = 2.dp)) {
                        Box(
                            Modifier.width(lab).fillMaxHeight().padding(end = 2.dp).clip(R)
                                .background(if (on) gold else label).border(1.dp, Pal.Edge, R)
                                .clickable { box.sel = r }.padding(start = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Column {
                                T("${c.number}", 14, if (on) Pal.Ink else Pal.GoldHi)
                                T(c.callsign, 11, if (on) Pal.Ink else Color.White)
                            }
                        }
                        val slots = Schedule.between(box.schedules[c.number].orEmpty(), ws, we)
                        if (slots.isEmpty()) {
                            Box(
                                Modifier.width(mpw * totalMinutes).fillMaxHeight().clip(R).background(label)
                                    .border(1.dp, Pal.Edge, R).padding(start = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { T("NO SIGNAL", 13, Pal.Soft) }
                        } else {
                            slots.forEach { s ->
                                val a = maxOf(s.start, ws)
                                val b = minOf(s.end, we)
                                if (b > a) {
                                    val live = now in s.start until s.end
                                    Box(
                                        Modifier.width(mpw * ((b - a) / 60000f)).fillMaxHeight().padding(end = 2.dp)
                                            .clip(R).background(if (on) gold else cell).border(1.dp, Pal.Edge, R)
                                            .clickable { box.watch(c.number) }
                                            .padding(horizontal = 6.dp).clipToBounds(),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        T(
                                            (if (s.start < ws) "◂ " else "") + s.item.title,
                                            12,
                                            if (on) Pal.Ink else if (live) Color.White else Pal.Soft,
                                            lines = 2,
                                        )
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

/** CRT panel: on/off, style, and every setting with − / + buttons. Bottom sheet on phones. */
@Composable
private fun BoxScope.CrtMenu(box: CableBox, portrait: Boolean) {
    val p = box.crtParams.toArray()
    val panel = if (portrait) {
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.7f)
    } else {
        Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight()
    }
    Column(panel.padding(8.dp).clip(R).background(bg).border(2.dp, Pal.Edge, R)) {
        Row(
            Modifier.fillMaxWidth().background(bar).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("CRT", 18, Pal.GoldHi, mod = Modifier.weight(1f))
            Btn(if (box.crtOn) "ON" else "OFF", on = box.crtOn) { box.toggleCrt() }
            Spacer(Modifier.width(8.dp))
            Btn("✕") { box.crtMenu = false }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
            T("STYLE", 12, Pal.Soft)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CrtStyle.entries.forEach { s ->
                    Btn(s.label, Modifier.weight(1f), on = box.crtStyle == s) {
                        if (box.crtStyle != s) box.crtAdjust(0, 1f)
                    }
                }
            }
            box.crtSpecs.forEachIndexed { i, spec ->
                val sel = box.crtParamIndex == i + 1
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(R)
                        .background(if (sel) gold else label).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(spec.label, 13, if (sel) Pal.Ink else Color.White, mod = Modifier.weight(1f))
                    Btn("−", Modifier.size(44.dp)) { box.crtAdjust(i + 1, -1f) }
                    T(spec.format.format(p[spec.index]), 13, if (sel) Pal.Ink else Pal.Soft, mod = Modifier.width(64.dp).padding(horizontal = 6.dp))
                    Btn("+", Modifier.size(44.dp)) { box.crtAdjust(i + 1, 1f) }
                }
            }
            if (!box.crtOn) {
                Spacer(Modifier.height(8.dp))
                T("Turn CRT ON to see changes.", 12, Pal.Soft, bold = false)
            }
        }
    }
}
