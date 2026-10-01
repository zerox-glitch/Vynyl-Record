package com.vynylrecord.turntable.graphics.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log

/**
 * Compiled, linked GLSL ES 3.0 program with cached uniform locations.
 *
 * Sources are compiled once at renderer initialisation from bundled assets: nothing is read
 * from the network, and nothing is rebuilt per frame. Uniform locations are resolved lazily and
 * memoised, and the renderer additionally snapshots the ones it uses every frame into plain
 * ints (see the uniform holders in the scene/renderer) so the draw path never hashes a string.
 */
class ShaderProgram private constructor(
    val name: String,
    val handle: Int,
) {
    private val locations = HashMap<String, Int>(32)

    fun use() {
        GLES30.glUseProgram(handle)
    }

    /** Cached `glGetUniformLocation`. Returns -1 when the uniform is not active. */
    fun location(uniformName: String): Int {
        val cached = locations[uniformName]
        if (cached != null) return cached
        val location = GLES30.glGetUniformLocation(handle, uniformName)
        locations[uniformName] = location
        return location
    }

    fun setFloat(location: Int, value: Float) {
        if (location >= 0) GLES30.glUniform1f(location, value)
    }

    fun setInt(location: Int, value: Int) {
        if (location >= 0) GLES30.glUniform1i(location, value)
    }

    fun setVec2(location: Int, x: Float, y: Float) {
        if (location >= 0) GLES30.glUniform2f(location, x, y)
    }

    fun setVec3(location: Int, x: Float, y: Float, z: Float) {
        if (location >= 0) GLES30.glUniform3f(location, x, y, z)
    }

    fun setVec3(location: Int, values: FloatArray, offset: Int = 0) {
        if (location >= 0) GLES30.glUniform3fv(location, 1, values, offset)
    }

    fun setVec4(location: Int, x: Float, y: Float, z: Float, w: Float) {
        if (location >= 0) GLES30.glUniform4f(location, x, y, z, w)
    }

    fun setVec4(location: Int, values: FloatArray, offset: Int = 0) {
        if (location >= 0) GLES30.glUniform4fv(location, 1, values, offset)
    }

    fun setMat3(location: Int, values: FloatArray, offset: Int = 0) {
        if (location >= 0) GLES30.glUniformMatrix3fv(location, 1, false, values, offset)
    }

    fun setMat4(location: Int, values: FloatArray, offset: Int = 0) {
        if (location >= 0) GLES30.glUniformMatrix4fv(location, 1, false, values, offset)
    }

    fun release() {
        if (handle != 0) GLES30.glDeleteProgram(handle)
        locations.clear()
    }

    companion object {

        private const val TAG = "VynylGL"

        fun fromAssets(assets: AssetManager, name: String, vertexAsset: String, fragmentAsset: String): ShaderProgram {
            val vertexSource = assets.open(vertexAsset).bufferedReader().use { it.readText() }
            val fragmentSource = assets.open(fragmentAsset).bufferedReader().use { it.readText() }
            return fromSources(name, vertexSource, fragmentSource)
        }

        fun fromSources(name: String, vertexSource: String, fragmentSource: String): ShaderProgram {
            val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$name.vert")
            val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$name.frag")
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw IllegalStateException("Failed to link $name: $log")
            }
            GLES30.glDetachShader(program, vertexShader)
            GLES30.glDetachShader(program, fragmentShader)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            return ShaderProgram(name, program)
        }

        private fun compile(type: Int, source: String, label: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Failed to compile $label: $log")
            }
            if (com.vynylrecord.turntable.BuildConfig.DEBUG) {
                Log.v(TAG, "compiled $label")
            }
            return shader
        }
    }
}
