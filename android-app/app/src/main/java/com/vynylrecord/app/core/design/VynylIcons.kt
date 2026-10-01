package com.vynylrecord.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own icons.
 *
 * Drawn here rather than pulled from a library because the app has a specific vocabulary: a turntable, a
 * waveform, a shelf of records. A generic "home/search/settings" set would make the Studio look like a
 * settings app with a record in it.
 *
 * Every glyph is built on a 24×24 grid, is a single flat colour, and uses no fill rule trickery, so it stays
 * legible at 20 dp in a bottom bar and at 48 dp in a toolbar. The strokes are two units wide, which is the
 * weight that matches the app's brass hairlines.
 */
object VynylIcons {

    private const val VIEWPORT = 24f
    private const val STROKE = 1.9f

    /** The Studio: a record with a microphone stylus over it — the act of pressing a voice. */
    val Studio: ImageVector by lazy {
        icon("VynylStudio") {
            // The disc.
            circle(12f, 12f, 8.4f)
            circle(12f, 12f, 2.2f)
            // The stylus, angled in from the upper right.
            moveTo(15.4f, 4.6f)
            lineTo(20.6f, 6.4f)
            moveTo(20.6f, 6.4f)
            lineTo(15.8f, 9.4f)
            // The microphone stand under the disc: the voice being pressed.
            moveTo(12f, 20.4f)
            lineTo(12f, 22.4f)
            moveTo(9f, 22.4f)
            lineTo(15f, 22.4f)
        }
    }

    /** The Sound Lab: a waveform between two rails. */
    val SoundLab: ImageVector by lazy {
        icon("VynylSoundLab") {
            moveTo(2.4f, 12f)
            lineTo(2.4f, 12f)
            moveTo(5.2f, 8.4f)
            lineTo(5.2f, 15.6f)
            moveTo(8.2f, 4.6f)
            lineTo(8.2f, 19.4f)
            moveTo(11.2f, 7.2f)
            lineTo(11.2f, 16.8f)
            moveTo(14.2f, 3.4f)
            lineTo(14.2f, 20.6f)
            moveTo(17.2f, 8.8f)
            lineTo(17.2f, 15.2f)
            moveTo(20.2f, 6.2f)
            lineTo(20.2f, 17.8f)
        }
    }

    /** The Vault: records standing on a shelf, seen end-on. */
    val Vault: ImageVector by lazy {
        icon("VynylVault") {
            // The shelf.
            moveTo(2.2f, 19.4f)
            lineTo(21.8f, 19.4f)
            // Five spines of different heights, like a shelf of records.
            rect(3.6f, 7.2f, 2.6f, 12.2f)
            rect(7.4f, 4.6f, 2.6f, 14.8f)
            rect(11.2f, 8.6f, 2.6f, 10.8f)
            rect(15.0f, 5.8f, 2.6f, 13.6f)
            // One record leaning out, because a shelf of records always has one.
            moveTo(18.8f, 19.4f)
            lineTo(20.6f, 6.2f)
        }
    }

    /** Settings: three faders. */
    val Settings: ImageVector by lazy {
        icon("VynylSettings") {
            moveTo(3f, 7f)
            lineTo(21f, 7f)
            moveTo(3f, 12f)
            lineTo(21f, 12f)
            moveTo(3f, 17f)
            lineTo(21f, 17f)
            circle(8f, 7f, 2.1f)
            circle(15f, 12f, 2.1f)
            circle(10f, 17f, 2.1f)
        }
    }

    val Play: ImageVector by lazy {
        filledIcon("VynylPlay") {
            moveTo(7.5f, 4.8f)
            lineTo(19.5f, 12f)
            lineTo(7.5f, 19.2f)
            close()
        }
    }

    val Pause: ImageVector by lazy {
        filledIcon("VynylPause") {
            rect(6.5f, 4.8f, 4f, 14.4f)
            rect(13.5f, 4.8f, 4f, 14.4f)
        }
    }

    val Stop: ImageVector by lazy {
        filledIcon("VynylStop") {
            rect(6.5f, 6.5f, 11f, 11f)
        }
    }

