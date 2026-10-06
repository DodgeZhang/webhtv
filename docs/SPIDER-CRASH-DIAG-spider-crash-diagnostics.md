# SPIDER-CRASH-DIAG — 崩溃页显示肇事蜘蛛源

## Recovery anchor

- **目标**：崩溃页在显示外部蜘蛛 JAR 闯祸时，附带「最后加载的蜘蛛（站点 key / api / JAR 指纹）」，让用户知道该换哪个源。
- **验收**：不吞异常、不隐式禁用任何源；`Prefers` 不被写入；面包屑随「清理缓存」一并清除；leanback 与 mobile 两 flavor 单测绿。
- **lane / scope**：`quick-fix`；`JarLoader.java`、`CrashActivity.java`、`SpiderCrashBreadcrumb.java`（新）、三语 `strings.xml`、`SpiderCrashBreadcrumbTest.java`（新）、本文件。
- **当前状态**：实现完成，验证完成，待提交。
- **下一步**：`task_guard.sh finish` 提交并打 recovery tag。

## 问题

用户反馈「点击全屏崩溃」，崩溃页栈：

```text
java.lang.StringIndexOutOfBoundsException: length=4; index=44
  at java.lang.String.substring(String.java:3002)
  at com.github.catvod.spider.merge.O0O0o0OOo0oO0oO0o.o0OOO0oO0oOoO0O0oO.onGlobalLayout(
     r8-map-id-3fb9904066d2e3df7fbf2d7171bb0015e29d1f1bf5ed5bd0aae52b8d00f5fb5f:742)
  at android.view.ViewTreeObserver.dispatchOnGlobalLayout(ViewTreeObserver.java:1082)
  at android.view.ViewRootImpl.performTraversals(ViewRootImpl.java:6057)
```

设备 Xiaomi 25102RKBE / Android 17 (SDK 37)，版本 `5.6.0-beta-202610052020`。

### 根因（置信度：高）

外部 CatVod 蜘蛛 JAR 在其自注册的 `OnGlobalLayoutListener` 里对长度 4 的字符串执行越界 `substring`。点击全屏 → 宿主改布局并转横屏 → 系统重新布局 → JAR 的监听器执行 → 主线程未捕获异常 → 进程被杀。

宿主无缺陷：`JarLoader.getSpider()` 的 `try/catch` 只覆盖类加载与 `spider.init(...)` 同步期，而监听器是系统**稍后**在 `performTraversals` 里回调的，早已离开该作用域。与 Exo / IJK / MPV 内核无关。

### 证据

| 项 | 值 |
|---|---|
| 本次真机 | `length=4; index=44`，map-id `3fb9904…`，行 742 |
| 2026-08-11 真机 | `length=4; index=7`，map-id `750c342…`，行 740 |
| 全盘扫描 | 13 个设备缓存 JAR + 宿主 APK（32 dex）+ 工作区 225 个 jar/apk/dex → **均无该 map-id 与该混淆类名** |
| 源码定位 | `CatVodSpider/app/proguard-rules.pro:2` = `-flattenpackagehierarchy com.github.catvod.spider.merge`，全工作区**唯一**产生 `spider.merge.*` 包的仓库 |
| 同族构建 | `7882b1db85bf60bbd99efb69755d0494.jar` 内 `merge/A/o`、`merge/A/s`、`merge/A/t` 三类均 implements `ViewTreeObserver$OnGlobalLayoutListener`，`onGlobalLayout` 里持 `WeakReference` 并回写监听器字段 |

同一缺陷家族、不同 R8 构建 → 该源持续复现。真正修复需 JAR 提供方改（`startsWith(prefix)` 后再 `substring(prefix.length())`，并在 View 销毁时摘除监听器），或用户更换该源。

### 历史处置

2026-08-12 曾实现宿主侧守卫（`SpiderCrashGuard` / `SpiderJarRegistry` / `SpiderIsolation`），评审未通过，归档于 `archive/spider-crash-guard-rejected` = `b704dafde443ef00220e87f73925fff954a53bd3`。致命缺陷：`findLoadedClass` 的语义是 initiating loader 而非 defining loader，归因机制构造性不成立，守卫退化为无条件吞掉所有主线程异常。评审给出的替代方向是**零风险诊断增强**，即本次实现。

