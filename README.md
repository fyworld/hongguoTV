# hongguoTV · Android TV 原生版

红果短剧的 Android TV 非官方原生客户端。**Kotlin + Android 原生 View + Media3**，最低 Android 7.0 / API 24。安装一个 APK，联网后可直接浏览和播放，无需部署服务器。

- 收藏和历史保存在本机，不上传任何账号数据
- APK 不含 JNI 库，ARM32 / ARM64 / x86 / x86_64 均可运行；视频解码能力依赖设备

## 功能

- **首页推荐**：热门短剧浏览，支持隐藏推荐、稍后看
- **遥控器操作**：焦点、方向键、确定键和返回键操作。
- **全局搜索**：TV 字母键盘 + 系统输入法双输入；拼音首字母联想（如 BFLC）；名称匹配 + 网页搜索合并、标题排序、季数补全；搜索历史流式排列
- **四板排行榜**：总热播榜 / 真人剧榜 / 漫剧榜 / AI剧榜
- **播放**：画面模式、480P / 720P / 1080P 清晰度选择、原比例播放、暂停、前后跳转、倍速、自动连播、断点续播、选集面板
- **本机记录**：收藏、观看进度、搜索历史；手机扫码备份与恢复（局域网）
- **手机输入**：扫码后用手机浏览器输入剧名发送到电视
- **应用内更新**：前台时自动检查 GitHub Release 新版，校验签名后由系统确认安装

## 派生来源与许可证

本项目按 **GPL-3.0-only** 发布，完整派生声明见 [NOTICE.md](NOTICE.md)：

- TV 界面框架与工程骨架派生自 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（GPL-3.0）
- 可用接口链路参考 [HongguoTV-Phone-TV-NoAds](https://github.com/qq294716498/HongguoTV-Phone-TV-NoAds)（Kotlin 独立重写）
- 内容签名与加密协议移植自 [drpys](https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715)（GPL-3.0）

本应用不是内容平台官方客户端。内容来自第三方接口，接口、签名、内容可用性或地区网络变化可能需要更新 APK。

## 构建

需要 JDK 17、Android SDK Platform 35 / Build Tools 35.0.0。Gradle 8.14.3 包装器已包含。设置 `ANDROID_HOME`，或在 `local.properties` 写入 `sdk.dir=SDK绝对路径`。

```sh
./gradlew.bat assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 工程结构

- `core/`：纯 Kotlin 内容列表、搜索联想、详情、请求签名、播放地址解析、MP4 索引和分段处理；可在 JVM 测试（40 项）
- `app/`：原生 TV 界面、遥控器焦点、本地收藏/进度、Media3 播放器及自定义 DataSource

播放器直接通过 HTTPS 分段拉取数据，经本机 Kotlin 处理后交给 Media3。没有 JS 引擎或常驻内容服务。仅在用户打开「手机输入」或「手机备份与恢复」窗口时，APK 临时监听局域网 HTTP；最长 5 分钟，窗口关闭或应用进入后台后停止。媒体只在内存中处理，不下载整部剧到存储。

## 验证边界

已进行 JVM 单元测试（40 项）、真实网络 Probe（search/home/detail/stream 1080P）、Android TV 模拟器遥控器检查。**实体电视的芯片解码、音频输出和长期稳定性需实机验证**。遇到设备解码失败可尝试 720P。