    /** Recording: an amber dot inside a ring, which is what a record button has looked like forever. */
    val Record: ImageVector by lazy {
        icon("VynylRecordIcon") {
            circle(12f, 12f, 8.6f)
            circle(12f, 12f, 3.6f)
        }
    }

    val Favourite: ImageVector by lazy {
        filledIcon("VynylFavourite") {
            moveTo(12f, 20.4f)
            lineTo(4.6f, 12.9f)
            lineTo(4.6f, 8.4f)
            lineTo(7.4f, 5.2f)
            lineTo(12f, 9.2f)
            lineTo(16.6f, 5.2f)
            lineTo(19.4f, 8.4f)
            lineTo(19.4f, 12.9f)
            close()
        }
    }

    val Export: ImageVector by lazy {
        icon("VynylExport") {
            moveTo(12f, 3.6f)
            lineTo(12f, 14.4f)
            moveTo(7.8f, 7.8f)
            lineTo(12f, 3.6f)
            moveTo(16.2f, 7.8f)
            lineTo(12f, 3.6f)
            moveTo(4.4f, 15.6f)
            lineTo(4.4f, 20.4f)
            lineTo(19.6f, 20.4f)
            lineTo(19.6f, 15.6f)
        }
    }

    val Share: ImageVector by lazy {
        icon("VynylShare") {
            circle(6f, 12f, 2.6f)
            circle(17.4f, 5.8f, 2.6f)
            circle(17.4f, 18.2f, 2.6f)
            moveTo(8.4f, 10.8f)
            lineTo(15f, 7f)
            moveTo(8.4f, 13.2f)
            lineTo(15f, 17f)
        }
    }

    val Edit: ImageVector by lazy {
        icon("VynylEdit") {
            moveTo(4.4f, 19.6f)
            lineTo(4.4f, 15.4f)
            lineTo(15.6f, 4.2f)
            lineTo(19.8f, 8.4f)
            lineTo(8.6f, 19.6f)
            close()
            moveTo(13.2f, 6.6f)
            lineTo(17.4f, 10.8f)
        }
    }

    /** Re-render: a record with a circular arrow. */
    val Render: ImageVector by lazy {
        icon("VynylRender") {
            circle(11f, 12f, 6.4f)
            circle(11f, 12f, 1.6f)
            moveTo(17.4f, 6.2f)
            lineTo(17.4f, 10.4f)
            lineTo(13.2f, 10.4f)
            moveTo(18.8f, 12.4f)
            lineTo(21.4f, 12.4f)
        }
    }

    val Duplicate: ImageVector by lazy {
        icon("VynylDuplicate") {
            rect(3.6f, 3.6f, 12.4f, 12.4f)
            rect(8f, 8f, 12.4f, 12.4f)
        }
    }

    val Delete: ImageVector by lazy {
        icon("VynylDelete") {
            moveTo(4.4f, 6.6f)
            lineTo(19.6f, 6.6f)
            moveTo(9.2f, 6.6f)
            lineTo(9.2f, 3.8f)
            lineTo(14.8f, 3.8f)
            lineTo(14.8f, 6.6f)
            moveTo(6.2f, 6.6f)
            lineTo(7.4f, 20.4f)
            lineTo(16.6f, 20.4f)
            lineTo(17.8f, 6.6f)
        }
    }

    val Camera: ImageVector by lazy {
        icon("VynylCamera") {
            circle(12f, 12f, 4.4f)
            moveTo(3.4f, 12f)
            lineTo(6.2f, 12f)
            moveTo(17.8f, 12f)
            lineTo(20.6f, 12f)
            moveTo(12f, 3.4f)
            lineTo(12f, 6.2f)
            moveTo(12f, 17.8f)
            lineTo(12f, 20.6f)
        }
    }

    val Lock: ImageVector by lazy {
        icon("VynylLock") {
            rect(4.6f, 10.4f, 14.8f, 10f)
            moveTo(8f, 10.4f)
            lineTo(8f, 7.6f)
            moveTo(8f, 7.6f)
            lineTo(12f, 4.4f)
            lineTo(16f, 7.6f)
            lineTo(16f, 10.4f)
        }
    }

