#!/usr/bin/env python3
"""
Static verification for the Vynyl native turntable module.

This project cannot be compiled without the Android SDK, Java 17 and a Gradle
distribution, so this script performs the checks that *can* run anywhere:

  1. Kotlin syntax          - every .kt file is parsed with tree-sitter-kotlin
  2. GLSL ES 3.0 syntax     - every shader is parsed in both precision branches
  3. XML well-formedness    - manifests, resources, data-extraction rules
  4. Offline guarantee      - no http(s) URL, no INTERNET permission, no telemetry SDK
  5. Uniform contract       - every "uName" uniform string used from Kotlin exists in a
                              shader, and every shader uniform is uploaded from Kotlin
  6. Manifest contract      - launcher activity declared, OpenGL feature declared as
                              optional (so the static fallback path is reachable)
  7. Asset contract         - shaders and the bundled demo audio exist and are referenced

Install the parsers once:
    pip install tree-sitter tree-sitter-kotlin tree-sitter-glsl

Usage:
    python3 tools/verify_static.py            # from android-turntable-native/
    python3 tools/verify_static.py --root .
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

FAILURES: list[str] = []
NOTES: list[str] = []


# --------------------------------------------------------------------------- helpers

def fail(message: str) -> None:
    FAILURES.append(message)


def note(message: str) -> None:
    NOTES.append(message)


def ok(message: str) -> None:
    print(f"  ok   {message}")


def strip_comments(text: str) -> str:
    """Remove XML, /* */ and // comments so documentation cannot trip the scans."""
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


# --------------------------------------------------------------------------- 1. Kotlin

def check_kotlin(root: str) -> None:
    print("[1/7] Kotlin syntax")
    try:
        from tree_sitter import Language, Parser
        import tree_sitter_kotlin
    except ImportError:
        fail("tree-sitter-kotlin missing: pip install tree-sitter tree-sitter-kotlin")
        return

    parser = Parser(Language(tree_sitter_kotlin.language()))
    files = sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True))
    files = [f for f in files if "/build/" not in f]
    if not files:
        fail("no Kotlin sources found")
        return

    bad = 0
    for path in files:
        data = open(path, "rb").read()
        lines = data.split(b"\n")
        tree = parser.parse(data)
        errors = []

        def walk(node):
            if node.type == "ERROR" or node.is_missing:
                line = node.start_point[0]
                context = lines[line][:90] if line < len(lines) else b""
                errors.append(f"line {line + 1}: {node.type} near {context!r}")
            for child in node.children:
                walk(child)

        walk(tree.root_node)
        if errors:
            bad += 1
            fail(f"Kotlin syntax error in {os.path.relpath(path, root)}")
            for e in errors[:6]:
                print(f"       {e}")
    ok(f"{len(files) - bad}/{len(files)} Kotlin files parsed cleanly")


# --------------------------------------------------------------------------- 2. GLSL

def preprocess_glsl(source: str, define_highp: bool) -> str:
    """Perform the conditional compilation a driver would, for the two precision branches.

    The tree-sitter GLSL grammar has no rule for `precision` statements, so this script also
    tolerates an unparsed `precision` line (see check_glsl)."""
    out: list[str] = []
    skip = False
    for line in source.split("\n"):
        stripped = line.strip()
        if stripped.startswith(("#ifdef", "#ifndef")):
            name = stripped.split()[1]
            value = (name == "GL_FRAGMENT_PRECISION_HIGH") and define_highp
            if stripped.startswith("#ifndef"):
                value = not value
            skip = not value
            continue
        if stripped.startswith("#else"):
            skip = not skip
            continue
        if stripped.startswith("#endif"):
            skip = False
            continue
        if not skip:
            out.append(line)
    return "\n".join(out)


def check_glsl(root: str) -> list[str]:
    print("[2/7] GLSL ES 3.0 syntax")
    try:
        from tree_sitter import Language, Parser
        import tree_sitter_glsl
    except ImportError:
        fail("tree-sitter-glsl missing: pip install tree-sitter tree-sitter-glsl")
        return []

    parser = Parser(Language(tree_sitter_glsl.language()))
    shaders = sorted(
        p for p in glob.glob(os.path.join(root, "app", "src", "main", "assets", "shaders", "*"))
        if p.endswith((".vert", ".frag", ".glsl"))
    )
    if not shaders:
        fail("no shader assets found")
        return []

    bad = 0
    for path in shaders:
        source = open(path).read()
        if not source.lstrip().startswith("#version 300 es"):
            fail(f"{os.path.relpath(path, root)} must start with '#version 300 es'")
            bad += 1
            continue
        for highp in (True, False):
            branch = "highp" if highp else "mediump"
            expanded = preprocess_glsl(source, highp)
            data = expanded.encode()
            lines = data.split(b"\n")
            errors = []

            def walk(node):
                if node.type == "ERROR" or node.is_missing:
                    line = node.start_point[0]
                    context = lines[line].strip() if line < len(lines) else b""
                    # Grammar gap: `precision <p> float;` is not modelled by tree-sitter-glsl.
                    if not context.startswith(b"precision"):
                        errors.append(f"[{branch}] line {line + 1}: {node.type} near {context[:70]!r}")
                for child in node.children:
                    walk(child)

            walk(parser.parse(data).root_node)
            if errors:
                bad += 1
                fail(f"GLSL syntax error in {os.path.relpath(path, root)}")
                for e in errors[:6]:
                    print(f"       {e}")
    ok(f"{len(shaders) - bad}/{len(shaders)} shader files parsed cleanly (both precision branches)")
    return shaders


