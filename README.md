# 任务清单插件（LifeHub）

[LifeHub](https://github.com/moke2585/lifehub) 的第一方任务清单插件。仓库同时包含声明式插件模块与 headless APK：宿主渲染页面，插件通过 `ContentProvider` 提供 manifest、页面、action 与数据。

| 项 | 值 |
|---|---|
| 插件 ID | `com.lifehub.plugin.memo` |
| APK 包名 | `com.lifehub.plugin.memo` |
| Provider authority | `com.lifehub.plugin.memo.provider` |
| 版本 | 1.1.0（versionCode 1） |
| 插件 SDK | 1（`minHostVersion=1`） |
| 数据 schema | 1 |
| 页面 | 4 |
| 能力 | `ui.action`、`storage.private` |

## 功能

- 任务：标题、正文、截止日期（年月日滚轮）、1–5 级优先级、40 个 Emoji 图标、置顶。
- 紧急指数与排序：按优先级与剩余时间计算，支持紧急/最近排序与置顶分组。
- 完成与回收站：滑动完成/删除、撤销、30 天回收站、恢复与永久删除。
- 日常任务倍率：点击 emoji 独立累加当日倍数，连续操作只保留最新提示。
- 快速添加：解析“明天 18:00 交报告 p5”式输入中的日期与 p1–p5。
- 搜索与筛选：全部/今天/7 天内/已逾期/日常，独立搜索页。
- 宿主集成：主页小组件“今日任务”、全局搜索数据源 `memo_search`。

## 页面

| 页面 ID | 标题 |
|---|---|
| `list` | 任务清单 |
| `edit` | 任务编辑 |
| `trash` | 回收站 |
| `search` | 搜索任务 |

## 仓库结构

```text
lifehub-plugin-memo/
├── build.gradle.kts                 # :plugins:memo，声明式插件模块（handler + assets）
├── src/main/java/com/lifehub/app/plugins/MemoPlugin.kt
├── src/main/assets/plugins/com.lifehub.plugin.memo/
│   ├── manifest.json
│   └── pages/*.json
├── src/test/java/com/lifehub/app/plugins/MemoPluginTest.kt
└── apk/                             # :plugins:memo-apk，headless APK
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/lifehub/plugin/memo/apk/
            ├── MemoApkProvider.kt
            └── MemoApkStorage.kt
```

宿主不再内置任务清单；`:plugins:memo` 作为共享库被 APK 引用，manifest、页面与资产随 APK 打包，由 provider 读取并返回给宿主。

## APK 插件运行时

- 发现：宿主通过 action `com.lifehub.plugin.apk.PROVIDER` 查询 provider；Android 11+ 需宿主声明 `<queries>`（宿主已配置）。
- 调用：宿主使用 `ContentResolver.call` 请求以下方法：

| 方法 | 作用 |
|---|---|
| `version` | 协议版本与插件版本 |
| `manifest` | 插件 manifest JSON |
| `page` | 指定页面 JSON |
| `action` | 执行用户操作 |
| `data` | 返回页面数据源 |
| `exportBackup` | 导出插件自有 SQLite 快照 |
| `importBackup` | 导入快照（事务覆盖） |

- 参数与结果使用 Bundle：`json`、`ok`、`error`、`version`；单次载荷上限 512 KiB，超限返回错误。
- Provider 校验调用方包名为 `com.lifehub.app` 或 `com.lifehub.app.debug`。
- 插件使用自有 SQLite：`records(collection, id, payload, created_at, updated_at)`，主键 `(collection, id)`，沿用宿主的记录限额。
- 备份：宿主备份 v1 把快照写入 `externalPayload`；旧宿主的集合备份在导入时转换为快照后迁移。
- 卸载 APK 会删除插件数据库；宿主保留“任务清单 · 未安装”占位，重装后重新发现。

协议细节见 [APK 插件契约 v1](https://github.com/moke2585/lifehub/blob/main/docs/apk-plugin-v1.md)。

## 构建与测试

本仓库不能单独构建：插件模块由同级宿主仓库的 `settings.gradle.kts` 引入。请把本仓库与 `lifehub` 放在同一父目录。

```powershell
cd ..\lifehub

# 声明式插件单元测试（27 项）
.\gradlew.bat :plugins:memo:testDebugUnitTest

# 调试 APK
.\gradlew.bat :plugins:memo-apk:assembleDebug

# 签名的发布 APK（需要 lifehub.release.* 或 LIFEHUB_RELEASE_* 签名配置）
.\gradlew.bat :plugins:memo-apk:assembleRelease

# 宿主与本插件的插件包校验
.\gradlew.bat :dev-harness:validatePlugins
```

产物：

- Debug：`apk/build/outputs/apk/debug/memo-apk-debug.apk`
- Release：`apk/build/outputs/apk/release/memo-apk-release.apk`

## 安装

```powershell
adb install -r ..\lifehub-plugin-memo\apk\build\outputs\apk\debug\memo-apk-debug.apk
```

- Debug 与 Release 使用不同签名，切换时需先卸载旧包。
- Release 签名与宿主共用 `lifehub.release.*` 配置，证书指纹见 [发布检查表](https://github.com/moke2585/lifehub/blob/main/docs/release-checklist.md)。
- 安装后宿主自动发现；卸载后首页显示“任务清单 · 未安装”，不影响其他插件。

## 数据与能力

- 能力：`ui.action`（任务增删改查、完成、排序、搜索、回收站）与 `storage.private`（任务、截止日期、优先级与回收站数据）。
- 不声明网络权限，任务内容只保存在插件私有数据库。

开发文档：[插件开发指南](https://github.com/moke2585/lifehub/blob/main/docs/plugin-development.md)
