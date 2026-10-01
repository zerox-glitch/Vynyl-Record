package com.vynylrecord.app.core.graphics

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The GL objects the renderer owns: one program, one mesh buffer per shape, one texture.
 *
 * Every failure here is reported as a null or a message rather than an exception, because a device can
 * legitimately refuse any of it — an old driver, a context lost in the background, a phone whose GPU does
 * not like a 128-segment disc. The player turns any of those into the static 2D deck, which is a working
 * screen rather than a black rectangle.
 */
internal object GlCheck {

    private const val TAG = "VynylGl"

    /** Runs [block] and reports the GL error it produced, if any. */
    fun check(where: String, block: () -> Unit = {}) {
        block()
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "$where produced GL error 0x${Integer.toHexString(error)}")
        }
    }

    /** The GL error name, for the debug overlay and the failure message. */
    fun describe(error: Int): String = when (error) {
        GLES30.GL_NO_ERROR -> "no error"
        GLES30.GL_INVALID_ENUM -> "invalid enum"
        GLES30.GL_INVALID_VALUE -> "invalid value"
        GLES30.GL_INVALID_OPERATION -> "invalid operation"
        GLES30.GL_OUT_OF_MEMORY -> "out of memory"
        else -> "0x${Integer.toHexString(error)}"
    }
}

/**
 * A compiled and linked program.
 *
 * Uniform locations are looked up once, at link time, and cached: `glGetUniformLocation` on every frame for
 * every part would be a string lookup per uniform per draw call, which is exactly the kind of thing that
 * makes a 60 FPS scene run at 40 on a mid-range phone.
 */
internal class GlProgram private constructor(
    val id: Int,
    private val uniforms: Map<String, Int>,
) {

    fun location(name: String): Int = uniforms[name] ?: -1

    fun use() {
        GLES30.glUseProgram(id)
    }

    fun release() {
        if (id != 0) GLES30.glDeleteProgram(id)
    }

    companion object {
        /**
         * Builds a program from two GLSL files in `assets/shaders`.
         *
         * The shaders ship as assets rather than as string constants so they can be read, diffed and
         * syntax-highlighted like the code they are. The cost is a tiny bit of I/O at startup, once.
         */
        fun load(context: Context, vertexAsset: String, fragmentAsset: String): GlProgram? {
            val vertexSource = readAsset(context, vertexAsset) ?: return null
            val fragmentSource = readAsset(context, fragmentAsset) ?: return null
            return build(vertexSource, fragmentSource)
        }

        fun build(vertexSource: String, fragmentSource: String): GlProgram? {
            val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return null
            val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
                GLES30.glDeleteShader(vertexShader)
                return null
            }
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "program link failed: ${GLES30.glGetProgramInfoLog(program)}")
                GLES30.glDeleteProgram(program)
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                return null
            }
            // The shaders themselves are no longer needed once linked; deleting them now is what keeps a
            // context alive across many screens' worth of reloads.
            GLES30.glDetachShader(program, vertexShader)
            GLES30.glDetachShader(program, fragmentShader)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)

            val uniforms = EXPECTED_UNIFORMS.associateWith { name -> GLES30.glGetUniformLocation(program, name) }
            return GlProgram(program, uniforms)
        }

        /**
         * Every uniform the two shaders declare, resolved once at link time.
         *
         * This list is a contract with `assets/shaders/turntable.{vert,frag}`: `location()` is a map
         * lookup, so a name missing here returns -1 and every `glUniform*` call for it becomes a silent
         * no-op. A mistyped or forgotten entry does not fail — the deck simply loses that material
         * property and renders as a flat disc. `ShaderContractTest` (androidTest) asserts the two lists
         * match in both directions, which is the only way this stays true.
         */
        val EXPECTED_UNIFORMS = listOf(
            // vertex
            "uModelMatrix",
            "uViewProjectionMatrix",
            "uNormalMatrix",
            "uUvScale",
            "uUvOffset",
            // fragment: material
            "uCameraPosition",
            "uBaseColor",
            "uSpecularColor",
            "uRoughness",
            "uMetallic",
            "uAlpha",
            "uTransmission",
            "uGrooveAmount",
            "uSheen",
            "uUseLabel",
            "uLabelTexture",
            // fragment: lighting
            "uKeyLightDirection",
            "uKeyLightColor",
            "uFillLightColor",
            "uAmbient",
            "uLampColor",
            "uLampIntensity",
            "uLampPosition",
            "uTime",
        )

        private fun compile(type: Int, source: String): Int? {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                Log.e(TAG, "shader compile failed (${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}): $log")
                GLES30.glDeleteShader(shader)
                return null
            }
            return shader
        }

        private fun readAsset(context: Context, name: String): String? = try {
            context.assets.open("shaders/$name").bufferedReader().use { it.readText() }
        } catch (error: FileNotFoundException) {
            Log.e(TAG, "shader asset $name is missing from the APK", error)
            null
        } catch (error: Exception) {
            Log.e(TAG, "shader asset $name could not be read", error)
            null
        }

        private const val TAG = "VynylGlProgram"
    }
}

/**
 * One shape on the GPU: a vertex array object with its three attributes and its index buffer.
 *
 * Vertex data is uploaded once. The buffers are direct, native-order and reused for the object's life —
 * which is what keeps the render loop free of allocation and, on a device with a small heap, free of the
 * pauses that come with it.
 */
internal class GpuMesh(private val mesh: MeshData) {

    val indexCount: Int = mesh.indexCount

    private val vao = IntArray(1)
    private val vbo = IntArray(2)
    private val ibo = IntArray(1)

    /** Uploads the mesh. Must be called with a current GL context. */
    fun upload() {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        GLES30.glGenBuffers(2, vbo, 0)
        attributeData(0, vbo[0], mesh.positions, 3)
        attributeData(1, vbo[1], mesh.normals, 3)

        GLES30.glGenBuffers(1, ibo, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indices.size * Short.SIZE_BYTES,
            shortBuffer(mesh.indices),
            GLES30.GL_STATIC_DRAW,
        )

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GlCheck.check("uploading a mesh")
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vao[0] != 0) GLES30.glDeleteVertexArrays(1, vao, 0)
        if (vbo[0] != 0) GLES30.glDeleteBuffers(2, vbo, 0)
        if (ibo[0] != 0) GLES30.glDeleteBuffers(1, ibo, 0)
        vao[0] = 0
        ibo[0] = 0
    }

    private fun attributeData(location: Int, buffer: Int, data: FloatArray, components: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * Float.SIZE_BYTES, floatBuffer(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, components, GLES30.GL_FLOAT, false, 0, 0)
    }

    private companion object {
        fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

        fun shortBuffer(data: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(data.size * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(data)
                    position(0)
                }
    }
}
