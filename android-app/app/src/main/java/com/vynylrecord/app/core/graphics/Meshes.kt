package com.vynylrecord.app.core.graphics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry of the deck, generated on the device.
 *
 * Every shape here is built from vertices at load time — there is no model file, no download and no mesh
 * asset in the APK. That is partly the offline requirement and partly a better fit for the object: a
 * procedural deck can be rebuilt at three levels of detail, its parts can be given different segment counts
 * on the phone that needs them, and there is no chance of a missing asset producing a hole in the scene.
 *
 * ## Conventions
 *
 * * **Y is up.** The plinth lies in the XZ plane and grows in Y; the platter turns about the Y axis.
 * * **The origin is the centre of each part**, so placing a part is a translate and turning it is a rotation
 *   about its own middle.
 * * **Front faces are counter-clockwise seen from outside**, and the renderer culls back faces, so a
 *   reversed quad shows up as a hole rather than as a subtle shading error.
 * * **Sharp shapes carry their own normals** (box, wedge, disc); **swept shapes derive theirs from the
 *   faces**, which is what keeps a cylinder smooth and a chamfer readable. Caps never share vertices with
 *   walls, so a cylinder's rim does not go soft.
 *
 * ## Level of detail
 *
 * Nothing is hard-coded to one device: every radial shape takes a segment count that the scene picks from
 * the graphics quality setting. A 60-segment platter on low and 128 on high is a small memory difference and
 * a real GPU-budget one.
 */
object Meshes {

