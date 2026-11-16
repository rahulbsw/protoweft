#!/usr/bin/env python3
"""Fails if a published POM declares a dependency without a version.

Maven Central rejects such POMs, and Maven consumers can't resolve them. Usage:
    check_poms.py <maven-repository-root> <version>
It checks every io/github/rahulbsw/*/<version>/*.pom under the root (for example ~/.m2/repository
after publishToMavenLocal -Pversion=<version>). Dependencies inside <dependencyManagement> are
skipped (the BOM's entries are constraints, not dependencies).
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def main(root: str, version: str) -> int:
    poms = sorted(pathlib.Path(root, "io", "github", "rahulbsw").glob(f"*/{version}/*.pom"))
    if not poms:
        print(f"no POMs for version {version} under {root}", file=sys.stderr)
        return 1
    problems = []
    for pom in poms:
        project = ET.parse(pom).getroot()
        for dep in project.findall("m:dependencies/m:dependency", NS):
            if not (dep.findtext("m:version", default="", namespaces=NS) or "").strip():
                coords = f"{dep.findtext('m:groupId', namespaces=NS)}:{dep.findtext('m:artifactId', namespaces=NS)}"
                problems.append(f"{pom.name}: dependency {coords} has no version")
    for p in problems:
        print(p, file=sys.stderr)
    print(f"checked {len(poms)} POMs: {len(problems)} dependencies without a version")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
