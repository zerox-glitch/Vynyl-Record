#!/usr/bin/env python3
"""Static checks over the app's Kotlin sources.

The app cannot be compiled in this environment — there is no JDK, no Android SDK and no route to a Gradle
distribution — so this script stands in for the parts of a compile that a parser can answer honestly:

1. **Every file parses.** A tree-sitter parse with no ERROR or missing nodes, so a stray brace, an
   unbalanced parenthesis or a half-written branch is caught here rather than in a build log.
2. **Imports resolve.** Every `import com.vynylrecord.app.…` names a package, a type, a top-level function
   or a property the project actually declares.
3. **`R.…` references exist.** Every `R.string.foo`, `R.drawable.bar`, `R.color.baz` and `R.xml.qux` in the
   Kotlin sources is declared under `app/src/main/res`.
4. **Member calls resolve.** For a receiver whose name matches exactly one class the project declares — a
   companion object call, a singleton, an enum — every `Receiver.member(…)` must name a member of that type,
   of a type it inherits from, or of the type's companion.
5. **Named arguments match parameters.** `Thing(nope = 1)` where `Thing`'s constructor has no `nope` is a
   compile error, and one a rename loves to introduce.
6. **Members reached through a known type resolve.** For a local or parameter with an explicit project type
   (`val pose: DeckPose`), every `pose.member` must exist on `DeckPose` or a project type it inherits from.
7. **No unused imports.** An import whose last name never appears again in its file is dead weight the
   compiler would warn about.

It also reports two things that are *not* compile errors, as findings rather than as failures:

* **Unused declarations.** A project type or top-level function that nothing else in the app refers to is
  usually a leftover from a design that changed; the report is short enough to read.
* **Unreferenced resource strings.** A string declared in `strings.xml` and used nowhere is a screen that
  was described before it was written.

What this does *not* do is type-check. Overload resolution, generics and lambdas are beyond a parser, and a
clean run is not proof that the app compiles — it is proof that nothing is obviously broken. Run it after
every change:  `tools/kotlincheck.py [--root android-app]`
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import tree_sitter_kotlin
from tree_sitter import Language, Node, Parser

PARSER = Parser(Language(tree_sitter_kotlin.language()))

TYPE_NODES = {"class_declaration", "object_declaration", "companion_object"}
MEMBER_NODES = {"function_declaration", "property_declaration", "enum_entry", "class_declaration",
                "object_declaration", "companion_object"}


def walk(node: Node):
    """Every node in the tree, depth first."""
    stack = [node]
    while stack:
        current = stack.pop()
        yield current
        stack.extend(reversed(current.children))


def text_of(node: Node, source: bytes) -> str:
    return source[node.start_byte : node.end_byte].decode("utf-8", errors="replace")


def first_identifier(node: Node, source: bytes) -> str | None:
    """The declaring name: the first identifier that is not part of a receiver type."""
    for child in node.children:
        if child.type == "identifier":
            return text_of(child, source)
        if child.type in ("user_type", "value_class_modifiers"):
            continue
    for child in node.children:
        if child.type == "identifier":
            return text_of(child, source)
    return None


def body_of(node: Node) -> Node | None:
    for child in node.children:
        if child.type in ("class_body", "enum_class_body"):
            return child
    return None


def supertypes_of(node: Node, source: bytes) -> list[str]:
    for child in node.children:
        if child.type == "delegation_specifiers":
            return re.findall(r"\b([A-Z]\w*)\b", text_of(child, source))
    return []


@dataclass
class Declaration:
    name: str
    kind: str
    path: Path
    line: int
    owner: str | None = None
    supertypes: list[str] = field(default_factory=list)

    @property
    def qualified(self) -> str:
        return f"{self.owner}.{self.name}" if self.owner else self.name


@dataclass
class Source:
    path: Path
    text: str
    tree: Node
    package: str = ""
    imports: list[tuple[str, int]] = field(default_factory=list)
    declarations: list[Declaration] = field(default_factory=list)


def parse(path: Path) -> Source:
    text = path.read_text(encoding="utf-8")
    return Source(path=path, text=text, tree=PARSER.parse(text.encode("utf-8")).root_node)


def read_headers(source: Source) -> None:
    root = source.tree
    for node in root.children:
        if node.type == "package_header":
            for child in walk(node):
                if child.type == "qualified_identifier":
                    source.package = text_of(child, source.text.encode())
                    break
        elif node.type == "import":
            name = text_of(node, source.text.encode()).replace("import", "", 1).strip()
            if " as " in name:
                name = name.split(" as ")[0].strip()
            source.imports.append((name, node.start_point[0] + 1))


def collect(source: Source) -> None:
    source_bytes = source.text.encode("utf-8")

    def visit(node: Node, owner: str | None) -> None:
        for child in node.children:
            if child.type in TYPE_NODES:
                name = first_identifier(child, source_bytes)
                if name:
                    source.declarations.append(
                        Declaration(
                            name=name,
                            kind="interface" if "interface" in text_of(child, source_bytes)[:40] else "type",
                            path=source.path,
                            line=child.start_point[0] + 1,
                            owner=owner,
                            supertypes=supertypes_of(child, source_bytes),
                        )
                    )
                    # A companion object's members answer to `ClassName.member`, which is why the owner is
                    # the enclosing class and not the companion.
                    inner_owner = owner if child.type == "companion_object" else (f"{owner}.{name}" if owner else name)
                    # Constructor properties are members: `data class AudioAsset(val id: String)` exposes `id`
                    # just as surely as a property written inside the body does, and every call site treats it
                    # that way.
                    for part in child.children:
                        if part.type != "primary_constructor":
                            continue
                        for inner in walk(part):
                            if inner.type == "class_parameter":
                                parameter = first_identifier(inner, source_bytes)
                                if parameter:
                                    source.declarations.append(
                                        Declaration(
                                            name=parameter,
                                            kind="property",
                                            path=source.path,
                                            line=inner.start_point[0] + 1,
                                            owner=inner_owner,
                                        )
                                    )
                    body = body_of(child)
                    if body is not None:
                        visit(body, inner_owner)
                continue
            if child.type == "function_declaration":
                # The name is the identifier that follows `fun`, not the last identifier in the declaration:
                # `fun audioAssets(): AudioAssetDao` ends with its return type.
                name = None
                children = child.children
                for index, part in enumerate(children):
                    if part.type == "fun":
                        for following in children[index + 1 :]:
                            if following.type == "identifier":
                                name = text_of(following, source_bytes)
                                break
                        break
                if name:
                    source.declarations.append(
                        Declaration(name=name, kind="function", path=source.path, line=child.start_point[0] + 1, owner=owner)
                    )
                visit(child, owner)
                continue
            if child.type == "property_declaration":
                for inner in child.children:
                    if inner.type == "variable_declaration":
                        name = first_identifier(inner, source_bytes)
                        if name:
                            source.declarations.append(
                                Declaration(name=name, kind="property", path=source.path, line=child.start_point[0] + 1, owner=owner)
                            )
                        break
                visit(child, owner)
                continue
            if child.type == "enum_entry" and owner:
                name = first_identifier(child, source_bytes)
                if name:
                    source.declarations.append(
                        Declaration(name=name, kind="enum_entry", path=source.path, line=child.start_point[0] + 1, owner=owner)
                    )
                continue
            if child.type in ("class_body", "enum_class_body", "function_body", "block", "control_structure_body"):
                # Members inside a function body are locals, not members of the type.
                if child.type in ("class_body", "enum_class_body"):
                    visit(child, owner)
                else:
                    visit(child, owner)
                continue
            visit(child, owner)

    visit(source.tree, None)


def parse_errors(sources: list[Source]) -> list[str]:
    problems: list[str] = []
    for source in sources:
        for node in walk(source.tree):
            if node.type == "ERROR" or node.is_missing:
                snippet = text_of(node, source.text.encode())[:70].replace("\n", " ")
                problems.append(f"{source.path.name}:{node.start_point[0] + 1}: parse error: {snippet}")
    return problems


def check_imports(sources: list[Source]) -> list[str]:
    packages = {source.package for source in sources}
    qualified: set[str] = set()
    simple: set[str] = set()
    for source in sources:
        for declaration in source.declarations:
            simple.add(declaration.name)
            qualified.add(f"{source.package}.{declaration.qualified}")
            qualified.add(f"{source.package}.{declaration.name}")

    problems: list[str] = []
    for source in sources:
        for name, line in source.imports:
            if not name.startswith("com.vynylrecord.app"):
                continue
            if name in qualified or name in packages:
                continue
            last = name.split(".")[-1]
            if last in simple:
                continue
            # A companion member or an extension imported as Package.Class.member.
            if any(part in simple for part in name.split(".")):
                continue
            if ".".join(name.split(".")[:-1]) in packages:
                continue
            problems.append(f"{source.path.name}:{line}: import {name} names nothing this project declares")
    return problems


def check_unused_imports(sources: list[Source]) -> list[str]:
    problems: list[str] = []
    # `val x by remember { … }` uses getValue/setValue without ever naming them, which is why the delegate
    # operators are checked against the file's use of `by` rather than against their own name.
    delegates = {"getValue", "setValue", "provideDelegate"}
    for source in sources:
        body = "\n".join(
            line for line in source.text.splitlines() if not line.strip().startswith("import ")
        )
        uses_delegation = re.search(r"\bby\b", body) is not None
        for name, line in source.imports:
            last = name.split(".")[-1]
            if last == "*":
                continue
            if last in delegates and uses_delegation:
                continue
            if not re.search(rf"\b{re.escape(last)}\b", body):
                problems.append(f"{source.path.name}:{line}: unused import {name}")
    return problems


def check_resources(sources: list[Source], res_root: Path) -> list[str]:
    declared: dict[str, set[str]] = defaultdict(set)
    for values in res_root.glob("values/*.xml"):
        for match in re.finditer(
            r'<(string|color|dimen|integer|bool|style|string-array)\s+name="([^"]+)"', values.read_text()
        ):
            kind, name = match.group(1), match.group(2)
            declared["array" if kind == "string-array" else kind].add(name)
    for pattern, kind in (("drawable", "drawable"), ("mipmap-*", "mipmap"), ("xml", "xml")):
        for folder in res_root.glob(pattern):
            if folder.is_dir():
                for file in folder.glob("*.xml"):
                    declared[kind].add(file.stem)

    problems: list[str] = []
    for source in sources:
        for match in re.finditer(r"\bR\.(\w+)\.(\w+)", source.text):
            kind, name = match.group(1), match.group(2)
            if kind not in declared:
                continue
            if name not in declared[kind]:
                line = source.text[: match.start()].count("\n") + 1
                problems.append(f"{source.path.name}:{line}: R.{kind}.{name} is not declared in res/")
    return problems


def parameter_names(sources: list[Source]) -> dict[str, set[str]]:
    """Constructor parameter names, by class name, for the named-argument check."""
    parameters: dict[str, set[str]] = {}
    for source in sources:
        source_bytes = source.text.encode()
        for node in walk(source.tree):
            if node.type != "class_declaration":
                continue
            name = first_identifier(node, source_bytes)
            if not name:
                continue
            names: set[str] = set()
            for child in node.children:
                if child.type != "primary_constructor":
                    continue
                for inner in walk(child):
                    if inner.type == "class_parameter":
                        parameter = first_identifier(inner, source_bytes)
                        if parameter:
                            names.add(parameter)
            # A data class exposes its component names through copy()/constructor either way, so an empty set
            # is kept rather than dropped: it means "no parameters", which is also worth checking.
            parameters[name] = names
    return parameters


def check_named_arguments(sources: list[Source], parameters: dict[str, set[str]]) -> list[str]:
    problems: list[str] = []
    for source in sources:
        source_bytes = source.text.encode()
        for node in walk(source.tree):
            if node.type != "call_expression":
                continue
            function = node.child_by_field_name("function")
            if function is None:
                continue
            name = text_of(function, source_bytes).split(".")[-1]
            if name not in parameters:
                continue
            arguments = node.child_by_field_name("value_arguments")
            if arguments is None:
                continue
            for argument in arguments.children:
                if argument.type != "value_argument":
                    continue
                label = None
                for child in argument.children:
                    if child.type == "identifier" and child.end_byte <= argument.end_byte:
                        label = text_of(child, source_bytes)
                        break
                if label is None or label == name:
                    continue
                # Only a labelled argument counts: a positional one cannot be checked without types.
                after_label = text_of(argument, source_bytes)[len(label) :].lstrip()
                if not after_label.startswith("="):
                    continue
                if label not in parameters[name]:
                    line = argument.start_point[0] + 1
                    problems.append(
                        f"{source.path.name}:{line}: {name}({label} = …) — {name} has no parameter {label}"
                    )
    return problems


SCOPE_NODES = ("function_declaration", "class_declaration", "object_declaration", "property_declaration")


def collect_typed_names(node: Node, source_bytes: bytes, into: dict[str, set[str]]) -> None:
    """Names in this scope whose type is written down, without descending into nested scopes."""
    for child in node.children:
        if child.type in ("function_declaration", "class_declaration", "object_declaration"):
            continue
        if child.type in ("parameter", "class_parameter", "variable_declaration", "property_declaration"):
            name = first_identifier(child, source_bytes)
            type_name = None
            if any(inner.type == "function_type" for inner in child.children):
                # `block: IconBuilder.() -> Unit` is a lambda, not an IconBuilder.
                continue
            for inner in child.children:
                if inner.type == "user_type":
                    type_name = first_identifier(inner, source_bytes)
                    break
            if type_name is None and child.type == "variable_declaration" and node.type == "property_declaration":
                # `val deck = DeckState()` — the type is inferred from the constructor, and a later
                # `deck.spin()` is checked against `DeckState` just the same.
                for holder in reversed(node.children):
                    if holder.type == "call_expression" and holder.children:
                        head = holder.children[0]
                        if head.type == "identifier":
                            spelled = text_of(head, source_bytes)
                            if spelled[:1].isupper():
                                type_name = spelled
                        break
            if name and type_name:
                into.setdefault(name, set()).add(type_name)
        collect_typed_names(child, source_bytes, into)


def inspect_scope(
    node: Node,
    typed: dict[str, set[str]],
    source: Source,
    members: dict[str, set[str]],
    external: set[str],
    problems: list[str],
    source_bytes: bytes,
) -> None:
    """Checks this scope's member accesses; nested scopes are checked on their own, with their own names."""
    for child in node.children:
        if child.type in SCOPE_NODES:
            check_scope(child, source, members, external, problems, source_bytes)
            continue
        if child.type == "navigation_expression":
            parts = text_of(child, source_bytes).split(".")
            if len(parts) == 2:
                variable, member = parts
                candidates = typed.get(variable)
                if candidates:
                    known = {name for name in candidates if name in members}
                    # Only report when the member is missing from every candidate type, so a name reused in
                    # one file with two types does not produce a finding on the type that does declare it.
                    if known and not (known & external) and all(member not in members[name] for name in known):
                        line = child.start_point[0] + 1
                        names = " or ".join(sorted(known))
                        problems.append(
                            f"{source.path.name}:{line}: {variable}.{member} — {names} has no member {member}"
                        )
        inspect_scope(child, typed, source, members, external, problems, source_bytes)


