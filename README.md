# 颢博清理 · HaoboCleaner

> 颢，白貌。取「还存储一个干净面貌」的意思。

一个 Android 存储清理工具：规则驱动扫描、分类清理、结果逐项勾选，不静默删除任何东西。

不需要 root。Android 11 / 12 上**一次文件夹授权就能直接读写 `Android/data`**；Android 13 起系统收紧了这块，改用 Shizuku 即可。

无广告，无联网，不申请「所有文件访问」也能用大部分功能。

[**下载 APK**](https://github.com/138849070/haobo-cleaner/releases/latest) · debug 签名，仅供测试

---

## 核心：SAF 授权 `Android` 父目录

这是整个项目的立足点，值得单独讲清楚。

Android 11 起，系统禁止第三方应用访问 `/sdcard/Android/data` 和 `/sdcard/Android/obb`，也没有 API 能清理其他应用的缓存。

但系统封的是 `Android/data` 和 `Android/obb` 这两个具体目录，**没有封它们的父目录 `Android`**。而 SAF（存储访问框架）的目录树授权是递归的——拿到 `Android` 的授权，就等于拿到了 `data/` 和 `obb/` 下的所有内容。

```kotlin
Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    putExtra(
        DocumentsContract.EXTRA_INITIAL_URI,
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid"),
    )
}
```

流程：

1. 用户点「去授权」
2. 系统选择器直接打开在 `Android` 文件夹上
3. 用户点两次「使用此文件夹」
4. `takePersistableUriPermission()` 保存授权，重启后依然有效
5. 之后通过 `DocumentFile` 遍历、用 `DocumentsContract.deleteDocument` 删除

全程只需要用户点两下，不需要 root、不需要 ADB、不需要「所有文件访问」。

### 这条路有保质期

- **Android 11 / 12**：可用
- **Android 13 及以上**：Google 补上了这个口子，`Android` 父目录也不允许选择

所以项目保留了两条备用路径：

| 后端 | 权限来源 | 覆盖范围 | 速度 |
|---|---|---|---|
| `SafBackend` | 用户授权 `Android` 目录 | `Android/data` 内部 | 慢（走 ContentProvider） |
| `ShellBackend` | Shizuku（uid 2000） | 全盘 | 快 |
| `DirectBackend` | 所有文件访问 | `Android` 目录之外的公共存储 | 快 |

`ScanEngine` 按优先级择一：**SAF > Shizuku > Direct**。

---

## 十个模块

**清理类**

| 模块 | 扫描对象 | 需要什么 |
|---|---|---|
| 常规清理 | 日志、崩溃记录、临时文件、缩略图缓存 | 所有文件访问 |
| 应用清理 | `/Android/data/*/cache`、`logs`、`code_cache` | SAF 授权 或 Shizuku |
| 残留清理 | 已卸载应用在 `Android/data` 留下的目录 | SAF 授权 或 Shizuku |
| 空文件夹 | 层层嵌套的空目录 | 所有文件访问 |
| 大文件 | 超过 10 MB 的文件 | 所有文件访问 |
| 安装包 | `*.apk` / `*.apks` / `*.xapk` / `*.apkm` | 所有文件访问 |
| 重复文件 | 内容完全相同的文件（大小预筛 + SHA-1） | 所有文件访问 |

**分析类**（只展示，不提供删除按钮）

| 模块 | 内容 |
|---|---|
| 存储分析 | 各文件夹的空间占用，按体积排序 |
| 分类分析 | 按图片 / 视频 / 音频 / 文档等类型统计 |
| 应用管理 | 应用列表及其占用，可跳转卸载 |

所有结果列出完整路径，默认全选，可逐项取消。

## 架构

```
MainActivity (Compose)
    ↓
ScanEngine —— 按模块选后端
    ↓
FileBackend
    ├── SafBackend     SAF 授权的 Android 目录（content:// URI）
    ├── ShellBackend   Shizuku, uid 2000
    └── DirectBackend  所有文件访问，公共存储
    ↓
FileWalker —— 主进程与 shell 进程共用的遍历/匹配/删除逻辑
```

`FileWalker` 是纯 `java.io` 实现，主进程和 Shizuku 的 shell 进程复用同一份代码，避免两条路径行为不一致。SAF 后端因为只能走 `DocumentFile`，单独实现。

规则定义在 `Rules.kt`：

```kotlin
data class CleanRule(
    val roots: List<String>,        // 从哪扫
    val include: List<String>,      // 匹配什么
    val exclude: List<String>,
    val excludeDirs: List<String>,
    val minSize: Long,
    val maxDepth: Int,
)
```

新增一类垃圾，通常只需要加一条规则，不用动界面和引擎。

## 构建

- JDK 17+（实测 JDK 21）
- Android SDK Platform 36 + Build-Tools
- Gradle 8.13+

版本：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.2.21 / Compose BOM 2025.05.00 / minSdk 30 / targetSdk 36

```bash
git clone https://github.com/138849070/haobo-cleaner.git
cd haobo-cleaner
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### Windows 上构建失败的话

如果报：

```
Could not move temporary workspace (...) to immutable location (...)
```

这是 Gradle 把解压后的 AAR 移进「不可变工作区」时 `rename()` 被安全软件拦了（荣耀电脑管家、部分国产安全套件会 hook 这个调用）。项目已经在 `gradle.properties` 里带了规避开关：

```properties
systemProp.org.gradle.internal.engine.disable.immutable.workspaces=true
```

如果仍然失败，命令行显式加上：

```bash
gradle assembleDebug -Dorg.gradle.internal.engine.disable.immutable.workspaces=true
```

另外项目路径不要放在含中文的目录下，AGP 会直接拒绝（已用 `android.overridePathCheck=true` 放行，但不保证每个环节都正常）。

## 代码结构

```
app/src/main/
├── aidl/com/haobo/cleaner/IShellService.aidl
├── java/com/haobo/cleaner/
│   ├── Rules.kt        # CleanGroup / CleanRule / JunkItem / 内置规则
│   ├── FileWalker.kt   # 遍历、glob 匹配、空目录判定（两端共用）
│   ├── FileBackend.kt  # DirectBackend / ShellBackend
│   ├── SafBackend.kt   # SAF 授权 Android 目录
│   ├── ShellService.kt # 运行在 shell 进程（uid 2000）
│   ├── ShellClient.kt  # Shizuku 授权、绑定、跨进程调用
│   ├── StatsScanner.kt # StorageStatsManager 读各应用缓存，免 Shizuku
│   ├── Analyzers.kt    # 重复文件 / 文件夹占用 / 分类统计 / 应用列表
│   ├── Exclusions.kt   # 白名单，扫描与删除双重过滤
│   ├── ScanEngine.kt   # 各模块的扫描与删除调度
│   ├── HaoboApp.kt     # 崩溃日志采集
│   └── MainActivity.kt # Compose UI：首页 + 结果页
└── AndroidManifest.xml
```

## 已知限制

- **SAF 授权在 Android 13+ 失效**，那之后只能靠 Shizuku
- 既没有 SAF 授权也没有 Shizuku 时，「应用清理」只能通过系统统计接口列出各应用的缓存大小，**看得到但删不掉**
- SAF 走 ContentProvider，遍历 `cache` 明显慢于 File API，所以删除粒度是「整个 cache 目录」
- 重复文件只比对 ≥ 1 MB 的文件，小文件收益低、全量哈希太慢
- 存储分析与分类分析会遍历整个内部存储，文件多的机器首次扫描要几十秒
- 大文件扫描会遍历 `DCIM`，视频多的机器首次扫描可能要几十秒
- 单次扫描结果上限 3000 条，防止内存溢出
- 少数应用把重要数据放在 `cache` 目录（不符合规范），清理前请看一眼路径
- 「所有文件访问」在 Google Play 审核极严，本应用面向侧载 / 国内分发

## 参考

- [Android 存储访问框架文档](https://developer.android.com/training/data-storage/shared/documents-files)
- [管理存储设备上的所有文件](https://developer.android.com/training/data-storage/manage-all-files)
- [Shizuku](https://github.com/RikkaApps/Shizuku)

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。

选 GPL 不是出于意识形态，是因为这类工具太容易被重新打包、塞广告、换名上架。GPL 至少要求对方公开改动。
