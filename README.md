# 开屏跳过：个人本地 AI 版本

基于 [madeye/ad-skipper](https://github.com/madeye/ad-skipper) 修改，保留原 MIT 许可证与版权信息。

## 当前范围

- Android 11+、arm64-v8a；可与原版并存，包名 `com.southbun.adskipper`。
- App 进入前台后最多检查 8 秒；同一 App 的内容变化不会延长窗口。
- 默认开启控件树与随包中文 OCR。YOLO 和可选下载的 InternVL3-2B 视觉大模型因真机兼容性问题默认关闭，已有开关选择保留。
- 视觉模型直接检查启动截图，不要求 OCR 或控件先读出“广告”标识。控件/OCR 快速路径保留广告上下文核验；没有标识的完整“跳过/Skip + 数字”候选仍须验证倒计时递减。关键词不匹配“跳过绑定”“跳过教程”等业务文字。
- 按钮保留尺寸、边界检查，不再限定四角。点击前核对包名、窗口、设置与 8 秒会话时限。
- VLM 仅接受剩余会话时间中最多 4 秒内完成的结果；原生图像编码可能超出时限，返回后的超时结果会丢弃。有效结果重新截图核对按钮区域和页面，画面明显变化则放弃旧坐标。
- VLM 在后台分析，控件/OCR/YOLO 继续扫描稍后出现的按钮；快速路径命中、切换应用或关闭开关时取消后台任务，不等待大模型完成才点击。
- L1 保留按钮控件，优先调用控件点击；文字位于按钮内部时查找就近且大小合理的可点击父控件，跳过不可用按钮。控件拒绝点击时才退回触摸，不在接受后重复点击。
- 每次前台会话最多接受一次点击；统计显示“已发送点击”。L1 点击后复查按钮是否仍在，状态提示 4 秒后自动隐藏。
- 开启“显示点击状态”后会提示正在识别、正在确认倒计时；8 秒内未点击时给出未识别或复查失败的原因。
- “本地 AI”页显示当前进程的实际识别次数、最近结果和耗时。界面暂时不可读时保留原有识别预算；自测浮窗不会被误判为离开测试页。
- 首页区分“运行中”和“已授权，服务未连接”；大模型的 C++ 运行异常会转成错误状态，暂停本进程的模型识别，保留控件/OCR/YOLO 路径。
- 桌面、系统设置与浏览器默认不操作。
- 截图仅用于本地识别，不写入文件；状态浮窗不保存截图。禁用应用备份与设备迁移数据。
- INTERNET 仅用于用户主动下载两个模型文件：ModelScope、Hugging Face 和 hf-mirror 的 HTTPS GET；不上传截图，不包含远程控制。模型固定版本、大小与 SHA-256，校验后才允许加载。拒绝明文网络，不申请 ACCESS_NETWORK_STATE。
- 不使用亮屏持续保活脉冲或精确闹钟，只保留通知与识别窗口内的短时唤醒锁。

## 安装与验证

1. 从本仓库 Actions 的 `ad-skipper-selfuse` 产物下载 APK。
2. 安装后按应用内按钮开启无障碍服务；部分系统需要先在应用信息中允许受限设置。
3. 小米等机型按实际情况允许后台运行；先在应用设置开启“自测模式”，再打开首页“模拟广告测试”。
4. 要启用大模型，在“本地 AI”页面连接 Wi-Fi 下载约 1.5GB，等待“模型已就绪”。下载支持暂停与续传，未下载时控件/OCR/YOLO 照常工作。
5. 先测试少量常用 App，同时检查正常引导页、应用切换、广告自动结束和关闭总开关。记录 VLM 是否赶在广告结束前完成。
6. 遇到不确定场景会放弃操作。真机覆盖率、厂商后台限制与耗电需要逐项验证。Xiaomi 17 Ultra / HyperOS 4 的真机日志已出现大模型 Vulkan `ErrorDeviceLost` 和 YOLO OpenMP 初始化崩溃；隔离测试中，大模型替代路径仅图像编码就耗时约 14–29 秒。该设备先使用控件/OCR，视觉模型另行适配。

测试包没有配置个人签名时使用 CI 临时调试签名；不同构建可能需要卸载旧测试包重装。日用前配置下面的固定签名。

## 云端构建

GitHub Actions 执行单元测试、Android lint、APK 构建，并检查最终 APK 权限、备份标志、签名和 SHA-256。
ncnn 固定到 20260526；llama.cpp 固定为原作者基准使用的 d6cf0b288dfac7015cfe705286e612c66a376a9c；Vulkan-Headers 固定为 8864cdc896bbc2a9b6eb36b3218fc9ef57908d77。构建前校验源代码压缩包 SHA-256。
主分支推送、PR 和手动运行可触发构建。

首次测试可直接构建 debug APK。固定签名 release 使用本仓库四个 Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`：个人签名 keystore 的 Base64 内容
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密码
- `ANDROID_KEY_ALIAS`：密钥别名
- `ANDROID_KEY_PASSWORD`：密钥密码

密钥不要提交到仓库。配置齐全后，工作流会生成签名 release；未配置时生成可安装的 debug 测试包。

本地构建需要 JDK 17、Android SDK 36、NDK、CMake 3.22.1、glslc 与 SPIRV-Headers。Linux 上按工作流“Fetch pinned local VLM dependencies”步骤准备 llama.cpp 与 Vulkan-Headers，再准备 ncnn：

```sh
curl -fL -o ncnn.zip https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip
echo '26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804  ncnn.zip' | sha256sum -c
unzip -q ncnn.zip
mv ncnn-20260526-android-vulkan core/src/main/cpp/ncnn
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```
