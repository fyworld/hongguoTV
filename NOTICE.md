# 第三方声明与派生来源

本项目（hongguoTV，Android TV 原生应用）是在以下开源项目基础上合并、重写与二次开发而成：

## 直接派生来源

1. **N3urda/hongguoTV**（GPL-3.0）
   - https://github.com/N3urda/hongguoTV
   - 提供本项目 `kotlin-tv/` 的工程骨架、TV 界面框架（遥控器焦点体系、导航、卡片布局）与整体架构。本仓库在此基础上重写了内容接口层（ContentRepository），并新增搜索联想体系（SearchSuggest）、四板排行榜、TV 字母键盘搜索页等功能。
2. **qq294716498/HongguoTV-Phone-TV-NoAds**（无许可证声明）
   - https://github.com/qq294716498/HongguoTV-Phone-TV-NoAds
   - 本项目参考其可用的红果短剧接口链路（App landpage 目录、网页 detail、App video_model/v1 播放、轻量 X-Khronos/X-Gorgon 签名），在 Kotlin 中独立重新实现了等价逻辑；未复制其 Flutter/Go 源代码。该仓库及其上游 guoapp 未附带开源许可证，特此如实说明来源。
3. **drpys**（GPL-3.0，经 N3urda/hongguoTV 转述）
   - https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
   - `core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是 `spider/js/红果果[短].js` 的 Kotlin 移植。本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

## 许可证

本项目按 **GPL-3.0-only** 发布，许可证全文见 [LICENSE](LICENSE)。发布 APK 时同时提供对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或上述 JavaScript 源文件。

## 其他依赖

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用上游素材。本应用不是内容平台官方客户端，内容来自第三方接口；接口、签名、内容可用性或地区网络变化可能需要更新 APK。
