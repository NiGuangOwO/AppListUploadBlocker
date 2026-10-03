# 应用列表上传拦截 · AppListUploadBlocker

一个单一职责的 LSPosed 模块：阻止小米「系统安全组件」（`com.miui.guardprovider`）把**已安装应用列表**上报到云端。

只使用 **libxposed API 102** 原生接口 + **DexKit** 定位，不依赖任何第三方 Hook 封装库。

## 拦截的是什么

目标应用在「病毒扫描」流程中会遍历 `PackageManager.getInstalledPackages(0)`，把每个第三方应用的信息打包成 JSON 后上报：

```
POST /detect/app HTTP/1.1
Host: flash.sec.miui.com
Content-Type: application/json

{
  "timestamp": "<TIMESTAMP>",
  "os": "<ROM_VERSION>",
  "biz_id": "virus_scan",
  "uuid": "<DEVICE_UUID>",
  "content": [
    {"pkg": "com.example.app", "version": "<VERSION_CODE>",
     "signature": "<SIGNATURE_MD5>", "appname": "<APP_NAME>"},
    ...
  ],
  "sign": "<REQUEST_SIGN>"
}
```

每个条目包含**包名、版本号、签名 MD5、应用名**。模块把这条链路整根掐断。

## 实现

用 DexKit 按字符串锚点定位两个方法，不硬编码混淆后的类名（目标 APK 每次发版都会重新混淆）：

| 角色 | 匹配锚点（AND 语义） |
|---|---|
| 外发出口 | `https://flash.sec.miui.com/detect/app` + `NetworkApiHelper` |
| 采集源头 | `AntiDefraudAppManager` + `getUnSystemAppList error, ` |

两个 Hook 各自带回退链，命中不到只记日志、不抛异常：

- **外发出口** Hook 返回 `null` → 调用方 `TextUtils.isEmpty()` 为真，直接跳过写库与后续解析。
- **采集源头** Hook 返回空 `ArrayList` → 调用方遍历安全，且 `getInstalledPackages()` 根本不会执行。

任一匹配器失败不会让宿主进程崩溃，也不会影响另一个 Hook 生效。

## 界面

设置页用 **Jetpack Compose + Miuix**（`top.yukonga.miuix.kmp` 0.9.4）实现，提供跟随系统的日夜配色与卡片式分组。

模块**不提供任何开关**：是否生效完全由 LSPosed 的「启用模块 + 勾选作用域」决定。设置页顶部用绿底勾号卡片展示激活状态，下方列出框架版本与作用域。

激活状态由 `XposedServiceHelper` 回调推出：能收到 `onServiceBind` 即模块已启用，再用 `getScope()` 判断作用域是否包含 `com.miui.guardprovider`；两者皆满足才显示「已激活」。

## 拦截记录

两个 Hook 每次真正拦下调用时都会落一条记录，设置页「拦截记录」区域展示累计次数与最近 50 条明细（时间 + 拦截点类型），并可一键清空。

跨进程通道用的是 **ContentProvider**：Hook 侧（运行在 `com.miui.guardprovider` 进程内）通过 `ContentResolver.call()` 调用本模块的 `BlockRecordProvider`（`content://io.github.niguangowo.applistblocker.records`，方法名 `record`，参数放在 `Bundle` 里），Provider 在模块进程内写入本进程私有的 SharedPreferences，设置页直接读同一个文件。

之所以不用 libxposed 的 remote preferences：框架在 Hook 侧返回的是**只读实现**（`LSPosedRemotePreferences.edit()` 抛 `UnsupportedOperationException`），Hook 进程无法写入；而且该实现在 Hook 侧只保存一份快照，只有框架主动推送时才刷新，不适合做写入通道。

之所以也不用广播：HyperOS 的 Greezer 会把处于 cached 状态的模块进程冻结，投递到该进程的广播会被 `Greezer Denial: ... need cached broadcast` 静默丢弃；若进程已被 force-stop，则被 `BroadcastQueueInjector` 以 `process is not permitted to auto start` 拒绝。**ContentProvider 的获取不受这两处门控限制**——即使模块进程已被 force-stop，一次 `call` 也能把它拉起（实机验证：`am force-stop` 后进程为空，`content call` 返回 `Bundle[{ok=true}]` 且进程出现）。

