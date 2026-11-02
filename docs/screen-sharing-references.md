# 屏幕共享实现参考

核对日期：2026-11-02。对照项目源码和项目自己的说明，区分采集、编解码、缓冲与底层传输，避免将其他项目的延迟指标直接用于本应用。

| 项目及源码 | 采用的机制 | 本项目的应用 |
| --- | --- | --- |
| [scrcpy 开发说明](https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md) | 视频与控制分别处理，尽快显示解码结果，旋转后更新视频会话尺寸 | 网络读取与解码解耦，独立排空解码输出，继续通过流内参数更新处理旋转 |
| [scrcpy SurfaceEncoder](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java) | Surface 硬件编码、实时优先级、100 ms 静止画面补帧、低延迟参数 | 补上实时优先级和延迟参数，将静止补帧间隔调整为 100 ms |
| [scrcpy frame_buffer](https://github.com/Genymobile/scrcpy/blob/master/app/src/frame_buffer.c) | 新的已解码帧替换尚未显示的旧帧 | 同一批解码输出只渲染最新一帧，旧帧释放并反馈已消费，避免补播旧画面 |
| [WebRTC HardwareVideoEncoder](https://github.com/webrtc-mirror/webrtc/blob/main/sdk/android/src/java/org/webrtc/HardwareVideoEncoder.java) | 编码队列上限、CBR、动态码率更新，编码器跟不上时丢帧 | 在途帧限制、码率反馈调整；查询编码器能力，支持 CBR 时启用 |
| [WebRTC AndroidVideoDecoder](https://github.com/webrtc-mirror/webrtc/blob/main/sdk/android/src/java/org/webrtc/AndroidVideoDecoder.java) | 输出线程独立取出解码结果，显示跟不上时释放过时输出 | 网络等待不会阻止解码输出，单帧发送后也能及时显示 |
| [ScreenStream](https://github.com/dkrivoruchko/ScreenStream/blob/master/README.md) | Android MediaProjection，分别提供 MJPEG、WebRTC、RTSP 模式 | 确认投屏需要按传输协议评估带宽与延迟，不能把所有连接方式视为同一种性能水平 |

scrcpy 的服务端通过 ADB 以 shell 身份运行，接收端主要在桌面使用 FFmpeg/SDL；本应用使用普通 Android 应用的 MediaProjection 授权及 MediaCodec。参考其编码、调度和缓冲设计，保留应用的授权流程。

WebRTC 的拥塞控制还包括完整的媒体传输与反馈体系。本次的 Nearby STREAM 接收反馈属于针对现有链路的轻量流控，不等同于采用了 WebRTC。完整迁移到 WebRTC 需要另行处理媒体链路、信令、依赖与连接管理。

根据 [Nearby 官方策略说明](https://developers.google.com/nearby/connections/strategies)，Cluster 偏向多设备、小载荷，Point-to-Point 面向一对一高吞吐。因此普通端对端和 NFC 新建连接统一使用 `P2P_POINT_TO_POINT`，进入投屏仍复用既有端点。两台设备应安装本次构建并重新连接。

Nearby 仍可能使用不同的无线链路，编码和缓冲修复不能提高底层链路本身的物理容量。下一轮真机复测应记录设备型号、两端 Wi-Fi 与蓝牙开关、分辨率、实际码率、反馈耗时和观察到的画面延迟。

验证覆盖单帧停流仍能显示、250 ms 延迟确认下持续采集和解码、慢速确认窗口有界、未知确认不释放窗口，以及拥塞降码率和恢复。上述测试验证机制，不作为双机无线投屏延迟的实测结果。
