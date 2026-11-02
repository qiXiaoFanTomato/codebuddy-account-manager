# 发布与更新流程

插件主页：<https://plugins.jetbrains.com/plugin/35719-codebuddy-account-manager>

## 1. 改版本号

`build.gradle.kts`：

```kotlin
version = "0.10.4"
```

版本号遵循 SemVer，Marketplace 的 `SemVer Only` 开关已开启，不合规的版本号会被拒。

## 2. 写变更说明

两处都要改，前者给人看，后者上市场：

- `CHANGELOG.md`：新增一个版本小节，写明新增/修复/变更。
- `src/main/resources/META-INF/plugin.xml` 的 `<change-notes>`：市场版本页展示的就是这段 HTML。

## 3. 构建

```powershell
.\gradlew.bat buildPlugin
# 产物：build/distributions/codebuddy-account-manager-<version>.zip
```

构建前建议先自查：

```powershell
.\gradlew.bat verifyPluginStructure   # plugin.xml 结构
.\gradlew.bat verifyPlugin            # 与目标 IDE 的兼容性
```

## 4. 发布更新

### 方式 A：网页上传（无需配置，推荐）

1. 打开插件页 → `Upload Update`；
2. 选择 `build/distributions/*.zip`；
3. 选择更新渠道（`Stable` 为正式版）；
4. 可选填 `What's new`，提交后进入审核。

### 方式 B：命令行发布

1. 在 `plugins.jetbrains.com` → 头像 → `Tokens` 生成永久 token；
2. 设置环境变量后执行：

```powershell
$env:PUBLISH_TOKEN = "<token>"
.\gradlew.bat publishPlugin
```

`publishPlugin` 会自动挑选 `build/distributions` 下最新的 zip，并按 zip 内的版本号发布。

## 5. 审核

- 提交后状态为 `Sent for moderation`，结果通过邮件发到 JetBrains 账号邮箱；
- 审核期间只有作者能在插件页看到新版本；
- 审核通过后版本自动对所有 `sinceBuild`–`untilBuild` 范围内的 IDE 可见。

## 6. 兼容范围

`build.gradle.kts` 的 `patchPluginXml` 控制：

```kotlin
sinceBuild.set("262")
untilBuild.set("262.*")
```

支持新的 IDE 大版本时同步调整这两行，例如放宽到 `untilBuild.set("263.*")`。

## 7. 已踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 上传返回 403 | 未在网页上勾选并接受 JetBrains Marketplace Developer Agreement | 首次进入 Upload 页时勾选接受 |
| 上传返回 400：`description must start with Latin characters` | 描述以 HTML 标签或中文开头 | 让 `<description>` 第一段以英文句子开头，中文放后面 |
| 上传返回 400：`extension point ... must have 'id' attribute` | `<statusBarWidgetFactory>` 缺 `id` | 补 `id`，值与实现类 `getId()` 返回值一致 |
| 市场详情页截图不显示 | 描述里的图片是外部链接，被拦或被墙 | 在编辑页 `Media → Plugin Screenshots` 直接上传图片 |
| 截图分辨率提示过低 | 建议 ≥ 1200×760 | 截取整个 IDE 窗口，而不是仅面板区域 |
