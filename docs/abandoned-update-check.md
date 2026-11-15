# 弃用记录：应用内检测 GitHub Release 更新

> **状态（2026-11-16 更新）：已复活。**
> v1.0.2 起重新引入该功能，代码取自本文 §5.2 记录的 `b48f28e`，并按三处做了改造：
> ① 签名固定（GitHub Secrets）后「下载回来装不上」的前提消失；
> ② 版本比较改为**优先比 versionCode**（发版说明里带 `<!-- versionCode=N -->`）；
> ③ 增加「设置 → 联网与隐私 → 检查更新」开关，关掉即零请求。
> 本文保留为**决策与踩坑记录**，实现现状以代码与 README 为准。

> **原状态**：已实现 → 已废弃 → 已回滚。代码不在主线，仅存于本地 reflog。
> **时间**：2026-11-12 设计并实现，同日废弃并 `git reset --hard` 回滚。
> **相关提交**：`91f247d`（feature 实现）、`b48f28e`（UI 合并 + 版本号 1.1.0）——两个都是**本地提交，从未 push**。
> **回滚点**：回到 `cb5ec96`（v1.0.1 / versionCode 2）。
> **结论一句话**：能查到新版，但下回来的包**装不上**，净收益为负，整体取消。

---

## 1. 当初要做什么

原始需求（按对话顺序整理）：

1. 检测 GitHub 上**自己的最新 Release 构建**；
2. 提供下载能力 + 前往发布页；
3. 冷启动时检测，弹窗提示：「稍后提醒」「立即更新」，下面一行可点的小字「前往 GitHub 下载」；
4. 配置页「关于」里，**版本旁边**显示「已是最新 / 检测到更新」，点击弹出与开屏同一套提示框；
5. **不自己写下载器**，直接调系统浏览器下载；
6. 补联网权限，文案写清「仅用于联网检测更新」，适配不同安卓机型；
7. README 同步说明。

---

## 2. 最终形态（回滚前的样子）

### 2.1 分层

| 层 | 内容 |
|---|---|
| `core`（纯 JVM，可离线单测） | `VersionCompare`、`ReleaseInfo` / `ReleaseAsset`、`GitHubReleaseApi`、`simplifyReleaseNotes`、`formatSize`、`formatDate` |
| `app` | `UpdateManager`（编排 + 状态机）、`ui/update/UpdateDialogs.kt`、`ConfigScreen` 里的一行按钮、`SettingsStore` / `AppSettings` 的 5 个新键 |

### 2.2 数据流

```
冷启动（解锁后）
  └─ 偏好是否已从 DataStore 读出？
       ├─ 否 → 不动作
       └─ 是 → UpdateManager.checkOnLaunch()
               ├─ 6h 内检查过 → 用磁盘上的结论点亮按钮，不联网
               └─ 否则 → GET api.github.com/repos/XaviorShen/BaiyunKeys-Android/releases/latest
                        ├─ 200 → 解析 tag / notes / assets → 比版本
                        │        ├─ 更新 → 弹窗（同版本 24h 内不重复弹）
                        │        └─ 不更新 → 静默，只点亮按钮
                        ├─ 404 → 视为「还没有正式 Release」，按已是最新处理
                        ├─ 403/429 → 「请求过于频繁，请稍后再试」
                        └─ 其它 → 静默失败
```

### 2.3 状态机

```
Idle ──check()──► Checking ──┬─► UpToDate
                             ├─► Available(release) ──► 弹窗
                             └─► Failed(message)
```

弹窗内容（`UpdatePrompt`）三态：`Available` / `UpToDate` / `Failed`。开屏检测与手动检查共用同一套弹窗。

### 2.4 交互与跳转

