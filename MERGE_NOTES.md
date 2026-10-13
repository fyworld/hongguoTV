# 红果 TV 合并版（hongguoTV-merged）

把 **HongguoTV-Phone-TV-NoAds 的可用播放内核**移植进 **N3urda/hongguoTV 的电视界面**，产出的可播放 TV 新版本。

- 最低 **Android 7.0（API 24）**，电视遥控器操作
- 包名 `com.hongguotv.nativeapp`，可与旧版并存
- 单 APK，无需部署 Node / Docker / 服务器

## 为什么原 kotlin-tv 无法播放

原版 `ContentRepository` 使用 drpys 移植的复杂签名链（x-argus / x-ladon / x-helios / x-medusa）请求
`multi_video_model/v1/` 接口，该链路已失效（签名/接口被平台侧变更拒绝）。

## 移植方案

以 phone-version（Go 内核）验证过的**可用接口链**重写了 `core/ContentRepository.kt`，对外接口
（home / search / detail / stream / comicRanking）保持不变，TV 界面层（MainActivity / HomeScreen 等）零改动：

| 功能 | 数据源 | 说明 |
|------|--------|------|
| 首页/分类 | App API `/reading/distribution/category/landpage/v/` | 轻量签名 X-Khronos + X-Gorgon（MD5 变换），offset 分页 |
| 详情+分集 | 网页 `hongguoduanju.com/detail?series_id=` | App 的 video_detail 接口当前返回空 body，改用网页 _ROUTER_DATA |
| 播放取流 | App API `/novel/player/video_model/v1/` | 5 档清晰度 + CENC spade_a 密钥，已实测可拉流 |
| CENC 解密 | `MediaCrypto.deriveKey`（spade_a→AES-128） | 沿用原 kotlin-tv 实现，与 phone-version 算法一致 |
| 搜索 | 网页 `/search/{keyword}?page=` | _ROUTER_DATA 解析 |
| 四板榜单 | 网页 `/rank/{hot-drama,hot-real-drama,hot-comic-drama,hot-ai-drama}` | 总热播榜/真人剧榜/漫剧榜/AI剧榜，沿用 ComicRank 双模式解析 |
| 视频分段 | HTTPS Range 分段拉取 + 内存解密 | RemoteVideo / VideoDataSource 不变 |

清晰度选择保留原版安全逻辑：过滤 bytevc2（Android MP4 提取器只出声音不出画面），优先 h264。

## 主要改动

1. `core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt` — 全部重写（见上表）
2. `core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt` — 测试改为匹配新接口链
3. 删除 `SignerTest.kt`、`signing.json`（旧签名链测试样本）
4. `app/build.gradle.kts` — `minSdk 26 → 24`，`compileSdk 36 → 35`（Media3 1.8 要求），`targetSdk 36 → 34`
5. `core/.../AppUpdates.kt` — `renameTo` 改为 `Files.move(REPLACE_EXISTING)`（Windows 构建机上原测试失败）
6. `gradle.properties` — 加 `android.overridePathCheck=true`（若项目路径含中文）

`Signer.kt` / `AesV3.kt` / `VendorConstants.kt` 保留（RemoteVideo 的 UA 与 sbox 仍引用），不再参与请求签名。

## 四板榜单（2026-10-13 追加）

排行榜 tab 从单一漫剧榜扩展为 phone-version 的四板体系，TV 样式不变：

- `core/RankBoard.kt`（新增）— `RankBoard` 枚举：HOT/REAL/COMIC/AI（path + upstreamKey + label）
- `core/ComicRank.kt` — `parse(html,page,board)` 参数化 board；错误信息带榜单名
- `core/ContentRepository.kt` — 新增 `ranking(board,page)`；`comicRanking(page)` 保留为 COMIC 别名
- `app/MainActivity.kt` — 排行榜 tab 顶部加四板切换按钮（样式仿片库"短剧/漫剧"按钮）；标题/副标题随 board 动态变化；焦点链 nav→board→刷新→卡片
- `app/Library.kt` + `core/LibraryBackup.kt` + `core/LibraryIndex.kt` — `rankBoard` 设置持久化（SharedPreferences key `rankBoard`，备份格式 v1 兼容追加字段）
- 网页端两种渲染模式都支持：mergeLoaderData fn=p（内联 JSON）与 fn=s（渲染 article 卡片 + 分页 nav），服务器会在两者间切换

实测（2026-10-13）：四板各 20 项/5 页，rank 连续，热度/更新时间正常。

## 构建与验证记录（2026-10-13）

```sh
# 环境：JDK 17 + Android SDK（platforms 34/35）+ Gradle wrapper 8.14.3 + AGP 8.11
JAVA_HOME=<jdk17> ./gradlew.bat :core:test :app:assembleDebug -I <aliyun-init>

# 结果
# :core:test — 全部通过（含新接口链 mock 测试 + 6 个四板榜单测试，共 89 个）
# :core:run（真实网络 Probe）— search=10; home=14; detail=55集; stream=1080P 30MB ftyp ✅
# :core:run --boards（四板实网验证）— 每板 20 项/5 页，rank=1..20，热度/更新时间正常 ✅
# :app:assembleDebug — BUILD SUCCESSFUL，10.2MB（四板版 10.45MB）
# aapt: sdkVersion='24' targetSdk='34' leanback-launchable ✅
```

## 安装

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 或拷贝 APK 到电视用文件管理器安装（Android 7 需开启"未知来源"）
```

## 许可证

GPL-3.0-only，沿用上游声明。内容来自第三方接口，接口变化可能导致失效；本项目与红果官方无关联。
