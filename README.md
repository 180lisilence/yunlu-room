# 云麓空间（AppManager）

查看、管理、卸载 Android 已安装应用的单机工具。已发布 **V1.0.0.001**（工程脚手架 + 应用列表 + 应用详情 + 单个/批量卸载 + 安装状态实时刷新 + APK 提取）；**当前进入 V3.0.0 开发线（版本号 3.0.0.001）**，批次一 B1 存储可视化已落地，完整规划见 `docs/三代方案-V3.0.0.md`。

## 方案文档

| 文档 | 状态 | 说明 |
|---|---|---|
| [`docs/初版方案-V1.0.0.md`](docs/初版方案-V1.0.0.md) | 已实施 | 第一版产品与技术方案（已落地为 V1.0.0.001） |
| [`docs/三代方案-V3.0.0.md`](docs/三代方案-V3.0.0.md) | **待评审（当前有效）** | 三代方案：6 批次 21 模块 —— 存储与清理闭环 / 治理与管控中枢 / 洞察与审计 / 备份还原与迁移 / 提权升级（Shizuku→Device Owner）/ 形态与跨设备 |
| [`docs/二代方案-V2.0.0.md`](docs/二代方案-V2.0.0.md) | 已归档 | 二代方案（未实施，已被 V3 取代；原稿见 [`docs/backup/`](docs/backup/)） |

> **文档版本关系**：V1 已落地 → V2 未实施即被取代（原稿已备份至 `docs/backup/二代方案-V2.0.0.backup-20261014.md`）→ **V3 为当前有效方案**，继承 V2 全部有效模块并新增 6 个能力方向（清理闭环、应用归档、应用冷冻、AppOps 管控、Device Owner 专业模式、跨设备与迁移）。

## 功能清单（V1.0.0）

| 模块 | 功能 |
|---|---|
| 应用列表 | 用户/系统应用分组（Tab 切换）、名称/包名搜索、按名称/大小/安装时间排序、APK 大小/版本/安装时间副标题 |
| 多选批量卸载 | 长按进入多选、全选、逐项弹系统确认框（队列驱动） |
| 应用详情 | 图标/名称/包名、系统/停用徽标、打开应用、系统应用信息页、卸载（仅用户应用）、版本/SDK/安装时间/安装来源/APK 大小/路径、权限列表（危险权限高亮、授权状态） |
| APK 提取 | 详情页一键提取安装包，可选「仅主包」或「完整分片集」（含全部配置分片，可直接安装）；支持系统分享与另存到「下载/云麓空间」；设置页可查看占用并一键清理 |
| 实时刷新 | 监听 `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` 广播，安装/卸载/更新后自动刷新 |
| 设置页 | 版本、隐私说明（无网络权限）、权限说明、已提取安装包统计与清理 |
| 主题 | Material 3 + 动态取色（Android 12+）+ 深色模式 |

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Material 3
- MVVM：`StateFlow` + `combine` 派生 UI 状态；`collectAsStateWithLifecycle`
- Hilt（KSP）依赖注入
- Navigation Compose（列表 / 详情 / 设置三个目的地）
- minSdk 24 / targetSdk 35 / compileSdk 35，JDK 17

## 目录结构

```
app/src/main/java/com/yunlu/kongjian/
├── AppManagerApplication.kt      # @HiltAndroidApp
├── MainActivity.kt               # @AndroidEntryPoint + NavHost
├── core/
│   ├── model/Models.kt           # AppInfo / AppDetail / PermissionInfo / 过滤排序
│   └── util/Formatters.kt        # 大小/时间格式化
├── data/AppRepository.kt         # PackageManager 封装、广播监听、Intent 构建
├── feature/
│   ├── applist/                  # 列表 ViewModel + Screen（含批量卸载队列）
│   ├── appdetail/                # 详情 ViewModel + Screen
│   └── settings/                 # 设置页
└── ui/theme/Theme.kt             # M3 主题（动态取色）
```

## 构建与运行

**本机构建环境（已验证）**：JDK 21（`D:\TOOL\jdk`）、Gradle 8.9-all（`E:\gongxiangwenjianjia\7\gradle-8.9-all\gradle-8.9`）、Android SDK（`D:\TOOL\Android\Sdk`，platforms/android-35 + build-tools/35.0.0），`local.properties` 已配置。

```bash
# 1. 构建 debug / release APK
cd "E:/gongxiangwenjianjia/7/云麓空间" && \
JAVA_HOME=/d/TOOL/jdk "/e/gongxiangwenjianjia/7/gradle-8.9-all/gradle-8.9/bin/gradle" assembleDebug assembleRelease --console=plain

# 2. 产物
#    app/build/outputs/apk/debug/app-debug.apk                    （调试版，包名 com.yunlu.kongjian.debug）
#    app/build/outputs/apk/release/app-release-unsigned.apk       （正式版未签名）

# 3. 签名正式版（uber-apk-signer + msll.jks）
cd app/build/outputs/apk/release && \
/d/TOOL/jdk/bin/java -jar "E:/tool/apktoooooool/uber-apk-signer.jar" -a app-release-unsigned.apk \
  --ks "E:/tool/apktoooooool/msll.jks" --ksAlias msll --ksKeyPass msll2026 --ksPass msll2026
#    产出：app-release-aligned-signed.apk（v2+v3 签名、已 zipalign）

# 4. 安装（连手机时）
adb install -r app/build/outputs/apk/release/app-release-aligned-signed.apk
```