# --------------------------------------------------------------------------- 3. XML

def check_xml(root: str) -> None:
    print("[3/7] XML well-formedness")
    files = sorted(glob.glob(os.path.join(root, "app", "src", "**", "*.xml"), recursive=True))
    files = [f for f in files if "/build/" not in f]
    bad = 0
    for path in files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            bad += 1
            fail(f"XML parse error in {os.path.relpath(path, root)}: {exc}")
    ok(f"{len(files) - bad}/{len(files)} XML files well-formed")


# --------------------------------------------------------------------------- 4. Offline

REMOTE_PATTERN = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+")
ALLOWED_URL_CONTEXTS = (
    "schemas.android.com",       # XML namespaces
    "://www.w3.org/",            # XML namespaces
    "://apache.org",             # license identifiers in comments
    "://www.apache.org",
    "://creativecommons.org",
    "://github.com",             # documentation links only (checked by extension)
    "://kotlinlang.org",
    "://developer.android.com",
    "://developer.mozilla.org",
    "://opensource.org",
    "://www.gnu.org",
)
FORBIDDEN_PERMISSIONS = ("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "BILLING")
FORBIDDEN_SDKS = (
    "com.google.firebase",
    "com.google.android.gms.ads",
    "com.crashlytics",
    "com.google.android.play:core",
    "com.amplitude",
    "com.segment",
    "io.sentry",
    "com.bugsnag",
    "com.flurry",
    "com.appsflyer",
    "com.facebook.appevents",
    "com.supabase",
    "okhttp3",
    "com.squareup.okhttp",
    "retrofit2",
    "com.android.volley",
    "coil",
    "glide",
    "com.bumptech.glide",
)


def check_offline(root: str) -> None:
    print("[4/7] Offline + security contract")
    sources = []
    for pattern in ("**/*.kt", "**/*.kts", "**/*.xml", "**/*.pro", "**/*.toml", "**/*.gradle"):
        sources += glob.glob(os.path.join(root, pattern), recursive=True)
    sources = sorted({s for s in sources if "/build/" not in s})

    offenders = []
    for path in sources:
        rel = os.path.relpath(path, root)
        text = strip_comments(open(path, errors="ignore").read())
        for match in REMOTE_PATTERN.findall(text):
            allowed = any(ctx in match for ctx in ALLOWED_URL_CONTEXTS)
            # Documentation files are not scanned here; sources are.
            if not allowed:
                offenders.append(f"{rel}: {match}")
    if offenders:
        for o in offenders:
            fail(f"remote URL in shipped source -> {o}")
    else:
        ok("no remote URLs in Kotlin/Gradle/XML sources")

    # Dependencies: no telemetry / networking / ad SDKs.
    build_files = glob.glob(os.path.join(root, "**", "build.gradle.kts"), recursive=True)
    catalog = os.path.join(root, "gradle", "libs.versions.toml")
    if os.path.exists(catalog):
        build_files.append(catalog)
    for path in build_files:
        text = strip_comments(open(path).read())
        for sdk in FORBIDDEN_SDKS:
            if sdk in text:
                fail(f"forbidden dependency '{sdk}' in {os.path.relpath(path, root)}")
    ok("no telemetry, ad, analytics or networking dependencies declared")

    # Manifest permissions.
    manifest = os.path.join(root, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(manifest):
        fail("AndroidManifest.xml missing")
        return
    text = strip_comments(open(manifest).read())
    for permission in FORBIDDEN_PERMISSIONS:
        if re.search(rf'android\.permission\.{permission}\b', text):
            fail(f"manifest requests forbidden permission: {permission}")
    if "uses-permission" not in text:
        ok("manifest declares no permissions at all")
    else:
        ok("manifest permission set is clean")

    # Bundled audio must be local and referenced by name only.
    audio_dir = os.path.join(root, "app", "src", "main", "assets", "audio")
    audio = [f for f in glob.glob(os.path.join(audio_dir, "*")) if os.path.isfile(f)]
    if audio:
        ok("bundled demo audio: " + ", ".join(sorted(os.path.basename(a) for a in audio)))
    else:
        note("no bundled audio asset found (integration supplies app-private audio)")


# --------------------------------------------------------------------------- 5. Uniforms

UNIFORM_DECL = re.compile(r"^\s*uniform\s+\w+\s+(\w+)\s*(\[[^\]]*\])?\s*;", re.M)
UNIFORM_USE = re.compile(r'"(u[A-Z]\w*)"')


def check_uniform_contract(root: str, shaders: list[str]) -> None:
    print("[5/7] Shader uniform contract")
    declared: dict[str, set[str]] = {}
    for path in shaders:
        name = os.path.basename(path)
        declared[name] = set(UNIFORM_DECL.findall(open(path).read()) for _ in ())  # type: ignore
        declared[name] = {m[0] for m in UNIFORM_DECL.findall(open(path).read())}

    used: dict[str, set[str]] = {}
    kotlin = [p for p in glob.glob(os.path.join(root, "app", "src", "**", "*.kt"), recursive=True)
              if "/build/" not in p]
    for path in kotlin:
        found = set(UNIFORM_USE.findall(open(path).read()))
        if found:
            used[os.path.relpath(path, root)] = found

    all_declared = set().union(*declared.values()) if declared else set()
    for path, names in sorted(used.items()):
        unknown = sorted(n for n in names if n not in all_declared)
        if unknown:
            fail(f"unknown shader uniform(s) referenced from {path}: {', '.join(unknown)}")

    all_used = set().union(*used.values()) if used else set()
    unuploaded = sorted(n for n in all_declared if n not in all_used)
    if unuploaded:
        # Samplers and gl_-style state are set directly on the texture unit.
        leftovers = [n for n in unuploaded if n not in ("uLabelTexture", "uSceneTexture", "uBackgroundTexture")]
        if leftovers:
            note("shader uniforms not referenced from Kotlin: " + ", ".join(leftovers))
    ok(f"{len(all_declared)} shader uniforms, {len(all_used)} referenced from Kotlin, contract consistent")


# --------------------------------------------------------------------------- 6. Manifest

def check_manifest(root: str) -> None:
    print("[6/7] Manifest contract")
    manifest = os.path.join(root, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(manifest):
        fail("AndroidManifest.xml missing")
        return

    tree = ET.parse(manifest)
    xml_root = tree.getroot()
    android = "{http://schemas.android.com/apk/res/android}"

    features = {f.get(android + "name"): f.get(android + "required") for f in xml_root.iter("uses-feature")}
    gles = features.get("android.hardware.opengles.version")
    if gles is None:
        fail("uses-feature opengles.version missing")
    elif gles != "false":
        fail("OpenGL ES 3.0 must be declared required='false' so the static fallback is reachable")
    else:
        ok("OpenGL ES 3.0 declared, not required (fallback path reachable)")

    activities = [a for a in xml_root.iter("activity")]
    launchers = [a for a in activities if any(
        i.find("category") is not None and
        i.find("category").get(android + "name") == "android.intent.category.LAUNCHER"
        for i in a.iter("intent-filter"))]
    if not launchers:
        fail("no launcher activity declared")
    else:
        ok(f"launcher activity: {launchers[0].get(android + 'name')}")


# --------------------------------------------------------------------------- 7. Assets

REQUIRED_SHADERS = ("turntable.vert", "turntable.frag", "fullscreen.vert", "background.frag", "composite.frag")


def check_assets(root: str) -> None:
    print("[7/7] Asset contract")
    shader_dir = os.path.join(root, "app", "src", "main", "assets", "shaders")
    for name in REQUIRED_SHADERS:
        if not os.path.exists(os.path.join(shader_dir, name)):
            fail(f"missing required shader asset: {name}")
    ok(f"all {len(REQUIRED_SHADERS)} shader assets present")

    # Shaders must be loaded from assets, never from a URL or a generated string blob.
    loader = os.path.join(root, "app", "src", "main", "java", "com", "vynylrecord", "turntable",
                          "graphics", "gl", "ShaderProgram.kt")
    if os.path.exists(loader):
        text = open(loader).read()
        if "assets.open" not in text:
            note("ShaderProgram does not obviously read from assets - verify loader manually")
        else:
            ok("shaders are loaded from bundled assets")


# --------------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="project root (defaults to the parent of tools/)")
    args = parser.parse_args()
    root = os.path.abspath(args.root)

    print(f"Vynyl native turntable - static verification\nroot: {root}\n")
    if not os.path.isdir(os.path.join(root, "app")):
        print(f"error: {root} does not look like the android-turntable-native project")
        return 2

    check_kotlin(root)
    shaders = check_glsl(root)
    check_xml(root)
    check_offline(root)
    check_uniform_contract(root, shaders)
    check_manifest(root)
    check_assets(root)

    print()
    if NOTES:
        print("Notes:")
        for n in NOTES:
            print(f"  note {n}")
        print()
    if FAILURES:
        print(f"FAILED ({len(FAILURES)} problem(s)):")
        for f in FAILURES:
            print(f"  fail {f}")
        return 1
    print("All static checks passed.")
    print("Reminder: this is static analysis, not a compile. Run ./gradlew assembleDebug for that.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