## 设计

### 为什么必须落盘

`AndroidManifest.xml:82-84` 给 `CrashActivity` 声明了 `android:process=":error_activity"`，崩溃页跑在独立进程，读不到主进程内存。故面包屑持久化到 `Path.cache()/spider-last.txt`。

### 为什么不用 Prefers

`Backup.include()`（`app/src/main/java/com/fongmi/android/tv/bean/Backup.java:263-288`）兜底是 `return options.isSpider()`，未知键会随「蜘蛛」备份导出并恢复到其它设备。`Path.cache()` 下的文件随既有「清理缓存」一并清除，无此污染。

### 改动

| 文件 | 改动 |
|---|---|
| `utils/SpiderCrashBreadcrumb.java`（新） | `record(siteKey, api, jar, jarKey)` 写 `cache/spider-last.txt`（临时文件 + rename，内容相同则跳过）；`read()` 返回文本或空串。所有 IO 包 `Throwable` 只返回空/null，**绝不吞进程级异常**。上限 512 字节，控制字符压成 `_`，jar url 只保留 `http`/`file`/`local` 分类（md5 已足够标识） |
| `api/loader/JarLoader.java` | `getSpider()` 入口处调用一次 `record(...)`。放在入口而非 cache miss 分支内，使**缓存命中**路径也更新面包屑，避免用户切源后崩溃指向旧源 |
| `ui/activity/CrashActivity.java` | `showError()` 在 `crash_details_message` 前拼接 `crash_details_spider`（面包屑为空时不拼） |
| 三语 `strings.xml` | `crash_details_spider`：`Last loaded spider: %1$s` / `最后加载的蜘蛛：%1$s` / `最後載入的蜘蛛：%1$s` |
| `test/.../SpiderCrashBreadcrumbTest.java`（新） | 7 例：格式化、空字段不产生悬空分隔符、控制字符压平、超长截断、落盘往返与缺失文件、超大文件拒读、目录当不可读而非抛异常 |

**明确不做**（逐条回避 8 月评审否决项）：不吞异常、不装 `Looper` 守卫、不做 JAR 隔离、不写 `Prefers`、不改播放与转屏逻辑。

## 验证

| 检查 | 结果 |
|---|---|
| `:app:testLeanbackArm64_v8aDebugUnitTest --tests '*SpiderCrashBreadcrumbTest*'` | `tests=7 failures=0 errors=0` |
| `:app:testMobileArm64_v8aDebugUnitTest --tests '*SpiderCrashBreadcrumbTest*'` | `tests=7 failures=0 errors=0` |
| `:app:assembleLeanbackArm64_v8aDebug` | EXIT=0 |
| APK 资源表（aapt2 dump） | `crash_details_spider` 三语齐全 |
| APK 字节码（dexdump） | `CrashActivity` 调用 `SpiderCrashBreadcrumb.read()` 且引用 `crash_details_spider`；`JarLoader` 调用 `record()` 恰好 1 次 |
| 真机 5557 覆盖安装 + 重启 | 先删后建，`cache/spider-last.txt` = `site=49be5d2a5930446782c18b8e43330ed706a9 api=csp_PyProxy jar=e351de1cedb1d8de5398cbf95f93f319 src=http` |

### 环境限制（已判定为既有缺陷，非本次引入）

SDK 28 模拟器上 `CrashActivity` 因 `customactivityoncrash_error_image` PNG 解码失败无法渲染（`IllegalArgumentException: Dimensions must be positive! provided (0, 0)`）。用**未改动的旧 APK**（`lastUpdateTime=2026-10-06 23:39:21`）在 `emulator-5562` 复现**完全相同**的失败。真机 SDK 37 上用户截图显示崩溃页正常。故未做模拟器端 UI 断言，改以字节码 + 资源表 + 真机面包屑落盘作为证据。

## 局限

**不修复崩溃本身**。根因在外部 JAR，本仓库改不了。本次只让崩溃页指出肇事源，供用户换源；真正的修复在 JAR 提供方。

## 回滚

`git revert <commit>`，或删 `SpiderCrashBreadcrumb.java` 与测试文件、回退 `JarLoader`/`CrashActivity`/三语字符串 3 处小改。无状态迁移、无 ABI 变化、无数据格式变化。
