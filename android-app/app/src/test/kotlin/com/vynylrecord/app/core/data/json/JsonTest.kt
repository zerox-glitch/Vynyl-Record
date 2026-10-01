package com.vynylrecord.app.core.data.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-written JSON reader and writer.
 *
 * It exists because a `.vynyl` bundle is read back years and app versions later, and a document that
 * cannot round-trip a dedication with a quote in it is a lost recording. Everything else in the bundle
 * format leans on this file.
 */
class JsonTest {

    @Test
    fun `scalars write the way JSON expects`() {
        assertEquals("\"hello\"", JsonValue.of("hello").write())
        assertEquals("42", JsonValue.of(42).write())
        assertEquals("42", JsonValue.of(42L).write())
        assertEquals("true", JsonValue.of(true).write())
        assertEquals("false", JsonValue.of(false).write())
        assertEquals("0.5", JsonValue.of(0.5f).write())
        assertEquals("[]", JsonValue.arr(emptyList()).write())
        assertEquals("{}", JsonValue.obj(emptyMap()).write())
    }

    @Test
    fun `strings are escaped and come back unchanged`() {
        val awkward = "She said \"remember me\"\nand left\\the room.\tÜnïcøde ✓ 日本語"
        val document = JsonValue.obj("dedication" to JsonValue.of(awkward))
        val text = document.write()
        assertFalse("a raw newline leaked into the document", text.contains("\n"))
        assertFalse(text.contains("\t"))

        val parsed = JsonValue.parse(text)?.asObjOrNull()
        assertNotNull(parsed)
        assertEquals(awkward, parsed!!.string("dedication"))
    }

    @Test
    fun `a nested document round-trips`() {
        val document = JsonValue.obj(
            "title" to JsonValue.of("Sunday Kitchen"),
            "durationMilliseconds" to JsonValue.of(93_000L),
            "favourite" to JsonValue.of(true),
            "recipe" to JsonValue.obj(
                "lowPassHz" to JsonValue.of(12_600f),
                "stereoWidth" to JsonValue.of(0.92f),
                "surfaceFilter" to JsonValue.of("highpass-low"),
            ),
            "peaks" to JsonValue.ofNumbers(listOf(0.1f, 0.5f, 1f)),
            "sides" to JsonValue.of(listOf("Side A", "Side B")),
        )

        val parsed = JsonValue.parse(document.write())
        assertNotNull(parsed)
        val object1 = parsed!!.asObjOrNull()!!
        assertEquals("Sunday Kitchen", object1.string("title"))
        assertEquals(93_000L, object1.long("durationMilliseconds"))
        assertTrue(object1.bool("favourite"))
        assertEquals(12_600f, object1.obj("recipe")!!.float("lowPassHz"), 0.5f)
        assertEquals("highpass-low", object1.obj("recipe")!!.string("surfaceFilter"))
        assertEquals(3, object1.array("peaks")!!.size)
        assertEquals(1f, object1.array("peaks")!!.numbers()[2].toFloat(), 1e-6f)
        assertEquals(listOf("Side A", "Side B"), object1.array("sides")!!.strings())
    }

    @Test
    fun `accessors fall back instead of throwing`() {
        val document = JsonValue.obj("title" to JsonValue.of("A"))
        assertEquals("A", document.string("title"))
        assertEquals("missing", document.string("nope", "missing"))
        assertEquals(0L, document.long("nope"))
        assertEquals(7, document.int("nope", 7))
        assertEquals(0f, document.float("nope"), 0f)
        assertEquals(0.0, document.double("nope"), 0.0)
        assertFalse(document.bool("nope"))
        assertNull(document.array("nope"))
        assertNull(document.obj("nope"))
        assertNull(document.string("title").takeIf { false })
    }

    @Test
    fun `a wrong type falls back rather than coercing silently`() {
        val document = JsonValue.obj(
            "count" to JsonValue.of("seven"),
            "flag" to JsonValue.of(1),
        )
        assertEquals(3, document.int("count", 3))
        assertEquals("", document.string("count"))
        assertFalse(document.bool("flag"))
    }

    @Test
    fun `numbers survive a round trip including integers and negatives`() {
        val numbers = listOf(0, 1, -1, 42, -9_999, 1_234_567)
        val document = JsonValue.arr(numbers.map { JsonValue.of(it) })
        val parsed = JsonValue.parse(document.write())!!.asArrOrNull()!!
        assertEquals(numbers.size, parsed.size)
        numbers.forEachIndexed { index, value ->
            assertEquals(value.toDouble(), parsed.numbers()[index], 0.0)
            assertEquals(value, parsed.items[index].let { (it as JsonValue.Num).toInt() })
        }
    }

    @Test
    fun `pretty printing is still parseable`() {
        val document = JsonValue.obj(
            "kind" to JsonValue.of("vynyl.record"),
            "record" to JsonValue.obj("title" to JsonValue.of("A Quiet Morning")),
        )
        val pretty = document.write(pretty = true)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("  "))
        assertEquals(document, JsonValue.parse(pretty))
    }

    @Test
    fun `broken documents return null rather than half a value`() {
        assertNull(JsonValue.parse(""))
        assertNull(JsonValue.parse("{"))
        assertNull(JsonValue.parse("{\"a\":}"))
        assertNull(JsonValue.parse("[1,2"))
        assertNull(JsonValue.parse("not json at all"))
        assertNull(JsonValue.parse("{\"a\" 1}"))
    }

    @Test
    fun `whitespace and empty containers are tolerated`() {
        assertEquals("A", JsonValue.parse("  {  \"t\"  :  \"A\"  }  ")!!.asObjOrNull()!!.string("t"))
        assertEquals(0, JsonValue.parse("{}")!!.asObjOrNull()!!.entries.size)
        assertEquals(0, JsonValue.parse("[]")!!.asArrOrNull()!!.size)
        assertEquals(1, JsonValue.parse("[ 1 ]")!!.asArrOrNull()!!.size)
    }

    @Test
    fun `a document with the wrong shape is still readable as a value`() {
        val text = "{\"peaks\":[0.1,0.2],\"nested\":{\"deep\":true}}"
        val parsed = JsonValue.parse(text)
        assertNotNull(parsed)
        val object1 = parsed!!.asObjOrNull()!!
        assertEquals(2, object1.array("peaks")!!.size)
        assertNull(object1.obj("peaks"))
        assertTrue(object1.obj("nested")!!.bool("deep"))
        // A number accessor on an array must not throw.
        assertEquals(0f, object1.float("peaks"), 0f)
    }

    @Test
    fun `the writer keeps key order so a manifest reads the same every time`() {
        val first = JsonValue.obj(
            "kind" to JsonValue.of("vynyl.record"),
            "appVersion" to JsonValue.of("1.0.0"),
            "exportedAt" to JsonValue.of(1L),
        )
        val second = JsonValue.obj(
            linkedMapOf(
                "kind" to JsonValue.of("vynyl.record"),
                "appVersion" to JsonValue.of("1.0.0"),
                "exportedAt" to JsonValue.of(1L),
            ),
        )
        assertEquals(first.write(), second.write())
        assertTrue(first.write().indexOf("kind") < first.write().indexOf("appVersion"))
    }
}
