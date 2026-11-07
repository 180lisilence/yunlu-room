"""
云麓空间 PC 版 —— Windows 已安装程序管理器
对标手机版功能：列表 / 搜索 / 排序 / 分组 / 卸载 / 打开安装目录 / 详情。

运行：python app.py
零依赖（仅用 Python 标准库）。
"""

from __future__ import annotations

import os
import subprocess
import sys
import tkinter as tk
from tkinter import ttk, messagebox

from scanner import (
    InstalledProgram,
    scan_installed_programs,
    format_size,
    format_date,
)

APP_TITLE = "云麓空间 PC 版"
APP_VERSION = "1.0.0"


# ------------------------------------------------------------------ 主题

LIGHT_THEME = {
    "bg": "#f5f5f5",
    "fg": "#1a1a1a",
    "frame": "#f5f5f5",
    "entry_bg": "#ffffff",
    "entry_fg": "#1a1a1a",
    "button_bg": "#e0e0e0",
    "button_fg": "#1a1a1a",
    "select_bg": "#cce4ff",
    "select_fg": "#1a1a1a",
    "tree_bg": "#ffffff",
    "tree_fg": "#1a1a1a",
    "tree_header_bg": "#e8e8e8",
    "tree_header_fg": "#1a1a1a",
    "status_bg": "#e8e8e8",
}

DARK_THEME = {
    "bg": "#1e1e1e",
    "fg": "#e6e6e6",
    "frame": "#252526",
    "entry_bg": "#3c3c3c",
    "entry_fg": "#e6e6e6",
    "button_bg": "#3c3c3c",
    "button_fg": "#e6e6e6",
    "select_bg": "#094771",
    "select_fg": "#ffffff",
    "tree_bg": "#252526",
    "tree_fg": "#e6e6e6",
    "tree_header_bg": "#3c3c3c",
    "tree_header_fg": "#e6e6e6",
    "status_bg": "#007acc",
}


class AppManagerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP_TITLE} v{APP_VERSION}")
        self.geometry("960x640")
        self.minsize(760, 480)

        self._all_programs: list[InstalledProgram] = []
        self._current_filtered: list[InstalledProgram] = []
        self.dark_mode = tk.BooleanVar(value=False)

        # ttk 主题用 clam 才能自由配色
        self.style = ttk.Style(self)
        self.style.theme_use("clam")

        self._build_toolbar()
        self._build_table()
        self._build_statusbar()

        self.apply_theme()
        self.refresh()

    # ------------------------------------------------------------------ 主题

    def apply_theme(self) -> None:
        t = DARK_THEME if self.dark_mode.get() else LIGHT_THEME
        self.configure(bg=t["bg"])

        s = self.style
        s.configure(".", background=t["frame"], foreground=t["fg"],
                   fieldbackground=t["entry_bg"], borderwidth=0)
        s.configure("TFrame", background=t["frame"])
        s.configure("TLabel", background=t["frame"], foreground=t["fg"])
        s.configure("TButton", background=t["button_bg"], foreground=t["button_fg"],
                   padding=(8, 4))
        s.map("TButton",
              background=[("active", t["select_bg"])],
              foreground=[("active", t["select_fg"])])
        s.configure("TCheckbutton", background=t["frame"], foreground=t["fg"])
        s.configure("TCombobox", background=t["entry_bg"], foreground=t["entry_fg"],
                   fieldbackground=t["entry_bg"])
        s.map("TCombobox",
              fieldbackground=[("readonly", t["entry_bg"])],
              foreground=[("readonly", t["entry_fg"])])
        s.configure("Treeview",
                   background=t["tree_bg"], foreground=t["tree_fg"],
                   fieldbackground=t["tree_bg"], rowheight=24)
        s.map("Treeview",
              background=[("selected", t["select_bg"])],
              foreground=[("selected", t["select_fg"])])
        s.configure("Treeview.Heading",
                   background=t["tree_header_bg"], foreground=t["tree_header_fg"])

        # tk 原生控件（Entry 等）手动配色
        for child in self.winfo_children():
            self._recolor(child, t)

        self.status_label.configure(background=t["status_bg"], foreground="#ffffff")

    def _recolor(self, widget, t) -> None:
        try:
            if isinstance(widget, (tk.Entry,)):
                widget.configure(bg=t["entry_bg"], fg=t["entry_fg"],
                                 insertbackground=t["entry_fg"])
            elif isinstance(widget, tk.Text):
                widget.configure(bg=t["entry_bg"], fg=t["entry_fg"])
            elif isinstance(widget, tk.Canvas):
                widget.configure(bg=t["bg"])
        except tk.TclError:
            pass
        for child in widget.winfo_children():
            self._recolor(child, t)

    # ------------------------------------------------------------------ UI

    def _build_toolbar(self) -> None:
        bar = ttk.Frame(self, padding=(8, 8))
        bar.pack(side=tk.TOP, fill=tk.X)

        ttk.Label(bar, text="搜索:").pack(side=tk.LEFT)
        self.search_var = tk.StringVar()
        self.search_var.trace_add("write", lambda *_: self.apply_filter())
        search = ttk.Entry(bar, textvariable=self.search_var, width=36)
        search.pack(side=tk.LEFT, padx=(4, 12))

        ttk.Label(bar, text="分组:").pack(side=tk.LEFT)
        self.group_var = tk.StringVar(value="全部")
        group = ttk.Combobox(
            bar,
            textvariable=self.group_var,
            values=["全部", "全机程序", "当前用户程序"],
            state="readonly",
            width=14,
        )
        group.pack(side=tk.LEFT, padx=(4, 12))
        group.bind("<<ComboboxSelected>>", lambda *_: self.apply_filter())

        ttk.Label(bar, text="排序:").pack(side=tk.LEFT)
        self.sort_var = tk.StringVar(value="按名称")
        sort = ttk.Combobox(
            bar,
            textvariable=self.sort_var,
            values=["按名称", "按大小", "按安装日期"],
            state="readonly",
            width=10,
        )
        sort.pack(side=tk.LEFT, padx=(4, 12))
        sort.bind("<<ComboboxSelected>>", lambda *_: self.apply_filter())

        self.hide_sys_comp = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            bar,
            text="隐藏系统组件",
            variable=self.hide_sys_comp,
            command=self.apply_filter,
        ).pack(side=tk.LEFT, padx=(0, 12))

        self.theme_btn = ttk.Button(
            bar,
            text="🌙 深色",
            command=self.toggle_theme,
        )
        self.theme_btn.pack(side=tk.LEFT, padx=(0, 12))

        ttk.Button(bar, text="刷新", command=self.refresh).pack(side=tk.RIGHT)

    def _build_table(self) -> None:
        container = ttk.Frame(self, padding=(8, 0))
        container.pack(side=tk.TOP, fill=tk.BOTH, expand=True)

        columns = ("name", "version", "publisher", "size", "date", "scope")
        self.tree = ttk.Treeview(container, columns=columns, show="headings")

        headings = {
            "name": ("名称", 280),
            "version": ("版本", 100),
            "publisher": ("发行商/来源", 180),
            "size": ("大小", 90),
            "date": ("安装日期", 100),
            "scope": ("类型", 80),
        }
        for col, (text, width) in headings.items():
            self.tree.heading(col, text=text)
            self.tree.column(col, width=width, anchor=tk.W)

        scrollbar = ttk.Scrollbar(container, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.tree.bind("<Double-1>", lambda _: self.show_detail())
        self.tree.bind("<Return>", lambda _: self.show_detail())

        action_bar = ttk.Frame(self, padding=(8, 6))
        action_bar.pack(side=tk.BOTTOM, fill=tk.X)
        ttk.Button(action_bar, text="启动程序", command=self.launch_program).pack(side=tk.LEFT, padx=4)
        ttk.Button(action_bar, text="卸载选中", command=self.uninstall_selected).pack(side=tk.LEFT, padx=4)
        ttk.Button(action_bar, text="打开安装目录", command=self.open_install_dir).pack(side=tk.LEFT, padx=4)
        ttk.Button(action_bar, text="复制卸载命令", command=self.copy_uninstall_string).pack(side=tk.LEFT, padx=4)
        ttk.Button(action_bar, text="详情", command=self.show_detail).pack(side=tk.LEFT, padx=4)

    def _build_statusbar(self) -> None:
        self.status_var = tk.StringVar(value="就绪")
        self.status_label = ttk.Label(self, textvariable=self.status_var, anchor=tk.W, padding=(8, 4))
        self.status_label.pack(side=tk.BOTTOM, fill=tk.X)

    # ---------------------------------------------------------------- data

    def toggle_theme(self) -> None:
        self.dark_mode.set(not self.dark_mode.get())
        self.theme_btn.configure(text="☀️ 浅色" if self.dark_mode.get() else "🌙 深色")
        self.apply_theme()

    def refresh(self) -> None:
        self.status_var.set("正在扫描已安装程序…")
        self.update_idletasks()
        self._all_programs = scan_installed_programs()
        self.apply_filter()

    def apply_filter(self) -> None:
        q = self.search_var.get().strip().lower()
        group = self.group_var.get()
        sort = self.sort_var.get()

        items = self._all_programs

        if group == "全机程序":
            items = [p for p in items if p.is_system_wide]
        elif group == "当前用户程序":
            items = [p for p in items if not p.is_system_wide]

        if q:
            items = [p for p in items if q in p.name.lower() or q in p.publisher.lower()]

        if self.hide_sys_comp.get():
            items = [p for p in items if not p.is_system_component]

        if sort == "按大小":
            items = sorted(items, key=lambda p: p.estimated_size_kb, reverse=True)
        elif sort == "按安装日期":
            items = sorted(items, key=lambda p: p.install_date, reverse=True)
        else:
            items = sorted(items, key=lambda p: p.name.lower())

        self._current_filtered = items
        self._populate_tree()

    def _populate_tree(self) -> None:
        for row in self.tree.get_children():
            self.tree.delete(row)

        for p in self._current_filtered:
            type_label = {
                "registry": "全机" if p.is_system_wide else "用户",
                "desktop": "便携",
            }.get(p.source, p.source)
            self.tree.insert(
                "",
                tk.END,
                values=(
                    p.name,
                    p.version or "-",
                    p.publisher or "-",
                    format_size(p.estimated_size_kb),
                    format_date(p.install_date),
                    type_label,
                ),
            )

        total = len(self._all_programs)
        shown = len(self._current_filtered)
        self.status_var.set(f"共 {total} 个程序，当前显示 {shown} 个")

    def _selected(self) -> InstalledProgram | None:
        sel = self.tree.selection()
        if not sel:
            return None
        idx = self.tree.index(sel[0])
        if idx < 0 or idx >= len(self._current_filtered):
            return None
        return self._current_filtered[idx]

    # ---------------------------------------------------------------- actions

    def launch_program(self) -> None:
        p = self._selected()
        if p is None:
            messagebox.showinfo(APP_TITLE, "请先在列表中选中一个程序。")
            return
        # 桌面快捷方式类：display_icon 字段存的是目标 exe 路径
        target = p.display_icon if p.source == "desktop" else ""
        if not target or not os.path.isfile(target):
            messagebox.showinfo(APP_TITLE, "该程序没有可直接启动的目标（注册表安装类请用系统卸载/启动）。")
            return
        try:
            os.startfile(target)  # type: ignore[attr-defined]
            self.status_var.set(f"已启动 {p.name}")
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"启动失败：{e}")

    def uninstall_selected(self) -> None:
        p = self._selected()
        if p is None:
            messagebox.showinfo(APP_TITLE, "请先在列表中选中一个程序。")
            return
        if not p.uninstall_string:
            messagebox.showwarning(APP_TITLE, f"「{p.name}」没有可用的卸载命令。")
            return
        if not messagebox.askyesno(
            APP_TITLE,
            f"将启动系统卸载向导卸载「{p.name}」。\n\n"
            f"注意：本程序不会静默删除任何文件，卸载由系统向导完成。\n"
            f"是否继续？",
        ):
            return
        try:
            # 用 cmd /c 调起卸载程序，兼容带引号的路径
            subprocess.Popen(["cmd", "/c", "start", "", p.uninstall_string], shell=False)
            self.status_var.set(f"已启动「{p.name}」的卸载向导")
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"启动卸载程序失败：{e}")

    def open_install_dir(self) -> None:
        p = self._selected()
        if p is None:
            messagebox.showinfo(APP_TITLE, "请先在列表中选中一个程序。")
            return
        if not p.install_dir_exists():
            messagebox.showinfo(APP_TITLE, f"「{p.name}」没有可用的安装目录。")
            return
        try:
            os.startfile(p.install_location)  # type: ignore[attr-defined]
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"打开目录失败：{e}")

    def copy_uninstall_string(self) -> None:
        p = self._selected()
        if p is None:
            messagebox.showinfo(APP_TITLE, "请先在列表中选中一个程序。")
            return
        if not p.uninstall_string:
            messagebox.showinfo(APP_TITLE, f"「{p.name}」没有卸载命令。")
            return
        self.clipboard_clear()
        self.clipboard_append(p.uninstall_string)
        self.status_var.set(f"已复制「{p.name}」的卸载命令到剪贴板")

    def show_detail(self) -> None:
        p = self._selected()
        if p is None:
            messagebox.showinfo(APP_TITLE, "请先在列表中选中一个程序。")
            return

        win = tk.Toplevel(self)
        win.title(f"详情 — {p.name}")
        win.geometry("640x420")
        win.transient(self)

        rows = [
            ("名称", p.name),
            ("版本", p.version or "-"),
            ("发行商", p.publisher or "-"),
            ("大小", format_size(p.estimated_size_kb)),
            ("安装日期", format_date(p.install_date)),
            ("范围", "全机程序（HKLM）" if p.is_system_wide else "当前用户程序（HKCU）"),
            ("安装目录", p.install_location or "-"),
            ("卸载命令", p.uninstall_string or "-"),
            ("注册表项", p.registry_key),
        ]

        frame = ttk.Frame(win, padding=12)
        frame.pack(fill=tk.BOTH, expand=True)

        for i, (k, v) in enumerate(rows):
            ttk.Label(frame, text=k, width=10, anchor=tk.W).grid(row=i, column=0, sticky=tk.W, pady=2)
            text = tk.Text(frame, wrap=tk.WORD, height=1, width=70)
            text.insert(tk.END, v)
            text.configure(state=tk.DISABLED)
            text.grid(row=i, column=1, sticky=tk.EW, pady=2)

        frame.columnconfigure(1, weight=1)


# 给 dataclass 加一个便捷方法（避免改 scanner.py）
def _install_dir_exists(self: InstalledProgram) -> bool:
    return bool(self.install_location) and os.path.isdir(self.install_location)


InstalledProgram.install_dir_exists = _install_dir_exists  # type: ignore[attr-defined]


if __name__ == "__main__":
    app = AppManagerApp()
    app.mainloop()
