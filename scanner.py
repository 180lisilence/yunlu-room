"""
扫描 Windows 已安装程序（对标手机版云麓空间的"已安装应用"列表）。

数据来源：注册表 Uninstall 项
  - HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall        (64 位程序)
  - HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall (32 位程序)
  - HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall        (当前用户安装)

只读取，不修改任何注册表项。
"""

from __future__ import annotations

import json
import os
import subprocess
import winreg
from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class InstalledProgram:
    name: str
    version: str
    publisher: str
    install_location: str
    uninstall_string: str
    estimated_size_kb: int
    install_date: str          # YYYYMMDD 原始字符串，空串表示未知
    is_system_wide: bool       # True=HKLM(全机), False=HKCU(仅当前用户)
    is_system_component: bool  # SystemComponent=1，多为运行时/子组件，用户可在 UI 开关隐藏
    source: str = "registry"    # "registry" = 注册表安装, "desktop" = 桌面快捷方式(便携/绿色软件)
    display_icon: str = ""
    registry_key: str = ""      # 来源注册表路径，便于排查


def _read_value(subkey, name: str, default):
    try:
        value, _ = winreg.QueryValueEx(subkey, name)
        return value
    except OSError:
        return default


def _scan_root(root, path: str, is_system_wide: bool, out: list[InstalledProgram]) -> None:
    try:
        key = winreg.OpenKey(root, path)
    except OSError:
        return

    index = 0
    while True:
        try:
            sub_name = winreg.EnumKey(key, index)
        except OSError:
            break
        index += 1

        try:
            subkey = winreg.OpenKey(key, sub_name)
        except OSError:
            continue

        try:
            name = _read_value(subkey, "DisplayName", "")
            if not name:
                continue

            # 只过滤真正的"已安装更新 / 补丁 / 不能卸载的隐藏组件"。
            # SystemComponent=1 不再过滤——VC++ Runtime、.NET、Python 子组件
            # 都带这个标记，但控制面板本来就显示它们，是否隐藏交给 UI 开关。
            if _read_value(subkey, "ReleaseType", ""):
                continue
            if _read_value(subkey, "ParentKeyName", ""):
                continue
            if _read_value(subkey, "DisableRemove", 0) == 1:
                continue

            install_date_raw = str(_read_value(subkey, "InstallDate", "") or "")
            out.append(InstalledProgram(
                name=str(name).strip(),
                version=str(_read_value(subkey, "DisplayVersion", "") or "").strip(),
                publisher=str(_read_value(subkey, "Publisher", "") or "").strip(),
                install_location=str(_read_value(subkey, "InstallLocation", "") or "").strip(),
                uninstall_string=str(_read_value(subkey, "UninstallString", "") or "").strip(),
                estimated_size_kb=int(_read_value(subkey, "EstimatedSize", 0) or 0),
                install_date=install_date_raw,
                is_system_wide=is_system_wide,
                is_system_component=_read_value(subkey, "SystemComponent", 0) == 1,
                display_icon=str(_read_value(subkey, "DisplayIcon", "") or "").strip(),
                registry_key=f"{path}\\{sub_name}",
            ))
        finally:
            try:
                winreg.CloseKey(subkey)
            except OSError:
                pass

    try:
        winreg.CloseKey(key)
    except OSError:
        pass