    val Import: ImageVector by lazy {
        icon("VynylImport") {
            moveTo(12f, 14.4f)
            lineTo(12f, 3.6f)
            moveTo(7.8f, 10.2f)
            lineTo(12f, 14.4f)
            moveTo(16.2f, 10.2f)
            lineTo(12f, 14.4f)
            moveTo(4.4f, 15.6f)
            lineTo(4.4f, 20.4f)
            lineTo(19.6f, 20.4f)
            lineTo(19.6f, 15.6f)
        }
    }

    val Search: ImageVector by lazy {
        icon("VynylSearch") {
            circle(10.4f, 10.4f, 6.2f)
            moveTo(15f, 15f)
            lineTo(20.4f, 20.4f)
        }
    }

    // ------------------------------------------------------------------ builder helpers

    private fun icon(name: String, block: IconBuilder.() -> Unit): ImageVector {
        val builder = IconBuilder()
        block(builder)
        return builder.build(name, filled = false)
    }

    private fun filledIcon(name: String, block: IconBuilder.() -> Unit): ImageVector {
        val builder = IconBuilder()
        block(builder)
        return builder.build(name, filled = true)
    }

    /**
     * The tiny path language the glyphs above are written in.
     *
     * Compose's `PathBuilder` is already close to this; the wrapper exists so a glyph reads as a drawing
     * instruction rather than as eleven nested builder calls, and so every glyph gets the same stroke width,
     * cap and colour without repeating them.
     */
    private class IconBuilder {
        private val commands = mutableListOf<Command>()

        fun moveTo(x: Float, y: Float) = commands.add(Command.Move(x, y))
        fun lineTo(x: Float, y: Float) = commands.add(Command.Line(x, y))
        fun close() = commands.add(Command.Close)

        fun circle(centreX: Float, centreY: Float, radius: Float) =
            commands.add(Command.Circle(centreX, centreY, radius))

        fun rect(x: Float, y: Float, width: Float, height: Float) =
            commands.add(Command.Rect(x, y, width, height))

        fun build(name: String, filled: Boolean): ImageVector =
            ImageVector.Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = VIEWPORT,
                viewportHeight = VIEWPORT,
            ).apply {
                path(
                    fill = if (filled) SolidColor(Color.White) else null,
                    stroke = if (filled) null else SolidColor(Color.White),
                    strokeLineWidth = if (filled) 0f else STROKE,
                    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                ) {
                    for (command in commands) {
                        when (command) {
                            is Command.Move -> moveTo(command.x, command.y)
                            is Command.Line -> lineTo(command.x, command.y)
                            Command.Close -> close()
                            is Command.Circle -> {
                                // Four thirds of a semicircle: the classic circle approximation, which keeps
                                // every glyph circular without needing arcs in this little language.
                                val k = command.radius * 0.5523f
                                moveTo(command.cx - command.radius, command.cy)
                                curveTo(
                                    command.cx - command.radius,
                                    command.cy - k,
                                    command.cx - k,
                                    command.cy - command.radius,
                                    command.cx,
                                    command.cy - command.radius,
                                )
                                curveTo(
                                    command.cx + k,
                                    command.cy - command.radius,
                                    command.cx + command.radius,
                                    command.cy - k,
                                    command.cx + command.radius,
                                    command.cy,
                                )
                                curveTo(
                                    command.cx + command.radius,
                                    command.cy + k,
                                    command.cx + k,
                                    command.cy + command.radius,
                                    command.cx,
                                    command.cy + command.radius,
                                )
                                curveTo(
                                    command.cx - k,
                                    command.cy + command.radius,
                                    command.cx - command.radius,
                                    command.cy + k,
                                    command.cx - command.radius,
                                    command.cy,
                                )
                                close()
                            }

                            is Command.Rect -> {
                                moveTo(command.x, command.y)
                                lineTo(command.x + command.width, command.y)
                                lineTo(command.x + command.width, command.y + command.height)
                                lineTo(command.x, command.y + command.height)
                                close()
                            }
                        }
                    }
                }
            }.build()

        private sealed interface Command {
            data class Move(val x: Float, val y: Float) : Command
            data class Line(val x: Float, val y: Float) : Command
            data class Circle(val cx: Float, val cy: Float, val radius: Float) : Command
            data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) : Command
            data object Close : Command
        }
    }
}
