package com.vynylrecord.app.core.data.json

import java.util.Locale

/**
 * A small JSON reader and writer, because this app has four places that need one and none of them are
 * worth a dependency.
 *
 * It serialises the advanced controls on a record row, the manifest inside a `.vynyl` bundle, the backup
 * index, and the waveform sidecar. Each of those is written by this app and read by this app — but a
 * bundle is also a file a user can carry between installs, so the format has to be a real one that other
 * tools can read and a human can inspect, not a Java serialisation.
 *
 * Parsing is total: a malformed document returns null rather than throwing, because every caller is
 * reading a file that came from outside the app's control and none of them can do anything useful with an
 * exception.
 */
sealed interface JsonValue {

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
        fun string(key: String, fallback: String = ""): String = (entries[key] as? Str)?.value ?: fallback
        fun long(key: String, fallback: Long = 0L): Long = (entries[key] as? Num)?.toLong() ?: fallback
        fun int(key: String, fallback: Int = 0): Int = (entries[key] as? Num)?.toInt() ?: fallback
        fun float(key: String, fallback: Float = 0f): Float = (entries[key] as? Num)?.toFloat() ?: fallback
        fun double(key: String, fallback: Double = 0.0): Double = (entries[key] as? Num)?.value ?: fallback
        fun bool(key: String, fallback: Boolean = false): Boolean = (entries[key] as? Bool)?.value ?: fallback
        fun array(key: String): Arr? = entries[key] as? Arr
        fun obj(key: String): Obj? = entries[key] as? Obj
    }

    data class Arr(val items: List<JsonValue>) : JsonValue {
        val size: Int get() = items.size
        fun strings(): List<String> = items.mapNotNull { (it as? Str)?.value }
        fun numbers(): List<Double> = items.mapNotNull { (it as? Num)?.value }
        operator fun get(index: Int): JsonValue? = items.getOrNull(index)
    }

    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue {
        fun toLong(): Long = value.toLong()
        fun toInt(): Int = value.toInt()
        fun toFloat(): Float = value.toFloat()
    }

    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    /** Writes the value back out. `pretty` is for files a person might open. */
    fun write(pretty: Boolean = false): String = buildString { writeTo(this, this@JsonValue, pretty, 0) }

    fun asStringOrNull(): String? = (this as? Str)?.value
    fun asObjOrNull(): Obj? = this as? Obj
    fun asArrOrNull(): Arr? = this as? Arr

    companion object {
        fun of(value: String): JsonValue = Str(value)
        fun of(value: Int): JsonValue = Num(value.toDouble())
        fun of(value: Long): JsonValue = Num(value.toDouble())
        fun of(value: Float): JsonValue = Num(value.toDouble())
        fun of(value: Double): JsonValue = Num(value)
        fun of(value: Boolean): JsonValue = Bool(value)
        fun of(value: List<String>): JsonValue = Arr(value.map { Str(it) })
        fun ofNumbers(values: List<Float>): JsonValue = Arr(values.map { Num(it.toDouble()) })

        fun obj(vararg pairs: Pair<String, JsonValue>): Obj = Obj(linkedMapOf(*pairs))
        fun obj(pairs: Map<String, JsonValue>): Obj = Obj(LinkedHashMap(pairs))
        fun arr(values: List<JsonValue>): Arr = Arr(values)

        private fun writeTo(builder: StringBuilder, value: JsonValue, pretty: Boolean, depth: Int) {
            when (value) {
                is Obj -> {
                    if (value.entries.isEmpty()) {
                        builder.append("{}")
                        return
                    }
                    builder.append('{')
                    val inner = if (pretty) depth + 1 else depth
                    value.entries.entries.forEachIndexed { index, (key, child) ->
                        if (index > 0) builder.append(',')
                        newline(builder, pretty, inner)
                        builder.append('"').append(escape(key)).append("\":")
                        if (pretty) builder.append(' ')
                        writeTo(builder, child, pretty, inner)
                    }
                    newline(builder, pretty, depth)
                    builder.append('}')
                }

                is Arr -> {
                    if (value.items.isEmpty()) {
                        builder.append("[]")
                        return
                    }
                    builder.append('[')
                    val inner = if (pretty) depth + 1 else depth
                    value.items.forEachIndexed { index, child ->
                        if (index > 0) builder.append(',')
                        newline(builder, pretty, inner)
                        writeTo(builder, child, pretty, inner)
                    }
                    newline(builder, pretty, depth)
                    builder.append(']')
                }

                is Str -> builder.append('"').append(escape(value.value)).append('"')
                is Num -> builder.append(number(value.value))
                is Bool -> builder.append(if (value.value) "true" else "false")
                is Null -> builder.append("null")
            }
        }

        private fun newline(builder: StringBuilder, pretty: Boolean, depth: Int) {
            if (!pretty) return
            builder.append('\n')
            repeat(depth) { builder.append("  ") }
        }

        /** Integers stay integers: a record id in a manifest should not read as `12.0`. */
        private fun number(value: Double): String {
            if (value.isNaN() || value.isInfinite()) return "0"
            return if (value == Math.floor(value) && Math.abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
            }
        }

        private fun escape(text: String): String {
            val builder = StringBuilder(text.length + 8)
            for (character in text) {
                when (character) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> if (character < ' ') {
                        builder.append(String.format(Locale.US, "\\u%04x", character.code))
                    } else {
                        builder.append(character)
                    }
                }
            }
            return builder.toString()
        }

        /** Parses a document, or returns null when it is not one. */
        fun parse(text: String): JsonValue? = try {
            val parser = Parser(text)
            val value = parser.readValue()
            parser.skipWhitespace()
            if (parser.atEnd()) value else null
        } catch (error: Exception) {
            null
        }
    }
}

