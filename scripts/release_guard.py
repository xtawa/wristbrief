#!/usr/bin/env python3
"""Narrow, deterministic release-security checks for WristBrief Android packaging."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
WEAR_MANIFEST = "app/src/main/AndroidManifest.xml"
MOBILE_MANIFEST = "mobile/src/main/AndroidManifest.xml"
WEAR_GRADLE = "app/build.gradle.kts"
MOBILE_GRADLE = "mobile/build.gradle.kts"


def fail(message: str) -> None:
    print(f"release-guard: {message}", file=sys.stderr)
    raise SystemExit(1)


def application(path: str):
    root = ET.parse(path).getroot()
    app = root.find("application")
    if app is None:
        fail(f"{path}: missing <application>")
    return app


def require_hardened_application(path: str) -> None:
    app = application(path)
    if app.get(ANDROID + "usesCleartextTraffic") != "false":
        fail(f"{path}: usesCleartextTraffic must remain false")
    if app.get(ANDROID + "allowBackup") != "false":
        fail(f"{path}: allowBackup must remain false")


def require_private_media_service(path: str) -> None:
    app = application(path)
    for service in app.findall("service"):
        if service.get(ANDROID + "name") == ".media.PodcastPlaybackService":
            if service.get(ANDROID + "exported") != "false":
                fail(f"{path}: PodcastPlaybackService must not be exported")
            return
    fail(f"{path}: PodcastPlaybackService missing")


def require_wear_standalone_declaration(path: str) -> None:
    app = application(path)
    for item in app.findall("meta-data"):
        if item.get(ANDROID + "name") == "com.google.android.wearable.standalone":
            if item.get(ANDROID + "value") not in {"true", "false"}:
                fail(f"{path}: Wear standalone metadata must be an explicit boolean")
            return
    fail(f"{path}: missing com.google.android.wearable.standalone metadata")


def gradle_scalar(path: str, name: str) -> str:
    text = Path(path).read_text(encoding="utf-8")
    match = re.search(rf"\b{re.escape(name)}\s*=\s*(?:\"([^\"]+)\"|(\d+))", text)
    if not match:
        fail(f"{path}: missing literal {name}")
    return match.group(1) or match.group(2)


def require_cross_device_identity() -> None:
    wear_id = gradle_scalar(WEAR_GRADLE, "applicationId")
    mobile_id = gradle_scalar(MOBILE_GRADLE, "applicationId")
    if wear_id != mobile_id:
        fail("phone and Wear applicationId must match for the Wearable Data Layer")

    wear_code = int(gradle_scalar(WEAR_GRADLE, "versionCode"))
    mobile_code = int(gradle_scalar(MOBILE_GRADLE, "versionCode"))
    if wear_code == mobile_code:
        fail("phone and Wear versionCode values must be unique for shared-package multi-APK delivery")
    if wear_code <= mobile_code:
        fail("Wear versionCode must remain above the overlapping mobile APK lane")


def require_no_embedded_server_bearer() -> None:
    wear_gradle = Path(WEAR_GRADLE).read_text(encoding="utf-8")
    forbidden = ("WRISTBRIEF_GATEWAY_TOKEN", "wristBriefGatewayToken")
    if any(value in wear_gradle for value in forbidden):
        fail("Wear build must not embed the legacy Gateway server bearer")


def main() -> None:
    for manifest in (WEAR_MANIFEST, MOBILE_MANIFEST):
        if not Path(manifest).is_file():
            fail(f"missing {manifest}")
        require_hardened_application(manifest)
    require_private_media_service(WEAR_MANIFEST)
    require_wear_standalone_declaration(WEAR_MANIFEST)
    require_cross_device_identity()
    require_no_embedded_server_bearer()
    print("release-guard: Android security and cross-device packaging invariants OK")


if __name__ == "__main__":
    main()
