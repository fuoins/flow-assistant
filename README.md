# 流量助手

中国移动流量查询助手（Android 原生应用）。登录中国移动官方账号后，自动查询流量余量并按套餐分组汇总；内置多线程流量消耗测速与定向流量领取入口，全程原生界面，不显示官方网页。

## 功能

- **流量查询与汇总**：隐藏 WebView 复用官方登录态，自动抓取 `flowquery` 流量明细，按「通用流量 / 咪咕视频 / 移动云盘 / 咪咕快游 / 其他 APP 专属」分组汇总，两列小卡展示剩余/总量/已用，点击展开套餐明细
- **登录**：原生登录界面（仅短信验证码登录，协议默认勾选），原生滑块验证（复刻官方 tianai-captcha 协议），Cookie 持久化保持登录态，重启直进流量页
- **流量消耗测速**：4 线程并发下载指定测速源，实时统计已消耗流量；测速源与流量领取链接支持外部 JSON 配置（`speed_sources` + `claim_groups`），失败自动回退内置默认
- **流量领取**：内置辽宁专属活动 + 全国通用定向流量（咪咕视频/快游/阅读/音乐/云盘等）领取入口
- 纯原生 UI：渐变头部、卡片折叠、进度条、自定义滑块验证，全程不显示官方网页

## 技术要点

- 原生 Java 实现，无第三方依赖（`HttpURLConnection` + 原生控件）
- WebView 仅作隐藏会话容器：登录/滑块/取数全部在后台完成
- 滑块验证完全复刻官方协议：`/genCaptcha` 取图 → 原生拖动 → `/check` 提交轨迹 → 回调官方 `sendsms` 发短信
- 登录跳转看门狗：官方跳转链卡住时自动直达流量页

## 构建

需要 JDK 17 与 Android SDK（platform 34 / build-tools 34.0.0）：

```bash
./build.sh
# 产物输出到 build/流量助手.apk
```

签名使用 `debug.keystore`（未随仓库分发，请自行生成或使用自建签名）：

```bash
keytool -genkeypair -v -keystore debug.keystore -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android
```

## 版本

- v1.8：测速源选择器 UI 升级、应用更名「流量助手」
- v1.7：原生滑块验证、全新会话防风控、登录/启动取数进度反馈
- 安装包见 `release/flow-assistant-v1.8.apk`

## 声明

本项目仅供学习交流。流量数据来源于中国移动官方页面，消耗测速请合理使用，定向流量识别以运营商计费为准。
