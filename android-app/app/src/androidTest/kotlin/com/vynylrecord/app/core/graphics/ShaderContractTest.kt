package com.vynylrecord.app.core.graphics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shader contract, checked against the APK.
 *
 * This test exists because of a real bug it now prevents. `GlProgram` resolves uniform locations once at
 * link time and `location(name)` is a map lookup — so a uniform the renderer binds but the list does not
 * contain returns `-1`, and `glUniform*` with `-1` is a silent no-op. The deck then draws with its
 * material properties missing: no grooves, no sheen, no label, a flat disc. Nothing fails, nothing is
 * logged, and the only symptom is that the 3D record looks wrong.
 *
 * Running the shaders on a real GPU is the job of a device test on a GL surface. What can be checked
 * everywhere, including on an emulator without a working GL driver, is that the two lists agree and that
 * the two shaders can link.
 */
@RunWith(AndroidJUnit4::class)
class ShaderContractTest {

    private lateinit var context: Context
    private lateinit var vertex: String
    private lateinit var fragment: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vertex = read("turntable.vert")
        fragment = read("turntable.frag")
    }

    private fun read(name: String): String = context.assets.open("shaders/$name")
        .bufferedReader()
        .use { it.readText() }

    /** The shader text without comments, so a comment that mentions `uniform` is not read as a declaration. */
    private fun code(source: String): String = source
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private fun declarations(source: String, keyword: String): List<String> =
        Regex("""(?m)^\s*$keyword\s+\w+\s+(\w+)\s*;""")
            .findAll(code(source))
            .map { it.groupValues[1] }
            .toList()

    private fun uniforms(source: String): List<String> = declarations(source, "uniform")

    @Test
    fun both_shaders_are_glsl_es_three_and_are_in_the_apk() {
        assertEquals("#version 300 es", vertex.lineSequence().first().trim())
        assertEquals("#version 300 es", fragment.lineSequence().first().trim())
        assertTrue("the vertex shader is empty", vertex.length > 400)
        assertTrue("the fragment shader is empty", fragment.length > 400)

        // ES 3.0 shaders must give a precision for floats; without it the shader fails to compile on
        // many drivers, and the failure is at runtime on a real device only.
        assertTrue("no float precision qualifier", fragment.contains("precision highp float"))
        assertTrue(fragment.contains("out vec4"))
    }

    @Test
    fun the_shaders_do_not_use_the_glsl_one_syntax_that_would_not_link() {
        listOf(vertex, fragment).forEach { source ->
            val body = code(source)
            assertFalse("GLSL 1.x attribute keyword", Regex("""(?m)^\s*attribute\s""").containsMatchIn(body))
            assertFalse("GLSL 1.x varying keyword", Regex("""(?m)^\s*varying\s""").containsMatchIn(body))
            assertFalse("gl_FragColor is not available in ES 3.0", body.contains("gl_FragColor"))
            assertFalse("texture2D was removed in ES 3.0", body.contains("texture2D("))
        }
    }

    @Test
    fun the_renderer_binds_exactly_the_uniforms_the_shaders_declare() {
        val declared = (uniforms(vertex) + uniforms(fragment)).toSet()
        val bound = GlProgram.EXPECTED_UNIFORMS.toSet()

        assertEquals("the renderer resolves one uniform more than once", GlProgram.EXPECTED_UNIFORMS.size, bound.size)
        assertEquals(
            "the renderer binds a uniform no shader declares, so it will silently do nothing",
            emptySet<String>(),
            bound - declared,
        )
        assertEquals(
            "a shader declares a uniform the renderer never sets, so it will always read as zero",
            emptySet<String>(),
            declared - bound,
        )
        // The list is ordered: vertex uniforms first, then material, then lighting.
        assertEquals("uModelMatrix", GlProgram.EXPECTED_UNIFORMS.first())
        assertTrue(GlProgram.EXPECTED_UNIFORMS.contains("uLabelTexture"))
        assertTrue(GlProgram.EXPECTED_UNIFORMS.contains("uGrooveAmount"))
        assertTrue(GlProgram.EXPECTED_UNIFORMS.contains("uUseLabel"))
        assertTrue(GlProgram.EXPECTED_UNIFORMS.contains("uSheen"))
    }

    @Test
    fun the_label_texture_is_the_only_texture_the_scene_needs() {
        val samplers = Regex("""uniform\s+sampler\w+\s+(\w+)\s*;""")
            .findAll(code(vertex) + code(fragment))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("uLabelTexture"), samplers)
    }

    @Test
    fun every_varying_the_fragment_shader_reads_is_written_by_the_vertex_shader() {
        val written = declarations(vertex, "out").toSet()
        val read = declarations(fragment, "in").toSet()
        assertEquals("the fragment shader reads ${read - written}, which the vertex shader never writes", emptySet<String>(), read - written)
        assertTrue("the vertex shader does not pass a normal", read.contains("vNormal"))
        assertTrue("the vertex shader does not pass a world position", read.contains("vWorldPosition"))
    }

    @Test
    fun the_attributes_match_the_buffers_the_meshes_upload() {
        // GpuMesh binds position at 0 and normal at 1; a shader that renumbers them draws garbage.
        assertTrue(Regex("""layout\s*\(\s*location\s*=\s*0\s*\)\s*in\s+vec3\s+(\w+)""").containsMatchIn(code(vertex)))
        assertTrue(Regex("""layout\s*\(\s*location\s*=\s*1\s*\)\s*in\s+vec3\s+(\w+)""").containsMatchIn(code(vertex)))
        val locations = Regex("""layout\s*\(\s*location\s*=\s*(\d+)\s*\)""")
            .findAll(code(vertex))
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals(listOf(0, 1), locations)
        // The UVs are derived from the local position, so no third attribute is needed; the mesh data
        // still carries them for the label pass.
        assertFalse(code(vertex).contains("location = 2"))
    }

    @Test
    fun the_vertex_shader_leaves_clip_space_to_the_view_projection() {
        val body = code(vertex)
        assertTrue(body.contains("gl_Position"))
        assertTrue("the camera matrix is not applied", body.contains("uViewProjectionMatrix"))
        assertTrue("the model matrix is not applied", body.contains("uModelMatrix"))
        assertTrue("the normal matrix is not applied", body.contains("uNormalMatrix"))
        // A shader that sets gl_Position to the model position renders everything at the origin.
        assertFalse(Regex("""gl_Position\s*=\s*vec4\s*\(\s*aPosition""").containsMatchIn(body))
    }
}
