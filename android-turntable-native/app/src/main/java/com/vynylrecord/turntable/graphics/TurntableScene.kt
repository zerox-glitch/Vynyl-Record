package com.vynylrecord.turntable.graphics

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.vynylrecord.turntable.graphics.animation.TurntableAnimator
import com.vynylrecord.turntable.graphics.geometry.Mesh
import com.vynylrecord.turntable.graphics.geometry.MeshFactory
import com.vynylrecord.turntable.graphics.geometry.TurntableBuilder
import com.vynylrecord.turntable.graphics.gl.GlUtil
import com.vynylrecord.turntable.graphics.gl.GpuMesh
import com.vynylrecord.turntable.graphics.gl.OffscreenTargets
import com.vynylrecord.turntable.graphics.gl.ShaderProgram
import com.vynylrecord.turntable.graphics.gl.SimpleTextureTarget
import com.vynylrecord.turntable.graphics.material.Material
import com.vynylrecord.turntable.graphics.material.LabelTextureFactory
import com.vynylrecord.turntable.graphics.material.MaterialLibrary
import com.vynylrecord.turntable.graphics.material.applyVinylStyle
import com.vynylrecord.turntable.model.Mat4
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.Vec3
import com.vynylrecord.turntable.model.VinylStyle

/**
 * The renderer, minus the Android plumbing.
 *
 * One scene pass with depth, one half-resolution backdrop pass, one composite pass. Nothing here
 * allocates per frame: every matrix lives in a preallocated array, every mesh is uploaded once, and
 * the geometry is only rebuilt when the quality level changes.
 *
 * Ownership rules:
 *
 *  * Every GL object is created in [initialise] and destroyed in [release]; both run on the GL thread.
 *  * The caller owns the label [Bitmap] and recycles it as soon as [uploadLabelBitmap] returns.
 *  * After a lost EGL context only [release] must be skipped — the driver already freed everything —
 *    so [initialise] builds a fresh scene from scratch. That path is exercised on every rotation.
 *
 * The scene is deliberately *stateless* about playback: it renders whatever pose the
 * [TurntableAnimator] currently holds.
 */
class TurntableScene(private val context: Context) {

    /** Meshes that move together. Order defines the transform application order. */
    enum class Node {
        /** Fixed to the plinth. */
        WORLD,

        /** Spins with the platter and the mat. */
        PLATTER,

        /** Spins with the platter, and additionally lifts and twists while being loaded. */
        RECORD,

        /** Yaws about the pivot, and cocks up about the pivot's horizontal axis. */
        ARM,

        /** Carried by the arm, with its own microscopic vertical flutter. */
        NEEDLE,

        /** Depresses into the plinth. */
        BUTTON,

        /** Rotates like a detented selector. */
        SELECTOR,
    }

    private class Part(
        val gpu: GpuMesh,
        val node: Node,
        val kind: MaterialLibrary.Kind,
        val transparent: Boolean,
    ) {
        val baseOffset: Int = node.ordinal * Mat4.SIZE
    }

    // ------------------------------------------------------------------ GL objects

    private var turntableProgram: ShaderProgram? = null
    private var backgroundProgram: ShaderProgram? = null
    private var compositeProgram: ShaderProgram? = null

    private var turntableUniforms: TurntableUniforms? = null
    private var backgroundUniforms: BackgroundUniforms? = null
    private var compositeUniforms: CompositeUniforms? = null

    private val sceneTargets = OffscreenTargets()
    private val backgroundTarget = SimpleTextureTarget()

    private var parts: List<Part> = emptyList()
    private var materials: Map<MaterialLibrary.Kind, Material> = emptyMap()
    private var labelTexture = 0
    private var whiteTexture = 0

    /** Locally generated studio rig; re-tinted whenever the vinyl style changes. */
    val environment = SceneEnvironment()

    // ------------------------------------------------------------------ render state

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var applyQuality: RenderQuality = RenderQuality.DEFAULT
    private var builtDetail: MeshFactory.GeometryDetail? = null
    private var isRecordTranslucent = false
    private var labelUploaded = false
    private var appliedMetadata: RecordMetadata? = null
    private var backdropSeconds = 0f

    /** Draw calls issued by the last scene pass; the composite and backdrop add one each. */
    var drawCalls: Int = 0
        private set

    /** Triangles submitted by the last scene pass. */
    var triangleCount: Int = 0
        private set

    /** Triangles the CPU has prepared; the debug overlay reports this as scene complexity. */
    var sceneTriangles: Int = 0
        private set

    /** Pixels the scene pass actually rendered into, i.e. after the quality render scale. */
    val viewportWidth: Int get() = if (sceneTargets.width > 0) sceneTargets.width else surfaceWidth
    val viewportHeight: Int get() = if (sceneTargets.height > 0) sceneTargets.height else surfaceHeight

