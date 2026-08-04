#!/usr/bin/env python3
"""Render and validate the production V Slot privacy policy."""

from __future__ import annotations

import argparse
import hashlib
import html
import ipaddress
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TEMPLATE = ROOT / "docs/store/PRIVACY_POLICY_RU_TEMPLATE.xhtml"
PACKAGE_NAME = "com.vslot.app"
PLACEHOLDER_RE = re.compile(r"\{\{([A-Z0-9_]+)}}")
DOCTYPE_RE = re.compile(r"^\s*<!doctype\s+html\s*>", re.IGNORECASE)
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
DOMAIN_LABEL_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
PLACEHOLDER_MARKERS = (
    "replace_with",
    "placeholder",
    "example.",
    "dummy",
    "fake",
    "tbd",
    "unknown",
    "уточнить",
    "your-",
    "тестов",
    "пример",
    "заглуш",
    "{{",
    "}}",
)
RESERVED_HOST_SUFFIXES = (".example", ".invalid", ".localhost", ".test")

PLACEHOLDER_ENV = {
    "V_SLOT_PRIVACY_POLICY_URL": "V_SLOT_PRIVACY_POLICY_URL",
    "POLICY_VERSION": "V_SLOT_POLICY_VERSION",
    "EFFECTIVE_DATE_RU": "V_SLOT_POLICY_EFFECTIVE_DATE_RU",
    "V_SLOT_DEVELOPER_LEGAL_NAME": "V_SLOT_DEVELOPER_LEGAL_NAME",
    "DEVELOPER_LEGAL_ADDRESS": "V_SLOT_DEVELOPER_LEGAL_ADDRESS",
    "V_SLOT_SUPPORT_EMAIL": "V_SLOT_SUPPORT_EMAIL",
    "APPMETRICA_LEGAL_ENTITY": "V_SLOT_APPMETRICA_LEGAL_ENTITY",
    "APPMETRICA_PROCESSING_ROLE": "V_SLOT_APPMETRICA_PROCESSING_ROLE",
    "APPMETRICA_PROCESSING_REGIONS": "V_SLOT_APPMETRICA_PROCESSING_REGIONS",
    "FIREBASE_LEGAL_ENTITY": "V_SLOT_FIREBASE_LEGAL_ENTITY",
    "FIREBASE_PROCESSING_ROLE": "V_SLOT_FIREBASE_PROCESSING_ROLE",
    "FIREBASE_PROCESSING_REGIONS": "V_SLOT_FIREBASE_PROCESSING_REGIONS",
    "PRIVACY_HOST_OPERATOR": "V_SLOT_PRIVACY_HOST_OPERATOR",
    "PRIVACY_HOST_PROCESSING_REGIONS": "V_SLOT_PRIVACY_HOST_PROCESSING_REGIONS",
    "CROSS_BORDER_TRANSFER_BASIS": "V_SLOT_CROSS_BORDER_TRANSFER_BASIS",
    "APPMETRICA_RETENTION_PERIOD": "V_SLOT_APPMETRICA_RETENTION_PERIOD",
    "FIREBASE_RETENTION_PERIOD": "V_SLOT_FIREBASE_RETENTION_PERIOD",
    "PRIVACY_HOST_LOG_RETENTION_PERIOD": "V_SLOT_PRIVACY_HOST_LOG_RETENTION_PERIOD",
    "DATA_DESTRUCTION_PROCESS": "V_SLOT_DATA_DESTRUCTION_PROCESS",
    "DATA_SUBJECT_REQUEST_PROCESS": "V_SLOT_DATA_SUBJECT_REQUEST_PROCESS",
    "REQUEST_RESPONSE_PERIOD": "V_SLOT_REQUEST_RESPONSE_PERIOD",
}

EXPECTED_SECTIONS = {
    "operator",
    "scope",
    "local-data",
    "analytics",
    "push",
    "processors",
    "retention",
    "deletion",
    "security",
    "children",
    "changes",
    "contact",
}


class PolicyError(ValueError):
    pass


def is_placeholder(value: str) -> bool:
    normalized = value.strip().lower()
    return not normalized or any(marker in normalized for marker in PLACEHOLDER_MARKERS)


