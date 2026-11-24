#!/usr/bin/env python3
"""生成插件 Release 元数据，供索引仓库扫描。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--gradle", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--signer", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    gradle = Path(args.gradle).read_text(encoding="utf-8")
    apk = Path(args.apk)

    application_id = required_group(gradle, r'applicationId\s*=\s*"([^"]+)"', "applicationId")
    version_code = int(required_group(gradle, r"versionCode\s*=\s*(\d+)", "versionCode"))
    version_name = required_group(gradle, r'versionName\s*=\s*"([^"]+)"', "versionName")
    if version_name != manifest["version"]:
        raise SystemExit("Gradle versionName 与 manifest.version 不一致")

    metadata = {
        "schema": 1,
        "pluginId": manifest["pluginId"],
        "name": manifest["name"],
        "version": manifest["version"],
        "artifactType": "apk",
        "applicationId": application_id,
        "versionCode": version_code,
        "url": args.url,
        "sha256": sha256_file(apk),
        "signerSha256": args.signer,
        "minHostVersion": manifest["minHostVersion"],
        "pluginSdkVersion": manifest["pluginSdkVersion"],
        "capabilities": [capability["id"] for capability in manifest.get("capabilities", [])],
        "repo": args.repo,
        "sizeBytes": apk.stat().st_size,
        "tag": args.tag,
        "commit": args.commit,
    }

    output = Path(args.out)
    output.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"metadata={output}")
    print(f"sha256={metadata['sha256']}")
    return 0


def required_group(text: str, pattern: str, field: str) -> str:
    match = re.search(pattern, text)
    if match is None:
        raise SystemExit(f"apk/build.gradle.kts 缺少 {field}")
    return match.group(1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    main()