    /** A box. Six quads, no shared vertices, so every corner stays a corner. */
    fun box(width: Float, height: Float, depth: Float): MeshData {
        val hx = width / 2f
        val hy = height / 2f
        val hz = depth / 2f
        val builder = MeshBuilder()
        builder.pushQuad(Vec3(hx, -hy, hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(hx, hy, hz), Vec3(1f, 0f, 0f))
        builder.pushQuad(Vec3(-hx, -hy, -hz), Vec3(-hx, -hy, hz), Vec3(-hx, hy, hz), Vec3(-hx, hy, -hz), Vec3(-1f, 0f, 0f))
        builder.pushQuad(Vec3(-hx, hy, hz), Vec3(hx, hy, hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz), Vec3(0f, 1f, 0f))
        builder.pushQuad(Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, -hy, hz), Vec3(-hx, -hy, hz), Vec3(0f, -1f, 0f))
        builder.pushQuad(Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz), Vec3(0f, 0f, 1f))
        builder.pushQuad(Vec3(hx, -hy, -hz), Vec3(-hx, -hy, -hz), Vec3(-hx, hy, -hz), Vec3(hx, hy, -hz), Vec3(0f, 0f, -1f))
        return builder.build()
    }

    /**
     * A box with its top and bottom edges chamfered.
     *
     * The plinth is why this exists: a lacquered wooden box has a bevel on every edge, and the bevel is what
     * catches the lamp and describes the object's thickness. Built as four rings of a rounded rectangle —
     * full size in the middle, inset at the top and bottom — so the chamfer is real geometry.
     */
    fun beveledBox(
        width: Float,
        height: Float,
        depth: Float,
        bevel: Float,
        cornerRadius: Float = bevel * 1.2f,
        cornersPerCorner: Int = 5,
    ): MeshData {
        val hx = width / 2f
        val hy = height / 2f
        val hz = depth / 2f
        val cut = min(bevel, min(hy * 0.9f, min(hx, hz) * 0.5f))
        val radius = min(cornerRadius, min(hx, hz) - 0.001f).coerceAtLeast(0.0005f)
        val inset = roundedRectangleProfile(hx - cut, hz - cut, max(0.0005f, radius - cut), cornersPerCorner)
        val full = roundedRectangleProfile(hx, hz, radius, cornersPerCorner)
        return prism(
            rings = listOf(
                Slice(-hy, inset),
                Slice(-hy + cut, full),
                Slice(hy - cut, full),
                Slice(hy, inset),
            ),
            capBottom = true,
            capTop = true,
        )
    }

    /** A cylinder about the Y axis, with flat caps. */
    fun cylinder(radius: Float, height: Float, segments: Int): MeshData = prism(
        rings = listOf(
            Slice(-height / 2f, circleProfile(radius, segments)),
            Slice(height / 2f, circleProfile(radius, segments)),
        ),
        capBottom = true,
        capTop = true,
    )

    /** A cylinder whose ends differ: the feet, the spindle, the arm's post. */
    fun taperedCylinder(bottomRadius: Float, topRadius: Float, height: Float, segments: Int): MeshData = prism(
        rings = listOf(
            Slice(-height / 2f, circleProfile(bottomRadius, segments)),
            Slice(height / 2f, circleProfile(topRadius, segments)),
        ),
        capBottom = true,
        capTop = true,
    )

    /** A cone: the lamp's shade, and the stylus' taper. */
    fun cone(radius: Float, height: Float, segments: Int): MeshData {
        val builder = MeshBuilder()
        val slope = radius / height
        val ring = circleProfile(radius, segments)
        // The apex first, then the base ring, then the base cap; each set of vertices is its own.
        for (segment in 0 until segments) {
            val next = (segment + 1) % segments
            val a = Vec3(ring[segment].first, -height / 2f, ring[segment].second)
            val b = Vec3(ring[next].first, -height / 2f, ring[next].second)
            val normalA = Vec3(a.x, slope * radius, a.z).normalize()
            val normalB = Vec3(b.x, slope * radius, b.z).normalize()
            builder.pushTriangle(
                Vec3(0f, height / 2f, 0f),
                b,
                a,
                normalA,
                normalB,
                Vec3(0f, 1f, 0f),
            )
        }
        for (segment in 0 until segments) {
            val next = (segment + 1) % segments
            builder.pushTriangle(
                Vec3(0f, -height / 2f, 0f),
                Vec3(ring[segment].first, -height / 2f, ring[segment].second),
                Vec3(ring[next].first, -height / 2f, ring[next].second),
                Vec3(0f, -1f, 0f),
                Vec3(0f, -1f, 0f),
                Vec3(0f, -1f, 0f),
            )
        }
        return builder.build()
    }

    /**
     * A disc with a hole in the middle: the record itself.
     *
     * The faces are annuli of triangles rather than fans, because a fan would need a centre vertex and the
     * centre is the hole. The outer and inner rims are walls with their own normals, so the record's edge
     * catches the light as a real thickness does.
     */
    fun disc(outerRadius: Float, innerRadius: Float, thickness: Float, segments: Int): MeshData {
        val builder = MeshBuilder()
        val top = thickness / 2f
        val bottom = -thickness / 2f
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        for (segment in 0 until segments) {
            val a0 = (segment.toFloat() / segments) * TWO_PI
            val a1 = ((segment + 1).toFloat() / segments) * TWO_PI
            val innerA = Vec3(cos(a0) * innerRadius, top, sin(a0) * innerRadius)
            val innerB = Vec3(cos(a1) * innerRadius, top, sin(a1) * innerRadius)
            val outerA = Vec3(cos(a0) * outerRadius, top, sin(a0) * outerRadius)
            val outerB = Vec3(cos(a1) * outerRadius, top, sin(a1) * outerRadius)
            // Top: counter-clockwise from above, so the face points up.
            builder.pushTriangle(innerA, outerB, outerA, up, up, up)
            builder.pushTriangle(innerA, innerB, outerB, up, up, up)
            // Bottom, wound the other way.
            builder.pushTriangle(innerA.copyAt(bottom), outerA.copyAt(bottom), outerB.copyAt(bottom), down, down, down)
            builder.pushTriangle(innerA.copyAt(bottom), outerB.copyAt(bottom), innerB.copyAt(bottom), down, down, down)

            // The outer rim: a wall at the record's edge, with the normal pointing radially outward.
            val rimBottomA = Vec3(outerA.x, bottom, outerA.z)
            val rimBottomB = Vec3(outerB.x, bottom, outerB.z)
            val rimNormalA = Vec3(cos(a0), 0f, sin(a0))
            val rimNormalB = Vec3(cos(a1), 0f, sin(a1))
            builder.pushQuad(rimBottomA, rimBottomB, outerB, outerA, rimNormalB, rimNormalA)

            // The spindle hole's wall, facing inward.
            val holeBottomA = Vec3(innerA.x, bottom, innerA.z)
            val holeBottomB = Vec3(innerB.x, bottom, innerB.z)
            val holeNormalA = Vec3(-cos(a0), 0f, -sin(a0))
            val holeNormalB = Vec3(-cos(a1), 0f, -sin(a1))
            builder.pushQuad(holeBottomA, innerA, innerB, holeBottomB, holeNormalA, holeNormalB)
        }
        return builder.build()
    }

    /**
     * A flat annulus with a real thickness: the sheen over the grooves and the platter's rim.
     *
     * Four faces — outer wall, top, inner wall, bottom — each built as its own strip of quads with explicit
     * normals, which is what keeps the top of a thin ring flat instead of rounded at the rim.
     */
    fun ring(outerRadius: Float, innerRadius: Float, thickness: Float, segments: Int): MeshData {
        val builder = MeshBuilder()
        val top = thickness / 2f
        val bottom = -thickness / 2f
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        for (segment in 0 until segments) {
            val a0 = (segment.toFloat() / segments) * TWO_PI
            val a1 = ((segment + 1).toFloat() / segments) * TWO_PI
            val innerBottomA = Vec3(cos(a0) * innerRadius, bottom, sin(a0) * innerRadius)
            val innerBottomB = Vec3(cos(a1) * innerRadius, bottom, sin(a1) * innerRadius)
            val innerTopA = Vec3(cos(a0) * innerRadius, top, sin(a0) * innerRadius)
            val innerTopB = Vec3(cos(a1) * innerRadius, top, sin(a1) * innerRadius)
            val outerBottomA = Vec3(cos(a0) * outerRadius, bottom, sin(a0) * outerRadius)
            val outerBottomB = Vec3(cos(a1) * outerRadius, bottom, sin(a1) * outerRadius)
            val outerTopA = Vec3(cos(a0) * outerRadius, top, sin(a0) * outerRadius)
            val outerTopB = Vec3(cos(a1) * outerRadius, top, sin(a1) * outerRadius)

            val normalOutA = Vec3(cos(a0), 0f, sin(a0))
            val normalOutB = Vec3(cos(a1), 0f, sin(a1))
            // Bottom-a0, top-a0, top-a1, bottom-a1 — the order every wall in this file uses.
            builder.pushQuad(outerBottomA, outerTopA, outerTopB, outerBottomB, normalOutA, normalOutA, normalOutB, normalOutB)

            val normalInA = Vec3(-cos(a0), 0f, -sin(a0))
            val normalInB = Vec3(-cos(a1), 0f, -sin(a1))
            // The inner wall faces the other way, so its winding is the mirror of the outer one's.
            builder.pushQuad(innerBottomA, innerBottomB, innerTopB, innerTopA, normalInA, normalInB, normalInB, normalInA)

            // The top face, counter-clockwise seen from above; the bottom face, its mirror.
            builder.pushTriangle(innerTopA, outerTopB, outerTopA, up, up, up)
            builder.pushTriangle(innerTopA, innerTopB, outerTopB, up, up, up)
            builder.pushTriangle(innerBottomA, outerBottomA, outerBottomB, down, down, down)
            builder.pushTriangle(innerBottomA, outerBottomB, innerBottomB, down, down, down)
        }
        return builder.build()
    }

    /** A torus: the counterweight's ring and the arm's pivot collar. */
    fun torus(majorRadius: Float, minorRadius: Float, majorSegments: Int, minorSegments: Int): MeshData {
        val builder = MeshBuilder()
        for (major in 0 until majorSegments) {
            val u0 = (major.toFloat() / majorSegments) * TWO_PI
            val u1 = ((major + 1).toFloat() / majorSegments) * TWO_PI
            for (minor in 0 until minorSegments) {
                val v0 = (minor.toFloat() / minorSegments) * TWO_PI
                val v1 = ((minor + 1).toFloat() / minorSegments) * TWO_PI

                fun point(u: Float, v: Float): Vec3 {
                    val normalX = cos(u) * cos(v)
                    val normalY = sin(v)
                    val normalZ = sin(u) * cos(v)
                    return Vec3(
                        cos(u) * majorRadius + normalX * minorRadius,
                        normalY * minorRadius,
                        sin(u) * majorRadius + normalZ * minorRadius,
                    )
                }

                fun normal(u: Float, v: Float): Vec3 = Vec3(cos(u) * cos(v), sin(v), sin(u) * cos(v)).normalize()

                val a = point(u0, v0)
                val b = point(u1, v0)
                val c = point(u1, v1)
                val d = point(u0, v1)
                // Around the tube and along the ring in the same order as the tube builder, which is the
                // order that leaves the surface facing outwards.
                builder.pushQuad(a, d, c, b, normal(u0, v0), normal(u0, v1), normal(u1, v1), normal(u1, v0))
            }
        }
        return builder.build()
    }

    /**
     * A swept tube along a cubic Bézier: the tonearm.
     *
     * A real tonearm is not straight — it is bent so the headshell can reach the inner grooves while the pivot
     * stays out of the platter's way. The curve is sampled by evaluating the Bézier, and a circle is swept
     * along it with a parallel-transported frame, which is what stops the tube from twisting as it bends.
     */
    fun tube(curve: List<Vec3>, radius: Float, alongSegments: Int, radialSegments: Int): MeshData {
        require(curve.size == 4) { "a tonearm's curve is a cubic Bézier: four control points" }
        val builder = MeshBuilder()
        val frames = Array(alongSegments + 1) { index -> bezier(curve, index.toFloat() / alongSegments) }
        var previousNormal: Vec3? = null
        var previousTangent: Vec3? = null

        for (index in frames.indices) {
            val point = frames[index]
            val tangent = when {
                index == 0 -> frames[1].minus(point).normalized()
                else -> point.minus(frames[index - 1]).normalized()
            }
            val normal = if (previousNormal == null) {
                // The frame has to start perpendicular to the first tangent. Starting from an arbitrary axis
                // — say "up" — collapses the circle onto the tangent and the tube comes out as a ribbon.
                val up = Vec3(0f, 1f, 0f)
                val projected = up.minus(tangent.scaled(up.dot(tangent)))
                if (projected.length > 1e-5f) projected.normalized() else tangent.cross(Vec3(1f, 0f, 0f)).normalized()
            } else {
                // Parallel transport: rotate the previous frame by the rotation that took the previous
                // tangent to this one, then project out anything that crept into the tangent direction.
                val axis = previousTangent!!.cross(tangent)
                val rotated = if (axis.length < 1e-7f) {
                    previousNormal
                } else {
                    rotateAround(
                        previousNormal,
                        axis.normalized(),
                        acos(previousTangent.dot(tangent).coerceIn(-1f, 1f)),
                    )
                }
                rotated.minus(tangent.scaled(rotated.dot(tangent))).normalized()
            }
            val biNormal = tangent.cross(normal).normalized()
            previousNormal = normal
            previousTangent = tangent
            for (radial in 0 until radialSegments) {
                val angle = (radial.toFloat() / radialSegments) * TWO_PI
                val offset = Vec3(
                    normal.x * cos(angle) + biNormal.x * sin(angle),
                    normal.y * cos(angle) + biNormal.y * sin(angle),
                    normal.z * cos(angle) + biNormal.z * sin(angle),
                )
                builder.vertex(
                    point.x + offset.x * radius,
                    point.y + offset.y * radius,
                    point.z + offset.z * radius,
                    offset.x,
                    offset.y,
                    offset.z,
                    index.toFloat() / alongSegments,
                    radial.toFloat() / radialSegments,
                )
            }
        }

        val rowLength = radialSegments
        for (row in 0 until alongSegments) {
            for (radial in 0 until radialSegments) {
                val next = (radial + 1) % radialSegments
                val a = (row * rowLength + radial).toShort()
                val b = ((row + 1) * rowLength + radial).toShort()
                val c = ((row + 1) * rowLength + next).toShort()
                val d = (row * rowLength + next).toShort()
                // Around the tube in the direction the circle is swept, then along the curve. The other
                // order leaves the tube inside-out.
                builder.pushIndices(a, d, c, a, c, b)
            }
        }
        return builder.build()
    }

    /**
     * A wedge: a box whose top face is shorter than its bottom.
     *
     * The headshell and the plinth's control panel are both wedges. The shape is generated rather than faked
     * with a rotated box because the slanted faces need their own normals.
     */
    fun wedge(width: Float, height: Float, depth: Float, topInset: Float = width * 0.28f): MeshData {
        val hx = width / 2f
        val hy = height / 2f
        val hz = depth / 2f
        val topWidth = max(0.0005f, hx - topInset)
        val builder = MeshBuilder()
        val sideSlope = Vec3(height, topInset, 0f).normalize()
        val frontSlope = Vec3(0f, topInset, height).normalize()
        builder.pushQuad(Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, -hy, hz), Vec3(-hx, -hy, hz), Vec3(0f, -1f, 0f))
        builder.pushQuad(Vec3(hx, -hy, -hz), Vec3(-hx, -hy, -hz), Vec3(-topWidth, hy, -hz), Vec3(topWidth, hy, -hz), Vec3(0f, 0f, -1f))
        builder.pushQuad(Vec3(hx, -hy, -hz), Vec3(topWidth, hy, -hz), Vec3(topWidth, hy, hz), Vec3(hx, -hy, hz), sideSlope)
        builder.pushQuad(Vec3(-hx, -hy, hz), Vec3(-topWidth, hy, hz), Vec3(-topWidth, hy, -hz), Vec3(-hx, -hy, -hz), Vec3(-sideSlope.x, sideSlope.y, sideSlope.z))
        builder.pushQuad(Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(topWidth, hy, hz), Vec3(-topWidth, hy, hz), frontSlope)
        builder.pushQuad(Vec3(topWidth, hy, -hz), Vec3(-topWidth, hy, -hz), Vec3(-topWidth, hy, hz), Vec3(topWidth, hy, hz), Vec3(0f, 1f, 0f))
        return builder.build()
    }

    /** A knob: a short cylinder with a fluted edge, so its rotation is visible. */
    fun knob(radius: Float, height: Float, segments: Int): MeshData {
        val flutes = segments.coerceAtLeast(8) * 2
        val profile = (0 until flutes).map { index ->
            val angle = (index.toFloat() / flutes) * TWO_PI
            val r = if (index % 2 == 0) radius else radius * 0.955f
            Pair(cos(angle) * r, sin(angle) * r)
        }
        return prism(
            rings = listOf(
                Slice(-height / 2f, profile),
                Slice(height * 0.28f, profile),
                Slice(height / 2f, profile.map { (x, z) -> Pair(x * 0.94f, z * 0.94f) }),
            ),
            capBottom = true,
            capTop = true,
        )
    }

    /** The stylus: a tiny tapered cone, which is all a needle is at this scale. */
    fun needle(height: Float): MeshData = cone(radius = height * 0.22f, height = height, segments = 10)

    // ------------------------------------------------------------------ internals

    /** One horizontal slice of a swept shape: a height and the profile swept at it. */
    private class Slice(val y: Float, val profile: List<Pair<Float, Float>>)

    /**
     * A profile swept between slices — the shape behind every cylinder, ring, box and knob here.
     *
     * Walls are strips of quads with their own vertices; caps are fans from a centre vertex with vertices of
     * their own as well. Nothing is shared between a wall and a cap, so [MeshData.withDerivedNormals] can be
     * used freely: a cylinder comes out smooth around its circumference and sharp at its rim.
     */
    private fun prism(rings: List<Slice>, capBottom: Boolean, capTop: Boolean): MeshData {
        val builder = MeshBuilder()
        val sides = rings.first().profile.size
        val vCoordinate = { index: Int -> index.toFloat() / (rings.size - 1).coerceAtLeast(1) }

        for (ringIndex in 0 until rings.size - 1) {
            val lower = rings[ringIndex]
            val upper = rings[ringIndex + 1]
            val v0 = vCoordinate(ringIndex)
            val v1 = vCoordinate(ringIndex + 1)
            for (side in 0 until sides) {
                val next = (side + 1) % sides
                builder.vertex(lower.profile[side].first, lower.y, lower.profile[side].second, 0f, 0f, 0f, 0f, v0)
                builder.vertex(upper.profile[side].first, upper.y, upper.profile[side].second, 0f, 0f, 0f, 0f, v1)
                builder.vertex(upper.profile[next].first, upper.y, upper.profile[next].second, 0f, 0f, 0f, 1f, v1)
                builder.vertex(lower.profile[next].first, lower.y, lower.profile[next].second, 0f, 0f, 0f, 1f, v0)
                builder.pushIndices(0, 1, 2, 0, 2, 3, relative = true)
            }
        }

        if (capBottom) {
            val slice = rings.first()
            for (side in 0 until sides) {
                val next = (side + 1) % sides
                builder.pushTriangle(
                    Vec3(0f, slice.y, 0f),
                    Vec3(slice.profile[side].first, slice.y, slice.profile[side].second),
                    Vec3(slice.profile[next].first, slice.y, slice.profile[next].second),
                    Vec3(0f, -1f, 0f),
                    Vec3(0f, -1f, 0f),
                    Vec3(0f, -1f, 0f),
                )
            }
        }
        if (capTop) {
            val slice = rings.last()
            for (side in 0 until sides) {
                val next = (side + 1) % sides
                builder.pushTriangle(
                    Vec3(0f, slice.y, 0f),
                    Vec3(slice.profile[next].first, slice.y, slice.profile[next].second),
                    Vec3(slice.profile[side].first, slice.y, slice.profile[side].second),
                    Vec3(0f, 1f, 0f),
                    Vec3(0f, 1f, 0f),
                    Vec3(0f, 1f, 0f),
                )
            }
        }
        return builder.build().withDerivedNormals()
    }

    /** A circle as a profile. The closing point is implied by the caller's modulo. */
    private fun circleProfile(radius: Float, segments: Int): List<Pair<Float, Float>> {
        val count = segments.coerceAtLeast(3)
        return (0 until count).map { segment ->
            val angle = (segment.toFloat() / count) * TWO_PI
            Pair(cos(angle) * radius, sin(angle) * radius)
        }
    }

    /** A rounded rectangle as a profile: four arcs joined by straight runs. */
    private fun roundedRectangleProfile(
        halfWidth: Float,
        halfDepth: Float,
        cornerRadius: Float,
        cornersPerCorner: Int,
    ): List<Pair<Float, Float>> {
        val radius = min(cornerRadius, min(halfWidth, halfDepth) - 0.0005f).coerceAtLeast(0.0005f)
        val corners = listOf(
            Triple(halfWidth - radius, halfDepth - radius, 0f),
            Triple(-(halfWidth - radius), halfDepth - radius, PI.toFloat() / 2f),
            Triple(-(halfWidth - radius), -(halfDepth - radius), PI.toFloat()),
            Triple(halfWidth - radius, -(halfDepth - radius), PI.toFloat() * 1.5f),
        )
        val points = mutableListOf<Pair<Float, Float>>()
        for ((centreX, centreZ, startAngle) in corners) {
            for (step in 0..cornersPerCorner) {
                val angle = startAngle + (step.toFloat() / cornersPerCorner) * (PI.toFloat() / 2f)
                points += Pair(centreX + cos(angle) * radius, centreZ + sin(angle) * radius)
            }
        }
        return points
    }

    /** Evaluates a cubic Bézier at [t]. */
    private fun bezier(control: List<Vec3>, t: Float): Vec3 {
        val inverse = 1f - t
        val a = inverse * inverse * inverse
        val b = 3f * inverse * inverse * t
        val c = 3f * inverse * t * t
        val d = t * t * t
        return Vec3(
            control[0].x * a + control[1].x * b + control[2].x * c + control[3].x * d,
            control[0].y * a + control[1].y * b + control[2].y * c + control[3].y * d,
            control[0].z * a + control[1].z * b + control[2].z * c + control[3].z * d,
        )
    }

    /** Rotates [vector] about [axis] by [angle] radians. */
    private fun rotateAround(vector: Vec3, axis: Vec3, angle: Float): Vec3 {
        val cosine = cos(angle)
        val sine = sin(angle)
        val dot = vector.dot(axis)
        val crossX = axis.y * vector.z - axis.z * vector.y
        val crossY = axis.z * vector.x - axis.x * vector.z
        val crossZ = axis.x * vector.y - axis.y * vector.x
        return Vec3(
            vector.x * cosine + crossX * sine + axis.x * dot * (1f - cosine),
            vector.y * cosine + crossY * sine + axis.y * dot * (1f - cosine),
            vector.z * cosine + crossZ * sine + axis.z * dot * (1f - cosine),
        )
    }

    private const val TWO_PI = (2.0 * PI).toFloat()
}

