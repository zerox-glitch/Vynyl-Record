package com.vynylrecord.turntable.graphics.geometry

import com.vynylrecord.turntable.graphics.geometry.MeshFactory.GeometryDetail
import com.vynylrecord.turntable.model.Mat4
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.TonearmGeometry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Assembles the reference turntable out of [MeshFactory] primitives.
 *
 * The result is a coherent physical object rather than a pile of shapes: the platter sits in a
 * pocket of the 460 x 360 mm plinth, the mat sits on the platter, the 12 inch record sits on the
 * mat with its spindle hole over the spindle, and the 9 inch tonearm's stylus lands exactly on
 * the record surface at the azimuths derived by [TonearmGeometry].
 *
 * Parts are split by *material*, not by function, because each [com.vynylrecord.turntable.graphics.gl.GpuMesh]
 * is bound to exactly one material. The platter group (body, rim, strobe ring, mat, record,
 * label) rotates together; the tonearm group (arm, needle) pivots about [TurntableSpec.TONEARM_PIVOT_Y].
 */
object TurntableBuilder {

    /** Tonearm local-space landmarks (origin at the pivot, arm extending along +X). */
    private object Arm {
        const val TUBE_START_X = -0.075f
        const val TUBE_START_Y = 0.0028f
        const val TUBE_END_X = 0.150f
        const val TUBE_END_Y = -0.0022f

        /** Headshell attach point, on the tube axis at [TUBE_END_X]. */
        const val HEAD_SHELL_START_T = -0.012f
        const val HEAD_SHELL_END_T = 0.048f

        /** Stylus tip: [TonearmGeometry.EFFECTIVE_LENGTH] out, landing on the record surface. */
        const val STYLUS_TIP_Y: Float = TurntableSpec.RECORD_TOP - TurntableSpec.TONEARM_PIVOT_Y

        // Kept compact: the tail swings outboard as the arm tracks inwards, and at the run-out
        // groove a longer overhang put the counterweight past the right edge of the plinth.
        const val COUNTERWEIGHT_START_X = -0.048f
        const val COUNTERWEIGHT_END_X = -0.070f
        const val COUNTERWEIGHT_RADIUS = 0.019f

        /** Unit vector of the headshell axis (tube end towards the stylus). */
        fun headshellDirectionX(): Float {
            val dx = TonearmGeometry.EFFECTIVE_LENGTH - TUBE_END_X
            val dy = STYLUS_TIP_Y - TUBE_END_Y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            return dx / length
        }

        fun headshellDirectionY(): Float {
            val dx = TonearmGeometry.EFFECTIVE_LENGTH - TUBE_END_X
            val dy = STYLUS_TIP_Y - TUBE_END_Y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            return dy / length
        }
    }

    /** Complete set of CPU meshes for one quality level. */
    class TurntableMeshes(
        val plinthBody: Mesh,
        val plinthTrim: Mesh,
        val foot: Mesh,
        val platterBody: Mesh,
        val platterRim: Mesh,
        val platterStrobe: Mesh,
        val mat: Mesh,
        val recordBody: Mesh,
        val recordTop: Mesh,
        val label: Mesh,
        val spindle: Mesh,
        val pivotBase: Mesh,
        val pivotBrass: Mesh,
        val tonearm: Mesh,
        val needle: Mesh,
        val armRest: Mesh,
        val powerButton: Mesh,
        val speedSelector: Mesh,
        val volumeKnob: Mesh,
        val indicatorBezel: Mesh,
        val indicatorLamp: Mesh,
        val ground: Mesh,
    ) {
        fun all(): List<Mesh> = listOf(
            plinthBody, plinthTrim, foot, platterBody, platterRim, platterStrobe, mat,
            recordBody, recordTop, label, spindle, pivotBase, pivotBrass, tonearm, needle,
            armRest, powerButton, speedSelector, volumeKnob, indicatorBezel, indicatorLamp, ground,
        )

        val totalTriangles: Int get() = all().sumOf { it.triangleCount }
        val totalVertices: Int get() = all().sumOf { it.vertexCount }
    }

