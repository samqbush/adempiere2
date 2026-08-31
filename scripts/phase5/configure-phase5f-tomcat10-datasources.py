#!/usr/bin/env python3
"""Copy the installed datasource bindings into the isolated Phase 5f runtime."""

from __future__ import annotations

import argparse
import copy
import xml.etree.ElementTree as ET
from pathlib import Path


RESOURCE_NAMES = {"java/AdempiereDS", "java/AdempiereSRDS"}
# webui.xml is included deliberately. It was omitted until run 33290776432,
# where catalina.out recorded
#   javax.naming.NameNotFoundException: Name [java/AdempiereDS] is not bound in
#   this Context
# from WebUIServlet.init, followed by a silent fallback to a standalone Hikari
# pool. The routed lane must run every context on the reviewed shared container
# datasource, not on uncoordinated per-context pools.
CONTEXT_DESCRIPTORS = {
    "ROOT.xml", "admin.xml", "mobile.xml", "adempiere.xml", "wstore.xml",
    "webui.xml",
}


def parse(path: Path) -> ET.ElementTree:
    """Parse while preserving comments inside the root element.

    The descriptors carry reviewed explanatory comments - notably the JAR scan
    policy note - and the default ElementTree parser silently discards them,
    so rewriting a descriptor here would quietly strip review context.
    """
    return ET.parse(
        path,
        parser=ET.XMLParser(
            target=ET.TreeBuilder(insert_comments=True)),
    )


def write(tree: ET.ElementTree, path: Path, preamble: str) -> None:
    """Write a descriptor back, restoring anything above the root element.

    TreeBuilder(insert_comments=True) only captures comments INSIDE the root,
    and ElementTree.write() never emits anything above it. webui.xml carries
    its entire reviewed rationale in a comment before <Context>, so writing the
    tree alone would delete exactly the review context this rewrite is supposed
    to keep.
    """
    tree.write(path, encoding="UTF-8", xml_declaration=True)
    if not preamble:
        return
    text = path.read_text(encoding="utf-8")
    marker = text.find("<Context")
    if marker < 0:
        raise SystemExit(f"{path.name} has no <Context> element after rewriting")
    path.write_text(text[:marker] + preamble + text[marker:], encoding="utf-8")


def preamble_of(path: Path) -> str:
    """Everything between the XML declaration and the root element."""
    text = path.read_text(encoding="utf-8")
    marker = text.find("<Context")
    if marker < 0:
        raise SystemExit(f"{path.name} has no <Context> element")
    head = text[:marker]
    declaration = head.find("?>")
    return head[declaration + 2:].lstrip("\r\n") if declaration >= 0 else head


def selected(parent: ET.Element, tag: str) -> dict[str, ET.Element]:
    elements = {
        element.get("name"): element
        for element in parent.findall(tag)
        if element.get("name") in RESOURCE_NAMES
    }
    if set(elements) != RESOURCE_NAMES:
        raise SystemExit(
            f"expected {sorted(RESOURCE_NAMES)} {tag} entries, found "
            f"{sorted(elements)}"
        )
    return elements


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-server", type=Path, required=True)
    parser.add_argument("--source-context", type=Path, required=True)
    parser.add_argument("--target-server", type=Path, required=True)
    parser.add_argument("--target-context-dir", type=Path, required=True)
    args = parser.parse_args()

    source_server = parse(args.source_server)
    source_global = source_server.getroot().find("GlobalNamingResources")
    if source_global is None:
        raise SystemExit("installed server.xml has no GlobalNamingResources")
    resources = selected(source_global, "Resource")

    target_server = parse(args.target_server)
    target_global = target_server.getroot().find("GlobalNamingResources")
    if target_global is None:
        raise SystemExit("Tomcat 10 server.xml has no GlobalNamingResources")
    existing = {
        element.get("name")
        for element in target_global.findall("Resource")
        if element.get("name") in RESOURCE_NAMES
    }
    if existing:
        raise SystemExit(
            f"Tomcat 10 already defines Phase 5f resources: {sorted(existing)}"
        )
    for name in sorted(RESOURCE_NAMES):
        target_global.append(copy.deepcopy(resources[name]))
    target_server.write(
        args.target_server, encoding="UTF-8", xml_declaration=True)

    source_context = parse(args.source_context)
    links = selected(source_context.getroot(), "ResourceLink")
    descriptors = {
        path.name: path
        for path in args.target_context_dir.glob("*.xml")
        if path.name in CONTEXT_DESCRIPTORS
    }
    if set(descriptors) != CONTEXT_DESCRIPTORS:
        raise SystemExit(
            f"expected Phase 5f descriptors {sorted(CONTEXT_DESCRIPTORS)}, "
            f"found {sorted(descriptors)}"
        )
    for path in descriptors.values():
        preamble = preamble_of(path)
        descriptor = parse(path)
        root = descriptor.getroot()
        existing_links = {
            element.get("name")
            for element in root.findall("ResourceLink")
            if element.get("name") in RESOURCE_NAMES
        }
        if existing_links:
            raise SystemExit(
                f"{path.name} already defines Phase 5f resource links: "
                f"{sorted(existing_links)}"
            )
        for name in sorted(RESOURCE_NAMES):
            root.append(copy.deepcopy(links[name]))
        write(descriptor, path, preamble)

    # Structural re-read. A missing ResourceLink does not fail deployment; it
    # fails at first JNDI lookup inside a servlet, where the product falls back
    # to a standalone pool and the lane keeps running with the wrong topology.
    for name, path in sorted(descriptors.items()):
        written = {
            element.get("name")
            for element in parse(path).getroot().findall("ResourceLink")
        }
        if not RESOURCE_NAMES <= written:
            raise SystemExit(
                f"{name} is missing resource links "
                f"{sorted(RESOURCE_NAMES - written)} after rewriting"
            )


if __name__ == "__main__":
    main()
