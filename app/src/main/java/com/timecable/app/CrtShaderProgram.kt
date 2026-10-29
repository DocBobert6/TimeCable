package com.timecable.app

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Single-pass GLSL ES port of Timothy Lottes' public-domain CRT shader
 * (and a cheaper Fast variant). Wired as a Media3 GlShaderProgram.
 *
 * The picture is resampled to a virtual low-resolution "TV" (params.lines tall) so
 * scanlines stay visible on HD sources. Parameters are read from [CrtLive] every frame.
 */
@OptIn(UnstableApi::class)
class CrtShaderProgram(
    useHdr: Boolean,
    style: CrtStyle,
    private val live: CrtLive,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 2) {

    private val glProgram: GlProgram
    private var inputWidth = 0
    private var inputHeight = 0

    init {
        val fragment = when (style) {
            CrtStyle.LOTTES_FAST -> FRAGMENT_FAST
            CrtStyle.LOTTES -> FRAGMENT_FULL
        }
        try {
            glProgram = GlProgram(VERTEX, COMMON + fragment)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            glProgram.setFloatsUniform("uTransformationMatrix", identity)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = live.params
            val h = p.lines.coerceAtMost(inputHeight.toFloat())
            val w = kotlin.math.floor(h * inputWidth / inputHeight.coerceAtLeast(1))
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            glProgram.setFloatsUniform("uTexSize", floatArrayOf(w, h))
            glProgram.setFloatsUniform("uParams", p.toArray())
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: GlUtil.GlException) { }
    }

    companion object {
        private val VERTEX = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uTransformationMatrix * aFramePosition;
                vec4 texPos = uTexTransformationMatrix * vec4(aFramePosition.x, aFramePosition.y, 0.0, 1.0);
                vTexCoord = (texPos.xy * 0.5) + 0.5;
            }
        """.trimIndent()

        /**
         * Shared header. highp where available: mediump cannot address individual
         * pixels at HD sizes, which breaks the per-pixel math on many TV sticks.
         * uParams: hardScan, hardPix, warpX, warpY, maskDark, maskLight, shadowMask,
         *          brightBoost, bloomAmount, shape, lines
         */
        private val COMMON = """
            #version 100
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexSize;
            uniform float uParams[11];

            vec3 toLinear(vec3 c) { return c * c; }
            vec3 toSrgb(vec3 c) { return sqrt(max(c, 0.0)); }

            vec2 warp(vec2 pos) {
                pos = pos * 2.0 - 1.0;
                pos *= vec2(1.0 + (pos.y * pos.y) * uParams[2],
                            1.0 + (pos.x * pos.x) * uParams[3]);
                return pos * 0.5 + 0.5;
            }

            vec3 triad(float x, float dark, float light) {
                vec3 m = vec3(dark);
                if      (x < 0.333) m.r = light;
                else if (x < 0.666) m.g = light;
                else                m.b = light;
                return m;
            }

            // Lottes' four mask types: 1=TV shadow mask, 2=aperture grille, 3=stretched VGA, 4=VGA.
            vec3 mask(vec2 pos) {
                float sm = uParams[6];
                float dark = uParams[4];
                float light = uParams[5];
                if (sm < 0.5) return vec3(1.0);
                if (sm < 1.5) {
                    float line = light;
                    float odd = fract(pos.x / 6.0) < 0.5 ? 1.0 : 0.0;
                    if (fract((pos.y + odd) / 2.0) < 0.5) line = dark;
                    return triad(fract(pos.x / 3.0), dark, light) * line;
                }
                if (sm < 2.5) return triad(fract(pos.x / 3.0), dark, light);
                if (sm < 3.5) {
                    pos.x += pos.y * 3.0;
                    return triad(fract(pos.x / 6.0), dark, light);
                }
                pos = floor(pos * vec2(1.0, 0.5));
                pos.x += pos.y * 3.0;
                return triad(fract(pos.x / 6.0), dark, light);
            }

            bool outside(vec2 pos) {
                return pos.x < 0.0 || pos.x > 1.0 || pos.y < 0.0 || pos.y > 1.0;
            }
        """.trimIndent() + "\n"

        /** Full Lottes: Gaussian soft pixels, 3-line scanline weighting, mask, wide bloom. */
        private val FRAGMENT_FULL = """
            const float HARD_BLOOM_PIX = -1.5;
            const float HARD_BLOOM_SCAN = -2.0;

            vec3 fetch(vec2 pos, vec2 off) {
                vec2 p = (floor(pos * uTexSize + off) + 0.5) / uTexSize;
                return toLinear(uParams[7] * texture2D(uTexSampler, p).rgb);
            }

            vec2 dist(vec2 pos) {
                pos = pos * uTexSize;
                return -((pos - floor(pos)) - 0.5);
            }

            float gaus(float pos, float scale) {
                return exp2(scale * pow(abs(pos), uParams[9]));
            }

            vec3 horz3(vec2 pos, float off, float scale) {
                float dst = dist(pos).x;
                float wb = gaus(dst - 1.0, scale);
                float wc = gaus(dst, scale);
                float wd = gaus(dst + 1.0, scale);
                return (fetch(pos, vec2(-1.0, off)) * wb + fetch(pos, vec2(0.0, off)) * wc +
                        fetch(pos, vec2(1.0, off)) * wd) / (wb + wc + wd);
            }

            vec3 horz5(vec2 pos, float off, float scale) {
                float dst = dist(pos).x;
                float wa = gaus(dst - 2.0, scale);
                float wb = gaus(dst - 1.0, scale);
                float wc = gaus(dst, scale);
                float wd = gaus(dst + 1.0, scale);
                float we = gaus(dst + 2.0, scale);
                return (fetch(pos, vec2(-2.0, off)) * wa + fetch(pos, vec2(-1.0, off)) * wb +
                        fetch(pos, vec2(0.0, off)) * wc + fetch(pos, vec2(1.0, off)) * wd +
                        fetch(pos, vec2(2.0, off)) * we) / (wa + wb + wc + wd + we);
            }

            float scan(vec2 pos, float off, float scale) {
                return gaus(dist(pos).y + off, scale);
            }

            vec3 tri(vec2 pos) {
                float hp = uParams[1];
                float hs = uParams[0];
                return horz3(pos, -1.0, hp) * scan(pos, -1.0, hs) +
                       horz5(pos,  0.0, hp) * scan(pos,  0.0, hs) +
                       horz3(pos,  1.0, hp) * scan(pos,  1.0, hs);
            }

            // Wider, softer kernel than tri(): a real glow rather than a brightness copy.
            vec3 bloom(vec2 pos) {
                return horz3(pos, -2.0, HARD_BLOOM_PIX) * scan(pos, -2.0, HARD_BLOOM_SCAN) +
                       horz5(pos, -1.0, HARD_BLOOM_PIX) * scan(pos, -1.0, HARD_BLOOM_SCAN) +
                       horz5(pos,  0.0, HARD_BLOOM_PIX) * scan(pos,  0.0, HARD_BLOOM_SCAN) +
                       horz5(pos,  1.0, HARD_BLOOM_PIX) * scan(pos,  1.0, HARD_BLOOM_SCAN) +
                       horz3(pos,  2.0, HARD_BLOOM_PIX) * scan(pos,  2.0, HARD_BLOOM_SCAN);
            }

            void main() {
                vec2 pos = warp(vTexCoord);
                if (outside(pos)) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec3 col = tri(pos);
                if (uParams[8] > 0.0) col += bloom(pos) * uParams[8];
                col *= mask(gl_FragCoord.xy);
                gl_FragColor = vec4(toSrgb(col), 1.0);
            }
        """.trimIndent()

        /** Fast: one tap, scanlines + mask + warp. Cheap enough for low-end TV sticks. */
        private val FRAGMENT_FAST = """
            float scanWeight(float y) {
                float dy = fract(y * uTexSize.y) - 0.5;
                return exp2(uParams[0] * dy * dy);
            }

            void main() {
                vec2 pos = warp(vTexCoord);
                if (outside(pos)) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 p = (floor(pos * uTexSize) + 0.5) / uTexSize;
                p.x = pos.x; // keep horizontal detail; only snap to scanlines vertically
                vec3 col = toLinear(uParams[7] * texture2D(uTexSampler, p).rgb);
                col *= scanWeight(pos.y);
                col *= mask(gl_FragCoord.xy);
                gl_FragColor = vec4(toSrgb(col), 1.0);
            }
        """.trimIndent()
    }
}
