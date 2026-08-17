#!/usr/bin/env python3
"""Verify immutable released slot-math sources, assets, tests, and archives."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
from typing import Any
import zipfile


SCHEMA = "v-slot-released-math-manifest-v1"
HEX_256 = re.compile(r"[0-9a-f]{64}")
SLOT_IDS = {
    "violet_fortune",
    "roman_reels",
    "neon_nights",
    "pharaoh_gold",
    "ocean_pearl",
}
OUTCOME_MODES = {
    "paid-five-wild",
    "paid-scatter-three",
    "free-extra-wild",
}
TOP_LEVEL_KEYS = {
    "schema",
    "status",
    "mathVersion",
    "descriptorSources",
    "asset",
    "rules",
    "goldenFingerprints",
    "goldenOutcomeDigests",
    "goldenOutcomeSetSha256",
}
MAX_MANIFEST_BYTES = 128 * 1024
MAX_PROTECTED_FILE_BYTES = 2 * 1024 * 1024
TEST_SOURCE = Path(
    "app/src/test/java/com/vslot/app/game/ReleasedSlotMathV5Test.kt"
)
CURRENT_ASSET = Path("app/src/main/assets/slots_config.json")
PACKAGED_MANIFEST_PATH = "assets/released_math/v5/manifest.json"


class DuplicateJsonKey(ValueError):
    pass


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json_bytes(raw: bytes, label: str) -> dict[str, Any]:
    if len(raw) > MAX_MANIFEST_BYTES:
        raise ValueError(f"{label} exceeds {MAX_MANIFEST_BYTES} bytes.")
    try:
        decoded = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"{label} is not valid UTF-8.") from error
    try:
        parsed = json.loads(decoded, object_pairs_hook=unique_object)
    except (json.JSONDecodeError, DuplicateJsonKey) as error:
        raise ValueError(f"{label} is not strict JSON: {error}") from error
    if not isinstance(parsed, dict):
        raise ValueError(f"{label} root must be an object.")
    return parsed


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strict_relative_path(value: Any, label: str) -> tuple[PurePosixPath | None, str | None]:
    if not isinstance(value, str) or not value:
        return None, f"{label} must be a non-empty string."
    path = PurePosixPath(value)
    if path.is_absolute() or str(path) != value or any(part in {"", ".", ".."} for part in path.parts):
        return None, f"{label} must be a normalized repository-relative POSIX path."
    return path, None


def inside_root(root: Path, relative: PurePosixPath) -> Path | None:
    root_resolved = root.resolve()
    candidate = root.joinpath(*relative.parts).resolve()
    try:
        candidate.relative_to(root_resolved)
    except ValueError:
        return None
    return candidate


def exact_keys(value: Any, expected: set[str], label: str, issues: list[str]) -> bool:
    if not isinstance(value, dict):
        issues.append(f"{label} must be an object.")
        return False
    actual = set(value)
    if actual != expected:
        issues.append(
            f"{label} keys differ: missing={sorted(expected - actual)} "
            f"unexpected={sorted(actual - expected)}"
        )
        return False
    return True


def manifest_issues(
    root: Path,
    manifest_path: Path,
    expected_manifest_sha256: str | None = None,
) -> tuple[list[str], dict[str, Any] | None, bytes | None]:
    issues: list[str] = []
    if not manifest_path.is_file():
        return [f"Released-math manifest is missing: {manifest_path}"], None, None
    raw_manifest = manifest_path.read_bytes()
    if expected_manifest_sha256 is not None and sha256_bytes(raw_manifest) != expected_manifest_sha256:
        issues.append("Released-math manifest differs from the external Gradle SHA-256 anchor.")
    try:
        manifest = load_json_bytes(raw_manifest, "Released-math manifest")
    except ValueError as error:
        return [str(error)], None, raw_manifest

    exact_keys(manifest, TOP_LEVEL_KEYS, "Manifest", issues)
    if manifest.get("schema") != SCHEMA:
        issues.append(f"Manifest schema must be {SCHEMA}.")
    if manifest.get("status") != "immutable":
        issues.append("Released V5 manifest status must be immutable.")
    if manifest.get("mathVersion") != 5:
        issues.append("Released V5 manifest mathVersion must equal 5.")

    sources = manifest.get("descriptorSources")
    protected_paths: list[Path] = []
    if not isinstance(sources, list) or len(sources) != 2:
        issues.append("descriptorSources must contain exactly the V5 evaluator and shared model source.")
    else:
        seen_paths: set[str] = set()
        for index, entry in enumerate(sources):
            label = f"descriptorSources[{index}]"
            if not exact_keys(entry, {"path", "bytes", "sha256"}, label, issues):
                continue
            relative, path_issue = strict_relative_path(entry["path"], f"{label}.path")
            if path_issue:
                issues.append(path_issue)
                continue
            assert relative is not None
            if str(relative) in seen_paths:
                issues.append(f"Duplicate protected source path: {relative}")
                continue
            seen_paths.add(str(relative))
            source = inside_root(root, relative)
            if source is None or not source.is_file():
                issues.append(f"Protected source is missing or leaves the repository: {relative}")
                continue
            protected_paths.append(source)
            if source.stat().st_size > MAX_PROTECTED_FILE_BYTES:
                issues.append(f"Protected source is unexpectedly large: {relative}")
            expected_size = entry["bytes"]
            if not isinstance(expected_size, int) or isinstance(expected_size, bool) or expected_size <= 0:
                issues.append(f"{label}.bytes must be a positive integer.")
            elif source.stat().st_size != expected_size:
                issues.append(f"Protected source byte length changed: {relative}")
            expected_hash = entry["sha256"]
            if not isinstance(expected_hash, str) or not HEX_256.fullmatch(expected_hash):
                issues.append(f"{label}.sha256 must be lowercase SHA-256.")
            elif sha256_path(source) != expected_hash:
                issues.append(f"Protected source SHA-256 changed: {relative}")

        expected_sources = {
            "app/src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt",
            "app/src/main/java/com/vslot/app/game/SlotModels.kt",
        }
        if seen_paths != expected_sources:
            issues.append("descriptorSources must protect the exact V5 evaluator and shared models.")

    asset = manifest.get("asset")
    asset_bytes: bytes | None = None
    if exact_keys(asset, {"path", "archivePath", "bytes", "sha256"}, "asset", issues):
        relative, path_issue = strict_relative_path(asset["path"], "asset.path")
        if path_issue:
            issues.append(path_issue)
        else:
            assert relative is not None
            asset_path = inside_root(root, relative)
            if asset_path is None or not asset_path.is_file():
                issues.append(f"Released V5 asset is missing or leaves the repository: {relative}")
            else:
                asset_bytes = asset_path.read_bytes()
                if len(asset_bytes) > MAX_PROTECTED_FILE_BYTES:
                    issues.append("Released V5 asset is unexpectedly large.")
                expected_size = asset["bytes"]
                if not isinstance(expected_size, int) or isinstance(expected_size, bool) or expected_size <= 0:
                    issues.append("asset.bytes must be a positive integer.")
                elif len(asset_bytes) != expected_size:
                    issues.append("Released V5 asset byte length changed.")
                expected_hash = asset["sha256"]
                if not isinstance(expected_hash, str) or not HEX_256.fullmatch(expected_hash):
                    issues.append("asset.sha256 must be lowercase SHA-256.")
                elif sha256_bytes(asset_bytes) != expected_hash:
                    issues.append("Released V5 asset SHA-256 changed.")
        if asset.get("archivePath") != "assets/released_math/v5/slots_config.json":
            issues.append("asset.archivePath must use the immutable V5 archive location.")

    rules = manifest.get("rules")
    if exact_keys(rules, {"paylineCount", "freeSpinsBonusAward", "xpFixtures"}, "rules", issues):
        if rules.get("paylineCount") != 10:
            issues.append("V5 paylineCount must equal 10.")
        if rules.get("freeSpinsBonusAward") != 5:
            issues.append("V5 freeSpinsBonusAward must equal 5.")
        xp = rules.get("xpFixtures")
        expected_xp = {
            "paid-minimum": 9,
            "free-minimum": 5,
            "paid-cap": 68,
            "free-cap": 64,
        }
        if xp != expected_xp:
            issues.append("V5 XP fixtures differ from the released policy.")

    fingerprints = manifest.get("goldenFingerprints")
    if not isinstance(fingerprints, dict) or set(fingerprints) != SLOT_IDS:
        issues.append("goldenFingerprints must contain exactly the five released slot ids.")
        fingerprints = {}
    for slot_id, digest in fingerprints.items():
        if not isinstance(digest, str) or not HEX_256.fullmatch(digest):
            issues.append(f"Invalid golden fingerprint for {slot_id}.")

    outcomes = manifest.get("goldenOutcomeDigests")
    expected_outcome_keys = {
        f"{slot_id}:{mode}" for slot_id in SLOT_IDS for mode in OUTCOME_MODES
    }
    if not isinstance(outcomes, dict) or set(outcomes) != expected_outcome_keys:
        issues.append("goldenOutcomeDigests must contain all 15 released production fixtures.")
        outcomes = {}
    for fixture_id, digest in outcomes.items():
        if not isinstance(digest, str) or not HEX_256.fullmatch(digest):
            issues.append(f"Invalid golden outcome digest for {fixture_id}.")
    outcome_set_payload = "".join(
        f"{fixture_id}={outcomes[fixture_id]}\n" for fixture_id in sorted(outcomes)
    ).encode("utf-8")
    outcome_set_sha256 = manifest.get("goldenOutcomeSetSha256")
    if not isinstance(outcome_set_sha256, str) or not HEX_256.fullmatch(outcome_set_sha256):
        issues.append("goldenOutcomeSetSha256 must be lowercase SHA-256.")
    elif sha256_bytes(outcome_set_payload) != outcome_set_sha256:
        issues.append("goldenOutcomeSetSha256 does not bind the complete sorted outcome set.")

    descriptor = next(
        (path for path in protected_paths if path.name == "ReleasedSlotMathV5.kt"),
        None,
    )
    if descriptor is not None:
        source = descriptor.read_text(encoding="utf-8")
        expected_asset_hash = asset.get("sha256") if isinstance(asset, dict) else None
        required_fragments = {
            "const val VERSION = 5": "V5 version constant",
            'const val ASSET_PATH = "released_math/v5/slots_config.json"': "V5 asset path",
            f'const val ASSET_SHA256 = "{expected_asset_hash}"': "V5 asset SHA constant",
            "fun evaluateStops(": "V5 evaluator",
            "fun xpForSpin(": "V5 XP policy",
            "fun fingerprint(": "V5 fingerprint",
        }
        for fragment, label in required_fragments.items():
            if fragment not in source:
                issues.append(f"Protected descriptor is missing {label}.")

    test_source = inside_root(root, PurePosixPath(TEST_SOURCE.as_posix()))
    if test_source is None or not test_source.is_file():
        issues.append(f"Released V5 golden test is missing: {TEST_SOURCE.as_posix()}")
    else:
        test_text = test_source.read_text(encoding="utf-8")
        required_test_fragments = [
            "production V5 outcomes retain complete paid bonus and free spin digests",
            "V5 journal survives a changed current catalog through released registry",
        ]
        for fragment in required_test_fragments:
            if fragment not in test_text:
                issues.append(f"Released V5 golden test is missing: {fragment}")
        for digest in list(fingerprints.values()) + list(outcomes.values()):
            if digest not in test_text:
                issues.append(f"Released V5 manifest digest is not asserted by the golden test: {digest}")

    return issues, manifest, raw_manifest


def archive_entry_candidates(path: str) -> tuple[str, str]:
    return path, f"base/{path}"


def unique_archive_entry(names: list[str], path: str) -> tuple[str | None, str | None]:
    matches = [candidate for candidate in archive_entry_candidates(path) if names.count(candidate) == 1]
    duplicates = [candidate for candidate in archive_entry_candidates(path) if names.count(candidate) > 1]
    if duplicates:
        return None, f"Archive contains duplicate protected entry: {duplicates[0]}"
    if len(matches) != 1:
        return None, f"Archive must contain exactly one protected entry for {path}."
    return matches[0], None


def archive_issues(
    archive: Path,
    manifest: dict[str, Any],
    raw_manifest: bytes,
) -> list[str]:
    if not archive.is_file():
        return [f"Released-math archive is missing: {archive}"]
    if not zipfile.is_zipfile(archive):
        return [f"Released-math archive is not a ZIP-compatible APK/AAB: {archive}"]
    issues: list[str] = []
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        manifest_entry, manifest_issue = unique_archive_entry(names, PACKAGED_MANIFEST_PATH)
        if manifest_issue:
            issues.append(manifest_issue)
        elif manifest_entry is not None:
            packaged_manifest = package.read(manifest_entry)
            if packaged_manifest != raw_manifest:
                issues.append("Packaged released-math manifest differs from source bytes.")

        asset = manifest["asset"]
        asset_entry, asset_issue = unique_archive_entry(names, asset["archivePath"])
        if asset_issue:
            issues.append(asset_issue)
        elif asset_entry is not None:
            packaged_asset = package.read(asset_entry)
            if len(packaged_asset) > MAX_PROTECTED_FILE_BYTES:
                issues.append("Packaged released V5 asset is unexpectedly large.")
            elif len(packaged_asset) != asset["bytes"]:
                issues.append("Packaged released V5 asset byte length changed.")
            elif sha256_bytes(packaged_asset) != asset["sha256"]:
                issues.append("Packaged released V5 asset SHA-256 changed.")
    return issues


def write_report(
    report: Path,
    manifest_path: Path,
    manifest: dict[str, Any],
    archive: Path | None,
) -> None:
    lines = [
        "schema=v-slot-released-math-validation-v1",
        f"math-version={manifest['mathVersion']}",
        f"manifest-sha256={sha256_path(manifest_path)}",
        f"asset-sha256={manifest['asset']['sha256']}",
        f"descriptor-source-count={len(manifest['descriptorSources'])}",
        f"golden-fingerprint-count={len(manifest['goldenFingerprints'])}",
        f"golden-outcome-digest-count={len(manifest['goldenOutcomeDigests'])}",
    ]
    if archive is not None:
        lines.extend(
            [
                f"archive-name={archive.name}",
                f"archive-sha256={sha256_path(archive)}",
            ]
        )
    lines.append("status=PASS")
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
        temporary = Path(output.name)
    os.replace(temporary, report)


def fixture_manifest(root: Path) -> Path:
    descriptor_path = Path("app/src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt")
    models_path = Path("app/src/main/java/com/vslot/app/game/SlotModels.kt")
    asset_path = Path("app/src/main/assets/released_math/v5/slots_config.json")
    descriptor = root / descriptor_path
    models = root / models_path
    asset = root / asset_path
    test = root / TEST_SOURCE
    current = root / CURRENT_ASSET
    for path in (descriptor, models, asset, test, current):
        path.parent.mkdir(parents=True, exist_ok=True)
    asset.write_bytes(b'{"slots":[]}\n')
    current.write_bytes(asset.read_bytes())
    asset_hash = sha256_path(asset)
    descriptor.write_text(
        "\n".join(
            [
                "const val VERSION = 5",
                'const val ASSET_PATH = "released_math/v5/slots_config.json"',
                f'const val ASSET_SHA256 = "{asset_hash}"',
                "fun evaluateStops(",
                "fun xpForSpin(",
                "fun fingerprint(",
            ]
        ),
        encoding="utf-8",
    )
    models.write_text("data class SlotConfig(val id: String)\n", encoding="utf-8")
    fingerprints = {slot_id: hashlib.sha256(f"fp:{slot_id}".encode()).hexdigest() for slot_id in SLOT_IDS}
    outcomes = {
        f"{slot_id}:{mode}": hashlib.sha256(f"outcome:{slot_id}:{mode}".encode()).hexdigest()
        for slot_id in SLOT_IDS
        for mode in OUTCOME_MODES
    }
    test.write_text(
        "production V5 outcomes retain complete paid bonus and free spin digests\n"
        "V5 journal survives a changed current catalog through released registry\n"
        + "\n".join(fingerprints.values())
        + "\n"
        + "\n".join(outcomes.values()),
        encoding="utf-8",
    )
    manifest = {
        "schema": SCHEMA,
        "status": "immutable",
        "mathVersion": 5,
        "descriptorSources": [
            {
                "path": descriptor_path.as_posix(),
                "bytes": descriptor.stat().st_size,
                "sha256": sha256_path(descriptor),
            },
            {
                "path": models_path.as_posix(),
                "bytes": models.stat().st_size,
                "sha256": sha256_path(models),
            },
        ],
        "asset": {
            "path": asset_path.as_posix(),
            "archivePath": "assets/released_math/v5/slots_config.json",
            "bytes": asset.stat().st_size,
            "sha256": asset_hash,
        },
        "rules": {
            "paylineCount": 10,
            "freeSpinsBonusAward": 5,
            "xpFixtures": {
                "paid-minimum": 9,
                "free-minimum": 5,
                "paid-cap": 68,
                "free-cap": 64,
            },
        },
        "goldenFingerprints": fingerprints,
        "goldenOutcomeDigests": outcomes,
        "goldenOutcomeSetSha256": sha256_bytes(
            "".join(
                f"{fixture_id}={outcomes[fixture_id]}\n"
                for fixture_id in sorted(outcomes)
            ).encode("utf-8")
        ),
    }
    manifest_path = root / "app/src/main/assets/released_math/v5/manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest_path


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="released-slot-math-validator-") as directory:
        root = Path(directory)
        manifest_path = fixture_manifest(root)
        expected_manifest_sha256 = sha256_path(manifest_path)
        issues, manifest, raw_manifest = manifest_issues(
            root,
            manifest_path,
            expected_manifest_sha256,
        )
        if issues or manifest is None or raw_manifest is None:
            raise AssertionError(f"Valid released-math fixture was rejected: {issues}")

        archive = root / "fixture.apk"
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr(PACKAGED_MANIFEST_PATH, raw_manifest)
            package.write(root / manifest["asset"]["path"], manifest["asset"]["archivePath"])
            package.write(root / CURRENT_ASSET, "assets/slots_config.json")
        if archive_issues(archive, manifest, raw_manifest):
            raise AssertionError("Valid released-math archive fixture was rejected.")

        descriptor = root / manifest["descriptorSources"][0]["path"]
        original_descriptor = descriptor.read_bytes()
        descriptor.write_bytes(original_descriptor + b"mutation")
        if not any(
            "Protected source SHA-256 changed" in issue
            for issue in manifest_issues(root, manifest_path, expected_manifest_sha256)[0]
        ):
            raise AssertionError("Validator accepted a mutated released evaluator.")
        descriptor.write_bytes(original_descriptor)

        asset = root / manifest["asset"]["path"]
        original_asset = asset.read_bytes()
        asset.write_bytes(original_asset + b"mutation")
        if not any(
            "asset SHA-256 changed" in issue
            for issue in manifest_issues(root, manifest_path, expected_manifest_sha256)[0]
        ):
            raise AssertionError("Validator accepted a mutated released asset.")
        asset.write_bytes(original_asset)

        parsed = load_json_bytes(raw_manifest, "fixture manifest")
        parsed["descriptorSources"][0]["path"] = "../escape.kt"
        manifest_path.write_text(json.dumps(parsed) + "\n", encoding="utf-8")
        if not any(
            "external Gradle SHA-256 anchor" in issue
            for issue in manifest_issues(root, manifest_path, expected_manifest_sha256)[0]
        ):
            raise AssertionError("Validator accepted a manifest changed without its external anchor.")
        if not any(
            "normalized repository-relative" in issue
            for issue in manifest_issues(root, manifest_path)[0]
        ):
            raise AssertionError("Validator accepted a traversal path.")
        manifest_path.write_bytes(raw_manifest)

        tampered_archive = root / "tampered.apk"
        tampered_asset = bytes([original_asset[0] ^ 0x01]) + original_asset[1:]
        with zipfile.ZipFile(tampered_archive, "w") as package:
            package.writestr(PACKAGED_MANIFEST_PATH, raw_manifest)
            package.writestr(manifest["asset"]["archivePath"], tampered_asset)
            package.write(root / CURRENT_ASSET, "assets/slots_config.json")
        if not any("Packaged released V5 asset SHA-256 changed" in issue for issue in archive_issues(tampered_archive, manifest, raw_manifest)):
            raise AssertionError("Validator accepted a tampered packaged asset.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--expected-manifest-sha256")
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        if any(
            (
                args.root,
                args.manifest,
                args.expected_manifest_sha256,
                args.archive,
                args.report,
            )
        ):
            raise SystemExit("--self-test cannot be combined with validation inputs.")
        self_test()
        print("Released slot-math validator self-test: PASS")
        return 0

    if args.root is None or args.manifest is None or args.expected_manifest_sha256 is None:
        raise SystemExit("--root, --manifest, and --expected-manifest-sha256 are required.")
    if not HEX_256.fullmatch(args.expected_manifest_sha256):
        raise SystemExit("--expected-manifest-sha256 must be lowercase SHA-256.")
    root = args.root.resolve()
    manifest_path = args.manifest.resolve()
    try:
        manifest_path.relative_to(root)
    except ValueError as error:
        raise SystemExit("Manifest must be inside the repository root.") from error

    issues, manifest, raw_manifest = manifest_issues(
        root,
        manifest_path,
        args.expected_manifest_sha256,
    )
    if not issues and args.archive is not None and manifest is not None and raw_manifest is not None:
        issues.extend(archive_issues(args.archive.resolve(), manifest, raw_manifest))
    if issues:
        raise SystemExit("Released slot-math validation failed:\n- " + "\n- ".join(issues))
    assert manifest is not None
    if args.report is not None:
        write_report(args.report, manifest_path, manifest, args.archive)
    scope = "source and archive" if args.archive is not None else "source"
    print(f"Released slot-math validation: PASS ({scope})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