    /** MSAA samples the driver actually granted; 0 means the composite's edge resolve is doing it. */
    val activeSamples: Int get() = sceneTargets.samples

    val isReady: Boolean get() = turntableProgram != null && parts.isNotEmpty()

    // ------------------------------------------------------------------ per-frame scratch

    private val viewProjection = FloatArray(Mat4.SIZE)
    private val projection = FloatArray(Mat4.SIZE)
    private val view = FloatArray(Mat4.SIZE)
    private val viewInverse = FloatArray(Mat4.SIZE)
    private val viewRotation = FloatArray(9)
    private val nodeMatrices = FloatArray(Node.entries.size * Mat4.SIZE)
    private val scratchMatrix = FloatArray(Mat4.SIZE)
    private val cameraEye = Vec3()
    private val cameraTarget = Vec3()
    private val probePoint = Vec3()

    // ------------------------------------------------------------------ lifecycle

    /**
     * Builds every GL object. Must be called with a current EGL context, and again after any context
     * loss. [quality] is the level the UI currently wants; [capabilities] is only used to clamp it.
     */
    fun initialise(quality: RenderQuality, capabilities: GlCapabilities?): Boolean {
        release()

        // A shader that fails to compile throws; the host turns that into the static fallback rather
        // than a black rectangle, so it is caught here and reported as a failed initialisation.
        return try {
            buildPrograms()
            applyQuality = quality
            environment.applyVinylStyle(VinylStyle.DEFAULT)
            applyMaterialSet(MaterialLibrary.buildSet(VinylStyle.DEFAULT))
            buildGeometry(quality)
            GlUtil.checkGlError("scene initialise")
            isReady
        } catch (error: RuntimeException) {
            Log.e(GlUtil.TAG, "Scene initialisation failed", error)
            release()
            false
        }
    }

    private fun buildPrograms() {
        val assets = context.assets
        turntableProgram = ShaderProgram.fromAssets(assets, "turntable", "shaders/turntable.vert", "shaders/turntable.frag")
        backgroundProgram = ShaderProgram.fromAssets(assets, "background", "shaders/fullscreen.vert", "shaders/background.frag")
        compositeProgram = ShaderProgram.fromAssets(assets, "composite", "shaders/fullscreen.vert", "shaders/composite.frag")

        turntableUniforms = TurntableUniforms(requireNotNull(turntableProgram))
        backgroundUniforms = BackgroundUniforms(requireNotNull(backgroundProgram))
        compositeUniforms = CompositeUniforms(requireNotNull(compositeProgram))

        whiteTexture = createSolidTexture(255, 255, 255, 255)
        labelTexture = createSolidTexture(24, 20, 18, 255)
        labelUploaded = false
    }

    /** Rebuilds meshes at the detail level implied by [quality]. Cheap enough to call on a change. */
    fun rebuildGeometry(quality: RenderQuality) {
        applyQuality = quality
        buildGeometry(quality)
    }

    private fun buildGeometry(quality: RenderQuality) {
        val detail = quality.geometry
        if (builtDetail == detail && parts.isNotEmpty()) return

        for (part in parts) part.gpu.release()

        val meshes = TurntableBuilder.build(detail)
        sceneTriangles = meshes.totalTriangles
        val built = ArrayList<Part>(MESH_COUNT)
        add(built, meshes.ground, Node.WORLD, MaterialLibrary.Kind.GROUND_SURFACE)
        add(built, meshes.foot, Node.WORLD, MaterialLibrary.Kind.RUBBER_FOOT)
        add(built, meshes.plinthBody, Node.WORLD, MaterialLibrary.Kind.PLINTH_LACQUER)
        add(built, meshes.plinthTrim, Node.WORLD, MaterialLibrary.Kind.PLINTH_TRIM_BRASS, transparent = true)
        add(built, meshes.platterBody, Node.PLATTER, MaterialLibrary.Kind.PLATTER_CASTING)
        add(built, meshes.platterRim, Node.PLATTER, MaterialLibrary.Kind.PLATTER_RIM_ALUMINIUM)
        add(built, meshes.platterStrobe, Node.PLATTER, MaterialLibrary.Kind.STROBE_MARK)
        add(built, meshes.mat, Node.PLATTER, MaterialLibrary.Kind.RUBBER_MAT)
        add(built, meshes.recordBody, Node.RECORD, MaterialLibrary.Kind.VINYL_RECORD)
        add(built, meshes.recordTop, Node.RECORD, MaterialLibrary.Kind.VINYL_RECORD)
        add(built, meshes.label, Node.RECORD, MaterialLibrary.Kind.PAPER_LABEL)
        add(built, meshes.spindle, Node.PLATTER, MaterialLibrary.Kind.POLISHED_STEEL)
        add(built, meshes.pivotBase, Node.WORLD, MaterialLibrary.Kind.PIVOT_HOUSING)
        add(built, meshes.pivotBrass, Node.ARM, MaterialLibrary.Kind.PLINTH_TRIM_BRASS)
        add(built, meshes.tonearm, Node.ARM, MaterialLibrary.Kind.POLISHED_STEEL)
        add(built, meshes.armRest, Node.WORLD, MaterialLibrary.Kind.CONTROL_PLASTIC)
        add(built, meshes.needle, Node.NEEDLE, MaterialLibrary.Kind.CONTROL_PLASTIC)
        add(built, meshes.powerButton, Node.BUTTON, MaterialLibrary.Kind.CONTROL_PLASTIC)
        add(built, meshes.speedSelector, Node.SELECTOR, MaterialLibrary.Kind.BRASS_CONTROL)
        add(built, meshes.volumeKnob, Node.WORLD, MaterialLibrary.Kind.BRASS_CONTROL)
        add(built, meshes.indicatorBezel, Node.WORLD, MaterialLibrary.Kind.CONTROL_PLASTIC)
        add(built, meshes.indicatorLamp, Node.WORLD, MaterialLibrary.Kind.INDICATOR_LAMP, transparent = true)

        parts = sortForDrawing(built)
        builtDetail = detail
        GlUtil.checkGlError("geometry rebuild")
    }

