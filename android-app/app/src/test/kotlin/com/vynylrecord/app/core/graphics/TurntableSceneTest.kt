package com.vynylrecord.app.core.graphics

import com.vynylrecord.app.core.model.VinylStyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The deck's mechanics.
 *
 * The animator turns "the player is playing" into a platter speed, an arm position, a needle contact and a
 * lamp level. Three properties are worth pinning: the needle is down at exactly the moment the audio starts,
 * the animation runs the same at any frame rate, and the mechanical sequence always resolves to a real state
 * rather than getting stuck half-cued.
 */
class TurntableSceneTest {

    private val step = 1f / 60f
    private val twoPi = (2.0 * Math.PI).toFloat()

    private fun run(animator: TurntableAnimator, seconds: Float) {
        var elapsed = 0f
        while (elapsed < seconds) {
            animator.update(step)
            elapsed += step
        }
    }

    /** How far the platter turned over [seconds], with the 2π wrap removed. */
    private fun turned(animator: TurntableAnimator, seconds: Float): Float {
        var travelled = 0f
        var previous = animator.currentPose.platterRotation
        var elapsed = 0f
        while (elapsed < seconds) {
            animator.update(step)
            val current = animator.currentPose.platterRotation
            var delta = current - previous
            if (delta < -1f) delta += twoPi
            travelled += delta
            previous = current
            elapsed += step
        }
        return travelled
    }

    @Test
    fun `the states cover the whole journey`() {
        assertEquals(
            listOf(
                "IDLE", "LOADING", "READY", "CUEING", "PLAYING", "PAUSED", "SEEKING",
                "ENDED", "LIFTING", "RETURNING", "RENDERING", "COMPLETE", "ERROR", "POWERED_DOWN",
            ),
            DeckState.entries.map { it.name },
        )
        DeckState.entries.forEach { state -> assertTrue(state.label.isNotBlank()) }
        assertTrue(DeckState.PLAYING.isPlaying)
        assertTrue(DeckState.PLAYING.isPowered)
        assertTrue(DeckState.LOADING.isPowered)
        assertFalse(DeckState.IDLE.isPlaying)
        assertFalse(DeckState.POWERED_DOWN.isPowered)
        assertEquals(DeckState.entries.toList(), DeckState.ordered)
    }

    @Test
    fun `transport maps the player's state onto the deck's`() {
        assertEquals(DeckState.PLAYING, DeckState.forTransport(isPlaying = true, isBuffering = false, hasRecord = true, hasError = false))
        assertEquals(DeckState.READY, DeckState.forTransport(isPlaying = false, isBuffering = false, hasRecord = true, hasError = false))
        assertEquals(DeckState.READY, DeckState.forTransport(isPlaying = false, isBuffering = true, hasRecord = true, hasError = false))
        assertEquals(DeckState.IDLE, DeckState.forTransport(isPlaying = false, isBuffering = false, hasRecord = false, hasError = false))
        // An error wins over everything else: the deck must not look like it is playing.
        assertEquals(DeckState.ERROR, DeckState.forTransport(isPlaying = true, isBuffering = false, hasRecord = true, hasError = true))
        assertEquals(DeckState.ERROR, DeckState.forTransport(isPlaying = false, isBuffering = false, hasRecord = false, hasError = true))
    }

    @Test
    fun `a fresh deck is stopped and the needle is up`() {
        val animator = TurntableAnimator()
        val pose = animator.update(step)
        assertEquals(DeckState.IDLE, animator.state)
        assertTrue("the deck was spinning before it was asked to", abs(pose.platterRotation) < 0.05f)
        assertFalse(animator.needleIsDown)
        assertEquals(0f, pose.needleContact, 0f)
        assertEquals(0f, pose.pressProgress, 0f)

        // Idle already has the arm on its rest, because that is where the pose starts.
        run(animator, 1f)
        assertTrue("the arm left its rest while idle", animator.currentPose.armLift > 0.5f)
    }

