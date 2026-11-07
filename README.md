# 云麓空间（Yunlu Space）

一套**双端本机应用管理器**：查看、搜索、排序、卸载你设备上安装的程序与 App。所有数据本地处理，**不联网、不上传任何信息**。

> 这是仓库的总览分支（`main`），承载介绍页 [`index.html`](index.html)。源码与构建按平台拆分到独立分支：

| 分支 | 内容 | 直达 |
|---|---|---|
| `main` | 本介绍页与项目总览 | — |
| `pc` | Windows PC 版（Python + Tkinter，含已编译 `云麓空间PC版.exe`） | [tree/pc](https://github.com/180lisilence/yunlu-room/tree/pc) |
| `android` | Android 版（Kotlin + Jetpack Compose，含已签名 `云麓空间-v1.0.0.001-release-aligned-signed.apk`） | [tree/android](https://github.com/180lisilence/yunlu-room/tree/android) |

## 双端一览

### 🖥️ PC 版（Windows）
- 读取注册表三处卸载项，列出全机 / 用户已安装程序（名称、版本、发行商、大小、安装日期、范围）。
- 实时搜索、分组（全机 / 用户）、排序（名称 / 大小 / 安装日期）。
- 卸载走系统向导、打开安装目录、复制卸载命令、查看完整详情。
- 零依赖（仅 Python 标准库），已打包为 `云麓空间PC版.exe`，双击即可运行。

### 📱 Android 版
- 用户 / 系统应用分组、名称 / 包名搜索、按名称 / 大小 / 安装时间排序。
- 单个与批量卸载（全部走系统确认界面），应用详情与权限查看。
- APK 提取与分享，安装 / 卸载后通过系统广播实时刷新。
- 技术栈：Kotlin 2.0.21 + Jetpack Compose + Material 3 + Hilt + MVVM；minSdk 24 / targetSdk 35。
- 已发布 **V1.0.0.001**（已签名 release），当前进入 **V3.0.0** 开发线（存储可视化等）。

## 隐私与安全
- **无网络权限**：两个版本均不联网，数据本地处理。
- **只读扫描**：PC 版读取注册表仅为展示；Android 版提取安装包只读取系统分区、不改动系统文件。
- **不静默卸载**：卸载一律调用系统界面，由用户确认。

## 快速开始
1. 查看 PC 版：切换到 `pc` 分支，运行 `python app.py`，或直接下载 `云麓空间PC版.exe`。
2. 查看 Android 版：切换到 `android` 分支，按 `README.md` 用 Gradle 构建，或直接安装提供的 release APK。

## 许可证
各分支内分别包含对应许可证文件（Android 分支含 `LICENSE`）。