/** A mesh in memory: positions, normals, texture coordinates and 16-bit indices. */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val indexCount: Int get() = indices.size

    /**
     * Recomputes normals by summing the faces that share each vertex.
     *
     * Used by the swept shapes, where an analytic normal is awkward — the chamfer of a beveled box, the
     * knurl of a knob — and where a faceted look would be wrong. Shapes with sharp edges carry their own
     * normals and never call this.
     */
    fun withDerivedNormals(): MeshData {
        normals.fill(0f)
        var index = 0
        while (index + 2 < indices.size) {
            val a = indices[index] * 3
            val b = indices[index + 1] * 3
            val c = indices[index + 2] * 3
            val abX = positions[b] - positions[a]
            val abY = positions[b + 1] - positions[a + 1]
            val abZ = positions[b + 2] - positions[a + 2]
            val acX = positions[c] - positions[a]
            val acY = positions[c + 1] - positions[a + 1]
            val acZ = positions[c + 2] - positions[a + 2]
            val nx = abY * acZ - abZ * acY
            val ny = abZ * acX - abX * acZ
            val nz = abX * acY - abY * acX
            for (vertex in intArrayOf(a, b, c)) {
                normals[vertex] += nx
                normals[vertex + 1] += ny
                normals[vertex + 2] += nz
            }
            index += 3
        }
        for (vertex in 0 until vertexCount) {
            val offset = vertex * 3
            val x = normals[offset]
            val y = normals[offset + 1]
            val z = normals[offset + 2]
            val length = sqrt(x * x + y * y + z * z)
            if (length > 1e-6f) {
                normals[offset] = x / length
                normals[offset + 1] = y / length
                normals[offset + 2] = z / length
            } else {
                normals[offset] = 0f
                normals[offset + 1] = 1f
                normals[offset + 2] = 0f
            }
        }
        return this
    }

    /** A sanity check the scene runs once at load: a malformed mesh is a much better error than a hole. */
    val isValid: Boolean
        get() = positions.size % 3 == 0 &&
            positions.isNotEmpty() &&
            normals.size == positions.size &&
            uvs.size == vertexCount * 2 &&
            indices.size % 3 == 0 &&
            indices.isNotEmpty() &&
            indices.all { it >= 0 && it < vertexCount } &&
            positions.all { it.isFinite() && abs(it) < 100f }

    /** The bounding radius of the mesh, used to place parts and to check sizes in tests. */
    fun boundingRadius(): Float {
        var maximum = 0f
        for (vertex in 0 until vertexCount) {
            val offset = vertex * 3
            val distance = sqrt(
                positions[offset] * positions[offset] +
                    positions[offset + 1] * positions[offset + 1] +
                    positions[offset + 2] * positions[offset + 2],
            )
            maximum = max(maximum, distance)
        }
        return maximum
    }
}

