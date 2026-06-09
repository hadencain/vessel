package com.vessel

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PlasmaRenderer"

class PlasmaRenderer {

    private var program     = 0
    private var quadVao     = 0
    private var cameraTexId = 0

    // Uniform locations
    private var uCameraTexture  = -1
    private var uTime           = -1
    private var uResolution     = -1
    private var uArtifactPos    = -1
    private var uViewProjection = -1
    private var uContactMag     = -1
    private var uContactPoint   = -1
    private var uBreathPhase    = -1
    private var uBleedHue       = -1
    private var uBleedEnabled   = -1
    private var uUvTransform    = -1
    private var uSmearDir       = -1
    private var uSmearSpeed     = -1

    // Pre-allocated buffers — avoids per-frame GC pressure
    private val ndcCorners  = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f)
    private val camCorners  = FloatArray(6)
    private val uvTransform = FloatArray(9)

    fun init(cameraTextureId: Int, context: Context) {
        cameraTexId = cameraTextureId

        val vertSrc = context.assets.open("shaders/plasma_vertex.glsl")
            .bufferedReader().readText()
        val fragSrc = context.assets.open("shaders/plasma_fragment.glsl")
            .bufferedReader().readText()

        program = buildProgram(vertSrc, fragSrc)

        uCameraTexture  = GLES30.glGetUniformLocation(program, "u_cameraTexture")
        uTime           = GLES30.glGetUniformLocation(program, "u_time")
        uResolution     = GLES30.glGetUniformLocation(program, "u_resolution")
        uArtifactPos    = GLES30.glGetUniformLocation(program, "u_artifactWorldPos")
        uViewProjection = GLES30.glGetUniformLocation(program, "u_viewProjection")
        uContactMag     = GLES30.glGetUniformLocation(program, "u_contactMagnitude")
        uContactPoint   = GLES30.glGetUniformLocation(program, "u_contactPoint")
        uBreathPhase    = GLES30.glGetUniformLocation(program, "u_breathPhase")
        uBleedHue       = GLES30.glGetUniformLocation(program, "u_bleedHue")
        uBleedEnabled   = GLES30.glGetUniformLocation(program, "u_bleedEnabled")
        uUvTransform    = GLES30.glGetUniformLocation(program, "u_uvTransform")
        uSmearDir       = GLES30.glGetUniformLocation(program, "u_smearDir")
        uSmearSpeed     = GLES30.glGetUniformLocation(program, "u_smearSpeed")

        quadVao = createFullScreenQuad()
    }

    fun draw(
        frame: Frame,
        vpMatrix: FloatArray,
        artifactPos: FloatArray,
        contactMag: Float,
        contactPoint: FloatArray,
        smearDir: FloatArray,
        smearSpeed: Float,
        breathPhase: Float,
        bleedHue: Float,
        bleedEnabled: Float,
        resolution: FloatArray,
        timeSec: Float
    ) {
        // ARCore updates the OES texture automatically on session.update().
        // No explicit background draw call needed here — the full-screen shader
        // samples u_cameraTexture directly for the background and plasma layers.

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(program)

        // Compute the affine mat3 that maps screen UV [0,1] → camera texture UV.
        // ARCore transforms NDC coords to the OES texture's native UV space, which
        // accounts for camera sensor rotation vs. display orientation.
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcCorners,
            Coordinates2d.TEXTURE_NORMALIZED, camCorners
        )
        // camCorners[0,1] = camUV for screenUV (0,0)  — translation t
        // camCorners[2,3] = camUV for screenUV (1,0)  — column 0 = result - t
        // camCorners[4,5] = camUV for screenUV (0,1)  — column 1 = result - t
        val tx = camCorners[0]; val ty = camCorners[1]
        val a  = camCorners[2] - tx; val b = camCorners[3] - ty
        val c  = camCorners[4] - tx; val d = camCorners[5] - ty
        // GLSL mat3 is column-major: col0=(a,b,0), col1=(c,d,0), col2=(tx,ty,1)
        uvTransform[0] = a;  uvTransform[1] = b;  uvTransform[2] = 0f
        uvTransform[3] = c;  uvTransform[4] = d;  uvTransform[5] = 0f
        uvTransform[6] = tx; uvTransform[7] = ty; uvTransform[8] = 1f
        GLES30.glUniformMatrix3fv(uUvTransform, 1, false, uvTransform, 0)

        // Bind the ARCore OES texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glUniform1i(uCameraTexture, 0)

        GLES30.glUniform1f(uTime, timeSec)
        GLES30.glUniform2f(uResolution, resolution[0], resolution[1])
        GLES30.glUniform3f(uArtifactPos, artifactPos[0], artifactPos[1], artifactPos[2])
        GLES30.glUniformMatrix4fv(uViewProjection, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(uContactMag, contactMag)
        GLES30.glUniform3f(uContactPoint, contactPoint[0], contactPoint[1], contactPoint[2])
        GLES30.glUniform1f(uBreathPhase, breathPhase)
        GLES30.glUniform1f(uBleedHue, bleedHue)
        GLES30.glUniform1f(uBleedEnabled, bleedEnabled)
        GLES30.glUniform3f(uSmearDir, smearDir[0], smearDir[1], smearDir[2])
        GLES30.glUniform1f(uSmearSpeed, smearSpeed)

        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun createFullScreenQuad(): Int {
        // x, y, u, v — NDC positions with UV coords
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).position(0)

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboArr[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)

        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        GLES30.glBindVertexArray(vaoArr[0])

        val stride = 4 * 4  // 4 floats × 4 bytes
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        return vaoArr[0]
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vert)
        GLES30.glAttachShader(prog, frag)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(prog)}")
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }
}