def check_scope(
    node: Node,
    source: Source,
    members: dict[str, set[str]],
    external: set[str],
    problems: list[str],
    source_bytes: bytes,
) -> None:
    typed: dict[str, set[str]] = {}
    collect_typed_names(node, source_bytes, typed)
    for child in node.children:
        if child.type in SCOPE_NODES:
            check_scope(child, source, members, external, problems, source_bytes)
        else:
            inspect_scope(child, typed, source, members, external, problems, source_bytes)


def check_typed_members(
    sources: list[Source],
    members: dict[str, set[str]],
    external_supertypes: set[str],
) -> list[str]:
    """Members reached through a local or parameter whose type is written down."""
    problems: list[str] = []
    for source in sources:
        source_bytes = source.text.encode()
        for node in source.tree.children:
            check_scope(node, source, members, external_supertypes, problems, source_bytes)
    return problems


def apply_extension_members(sources: list[Source], members: dict[str, set[str]]) -> None:
    """`fun Vec3.normalized()` is, for a caller, a member of `Vec3`."""
    for source in sources:
        source_bytes = source.text.encode()
        for node in walk(source.tree):
            if node.type != "function_declaration" or node.parent is None:
                continue
            if node.parent.type in ("class_body", "enum_class_body", "companion_object"):
                continue
            receiver = None
            for child in node.children:
                if child.type == "user_type":
                    receiver = first_identifier(child, source_bytes)
                    break
            if receiver is None or receiver not in members:
                continue
            for index, child in enumerate(node.children):
                if child.type == "fun":
                    for following in node.children[index + 1 :]:
                        if following.type == "identifier":
                            members[receiver].add(text_of(following, source_bytes))
                            break
                    break


