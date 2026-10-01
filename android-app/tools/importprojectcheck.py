#!/usr/bin/env python3
"""Finds uses of project top-level symbols from another package without an import.

The Kotlin parser cannot run here (no JDK), so this static pass guards the one
mistake the other checks cannot see: a top-level function or property declared in
package A, called from package B, with the import forgotten. That is a hard
compile error in Kotlin and it is invisible to a keyword-only scan.

Usage: python tools/importprojectcheck.py [src-root ...]
"""

from __future__ import annotations

import sys
from pathlib import Path

import tree_sitter_kotlin
from tree_sitter import Language, Parser

LANGUAGE = Language(tree_sitter_kotlin.language())
PARSER = Parser(LANGUAGE)

IDENTIFIER_NODES = ("simple_identifier", "type_identifier", "identifier")

DECLARATION_NODES = {
    "function_declaration",
    "property_declaration",
    "class_declaration",
    "object_declaration",
    "interface_declaration",
    "type_alias",
}


def parse(path: Path):
    return PARSER.parse(path.read_bytes())


def package_of(root, source: bytes) -> str:
    for node in root.children:
        if node.type == "package_header":
            return source[node.start_byte:node.end_byte].decode().replace("package", "", 1).strip()
    return ""


def imports_of(root, source: bytes) -> set[str]:
    imports: set[str] = set()
    for node in root.children:
        if node.type == "import":
            text = source[node.start_byte:node.end_byte].decode()
            text = text.replace("import", "", 1).strip().rstrip(";")
            if text.endswith(".*"):
                imports.add(text[:-2])
            elif " as " in text:
                imports.add(text)
            else:
                imports.add(text)
    return imports


def declaration_name(node, source: bytes) -> str | None:
    """The name a declaration introduces, whether it is simple or a callable."""
    if node.type == "type_alias":
        for child in node.children:
            if child.type in IDENTIFIER_NODES:
                return source[child.start_byte:child.end_byte].decode()
        return None
    if node.type == "property_declaration":
        # `val name: Type` wraps the name in a variable_declaration, so the identifier is one level down.
        for child in node.children:
            if child.type == "variable_declaration":
                for inner in child.children:
                    if inner.type in IDENTIFIER_NODES:
                        return source[inner.start_byte:inner.end_byte].decode()
                return None
    for child in node.children:
        if child.type in IDENTIFIER_NODES:
            return source[child.start_byte:child.end_byte].decode()
    return None


def collect_declarations(root, source: bytes, package: str, path: Path, index: dict[str, list[tuple[str, Path]]]) -> None:
    for node in root.children:
        if node.type not in DECLARATION_NODES:
            continue
        name = declaration_name(node, source)
        if not name:
            continue
        full = f"{package}.{name}" if package else name
        index.setdefault(name, []).append((full, path))


def local_names(root, source: bytes) -> set[str]:
    """Every name declared anywhere in the file, including parameters and local values.

    A name that is declared and used in the same file never needs an import, however deeply it is nested.
    """
    names: set[str] = set()
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in DECLARATION_NODES or node.type in ("class_declaration", "object_declaration"):
            name = declaration_name(node, source)
            if name:
                names.add(name)
        elif node.type == "parameter":
            for child in node.children:
                if child.type in IDENTIFIER_NODES:
                    names.add(source[child.start_byte:child.end_byte].decode())
                    break
        stack.extend(node.children)
    return names


COMMENT_NODES = {"block_comment", "line_comment", "shebang_line"}

# Well-known library symbols that are extensions or top-level functions: unlike a class, a missing import
# for one of these is invisible to every other check here, and it is a hard compile error.
EXTERNAL_IMPORTS = {
    "collectAsStateWithLifecycle": "androidx.lifecycle.compose.collectAsStateWithLifecycle",
    "rememberLauncherForActivityResult": "androidx.activity.compose.rememberLauncherForActivityResult",
    "rememberSaveable": "androidx.compose.runtime.saveable.rememberSaveable",
    "stringResource": "androidx.compose.ui.res.stringResource",
    "viewModel": "androidx.lifecycle.viewmodel.compose.viewModel",
    "LocalContext": "androidx.compose.ui.platform.LocalContext",
    "LocalVynylGraph": "com.vynylrecord.app.LocalVynylGraph",
    "Scaffold": "androidx.compose.material3.Scaffold",
    "AlertDialog": "androidx.compose.material3.AlertDialog",
    "TopAppBar": "androidx.compose.material3.TopAppBar",
    "ExperimentalMaterial3Api": "androidx.compose.material3.ExperimentalMaterial3Api",
    "rememberModalBottomSheetState": "androidx.compose.material3.rememberModalBottomSheetState",
    "Image": "androidx.compose.foundation.Image",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "LazyVerticalGrid": "androidx.compose.foundation.lazy.grid.LazyVerticalGrid",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "AndroidView": "androidx.compose.ui.viewinterop.AndroidView",
    "hapticFeedback": "androidx.compose.ui.platform.LocalHapticFeedback",
    "mapNotNull": "kotlin.collections.mapNotNull",
}


