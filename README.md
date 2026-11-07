# 云麓空间 PC 版

Windows 本机已安装程序管理器，对标手机版「云麓空间」（AppManager）。

## 功能

| 功能 | 说明 |
|---|---|
| 程序列表 | 名称、版本、发行商、大小、安装日期、范围（全机/用户） |
| 搜索 | 按名称 / 发行商实时过滤 |
| 分组 | 全部 / 全机程序（HKLM）/ 当前用户程序（HKCU） |
| 排序 | 按名称 / 按大小 / 按安装日期 |
| 卸载 | 选中后启动**系统卸载向导**（不静默删除，由用户在系统向导里确认） |
| 打开安装目录 | 直接定位到 InstallLocation |
| 复制卸载命令 | 拷贝 UninstallString 到剪贴板 |
| 详情 | 双击查看完整信息（注册表项、卸载命令全文等） |

## 运行

零依赖（仅 Python 标准库）：

```bash
# 切到本仓库 pc 分支根目录后执行
python app.py
# 或直接双击同目录下的 云麓空间PC版.exe（已打包，无需 Python 环境）
```

## 实现说明

- 数据来源：注册表三处 Uninstall 项（HKLM 64 位 / HKLM WOW6432 32 位 / HKCU），**只读**。
- 已过滤：SystemComponent=1 的隐藏组件、系统更新 / 补丁、ParentKeyName 项。
- 同名去重（HKLM 与 WOW6432 偶尔重复登记）。
- 卸载走 `cmd /c start "" <UninstallString>`，与「控制面板 → 程序和功能」行为一致。

## 文件

| 文件 | 作用 |
|---|---|
| `scanner.py` | 注册表扫描（可独立命令行跑自检：`python scanner.py`） |
| `app.py` | Tkinter GUI 主程序 |