def build_member_index(sources: list[Source]) -> tuple[dict[str, set[str]], dict[str, set[str]], set[str]]:
    """Members by type name, supertypes by type name, and every type name that appears more than once."""
    members: dict[str, set[str]] = defaultdict(set)
    supertypes: dict[str, list[str]] = defaultdict(list)
    name_counts: dict[str, int] = defaultdict(int)

    for source in sources:
        for declaration in source.declarations:
            if declaration.owner:
                members[declaration.owner.split(".")[0]].add(declaration.name)
                members[declaration.owner].add(declaration.name)
                # A nested type (`object WavCodec { data class Header }`) is written at the call site as
                # its simple name, so its own members must answer to that name too.
                nested = declaration.owner.rsplit(".", 1)[-1]
                if nested != declaration.owner:
                    members.setdefault(nested, set()).add(declaration.name)
            if declaration.owner is None or declaration.kind == "type":
                members.setdefault(declaration.name, set())
            if declaration.kind in ("type", "interface"):
                supertypes[declaration.name].extend(declaration.supertypes)
                name_counts[declaration.name] += 1

    # The compiler generates these for every data class, and every call site is entitled to use them.
    generated = {"copy", "equals", "hashCode", "toString"} | {f"component{i}" for i in range(1, 10)}
    for source in sources:
        source_bytes = source.text.encode()
        for node in walk(source.tree):
            if node.type != "class_declaration":
                continue
            header = text_of(node, source_bytes)[:80]
            if not re.search(r"data\s+class", header):
                continue
            name = first_identifier(node, source_bytes)
            if name:
                members.setdefault(name, set()).update(generated)

    # Inherited members: a call on an implementation is satisfied by anything its bases declare.
    for _ in range(3):
        for name, bases in list(supertypes.items()):
            for base in bases:
                if base in members:
                    members[name] |= members[base]

    unique_types = {name for name, count in name_counts.items() if count == 1}
    return members, supertypes, unique_types


