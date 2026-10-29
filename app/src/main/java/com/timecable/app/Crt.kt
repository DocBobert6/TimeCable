package com.timecable.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Which CRT look to use when the CRT effect is switched on. */
enum class CrtStyle(val label: String) {
    LOTTES_FAST("LOTTES FAST"),
    LOTTES("LOTTES"),
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
    fun toArray() = floatArrayOf(
        hardScan, hardPix, warpX, warpY, maskDark, maskLight, shadowMask, brightBoost, bloomAmount, shape,
    )

    companion object {
        val Default = CrtParams()
        fun fromArray(a: FloatArray) = CrtParams(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9])
    }
}

/** One adjustable row in the CRT panel. [index] is the slot in [CrtParams.toArray]. */
data class CrtParamSpec(
    val index: Int,
    val label: String,
    val step: Float,
    val min: Float,
    val max: Float,
    val format: String,
)

object CrtSpecs {
    val all = listOf(
        CrtParamSpec(0, "HARD SCAN", 1f, -20f, 0f, "%.1f"),
        CrtParamSpec(1, "HARD PIX", 1f, -20f, 0f, "%.1f"),
        CrtParamSpec(2, "WARP X", 0.005f, 0f, 0.125f, "%.3f"),
        CrtParamSpec(3, "WARP Y", 0.005f, 0f, 0.125f, "%.3f"),
        CrtParamSpec(4, "MASK DARK", 0.05f, 0f, 2f, "%.2f"),
        CrtParamSpec(5, "MASK LIGHT", 0.05f, 0f, 2f, "%.2f"),
        CrtParamSpec(6, "SHADOW MASK", 1f, 0f, 4f, "%.0f"),
        CrtParamSpec(7, "BRIGHT BOOST", 0.05f, 0.5f, 2f, "%.2f"),
        CrtParamSpec(8, "BLOOM", 0.05f, 0f, 1f, "%.2f"),
        CrtParamSpec(9, "SHAPE", 0.1f, 0.5f, 10f, "%.1f"),
    )

    fun adjust(p: CrtParams, spec: CrtParamSpec, dir: Float): CrtParams {
        val a = p.toArray()
        a[spec.index] = (a[spec.index] + dir * spec.step).coerceIn(spec.min, spec.max)
        return CrtParams.fromArray(a)
    }
}

@OptIn(UnstableApi::class)
class CrtEffect(
    private val style: CrtStyle,
    private val params: CrtParams,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CrtShaderProgram(context, useHdr, style, params)
    }
}