`BlockRecordProvider` 声明为 `exported="true"` 且**不声明自定义权限**：调用方是目标应用进程（`com.miui.guardprovider`），它不可能持有本模块声明的权限，一旦声明权限系统会直接拒绝调用、记录全部丢失。

由于没有权限门槛，任何应用都可以直接 `call` 这个 Provider 伪造记录。**拦截记录仅用于本机展示，不作为安全审计依据。**

时间戳来自不可信的调用方，Provider 会把超出「当前时刻 + 60 秒」或非正数的值夹取为当前时刻：既不误伤投递延迟，也避免 `Long.MAX_VALUE` 编出 19 位字符串破坏定宽排序、挤掉真实记录。

存储结构（`BlockRecordStore`）：

| 键 | 内容 |
| --- | --- |
| `total_count` | 累计拦截次数，只增不减，清空时一并重置 |
| `entries` | 最近 50 条记录，每项编码为 `<13 位毫秒时间戳>\|<类型>\|<序号>`，定宽时间戳保证字典序与时间序一致，序号区分同一毫秒内的同类型拦截 |

类型取值为 `EGRESS`（外发出口拦截）与 `COLLECTOR`（采集源头拦截）。

记录功能属于附带能力：`BlockRecordStore` 的读写全部捕获异常，失败时只打一条 `WARN` 日志，**任何记录失败都不会影响拦截本身**。Hook 侧的投递队列有界（64 条），队列满时丢弃记录并打日志，不会反过来拖慢目标应用。

## 构建

```bash
export JAVA_HOME=/path/to/jdk-25
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

要求：JDK 25、compileSdk 37、buildTools 37.0.0、NDK（arm64-v8a）。

注意：AGP 9.0 起内置 Kotlin 支持，**不要**再声明 `org.jetbrains.kotlin.android` 插件（会报 `no longer required for Kotlin support since AGP 9.0`）；Compose 只需 `org.jetbrains.kotlin.plugin.compose`。

## 自动构建与发布

`.github/workflows/release.yml` 负责在 CI 上编译 APK 并发布到 GitHub Release。

**触发方式**

- 推送 `v*` 形式的 tag（例如 `v1.0.0`）自动触发；
- 或在 Actions 页面手动 `Run workflow`，填写要发布的 tag。

**发布前需配置签名 secrets**（Settings → Secrets and variables → Actions）：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 口令 |

四个 secret 缺任意一个，工作流会**直接失败并拒绝发布**——因为 `app/build.gradle.kts` 的 release 未配置 `signingConfig`，`assembleRelease` 只会产出 `app-release-unsigned.apk`，而未签名 APK 无法安装。

若只是想验证构建产物，可手动触发并勾选 `allow_unsigned`，此时会发布一个文件名带 `-unsigned` 后缀的未签名包。

发布流程：`zipalign -p 4` → `apksigner sign` → `apksigner verify --print-certs` → 上传 workflow artifact → 创建 Release 并附上 `AppListUploadBlocker-<tag>.apk`。

## 安装与使用

1. 安装 APK，在 LSPosed 中启用模块。
2. 作用域勾选 **系统安全组件**（`com.miui.guardprovider`）。
3. 强制停止并重启该应用（或重启手机）使 Hook 生效。
4. 打开模块 App，绿卡显示「已激活」即表示模块已启用且作用域正确。

模块内部**无条件拦截**：未被 LSPosed 启用时目标进程根本不会被注入，因此不需要额外的开关；作用域改动**无需重启**目标进程。

## 版本适配

DexKit 匹配器按字符串锚点定位，只要目标 APK 仍保留上述字符串就能自动适配新版本。若这些字符串被改动，模块会在日志中输出：

```
No matcher hit the app list upload path of com.miui.guardprovider (unsupported app version?)
```

此时需要在 `ModuleEntry.findUploadEgress()` / `findAppListCollector()` 中补充新的锚点。

## 许可证

AGPL-3.0
