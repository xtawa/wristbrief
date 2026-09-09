#!/usr/bin/env python3
"""Narrow, deterministic release-security checks for WristBrief Android manifests."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


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


def main() -> None:
    for manifest in ("app/src/main/AndroidManifest.xml", "mobile/src/main/AndroidManifest.xml"):
        if not Path(manifest).is_file():
            fail(f"missing {manifest}")
        require_hardened_application(manifest)
    require_private_media_service("app/src/main/AndroidManifest.xml")
    print("release-guard: manifest security invariants OK")


if __name__ == "__main__":
    main()
