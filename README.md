# 洛雪影视导航（Luoxue TV Nav）

一个手机 / 平板 / 电视通用的影视站点导航壳 APP：全屏无浏览器 UI 的导航页，列出在线影视站点，点击任意站点全屏直达。

## 特性

- **全屏导航页**：无地址栏、无状态栏、无任何浏览器 UI，纯沉浸式体验
- **站点清单**：内置 43 个在线影视站点（数据来自 [awesome-zhuiju-free](https://github.com/laoma528/awesome-zhuiju-free) 的 online_video 分类）
- **可用性检测**：每个站点带渐变圆形状态标签——**绿 = 可访问，红 = 不可访问**（检测中为灰）
- **远程数据更新**：点"检测更新"从本仓库的 [resources/resources.json](resources/resources.json) 拉取最新站点清单并写入本地 JSON，程序始终读取本地数据；拉取失败自动沿用本地已有清单。更新站点只需修改仓库中的该 JSON 文件，APP 端无需重装
- **置顶收藏**：触屏长按卡片置顶 / 取消置顶（★ 标识），置顶站点独立分区显示，状态本地持久化
- **平板自适应**：按屏幕宽度自动切换列数（手机 2 列 / 小平板 3 列 / 平板 4 列 / 大平板 5 列），字号、间距、图标同步等比放大
- **电视 / 盒子适配**：自动识别 Android TV 环境，启用大屏 UI（≥5 列 + 1.4 倍放大）与完整遥控器导航——方向键移动焦点、确认键进站、菜单键置顶、返回键回退

## 内核

- **GeckoView 158（Firefox 内核，2026-10-21 版，armeabi-v7a 官方变体）**：进站主渲染内核，支持 `:has()` 等全部现代 CSS 特性，解决旧内核（系统 WebView Chrome/66、X5 Chromium 89）的排版错乱、黑屏、图片不加载等问题
- 桌面 Edge UA：与电脑端完全一致，站点按桌面版渲染

## 电视端遥控器输入（v1.36 起）

- **原生滚动**：`PanZoomController.scrollBy` 内核级滚动，方向键驱动，不会黑屏
- **原生点击**：`PanZoomController.onTouchEvent` 注入完整触摸序列 + `onMouseEvent` 鼠标序列（hover 定位 → down → up，buttonState 归零），兼容只吃触摸 / 只吃鼠标事件的站点
- **焦点触边滚动**：光标在视口内自由移动，移到边缘（EDGE_MARGIN=100）方向键才滚动页面，TV 标准手感
- **长按拖动**：长按确认键起 DOWN → 方向键 MOVE → 松开 UP（验证码滑块等拖动场景）
- **备用输入桥**：WebExtension（gv_bridge）content script 通道，主输入不依赖它

## 下载

| 版本        | 文件                                                                         | 说明                                          |
| --------- | -------------------------------------------------------------------------- | ------------------------------------------- |
| v1.36 正式版 | luoxue-tv-nav-v1.36-gecko-release.apk | GeckoView 158 内核 + 原生输入桥（滚动/点击/拖动全通）+ 焦点触边滚动 + 鼠标事件双发，签名版 |

> APK 体积较大（约 158MB，含 Gecko 内核），超过 GitHub 单文件 100MB 限制，不随仓库分发；正式发布以仓库 Release 附件为准。

## 构建

环境要求：

- JDK 17+
- Android SDK（compileSdk 37.2）
- Gradle 9.x

```bash
# 构建 debug + release 双版本
gradle :app:assembleDebug :app:assembleRelease

```

## 历史版本（旧内核）

- v1.31~v1.35：GeckoView 158 换核过渡期（WebExtension 桥尝试）
- v1.24~v1.30：内嵌 X5 内核（Chromium 89）
- v1.13~v1.23：系统 WebView（Chrome/66），虚拟光标模式