    @Test
    fun `the platter turns at 33 and a third rpm`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING)
        run(animator, 2f)
        val perSecond = turned(animator, 1f)
        val rpm = perSecond / twoPi * 60f
        assertEquals("the deck turned at $rpm rpm", 33.333f, rpm, 0.5f)
    }

    @Test
    fun `the platter ramps up rather than jumping to speed`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING)
        animator.update(step)
        val firstFrame = turned(animator, step)
        val fullSpeedFrame = TurntableAnimator.SPINNING_VELOCITY * step
        assertTrue("the platter reached full speed in one frame", firstFrame < fullSpeedFrame * 0.5f)
        run(animator, 1f)
        assertTrue("the platter never sped up", turned(animator, step) > firstFrame * 3f)
    }

    @Test
    fun `seeking turns the platter faster than playing`() {
        val playing = TurntableAnimator()
        val seeking = TurntableAnimator()
        playing.setState(DeckState.PLAYING, immediate = true)
        seeking.setState(DeckState.SEEKING, immediate = true)
        // Both motors take the same ramp from a standstill, so the difference is only visible once the
        // slower one has reached its target speed.
        run(playing, 1.2f)
        run(seeking, 1.2f)
        assertTrue(
            "seeking did not spin faster: ${turned(seeking, 0.5f)} against ${turned(playing, 0.5f)}",
            turned(seeking, 0.5f) > turned(playing, 0.5f),
        )
    }

    @Test
    fun `the needle lands at the end of the cue, not before`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.CUEING)
        assertFalse("the needle was down before the cue began", animator.needleIsDown)

        // Just before the cue window ends the needle must still be on its way down.
        run(animator, TurntableAnimator.CUE_SECONDS * 0.8f)
        assertFalse("the needle landed early", animator.needleIsDown)
        assertTrue("the needle never moved", animator.currentPose.needleContact > 0.5f)

        run(animator, TurntableAnimator.CUE_SECONDS * 0.3f)
        assertTrue("the needle never landed", animator.needleIsDown)
    }

    @Test
    fun `playing and pausing both keep the needle in the groove`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.CUEING)
        run(animator, TurntableAnimator.CUE_SECONDS + 0.1f)
        animator.setState(DeckState.PLAYING)
        run(animator, 1f)
        assertTrue(animator.needleIsDown)
        val atSpeed = turned(animator, 0.5f)
        assertTrue(atSpeed > 1.5f)

        animator.setState(DeckState.PAUSED)
        // Pausing must not lift the needle: a lift at every pause would thump, and the listener expects to
        // find the record where they left it.
        assertTrue("pausing lifted the needle", animator.needleIsDown)
        run(animator, 4f)
        assertTrue(animator.needleIsDown)
        assertEquals("the platter kept turning while paused", 0f, turned(animator, 2f), 0.02f)
    }

    @Test
    fun `the arm follows the playhead through the side and is clamped at both ends`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING, immediate = true)
        animator.setPlayhead(0f)
        run(animator, 1f)
        val atTheStart = animator.currentPose.armTravel

        animator.setPlayhead(0.5f)
        run(animator, 1f)
        val inTheMiddle = animator.currentPose.armTravel

        animator.setPlayhead(1f)
        run(animator, 1f)
        val atTheEnd = animator.currentPose.armTravel

        assertTrue("the arm did not advance: $atTheStart, $inTheMiddle, $atTheEnd", atTheStart < inTheMiddle)
        assertTrue("the arm did not reach the run-out", inTheMiddle < atTheEnd)
        assertEquals(TurntableAnimator.ARM_TRAVEL_START, atTheStart, 0.02f)
        assertEquals(TurntableAnimator.ARM_TRAVEL_END, atTheEnd, 0.02f)

        // A playhead outside the side is clamped rather than wrapping.
        animator.setPlayhead(-5f)
        run(animator, 1f)
        assertEquals(TurntableAnimator.ARM_TRAVEL_START, animator.currentPose.armTravel, 0.02f)
        animator.setPlayhead(9f)
        run(animator, 1f)
        assertEquals(TurntableAnimator.ARM_TRAVEL_END, animator.currentPose.armTravel, 0.02f)
        assertEquals(1f, animator.playheadFraction, 0f)
    }

    @Test
    fun `the animation is frame-rate independent`() {
        val sixty = TurntableAnimator()
        val thirty = TurntableAnimator()
        listOf(sixty, thirty).forEach { animator ->
            animator.setState(DeckState.PLAYING, immediate = true)
            animator.setPlayhead(0.4f)
        }
        repeat(30) { sixty.update(1f / 60f) }
        repeat(15) { thirty.update(1f / 30f) }

        val a = sixty.currentPose
        val b = thirty.currentPose
        assertEquals("platter rotation diverged", a.platterRotation, b.platterRotation, 0.02f)
        assertEquals("arm travel diverged", a.armTravel, b.armTravel, 0.02f)
        assertEquals("lamp diverged", a.lampIntensity, b.lampIntensity, 0.05f)
        assertTrue(sixty.needleIsDown && thirty.needleIsDown)
    }

    @Test
    fun `a resumed app does not teleport the platter`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING, immediate = true)
        run(animator, 1f)
        val before = animator.currentPose.platterRotation
        // Coming back from the background hands the animator a huge delta; it must be treated as one step.
        animator.update(30f)
        val jump = abs(animator.currentPose.platterRotation - before)
        val mostItCouldTurn = TurntableAnimator.SPINNING_VELOCITY * TurntableAnimator.MAX_STEP_SECONDS
        assertTrue("a resumed frame turned the platter by $jump rad", jump <= mostItCouldTurn * 1.01f)
        assertTrue("the deck did not move at all on a resumed frame", jump > 0f)
        assertEquals(TurntableAnimator.MAX_STEP_SECONDS, 1f / 20f, 0f)
    }

    @Test
    fun `the platter coasts down instead of stopping dead`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING, immediate = true)
        run(animator, 2f)
        animator.setState(DeckState.IDLE)
        val coasting = turned(animator, 0.3f)
        val atSpeed = TurntableAnimator.SPINNING_VELOCITY * 0.3f
        assertTrue("the platter stopped instantly", coasting > 0.2f)
        assertTrue("the platter did not slow down", coasting < atSpeed)
        // And it does stop: a deck that never stops is a deck that never parks.
        run(animator, 4f)
        assertEquals(0f, turned(animator, 1f), 0.005f)
        assertTrue(animator.currentPose.armLift > 0.9f)
    }

    @Test
    fun `a side running out parks the arm by itself`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING, immediate = true)
        animator.setPlayhead(1f)
        run(animator, 1f)
        animator.setState(DeckState.ENDED)

        // The run-out: the platter coasts, the needle stays down, the arm sits where the side ended.
        run(animator, 0.3f)
        assertEquals(DeckState.ENDED, animator.state)
        assertTrue(animator.needleIsDown)
        run(animator, 6f)

        assertEquals("the deck never parked the arm", DeckState.READY, animator.state)
        assertFalse("the needle stayed down after the side ended", animator.needleIsDown)
        assertTrue("the arm never returned", animator.currentPose.armTravel < 0.05f)
        assertTrue(animator.currentPose.armLift > 0.85f)
    }

    @Test
    fun `reduced motion collapses a transition instead of animating it`() {
        fun settled(): TurntableAnimator {
            val animator = TurntableAnimator()
            animator.setState(DeckState.PLAYING, immediate = true)
            animator.setPlayhead(1f)
            run(animator, 1.5f)
            // The arm has tracked all the way to the run-out.
            assertEquals(TurntableAnimator.ARM_TRAVEL_END, animator.currentPose.armTravel, 0.01f)
            return animator
        }

        val full = settled()
        val reduced = settled().apply { reducedMotion = true }
        full.setState(DeckState.RETURNING)
        reduced.setState(DeckState.RETURNING)
        full.update(step)
        reduced.update(step)
        assertEquals("reduced motion should snap to the target", 0f, reduced.currentPose.armTravel, 1e-4f)
        assertTrue(
            "reduced motion was not ahead: ${reduced.currentPose.armTravel} against ${full.currentPose.armTravel}",
            reduced.currentPose.armTravel < full.currentPose.armTravel - 0.2f,
        )
    }

    @Test
    fun `an immediate change skips the ramp`() {
        val playing = TurntableAnimator()
        playing.setState(DeckState.PLAYING, immediate = true)
        assertTrue("an immediate play should have the needle down", playing.needleIsDown)
        assertEquals("the arm was not down on an immediate play", 0f, playing.currentPose.armLift, 1e-4f)
        assertEquals(TurntableAnimator.SPINNING_VELOCITY, 3.4907f, 0.01f)
        assertEquals("the platter did not start at speed", DeckState.PLAYING, playing.state)

        val idle = TurntableAnimator()
        idle.setState(DeckState.PLAYING, immediate = true)
        idle.setState(DeckState.IDLE, immediate = true)
        assertFalse(idle.needleIsDown)
        assertEquals("the arm did not return to its rest", 1f, idle.currentPose.armLift, 1e-4f)
        assertEquals(0f, idle.currentPose.armTravel, 1e-4f)
        assertEquals(0f, idle.currentPose.discLift, 1e-4f)
    }

    @Test
    fun `loading lowers the record onto the platter`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.LOADING, immediate = true)
        animator.update(step)
        assertEquals(TurntableAnimator.DISC_LIFT_HEIGHT, animator.currentPose.discLift, 2e-3f)
        run(animator, TurntableAnimator.LOAD_SECONDS + 0.2f)
        assertEquals("the record never settled", 0f, animator.currentPose.discLift, 1e-6f)
        // The platter is already turning while the record is placed, as it would on a real deck.
        assertTrue(turned(animator, 0.2f) > 0.05f)
    }

    @Test
    fun `a press drives the deck's own state`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.RENDERING, immediate = true)
        animator.setRenderProgress(0.5f)
        animator.update(step)
        assertEquals(0.5f, animator.currentPose.pressProgress, 1e-6f)
        assertFalse("the needle was down while pressing", animator.needleIsDown)
        assertEquals(0f, animator.currentPose.needleContact, 0f)
        assertTrue("the lamp was out while pressing", animator.currentPose.lampIntensity > TurntableAnimator.LAMP_OFF)

        animator.setRenderProgress(4f)
        animator.update(step)
        assertEquals(1f, animator.renderProgress, 0f)
        assertEquals(1f, animator.currentPose.pressProgress, 1e-6f)

        // The finished press flares the lamp, which is how the user knows it landed without reading anything.
        val pressing = animator.currentPose.lampIntensity
        animator.setState(DeckState.COMPLETE, immediate = true)
        animator.update(step)
        assertTrue(animator.currentPose.lampIntensity >= pressing)
        assertTrue(animator.currentPose.discSheen > 0f)
    }

    @Test
    fun `an error is visible without reading a message`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.ERROR)
        run(animator, 4f)
        assertEquals(DeckState.ERROR, animator.state)
        assertTrue("the error did not warm the lamp", animator.currentPose.lampWarmth > 0.5f)
        assertFalse(animator.needleIsDown)
        // The error lamp is ruby, which is a lower green channel for the same brightness: on the deck the
        // difference is unmistakable, and it is the only status that is not amber.
        assertTrue(DeckLighting.LAMP_ERROR_COLOR[1] < DeckLighting.LAMP_COLOR[1])
        assertTrue(DeckLighting.LAMP_ERROR_COLOR[2] < DeckLighting.LAMP_COLOR[2])
        assertTrue(DeckLighting.LAMP_ERROR_COLOR[0] > DeckLighting.LAMP_ERROR_COLOR[1])
    }

    @Test
    fun `every state produces a finite, plausible pose`() {
        DeckState.entries.forEach { state ->
            val animator = TurntableAnimator()
            animator.setState(state, immediate = true)
            animator.setPlayhead(0.3f)
            animator.setRenderProgress(0.6f)
            repeat(120) { animator.update(step) }
            val pose = animator.currentPose
            listOf(
                pose.platterRotation, pose.discRotation, pose.discLift, pose.armTravel,
                pose.armLift, pose.needleContact, pose.lampIntensity, pose.lampWarmth,
                pose.discSheen, pose.pressProgress,
            ).forEach { value ->
                assertTrue("$state produced $value", value.isFinite())
                assertTrue("$state produced an implausible $value", abs(value) < 1_000f)
            }
            assertTrue("$state produced a needle contact of ${pose.needleContact}", pose.needleContact in 0f..1f)
            assertTrue("$state produced an arm lift of ${pose.armLift}", pose.armLift in 0f..1f)
            assertTrue("$state produced an arm travel of ${pose.armTravel}", pose.armTravel in 0f..1f)
            assertTrue("$state produced a lamp of ${pose.lampIntensity}", pose.lampIntensity >= 0f)
            assertTrue("$state produced a sheen of ${pose.discSheen}", pose.discSheen >= 0f)
            assertTrue("$state produced a lift of ${pose.discLift}", pose.discLift >= 0f)
            assertTrue(animator.describe().isNotBlank())
            assertTrue(animator.describe().contains("rpm"))
        }
    }

    @Test
    fun `the pose can be copied without allocating a new one`() {
        val animator = TurntableAnimator()
        animator.setState(DeckState.PLAYING, immediate = true)
        animator.setPlayhead(0.4f)
        animator.update(step)
        val copy = DeckPose()
        copy.set(animator.currentPose)
        assertEquals(animator.currentPose.platterRotation, copy.platterRotation, 0f)
        assertEquals(animator.currentPose.armTravel, copy.armTravel, 0f)
        assertEquals(animator.currentPose.needleContact, copy.needleContact, 0f)
        assertEquals(animator.currentPose.lampIntensity, copy.lampIntensity, 0f)
        assertEquals(animator.currentPose.pressProgress, copy.pressProgress, 0f)
    }

    @Test
    fun `every style gives the disc its own material`() {
        assertEquals(5, VinylStyleId.all.size)
        val materials = VinylStyleId.all.map { style -> style.id to DeckMaterials.record(style) }
        materials.forEach { (id, material) ->
            assertEquals("$id has no base colour", 3, material.baseColor.size)
            assertEquals("$id has no specular colour", 3, material.specularColor.size)
            assertTrue(material.grooveAmount > 0f)
            assertTrue(material.roughness in 0f..1f)
            assertTrue(material.metallic in 0f..1f)
            assertTrue(material.alpha in 0f..1f)
        }
        assertEquals(
            "two styles share a disc colour",
            VinylStyleId.all.size,
            materials.map { it.second.baseColor.joinToString() }.toSet().size,
        )
    }

    @Test
    fun `the smoked pressing is drawn as translucent vinyl`() {
        val smoked = DeckMaterials.record(VinylStyleId.SMOKED_OBSIDIAN)
        val sapphire = DeckMaterials.record(VinylStyleId.MIDNIGHT_SAPPHIRE)
        val ruby = DeckMaterials.record(VinylStyleId.CLASSIC_WAX_RUBY)
        assertTrue("the smoked record is opaque", smoked.alpha < 1f)
        assertTrue("the smoked record does not transmit light", smoked.transmission > 0f)
        assertTrue("the sapphire record is opaque", sapphire.alpha < 1f)
        assertEquals(1f, ruby.alpha, 1e-6f)
        assertEquals(0f, ruby.transmission, 1e-6f)
    }

    @Test
    fun `the groove sheen is a separate pass whose strength follows the animation`() {
        val dull = DeckMaterials.grooveSheen(VinylStyleId.CLASSIC_WAX_RUBY, 0f)
        val bright = DeckMaterials.grooveSheen(VinylStyleId.CLASSIC_WAX_RUBY, 1f)
        assertTrue(bright.alpha > dull.alpha)
        assertEquals(1f, bright.sheen, 1e-6f)
        assertTrue("the sheen is opaque, so it would hide the grooves", bright.alpha < 0.5f)
        assertTrue(dull.alpha > 0f)

        val label = DeckMaterials.label(VinylStyleId.MIDNIGHT_SAPPHIRE)
        assertTrue("the label does not use the drawn bitmap", label.usesLabel)
        assertFalse(DeckMaterials.record(VinylStyleId.IMPERIAL_GOLD_MASTER).usesLabel)
    }

    @Test
    fun `the lamp material carries the colour and the intensity`() {
        val off = DeckMaterials.lamp(DeckLighting.LAMP_COLOR, TurntableAnimator.LAMP_OFF)
        val on = DeckMaterials.lamp(DeckLighting.LAMP_COLOR, TurntableAnimator.LAMP_BRIGHT)
        val error = DeckMaterials.lamp(DeckLighting.LAMP_ERROR_COLOR, TurntableAnimator.LAMP_MEDIUM)
        assertTrue("a brighter lamp is not more opaque", on.alpha > off.alpha)
        assertTrue(on.alpha <= 1f)
        assertEquals(DeckLighting.LAMP_COLOR[0], on.baseColor[0], 1e-6f)
        assertNotEquals(error.baseColor[0], on.baseColor[0])
        assertTrue(DeckLighting.AMBIENT.all { it > 0f })
        assertTrue("the key light is not warm", DeckLighting.KEY_COLOR[0] > DeckLighting.KEY_COLOR[2])
        assertEquals(1f, DeckLighting.KEY_DIRECTION.map { it * it }.sum(), 1e-4f)
    }

    @Test
    fun `the deck is built at a believable size`() {
        // A real 12-inch record: 0.3048 m across, a 0.10 m label, on a plinth a little larger than the disc.
        assertEquals(0.1524f, DeckDimensions.RECORD_RADIUS, 1e-4f)
        assertEquals(0.050f, DeckDimensions.RECORD_LABEL_RADIUS, 1e-4f)
        assertTrue("the plinth is narrower than the record", DeckDimensions.PLINTH_WIDTH > DeckDimensions.RECORD_RADIUS * 2f)
        assertTrue(DeckDimensions.PLINTH_DEPTH > DeckDimensions.RECORD_RADIUS * 2f)
        assertTrue("the platter is smaller than the record", DeckDimensions.PLATTER_RADIUS > DeckDimensions.RECORD_RADIUS)
        assertTrue("the record floats above the platter", DeckDimensions.RECORD_BOTTOM > DeckDimensions.PLATTER_Y)
        assertTrue("the mat is thicker than the record", DeckDimensions.MAT_Y > DeckDimensions.PLATTER_Y)
        assertTrue("the lamp is inside the plinth", DeckDimensions.LAMP_Y > DeckDimensions.PLINTH_HEIGHT)
        assertTrue("the spindle is wider than the label", DeckDimensions.SPINDLE_RADIUS < DeckDimensions.RECORD_LABEL_RADIUS)
        assertEquals(4, DeckDimensions.FEET.size)
        assertEquals(4, DeckDimensions.FEET.map { "%.3f,%.3f".format(it.first, it.second) }.toSet().size)
        DeckDimensions.FEET.forEach { (x, z) ->
            assertTrue("a foot is off the plinth", abs(x) < DeckDimensions.PLINTH_WIDTH / 2f)
            assertTrue("a foot is off the plinth", abs(z) < DeckDimensions.PLINTH_DEPTH / 2f)
        }
        assertTrue(DeckDimensions.FOOT_HEIGHT > 0f)
        assertTrue(DeckDimensions.FOOT_RADIUS > 0f)
        assertTrue(DeckDimensions.KNOB_ONE_X < 0f)
        assertTrue(DeckDimensions.KNOB_TWO_X > 0f)
    }

    @Test
    fun `the stylus follows the groove from the outside in`() {
        val leadIn = DeckDimensions.stylusPosition(0f, 0f, Vec3())
        val radiusAtStart = kotlin.math.sqrt(leadIn.x * leadIn.x + leadIn.z * leadIn.z)
        assertTrue("the stylus starts off the record", radiusAtStart <= DeckDimensions.RECORD_RADIUS)
        assertTrue("the stylus starts inside the label", radiusAtStart > DeckDimensions.RECORD_LABEL_RADIUS)

        val runOut = DeckDimensions.stylusPosition(1f, 0f, Vec3())
        val radiusAtEnd = kotlin.math.sqrt(runOut.x * runOut.x + runOut.z * runOut.z)
        assertTrue("the arm did not move inwards: $radiusAtStart to $radiusAtEnd", radiusAtEnd < radiusAtStart)
        assertTrue("the stylus ran past the label", radiusAtEnd >= DeckDimensions.RECORD_LABEL_RADIUS * 0.8f)

        // Lifting the arm raises the stylus off the record by exactly the arm lift height.
        val lifted = DeckDimensions.stylusPosition(0.5f, 1f, Vec3())
        val down = DeckDimensions.stylusPosition(0.5f, 0f, Vec3())
        assertTrue(lifted.y > down.y)
        assertEquals(DeckDimensions.ARM_LIFT_HEIGHT, lifted.y - down.y, 1e-5f)
        // The caller's vector is the one filled in, so the renderer never allocates.
        val reused = Vec3(1f, 1f, 1f)
        assertTrue(DeckDimensions.stylusPosition(0.5f, 0f, reused) === reused)
    }

    @Test
    fun `the tonearm curve is a real curve with a real length`() {
        val curve = DeckDimensions.armCurve()
        assertTrue("the arm is a straight stick", curve.size >= 3)
        curve.zipWithNext().forEach { (a, b) ->
            val distance = kotlin.math.sqrt(
                (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z),
            )
            assertTrue("two control points coincide", distance > 1e-4f)
        }
        // A straight arm would give the same direction for every segment; a real one bends. Compare the
        // first and last segment directions as unit vectors, so the test does not depend on segment lengths.
        fun direction(index: Int): FloatArray {
            val (a, b) = curve[index] to curve[index + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            return floatArrayOf(dx / length, dy / length, dz / length)
        }

        val first = direction(0)
        val last = direction(curve.size - 2)
        val cosine = first[0] * last[0] + first[1] * last[1] + first[2] * last[2]
        assertTrue("the arm does not bend: the ends are $cosine apart", cosine < 0.995f)
    }

    @Test
    fun `every part of the deck is listed and drawn in order`() {
        assertEquals(16, DeckPart.entries.size)
        val labels = DeckPart.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(DeckPart.entries.toList(), DeckPart.ordered)
        // The draw order starts at the plinth and ends at the lamp, which is what is in front of everything.
        assertEquals(DeckPart.PLINTH, DeckPart.ordered.first())
        assertEquals(DeckPart.LAMP, DeckPart.ordered.last())
        assertTrue(DeckPart.ordered.indexOf(DeckPart.PLATTER) < DeckPart.ordered.indexOf(DeckPart.RECORD))
        assertTrue(DeckPart.ordered.indexOf(DeckPart.RECORD) < DeckPart.ordered.indexOf(DeckPart.LABEL))
        assertTrue(DeckPart.ordered.indexOf(DeckPart.STYLUS) > DeckPart.ordered.indexOf(DeckPart.HEADSHELL))
    }
}
