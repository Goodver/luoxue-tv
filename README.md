# 洛雪影视导航 · 手机版（Luoxue TV Nav Mobile）

一个面向手机的影视站点导航 APP：**系统自带 WebView 内核渲染**（无内置浏览器内核，安装包仅 ~735KB），全屏沉浸式浏览。

## 特性

- **系统 WebView 内核**：调用 Android System WebView 渲染，零内置内核，包体小、省内存
- **全屏导航页**：无地址栏、无状态栏、无任何浏览器 UI，纯沉浸式体验
- **站点清单**：内置 43 个在线影视站点（数据来自 [awesome-zhuiju-free](https://github.com/laoma528/awesome-zhuiju-free) 的 online_video 分类）
- **可用性检测**：每个站点带渐变圆形状态标签——**绿 = 可访问，红 = 不可访问**；app 启动时检测一次，不重复检测
- **远程数据更新**：点"检测更新"从多源拉取最新站点清单并写入本地 JSON（jsDelivr CDN → github.io → raw → 加速镜像，带时间戳绕过 CDN 缓存），国内网络下任一源可用即更新成功；更新站点只需修改 [resources/resources.json](resources/resources.json)，APP 端无需重装
- **置顶收藏**：长按卡片置顶 / 取消置顶（★ 标识），置顶站点独立分区显示，状态本地持久化
- **平板自适应**：按屏幕宽度自动切换列数（手机 2 列 / 小平板 3 列 / 平板 4 列 / 大平板 5 列），字号、间距同步等比放大
- **广告拦截（三管齐下）**：
  - **桌面 UA + 窄视口**：广告系统按桌面浏览器处理（不投广告），响应式站点仍按移动版 CSS 排版
  - **浏览器特征伪装**：`window.chrome` / `navigator.vendor` / `platform` / `hardwareConcurrency` / `userAgentData` 伪装成真 Chrome 桌面特征（页面脚本执行前注入）
  - **广告域名黑名单 31 域**（穿山甲 / 腾讯优量汇 / 百度联盟 / 360 联盟等，shouldInterceptRequest 拦截）+ **广告容器 CSS 隐藏**（底部横幅 / 右下角漂浮）
- **视频全屏**：HTML5 视频全屏接管，返回键三层处理（退出全屏 → 网页回退 → 退出进站页）
- **加载过场**：深色层 + 转圈 + "站点名 · 正在进入…"，页面加载完淡出
- **正式签名版**：release 包使用独立密钥库签名，可覆盖升级

## 下载

| 版本 | 文件 | 说明 |
| --- | --- | --- |
| v1.0.5 | [luoxue-tv-mobile-v1.0.5.apk](https://github.com/Goodver/luoxue-tv/releases/download/v1.0.5/luoxue-tv-mobile-v1.0.5.apk) | 系统 WebView · Android 9+ · 广告拦截 + 桌面UA窄视口 + 多源更新，签名版 |
| 历史版本 | [Releases 页面](https://github.com/Goodver/luoxue-tv/releases) | 全部历史版本 APK |

> 安装：手机直接打开 APK 安装（Android 7.0+，建议 9.0+）。包名 `com.luoxue.tv.mobile`，与电视版互不干扰。

## 构建

环境要求：

- JDK 17+
- Android SDK（compileSdk 36）
- Gradle 9.x

```bash
# 构建 debug + release 双版本
gradle :app:assembleDebug :app:assembleRelease

# 产物
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

> release 签名：正式版使用独立密钥库（仓库不包含密钥文件，签名密钥请妥善自行保管；**换签名会导致已安装版本无法覆盖升级**）。

## 目录结构

```
luoxue-tv-mobile/
├── app/
│   └── src/main/
│       ├── java/com/luoxue/tv/mobile/
│       │   ├── MainActivity.java       # 导航首页（纯代码 UI）
│       │   └── WebActivity.java        # 全屏进站页（系统 WebView + UA伪装 + 广告拦截 + 视频全屏）
│       ├── assets/
│       │   └── resources.json          # 内置 43 站导航数据（远程更新源）
│       └── AndroidManifest.xml
├── resources/
│   └── resources.json                  # 站点数据源（远程更新源）
└── build.gradle.kts
```

## 数据与免责声明

- 站点清单数据来源：[resources/resources.json](resources/resources.json)，源自 [awesome-zhuiju-free](https://github.com/laoma528/awesome-zhuiju-free)（仅取 online_video 分类）
- 本应用仅为导航入口，不托管、不存储任何影视内容；站点的可用性与内容由各站点自行负责
- 请勿轻易相信或点击站点上的广告（应用已内置广告拦截，但无法保证 100% 覆盖）

## 版本历史

- **v1.0.5**：检测更新按钮贴屏幕最右侧（修复 WRAP_CONTENT 导致 weight 失效）；底部标注作者 Goodver
- **v1.0.4**：对齐桌面版排版——提示语"绿 = 可访问 / 红 = 不可访问"带圆点，卡片状态点改纯展示
- **v1.0.3**：可用性只检测一次（远程更新不再触发二次检测，状态标签按 URL 保留）；提示语加绿/红圆点；底部标注版本号
- **v1.0.2**：桌面 UA + 窄视口（广告系统不投广告、响应式站点仍移动排版）；浏览器特征同步桌面（Win32/mobile:false/16 核）；广告域名黑名单扩至 31 域（含国内移动广告联盟）
- **v1.0.1**：浏览器特征伪装（window.chrome / navigator.vendor / platform / userAgentData），页面脚本执行前注入
- **v1.0.0**：首个手机版——系统 WebView 内核、手机 2 列导航、置顶收藏、可用性检测、远程多源更新、基础广告拦截（16 域黑名单 + CSS 隐藏）、视频全屏