    /** Swaps in a new material set and re-tints the studio. Called when the style changes. */
    fun applyStyle(style: VinylStyle) {
        materials[MaterialLibrary.Kind.VINYL_RECORD]?.applyVinylStyle(style)
        isRecordTranslucent = style.isTranslucent
        environment.applyVinylStyle(style)
    }

    /**
     * Draws a label for [metadata] at the current quality level's texture size.
     *
     * Runs on the render thread, and only when the wording or the style actually changed: a full
     * repaint is a few milliseconds of Canvas work plus a texture upload.
     */
    fun renderLabelBitmap(metadata: RecordMetadata, style: VinylStyle): Bitmap =
        LabelTextureFactory.create(metadata, style, applyQuality.labelTextureSize)

    /** True when this exact wording is already on the platter. */
    fun hasAppliedMetadata(metadata: RecordMetadata): Boolean =
        appliedMetadata?.rendersSameLabelAs(metadata) == true

    fun markMetadataApplied(metadata: RecordMetadata) {
        appliedMetadata = metadata
    }

    /**
     * Uploads [bitmap] and recycles it. Ownership passes to the scene: the caller must not touch the
     * bitmap again, which is what keeps a 768x768 ARGB buffer from lingering between reprints.
     */
    fun uploadLabelBitmap(bitmap: Bitmap) {
        val texture = labelTexture
        if (texture == 0) {
            bitmap.recycle()
            return
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        // texImage2D replaces the storage outright, so reprinting a label never leaves the old
        // dimensions behind and never needs the texture object to be recreated.
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (!labelUploaded) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            labelUploaded = true
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        GlUtil.checkGlError("label upload")
    }

    /** Called with the new surface size. Returns true when a full rebuild happened. */
    fun resize(quality: RenderQuality, width: Int, height: Int, capabilities: GlCapabilities?): Boolean {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        applyQuality = quality
        buildGeometry(quality)

        val longestEdge = kotlin.math.max(surfaceWidth, surfaceHeight) * quality.renderScale
        val budget = if (longestEdge > quality.maxRenderSize) quality.maxRenderSize / longestEdge else 1f
        val renderWidth = (surfaceWidth * quality.renderScale * budget).toInt().coerceAtLeast(64)
        val renderHeight = (surfaceHeight * quality.renderScale * budget).toInt().coerceAtLeast(64)
        val requested = RenderQuality.clampSamples(quality, capabilities)
        val ok = sceneTargets.ensure(renderWidth, renderHeight, requested)
        if (!ok) {
            // The driver refused the multisampled configuration; fall back to the edge resolve.
            sceneTargets.ensure(renderWidth, renderHeight, 0)
        }
        backgroundTarget.ensure(renderWidth / 2, renderHeight / 2)
        return ok
    }

    /**
     * Draws one frame.
     *
     * @param pose the mechanism's current pose; read but never mutated.
     * @param camera the camera to draw from.
     * @param deltaSeconds real frame time, used only by the slow backdrop drift.
     */
    fun render(pose: TurntableAnimator, camera: CameraRig.Snapshot, deltaSeconds: Float) {
        val turntable = turntableProgram ?: return
        val background = backgroundProgram ?: return
        val composite = compositeProgram ?: return
        if (parts.isEmpty() || sceneTargets.width <= 0) return
        backdropSeconds += deltaSeconds.coerceIn(0f, 0.1f)

        updateBackdropHorizon(camera)
        buildNodeMatrices(pose)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // ---- pass 1: the studio backdrop, at half resolution.
        backgroundTarget.bind()
        GLES30.glViewport(0, 0, backgroundTarget.width, backgroundTarget.height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        background.use()
        uploadBackground(backgroundUniforms!!, background, deltaSeconds)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // ---- pass 2: the deck, into a transparent, possibly multisampled target.
        sceneTargets.bindForScene()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        turntable.use()
        uploadSharedSceneUniforms(turntableUniforms!!, turntable, camera)

        var currentKind: MaterialLibrary.Kind? = null
        var currentMaterial: Material? = null
        var draws = 0
        var triangles = 0
        for (part in parts) {
            val material = materials[part.kind] ?: continue
            if (part.kind != currentKind || material !== currentMaterial) {
                uploadMaterial(turntableUniforms!!, turntable, material, part.kind, pose.lampLevel)
                currentKind = part.kind
                currentMaterial = material
            }
            uploadModel(turntableUniforms!!, turntable, nodeMatrices, part.baseOffset)
            part.gpu.draw()
            draws++
            triangles += part.gpu.triangleCount
        }

        // ---- pass 3: composite the deck over the backdrop.
        sceneTargets.resolve()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        composite.use()
        uploadComposite(compositeUniforms!!, composite)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        drawCalls = draws + 2
        triangleCount = triangles
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GlUtil.checkGlError("frame")
    }

    /** Frees every GL object. Must run with a current context; skip it after a context loss. */
    fun release() {
        for (part in parts) part.gpu.release()
        parts = emptyList()
        builtDetail = null
        sceneTriangles = 0

        sceneTargets.release()
        backgroundTarget.release()

        turntableProgram?.release()
        backgroundProgram?.release()
        compositeProgram?.release()
        turntableProgram = null
        backgroundProgram = null
        compositeProgram = null
        turntableUniforms = null
        backgroundUniforms = null
        compositeUniforms = null

        if (labelTexture != 0) {
            GlUtil.deleteTexture(labelTexture)
            labelTexture = 0
        }
        if (whiteTexture != 0) {
            GlUtil.deleteTexture(whiteTexture)
            whiteTexture = 0
        }
        labelUploaded = false
        appliedMetadata = null
        materials = emptyMap()
        isRecordTranslucent = false
        drawCalls = 0
        triangleCount = 0
    }

    /** Number of draw calls one scene pass issues when nothing is culled. */
    val meshCount: Int get() = parts.size

    /** One line for the debug overlay and for the crash-free "what is actually running" report. */
    fun describe(): String {
        val samples = if (activeSamples > 0) "MSAA ${activeSamples}x" else "no MSAA"
        val scale = (applyQuality.renderScale * 100f).toInt()
        return "meshes=${parts.size} tris=$sceneTriangles ${applyQuality.displayName} ${scale}% $samples"
    }

    // ------------------------------------------------------------------ internals

    /**
     * Uploads one mesh and files it under the node that carries it.
     *
     * Meshes used more than once (the feet) arrive already merged by [TurntableBuilder], so every
     * entry here is a single draw call.
     */
    private fun add(
        target: MutableList<Part>,
        mesh: Mesh,
        node: Node,
        kind: MaterialLibrary.Kind,
        transparent: Boolean = false,
    ) {
        val gpu = GpuMesh(mesh)
        gpu.upload()
        target.add(Part(gpu = gpu, node = node, kind = kind, transparent = transparent))
    }

    /**
     * Draw order: opaque geometry first, then the translucent floor, then anything that blends over
     * it. Grouping by material also means the per-material uniforms are uploaded once per run
     * instead of once per mesh.
     */
    private fun sortForDrawing(built: List<Part>): List<Part> = built.sortedWith(
        compareBy(
            { part -> if (part.kind == MaterialLibrary.Kind.GROUND_SURFACE) 2 else if (part.transparent || part.kind == MaterialLibrary.Kind.VINYL_RECORD) 1 else 0 },
            { part -> part.kind.ordinal },
            { part -> part.node.ordinal },
        ),
    )

    private fun applyMaterialSet(set: Map<MaterialLibrary.Kind, Material>) {
        materials = set
        isRecordTranslucent = materials[MaterialLibrary.Kind.VINYL_RECORD]?.isTransparent ?: false
    }

    private fun buildNodeMatrices(pose: TurntableAnimator) {
        val platterX = TurntableSpec.PLATTER_CENTER_X
        val platterZ = TurntableSpec.PLATTER_CENTER_Z
        Mat4.identity(nodeMatrices, Node.WORLD.ordinal * Mat4.SIZE)

        // Platter: a plain spin about the platter's own vertical axis.
        rotateAboutOwnVerticalAxis(
            nodeMatrices, Node.PLATTER.ordinal * Mat4.SIZE,
            platterX, 0f, platterZ, -pose.platterAngleDeg,
        )

        // Record: carried by the platter, lifted during insertion and twisted as it drops.
        val recordOffset = Node.RECORD.ordinal * Mat4.SIZE
        Mat4.copyInto(nodeMatrices, Node.PLATTER.ordinal * Mat4.SIZE, nodeMatrices, recordOffset)
        if (pose.recordInsertion01 > 0.001f || pose.recordOffsetY > 0f) {
            rotateAboutOwnVerticalAxis(
                scratchMatrix, 0,
                platterX, 0f, platterZ,
                pose.recordInsertion01 * RECORD_INSERTION_TWIST_DEGREES,
            )
            Mat4.translate(scratchMatrix, 0, 0f, pose.recordOffsetY, 0f, scratchMatrix, 0)
            Mat4.multiply(nodeMatrices, recordOffset, scratchMatrix, 0, nodeMatrices, recordOffset)
        }

        // Arm: cock the stylus up off the record, then yaw the whole arm about its bearing.
        //
        // `translate(pivot) * yaw * lift * translate(-pivot)` is evaluated right to left, so the
        // cueing lift is applied with the bearing at the origin -- about the pivot's own horizontal
        // axis, which raises the +X stylus end -- and the yaw then swings the arm to its position
        // over the record. Composing the two the other way round would roll the yawed arm sideways
        // instead of lifting it.
        //
        // This form is correct for *world-space* meshes, which is why TurntableBuilder bakes the
        // bearing into the arm, the stylus and the brass pivot hardware. When the arm meshes were
        // left pivot-local, this same matrix threw the assembly 240 mm off its bearing: the arm
        // rendered beside the platter with the counterweight hanging off the front of the plinth.
        val armOffset = Node.ARM.ordinal * Mat4.SIZE
        Mat4.setTranslation(TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.TONEARM_PIVOT_Y, TurntableSpec.TONEARM_PIVOT_Z, scratchMatrix, 0)
        Mat4.rotateY(scratchMatrix, 0, -pose.tonearmAngleDeg, scratchMatrix, 0)
        Mat4.rotateZ(scratchMatrix, 0, pose.tonearmLift01 * TurntableSpec.TONEARM_LIFT_MAX_DEG, scratchMatrix, 0)
        Mat4.translate(
            scratchMatrix, 0,
            -TurntableSpec.TONEARM_PIVOT_X, -TurntableSpec.TONEARM_PIVOT_Y, -TurntableSpec.TONEARM_PIVOT_Z,
            nodeMatrices, armOffset,
        )

        // Needle: the arm's pose plus the groove flutter, which is a fraction of a millimetre.
        val needleOffset = Node.NEEDLE.ordinal * Mat4.SIZE
        Mat4.copyInto(nodeMatrices, armOffset, nodeMatrices, needleOffset)
        if (pose.needleMicroOffsetY != 0f) {
            Mat4.translate(nodeMatrices, needleOffset, 0f, pose.needleMicroOffsetY, 0f, nodeMatrices, needleOffset)
        }

        // Controls: the power button sinks, the speed selector swings between its detents.
        val buttonOffset = Node.BUTTON.ordinal * Mat4.SIZE
        Mat4.setTranslation(0f, -pose.powerButtonPress * BUTTON_TRAVEL_METRES, 0f, nodeMatrices, buttonOffset)

        val selectorOffset = Node.SELECTOR.ordinal * Mat4.SIZE
        rotateAboutOwnVerticalAxis(
            nodeMatrices, selectorOffset,
            TurntableSpec.SPEED_SELECTOR_X, TurntableSpec.PLINTH_TOP, TurntableSpec.SPEED_SELECTOR_Z,
            pose.speedSelectorAngleDeg,
        )
    }

    /**
     * `translate(x,y,z) * rotateY(degrees) * translate(-x,-y,-z)`.
     *
     * Used for every part that turns about a vertical axis through a point that is not the world
     * origin (platter, record, selector). [target] is overwritten, so the result is absolute.
     */
    private fun rotateAboutOwnVerticalAxis(
        target: FloatArray,
        offset: Int,
        x: Float,
        y: Float,
        z: Float,
        degrees: Float,
    ) {
        Mat4.setTranslation(x, y, z, scratchMatrix, 0)
        Mat4.rotateY(scratchMatrix, 0, degrees, scratchMatrix, 0)
        Mat4.translate(scratchMatrix, 0, -x, -y, -z, target, offset)
    }

    private fun uploadSharedSceneUniforms(uniforms: TurntableUniforms, program: ShaderProgram, camera: CameraRig.Snapshot) {
        camera.positionOf(cameraEye)
        Mat4.setPerspective(camera.fovYDegrees, aspect(), NEAR_PLANE, FAR_PLANE, projection, 0)
        Mat4.setLookAt(
            cameraEye.x, cameraEye.y, cameraEye.z,
            camera.targetX, camera.targetY, camera.targetZ,
            0f, 1f, 0f,
            view, 0,
        )
        Mat4.multiply(projection, 0, view, 0, viewProjection, 0)
        Mat4.invert(view, 0, viewInverse, 0)
        Mat4.normalMatrix3(viewInverse, 0, viewRotation, 0)

        program.setMat4(uniforms.viewProjection, viewProjection)
        program.setVec3(uniforms.cameraPosition, cameraEye.x, cameraEye.y, cameraEye.z)

        program.setVec3(uniforms.keyDirection, environment.keyDirection, 0)
        program.setVec3(uniforms.keyColor, environment.keyColor, 0)
        program.setVec3(uniforms.fillPosition, environment.fillPosition, 0)
        program.setVec3(uniforms.fillColor, environment.fillColor, 0)
        program.setVec3(uniforms.rimDirection, environment.rimDirection, 0)
        program.setVec3(uniforms.rimColor, environment.rimColor, 0)
        program.setVec3(uniforms.ambientFloor, environment.ambientFloor, 0)
        program.setVec3(uniforms.ambientSky, environment.ambientSky, 0)

        program.setVec4(uniforms.discShadow0, environment.discShadow0, 0)
        program.setVec4(uniforms.discShadow1, environment.discShadow1, 0)
        program.setVec4(uniforms.rectShadow, environment.rectShadow, 0)
        program.setFloat(uniforms.shadowCeiling, environment.shadowCeiling)
        program.setFloat(uniforms.shadowStrength, environment.shadowStrength)
        program.setFloat(uniforms.shadowBias, environment.shadowBias)
    }

    private fun uploadMaterial(
        uniforms: TurntableUniforms,
        program: ShaderProgram,
        material: Material,
        kind: MaterialLibrary.Kind,
        lampLevel: Float,
    ) {
        program.setVec3(uniforms.albedo, material.albedo, 0)
        program.setVec3(uniforms.specularTint, material.specularTint, 0)
        program.setFloat(uniforms.roughness, material.roughness.value)
        program.setFloat(uniforms.metallic, material.metallic.value)
        program.setFloat(uniforms.reflectance, material.reflectance.value)
        program.setFloat(uniforms.clearcoat, material.clearcoat.value)
        program.setFloat(uniforms.fresnelBoost, material.fresnelBoost.value)
        program.setFloat(uniforms.opacity, material.opacity.value)
        program.setFloat(uniforms.ambientOcclusion, material.ambientOcclusion.value)
        program.setVec3(uniforms.emissive, material.emissive, 0)
        // The indicator lamp is driven by the mechanism's own lamp level, so the deck shows its
        // state even when nothing else in the scene is bright.
        val emission = if (kind == MaterialLibrary.Kind.INDICATOR_LAMP) {
            0.35f + 1.05f * lampLevel.coerceIn(0f, 1f)
        } else {
            material.emissionStrength.value
        }
        program.setFloat(uniforms.emissionStrength, emission)

        val textured = material.useLabelTexture.value
        program.setInt(uniforms.useLabelTexture, if (textured) 1 else 0)
        if (textured) {
            program.setInt(uniforms.labelTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (labelUploaded) labelTexture else whiteTexture)
        }

        program.setInt(uniforms.grooveMode, if (material.grooveMode.value) 1 else 0)
        program.setVec2(uniforms.discRadialRange, material.discRadialRange[0], material.discRadialRange[1])
        program.setVec2(uniforms.grooveRange, material.grooveRange[0], material.grooveRange[1])
        program.setFloat(uniforms.groovePitch, material.groovePitch.value)
        program.setFloat(uniforms.grooveDepth, material.grooveDepth.value)
        program.setFloat(uniforms.grooveStrength, material.grooveStrength.value)
        program.setFloat(uniforms.grooveEccentricity, material.grooveEccentricity.value)
        program.setFloat(uniforms.grooveModulation, material.grooveModulation.value)

        program.setInt(uniforms.brushedMode, if (material.brushedMode.value) 1 else 0)
        program.setFloat(uniforms.brushedStrength, material.brushedStrength.value)
        program.setFloat(uniforms.brushedScale, material.brushedScale.value)

        program.setFloat(uniforms.detailLevel, applyQuality.grooveDetail)
        program.setVec4(uniforms.uvTransform, material.uvTransform, 0)

        // Blending is decided per material, not per mesh: translucent pressings and the brass trim
        // over the polished edge both rely on it, and the depth mask has to follow.
        if (material.isTransparent) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(true)
        }
    }

    private fun uploadModel(uniforms: TurntableUniforms, program: ShaderProgram, matrix: FloatArray, offset: Int) {
        program.setMat4(uniforms.model, matrix, offset)
        Mat4.normalMatrix3(matrix, offset, normalMatrix3, 0)
        program.setMat3(uniforms.normalMatrix, normalMatrix3, 0)
    }

    private fun uploadModel(uniforms: TurntableUniforms, program: ShaderProgram, matrix: FloatArray) =
        uploadModel(uniforms, program, matrix, 0)

    private fun uploadBackground(uniforms: BackgroundUniforms, program: ShaderProgram, deltaSeconds: Float) {
        program.setVec2(uniforms.resolution, backgroundTarget.width.toFloat(), backgroundTarget.height.toFloat())
        program.setFloat(uniforms.time, backdropSeconds)
        program.setInt(uniforms.grainEnabled, if (environment.grainEnabled != 0 && applyQuality.grain) 1 else 0)
        program.setInt(uniforms.floorEnabled, 1)
        program.setInt(uniforms.animate, if (environment.animateBackdrop != 0 && !reducedMotion) 1 else 0)
        program.setVec3(uniforms.backdropTop, environment.backdropTop, 0)
        program.setVec3(uniforms.backdropMid, environment.backdropMid, 0)
        program.setVec3(uniforms.backdropBottom, environment.backdropBottom, 0)
        program.setVec3(uniforms.glowColor, environment.glowColor, 0)
        program.setVec2(uniforms.glowPosition, environment.glowPositionX, environment.glowPositionY)
        program.setFloat(uniforms.glowStrength, environment.glowStrength)
        program.setFloat(uniforms.grainStrength, if (applyQuality.grain) environment.grainStrength else 0f)
        program.setFloat(uniforms.vignetteStrength, environment.vignetteStrength)
        program.setFloat(uniforms.horizon, environment.horizon)
        program.setFloat(uniforms.floorFade, environment.floorFade)
    }

    private fun uploadComposite(uniforms: CompositeUniforms, program: ShaderProgram) {
        val renderWidth = sceneTargets.width.toFloat()
        val renderHeight = sceneTargets.height.toFloat()

        program.setVec2(uniforms.resolution, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        program.setVec2(uniforms.sceneTexel, 1f / renderWidth, 1f / renderHeight)
        // Hardware MSAA already resolved the edges, so the shader's edge resolve stays out of the way.
        program.setFloat(uniforms.fxaaStrength, if (activeSamples == 0) applyQuality.edgeResolve else 0f)
        program.setFloat(uniforms.exposure, environment.exposure)
        program.setInt(uniforms.grainEnabled, if (applyQuality.grain) 1 else 0)
        program.setFloat(uniforms.grainStrength, if (applyQuality.grain) environment.grainStrength * 0.6f else 0f)
        program.setFloat(uniforms.vignetteStrength, environment.vignetteStrength)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTargets.colourTexture())
        program.setInt(uniforms.sceneTexture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backgroundTarget.textureHandle())
        program.setInt(uniforms.backgroundTexture, 1)
    }

    /**
     * Keeps the backdrop's horizon just behind the deck.
     *
     * The studio floor is painted by the shader, so the horizon has to track the camera instead of
     * being a fixed screen fraction: at a low elevation the floor fills most of the frame, and from
     * directly above it should almost disappear. The probe point is nine plinth-widths away along
     * the view direction, which is close enough to the visible horizon at every preset and cheap
     * enough to recompute whenever the camera moves.
     */
    private fun updateBackdropHorizon(camera: CameraRig.Snapshot) {
        camera.positionOf(cameraEye)
        val dx = camera.targetX - cameraEye.x
        val dy = camera.targetY - cameraEye.y
        val dz = camera.targetZ - cameraEye.z
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        probePoint.x = cameraEye.x + dx / length * HORIZON_PROBE_DISTANCE
        probePoint.y = cameraEye.y + dy / length * HORIZON_PROBE_DISTANCE
        probePoint.z = cameraEye.z + dz / length * HORIZON_PROBE_DISTANCE

        Mat4.setPerspective(camera.fovYDegrees, aspect(), NEAR_PLANE, FAR_PLANE, projection, 0)
        Mat4.setLookAt(
            cameraEye.x, cameraEye.y, cameraEye.z,
            camera.targetX, camera.targetY, camera.targetZ,
            0f, 1f, 0f,
            view, 0,
        )
        Mat4.multiply(projection, 0, view, 0, viewProjection, 0)
        Mat4.transformPoint(viewProjection, 0, probePoint.x, probePoint.y, probePoint.z, probePoint)
        // transformPoint divides by w, so the probe (which always lies along the view ray, in front
        // of the camera) needs no behind-camera test.
        environment.horizon = ((probePoint.y + 1f) * 0.5f).coerceIn(0.04f, 0.96f)
    }

    private fun aspect(): Float =
        if (surfaceHeight <= 0) 1f else surfaceWidth.toFloat() / surfaceHeight.toFloat()

    private fun createSolidTexture(red: Int, green: Int, blue: Int, alpha: Int): Int {
        val handles = IntArray(1)
        GLES30.glGenTextures(1, handles, 0)
        val texture = handles[0]
        val pixels = java.nio.ByteBuffer.allocateDirect(4)
        pixels.put(red.toByte()).put(green.toByte()).put(blue.toByte()).put(alpha.toByte())
        pixels.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texture
    }

    private val normalMatrix3 = FloatArray(9)

    /** Cached uniform locations: resolved once per program, never per frame. */
    private class TurntableUniforms(program: ShaderProgram) {
        val model = program.location("uModelMatrix")
        val viewProjection = program.location("uViewProjectionMatrix")
        val normalMatrix = program.location("uNormalMatrix")
        val uvTransform = program.location("uUvTransform")

        val albedo = program.location("uAlbedo")
        val specularTint = program.location("uSpecularTint")
        val roughness = program.location("uRoughness")
        val metallic = program.location("uMetallic")
        val reflectance = program.location("uReflectance")
        val clearcoat = program.location("uClearcoat")
        val fresnelBoost = program.location("uFresnelBoost")
        val opacity = program.location("uOpacity")
        val ambientOcclusion = program.location("uAmbientOcclusion")
        val emissive = program.location("uEmissive")
        val emissionStrength = program.location("uEmissionStrength")

        val useLabelTexture = program.location("uUseLabelTexture")
        val labelTexture = program.location("uLabelTexture")

        val grooveMode = program.location("uGrooveMode")
        val discRadialRange = program.location("uDiscRadialRange")
        val grooveRange = program.location("uGrooveRange")
        val groovePitch = program.location("uGroovePitch")
        val grooveDepth = program.location("uGrooveDepth")
        val grooveStrength = program.location("uGrooveStrength")
        val grooveEccentricity = program.location("uGrooveEccentricity")
        val grooveModulation = program.location("uGrooveModulation")
        val brushedMode = program.location("uBrushedMode")
        val brushedStrength = program.location("uBrushedStrength")
        val brushedScale = program.location("uBrushedScale")
        val detailLevel = program.location("uDetailLevel")

        val cameraPosition = program.location("uCameraPosition")
        val keyDirection = program.location("uKeyDirection")
        val keyColor = program.location("uKeyColor")
        val fillPosition = program.location("uFillPosition")
        val fillColor = program.location("uFillColor")
        val rimDirection = program.location("uRimDirection")
        val rimColor = program.location("uRimColor")
        val ambientFloor = program.location("uAmbientFloor")
        val ambientSky = program.location("uAmbientSky")

        val discShadow0 = program.location("uDiscShadow0")
        val discShadow1 = program.location("uDiscShadow1")
        val rectShadow = program.location("uRectShadow")
        val shadowCeiling = program.location("uShadowCeiling")
        val shadowStrength = program.location("uShadowStrength")
        val shadowBias = program.location("uShadowBias")
    }

    private class BackgroundUniforms(program: ShaderProgram) {
        val resolution = program.location("uResolution")
        val time = program.location("uTime")
        val backdropTop = program.location("uBackdropTop")
        val backdropMid = program.location("uBackdropMid")
        val backdropBottom = program.location("uBackdropBottom")
        val glowColor = program.location("uGlowColor")
        val glowPosition = program.location("uGlowPosition")
        val glowStrength = program.location("uGlowStrength")
        val grainStrength = program.location("uGrainStrength")
        val vignetteStrength = program.location("uVignetteStrength")
        val horizon = program.location("uHorizon")
        val floorFade = program.location("uFloorFade")
        val grainEnabled = program.location("uGrainEnabled")
        val floorEnabled = program.location("uFloorEnabled")
        val animate = program.location("uAnimate")
    }

    private class CompositeUniforms(program: ShaderProgram) {
        val sceneTexture = program.location("uSceneTexture")
        val backgroundTexture = program.location("uBackgroundTexture")
        val resolution = program.location("uResolution")
        val sceneTexel = program.location("uSceneTexel")
        val fxaaStrength = program.location("uFxaaStrength")
        val exposure = program.location("uExposure")
        val grainStrength = program.location("uGrainStrength")
        val vignetteStrength = program.location("uVignetteStrength")
        val grainEnabled = program.location("uGrainEnabled")
    }

    /** Set by the renderer each frame; the backdrop pass reads it. */
    var reducedMotion: Boolean = false

    private companion object {
        const val NEAR_PLANE = 0.05f
        const val FAR_PLANE = 12f
        const val MESH_COUNT = 22
        const val BUTTON_TRAVEL_METRES = 0.0016f
        const val RECORD_INSERTION_TWIST_DEGREES = 9f
        const val HORIZON_PROBE_DISTANCE = 8f
    }
}