def check_member_calls(sources: list[Source], members: dict[str, set[str]], unique_types: set[str]) -> list[str]:
    problems: list[str] = []
    aliases: dict[str, str] = {}
    for source in sources:
        for name, _ in source.imports:
            if name.startswith("com.vynylrecord.app") or name.startswith("android") or name.startswith("kotlinx"):
                aliases[name.split(".")[-1]] = name.split(".")[-1]

    for source in sources:
        for node in walk(source.tree):
            if node.type != "call_expression":
                continue
            function = node.child_by_field_name("function")
            if function is None or function.type != "navigation_expression":
                continue
            text = text_of(function, source.text.encode())
            parts = text.split(".")
            if len(parts) != 2:
                continue
            receiver, member = parts
            if receiver not in unique_types or receiver not in members:
                continue
            if member in members[receiver]:
                continue
            line = node.start_point[0] + 1
            problems.append(f"{source.path.name}:{line}: {receiver}.{member}(…) — {receiver} has no member {member}")
    return problems


def report_unused(sources: list[Source], res_root: Path) -> list[str]:
    """Declarations and strings nothing refers to. Findings, not failures."""
    referenced: set[str] = set()
    for source in sources:
        for match in re.finditer(r"\b([A-Za-z_]\w*)\b", source.text):
            referenced.add(match.group(1))

    findings: list[str] = []
    for source in sources:
        for declaration in source.declarations:
            if declaration.kind == "type" and declaration.name.endswith("Test"):
                # A JUnit class is discovered by the runner rather than called by name.
                continue
            if declaration.owner is not None or declaration.kind not in ("type", "function"):
                continue
            uses = len(re.findall(rf"\b{declaration.name}\b", "".join(s.text for s in sources)))
            if uses <= 1:
                findings.append(f"{source.path.name}:{declaration.line}: {declaration.name} is declared and never used")

    used_strings = {
        match.group(1)
        for source in sources
        for match in re.finditer(r"R\.string\.(\w+)", source.text)
    }
    # Resources are also referenced from other resources and from the manifest, by name.
    other_resources = list(res_root.rglob("*.xml")) + list(res_root.parent.glob("AndroidManifest.xml"))
    for resource in other_resources:
        text = resource.read_text()
        for match in re.finditer(r'"@(string|drawable|color|style)/(\w+)"', text):
            if match.group(1) == "string":
                used_strings.add(match.group(2))
    for values in res_root.glob("values/strings.xml"):
        for match in re.finditer(r'<string name="([^"]+)"', values.read_text()):
            if match.group(1) not in used_strings:
                findings.append(f"res/values/strings.xml: {match.group(1)} is declared and never used")
    return findings


