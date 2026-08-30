#!/usr/bin/env python3
"""Copy the installed datasource bindings into the isolated Phase 5f runtime."""

from __future__ import annotations

import argparse
import copy
import xml.etree.ElementTree as ET
from pathlib import Path


RESOURCE_NAMES = {"java/AdempiereDS", "java/AdempiereSRDS"}
CONTEXT_DESCRIPTORS = {
    "ROOT.xml", "admin.xml", "mobile.xml", "adempiere.xml", "wstore.xml",
}


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

    source_server = ET.parse(args.source_server)
    source_global = source_server.getroot().find("GlobalNamingResources")
    if source_global is None:
        raise SystemExit("installed server.xml has no GlobalNamingResources")
    resources = selected(source_global, "Resource")

    target_server = ET.parse(args.target_server)
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

    source_context = ET.parse(args.source_context)
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
        descriptor = ET.parse(path)
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
        descriptor.write(path, encoding="UTF-8", xml_declaration=True)


if __name__ == "__main__":
    main()
