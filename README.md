# 开屏跳过：个人离线版本

基于 [madeye/ad-skipper](https://github.com/madeye/ad-skipper) 修改，保留原 MIT 许可证与版权信息。

## 当前范围

- Android 11+、arm64-v8a；可与原版并存，包名 `com.southbun.adskipper`。
- App 进入前台后最多检查 8 秒；同一 App 的内容变化不会延长窗口。
- 默认开启控件树与随包中文 OCR，图片按钮检测（YOLO）默认关闭，可在设置开启。
- 控件和图片模型要求当前画面的广告证据；OCR 在没有广告标识时可提出完整“跳过/Skip + 数字”候选，必须复查到同一位置的倒计时实际递减后才点击。关键词不匹配“跳过绑定”“跳过教程”等业务文字。
- 普通候选限于角落；小尺寸的边缘倒计时按钮可位于更低的位置，仍须验证倒计时递减。点击前核对包名、窗口、设置与 8 秒会话时限。
- L1 保留按钮控件，优先调用控件点击；文字位于按钮内部时查找就近且大小合理的可点击父控件，跳过不可用按钮。控件拒绝点击时才退回触摸，不在接受后重复点击。
- 每次前台会话最多接受一次点击；统计显示“已发送点击”。L1 点击后复查按钮是否仍在，状态提示 4 秒后自动隐藏。
- 开启“显示点击状态”后会提示正在识别、正在确认倒计时；8 秒内未点击时给出未识别或复查失败的原因。
- 桌面、系统设置与浏览器默认不操作。
- 截图仅用于本地识别，不写入文件；状态浮窗不保存截图。禁用应用备份与设备迁移数据。
- 无 INTERNET / ACCESS_NETWORK_STATE 权限；不包含大视觉模型下载和远程控制。
- 不使用亮屏持续保活脉冲或精确闹钟，只保留通知与识别窗口内的短时唤醒锁。

## 安装与验证

1. 从本仓库 Actions 的 `ad-skipper-selfuse` 产物下载 APK。
2. 安装后按应用内按钮开启无障碍服务；部分系统需要先在应用信息中允许受限设置。
3. 小米等机型按实际情况允许后台运行；先在应用设置开启“自测模式”，再打开首页“模拟广告测试”。
4. 先测试少量常用 App，同时检查正常引导页、应用切换、广告自动结束和关闭总开关。
5. 遇到不确定场景会放弃操作。真机覆盖率、厂商后台限制与耗电需要逐项验证。

测试包没有配置个人签名时使用 CI 临时调试签名；不同构建可能需要卸载旧测试包重装。日用前配置下面的固定签名。

## 云端构建

GitHub Actions 执行单元测试、Android lint、APK 构建，并检查最终 APK 权限、备份标志、签名和 SHA-256。
ncnn 固定到 20260526 发布包，并校验 SHA-256；不再拉取 llama.cpp。
主分支推送、PR 和手动运行可触发构建。

首次测试可直接构建 debug APK。固定签名 release 使用本仓库四个 Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`：个人签名 keystore 的 Base64 内容
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密码
- `ANDROID_KEY_ALIAS`：密钥别名
- `ANDROID_KEY_PASSWORD`：密钥密码

密钥不要提交到仓库。配置齐全后，工作流会生成签名 release；未配置时生成可安装的 debug 测试包。

本地构建需要 JDK 17、Android SDK 36、NDK 与 CMake 3.22.1：

```sh
curl -fL -o ncnn.zip https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip
echo '26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804  ncnn.zip' | sha256sum -c
unzip -q ncnn.zip
mv ncnn-20260526-android-vulkan core/src/main/cpp/ncnn
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```
