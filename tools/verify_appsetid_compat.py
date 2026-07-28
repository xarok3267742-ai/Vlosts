#!/usr/bin/env python3
"""Verify that optimized DEX keeps AppMetrica App Set ID fail-closed."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Iterable


FACTORY_DESCRIPTOR = "Lio/appmetrica/analytics/impl/p2;"
RETRIEVER_DESCRIPTOR = (
    "Lio/appmetrica/analytics/appsetid/internal/AppSetIdRetriever;"
)
INTERFACE_DESCRIPTOR = (
    "Lio/appmetrica/analytics/appsetid/internal/IAppSetIdRetriever;"
)
LISTENER_DESCRIPTOR = (
    "Lio/appmetrica/analytics/appsetid/internal/AppSetIdListener;"
)
GOOGLE_APP_SET_DESCRIPTOR_PREFIX = "Lcom/google/android/gms/appset/"

CLASS_DESCRIPTOR_PATTERN = re.compile(
    r"^\s*Class descriptor\s+:\s+'([^']+)'",
    re.MULTILINE,
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def class_blocks(dump: str) -> dict[str, str]:
    matches = list(CLASS_DESCRIPTOR_PATTERN.finditer(dump))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        descriptor = match.group(1)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(dump)
        blocks[descriptor] = dump[match.start():end]
    return blocks


def validation_issues(dumps: Iterable[str]) -> list[str]:
    dump_list = list(dumps)
    combined = "\n".join(dump_list)
    blocks: dict[str, str] = {}
    for dump in dump_list:
        duplicate_descriptors = blocks.keys() & class_blocks(dump).keys()
        if duplicate_descriptors:
            return [
                "DEX dump contains duplicate class descriptors: "
                + ", ".join(sorted(duplicate_descriptors))
            ]
        blocks.update(class_blocks(dump))

    issues: list[str] = []
    if GOOGLE_APP_SET_DESCRIPTOR_PREFIX in combined:
        issues.append("Google Play App Set API type descriptors are present in optimized DEX.")

    for descriptor in (
        INTERFACE_DESCRIPTOR,
        LISTENER_DESCRIPTOR,
        RETRIEVER_DESCRIPTOR,
        FACTORY_DESCRIPTOR,
    ):
        if descriptor not in blocks:
            issues.append(f"Required App Set ID compatibility class is missing: {descriptor}")

    factory = blocks.get(FACTORY_DESCRIPTOR, "")
    expected_factory_type = f"'(){INTERFACE_DESCRIPTOR}'"
    if "name          : 'a'" not in factory or expected_factory_type not in factory:
        issues.append("AppMetrica App Set ID factory method signature is missing.")
    if not re.search(
        r"new-instance[^\n]*"
        r"Lio/appmetrica/analytics/appsetid/internal/AppSetIdRetriever;",
        factory,
    ):
        issues.append("AppMetrica factory no longer constructs the fail-closed retriever.")
    if not re.search(
        r"new-instance[^\n]*Lio/appmetrica/analytics/impl/o8;",
        factory,
    ):
        issues.append("AppMetrica factory no longer preserves its no-provider fallback.")
    if len(re.findall(r"\breturn-object\b", factory)) < 2:
        issues.append("AppMetrica factory does not retain both provider branches.")
    if re.search(r"\bthrow(?:-\w+)?\b", factory):
        issues.append("AppMetrica factory contains a throwing optimized path.")

    retriever = blocks.get(RETRIEVER_DESCRIPTOR, "")
    expected_retriever_type = (
        f"'(Landroid/content/Context;{LISTENER_DESCRIPTOR})V'"
    )
    if (
        "name          : 'retrieveAppSetId'" not in retriever
        or expected_retriever_type not in retriever
    ):
        issues.append("Fail-closed App Set ID retriever method signature is missing.")
    if not re.search(
        r"invoke-interface[^\n]*"
        r"Lio/appmetrica/analytics/appsetid/internal/AppSetIdListener;"
        r"\.onFailure:\(Ljava/lang/Throwable;\)V",
        retriever,
    ):
        issues.append("Fail-closed App Set ID retriever does not report failure.")
    if ".onAppSetIdRetrieved:" in retriever:
        issues.append("Fail-closed App Set ID retriever can report an identifier.")

    return issues


def run_dexdump(dexdump: Path, dex: Path) -> str:
    completed = subprocess.run(
        [str(dexdump), "-d", str(dex)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    output = completed.stdout.decode("utf-8", errors="replace")
    if completed.returncode != 0:
        excerpt = output.strip()[-4096:]
        raise RuntimeError(
            f"dexdump failed for {dex.name} with exit code "
            f"{completed.returncode}: {excerpt}"
        )
    return output


def write_report(report: Path, dexdump: Path, dex_files: list[Path]) -> None:
    lines = [
        "schema=v-slot-app-set-id-dex-validation-v1",
        f"dexdump-sha256={sha256(dexdump)}",
        f"dex-count={len(dex_files)}",
    ]
    for index, dex in enumerate(dex_files):
        lines.append(f"dex-{index}-name={dex.name}")
        lines.append(f"dex-{index}-sha256={sha256(dex)}")
    lines.extend(
        [
            f"factory-class={FACTORY_DESCRIPTOR}",
            f"compat-retriever-class={RETRIEVER_DESCRIPTOR}",
            "google-app-set-type-descriptor-count=0",
            "status=PASS",
        ]
    )
    report.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        dir=report.parent,
        prefix=f".{report.name}.",
        delete=False,
    ) as output:
        output.write("\n".join(lines) + "\n")
        temporary_path = Path(output.name)
    os.replace(temporary_path, report)


def fixture(
    *,
    google_descriptor: bool = False,
    factory_throw: bool = False,
    report_identifier: bool = False,
) -> str:
    provider = (
        "\nClass descriptor  : 'Lcom/google/android/gms/appset/AppSet;'\n"
        if google_descriptor
        else ""
    )
    factory_terminal = (
        "0008: throw v0\n"
        if factory_throw
        else (
            "0008: new-instance v0, "
            "'Lio/appmetrica/analytics/appsetid/internal/AppSetIdRetriever;'\n"
            "000a: return-object v0\n"
            "000e: new-instance v0, 'Lio/appmetrica/analytics/impl/o8;'\n"
            "0013: return-object v0\n"
        )
    )
    retriever_call = (
        "invoke-interface {v1}, "
        "'Lio/appmetrica/analytics/appsetid/internal/AppSetIdListener;"
        ".onAppSetIdRetrieved:(Ljava/lang/String;)V'\n"
        if report_identifier
        else (
            "invoke-interface {v1}, "
            "'Lio/appmetrica/analytics/appsetid/internal/AppSetIdListener;"
            ".onFailure:(Ljava/lang/Throwable;)V'\n"
        )
    )
    return f"""
