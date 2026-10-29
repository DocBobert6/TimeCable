package com.timecable.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** CRT mode. Off by default. */
enum class CrtMode {
    OFF,
    LOTTES,
    LOTTES_FAST,
}

/**
 * Adjustable parameters for CRT-Lottes (and Fast).
 * Defaults match Timothy Lottes' public-domain reference.
 */
data class CrtParams(
    val hardScan: Float = -8.0f,
    val hardPix: Float = -3.0f,
    val warpX: Float = 0.031f,
    val warpY: Float = 0.041f,
    val maskDark: Float = 0.5f,
    val maskLight: Float = 1.5f,
    val shadowMask: Float = 3.0f,   // 0=off, 1=TV, 2=aperture, 3=stretched VGA, 4=VGA
    val brightBoost: Float = 1.0f,
    val bloomAmount: Float = 0.15f,
    val shape: Float = 2.0f,
) {
    companion object {
        val Default = CrtParams()
    }
}

@OptIn(UnstableApi::class)
class CrtEffect(
    private val mode: CrtMode,
    private val params: CrtParams,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CrtShaderProgram(context, useHdr, mode, params)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = mode == CrtMode.OFF
}