def scan_desktop_shortcuts() -> list[InstalledProgram]:
    """
    扫描桌面快捷方式（用户桌面 + 公共桌面），解析 .lnk 指向的 exe。
    用于补全"绿色/便携软件"——它们不在注册表 Uninstall 里。
    """
    user_desktop = os.path.join(os.environ.get("USERPROFILE", ""), "Desktop")
    public_desktop = r"C:\Users\Public\Desktop"

    ps_script = f'''
$ErrorActionPreference = "SilentlyContinue"
$ws = New-Object -ComObject WScript.Shell
$paths = @("{user_desktop}", "{public_desktop}")
$results = @()
foreach ($p in $paths) {{
  if (Test-Path $p) {{
    Get-ChildItem $p -Filter *.lnk | ForEach-Object {{
      try {{
        $s = $ws.CreateShortcut($_.FullName)
        if ($s.TargetPath -and (Test-Path $s.TargetPath) -and ($s.TargetPath -notlike "::*")) {{
          $size = 0
          try {{ $size = (Get-Item $s.TargetPath).Length }} catch {{}}
          $dir = Split-Path $s.TargetPath -Parent
          $results += [PSCustomObject]@{{
            Name=$_.BaseName; Target=$s.TargetPath; Dir=$dir; Size=$size
          }}
        }}
      }} catch {{}}
    }}
  }}
}}
[Console]::OutputEncoding = [Text.Encoding]::UTF8
if ($results.Count -eq 0) {{ Write-Output "[]" }} else {{ $results | ConvertTo-Json -Compress }}
'''
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_script],
            capture_output=True,
            timeout=15,
        )
        raw = proc.stdout.decode("utf-8", errors="replace").strip()
        items = json.loads(raw) if raw else []
        if isinstance(items, dict):
            items = [items]
    except Exception:
        return []

    out: list[InstalledProgram] = []
    for it in items:
        target = it.get("Target", "")
        out.append(InstalledProgram(
            name=it.get("Name", os.path.basename(target)).strip(),
            version="-",
            publisher="(便携/桌面快捷方式)",
            install_location=it.get("Dir", ""),
            uninstall_string="",   # 绿色软件无标准卸载命令
            estimated_size_kb=int(it.get("Size", 0) or 0) // 1024,
            install_date="",
            is_system_wide=False,
            is_system_component=False,
            source="desktop",
            display_icon=target,
            registry_key="(桌面快捷方式)",
        ))
    return out


def scan_installed_programs() -> list[InstalledProgram]:
    """扫描全部已安装程序（注册表 + 桌面快捷方式），按名称去重后返回。"""
    programs: list[InstalledProgram] = []

    _scan_root(winreg.HKEY_LOCAL_MACHINE,
               r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
               True, programs)
    _scan_root(winreg.HKEY_LOCAL_MACHINE,
               r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
               True, programs)
    _scan_root(winreg.HKEY_CURRENT_USER,
               r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
               False, programs)

    # 同名去重（HKLM/WOW6432 偶尔会重复登记），保留信息更全的那一条
    best: dict[str, InstalledProgram] = {}
    for p in programs:
        key = p.name.lower()
        old = best.get(key)
        if old is None or (len(p.uninstall_string) > len(old.uninstall_string)):
            best[key] = p

    # 合并桌面快捷方式：仅加入"注册表列表里没有"的便携软件
    reg_names = {k.lower() for k in best}
    # 同时按目标路径去重（避免同名不同写法）
    reg_targets = set()
    for p in programs:
        if p.install_location:
            reg_targets.add(p.install_location.lower().rstrip("\\"))

    for sc in scan_desktop_shortcuts():
        key = sc.name.lower()
        dir_key = sc.install_location.lower().rstrip("\\") if sc.install_location else ""
        if key in reg_names:
            continue
        if dir_key and dir_key in reg_targets:
            continue
        best[key] = sc

    return sorted(best.values(), key=lambda x: x.name.lower())


def format_size(kb: int) -> str:
    if kb <= 0:
        return "-"
    if kb >= 1024 * 1024:
        return f"{kb / 1024 / 1024:.2f} GB"
    if kb >= 1024:
        return f"{kb / 1024:.1f} MB"
    return f"{kb} KB"


def format_date(raw: str) -> str:
    raw = (raw or "").strip()
    if len(raw) == 8 and raw.isdigit():
        try:
            return datetime.strptime(raw, "%Y%m%d").strftime("%Y-%m-%d")
        except ValueError:
            pass
    return raw or "-"


if __name__ == "__main__":
    # 命令行直接跑 = 自检，打印前 20 条
    items = scan_installed_programs()
    print(f"共扫描到 {len(items)} 个程序")
    for p in items[:20]:
        print(f"  [{ '系统' if p.is_system_wide else '用户' }] {p.name} | {p.version} | {format_size(p.estimated_size_kb)} | {p.publisher}")
