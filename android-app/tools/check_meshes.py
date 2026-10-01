#!/usr/bin/env python3
"""Winding and normal check for the procedural meshes.

The Kotlin mesh builders in ``core/graphics/Meshes.kt`` generate every vertex of the turntable on the
device. A reversed triangle there is not a subtle shading error: the renderer culls back faces, so a
reversed quad is a hole in the plinth, the platter or the record, and it is visible the moment the scene
is drawn.

This script re-implements the same builders in Python — same formulas, same ring order — and checks three
things for every triangle:

1. **Winding against the vertex normals.** ``dot(faceNormal, vertexNormal) > 0`` for every triangle means
   the face's front side is the side the normals point to, which is what "counter-clockwise seen from
   outside" has to mean in practice.
2. **Closedness of the swept shapes.** Every edge of a closed shell must be shared by exactly two
   triangles, so a missing cap or a skipped ring is caught rather than inferred.
3. **Degenerate vertices.** No triangle may have two identical vertices or zero area.

Run it directly (``python3 android-app/tools/check_meshes.py``). It needs no Android toolchain, which is
the point: this is the part of the 3D work that can be verified where the app cannot be built.
"""

from __future__ import annotations

import math
import sys
from collections import Counter
from dataclasses import dataclass

TAU = 2.0 * math.pi


@dataclass
class Mesh:
    positions: list[tuple[float, float, float]]
    normals: list[tuple[float, float, float]]
    uvs: list[tuple[float, float]]
    indices: list[int]

    @property
    def triangles(self):
        for i in range(0, len(self.indices), 3):
            yield self.indices[i], self.indices[i + 1], self.indices[i + 2]


