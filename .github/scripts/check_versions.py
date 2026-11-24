#!/usr/bin/env python3
"""校验发布标签、Gradle versionName 与插件 manifest.version 一致。"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path


def main() -> int:
    tag = os.environ.get("TAG", "")
    build_root = Path(os.environ["BUILD_ROOT"])
    if not tag.startswith("v"):
        raise SystemExit(f"标签必须以 v 开头：{tag}")
    version = tag[1:]

    gradle = (build_root / "lifehub-plugin-memo/apk/build.gradle.kts").read_text(
        encoding="utf-8",
    )
    manifest = json.loads(
        (
            build_root
            / "lifehub-plugin-memo/src/main/assets/plugins/com.lifehub.plugin.memo/manifest.json"
        ).read_text(encoding="utf-8"),
    )

    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if version_name is None:
        raise SystemExit("apk/build.gradle.kts 缺少 versionName")
    if version_name.group(1) != version:
        raise SystemExit(
            f"标签 {tag} 与 Gradle versionName {version_name.group(1)} 不一致",
        )
    if manifest.get("version") != version:
        raise SystemExit(
            f"标签 {tag} 与 manifest.version {manifest.get('version')} 不一致",
        )
    print(f"版本校验通过：{version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