    @Suppress("LongMethod")
    fun build(detail: GeometryDetail): TurntableMeshes {
        val large = detail.largeSegments
        val medium = detail.mediumSegments
        val small = detail.smallSegments

        // ---------------------------------------------------------------- plinth
        val plinth = MeshBuilder("plinth", 4096)
        val body = MeshFactory.roundedPrism(
            name = "plinthBody",
            width = TurntableSpec.PLINTH_WIDTH,
            depth = TurntableSpec.PLINTH_DEPTH,
            height = TurntableSpec.PLINTH_HEIGHT,
            cornerRadius = TurntableSpec.PLINTH_CORNER_RADIUS,
            cornerSegments = detail.cornerSegments,
            topBevel = TurntableSpec.PLINTH_TOP_BEVEL,
            bottomBevel = 0.005f,
            edgeSegments = 5,
        )
        plinth.append(body, MeshFactory.translationMatrix(0f, TurntableSpec.PLINTH_BOTTOM, 0f))

        // Recessed top panel: drops the deck area around the platter and the controls a
        // fraction of a millimetre, which reads as a machined plate under the key light.
        val panelInset = 0.016f
        val panel = MeshFactory.roundedPrism(
            name = "deckPanel",
            width = TurntableSpec.PLINTH_WIDTH - panelInset * 2f,
            depth = TurntableSpec.PLINTH_DEPTH - panelInset * 2f,
            height = TurntableSpec.PLINTH_INSET_HEIGHT,
            cornerRadius = TurntableSpec.PLINTH_CORNER_RADIUS,
            cornerSegments = detail.cornerSegments,
            topBevel = 0.0004f,
            bottomBevel = 0f,
            edgeSegments = 3,
        )
        plinth.append(
            panel,
            MeshFactory.translationMatrix(
                0f,
                TurntableSpec.PLINTH_TOP - TurntableSpec.PLINTH_INSET_HEIGHT * 0.5f,
                0f,
            ),
        )
        val plinthBody = plinth.build()

        // Brushed brass trim band around the plinth shoulder.
        val trimHeight = 0.0035f
        val plinthTrim = MeshFactory.roundedPrism(
            name = "plinthTrim",
            width = TurntableSpec.PLINTH_WIDTH + 0.0016f,
            depth = TurntableSpec.PLINTH_DEPTH + 0.0016f,
            height = trimHeight,
            cornerRadius = TurntableSpec.PLINTH_CORNER_RADIUS + 0.0008f,
            cornerSegments = detail.cornerSegments,
            topBevel = 0.0006f,
            bottomBevel = 0.0006f,
            edgeSegments = 3,
        ).let { band ->
            val builder = MeshBuilder("plinthTrim", 1024)
            builder.append(
                band,
                MeshFactory.translationMatrix(
                    0f,
                    TurntableSpec.PLINTH_TOP - trimHeight - 0.004f,
                    0f,
                ),
            )
            builder.build()
        }

        // ---------------------------------------------------------------- feet
        val footProfile = floatArrayOf(
            0.0165f, 0f,
            0.0190f, 0.0022f,
            0.0190f, 0.0060f,
            0.0125f, 0.0195f,
            0.0125f, TurntableSpec.FOOT_HEIGHT,
            0f, TurntableSpec.FOOT_HEIGHT,
        )
        val footTemplate = MeshFactory.revolve("foot", footProfile, small, capStart = true)
        val foot = MeshBuilder("foot", footTemplate.vertexCount * 4).apply {
            for (signX in intArrayOf(-1, 1)) {
                for (signZ in intArrayOf(-1, 1)) {
                    append(
                        footTemplate,
                        MeshFactory.translationMatrix(
                            signX * TurntableSpec.FOOT_INSET_X, 0f, signZ * TurntableSpec.FOOT_INSET_Z,
                        ),
                    )
                }
            }
        }.build()

        // ---------------------------------------------------------------- platter
        val platterProfile = floatArrayOf(
            0f, 0f,
            TurntableSpec.PLATTER_RADIUS - 0.004f, 0f,
            TurntableSpec.PLATTER_RADIUS, 0.0035f,
            TurntableSpec.PLATTER_RADIUS, TurntableSpec.PLATTER_HEIGHT - 0.0035f,
            TurntableSpec.PLATTER_RADIUS - 0.004f, TurntableSpec.PLATTER_HEIGHT,
            0f, TurntableSpec.PLATTER_HEIGHT,
        )
        val platterBody = place(
            MeshFactory.revolve("platterBody", platterProfile, large),
            TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLINTH_TOP, TurntableSpec.PLATTER_CENTER_Z,
        )

        // Machined aluminium rim band, slightly proud of the casting.
        val platterRim = place(MeshFactory.annulus(
            name = "platterRim",
            innerRadius = TurntableSpec.PLATTER_RADIUS - 0.0004f,
            outerRadius = TurntableSpec.PLATTER_RADIUS + 0.0012f,
            thickness = TurntableSpec.PLATTER_RIM_HEIGHT,
            segments = large,
            edgeBevel = 0.0006f,
        ), TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLINTH_TOP, TurntableSpec.PLATTER_CENTER_Z)

        // Strobe ring: 60 pressed marks that make the platter's rotation unmistakable.
        val strobeBuilder = MeshBuilder("platterStrobe", 2048)
        val strobeMark = MeshFactory.cylinder("strobeMark", radius = 0.0015f, height = 0.0009f, segments = 8)
        val strobeRadius = (TurntableSpec.MAT_RADIUS + TurntableSpec.PLATTER_RADIUS) * 0.5f
        val strobeMatrix = FloatArray(16)
        val strobeRotation = FloatArray(16)
        val strobeOffset = FloatArray(16)
        for (i in 0 until STROBE_MARK_COUNT) {
            val angle = (2.0 * PI * i / STROBE_MARK_COUNT).toFloat()
            Mat4.setTranslation(cos(angle) * strobeRadius, TurntableSpec.PLATTER_HEIGHT - 0.0004f, sin(angle) * strobeRadius, strobeOffset, 0)
            Mat4.setRotationY(0f, strobeRotation, 0)
            Mat4.multiply(strobeOffset, 0, strobeRotation, 0, strobeMatrix, 0)
            strobeBuilder.append(strobeMark, strobeMatrix)
        }
        val platterStrobe = place(
            strobeBuilder.build(),
            TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLINTH_TOP, TurntableSpec.PLATTER_CENTER_Z,
        )

        // ---------------------------------------------------------------- mat
        val mat = place(MeshFactory.disc(
            name = "mat",
            radius = TurntableSpec.MAT_RADIUS,
            thickness = TurntableSpec.MAT_THICKNESS,
            segments = large,
            edgeBevelWidth = 0.0022f,
            edgeBevelHeight = 0.0012f,
        ), TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLATTER_TOP, TurntableSpec.PLATTER_CENTER_Z)

        // ---------------------------------------------------------------- record
        // Sides, bottom and edge band use the lathe UV; the playing surface is a separate
        // annulus whose V coordinate is the normalised radius (see radialAnnulusTop).
        val holeRadius = TurntableSpec.SPINDLE_RADIUS + 0.0002f
        val bevelWidth = TurntableSpec.RECORD_EDGE_BEVEL_WIDTH
        val bevelHeight = TurntableSpec.RECORD_EDGE_BEVEL_HEIGHT
        val recordProfile = floatArrayOf(
            holeRadius, TurntableSpec.RECORD_THICKNESS,
            holeRadius, 0f,
            TurntableSpec.RECORD_RADIUS - bevelWidth, 0f,
            TurntableSpec.RECORD_RADIUS, bevelHeight,
            TurntableSpec.RECORD_RADIUS, TurntableSpec.RECORD_THICKNESS - bevelHeight,
            TurntableSpec.RECORD_RADIUS - bevelWidth, TurntableSpec.RECORD_THICKNESS,
        )
        val recordBody = place(
            MeshFactory.revolve("recordBody", recordProfile, large),
            TurntableSpec.PLATTER_CENTER_X, TurntableSpec.RECORD_BOTTOM, TurntableSpec.PLATTER_CENTER_Z,
        )
        val recordTop = place(MeshFactory.radialAnnulusTop(
            name = "recordTop",
            innerRadius = holeRadius,
            outerRadius = TurntableSpec.RECORD_RADIUS - bevelWidth,
            y = TurntableSpec.RECORD_THICKNESS,
            segments = large,
            // More rings means the normalised V coordinate tracks the physical radius more exactly,
            // which the groove shader depends on to place its pitch.
            rings = if (detail.grooves) 8 else 4,
        ), TurntableSpec.PLATTER_CENTER_X, TurntableSpec.RECORD_BOTTOM, TurntableSpec.PLATTER_CENTER_Z)

        val label = place(MeshFactory.planarAnnulus(
            name = "label",
            innerRadius = TurntableSpec.LABEL_HOLE_RADIUS,
            outerRadius = TurntableSpec.LABEL_RADIUS,
            thickness = TurntableSpec.LABEL_THICKNESS,
            segments = large,
            uvExtent = TurntableSpec.LABEL_RADIUS,
        ), TurntableSpec.PLATTER_CENTER_X, TurntableSpec.RECORD_TOP, TurntableSpec.PLATTER_CENTER_Z)

        // ---------------------------------------------------------------- spindle
        val spindleHeight = TurntableSpec.SPINDLE_TOP - TurntableSpec.PLINTH_TOP
        val spindleProfile = floatArrayOf(
            0f, 0f,
            TurntableSpec.SPINDLE_COLLAR_RADIUS, 0f,
            TurntableSpec.SPINDLE_COLLAR_RADIUS, TurntableSpec.SPINDLE_COLLAR_HEIGHT,
            TurntableSpec.SPINDLE_RADIUS + 0.0004f, TurntableSpec.SPINDLE_COLLAR_HEIGHT + 0.0012f,
            TurntableSpec.SPINDLE_RADIUS, spindleHeight - 0.0022f,
            TurntableSpec.SPINDLE_RADIUS - 0.0004f, spindleHeight - 0.0004f,
            0f, spindleHeight,
        )
        val spindle = place(
            MeshFactory.revolve("spindle", spindleProfile, medium, capStart = true),
            TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLINTH_TOP, TurntableSpec.PLATTER_CENTER_Z,
        )

        // ---------------------------------------------------------------- tonearm base
        // Built in pivot-local space (origin at the deck surface under the pivot) and placed once.
        val pivotBase = MeshBuilder("pivotBaseBody", 1024).apply {
            val collar = MeshFactory.cylinder("collar", 0.028f, 0.009f, medium, topChamfer = 0.0022f)
            append(collar, null)
            val postHeight = TurntableSpec.TONEARM_PIVOT_Y - TurntableSpec.PLINTH_TOP - 0.010f
            val post = MeshFactory.cylinder("post", 0.0165f, postHeight, medium, topChamfer = 0.0016f)
            append(post, MeshFactory.translationMatrix(0f, 0.006f, 0f))
        }.let {
            place(it.build(), TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.PLINTH_TOP, TurntableSpec.TONEARM_PIVOT_Z)
        }

        // Brass details are built in pivot-local space (origin at the deck top under the pivot)
        // and placed once, so the renderer only ever applies the arm's own rotation.
        val pivotBrassBuilder = MeshBuilder("pivotBrass", 1200)
        val brassBand: Mesh = MeshFactory.torus("brassBand", 0.0172f, 0.0026f, medium, 12)
        pivotBrassBuilder.append(
            brassBand,
            MeshFactory.translationMatrix(0f, 0.016f, 0f),
        )
        val gimbal = MeshFactory.torus("gimbal", 0.0125f, 0.0032f, medium, 10)
        val gimbalMatrix = FloatArray(16)
        Mat4.setRotationZ(90f, gimbalMatrix, 0)
        Mat4.translate(
            gimbalMatrix, 0,
            0f, TurntableSpec.TONEARM_PIVOT_Y - TurntableSpec.PLINTH_TOP, 0f,
            gimbalMatrix, 0,
        )
        pivotBrassBuilder.append(gimbal, gimbalMatrix)
        val pivotBrass = place(
            pivotBrassBuilder.build(),
            TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.PLINTH_TOP, TurntableSpec.TONEARM_PIVOT_Z,
        )

        // ---------------------------------------------------------------- tonearm
        val tonearmBuilder = MeshBuilder("tonearm", 2048)
        val tube = MeshFactory.tubeBetween(
            name = "armTube",
            startX = Arm.TUBE_START_X, startY = Arm.TUBE_START_Y, startZ = 0f,
            endX = Arm.TUBE_END_X, endY = Arm.TUBE_END_Y, endZ = 0f,
            radius = 0.0052f,
            endRadius = 0.0046f,
            segments = small,
            capStart = true,
            capEnd = false,
        )
        tonearmBuilder.append(tube, null)
        val counterweightShaft = MeshFactory.tubeBetween(
            name = "counterweightShaft",
            startX = -0.026f, startY = 0.0016f, startZ = 0f,
            endX = -0.074f, endY = 0.0032f, endZ = 0f,
            radius = 0.0034f,
            segments = small,
            capEnd = true,
        )
        tonearmBuilder.append(counterweightShaft, null)
        val counterweight = MeshFactory.tubeBetween(
            name = "counterweight",
            startX = Arm.COUNTERWEIGHT_START_X, startY = 0.0022f, startZ = 0f,
            endX = Arm.COUNTERWEIGHT_END_X, endY = 0.0031f, endZ = 0f,
            radius = Arm.COUNTERWEIGHT_RADIUS,
            segments = medium,
        )
        tonearmBuilder.append(counterweight, null)

        // Headshell: a slightly tapered beam along the headshell axis, rotated about the
        // vertical by the 23 degree offset angle around the stylus (so the stylus stays put).
        val headStartX = Arm.TUBE_END_X + Arm.headshellDirectionX() * Arm.HEAD_SHELL_START_T
        val headStartY = Arm.TUBE_END_Y + Arm.headshellDirectionY() * Arm.HEAD_SHELL_START_T
        val headEndX = Arm.TUBE_END_X + Arm.headshellDirectionX() * Arm.HEAD_SHELL_END_T
        val headEndY = Arm.TUBE_END_Y + Arm.headshellDirectionY() * Arm.HEAD_SHELL_END_T
        val headshell = MeshFactory.beam(
            name = "headshell",
            startX = headStartX, startY = headStartY, startZ = 0f,
            endX = headEndX, endY = headEndY, endZ = 0f,
            width = 0.023f,
            height = 0.0105f,
            endWidth = 0.019f,
            endHeight = 0.0085f,
        )
        val headshellMatrix = buildShellMatrix(
            pivotX = TonearmGeometry.EFFECTIVE_LENGTH, pivotY = Arm.STYLUS_TIP_Y,
            offsetDegrees = -TurntableSpec.TONEARM_HEADSHELL_OFFSET_DEG,
        )
        tonearmBuilder.append(headshell, headshellMatrix)

        // Headshell ridge: a thin brass detail strip along the top of the shell.
        val shellRidge = MeshFactory.beam(
            name = "shellRidge",
            startX = headStartX + Arm.headshellDirectionX() * 0.004f,
            startY = headStartY + Arm.headshellDirectionY() * 0.004f + 0.0052f,
            startZ = 0f,
            endX = headEndX - Arm.headshellDirectionX() * 0.006f,
            endY = headEndY - Arm.headshellDirectionY() * 0.006f + 0.0042f,
            endZ = 0f,
            width = 0.006f,
            height = 0.0016f,
        )
        tonearmBuilder.append(shellRidge, headshellMatrix)

        // Yoke arms connecting the tube to the gimbal ring.
        val yoke = MeshFactory.beam(
            name = "yoke",
            startX = -0.006f, startY = 0.0022f, startZ = -0.0105f,
            endX = -0.006f, endY = 0.0022f, endZ = 0.0105f,
            width = 0.010f,
            height = 0.008f,
        )
        tonearmBuilder.append(yoke, null)
        // Baked into world space at the bearing, like `pivotBrass` above. The renderer applies one
        // matrix per node, so every mesh on the arm node has to live in the same space -- leaving
        // these pivot-local put the arm a fifth of a metre off its bearing.
        val tonearm = place(
            tonearmBuilder.build(),
            TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.TONEARM_PIVOT_Y, TurntableSpec.TONEARM_PIVOT_Z,
        )

        // ---------------------------------------------------------------- stylus / needle
        // Kept as its own mesh so it can carry the touch-down micro movement independently.
        val needleBuilder = MeshBuilder("needle", 256)
        val cantilever = MeshFactory.tubeBetween(
            name = "cantilever",
            startX = headEndX - Arm.headshellDirectionX() * 0.004f,
            startY = headEndY - Arm.headshellDirectionY() * 0.004f - 0.0038f,
            startZ = 0f,
            endX = TonearmGeometry.EFFECTIVE_LENGTH - 0.0022f,
            endY = Arm.STYLUS_TIP_Y + 0.0009f,
            endZ = 0f,
            radius = 0.0011f,
            endRadius = 0.0007f,
            segments = 10,
        )
        needleBuilder.append(cantilever, headshellMatrix)
        val stylusTip = MeshFactory.cylinder("stylusTip", 0.00055f, 0.0012f, 8, topRadius = 0.00035f)
        val tipMatrix = FloatArray(16)
        Mat4.setTranslation(
            TonearmGeometry.EFFECTIVE_LENGTH, Arm.STYLUS_TIP_Y + 0.0006f, 0f,
            tipMatrix, 0,
        )
        needleBuilder.append(stylusTip, tipMatrix)
        val needle = place(
            needleBuilder.build(),
            TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.TONEARM_PIVOT_Y, TurntableSpec.TONEARM_PIVOT_Z,
        )

        // ---------------------------------------------------------------- arm rest
        val restDirectionX = cos(Math.toRadians(TurntableSpec.TONEARM_REST_ANGLE_DEG.toDouble())).toFloat()
        val restDirectionZ = sin(Math.toRadians(TurntableSpec.TONEARM_REST_ANGLE_DEG.toDouble())).toFloat()
        val cradleX = TurntableSpec.TONEARM_PIVOT_X + restDirectionX * (TonearmGeometry.EFFECTIVE_LENGTH - 0.021f)
        val cradleZ = TurntableSpec.TONEARM_PIVOT_Z + restDirectionZ * (TonearmGeometry.EFFECTIVE_LENGTH - 0.021f)
        val armRestBuilder = MeshBuilder("armRest", 512)
        val restPost = MeshFactory.cylinder(
            "restPost",
            0.0095f,
            TurntableSpec.TONEARM_PIVOT_Y - TurntableSpec.PLINTH_TOP - 0.008f,
            medium,
            topChamfer = 0.0035f,
        )
        armRestBuilder.append(restPost, MeshFactory.translationMatrix(cradleX, TurntableSpec.PLINTH_TOP, cradleZ))
        val restCradle = MeshFactory.tubeBetween(
            name = "restCradle",
            startX = cradleX - restDirectionX * 0.009f,
            startY = TurntableSpec.TONEARM_PIVOT_Y - 0.0095f,
            startZ = cradleZ - restDirectionZ * 0.009f,
            endX = cradleX + restDirectionX * 0.009f,
            endY = TurntableSpec.TONEARM_PIVOT_Y - 0.0095f,
            endZ = cradleZ + restDirectionZ * 0.009f,
            radius = 0.0042f,
            segments = small,
        )
        armRestBuilder.append(restCradle, null)
        val armRest = armRestBuilder.build()

        // ---------------------------------------------------------------- controls
        val powerButton = place(MeshFactory.revolve(
            "powerButton",
            floatArrayOf(
                0f, 0f,
                TurntableSpec.POWER_BUTTON_RADIUS, 0f,
                TurntableSpec.POWER_BUTTON_RADIUS, 0.0021f,
                TurntableSpec.POWER_BUTTON_RADIUS - 0.0016f, 0.0034f,
                TurntableSpec.POWER_BUTTON_RADIUS - 0.0055f, 0.0042f,
                0f, 0.0044f,
            ),
            medium,
            capStart = true,
        ), TurntableSpec.POWER_BUTTON_X, TurntableSpec.PLINTH_TOP, TurntableSpec.POWER_BUTTON_Z)

        val speedSelectorBuilder = MeshBuilder("speedSelector", 512)
        val selectorBody = MeshFactory.revolve(
            "selectorBody",
            floatArrayOf(
                0f, 0f,
                TurntableSpec.SPEED_SELECTOR_RADIUS, 0f,
                TurntableSpec.SPEED_SELECTOR_RADIUS, 0.0055f,
                TurntableSpec.SPEED_SELECTOR_RADIUS - 0.0022f, 0.0072f,
                0f, 0.0076f,
            ),
            small,
            capStart = true,
        )
        speedSelectorBuilder.append(selectorBody, null)
        val selectorPointer = MeshFactory.beam(
            name = "selectorPointer",
            startX = 0f, startY = 0.0070f, startZ = 0.0015f,
            endX = 0f, endY = 0.0074f, endZ = TurntableSpec.SPEED_SELECTOR_RADIUS - 0.0008f,
            width = 0.0042f,
            height = 0.0022f,
        )
        speedSelectorBuilder.append(selectorPointer, null)
        val speedSelector = place(
            speedSelectorBuilder.build(),
            TurntableSpec.SPEED_SELECTOR_X, TurntableSpec.PLINTH_TOP, TurntableSpec.SPEED_SELECTOR_Z,
        )

        val volumeKnob = place(MeshFactory.revolve(
            "volumeKnob",
            floatArrayOf(
                0f, 0f,
                TurntableSpec.KNOB_RADIUS, 0f,
                TurntableSpec.KNOB_RADIUS, 0.0082f,
                TurntableSpec.KNOB_RADIUS - 0.0026f, 0.0112f,
                TurntableSpec.KNOB_RADIUS - 0.0068f, 0.0122f,
                0f, 0.0124f,
            ),
            medium,
            capStart = true,
            flutes = 18,
            fluteDepth = 0.035f,
        ), TurntableSpec.KNOB_X, TurntableSpec.PLINTH_TOP, TurntableSpec.KNOB_Z)

        val indicatorBezel = place(MeshFactory.annulus(
            name = "indicatorBezel",
            innerRadius = TurntableSpec.INDICATOR_RADIUS,
            outerRadius = TurntableSpec.INDICATOR_RADIUS + 0.0026f,
            thickness = 0.0012f,
            segments = medium,
            edgeBevel = 0.0004f,
        ), TurntableSpec.INDICATOR_X, TurntableSpec.PLINTH_TOP, TurntableSpec.INDICATOR_Z)

        val indicatorLamp = place(MeshFactory.revolve(
            "indicatorLamp",
            floatArrayOf(
                0f, 0f,
                TurntableSpec.INDICATOR_RADIUS + 0.0008f, 0f,
                TurntableSpec.INDICATOR_RADIUS + 0.0008f, 0.0008f,
                TurntableSpec.INDICATOR_RADIUS, 0.0019f,
                TurntableSpec.INDICATOR_RADIUS * 0.55f, 0.0024f,
                0f, 0.0026f,
            ),
            small,
            capStart = true,
        ), TurntableSpec.INDICATOR_X, TurntableSpec.PLINTH_TOP, TurntableSpec.INDICATOR_Z)

        val ground = MeshFactory.groundPlane("ground", size = 26f, segments = 8)

        return TurntableMeshes(
            plinthBody = plinthBody,
            plinthTrim = plinthTrim,
            foot = foot,
            platterBody = platterBody,
            platterRim = platterRim,
            platterStrobe = platterStrobe,
            mat = mat,
            recordBody = recordBody,
            recordTop = recordTop,
            label = label,
            spindle = spindle,
            pivotBase = pivotBase,
            pivotBrass = pivotBrass,
            tonearm = tonearm,
            needle = needle,
            armRest = armRest,
            powerButton = powerButton,
            speedSelector = speedSelector,
            volumeKnob = volumeKnob,
            indicatorBezel = indicatorBezel,
            indicatorLamp = indicatorLamp,
            ground = ground,
        )
    }