def subtract(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def normalize(v):
    length = math.sqrt(dot(v, v))
    return (0.0, 1.0, 0.0) if length < 1e-9 else (v[0] / length, v[1] / length, v[2] / length)


class Builder:
    def __init__(self):
        self.positions: list[tuple[float, float, float]] = []
        self.normals: list[tuple[float, float, float]] = []
        self.uvs: list[tuple[float, float]] = []
        self.indices: list[int] = []

    def vertex(self, x, y, z, nx, ny, nz, u, v):
        self.positions.append((x, y, z))
        self.normals.append((nx, ny, nz))
        self.uvs.append((u, v))
        return len(self.positions) - 1

    def triangle(self, a, b, c, na, nb, nc):
        i0 = self.vertex(*a, *na, 0.0, 0.0)
        i1 = self.vertex(*b, *nb, 1.0, 0.0)
        i2 = self.vertex(*c, *nc, 0.5, 1.0)
        self.indices += [i0, i1, i2]

    def build(self):
        return Mesh(self.positions, self.normals, self.uvs, self.indices)


def circle_profile(radius, segments):
    return [(math.cos(i / segments * TAU) * radius, math.sin(i / segments * TAU) * radius) for i in range(segments)]


def rounded_rectangle_profile(half_w, half_d, radius, per_corner):
    radius = max(0.0005, min(radius, min(half_w, half_d) - 0.0005))
    corners = [
        (half_w - radius, half_d - radius, 0.0),
        (-(half_w - radius), half_d - radius, math.pi / 2),
        (-(half_w - radius), -(half_d - radius), math.pi),
        (half_w - radius, -(half_d - radius), 1.5 * math.pi),
    ]
    points = []
    for cx, cz, start in corners:
        for step in range(per_corner + 1):
            angle = start + step / per_corner * (math.pi / 2)
            points.append((cx + math.cos(angle) * radius, cz + math.sin(angle) * radius))
    return points


def prism(rings, cap_bottom, cap_top):
    """rings: list of (y, profile). Same construction as Meshes.prism in Kotlin."""
    builder = Builder()
    sides = len(rings[0][1])
    for ring_index in range(len(rings) - 1):
        y0, lower = rings[ring_index]
        y1, upper = rings[ring_index + 1]
        v0 = ring_index / max(1, len(rings) - 1)
        v1 = (ring_index + 1) / max(1, len(rings) - 1)
        for side in range(sides):
            nxt = (side + 1) % sides
            base = builder.vertex(lower[side][0], y0, lower[side][1], 0, 0, 0, 0, v0)
            builder.vertex(upper[side][0], y1, upper[side][1], 0, 0, 0, 0, v1)
            builder.vertex(upper[nxt][0], y1, upper[nxt][1], 0, 0, 0, 1, v1)
            builder.vertex(lower[nxt][0], y0, lower[nxt][1], 0, 0, 0, 1, v0)
            builder.indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    if cap_bottom:
        y, profile = rings[0]
        for side in range(sides):
            nxt = (side + 1) % sides
            builder.triangle((0, y, 0), (profile[side][0], y, profile[side][1]), (profile[nxt][0], y, profile[nxt][1]),
                             (0, -1, 0), (0, -1, 0), (0, -1, 0))
    if cap_top:
        y, profile = rings[-1]
        for side in range(sides):
            nxt = (side + 1) % sides
            builder.triangle((0, y, 0), (profile[nxt][0], y, profile[nxt][1]), (profile[side][0], y, profile[side][1]),
                             (0, 1, 0), (0, 1, 0), (0, 1, 0))
    mesh = builder.build()
    derive_normals(mesh)
    return mesh


def derive_normals(mesh: Mesh):
    accumulated = [[0.0, 0.0, 0.0] for _ in mesh.positions]
    for a, b, c in mesh.triangles:
        pa, pb, pc = mesh.positions[a], mesh.positions[b], mesh.positions[c]
        n = cross(subtract(pb, pa), subtract(pc, pa))
        for index in (a, b, c):
            for axis in range(3):
                accumulated[index][axis] += n[axis]
    mesh.normals = [normalize(tuple(v)) for v in accumulated]


def box(width, height, depth):
    hx, hy, hz = width / 2, height / 2, depth / 2
    builder = Builder()
    quads = [
        ((hx, -hy, hz), (hx, -hy, -hz), (hx, hy, -hz), (hx, hy, hz), (1, 0, 0)),
        ((-hx, -hy, -hz), (-hx, -hy, hz), (-hx, hy, hz), (-hx, hy, -hz), (-1, 0, 0)),
        ((-hx, hy, hz), (hx, hy, hz), (hx, hy, -hz), (-hx, hy, -hz), (0, 1, 0)),
        ((-hx, -hy, -hz), (hx, -hy, -hz), (hx, -hy, hz), (-hx, -hy, hz), (0, -1, 0)),
        ((-hx, -hy, hz), (hx, -hy, hz), (hx, hy, hz), (-hx, hy, hz), (0, 0, 1)),
        ((hx, -hy, -hz), (-hx, -hy, -hz), (-hx, hy, -hz), (hx, hy, -hz), (0, 0, -1)),
    ]
    for a, b, c, d, n in quads:
        i0 = builder.vertex(*a, *n, 0, 0)
        i1 = builder.vertex(*b, *n, 1, 0)
        i2 = builder.vertex(*c, *n, 1, 1)
        i3 = builder.vertex(*d, *n, 0, 1)
        builder.indices += [i0, i1, i2, i0, i2, i3]
    return builder.build()


def beveled_box(width, height, depth, bevel, per_corner=5):
    hx, hy, hz = width / 2, height / 2, depth / 2
    cut = min(bevel, min(hy * 0.9, min(hx, hz) * 0.5))
    radius = max(0.0005, min(bevel * 1.2, min(hx, hz) - 0.001))
    inset = rounded_rectangle_profile(hx - cut, hz - cut, max(0.0005, radius - cut), per_corner)
    full = rounded_rectangle_profile(hx, hz, radius, per_corner)
    return prism([(-hy, inset), (-hy + cut, full), (hy - cut, full), (hy, inset)], True, True)


def cylinder(radius, height, segments):
    return prism([(-height / 2, circle_profile(radius, segments)), (height / 2, circle_profile(radius, segments))], True, True)


def cone(radius, height, segments):
    builder = Builder()
    slope = radius / height
    profile = circle_profile(radius, segments)
    for segment in range(segments):
        nxt = (segment + 1) % segments
        a = (profile[segment][0], -height / 2, profile[segment][1])
        b = (profile[nxt][0], -height / 2, profile[nxt][1])
        na = normalize((a[0], slope * radius, a[2]))
        nb = normalize((b[0], slope * radius, b[2]))
        builder.triangle((0, height / 2, 0), b, a, (0, 1, 0), nb, na)
    for segment in range(segments):
        nxt = (segment + 1) % segments
        builder.triangle(
            (0, -height / 2, 0),
            (profile[segment][0], -height / 2, profile[segment][1]),
            (profile[nxt][0], -height / 2, profile[nxt][1]),
            (0, -1, 0), (0, -1, 0), (0, -1, 0),
        )
    return builder.build()


def disc(outer_radius, inner_radius, thickness, segments):
    builder = Builder()
    top, bottom = thickness / 2, -thickness / 2
    up, down = (0, 1, 0), (0, -1, 0)
    for segment in range(segments):
        a0 = segment / segments * TAU
        a1 = (segment + 1) / segments * TAU
        inner_a = (math.cos(a0) * inner_radius, top, math.sin(a0) * inner_radius)
        inner_b = (math.cos(a1) * inner_radius, top, math.sin(a1) * inner_radius)
        outer_a = (math.cos(a0) * outer_radius, top, math.sin(a0) * outer_radius)
        outer_b = (math.cos(a1) * outer_radius, top, math.sin(a1) * outer_radius)
        builder.triangle(inner_a, outer_b, outer_a, up, up, up)
        builder.triangle(inner_a, inner_b, outer_b, up, up, up)
        builder.triangle((inner_a[0], bottom, inner_a[2]), (outer_a[0], bottom, outer_a[2]), (outer_b[0], bottom, outer_b[2]), down, down, down)
        builder.triangle((inner_a[0], bottom, inner_a[2]), (outer_b[0], bottom, outer_b[2]), (inner_b[0], bottom, inner_b[2]), down, down, down)

        rim_normal_a = (math.cos(a0), 0, math.sin(a0))
        rim_normal_b = (math.cos(a1), 0, math.sin(a1))
        rim_bottom_a = (outer_a[0], bottom, outer_a[2])
        rim_bottom_b = (outer_b[0], bottom, outer_b[2])
        quad(builder, rim_bottom_a, outer_a, outer_b, rim_bottom_b, rim_normal_a, rim_normal_b)

        hole_normal_a = (-math.cos(a0), 0, -math.sin(a0))
        hole_normal_b = (-math.cos(a1), 0, -math.sin(a1))
        hole_bottom_a = (inner_a[0], bottom, inner_a[2])
        hole_bottom_b = (inner_b[0], bottom, inner_b[2])
        quad(builder, hole_bottom_a, hole_bottom_b, inner_b, inner_a, hole_normal_a, hole_normal_b)
    return builder.build()


def ring(outer_radius, inner_radius, thickness, segments):
    builder = Builder()
    top, bottom = thickness / 2, -thickness / 2
    up, down = (0, 1, 0), (0, -1, 0)
    for segment in range(segments):
        a0 = segment / segments * TAU
        a1 = (segment + 1) / segments * TAU
        inner_bottom_a = (math.cos(a0) * inner_radius, bottom, math.sin(a0) * inner_radius)
        inner_bottom_b = (math.cos(a1) * inner_radius, bottom, math.sin(a1) * inner_radius)
        inner_top_a = (math.cos(a0) * inner_radius, top, math.sin(a0) * inner_radius)
        inner_top_b = (math.cos(a1) * inner_radius, top, math.sin(a1) * inner_radius)
        outer_bottom_a = (math.cos(a0) * outer_radius, bottom, math.sin(a0) * outer_radius)
        outer_bottom_b = (math.cos(a1) * outer_radius, bottom, math.sin(a1) * outer_radius)
        outer_top_a = (math.cos(a0) * outer_radius, top, math.sin(a0) * outer_radius)
        outer_top_b = (math.cos(a1) * outer_radius, top, math.sin(a1) * outer_radius)

        normal_out_a = (math.cos(a0), 0, math.sin(a0))
        normal_out_b = (math.cos(a1), 0, math.sin(a1))
        # Bottom-a0, top-a0, top-a1, bottom-a1: the order every wall in this file uses.
        quad(builder, outer_bottom_a, outer_top_a, outer_top_b, outer_bottom_b, normal_out_a, normal_out_b)

        normal_in_a = (-math.cos(a0), 0, -math.sin(a0))
        normal_in_b = (-math.cos(a1), 0, -math.sin(a1))
        quad(builder, inner_bottom_a, inner_bottom_b, inner_top_b, inner_top_a, normal_in_a, normal_in_b)

        builder.triangle(inner_top_a, outer_top_b, outer_top_a, up, up, up)
        builder.triangle(inner_top_a, inner_top_b, outer_top_b, up, up, up)
        builder.triangle(inner_bottom_a, outer_bottom_a, outer_bottom_b, down, down, down)
        builder.triangle(inner_bottom_a, outer_bottom_b, inner_bottom_b, down, down, down)
    return builder.build()


def torus(major_radius, minor_radius, major_segments, minor_segments):
    builder = Builder()

    def point(u, v):
        normal = (math.cos(u) * math.cos(v), math.sin(v), math.sin(u) * math.cos(v))
        return (
            math.cos(u) * major_radius + normal[0] * minor_radius,
            normal[1] * minor_radius,
            math.sin(u) * major_radius + normal[2] * minor_radius,
        ), normalize(normal)

    for major in range(major_segments):
        u0 = major / major_segments * TAU
        u1 = (major + 1) / major_segments * TAU
        for minor in range(minor_segments):
            v0 = minor / minor_segments * TAU
            v1 = (minor + 1) / minor_segments * TAU
            a, na = point(u0, v0)
            b, nb = point(u1, v0)
            c, nc = point(u1, v1)
            d, nd = point(u0, v1)
            quad(builder, a, d, c, b, na, nd, nc, nb)
    return builder.build()


def tube(curve, radius, along_segments, radial_segments):
    builder = Builder()
    points = [bezier(curve, i / along_segments) for i in range(along_segments + 1)]
    previous_normal = None
    previous_tangent = None
    for index, point_v in enumerate(points):
        tangent = normalize(subtract(points[1], point_v) if index == 0 else subtract(point_v, points[index - 1]))
        if previous_normal is None:
            # Start perpendicular to the tangent: the frame is what keeps the tube round, and a normal that
            # begins parallel to the tangent collapses the circle into a line.
            up = (0.0, 1.0, 0.0)
            projected = subtract(up, tuple(c * dot(up, tangent) for c in tangent))
            normal = normalize(projected) if math.sqrt(dot(projected, projected)) > 1e-5 else normalize(cross(tangent, (1.0, 0.0, 0.0)))
        else:
            # Parallel transport: rotate the previous frame by the rotation that took the previous tangent
            # to this one, then re-project so nothing creeps into the tangent direction.
            axis = cross(previous_tangent, tangent)
            length = math.sqrt(dot(axis, axis))
            if length < 1e-7:
                rotated = previous_normal
            else:
                angle = math.acos(max(-1.0, min(1.0, dot(previous_tangent, tangent))))
                rotated = rotate_around(previous_normal, tuple(c / length for c in axis), angle)
            projected = subtract(rotated, tuple(c * dot(rotated, tangent) for c in tangent))
            normal = normalize(projected)
        bi_normal = normalize(cross(tangent, normal))
        previous_normal = normal
        previous_tangent = tangent
        for radial in range(radial_segments):
            angle = radial / radial_segments * TAU
            offset = (
                normal[0] * math.cos(angle) + bi_normal[0] * math.sin(angle),
                normal[1] * math.cos(angle) + bi_normal[1] * math.sin(angle),
                normal[2] * math.cos(angle) + bi_normal[2] * math.sin(angle),
            )
            builder.vertex(
                point_v[0] + offset[0] * radius,
                point_v[1] + offset[1] * radius,
                point_v[2] + offset[2] * radius,
                *offset, index / along_segments, radial / radial_segments,
            )
    for row in range(along_segments):
        for radial in range(radial_segments):
            nxt = (radial + 1) % radial_segments
            a = row * radial_segments + radial
            b = (row + 1) * radial_segments + radial
            c = (row + 1) * radial_segments + nxt
            d = row * radial_segments + nxt
            # Around the tube in the direction the circle is swept, and along it in the direction the curve
            # is travelled: the other order leaves the tube inside-out.
            builder.indices += [a, d, c, a, c, b]
    return builder.build()


def bezier(control, t):
    inverse = 1 - t
    weights = (inverse ** 3, 3 * inverse ** 2 * t, 3 * inverse * t ** 2, t ** 3)
    return tuple(sum(control[i][axis] * weights[i] for i in range(4)) for axis in range(3))


def rotate_around(vector, axis, angle):
    cosine, sine = math.cos(angle), math.sin(angle)
    d = dot(vector, axis)
    c = cross(axis, vector)
    return (
        vector[0] * cosine + c[0] * sine + axis[0] * d * (1 - cosine),
        vector[1] * cosine + c[1] * sine + axis[1] * d * (1 - cosine),
        vector[2] * cosine + c[2] * sine + axis[2] * d * (1 - cosine),
    )


def wedge(width, height, depth, top_inset=None):
    hx, hy, hz = width / 2, height / 2, depth / 2
    top_inset = width * 0.28 if top_inset is None else top_inset
    top_width = max(0.0005, hx - top_inset)
    side_slope = normalize((height, top_inset, 0))
    front_slope = normalize((0, top_inset, height))
    builder = Builder()
    quad(builder, (-hx, -hy, -hz), (hx, -hy, -hz), (hx, -hy, hz), (-hx, -hy, hz), (0, -1, 0))
    quad(builder, (hx, -hy, -hz), (-hx, -hy, -hz), (-top_width, hy, -hz), (top_width, hy, -hz), (0, 0, -1))
    quad(builder, (hx, -hy, -hz), (top_width, hy, -hz), (top_width, hy, hz), (hx, -hy, hz), side_slope)
    quad(builder, (-hx, -hy, hz), (-top_width, hy, hz), (-top_width, hy, -hz), (-hx, -hy, -hz), (-side_slope[0], side_slope[1], side_slope[2]))
    quad(builder, (-hx, -hy, hz), (hx, -hy, hz), (top_width, hy, hz), (-top_width, hy, hz), front_slope)
    quad(builder, (top_width, hy, -hz), (-top_width, hy, -hz), (-top_width, hy, hz), (top_width, hy, hz), (0, 1, 0))
    return builder.build()


def quad(builder, a, b, c, d, na, nb=None, nc=None, nd=None):
    nb = na if nb is None else nb
    nc = na if nc is None else nc
    nd = na if nd is None else nd
    i0 = builder.vertex(*a, *na, 0, 0)
    i1 = builder.vertex(*b, *nb, 1, 0)
    i2 = builder.vertex(*c, *nc, 1, 1)
    i3 = builder.vertex(*d, *nd, 0, 1)
    builder.indices += [i0, i1, i2, i0, i2, i3]


def check(name, mesh, closed=True):
    problems = []
    for a, b, c in mesh.triangles:
        pa, pb, pc = mesh.positions[a], mesh.positions[b], mesh.positions[c]
        face = cross(subtract(pb, pa), subtract(pc, pa))
        area = math.sqrt(dot(face, face))
        if area < 1e-12:
            problems.append(f"degenerate triangle at {a}/{b}/{c}")
            continue
        for index in (a, b, c):
            alignment = dot(normalize(face), normalize(mesh.normals[index]))
            if alignment <= 0.0:
                problems.append(
                    f"face {a}/{b}/{c} points opposite the vertex normal at {index} (dot={alignment:.3f})"
                )
                break
        if len({pa, pb, pc}) < 3:
            problems.append(f"repeated vertex in triangle {a}/{b}/{c}")

    if closed:
        # Closedness is measured by position rather than by index: a box gives every face its own four
        # vertices so that its corners stay sharp, and an index-based edge count would call that an open
        # shell. Rounding to a micron is what makes the two faces of a seam meet.
        def key(index):
            x, y, z = mesh.positions[index]
            return (round(x, 6), round(y, 6), round(z, 6))

        edges = Counter()
        for a, b, c in mesh.triangles:
            for u, v in ((key(a), key(b)), (key(b), key(c)), (key(c), key(a))):
                edges[(min(u, v), max(u, v))] += 1
        open_edges = [edge for edge, count in edges.items() if count != 2]
        if open_edges:
            problems.append(f"{len(open_edges)} edge(s) are not shared by exactly two triangles")

    status = "ok  " if not problems else "FAIL"
    print(f"[{status}] {name:22s} {len(mesh.positions):5d} verts  {len(mesh.indices) // 3:5d} tris")
    for problem in problems[:6]:
        print(f"         · {problem}")
    return not problems


def main():
    arm_curve = [(0, 0, 0), (-0.10, 0.004, 0.10), (-0.18, -0.012, 0.20), (-0.26, -0.02, 0.31)]
    results = [
        check("box", box(0.46, 0.075, 0.40)),
        check("beveledBox", beveled_box(0.46, 0.075, 0.40, 0.014)),
        check("cylinder", cylinder(0.165, 0.016, 48), closed=False),
        check("taperedCylinder", prism([(-0.012, circle_profile(0.018, 24)), (0.012, circle_profile(0.013, 24))], True, True)),
        check("cone", cone(0.03, 0.10, 20)),
        check("disc", disc(0.152, 0.006, 0.0022, 48), closed=False),
        check("ring", ring(0.15, 0.055, 0.0013, 48)),
        check("torus", torus(0.016, 0.011, 28, 12)),
        check("tube", tube(arm_curve, 0.0055, 24, 12), closed=False),
        check("wedge", wedge(0.03, 0.02, 0.014)),
    ]
    print()
    if all(results):
        print(f"{len(results)}/{len(results)} mesh builders produce outward-facing geometry")
        return 0
    print(f"{sum(1 for r in results if not r)} of {len(results)} mesh builders have winding problems")
    return 1


if __name__ == "__main__":
    sys.exit(main())