> 应用名：云麓空间；版本：3.0.0.001（versionCode 30001）；包名：`com.yunlu.kongjian`（debug 带 `.debug` 后缀）。
> `settings.gradle.kts` 已配阿里云 Maven 镜像加速依赖拉取。

## 权限说明

| 权限 | 用途 |
|---|---|
| `QUERY_ALL_PACKAGES` | Android 11+ 列出全部已安装应用（普通权限，安装即授） |
| `REQUEST_DELETE_PACKAGES` | 调起系统卸载界面 |
| `WRITE_EXTERNAL_STORAGE`（仅 ≤ API 28） | 提取的 APK 另存到「下载/云麓空间」；API 29+ 走 MediaStore，不需要任何权限 |

> 提取安装包只**读取**系统分区，不修改任何系统文件；提取结果落在应用私有目录 `filesDir/apk_extract/`，通过 FileProvider（仅映射该目录）授权给分享目标。

- **无网络权限**：所有数据本地处理，不联网。
- 卸载一律走系统卸载界面，由用户确认，不做任何静默卸载。
- 系统应用卸载入口在 V1.0.0 置灰（方案 L4 层，V2.0 接 Shizuku 后开放「针对当前用户卸载」，可恢复）。

## 验收清单（对照方案 V1.0.0）

- [ ] 真机列出全部用户应用与系统应用，数量与系统设置一致
- [ ] 搜索（名称/包名）实时过滤
- [ ] 三种排序正确切换
- [ ] 单个卸载：系统弹窗 → 确认后列表移除、Snackbar 提示
- [ ] 批量卸载：多选后逐项弹窗，全部完成后退出多选
- [ ] 详情页：权限、安装来源、APK 大小显示正确
- [ ] 安装/卸载任意应用后，回到本应用列表自动刷新
- [ ] Android 11+ 设备（重点验证包可见性）与 Android 14 设备各测一台

## 已验证 / 已知边界

**已验证（2026-10-13，本机 Gradle 8.9 + JDK 21 + SDK 35）**：
- `assembleDebug` / `assembleRelease` 均构建成功，弃用警告清零；
- aapt2 校验：包名 `com.yunlu.kongjian`、label「云麓空间」、versionName `3.0.0.001`、versionCode `30001`、targetSdk 35；
- release APK 经 uber-apk-signer 完成 v2+v3 签名与 zipalign（签名主体 CN=msll，有效期至 2056-09-30）；
- 修复记录：`PackageInfo.REQUESTED_PERMISSION_GRANTED` 常量名、补 `hilt-navigation-compose` 依赖、`Icons.Filled.Sort`→`Icons.AutoMirrored.Filled.List`、委托属性 smart cast、`ACTION_UNINSTALL_PACKAGE`→`ACTION_DELETE`。

**未执行**：真机运行验证（当前无设备连接，按操作者要求暂不连手机）。

**后续批次**：V3 批次二起的能力（使用统计、清理闭环、Shizuku 系统应用治理、APK 备份、Device Owner 等）见 `docs/三代方案-V3.0.0.md` 第 5 章。

## V3 开发进度（批次一进行中）

三代方案 V3.0.0 已开始实施（基线 commit `239a198`，设计见 `docs/三代方案-V3.0.0.md`）。

| 模块 | 状态 | 落地点 |
|---|---|---|
| **B1 存储可视化** | ✅ 已完成 · 通过 `assembleDebug` | `StorageUsage`/`DeviceStorage` 模型；`AppRepository` 存储查询（60s TTL 缓存 + 8 路并行）；列表「按占用」排序 + 顶部存储概览卡；详情页存储分项卡片；全局存储页 `StorageScreen`（设备卷总量/已用/可用 + 四段占比图 + 用户/系统/缓存汇总） |
| B2 使用统计与闲置识别 | ⬜ 未开始 | 需 `PACKAGE_USAGE_STATS` 特殊权限；无权限时整块隐藏（方案铁律，禁止兜底为 0） |
| B3 垃圾与残留扫描 | ⬜ 未开始 | 需 Shizuku / `MANAGE_EXTERNAL_STORAGE` 读 `Android/data` |
| B4 应用归档 | ⬜ 未开始 | `.kaz` 打包（APK 分片 + 元数据）+ 还原 |

- **构建已验证**：`JAVA_HOME=/d/TOOL/jdk "/e/gongxiangwenjianjia/7/gradle-8.9-all/gradle-8.9/bin/gradle" assembleDebug --offline` → 产出 `云麓空间-v3.0.0.001-debug.apk`（10.3 MB，commit `5595b3c` 后版本号已升至 `3.0.0.001` / versionCode `30001`）。
- **编译期修正**：android-35 平台 `StorageStats` 仅暴露 getter（无 public `externalDataBytes` 字段），且 `APK_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT` 常量在本编译链 Kotlin 解析异常 → 已据实改为 `externalCacheBytes` 并暂移除 API 35 专属 AOT 拆分分支（AOT 行全版本隐藏，待真机 API 35 验证后重启用）。
- 真机运行验收仍按约定暂不做（无设备连接）。
