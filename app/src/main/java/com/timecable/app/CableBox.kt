package com.timecable.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class Mode { WATCH, GUIDE }

/** The "set-top box": tuning, remote-control handling, and ExoPlayer playback of the live schedule. */
@OptIn(UnstableApi::class)
class CableBox(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir: File = ctx.filesDir
    private val badFile = File(dir, "bad.txt")
    private val bad: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also { s ->
        if (badFile.exists()) s.addAll(badFile.readLines())
    }
    private val beep: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_SYSTEM, 50)
    } catch (e: Exception) {
        null
    }

    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    val channels = Lineup.channels
    val schedules = mutableStateMapOf<Int, List<Item>>()
    var mode by mutableStateOf(Mode.WATCH)
    var tuned by mutableIntStateOf(channels.first().number)
    var videoReady by mutableStateOf(false)
    var bannerAt by mutableLongStateOf(0L)
    var digits by mutableStateOf("")
    var sel by mutableIntStateOf(0)
    var shift by mutableIntStateOf(0)

    // CRT (off by default)
    var crtMode by mutableStateOf(CrtMode.OFF)
    var crtParams by mutableStateOf(CrtParams.Default)
    var crtMenu by mutableStateOf(false)          // simple on-screen parameter panel
    var crtParamIndex by mutableIntStateOf(0)     // which param is highlighted in the menu

    // Guide geometry (logical, matches existing 2000s grid density)
    var guideHalfMinutes by mutableIntStateOf(30) // minutes per column
    var guideColumns by mutableIntStateOf(6)      // total columns shown

    private var last = tuned
    private var playing: Item? = null
    private var digitJob: Job? = null

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
        playWhenReady = true
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { videoReady = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) this@CableBox.play()
            }
            override fun onPlayerError(error: PlaybackException) {
                val permanent = error.errorCode in listOf(
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                )
                if (permanent) {
                    this@CableBox.ban(playing)
                    this@CableBox.play()
                } else {
                    scope.launch { delay(5_000); this@CableBox.play() }
                }
            }
        })
    }

    fun start() {
        requestAudioFocus()
        applyCrt()
        for (ch in channels) {
            scope.launch {
                repeat(6) {
                    val got = Archive.load(dir, ch, bad) { l -> scope.launch { setItems(ch.number, l) } }
                    if (got.isNotEmpty()) return@launch
                    delay(15_000)
                }
            }
        }
    }

    private fun setItems(n: Int, l: List<Item>) {
        schedules[n] = l
        if (n == tuned && playing == null) play()
    }

    fun play() {
        val now = System.currentTimeMillis()
        val slot = Schedule.at(schedules[tuned].orEmpty(), now)
        if (slot == null) {
            playing = null
            player.stop()
            return
        }
        playing = slot.item
        val off = (now - slot.start).coerceIn(0L, slot.item.durMs - 3_000L)
        player.setMediaItem(MediaItem.fromUri(slot.item.url), off)
        player.prepare()
    }

    private fun ban(item: Item?) {
        if (item == null) return
        bad.add(item.id)
        try { badFile.writeText(bad.joinToString("\n")) } catch (e: Exception) { }
        val ch = channels.first { it.number == tuned }
        val l = schedules[tuned].orEmpty().filter { it.id != item.id }
        schedules[tuned] = l
        Archive.writeCache(dir, ch.callsign, l)
    }

    fun tune(n: Int) {
        if (channels.none { it.number == n }) return
        bannerAt = System.currentTimeMillis()
        if (n == tuned && playing != null) return
        if (n != tuned) last = tuned
        tuned = n
        videoReady = false
        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        play()
    }

    fun pause() {
        player.pause()
        abandonAudioFocus()
    }

    fun resume() {
        requestAudioFocus()
        player.playWhenReady = true
        play()
    }

    fun release() {
        scope.cancel()
        abandonAudioFocus()
        player.release()
        beep?.release()
    }

    // ---- CRT ----

    fun cycleCrtMode() {
        crtMode = when (crtMode) {
            CrtMode.OFF -> CrtMode.LOTTES_FAST
            CrtMode.LOTTES_FAST -> CrtMode.LOTTES
            CrtMode.LOTTES -> CrtMode.OFF
        }
        applyCrt()
        bannerAt = System.currentTimeMillis()
        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
    }

    fun applyCrt() {
        val effects = if (crtMode == CrtMode.OFF) {
            emptyList()
        } else {
            listOf(CrtEffect(crtMode, crtParams))
        }
        player.setVideoEffects(effects)
    }

    fun adjustCrtParam(delta: Float) {
        val p = crtParams
        crtParams = when (crtParamIndex) {
            0 -> p.copy(hardScan = (p.hardScan + delta).coerceIn(-20f, 0f))
            1 -> p.copy(hardPix = (p.hardPix + delta).coerceIn(-20f, 0f))
            2 -> p.copy(warpX = (p.warpX + delta * 0.005f).coerceIn(0f, 0.125f))
            3 -> p.copy(warpY = (p.warpY + delta * 0.005f).coerceIn(0f, 0.125f))
            4 -> p.copy(maskDark = (p.maskDark + delta * 0.05f).coerceIn(0f, 2f))
            5 -> p.copy(maskLight = (p.maskLight + delta * 0.05f).coerceIn(0f, 2f))
            6 -> p.copy(shadowMask = (p.shadowMask + delta).coerceIn(0f, 4f))
            7 -> p.copy(brightBoost = (p.brightBoost + delta * 0.05f).coerceIn(0.5f, 2f))
            8 -> p.copy(bloomAmount = (p.bloomAmount + delta * 0.05f).coerceIn(0f, 1f))
            9 -> p.copy(shape = (p.shape + delta * 0.1f).coerceIn(0.5f, 10f))
            else -> p
        }
        applyCrt()
    }

    // ---- Audio focus (safe; does not break existing behaviour) ----

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    // ---- remote control ----

    fun onKey(code: Int): Boolean {
        if (crtMenu) return crtMenuKey(code)
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            addDigit(code - KeyEvent.KEYCODE_0)
            return true
        }
        // Long-press style: KEYCODE_PROG_RED or KEYCODE_TV_INPUT_HDMI_1 as CRT cycle on many remotes;
        // also support KEYCODE_BUTTON_L1 / KEYCODE_F1 as a reliable fallback.
        if (code == KeyEvent.KEYCODE_PROG_RED ||
            code == KeyEvent.KEYCODE_BUTTON_L1 ||
            code == KeyEvent.KEYCODE_F1
        ) {
            cycleCrtMode()
            return true
        }
        if (code == KeyEvent.KEYCODE_PROG_GREEN ||
            code == KeyEvent.KEYCODE_BUTTON_R1 ||
            code == KeyEvent.KEYCODE_F2
        ) {
            if (crtMode != CrtMode.OFF) {
                crtMenu = true
                crtParamIndex = 0
            }
            return true
        }
        return if (mode == Mode.WATCH) watchKey(code) else guideKey(code)
    }

    private fun crtMenuKey(c: Int): Boolean = when (c) {
        KeyEvent.KEYCODE_DPAD_UP -> {
            crtParamIndex = (crtParamIndex - 1).mod(10)
            true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            crtParamIndex = (crtParamIndex + 1).mod(10)
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            adjustCrtParam(-1f)
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            adjustCrtParam(1f)
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_PROG_GREEN,
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_F2 -> {
            crtMenu = false
            true
        }
        else -> false
    }

    private fun idx() = channels.indexOfFirst { it.number == tuned }
    private fun step(d: Int) = tune(channels[Math.floorMod(idx() + d, channels.size)].number)

    private fun openGuide() {
        sel = idx()
        shift = 0
        mode = Mode.GUIDE
    }

    private fun watchKey(c: Int): Boolean = when (c) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(1); true }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { tune(last); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (digits.isNotEmpty()) commitDigits() else openGuide()
            true
        }
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> { openGuide(); true }
        KeyEvent.KEYCODE_INFO -> { bannerAt = System.currentTimeMillis(); true }
        else -> false
    }

    private fun guideKey(c: Int): Boolean = when (c) {
        KeyEvent.KEYCODE_DPAD_UP -> { sel = Math.floorMod(sel - 1, channels.size); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { sel = Math.floorMod(sel + 1, channels.size); true }
        KeyEvent.KEYCODE_CHANNEL_UP -> { sel = Math.floorMod(sel - 5, channels.size); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> { sel = Math.floorMod(sel + 5, channels.size); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { shift = (shift - 1).coerceAtLeast(0); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { shift = (shift + 1).coerceAtMost(12); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (digits.isNotEmpty()) {
                commitDigits()
            } else {
                tune(channels[sel].number)
                mode = Mode.WATCH
            }
            true
        }
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> { mode = Mode.WATCH; true }
        else -> false
    }

    private fun addDigit(d: Int) {
        if (digits.length >= 3) digits = ""
        digits = digits + d
        digitJob?.cancel()
        if (digits.length == 3) {
            commitDigits()
        } else {
            digitJob = scope.launch { delay(1_800); commitDigits() }
        }
    }

    private fun commitDigits() {
        digitJob?.cancel()
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null && channels.any { it.number == n }) {
            tune(n)
            mode = Mode.WATCH
        } else {
            beep?.startTone(ToneGenerator.TONE_PROP_NACK, 80)
        }
    }
}