/** A recursive-descent reader. Deliberately strict, so a corrupted bundle fails instead of half-parsing. */
private class Parser(private val text: String) {

    private var index = 0

    fun atEnd(): Boolean = index >= text.length

    fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun readValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) throw IllegalStateException("unexpected end of document")
        return when (val character = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't', 'f' -> readBoolean()
            'n' -> readNull()
            else -> if (character == '-' || character.isDigit()) readNumber() else {
                throw IllegalStateException("unexpected character '$character' at $index")
            }
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val entries = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            entries[key] = readValue()
            skipWhitespace()
            when (val character = next()) {
                ',' -> continue
                '}' -> return JsonValue.Obj(entries)
                else -> throw IllegalStateException("expected ',' or '}' but found '$character'")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += readValue()
            skipWhitespace()
            when (val character = next()) {
                ',' -> continue
                ']' -> return JsonValue.Arr(items)
                else -> throw IllegalStateException("expected ',' or ']' but found '$character'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            val character = next()
            when (character) {
                '"' -> return builder.toString()
                '\\' -> when (val escape = next()) {
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'u' -> {
                        val hex = text.substring(index, index + 4)
                        index += 4
                        builder.append(hex.toInt(16).toChar())
                    }

                    else -> throw IllegalStateException("unknown escape '\\$escape'")
                }

                else -> builder.append(character)
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        if (peek() == '-') index++
        while (!atEnd() && (text[index].isDigit() || text[index] == '.' || text[index] == 'e' ||
                text[index] == 'E' || text[index] == '+' || text[index] == '-')
        ) {
            index++
        }
        val slice = text.substring(start, index)
        return JsonValue.Num(slice.toDoubleOrNull() ?: throw IllegalStateException("bad number '$slice'"))
    }

    private fun readBoolean(): JsonValue.Bool {
        if (text.startsWith("true", index)) {
            index += 4
            return JsonValue.Bool(true)
        }
        if (text.startsWith("false", index)) {
            index += 5
            return JsonValue.Bool(false)
        }
        throw IllegalStateException("bad literal at $index")
    }

    private fun readNull(): JsonValue {
        if (text.startsWith("null", index)) {
            index += 4
            return JsonValue.Null
        }
        throw IllegalStateException("bad literal at $index")
    }

    private fun peek(): Char = if (atEnd()) '\u0000' else text[index]

    private fun next(): Char {
        if (atEnd()) throw IllegalStateException("unexpected end of document")
        return text[index++]
    }

    private fun expect(character: Char) {
        val found = next()
        if (found != character) throw IllegalStateException("expected '$character' but found '$found'")
    }
}