| 入口 | 文案 | 行为 |
|---|---|---|
| 弹窗主按钮 | **立即更新** | 打开**最新正式 Release 里 `BaiyunKeys-Android.apk` 的直链**；无资产时退回 Releases 列表页 |
| 弹窗次按钮 | **稍后提醒**（原叫「下次更新」，歧义改掉） | 关闭弹窗，同版本 24h 内不再弹 |
| 弹窗小字 | **前往 GitHub 下载** | 打开**项目 Releases 列表页**（不是单个 tag 详情页） |
| 关于页按钮 | 检查更新 / 正在检查… / **已是最新 · 重新检查** / **检测到更新 · 查看详情** / 检查失败 · 点此重试 | 唯一入口；有更新时点它直接重看弹窗，不再联网 |
| 首次启动 | 联网说明弹窗 | 只提示一次，按钮居中 |

按钮文案刻意写成「状态 + 动作」，避免只显示「已是最新」让人以为不可点。

### 2.5 关键参数

| 参数 | 值 | 理由 |
|---|---|---|
| 开屏检测间隔 | 6h（`UPDATE_CHECK_INTERVAL_MS`） | 别每次冷启动都打 GitHub |
| 弹窗抑制 | 同 tag 24h（`UPDATE_PROMPT_TTL_MS`） | 「稍后提醒」「立即更新」都记时间戳 |
| 网络超时 | 10s | 检测是锦上添花，不能让人干等 |
| 未认证限流 | 60 次/小时/IP | 手动检查绕过节流，仍够用 |

### 2.6 版本比较规则

- tag 去 `v`/`V` 前缀，裁掉 `-beta`、`+build` 后缀；
- 点分数字逐段比，段数不齐按 `0` 补（`1.0 == 1.0.0`）；
- **只比 versionName，不比 versionCode**（GitHub 上没有 versionCode）；
- 本地高于线上（开发机常见）按「已是最新」处理。

### 2.7 权限

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- `INTERNET` 是 normal 权限，**安装即授予，没有运行时弹窗**；所谓「索要权限」只能做成 Manifest 声明 + 首次启动的说明弹窗。
- `ACCESS_NETWORK_STATE` 用于离线时直接跳过检测，不干等超时。
- 因为**不写下载器**，所以既不需要存储权限，也不需要 `REQUEST_INSTALL_PACKAGES` / FileProvider。

### 2.8 存储

`AppSettings` / `SettingsStore` 新增 5 个键：

| 键 | 含义 |
|---|---|
| `updateLastCheckAt` | 上次检查时间戳（节流用） |
| `updateKnownTag` | 上次结论：空 = 没查过 / `latest` = 已是最新 / 否则是远端 tag |
| `updatePromptedVersion` | 上次弹过提示的 tag |
| `updatePromptedAt` | 上次弹提示的时间 |
| `networkNoticeShown` | 「联网说明」是否已提示 |

`updateKnownTag` 是实测后补的：进程重启且被 6h 节流跳过时，内存状态是 `Idle`，按钮会退回「检查更新」，把已知结论丢掉。

---

## 3. 为什么废弃

### 3.1 直接原因：签名不一致，下载回来装不上

| APK 来源 | 证书 DN | 证书 SHA-256 |
|---|---|---|
| 本机构建 | `C=US, O=Android, CN=Android Debug` | `8d9556d02fcd174b052e1d3d27bf0b1d3ed9411c342233a92ea4d075b161fe5b` |
| GitHub Release v1.0.1 | `C=US, O=Android, CN=Android Debug` | `478bbf84190f1b0e3886f679b1b615a30cb3b618f76ce4300659d96b7321c780` |

**DN 一样，公钥不一样**——两把都是自动生成的 debug key。

根因链条：

1. 仓库里没有提交任何 keystore；
2. `app/build.gradle.kts` 的 `release` 直接 `signingConfig = signingConfigs.getByName("debug")`；
3. debug keystore 由 AGP 在构建时生成到 `~/.android/debug.keystore`；
4. GitHub Actions 的 runner 是一次性的，**每次构建都重新生成一把随机 key**。