def validate_https_url(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise PolicyError("V_SLOT_PRIVACY_POLICY_URL must be a public HTTPS URL without user info")
    try:
        parsed.port
    except ValueError as error:
        raise PolicyError("V_SLOT_PRIVACY_POLICY_URL contains an invalid port") from error
    if parsed.query or parsed.fragment:
        raise PolicyError("V_SLOT_PRIVACY_POLICY_URL must not contain a query or fragment")
    hostname = parsed.hostname.lower()
    if hostname == "localhost" or hostname.endswith(RESERVED_HOST_SUFFIXES):
        raise PolicyError("V_SLOT_PRIVACY_POLICY_URL must not use a reserved or local hostname")
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        labels = hostname.split(".")
        if len(labels) < 2 or any(not DOMAIN_LABEL_RE.fullmatch(label) for label in labels):
            raise PolicyError("V_SLOT_PRIVACY_POLICY_URL must use a public fully qualified hostname")
    else:
        if not address.is_global:
            raise PolicyError("V_SLOT_PRIVACY_POLICY_URL must not use a private or non-global IP address")


def validate_email(value: str) -> None:
    if not EMAIL_RE.fullmatch(value):
        raise PolicyError("V_SLOT_SUPPORT_EMAIL must be a valid email address")
    domain = value.rsplit("@", 1)[1].lower()
    if domain == "localhost" or domain.endswith(RESERVED_HOST_SUFFIXES):
        raise PolicyError("V_SLOT_SUPPORT_EMAIL must not use a reserved or local domain")


def validate_developer_name(value: str) -> None:
    if len(value.strip()) < 2 or is_placeholder(value):
        raise PolicyError("V_SLOT_DEVELOPER_LEGAL_NAME must be a real name of at least 2 characters")


def validate_template(template: str) -> None:
    placeholders = set(PLACEHOLDER_RE.findall(template))
    expected = set(PLACEHOLDER_ENV)
    if placeholders != expected:
        missing = sorted(expected - placeholders)
        unknown = sorted(placeholders - expected)
        raise PolicyError(f"template placeholder mismatch; missing={missing}, unknown={unknown}")


def validate_document(
    document: str,
    expected_url: str | None = None,
    expected_email: str | None = None,
    expected_developer_name: str | None = None,
) -> None:
    document_bytes = document.encode("utf-8")
    if len(document_bytes) < 512:
        raise PolicyError("rendered policy must be at least 512 UTF-8 bytes")
    doctype = DOCTYPE_RE.match(document)
    if not doctype:
        raise PolicyError("rendered policy must start with an HTML5 doctype")
    xhtml = document[doctype.end():]
    if "<!doctype" in xhtml.lower():
        raise PolicyError("rendered policy must contain exactly one doctype")
    normalized = document.lower()
    if (
        PLACEHOLDER_RE.search(document)
        or any(marker in normalized for marker in PLACEHOLDER_MARKERS)
        or re.search(r"\bTODO\b", document, re.IGNORECASE)
    ):
        raise PolicyError("rendered policy still contains placeholders")
    try:
        root = ET.fromstring(xhtml)
    except ET.ParseError as error:
        raise PolicyError(f"rendered policy is not valid XHTML: {error}") from error

    elements = list(root.iter())
    if any("}" in element.tag or ":" in element.tag for element in elements):
        raise PolicyError("XML namespaces are forbidden")
    if any("}" in name or ":" in name for element in elements for name in element.attrib):
        raise PolicyError("namespaced attributes are forbidden")
    language = root.attrib.get("lang", "").lower()
    if root.tag.lower() != "html" or (language != "ru" and not language.startswith("ru-")):
        raise PolicyError("policy root must be <html lang=\"ru\">")
    if sum(element.tag.lower() == "head" for element in elements) != 1:
        raise PolicyError("policy must contain exactly one head element")
    if sum(element.tag.lower() == "body" for element in elements) != 1:
        raise PolicyError("policy must contain exactly one body element")

    titles = [element for element in elements if element.tag.lower() == "title"]
    title = " ".join(titles[0].itertext()).strip() if len(titles) == 1 else ""
    if "V Slot" not in title or "конфиденциальност" not in title.lower():
        raise PolicyError("policy title must identify the V Slot privacy policy")

    forbidden = {"script", "iframe", "form", "object", "embed", "base"}
    found_forbidden = sorted({element.tag.lower() for element in elements if element.tag.lower() in forbidden})
    if found_forbidden:
        raise PolicyError(f"policy contains forbidden elements: {found_forbidden}")

    links = [element for element in elements if element.tag.lower() == "link"]
    canonical_links = []
    for link in links:
        rel_tokens = {token for token in link.attrib.get("rel", "").lower().split() if token}
        if "canonical" not in rel_tokens:
            raise PolicyError("external link resources are forbidden")
        canonical_links.append(link)
    if len(canonical_links) != 1:
        raise PolicyError("policy must contain exactly one canonical link")
    canonical = canonical_links[0].attrib.get("href", "")
    validate_https_url(canonical)
    if expected_url and canonical != expected_url:
        raise PolicyError("canonical URL does not match V_SLOT_PRIVACY_POLICY_URL")

    for element in elements:
        for attribute_name, attribute_value in element.attrib.items():
            name = attribute_name.lower()
            value = attribute_value.strip().lower()
            if name in {"src", "srcset", "poster"}:
                raise PolicyError(f"{name} resources are forbidden")
            if name == "style" and "url(" in value:
                raise PolicyError("CSS URL resources are forbidden")
            if name.startswith("on"):
                raise PolicyError("event handler attributes are forbidden")
            if name in {"href", "action", "formaction"} and re.match(
                r"^(?:javascript|data|vbscript):",
                value,
            ):
                raise PolicyError("executable or embedded URLs are forbidden")
        if element.tag.lower() == "style":
            css = "".join(element.itertext()).lower()
            if "url(" in css or "@import" in css:
                raise PolicyError("external CSS resources are forbidden")
        if (
            element.tag.lower() == "meta"
            and element.attrib.get("http-equiv", "").lower() == "refresh"
        ):
            raise PolicyError("meta refresh is forbidden")

    section_elements: dict[str, list[ET.Element]] = {}
    for element in elements:
        if element.tag.lower() != "section":
            continue
        section_name = element.attrib.get("data-v-slot-policy-section", "").strip()
        if section_name:
            section_elements.setdefault(section_name, []).append(element)
    section_names = set(section_elements)
    if section_names != EXPECTED_SECTIONS:
        raise PolicyError(
            f"policy section mismatch; missing={sorted(EXPECTED_SECTIONS - section_names)}, "
            f"unknown={sorted(section_names - EXPECTED_SECTIONS)}"
        )
    for section_name in sorted(EXPECTED_SECTIONS):
        matching_sections = section_elements[section_name]
        if len(matching_sections) != 1:
            raise PolicyError(f"policy section {section_name} must appear exactly once")
        section_text = " ".join(" ".join(matching_sections[0].itertext()).split())
        if len(section_text) < 80:
            raise PolicyError(f"policy section {section_name} must contain substantive reviewed text")

    policy_text = " ".join(" ".join(root.itertext()).split())
    if "V Slot" not in policy_text or PACKAGE_NAME not in policy_text:
        raise PolicyError(f"policy must identify V Slot and package {PACKAGE_NAME}")
    if expected_developer_name and expected_developer_name not in policy_text:
        raise PolicyError("policy does not contain V_SLOT_DEVELOPER_LEGAL_NAME")
    if expected_email and expected_email not in policy_text:
        raise PolicyError("policy does not contain V_SLOT_SUPPORT_EMAIL")

    mailto_links = [
        element
        for element in elements
        if element.tag.lower() == "a"
        if element.attrib.get("href", "").startswith("mailto:")
    ]
    if not mailto_links:
        raise PolicyError("policy must contain a support mailto link")
    for link in mailto_links:
        email = link.attrib["href"][7:]
        validate_email(email)
        visible_email = " ".join(" ".join(link.itertext()).split())
        if visible_email != email:
            raise PolicyError("visible support email must match its mailto link")
        if expected_email and email != expected_email:
            raise PolicyError("support mailto does not match V_SLOT_SUPPORT_EMAIL")


def render_policy(template_path: Path, output_path: Path) -> str:
    template = template_path.read_text(encoding="utf-8")
    validate_template(template)

    missing_env = sorted({env_name for env_name in PLACEHOLDER_ENV.values() if not os.environ.get(env_name, "").strip()})
    if missing_env:
        raise PolicyError(f"missing required environment variables: {', '.join(missing_env)}")

    values = {placeholder: os.environ[env_name].strip() for placeholder, env_name in PLACEHOLDER_ENV.items()}
    placeholder_values = sorted(
        env_name
        for placeholder, env_name in PLACEHOLDER_ENV.items()
        if is_placeholder(values[placeholder])
    )
    if placeholder_values:
        raise PolicyError(f"placeholder-like values are forbidden: {', '.join(placeholder_values)}")

    validate_https_url(values["V_SLOT_PRIVACY_POLICY_URL"])
    validate_email(values["V_SLOT_SUPPORT_EMAIL"])
    validate_developer_name(values["V_SLOT_DEVELOPER_LEGAL_NAME"])

    rendered = PLACEHOLDER_RE.sub(
        lambda match: html.escape(values[match.group(1)], quote=True),
        template,
    )
    validate_document(
        rendered,
        expected_url=values["V_SLOT_PRIVACY_POLICY_URL"],
        expected_email=values["V_SLOT_SUPPORT_EMAIL"],
        expected_developer_name=values["V_SLOT_DEVELOPER_LEGAL_NAME"],
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=output_path.parent,
        prefix=f".{output_path.name}.",
        delete=False,
    ) as temporary:
        temporary.write(rendered)
        temporary_path = Path(temporary.name)
    temporary_path.chmod(0o644)
    temporary_path.replace(output_path)
    return hashlib.sha256(rendered.encode("utf-8")).hexdigest()


def required_check_values() -> tuple[str, str, str]:
    required_names = (
        "V_SLOT_PRIVACY_POLICY_URL",
        "V_SLOT_SUPPORT_EMAIL",
        "V_SLOT_DEVELOPER_LEGAL_NAME",
    )
    values = {name: os.environ.get(name, "").strip() for name in required_names}
    missing = sorted(name for name, value in values.items() if not value)
    if missing:
        raise PolicyError(f"checking a rendered policy requires: {', '.join(missing)}")
    placeholders = sorted(name for name, value in values.items() if is_placeholder(value))
    if placeholders:
        raise PolicyError(f"placeholder-like check values are forbidden: {', '.join(placeholders)}")
    validate_https_url(values["V_SLOT_PRIVACY_POLICY_URL"])
    validate_email(values["V_SLOT_SUPPORT_EMAIL"])
    validate_developer_name(values["V_SLOT_DEVELOPER_LEGAL_NAME"])
    return (
        values["V_SLOT_PRIVACY_POLICY_URL"],
        values["V_SLOT_SUPPORT_EMAIL"],
        values["V_SLOT_DEVELOPER_LEGAL_NAME"],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--output", type=Path, help="render policy to this path using environment values")
    group.add_argument("--check", type=Path, help="validate an already-rendered policy")
    group.add_argument("--check-template", action="store_true", help="validate template placeholder coverage")
    args = parser.parse_args()

    try:
        if args.check_template:
            validate_template(args.template.read_text(encoding="utf-8"))
            print(f"Privacy policy template is valid: {args.template}")
            return 0
        if args.check:
            expected_url, expected_email, expected_developer_name = required_check_values()
            document = args.check.read_text(encoding="utf-8")
            validate_document(
                document,
                expected_url=expected_url,
                expected_email=expected_email,
                expected_developer_name=expected_developer_name,
            )
            digest = hashlib.sha256(document.encode("utf-8")).hexdigest()
            print(f"Privacy policy is valid: {args.check} (sha256={digest})")
            return 0

        digest = render_policy(args.template, args.output)
        print(f"Rendered {args.output} (sha256={digest})")
        return 0
    except (OSError, PolicyError) as error:
        print(f"Privacy policy error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