Class descriptor  : '{INTERFACE_DESCRIPTOR}'
Class descriptor  : '{LISTENER_DESCRIPTOR}'
Class descriptor  : '{RETRIEVER_DESCRIPTOR}'
  name          : 'retrieveAppSetId'
  type          : '(Landroid/content/Context;{LISTENER_DESCRIPTOR})V'
  {retriever_call}
Class descriptor  : '{FACTORY_DESCRIPTOR}'
  name          : 'a'
  type          : '(){INTERFACE_DESCRIPTOR}'
  {factory_terminal}
{provider}
"""


def self_test() -> None:
    if validation_issues([fixture()]):
        raise AssertionError("Valid App Set ID fixture was rejected.")

    cases = {
        "Google App Set descriptor": fixture(google_descriptor=True),
        "throwing AppMetrica factory": fixture(factory_throw=True),
        "identifier callback": fixture(report_identifier=True),
        "missing compatibility interface": fixture().replace(
            f"Class descriptor  : '{INTERFACE_DESCRIPTOR}'\n",
            "",
            1,
        ),
    }
    for label, candidate in cases.items():
        if not validation_issues([candidate]):
            raise AssertionError(f"Invalid fixture was accepted: {label}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--dexdump", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("dex", nargs="*", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        if args.dexdump or args.report or args.dex:
            raise SystemExit("--self-test cannot be combined with DEX inputs.")
        self_test()
        print("App Set ID DEX validator self-test: PASS")
        return 0

    if args.dexdump is None or args.report is None or not args.dex:
        raise SystemExit("--dexdump, --report, and at least one DEX file are required.")
    if not args.dexdump.is_file():
        raise SystemExit(f"dexdump is missing: {args.dexdump}")

    dex_files = sorted(args.dex, key=lambda path: str(path))
    missing = [str(path) for path in dex_files if not path.is_file()]
    if missing:
        raise SystemExit("DEX inputs are missing: " + ", ".join(missing))

    try:
        dumps = [run_dexdump(args.dexdump, dex) for dex in dex_files]
    except RuntimeError as error:
        raise SystemExit(str(error)) from error
    issues = validation_issues(dumps)
    if issues:
        raise SystemExit("App Set ID DEX validation failed:\n- " + "\n- ".join(issues))
    write_report(args.report, args.dexdump, dex_files)
    print(f"App Set ID DEX validation: PASS ({len(dex_files)} DEX file(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