def main() -> int:
    root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[1]
    sources_root = root / "app/src/main/java"
    res_root = root / "app/src/main/res"

    # The unit and instrumentation tests are Kotlin in this project too, and a test that does not
    # compile is worse than no test: check them with the same rules.
    source_paths = sorted(sources_root.rglob("*.kt"))
    for extra in (root / "app/src/test", root / "app/src/androidTest"):
        if extra.is_dir():
            source_paths.extend(sorted(extra.rglob("*.kt")))
    sources = [parse(path) for path in source_paths]
    for source in sources:
        read_headers(source)
        collect(source)

    problems = parse_errors(sources)
    problems += check_imports(sources)
    problems += check_resources(sources, res_root)
    problems += check_unused_imports(sources)

    members, supertypes, unique_types = build_member_index(sources)
    apply_extension_members(sources, members)
    external_supertypes = {
        name
        for name, bases in supertypes.items()
        for base in bases
        if base not in unique_types and base not in members
    }
    problems += check_member_calls(sources, members, unique_types)
    problems += check_named_arguments(sources, parameter_names(sources))
    problems += check_typed_members(sources, members, external_supertypes)

    findings = report_unused(sources, res_root)

    declaration_count = sum(len(source.declarations) for source in sources)
    print(f"parsed {len(sources)} Kotlin files · {declaration_count} declarations · {len(members)} member scopes")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  · {problem}")
    else:
        print("no problems: every file parses, every project import and R reference resolves,")
        print("no import is unused, and every member call on a project type names something it declares")

    if findings:
        print(f"\n{len(findings)} declaration(s) nothing refers to (findings, not failures):")
        for finding in findings:
            print(f"  · {finding}")

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
