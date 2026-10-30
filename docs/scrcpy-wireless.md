# scrcpy 无线投屏 / 电脑控制 验收记录

日期：2026-10-31
设备：SM-F731U1 / Android 17，无线调试 10.0.0.233:33917

## 部署

- scrcpy 5.0.1 win64（官方 release，11,264,634 字节），解压到 `tools/scrcpy-win64-v5.0.1/`
- 一键脚本：`scripts/scrcpy_wireless.sh`
- 双击入口：`scrcpy_wireless.bat`（仓库根目录）
- 只走官方无线调试 TLS 通道，mDNS 自动发现，端口漂移/重启后无需手动改地址

启动参数：`--keyboard=uhid --max-size=1080 --max-fps=60 --no-audio --stay-awake --power-off-on-close`

说明：
- `--no-audio` 是刻意的：手机上 TTS/ASR 还要占用音频通道，投屏不抢。
- `--stay-awake` 保证调试时不被息屏打断。

## 实测结果

1. 发现与连接（`scrcpy_wireless.sh` 输出）
   `INFO: ADB device found: --> (tcpip) 10.0.0.233:33917 device SM_F731U1`
   `[server] INFO: Device: [samsung] samsung SM-F731U1 (Android 17)`

2. 视频真实到达
   `INFO: Renderer: direct3d11` / `INFO: Interop: d3d11va` / `INFO: Video decoding: d3d11va` / `INFO: Texture (D3D11VA): 448x1088`
   窗口存在：`MainWindowTitle = ZFlip5 10.0.0.233:33917`
   录制验证（`--record`）：`screen-20261031-041133.mp4`，h264，440x1080，458 帧，时长 19.57 s，10,033,293 字节，`Recording complete` 正常收尾（moov 完整，ffprobe 可解析）。
   抽帧（第 19 秒）确认画面是真实设备内容：锁屏 04:12、"10月31日周六"、中国联通 + VoLTE、Wi-Fi、电量、底部电话/相机图标。
   → 投屏链路真实可用，不是"进程启动成功"。

3. 控制通道
   `dumpsys input` 中出现 UHID 设备 `scrcpy`（`/sys/devices/virtual/misc/uhid/0006:0000:0000.0003`），说明 scrcpy 已通过控制 socket 在系统侧注册了虚拟键鼠设备，控制通道已建立。
   端到端注入（点击/快捷键落到设备）本轮未由自动化确认：本会话的窗口前台化被系统拒绝（`foreground_unavailable`，实际前台 HWND 不是 scrcpy），synthetic 事件 scrcpy 未响应。期间设备已被切到其他前台应用，为避免向在用手机盲注入触摸，我停止了继续尝试。
   待人工确认（10 秒）：在投屏窗口里点一下屏幕，手机应同步响应。这一步需要你在电脑前完成。

## 已知限制

- 渲染走 D3D11VA 硬件叠加层，`computer_use` 截图拿到的窗口内容是黑的（截不到叠加层），不代表投屏失败；判断画面请看实际窗口或 `--record` 的录像。
- 手机息屏/AOD 时投屏内容就是黑的，需要 `input keyevent 224` 或按电源键唤醒。
- 投屏不转发音频。
- 电脑控制依赖 scrcpy 窗口获得前台焦点；自动化环境里前台化受限时，只能用人工点击。

## 用法

    bash scripts/scrcpy_wireless.sh              # 一键投屏
    bash scripts/scrcpy_wireless.sh --record     # 投屏并录像到 screen-<时间>.mp4
    bash scripts/scrcpy_wireless.sh --no-window --record   # 无窗口只录像
    bash scripts/scrcpy_wireless.sh --kill       # 关闭投屏

    双击 scrcpy_wireless.bat 等同第一条。