推论：

- CI 的第 N 次构建和第 N+1 次签名不同；
- CI 和你本机也不同；
- 因此**「立即更新」下回来的包在任何现存安装上都无法覆盖**，只会得到下面这条报错。

模拟器实测（MuMu / Android 15 / API 35）：

```
# 先撞版本号
Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected:
         Update version code 2 is older than current 3]

# 加 -d 跳过降级检查后，签名问题才暴露
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
         com.xavior.baiyunkeys signatures do not match newer version; ignoring!]
```

### 3.2 为什么不用「统一签名」来救

统一签名有两条路，技术上都可行：

| 方案 | 做法 | 代价 |
|---|---|---|
| A | 生成固定 keystore，提交进仓库，debug/release 共用 | 私钥公开：任何人可签出同签名「更新包」，覆盖安装后能读到自己 App 的数据目录（门禁 Key、SM2 私钥）。个人侧载项目风险可控但不可撤销 |
| B | keystore 放 GitHub Secrets，本地与 CI 各存一份 | 仓库干净，配置成本略高 |

**但两条路都要求现存安装卸载重装一次**——因为手机上那个包是 CI 的随机 key 签的，和以后所有的都对不上。

而小米等 ROM 对侧载 APK 还有「纯净模式 / 外部来源应用检查」额外拦截。

最终判断：**为了一个自更新入口，让所有已装机（含门禁数据）卸载一次，不划算**，功能取消。

### 3.3 顺带踩到的其它坑

| 坑 | 说明 |
|---|---|
| versionCode 必须递增 | 哪怕签名统一，新包 code 小于已装版本就报 `INSTALL_FAILED_VERSION_DOWNGRADE`。CI 应加「tag ↔ versionName ↔ versionCode」校验 |
| Release 正文为空 | 该仓库 `v*` Release 的 body 只有一行 `**Full Changelog**: …`，去噪后弹窗只剩「本次更新没有提供说明」。发版需手写描述 |
| 浏览器介入 | Chrome 会对 APK 提示「此类文件可能有害」，且需给浏览器「安装未知应用」权限；这些都在 App 之外，只能靠文案提示 |
| 分不清两条通道 | 滚动 `latest`（prerelease，tag 是字面量 `latest`）无法做版本比较，只能检测正式 `v*` Release；仓库没有正式版本时一律显示「已是最新」 |

---

## 4. 当时的实测记录（回滚前）

| 场景 | 结果 |
|---|---|
| 首次冷启动 | 「联网说明」弹窗正常，按钮居中 |
| 冷启动检测（清数据后） | 真实请求 GitHub，`远端 v1.0.1 / 本地 1.0.1 / 有新版本=false` |
| 节流跳过 + 重启进程 | 日志 `沿用上次结论 latest`，按钮直接显示「已是最新」，**零网络请求** |
| 手动复查 | 绕过 6h 节流，弹「已是最新版本」 |
| 有新版本（临时把比较基准降到 0.9.0 触发） | 弹窗显示 `发现新版本 v1.0.1` / `当前版本 1.0.1 · 发布于 2026-11-11 · 37.4 MB` |
| 立即更新 | 拉起 Chrome，**下载完成 37.36 MB**（`release-assets.githubusercontent.com`） |
| 前往 GitHub 下载 | 落到 Releases 列表页（可见 v1.0.1 / v1.0.0） |
| 覆盖安装 | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ← **本功能被废弃的直接原因** |

对照 GitHub API 真实数据：`tag_name = v1.0.1`、`assets = [BaiyunKeys-Android.apk, 37.36 MB]`，弹窗显示的 37.4 MB 与之吻合，APK 资产挑选逻辑正确（优先固定文件名，其次第一个 `.apk`）。

调试日志 tag：`adb logcat -s BaiyunKeys`（与开锁链路共用）。

---

## 5. 将来若要重做：前置条件与恢复方法