/** A tiny accumulator for hand-built geometry. */
private class MeshBuilder {
    private val positions = mutableListOf<Float>()
    private val normals = mutableListOf<Float>()
    private val uvs = mutableListOf<Float>()
    private val indices = mutableListOf<Short>()

    /** The index of the next vertex to be added. */
    private val nextIndex: Int get() = positions.size / 3

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): MeshBuilder {
        positions += listOf(x, y, z)
        normals += listOf(nx, ny, nz)
        uvs += listOf(u, v)
        return this
    }

    fun pushIndices(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, relative: Boolean = false): MeshBuilder {
        val base = if (relative) nextIndex - 4 else 0
        for (value in intArrayOf(a, b, c, d, e, f)) {
            indices += (base + value).toShort()
        }
        return this
    }

    fun pushTriangle(a: Vec3, b: Vec3, c: Vec3, normalA: Vec3, normalB: Vec3, normalC: Vec3): MeshBuilder {
        val base = nextIndex
        vertex(a.x, a.y, a.z, normalA.x, normalA.y, normalA.z, 0f, 0f)
        vertex(b.x, b.y, b.z, normalB.x, normalB.y, normalB.z, 1f, 0f)
        vertex(c.x, c.y, c.z, normalC.x, normalC.y, normalC.z, 0.5f, 1f)
        pushIndices(base, base + 1, base + 2, base, base + 1, base + 2)
        return this
    }

    /** A quad from four corners with per-corner normals, as two triangles. */
    fun pushQuad(
        a: Vec3,
        b: Vec3,
        c: Vec3,
        d: Vec3,
        normalA: Vec3,
        normalB: Vec3 = normalA,
        normalC: Vec3 = normalA,
        normalD: Vec3 = normalA,
    ): MeshBuilder {
        val normalB2 = normalB
        val normalC2 = normalC
        val normalD2 = normalD
        val base = nextIndex
        vertex(a.x, a.y, a.z, normalA.x, normalA.y, normalA.z, 0f, 0f)
        vertex(b.x, b.y, b.z, normalB2.x, normalB2.y, normalB2.z, 1f, 0f)
        vertex(c.x, c.y, c.z, normalC2.x, normalC2.y, normalC2.z, 1f, 1f)
        vertex(d.x, d.y, d.z, normalD2.x, normalD2.y, normalD2.z, 0f, 1f)
        indices += base.toShort()
        indices += (base + 1).toShort()
        indices += (base + 2).toShort()
        indices += base.toShort()
        indices += (base + 2).toShort()
        indices += (base + 3).toShort()
        return this
    }

    fun build(): MeshData = MeshData(
        positions.toFloatArray(),
        normals.toFloatArray(),
        uvs.toFloatArray(),
        indices.toShortArray(),
    )
}

/** Small vector helpers the mesh builders need and [Vec3] does not carry. */
private fun Vec3.minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

private fun Vec3.normalized(): Vec3 {
    val length = sqrt(x * x + y * y + z * z)
    return if (length < 1e-8f) Vec3(0f, 1f, 0f) else Vec3(x / length, y / length, z / length)
}

/** A copy of this vector at a different height, used to build a disc's bottom face from its top. */
private fun Vec3.copyAt(y: Float): Vec3 = Vec3(x, y, z)

/**
 * The cross product, without an output parameter.
 *
 * [Vec3] carries a three-argument `cross` that writes into a scratch vector, which is what a per-frame
 * caller wants. The mesh builders run once, at load, and read better without the scratch.
 */
private fun Vec3.cross(other: Vec3): Vec3 = Vec3(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x,
)

/** Scales a vector, for the projections the tube's frames need. */
private fun Vec3.scaled(factor: Float): Vec3 = Vec3(x * factor, y * factor, z * factor)

/** The Euclidean length, so a guard can ask "is this axis long enough to rotate about?". */
private val Vec3.length: Float get() = sqrt(x * x + y * y + z * z)
