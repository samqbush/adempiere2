#!/usr/bin/env python3
"""Verify the closed Phase 5f /webui timeline and DSP compatibility contract."""

import argparse
from pathlib import Path
import re
import zipfile


DSP_PATH = "theme/default/css/theme.css.dsp"
DESCRIPTOR_PATH = "WEB-INF/web.xml"
TIMELINE_CLASS = "WEB-INF/classes/org/adempiere/webui/TimelineEventFeed.class"
LEGACY_TLDS = {
    "WEB-INF/tld/zul/core.dsp.tld",
    "WEB-INF/tld/web/html.dsp.tld",
    "WEB-INF/tld/web/core.dsp.tld",
    "WEB-INF/tld/zk/core.jsp.tld",
    "WEB-INF/tld/zk/core.dsp.tld",
}


def validate(entries: dict[str, bytes], css: bytes) -> list[str]:
    problems: list[str] = []
    descriptor = entries.get(DESCRIPTOR_PATH, b"").decode("utf-8", errors="replace")
    descriptor = re.sub(r"<!--.*?-->", " ", descriptor, flags=re.DOTALL)
    required = {
        TIMELINE_CLASS,
        DSP_PATH,
        DESCRIPTOR_PATH,
    }
    missing = sorted(required - entries.keys())
    if missing:
        problems.append(f"missing required entries: {missing}")
    if entries.get(DSP_PATH) != css:
        problems.append("historical DSP bytes differ from reviewed Phase 5d CSS")
    dsp_entries = sorted(name for name in entries if name.lower().endswith(".dsp"))
    if dsp_entries != [DSP_PATH]:
        problems.append(f"unexpected DSP entries: {dsp_entries}")
    tlds = sorted(LEGACY_TLDS & entries.keys())
    if tlds:
        problems.append(f"legacy DSP/JSP TLDs are packaged: {tlds}")
    checks = {
        'metadata-complete="true"': "metadata-complete Servlet descriptor",
        "<absolute-ordering/>": "empty absolute ordering",
        "org.adempiere.webui.TimelineEventFeed": "timeline servlet class",
        "<url-pattern>/timeline</url-pattern>": "exact timeline mapping",
        "<extension>dsp</extension>": "DSP MIME extension",
        "<mime-type>text/css</mime-type>": "CSS content type",
    }
    for needle, reason in checks.items():
        if needle not in descriptor:
            problems.append(f"descriptor lacks {reason}")
    forbidden = {
        "org.zkoss.web.servlet.dsp.InterpreterServlet": "DSP interpreter",
        "<url-pattern>*.dsp</url-pattern>": "wildcard DSP mapping",
    }
    for needle, reason in forbidden.items():
        if needle in descriptor:
            problems.append(f"descriptor contains {reason}")
    if descriptor.count("<url-pattern>/timeline</url-pattern>") != 1:
        problems.append("timeline must have exactly one descriptor mapping")
    return problems


def read_zip(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        return {
            name: archive.read(name)
            for name in archive.namelist()
            if not name.endswith("/")
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--war", type=Path, required=True)
    parser.add_argument("--css", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    entries = read_zip(args.war)
    css = args.css.read_bytes()
    problems = validate(entries, css)
    if problems:
        raise SystemExit("Phase 5f /webui contract failed:\n  - " + "\n  - ".join(problems))

    mutations = {
        "timeline-route-omission": lambda data: data.__setitem__(
            DESCRIPTOR_PATH,
            data[DESCRIPTOR_PATH].replace(
                b"<servlet-mapping>\n\t\t<servlet-name>timelineFeed</servlet-name>"
                b"\n\t\t<url-pattern>/timeline</url-pattern>\n\t</servlet-mapping>",
                b"",
            ),
        ),
        "timeline-wildcard-route": lambda data: data.__setitem__(
            DESCRIPTOR_PATH,
            data[DESCRIPTOR_PATH].replace(
                b"<url-pattern>/timeline</url-pattern>",
                b"<url-pattern>/*</url-pattern>",
            ),
        ),
        "dsp-asset-drift": lambda data: data.__setitem__(DSP_PATH, b"mutated"),
        "dsp-wildcard-restored": lambda data: data.__setitem__(
            DESCRIPTOR_PATH,
            data[DESCRIPTOR_PATH].replace(
                b"</web-app>",
                b"<servlet-mapping><servlet-name>dspLoader</servlet-name>"
                b"<url-pattern>*.dsp</url-pattern></servlet-mapping></web-app>",
            ),
        ),
        "second-dsp-packaged": lambda data: data.__setitem__(
            "theme/default/css/other.css.dsp", css
        ),
        "legacy-tld-packaged": lambda data: data.__setitem__(
            "WEB-INF/tld/web/core.dsp.tld", b"legacy"
        ),
    }
    rows = ["mutation_id\tresult\tdetection"]
    for mutation_id, mutate in mutations.items():
        mutant = dict(entries)
        mutate(mutant)
        detected = validate(mutant, css)
        if not detected:
            raise SystemExit(f"mutation was not detected: {mutation_id}")
        rows.append(f"{mutation_id}\tpass\t{detected[0]}")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(
        "Phase 5f /webui inherited routes: timeline and exact static DSP "
        f"contract valid; {len(mutations)} mutations detected"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