### 5.1 硬门槛（缺一不可）

1. **固定签名**（A 或 B），且接受「现存安装最后一次卸载重装」；
2. **versionCode 单调递增**，并在 CI 加 tag ↔ versionName 一致性校验；
3. 明确「只从本仓库 Releases 安装」的分发约定（防止有人拿泄露的签名发同签名假包）。

> 注意：`versionCode 3` 已被主线的 **v1.0.2** 占用（见提交 `f00d7f5`），重启功能请从 4 起。
> 另外主线的 v1.0.2 已为「自动获取配置」补上 `INTERNET`，重做时权限这部分不必再加。

### 5.2 从 reflog 恢复旧实现

两个提交仍可达（`git reset --hard` 只是移动分支指针，对象还在；但**不可达**的 reflog 条目默认 30 天后会被 `git gc` 清掉，要捡请趁早）：

```bash
git log --oneline --reflog | head -20          # 找到 91f247d / b48f28e
git show --stat 91f247d                        # 看实现
git cherry-pick 91f247d b48f28e                # 捡回来（注意会撞版本号与 INTERNET 行）

# 或者只取文件
git ls-tree -r --name-only 91f247d -- core/src/main/kotlin/com/baiyunkeys/core/update
git show 91f247d:app/src/main/kotlin/com/xavior/baiyunkeys/update/UpdateManager.kt > /tmp/UpdateManager.kt
```

设计稿在 `91f247d:docs/mockups/update-ui.html`（配套 PNG 同目录），可单独导出：

```bash
git show b48f28e:docs/mockups/update-ui.html > update-ui.html
git show b48f28e:docs/mockups/update-ui.png  > update-ui.png
```

### 5.3 省钱版（不联网）

如果只是想让人知道有新版本、又不想碰签名和联网：

- 保持 README 顶部那个固定下载按钮（指向 `releases/download/latest/BaiyunKeys-Android.apk`）；
- 每次发版在 `docs/release-notes/` 写清楚「升级前先导出备份」；
- App 内不做任何检测——现阶段主线就是这个状态。

---

## 6. 原设计稿描述（四屏）

回滚前渲染过一版，四屏从左到右：

1. **开屏更新弹窗**：标题「发现新版本 v1.0.1」；副标题「当前版本 1.1.0 · 发布于 2026-11-11 · 37.4 MB」；正文是去噪后的更新条目；按钮区「稍后提醒」+「立即更新」**水平居中**；下方居中一行小字「前往 GitHub 下载」。
2. **首次启动联网说明**：「联网说明 / 首次使用 · 仅提示一次」+ 两句说明 + 居中的「知道了」。
3. **关于卡片（检测到更新）**：`版本  1.1.0`，下面一枚**全宽实心绿**按钮「● 检测到更新 · 查看详情」。
4. **关于卡片（已是最新）+ 已是最新弹窗**：浅绿描边按钮「已是最新 · 重新检查」；弹窗标题「已是最新版本」，居中「知道了」。

配色沿用项目色板：`brand #0FB981`、`brandSoft`、深色主题对应 `#2DD4A0` / `#0F2E26`；弹窗 28dp 圆角、24dp 内边距。

---

## 7. 取舍复盘

| 维度 | 结论 |
|---|---|
| 技术可行性 | ✅ 完全可行，链路在模拟器上跑通 |
| 产品价值 | ❌ 被签名问题抵消：下载成功的终点是「装不上」 |
| 维护成本 | 低（约 500 行 + 24 条单测），但不是问题所在 |
| 真正的阻塞 | 分发基础设施（固定签名 + 版本号纪律 + 侧载引导），而不是这个功能本身 |

**一句话教训**：自更新不是 App 内的功能，而是「签名 + 版本号 + 分发渠道」三件事的副产品。这三件事没定下来之前，先做检测再弹「立即更新」，等于把失败往后推了一步。
