# launcher

Windows 一键启动器：WinForms 单文件 EXE，源码 `src/PsychCounselorLauncher.cs`，构建脚本 `build-launcher.cmd`（用系统 `csc.exe`，无需额外 .NET SDK）。

> 启动器的端口扫描/冲突弹窗/健康等待/幂等清理/self-test 退出码、首次启动超时与 ONNX 缓存行为，完整说明见唯一技术文档 §3.4 与 §12：[`docs/TECHNICAL_DESIGN.md`](../docs/TECHNICAL_DESIGN.md)。
