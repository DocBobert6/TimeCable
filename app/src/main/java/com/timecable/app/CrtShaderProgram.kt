package com.timecable.app

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Single-pass GLSL ES port of Timothy Lottes' public-domain CRT shader
 * (and a cheaper Fast variant). Wired as a Media3 GlShaderProgram.
 *
 * Note: Media3 video effects use OpenGL ES, not Vulkan. The algorithms are
 * the same Lottes math; only the shading language / pipeline differs.
 */
@OptIn(UnstableApi::class)
class CrtShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val mode: CrtMode,
    private val params: CrtParams,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 2) {

    private val glProgram: GlProgram
    private var inputWidth = 0
    private var inputHeight = 0

    init {
        val fragment = when (mode) {
            CrtMode.LOTTES_FAST -> FRAGMENT_FAST
            else -> FRAGMENT_FULL
        }
        try {
            glProgram = GlProgram(VERTEX, fragment)
            // Full-screen quad in NDC
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
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            glProgram.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            glProgram.setFloatsUniform(
                "uParams",
                floatArrayOf(
                    params.hardScan,
                    params.hardPix,
                    params.warpX,
                    params.warpY,
                    params.maskDark,
                    params.maskLight,
                    params.shadowMask,
                    params.brightBoost,
                    params.bloomAmount,
                    params.shape,
                ),
            )
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
        // Matches Media3 internal transformation vertex shader shape.
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
         * Full Lottes-style single pass:
         * - mild barrel warp
         * - horizontal Gaussian soft pixels
         * - scanline weighting
         * - aperture / shadow mask
         * - small bloom
         */
        private val FRAGMENT_FULL = """
            #version 100
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexSize;
            // hardScan, hardPix, warpX, warpY, maskDark, maskLight, shadowMask, brightBoost, bloomAmount, shape
            uniform float uParams[10];

            vec3 toLinear(vec3 c) {
                return c * c;
            }
            vec3 toSrgb(vec3 c) {
                return sqrt(max(c, 0.0));
            }

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

            vec3 horz3(vec2 pos, float off) {
                vec3 b = fetch(pos, vec2(-1.0, off));
                vec3 c = fetch(pos, vec2( 0.0, off));
                vec3 d = fetch(pos, vec2( 1.0, off));
                float dst = dist(pos).x;
                float scale = uParams[1];
                float wb = gaus(dst - 1.0, scale);
                float wc = gaus(dst + 0.0, scale);
                float wd = gaus(dst + 1.0, scale);
                return (b*wb + c*wc + d*wd) / (wb + wc + wd);
            }

            vec3 horz5(vec2 pos, float off) {
                vec3 a = fetch(pos, vec2(-2.0, off));
                vec3 b = fetch(pos, vec2(-1.0, off));
                vec3 c = fetch(pos, vec2( 0.0, off));
                vec3 d = fetch(pos, vec2( 1.0, off));
                vec3 e = fetch(pos, vec2( 2.0, off));
                float dst = dist(pos).x;
                float scale = uParams[1];
                float wa = gaus(dst - 2.0, scale);
                float wb = gaus(dst - 1.0, scale);
                float wc = gaus(dst + 0.0, scale);
                float wd = gaus(dst + 1.0, scale);
                float we = gaus(dst + 2.0, scale);
                return (a*wa + b*wb + c*wc + d*wd + e*we) / (wa + wb + wc + wd + we);
            }

            float scan(vec2 pos, float off) {
                return gaus(dist(pos).y + off, uParams[0]);
            }

            vec3 tri(vec2 pos) {
                vec3 a = horz3(pos, -1.0);
                vec3 b = horz5(pos,  0.0);
                vec3 c = horz3(pos,  1.0);
                float wa = scan(pos, -1.0);
                float wb = scan(pos,  0.0);
                float wc = scan(pos,  1.0);
                return a*wa + b*wb + c*wc;
            }

            vec3 bloom(vec2 pos) {
                vec3 a = horz3(pos, -1.0);
                vec3 b = horz5(pos,  0.0);
                vec3 c = horz3(pos,  1.0);
                float wa = scan(pos, -1.0);
                float wb = scan(pos,  0.0);
                float wc = scan(pos,  1.0);
                return (a*wa + b*wb + c*wc) * 0.5;
            }

            vec2 warp(vec2 pos) {
                pos = pos * 2.0 - 1.0;
                pos *= vec2(1.0 + (pos.y * pos.y) * uParams[2],
                            1.0 + (pos.x * pos.x) * uParams[3]);
                return pos * 0.5 + 0.5;
            }

            vec3 mask(vec2 pos) {
                vec3 m = vec3(uParams[4]);
                float sm = uParams[6];
                if (sm < 0.5) return vec3(1.0);

                if (sm < 2.5) {
                    pos.x = fract(pos.x * 0.333333);
                    if      (pos.x < 0.333) m.r = uParams[5];
                    else if (pos.x < 0.666) m.g = uParams[5];
                    else                    m.b = uParams[5];
                } else {
                    pos.x += pos.y * 3.0;
                    pos.x = fract(pos.x * 0.166666);
                    if      (pos.x < 0.333) m.r = uParams[5];
                    else if (pos.x < 0.666) m.g = uParams[5];
                    else                    m.b = uParams[5];
                }
                return m;
            }

            void main() {
                vec2 pos = warp(vTexCoord);
                if (pos.x < 0.0 || pos.x > 1.0 || pos.y < 0.0 || pos.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec3 col = tri(pos);
                col += bloom(pos) * uParams[8];
                col *= mask(gl_FragCoord.xy);
                gl_FragColor = vec4(toSrgb(col), 1.0);
            }
        """.trimIndent()

        /**
         * Fast path: fewer taps, still has scanlines + mask + mild warp.
         * Much cheaper on low-end Android TV sticks.
         */
        private val FRAGMENT_FAST = """
            #version 100
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexSize;
            uniform float uParams[10];

            vec3 toLinear(vec3 c) { return c * c; }
            vec3 toSrgb(vec3 c) { return sqrt(max(c, 0.0)); }

            vec2 warp(vec2 pos) {
                pos = pos * 2.0 - 1.0;
                pos *= vec2(1.0 + (pos.y * pos.y) * uParams[2],
                            1.0 + (pos.x * pos.x) * uParams[3]);
                return pos * 0.5 + 0.5;
            }

            float scanWeight(float y) {
                float dy = fract(y * uTexSize.y) - 0.5;
                return exp2(uParams[0] * abs(dy));
            }

            vec3 aperture(vec2 pos) {
                vec3 m = vec3(uParams[4]);
                float x = fract(pos.x * 0.333333);
                if      (x < 0.333) m.r = uParams[5];
                else if (x < 0.666) m.g = uParams[5];
                else                m.b = uParams[5];
                return m;
            }

            void main() {
                vec2 pos = warp(vTexCoord);
                if (pos.x < 0.0 || pos.x > 1.0 || pos.y < 0.0 || pos.y > 1.0) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                vec3 col = toLinear(uParams[7] * texture2D(uTexSampler, pos).rgb);
                col *= scanWeight(pos.y);
                if (uParams[6] > 0.5) col *= aperture(gl_FragCoord.xy);
                gl_FragColor = vec4(toSrgb(col), 1.0);
            }
        """.trimIndent()
    }
}