    /**
     * Bakes a translation into a mesh so the scene graph only ever applies animated rotations.
     * Used for everything that sits at a fixed spot on the deck (controls, spindle, platter).
     */
    private fun place(mesh: Mesh, x: Float, y: Float, z: Float): Mesh {
        if (x == 0f && y == 0f && z == 0f) return mesh
        val builder = MeshBuilder(mesh.name, mesh.vertexCount)
        builder.append(mesh, MeshFactory.translationMatrix(x, y, z))
        return builder.build()
    }

    /**
     * Matrix that rotates an arm-local part about the vertical axis through the stylus tip.
     *
     * Applying the vertical-tracking offset angle around the tip (rather than the shell's own
     * centre) keeps the stylus exactly on the effective-length circle, which is what the
     * touch-down maths in [TonearmGeometry] relies on.
     */
    private fun buildShellMatrix(pivotX: Float, pivotY: Float, offsetDegrees: Float): FloatArray {
        val toTip = FloatArray(16)
        Mat4.setTranslation(-pivotX, -pivotY, 0f, toTip, 0)
        val rotation = FloatArray(16)
        Mat4.setRotationY(offsetDegrees, rotation, 0)
        val back = FloatArray(16)
        Mat4.setTranslation(pivotX, pivotY, 0f, back, 0)
        val combined = FloatArray(16)
        Mat4.multiply(toTip, 0, rotation, 0, combined, 0)
        Mat4.multiply(combined, 0, back, 0, combined, 0)
        return combined
    }

    /** Number of pressed marks on the platter's strobe ring. */
    const val STROBE_MARK_COUNT = 60
}
