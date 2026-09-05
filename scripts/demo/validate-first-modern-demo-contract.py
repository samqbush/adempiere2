#!/usr/bin/env python3
"""Validate the database-neutral contract for the first modern demo bundle."""

from __future__ import annotations

import configparser
import json
import re
import stat
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEMO = ROOT / "demo" / "first-modern-business"
CONTRACT = DEMO / "contract.properties"


def fail(message: str) -> None:
    raise SystemExit(f"first modern demo contract: {message}")


def load_properties(path: Path) -> dict[str, str]:
    parser = configparser.ConfigParser()
    parser.read_string("[contract]\n" + path.read_text(encoding="utf-8"))
    return dict(parser["contract"])


def property_value(path: Path, key: str) -> str:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1]
    fail(f"{path.relative_to(ROOT)} does not define {key}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def require_executable(path: Path) -> None:
    require(path.is_file(), f"missing executable {path.relative_to(ROOT)}")
    require(
        bool(path.stat().st_mode & stat.S_IXUSR),
        f"{path.relative_to(ROOT)} is not executable",
    )


properties = load_properties(CONTRACT)
required_properties = {
    "contract.version",
    "accepted.develop.commit",
    "accepted.phase5g1b.run",
    "accepted.phase5g1b.run.url",
    "platform",
    "java.image",
    "tomcat9.version",
    "tomcat10.version",
    "postgres.image",
    "zk.version",
    "migration.release",
    "public.url",
    "modern.internal.address",
    "modern.internal.port",
    "database.name",
    "database.user",
    "database.marker",
    "compose.project",
    "compose.database.volume",
    "application.image",
    "database.image",
    "artifact.files",
    "artifact.secret.exclusions",
}
missing = sorted(required_properties - properties.keys())
require(not missing, f"missing properties: {', '.join(missing)}")

runtime2 = ROOT / "gradle" / "phase2" / "runtime.properties"
runtime4 = ROOT / "gradle" / "phase4" / "runtime.properties"
zk_target = ROOT / "gradle" / "phase5" / "zk-target.properties"
require(
    properties["tomcat9.version"] == property_value(runtime2, "tomcat.version"),
    "Tomcat 9 pin differs from Phase 2 runtime",
)
require(
    properties["postgres.version"] == property_value(runtime2, "postgres.version"),
    "PostgreSQL pin differs from Phase 2 runtime",
)
require(
    properties["migration.release"] == property_value(runtime2, "migration.release"),
    "migration release differs from Phase 2 runtime",
)
require(
    properties["tomcat10.version"] == property_value(runtime4, "tomcat.version"),
    "Tomcat 10 pin differs from Phase 4 runtime",
)
require(
    properties["modern.internal.port"] == property_value(runtime4, "api.port"),
    "modern port differs from Phase 4 runtime",
)
require(
    properties["zk.version"] == property_value(zk_target, "zk.version"),
    "ZK pin differs from the accepted target",
)

for image_key in ("java.image", "postgres.image"):
    image = properties[image_key]
    require(
        re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", image) is not None,
        f"{image_key} must be pinned by sha256 digest",
    )

accepted_commit = properties["accepted.develop.commit"]
require(
    re.fullmatch(r"[0-9a-f]{40}", accepted_commit) is not None,
    "accepted develop commit must be a full SHA-1",
)
ancestor = subprocess.run(
    ["git", "merge-base", "--is-ancestor", accepted_commit, "HEAD"],
    cwd=ROOT,
    check=False,
)
require(
    ancestor.returncode == 0,
    "accepted Phase 5g-1b commit is not an ancestor of HEAD",
)
require(
    properties["accepted.phase5g1b.run.url"].endswith(
        "/" + properties["accepted.phase5g1b.run"]
    ),
    "accepted run URL and run ID disagree",
)

compose = (DEMO / "compose.yaml").read_text(encoding="utf-8")
published_ports: list[str] = []
lines = compose.splitlines()
for index, line in enumerate(lines):
    if line == "    ports:":
        for candidate in lines[index + 1 :]:
            if candidate.startswith("      - "):
                published_ports.append(candidate.strip()[2:].strip('"'))
                continue
            if candidate and not candidate.startswith("      "):
                break
require(
    published_ports == ["127.0.0.1:8888:8888"],
    "compose must publish exactly 127.0.0.1:8888 to Tomcat 9 port 8888",
)
require("internal: true" in compose, "compose network must be internal")
require(
    "http://127.0.0.1:8888/webui/ 200" in compose
    and "http://127.0.0.1:8890/webui/ 200,403" in compose,
    "application health must require both public Tomcat 9 and loopback Tomcat 10",
)
require(
    properties["compose.database.volume"] == "adempiere-first-modern-demo-db",
    "database volume name is not the reviewed exact value",
)

launcher = DEMO / "demo"
for executable in (
    launcher,
    DEMO / "runtime" / "start-application.sh",
    DEMO / "runtime" / "render-environment.sh",
    DEMO / "runtime" / "run-verifier.sh",
    DEMO / "runtime" / "demo-database-tool",
    DEMO / "runtime" / "database" / "10-create-role.sh",
    DEMO / "runtime" / "database" / "20-import-seed.sh",
    DEMO / "runtime" / "database" / "30-mark-database.sh",
    DEMO / "runtime" / "database" / "database-healthcheck.sh",
):
    require_executable(executable)

launcher_text = launcher.read_text(encoding="utf-8")
for command in ("init", "up", "status", "verify", "reset", "down"):
    require(
        re.search(rf"\b{command}\)", launcher_text) is not None,
        f"launcher does not implement {command}",
    )
require(
    'org.adempiere.demo.owner' in launcher_text
    and 'org.adempiere.demo.project' in launcher_text
    and 'org.adempiere.demo.instance' in launcher_text,
    "reset must verify every volume ownership label",
)
require(
    launcher_text.count("verify_volume_labels") >= 4,
    "volume labels must be verified before startup, after creation, and before reset",
)
require(
    'DEMO_INSTANCE_ID="$instance_id"' in launcher_text
    and 'DEMO_DATABASE_VOLUME="$database_volume"' in launcher_text
    and '--env-file "$env_file"' not in launcher_text,
    "Compose must receive only the launcher-validated instance environment",
)
require(
    'docker volume rm "$database_volume"' in launcher_text,
    "reset must remove only the exact resolved demo volume",
)
dockerfile = (DEMO / "runtime" / "Dockerfile.app").read_text(encoding="utf-8")
environment_renderer = (
    DEMO / "runtime" / "render-environment.sh"
).read_text(encoding="utf-8")
require(
    "COPY tomcat9 /opt/tomcat" in dockerfile
    and 'replace ADEMPIERE_APPS_PATH "/opt/tomcat"' in environment_renderer,
    "Tomcat 9 source and generated CATALINA_BASE must remain distinct",
)
startup = (DEMO / "runtime" / "start-application.sh").read_text(encoding="utf-8")
require(
    "webui-routed.war" in dockerfile
    and 'cp /opt/demo/artifacts/webui-routed.war "$home/lib/webui.war"' in startup,
    "silent setup must not replace the reviewed routed public WAR",
)
database_healthcheck = (
    DEMO / "runtime" / "database" / "database-healthcheck.sh"
).read_text(encoding="utf-8")
require(
    "<<'SQL'" in database_healthcheck and "--command" not in database_healthcheck,
    "database health SQL must use stdin so psql expands marker variables",
)

tracked = subprocess.check_output(
    [
        "git",
        "ls-files",
        "--cached",
        "--others",
        "--exclude-standard",
        "demo/first-modern-business",
    ],
    cwd=ROOT,
    text=True,
).splitlines()
for relative in tracked:
    path = ROOT / relative
    require(path.is_file(), f"tracked demo path is not a file: {relative}")
    lowered = relative.lower()
    require(
        not lowered.endswith((".key", ".password", ".log")),
        f"secret or runtime file is tracked: {relative}",
    )
    require(
        not lowered.endswith("/adempiereenv.properties")
        and not lowered.endswith("/adempiere.properties"),
        f"configured runtime properties are tracked: {relative}",
    )

source_text = "\n".join(
    (ROOT / path).read_text(encoding="utf-8", errors="replace") for path in tracked
)
for forbidden in ("BEGIN PRIVATE KEY", "BEGIN OPENSSH PRIVATE KEY"):
    require(forbidden.lower() not in source_text.lower(), f"found {forbidden!r}")

manifest_files = set(properties["artifact.files"].split(","))
require(
    manifest_files
    == {
        "adempiere-modern-demo-images.tar",
        "compose.yaml",
        "demo",
        "contract.properties",
        "provenance.json",
        "SHA256SUMS",
        "first-modern-business-demo.md",
    },
    "artifact file allowlist changed without contract review",
)
require(
    set(properties["artifact.secret.exclusions"].split(","))
    == {
        ".demo-state",
        "AdempiereEnv.properties",
        "Adempiere.properties",
        "*.key",
        "*.password",
        "*.log",
    },
    "artifact secret-exclusion list changed without contract review",
)

workflow = (
    ROOT / ".github" / "workflows" / "first-modern-business-demo.yml"
).read_text(encoding="utf-8")
require(
    "github.ref == 'refs/heads/develop'" in workflow,
    "bundle workflow must be restricted to develop",
)
require(
    ":zkwebui:firstModernDemoPublicOriginSmoke" in workflow,
    "bundle smoke must exercise an authenticated public-origin modern write",
)
require(
    "./demo reset" in workflow and "./demo verify" in workflow,
    "bundle smoke must exercise reset and verification",
)
main_workflow = (ROOT / ".github" / "workflows" / "main.yml").read_text(
    encoding="utf-8"
)
require(
    "task: firstModernDemoFinalVerification phase5cFinalVerification"
    in main_workflow,
    "Contracts must run the demo gate head plus the off-chain Phase 5c gate",
)

summary = {
    "accepted_commit": accepted_commit,
    "accepted_run": properties["accepted.phase5g1b.run"],
    "platform": properties["platform"],
    "public_url": properties["public.url"],
    "tracked_demo_files": len(tracked),
}
output = ROOT / "build" / "first-modern-demo" / "contract-summary.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(
    "validated first modern demo contract: "
    f"{len(tracked)} tracked files, public {properties['public.url']}"
)