def check_external_imports(path: Path, root, source: bytes, imports: set[str], package: str) -> list[str]:
    """A library extension used without its import does not compile, and nothing else here sees it."""
    problems: list[str] = []
    names = referenced_names(root, source)
    for name, required in EXTERNAL_IMPORTS.items():
        if name not in names:
            continue
        if required.rsplit(".", 1)[0] == package:
            continue
        if required in imports or any(entry.endswith(".$name") for entry in imports):
            continue
        problems.append(f"{path}: uses '{name}' without importing {required}")
    return problems


def referenced_names(root, source: bytes) -> set[str]:
    """Unqualified identifiers used in code, ignoring comments and anything after a dot.

    `com.example.Thing` and `Other.Thing` are both qualified: the leading name is what needs the import,
    not the one that follows a dot.
    """
    names: set[str] = set()
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in COMMENT_NODES:
            continue
        if node.type in IDENTIFIER_NODES:
            previous = node.prev_sibling
            if previous is not None:
                # `.name`, `?.name` and `!!.name` are all member access: the receiver is what carries the
                # import, not the member.
                token = source[previous.start_byte:previous.end_byte].decode(errors="replace")
                if token.endswith(".") or token == "!!":
                    continue
            names.add(source[node.start_byte:node.end_byte].decode())
            continue
        stack.extend(node.children)
    return names


def qualified_chains(root, source: bytes) -> set[str]:
    """Dotted chains that start with the app's own root package."""
    chains: set[str] = set()
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in COMMENT_NODES or node.type in ("package_header", "import"):
            continue
        text = source[node.start_byte:node.end_byte].decode(errors="replace")
        if node.type in IDENTIFIER_NODES and text == "com":
            start = node.start_byte
            end = node.end_byte
            cursor = node
            while True:
                following = cursor.next_sibling
                if following is None or following.type != ".":
                    break
                after = following.next_sibling
                if after is None or after.type not in IDENTIFIER_NODES:
                    break
                end = after.end_byte
                cursor = after
            chain = source[start:end].decode(errors="replace")
            if chain.startswith("com.vynylrecord.app"):
                chains.add(chain)
        stack.extend(node.children)
    return chains


def resolve_chains(chains: set[str], packages: set[str], types: dict[str, set[str]]) -> list[str]:
    """Every `com.vynylrecord.app...` chain must name something that exists."""
    problems: list[str] = []
    for chain in sorted(chains):
        segments = chain.split(".")
        longest = -1
        for index in range(len(segments), 0, -1):
            if ".".join(segments[:index]) in packages:
                longest = index
                break
        if longest <= 0 or longest >= len(segments):
            problems.append(f"{chain}: no package of the app declares this")
            continue
        head = ".".join(segments[:longest])
        name = segments[longest]
        if head not in types.get(name, set()):
            problems.append(f"{chain}: '{name}' is not declared in {head}")
    return problems


def main(roots: list[Path]) -> int:
    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.kt")))

    parsed: list[tuple[Path, bytes, "object", str, set[str]]] = []
    index: dict[str, list[tuple[str, Path]]] = {}
    for path in files:
        tree = parse(path)
        source = path.read_bytes()
        package = package_of(tree.root_node, source)
        parsed.append((path, source, tree.root_node, package, imports_of(tree.root_node, source)))
        collect_declarations(tree.root_node, source, package, path, index)

    problems: list[str] = []
    checked = 0
    for path, source, root, package, imports in parsed:
        names = referenced_names(root, source) - local_names(root, source)
        for name in sorted(names):
            declarations = index.get(name)
            if not declarations:
                continue
            # A name declared in this very file never needs an import.
            if any(decl_path == path for _, decl_path in declarations):
                continue
            candidates = {full for full, _ in declarations}
            if any(full in imports for full in candidates):
                continue
            if any(full.rsplit(".", 1)[0] in imports for full in candidates):
                continue
            if any(full.rsplit(".", 1)[0] == package for full in candidates):
                continue
            checked += 1
            problems.append(
                f"{path}: uses '{name}' declared in {sorted(candidates)} without importing it",
            )

    packages = {package for _, _, _, package, _ in parsed if package}
    types: dict[str, set[str]] = {}
    for name, declarations in index.items():
        types.setdefault(name, set())
        for full, _ in declarations:
            types[name].add(full.rsplit(".", 1)[0])

    for path, source, root, package, imports in parsed:
        problems.extend(check_external_imports(path, root, source, imports, package))

    chains: set[str] = set()
    for _, source, root, _, _ in parsed:
        chains |= qualified_chains(root, source)
    problems.extend(resolve_chains(chains, packages, types))

    print(
        f"scanned {len(files)} files, {len(index)} top-level names, "
        f"{checked} cross-package references, {len(chains)} qualified references",
    )
    if problems:
        print(f"\n{len(problems)} reference(s) that do not resolve:")
        for problem in problems:
            print("  " + problem)
        return 1
    print("no cross-package reference is missing its import")
    return 0


if __name__ == "__main__":
    arguments = [Path(argument) for argument in sys.argv[1:]]
    if not arguments:
        arguments = [Path("app/src")]
    sys.exit(main(arguments))
