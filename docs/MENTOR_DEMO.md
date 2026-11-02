# 导师演示录像（2026-11-02）

演示针对 OPPO PKB110、Android 16、1080×2374、共享包 `com.clawmate.shared`。
本阶段不修改产品功能；不调用云服务，不运行会议专项，不展示私人照片或凭据。

## 内容

1. 首页与共享聊天界面（不声称本片验证了云端回复）。
2. 公开演示工作流：等待 3 秒后执行 echo，实际状态 success。
3. 公开演示工作流：等待 30 秒，在运行中停止，实际状态 cancelled。
4. 查看共享图形编辑器的节点、失败策略。
5. 本应用真实截图、本地预览；不确认上传、不执行跨应用动作。

这是本机确定性步骤演示，不是模型规划、GUI 自动循环或真实业务采集。
未验收功能继续以 MIGRATION_BACKLOG.md 为准。

## 重放条件

`scripts/record-mentor-demo.py` 是本台设备已排练布局的驱动，不是通用 UI 测试。
它检查任务索引前两项分别为 `mentor-demo-cancel` 和 `mentor-demo-success`。
两条任务已在本次设备准备中追加，保留原有任务；旧索引备份在忽略的证据目录。
示例配置均使用 metadata.description 为“演示：取消等待”或“演示：本机执行”，
steps 为 gui_action/sleep（30 或 3 秒），再接 tool/echo。
不要对私人手机或不同布局直接运行；先检查画面与入口。

```powershell
python scripts/record-mentor-demo.py --adb <adb路径> --serial <设备序列号> --scrcpy <scrcpy路径> --output <不存在的录像目录>
python scripts/render-mentor-demo.py --take <录像目录> --output <新的MP4路径> --font <中文字体文件>
```

录制需要 Python、adb、scrcpy；后期需要 Pillow、FFmpeg/ffprobe 和中文字体。
scrcpy 仅录制视频（禁用声音、播放窗口与控制），adb 驱动操作。
来源：[scrcpy 官方仓库](https://github.com/Genymobile/scrcpy)。
本机 screenrecord 直接写文件被系统拒绝，已改用官方 scrcpy 5.0.1，未更改权限。
视频没有配音；横屏版添加章节说明并裁去系统状态栏，原始竖屏版保留实际录像。

## 验证与交付

检查章节截图、实际录像关键帧、视频完整解码和执行结果。
录像、设备标识、旧索引及工具下载保存在忽略目录或用户 Videos 目录，不提交 Git。
本次不修改用户手册，因为没有改变产品入口、按钮或能力边界。
