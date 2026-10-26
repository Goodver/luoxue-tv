package com.luoxue.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.ContentDelegate;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate;
import org.mozilla.geckoview.GeckoSession.ProgressDelegate;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;
import org.mozilla.geckoview.ScreenLength;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/**
 * 全屏网页页（GeckoView 内核版）——无地址栏、无状态栏、无任何浏览器 UI。
 * 与 WebActivity(X5) 界面完全一致，仅渲染内核换成 Mozilla GeckoView
 * （Firefox 158 内核，2026-10 版）：X5 内核(Chromium 89)缺失的 :has() 等现代特性
 * 全部支持，解决电视端"排版错乱/进站黑屏/只显示logo"等内核能力不足问题。
 *
 * 内核差异（v1.31）：
 * - 桌面布局：viewportMode=DESKTOP + 桌面 UA，无主文档 viewport 注入
 * - GeckoView 158 已移除 GeckoSession.evaluateJavascript —— 遥控交互全部走
 *   内核原生输入管线（比 JS 注入更可靠）：
 *     · 滚动：PanZoomController.scrollBy() 内核级滚动（不会黑屏）
 *     · 点击：dispatchTouchEvent 触摸按下/抬起（内核合成 click）
 *     · 拖动：触摸 DOWN + 方向键 MOVE（滑动验证码滑块）
 * - 视频全屏：ContentDelegate.onFullScreen 回调控制光标层与系统 UI
 */
public class GeckoWebActivity extends Activity {

    // 桌面 UA：与电脑端桌面版 app（Electron 45 = Chromium 156.0.8078.48）实测完全一致
    // 实测值：Mozilla/5.0 ... luoxue-tv-nav-desktop/1.0.10 Chrome/156.0.8078.48 Electron/45.0.0 Safari/537.36
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) luoxue-tv-nav-desktop/1.0.10 "
            + "Chrome/156.0.8078.48 Electron/45.0.0 Safari/537.36";

    private static final int CURSOR_STEP = 48;
    private static final int SCROLL_STEP = 64;
    private static final float TOUCH_SCROLL_DIST = 150;  // v1.38n 触摸滑动距离（方向键触边一滑的位移）
    private static final int SCROLL_STEP_DRAG = 20;
    private static final int DRAG_HOLD_MS = 500;
    private static final int CURSOR_SIZE = 14;
    /** v1.38 双击判定窗口：两次确认键按下间隔 < 350ms 视为双击（追加鼠标右键） */
    private static final int DOUBLE_CLICK_MS = 350;
    /** 焦点触边滚动：光标距视口边缘小于该值后，方向键才触发页面滚动 */
    private static final int EDGE_MARGIN = 100;
    private static final long MIN_LOADING_MS = 800; // 加载过渡页最短显示时长

    // v1.38 双击判定：上一次确认键松开时间
    private long lastClickUpTime = 0;

    // GeckoRuntime 进程级单例（GeckoView 必须共享 runtime）
    private static GeckoRuntime geckoRuntime;

    // ===== v1.36 WebExtension 输入桥 =====
    // GeckoView 158 无 evaluateJavascript，且 javascript: URL 不执行（v1.35 实测）、
    // dispatchTouchEvent 注入的滚轮/鼠标事件内核不识别（v1.34 实测）。
    // 官方唯一可靠 JS 通道：WebExtension native messaging——
    // 扩展 content script 通过 runtime.connectNative("gv-bridge") 连回 App，
    // App 持有 Port，用 port.postMessage 驱动页面 DOM（点击/滚动/悬停/拖动）。
    private static WebExtension gvExtension;
    private static WebExtension.Port gvPort;
    private static boolean gvBridgeReady = false;
    /** 轮询通道的待执行指令（App→页面，onMessage 应答下发） */
    private static org.json.JSONObject pendingCmd = null;
    /** v1.38j：当前活跃 GeckoSession（供 initBridge 装好扩展后补设 session 级消息代理） */
    private static volatile GeckoSession activeSession = null;
    /** v1.38j：共享 MessageDelegate——扩展级与 session 级复用同一实例（onConnect/onMessage） */
    private static WebExtension.MessageDelegate gvMsgDelegate = null;

    private static synchronized void initBridge(GeckoRuntime rt) {
        if (gvBridgeReady || rt == null) return;
        try {
            android.util.Log.d("LuoxueTV", "initBridge: installing gv-bridge extension");
            WebExtensionController ctrl = rt.getWebExtensionController();
            // 电视端不能弹权限窗：安装/更新一律自动允许
            ctrl.setPromptDelegate(new WebExtensionController.PromptDelegate() {
                @Override
                public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                        WebExtension ext, String[] perms, String[] origins, String[] data) {
                    try {
                        return GeckoResult.fromValue(
                                new WebExtension.PermissionPromptResponse(true, true, true));
                    } catch (Exception e) { return null; }
                }
                @Override
                public GeckoResult<AllowOrDeny> onUpdatePrompt(
                        WebExtension ext, String[] perms, String[] origins, String[] data) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                @Override
                public GeckoResult<AllowOrDeny> onOptionalPrompt(
                        WebExtension ext, String[] perms, String[] origins, String[] data) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
            });
            // ensureBuiltIn：幂等安装（已安装则不重装，避免旧版被禁用导致
            // App delegate 与页面 content script 版本错位、消息路由断裂）
            ctrl.ensureBuiltIn("resource://android/assets/web-extension/", "gv-bridge@luoxue.tv")
                    .accept(new GeckoResult.Consumer<WebExtension>() {
                        @Override
                        public void accept(WebExtension ext) {
                            android.util.Log.d("LuoxueTV", "initBridge: install OK id=" + ext.id);
                            gvExtension = ext;
                            // 共享消息代理：扩展级 + session 级双注册（v1.38j）
                            // 根因：GeckoView 125+ 的 content script connectNative 需 session 级
                            // delegate，否则消息被 release（logcat: releasePendingMessages session=null）
                            if (gvMsgDelegate == null) {
                                gvMsgDelegate = new WebExtension.MessageDelegate() {
                                    @Override
                                    public void onConnect(WebExtension.Port port) {
                                        android.util.Log.d("LuoxueTV", "initBridge: content script CONNECTED");
                                        gvPort = port;
                                        gvBridgeReady = true;
                                    }

                                    @Override
                                    public GeckoResult<Object> onMessage(
                                            String nativeApp, Object message, WebExtension.MessageSender sender) {
                                        // 页面每 250ms 轮询：有按键指令就应答下发（不依赖 onConnect 的备用通道）
                                        org.json.JSONObject reply = pendingCmd;
                                        pendingCmd = null;
                                        if (reply != null) {
                                            android.util.Log.d("LUOXUE", "onMessage poll DELIVERED type=" + reply.optString("type"));
                                        }
                                        try {
                                            return GeckoResult.fromValue(reply);
                                        } catch (Exception e) { return GeckoResult.fromValue(null); }
                                    }
                                };
                            }
                            ext.setMessageDelegate(gvMsgDelegate, "gv_bridge");
                            // 若已有活跃 session（用户已进站），补设 session 级代理
                            GeckoSession s = activeSession;
                            if (s != null) {
                                try {
                                    s.getWebExtensionController()
                                            .setMessageDelegate(ext, gvMsgDelegate, "gv_bridge");
                                    android.util.Log.d("LuoxueTV", "initBridge: session-level delegate set for active session");
                                } catch (Exception e) {
                                    android.util.Log.e("LuoxueTV", "initBridge: session delegate fail " + e.getMessage());
                                }
                            }
                        }
                    }, new GeckoResult.Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable t) {
                            android.util.Log.e("LuoxueTV", "initBridge: INSTALL FAIL " + t.getMessage());
                        }
                    });
        } catch (Exception e) {
            android.util.Log.e("LuoxueTV", "initBridge: EXCEPTION " + e.getMessage());
        }
    }

    private GeckoView geckoView;

    /** 视口高度（用于触边判定） */
    private int getViewHeight() {
        if (geckoView != null && geckoView.getHeight() > 0) return geckoView.getHeight();
        return getResources().getDisplayMetrics().heightPixels;
    }
    private int getViewWidth() {
        if (geckoView != null && geckoView.getWidth() > 0) return geckoView.getWidth();
        return getResources().getDisplayMetrics().widthPixels;
    }
    private GeckoSession session;
    private PanZoomController panZoom;
    private FrameLayout fullscreenContainer;
    private ProgressBar progress;
    private FrameLayout loadingLayer;

    private FrameLayout cursorLayer;
    private View cursorBox;
    private int cursorX = -1, cursorY = -1;
    private boolean cursorMode = true;
    private float density = 1f;

    private boolean dragActive = false;
    private boolean fullScreenActive = false;
    private boolean canGoBack = false;
    private long dragDownTime = 0;
    private long pageStartTime = 0;
    private android.os.Handler dragHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private GradientDrawable cursorDrawable;
    private final Runnable dragStartRunnable = new Runnable() {
        @Override
        public void run() {
            dragActive = true;
            if (cursorX >= 0 && cursorY >= 0) nativeDragStart(cursorX, cursorY);
            if (cursorBox != null) {
                cursorBox.setScaleX(1.6f);
                cursorBox.setScaleY(1.6f);
                if (cursorDrawable != null) cursorDrawable.setColor(Color.rgb(255, 152, 0));
            }
        }
    };

    /** GeckoRuntime 单例（v1.32 内存优化：关调试端口/扩展进程/站点隔离/MSAA；
     *  v1.33: 内容进程并入主进程——省一个进程的页表/线程/缓存开销，
     *  电视 2GB 内存场景下实测播放内存 1G+ → 700MB+ → 进一步下降 */
    private static synchronized GeckoRuntime getRuntime(Context ctx) {
        if (geckoRuntime == null) {
            geckoRuntime = GeckoRuntime.create(ctx, new GeckoRuntimeSettings.Builder()
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    .remoteDebuggingEnabled(false)      // 关调试端口，省内存
                    .extensionsProcessEnabled(true)     // v1.38h 实验：扩展进程开启（false 时 content script 从未连接，gvPort=false 实锤）
                    .fissionEnabled(false)              // 关站点隔离，单内容进程
                    .glMsaaLevel(0)                     // 关 MSAA，降 GPU 负载（视频/滚动更顺）
                    .isolatedProcessEnabled(false)      // 内容进程并入主进程，省进程级开销
                    .fontInflation(false)               // 关字体膨胀，防电视字体放大占内存
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .antiTracking(ContentBlocking.AntiTracking.DEFAULT) // v1.39 拦广告/分析/社交追踪器（Firefox 同款 Disconnect 列表）
                            .bounceTrackingProtectionMode(ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED_STANDBY) // 防广告跳转追踪
                            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT) // 防钓鱼/恶意软件
                            .build())
                    .build());
        }
        return geckoRuntime;
    }

    /** v1.39 广告域名黑名单（保守匹配，防误拦正常站）：主跳转/iframe 广告域名 */
    private static final String[] AD_HOST_BLACKLIST = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "adnxs.com", "adsafeprotected.com",
            "scorecardresearch.com", "taboola.com", "outbrain.com",
            "moatads.com", "criteo.com", "lijit.com", "rubiconproject.com",
            "yk392.com"   // v1.39.3 厂长资源广告（用户实测域名，含 www.yk392.com）
    };

    private boolean isAdUrl(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            for (String a : AD_HOST_BLACKLIST) {
                if (h.equals(a) || h.endsWith("." + a)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 预热入口：MainActivity 调用，提前加载内核（进程内仅一个 runtime） */
    public static void warm(Context ctx) {
        try {
            GeckoRuntime rt = getRuntime(ctx.getApplicationContext());
            initBridge(rt);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        density = getResources().getDisplayMetrics().density;

        String url = getIntent().getStringExtra("url");
        final String title = getIntent().getStringExtra("title");
        if (url == null) url = "about:blank";

        android.app.UiModeManager uim =
                (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        boolean tvMode = uim != null
                && uim.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        cursorMode = tvMode;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ===== GeckoView 内核初始化 =====
        GeckoRuntime rt = getRuntime(this);
        GeckoSessionSettings sst = new GeckoSessionSettings.Builder()
                .userAgentOverride(DESKTOP_UA)          // 桌面 UA，与电脑端一致
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP) // 桌面宽度视口
                .useTrackingProtection(false)            // 关跟踪保护，省内存
                .build();
        session = new GeckoSession(sst);

        // v1.38j：session 级消息代理——GeckoView 125+ 的 content script connectNative
        // 必须通过 WebExtension.SessionController.setMessageDelegate 关联到具体 session，
        // 仅扩展级 delegate 时消息被 release（实测 logcat: releasePendingMessages session=null）
        activeSession = session;
        if (gvExtension != null && gvMsgDelegate != null) {
            try {
                session.getWebExtensionController()
                        .setMessageDelegate(gvExtension, gvMsgDelegate, "gv_bridge");
            } catch (Exception e) {
                android.util.Log.w("LUOXUE", "session bridge set fail: " + e);
            }
        }

        session.setProgressDelegate(new ProgressDelegate() {
            @Override
            public void onProgressChange(GeckoSession s, int newProgress) {
                if (progress != null) {
                    progress.setProgress(newProgress);
                    progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
                if (newProgress >= 100) hideLoading();
            }

            @Override
            public void onPageStart(GeckoSession s, String u) {
                if (progress != null) progress.setVisibility(View.VISIBLE);
                pageStartTime = android.os.SystemClock.uptimeMillis();
                if (loadingLayer != null) {
                    loadingLayer.setAlpha(1f);
                    loadingLayer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                // 加载过渡页最短显示 800ms，避免一闪而过
                long elapsed = android.os.SystemClock.uptimeMillis() - pageStartTime;
                if (elapsed < MIN_LOADING_MS) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override public void run() { hideLoading(); }
                            }, MIN_LOADING_MS - elapsed);
                } else {
                    hideLoading();
                }
                if (title != null) setTitle(title);
                cursorLayer.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
                initCursor();
            }
        });

        session.setContentDelegate(new ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                fullScreenActive = fullScreen;
                // 视频全屏：隐藏光标层避免遮挡
                if (cursorLayer != null) {
                    cursorLayer.setVisibility(fullScreen ? View.GONE
                            : (cursorMode ? View.VISIBLE : View.GONE));
                }
                if (fullScreen) hideSystemUi();
            }
        });

        // 导航委托：拦截广告域名跳转（v1.39）+ 跟踪返回能力
        session.setNavigationDelegate(new NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean cgb) {
                canGoBack = cgb;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession s, NavigationDelegate.LoadRequest request) {
                String u = request.uri;
                if (u != null && isAdUrl(u)) {
                    android.util.Log.d("LUOXUE", "adblock: deny " + u);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
        });

        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.BLACK);
        geckoView.setSession(session);
        session.open(rt);
        panZoom = session.getPanZoomController();

        // 加载中过场过渡层
        loadingLayer = new FrameLayout(this);
        loadingLayer.setBackgroundColor(Color.rgb(18, 18, 24));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        spinner.getIndeterminateDrawable().setColorFilter(
                Color.rgb(124, 77, 255), android.graphics.PorterDuff.Mode.SRC_IN);
        box.addView(spinner, lpWrap());

        TextView tip = new TextView(this);
        tip.setText((title != null ? title : "站点") + " · 正在进入…");
        tip.setTextColor(Color.rgb(200, 200, 215));
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tvMode ? 20 : 15);
        tip.setGravity(Gravity.CENTER);
        box.addView(tip, lpWrap());

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        loadingLayer.addView(box, boxLp);

        // 诊断行：内核状态
        try {
            TextView diag = new TextView(this);
            String osVer = "";
            try { osVer = android.os.Build.VERSION.RELEASE; } catch (Exception e) {}
            String gv = "Gecko";
            try { gv = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION; } catch (Exception e) {}
            diag.setText("渲染内核: " + gv + " · Android " + osVer);
            diag.setTextColor(Color.rgb(130, 130, 155));
            diag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            diag.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams diagLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            diagLp.gravity = Gravity.BOTTOM;
            diagLp.bottomMargin = dp(16);
            loadingLayer.addView(diag, diagLp);
        } catch (Exception e) {}

        // 虚拟光标层（纯视觉指示；交互全部走内核原生输入）
        cursorLayer = new FrameLayout(this);
        cursorLayer.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        cursorBox = new View(this);
        cursorDrawable = new GradientDrawable();
        cursorDrawable.setShape(GradientDrawable.OVAL);
        cursorDrawable.setColor(Color.rgb(255, 60, 60));
        cursorDrawable.setStroke(dp(2), Color.WHITE);
        cursorBox.setBackground(cursorDrawable);
        cursorLayer.addView(cursorBox, new FrameLayout.LayoutParams(
                dp(CURSOR_SIZE), dp(CURSOR_SIZE)));

        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(cursorLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(loadingLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(124, 77, 255)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        root.addView(progress, plp);

        setContentView(root);
        session.loadUri(url);
        geckoView.requestFocus();
    }

    /** 顶层按键分发：方向键=滚动+光标移动，确认键=点击/长按拖动
     *  v1.38f 入口日志：定位按键是否到达本方法（v1.38e 实测按键发出但无任何输入日志） */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        android.util.Log.d("LUOXUE", "dispatch kc=" + kc + " action=" + event.getAction()
                + " cursorMode=" + cursorMode);
        if (cursorMode) {
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(-CURSOR_STEP, 0); nativeDragMove(cursorX, cursorY); }
                        else if (cursorX > EDGE_MARGIN) { moveCursor(-CURSOR_STEP, 0); nativeMouseHover(cursorX, cursorY); }
                        else nativeTouchScroll(TOUCH_SCROLL_DIST, 0);   // v1.38n 触边=触摸滑动（手指右滑→内容右移→看左边）
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(CURSOR_STEP, 0); nativeDragMove(cursorX, cursorY); }
                        else if (cursorX < getViewWidth() - EDGE_MARGIN) { moveCursor(CURSOR_STEP, 0); nativeMouseHover(cursorX, cursorY); }
                        else nativeTouchScroll(-TOUCH_SCROLL_DIST, 0);  // 手指左滑→内容左移→看右边
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(0, -CURSOR_STEP); nativeDragMove(cursorX, cursorY); }
                        else if (cursorY > EDGE_MARGIN) { moveCursor(0, -CURSOR_STEP); nativeMouseHover(cursorX, cursorY); }
                        else nativeTouchScroll(0, TOUCH_SCROLL_DIST);   // 手指下滑→内容下移→看上面
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(0, CURSOR_STEP); nativeDragMove(cursorX, cursorY); }
                        else if (cursorY < getViewHeight() - EDGE_MARGIN) { moveCursor(0, CURSOR_STEP); nativeMouseHover(cursorX, cursorY); }
                        else nativeTouchScroll(0, -TOUCH_SCROLL_DIST);  // 手指上滑→内容上移→看下面
                    }
                    return true;
                default:
                    break;
            }
        }
        if (kc == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            cursorMode = !cursorMode;
            cursorLayer.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
            if (cursorMode) initCursor();
            android.util.Log.w("LUOXUE", "MENU toggled cursorMode=" + cursorMode);
            if (!cursorMode) {
                android.widget.Toast.makeText(this, "光标模式：关（按菜单键恢复）",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_CENTER
                || kc == KeyEvent.KEYCODE_ENTER
                || kc == KeyEvent.KEYCODE_NUMPAD_ENTER
                || kc == KeyEvent.KEYCODE_SPACE) {
            if (cursorMode) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // 按住 500ms 进入拖动模式（滑动验证码滑块等）
                    dragHandler.removeCallbacks(dragStartRunnable);
                    dragHandler.postDelayed(dragStartRunnable, DRAG_HOLD_MS);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    dragHandler.removeCallbacks(dragStartRunnable);
                    if (dragActive) {
                        dragActive = false;
                        if (cursorBox != null) {
                            cursorBox.setScaleX(1f);
                            cursorBox.setScaleY(1f);
                            if (cursorDrawable != null) cursorDrawable.setColor(Color.rgb(255, 60, 60));
                        }
                        nativeDragEnd(cursorX, cursorY);
                    } else {
                        // v1.38b 单击立即执行左键（零延迟，杜绝误判）；350ms 内再按=双击，追加鼠标右键
                        long now = android.os.SystemClock.uptimeMillis();
                        boolean isDoubleClick = (now - lastClickUpTime < DOUBLE_CLICK_MS);
                        lastClickUpTime = now;
                        // 左键：原生鼠标序列 + 触摸序列（v1.36d/1.38 串行）
                        nativeClick(cursorX, cursorY);
                        // JS DOM 级点击兜底（v1.37）
                        dragHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                clickJsAt(cursorX, cursorY);
                            }
                        }, 400);
                        if (isDoubleClick) {
                            // 双击=追加鼠标右键（对左键点击无反应的站点，右键 contextmenu 生效）
                            rightClickAt(cursorX, cursorY);
                        }
                    }
                }
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ============ v1.36 WebExtension 输入桥（替代 v1.35 javascript: URL 方案） ============
    // GeckoView 158 不执行 javascript: URL（v1.35 实测红点标记未出现）。
    // 所有交互改走 WebExtension Port：postMessage 给 content script，由扩展 JS 操作 DOM。
    // 坐标直接传 View 物理像素，content.js 内 ÷devicePixelRatio 换算 CSS 像素。

    /** setAxisValue 是隐藏 API（SDK stub 无此方法），用反射设置滚轮滚动量 */
    private static void setAxisReflect(MotionEvent e, int axis, float value) {
        try {
            MotionEvent.class.getMethod("setAxisValue", int.class, float.class)
                    .invoke(e, axis, value);
        } catch (Throwable ignored) {}
    }

    /** v1.36c 原生滚动：PanZoomController.scrollBy 直驱内核
     *  v1.38e：scrollBy 在内部滚动容器站失效（白嫖者联盟/青空次元，logcat 证实调用但页面不动）
     *          → 换鼠标滚轮主通道（ACTION_SCROLL，PC 站标准滚动，浏览器在光标下自动选滚动容器） */
    private void nativeScroll(int dx, int dy) {
        if (panZoom == null) {
            android.util.Log.w("LUOXUE", "nativeScroll skipped: panZoom null");
            return;
        }
        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL,
                    cursorX, cursorY, 0f, 0f, 0, 1f, 1f, 0, 0);
            try {
                e.setSource(android.view.InputDevice.SOURCE_MOUSE);
                if (dy != 0) setAxisReflect(e, MotionEvent.AXIS_VSCROLL, (float) dy);
                if (dx != 0) setAxisReflect(e, MotionEvent.AXIS_HSCROLL, (float) dx);
                panZoom.onMouseEvent(e);
                android.util.Log.d("LUOXUE", "nativeScroll wheel v=" + dy + " h=" + dx
                        + " at " + cursorX + "," + cursorY);
            } catch (Throwable t) {
                android.util.Log.w("LUOXUE", "nativeScroll wheel fail: " + t);
            } finally {
                e.recycle();
            }
        } catch (Exception ex) {
            android.util.Log.w("LUOXUE", "nativeScroll error: " + ex);
        }
    }

    /** v1.36d 原生点击：PanZoomController 注入触摸+鼠标序列（内核手势入口）
     *  v1.38c：鼠标 DOWN/UP 同步发送（间隔 16ms=1帧），消除按钮残留窗口
     *  v1.38d：删除触摸阶段——触摸 DOWN 延迟注入会与方向键滚动冲突
     *          （触摸手势卡"按住"时 scrollBy 被忽略 = 滚动回归根因）。
     *          点击纯鼠标序列（v1.34 验证鼠标注入可合成 click），JS 兜底走 bridge */
    private void nativeClick(int x, int y) {
        if (panZoom == null) {
            android.util.Log.w("LUOXUE", "nativeClick skipped: panZoom null");
            return;
        }
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.util.Log.d("LUOXUE", "nativeClick mouse at " + x + "," + y);
            injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, now);
            injectMouseEvent(MotionEvent.ACTION_DOWN, x, y, 1, now);
            injectMouseEvent(MotionEvent.ACTION_UP, x, y, 0, now + 16);
        } catch (Exception ignored) {}
    }

    /** v1.38 鼠标右键点击（双击确认键触发）：原生右键序列 + JS contextmenu 兜底
     *  buttons=2 为 BUTTON_SECONDARY，内核将合成 contextmenu 事件
     *  v1.38c：DOWN/UP 同步发送（同左键，消除按钮残留窗口） */
    private void rightClickAt(int x, int y) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, now);
            injectMouseEvent(MotionEvent.ACTION_DOWN, x, y, 2, now);
            injectMouseEvent(MotionEvent.ACTION_UP, x, y, 0, now + 16);
        } catch (Exception ignored) {}
        // JS contextmenu 兜底（v1.38）：content script 触发 mousedown/mouseup/contextmenu(button=2)
        // v1.38g：evaluateJavascript 直连为主，扩展通道双保险
        String js = "(function(px,py){var d=window.devicePixelRatio||1,x=px/d,y=py/d;"
                + "var el=document.elementFromPoint(x,y);if(!el)return;var t=el;"
                + "for(var i=0;i<8&&t;i++,t=t.parentElement){"
                + "if(t.tagName==='A'||t.tagName==='BUTTON'||t.tagName==='INPUT'||t.tagName==='SELECT'"
                + "||(t.getAttribute&&t.getAttribute('oncontextmenu')))break;}"
                + "if(!t)t=el;var r=t.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "function f(n,o){try{t.dispatchEvent(new MouseEvent(n,o));}catch(e){}}"
                + "f('mousedown',{bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window,button:2,buttons:2});"
                + "f('mouseup',{bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window,button:2,buttons:0});"
                + "f('contextmenu',{bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window,button:2,buttons:0});"
                + "})(" + x + "," + y + ");";
        jsEval(js);
        dragHandler.postDelayed(new Runnable() {
            @Override public void run() {
                postBridge("rightClick", x, y, 0, 0, 3);
            }
        }, 400);
    }

    /** 鼠标事件注入（PanZoomController.onMouseEvent 官方鼠标入口）
     *  buttons: 0=无按键, 1=BUTTON_PRIMARY（DOWN 时按住的键，UP 时必须为 0 表示松开） */
    private void injectMouseEvent(int action, float x, float y, int buttons, long eventTime) {
        if (panZoom == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (eventTime <= 0) eventTime = now;
        MotionEvent e = MotionEvent.obtain(now, eventTime, action, x, y, 0f, 0f, 0, 1f, 1f, 0, 0);
        try {
            e.setSource(android.view.InputDevice.SOURCE_MOUSE);
        } catch (Throwable t1) {
            try {
                MotionEvent.class.getMethod("setSource", int.class)
                        .invoke(e, android.view.InputDevice.SOURCE_MOUSE);
            } catch (Throwable t2) {}
        }
        try {
            MotionEvent.class.getMethod("setButtonState", int.class).invoke(e, buttons);
        } catch (Throwable t3) {}
        panZoom.onMouseEvent(e);
        e.recycle();
    }

    /** 鼠标悬停注入：焦点移动时跟随，触发 hover 菜单/高亮 */
    private void nativeMouseHover(int x, int y) {
        try {
            injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0);
        } catch (Exception ignored) {}
    }

    /** 鼠标点击序列：HOVER 定位 → DOWN(btn=1) → 延迟 UP(btn=0 松开)
     *  真实点击节奏：down 后 120ms 再 up，buttonState 归零让内核合成 click */
    private void nativeMouseClick(final int x, final int y) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, now);
            injectMouseEvent(MotionEvent.ACTION_DOWN, x, y, 1, now);
            final long upTime = now + 120;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        injectMouseEvent(MotionEvent.ACTION_UP, x, y, 0, upTime);
                    } catch (Exception ignored) {}
                }
            }, 120);
        } catch (Exception ignored) {}
    }

    /** v1.36d 原生拖动（验证码滑块/横向滚动）：长按确认键起 DOWN，方向键发 MOVE，松开发 UP */
    private void nativeDragStart(int x, int y) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            try {
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            } catch (Throwable t1) {
                try {
                    MotionEvent.class.getMethod("setSource", int.class)
                            .invoke(down, android.view.InputDevice.SOURCE_TOUCHSCREEN);
                } catch (Throwable t2) {}
            }
            panZoom.onTouchEvent(down);
            down.recycle();
        } catch (Exception ignored) {}
    }

    private void nativeDragMove(int x, int y) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent move = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y, 0);
            try {
                move.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            } catch (Throwable t1) {
                try {
                    MotionEvent.class.getMethod("setSource", int.class)
                            .invoke(move, android.view.InputDevice.SOURCE_TOUCHSCREEN);
                } catch (Throwable t2) {}
            }
            panZoom.onTouchEvent(move);
            move.recycle();
        } catch (Exception ignored) {}
    }

    private void nativeDragEnd(int x, int y) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
            try {
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            } catch (Throwable t1) {
                try {
                    MotionEvent.class.getMethod("setSource", int.class)
                            .invoke(up, android.view.InputDevice.SOURCE_TOUCHSCREEN);
                } catch (Throwable t2) {}
            }
            panZoom.onTouchEvent(up);
            up.recycle();
        } catch (Exception ignored) {}
    }

    /** v1.38n 触摸滑动滚动（v1.36 验证过的电视最有效滚动手势）：
     *  方向键触边 → 注入 touch DOWN→6 步 MOVE→UP 滑动序列。
     *  触摸滑动由浏览器命中测试自动选择滚动容器（内部容器也能滚），
     *  与 scrollBy（仅文档级）/鼠标滚轮（电视端 GeckoView 不响应 ACTION_SCROLL）不同。
     *  dx/dy 为手指滑动位移：手指向上滑 dy<0 → 内容上移 → 露出下面内容。 */
    private void nativeTouchScroll(float dx, float dy) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            float x0 = cursorX, y0 = cursorY;
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x0, y0, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            panZoom.onTouchEvent(down);
            // 分 6 步滑动（模拟真实手指拖动，避免单步大跳不被识别为手势）
            for (int i = 1; i <= 6; i++) {
                MotionEvent move = MotionEvent.obtain(now, now + i * 16L,
                        MotionEvent.ACTION_MOVE, x0 + dx * i / 6f, y0 + dy * i / 6f, 0);
                move.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                panZoom.onTouchEvent(move);
                move.recycle();
            }
            MotionEvent up = MotionEvent.obtain(now, now + 6 * 16L + 60,
                    MotionEvent.ACTION_UP, x0 + dx, y0 + dy, 0);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            panZoom.onTouchEvent(up);
            up.recycle();
            down.recycle();
            android.util.Log.d("LUOXUE", "touchScroll dx=" + dx + " dy=" + dy + " at " + x0 + "," + y0);
        } catch (Exception e) {
            android.util.Log.w("LUOXUE", "touchScroll err " + e);
        }
    }

    private static final String BRIDGE_NAME = "gv_bridge";
    /** 向页面 content script 发送操作消息（port 未就绪时 300ms 内重试 5 次） */
    private void postBridge(final String type, final int x, final int y,
                            final int dx, final int dy, final int retry) {
        android.util.Log.d("LUOXUE", "postBridge type=" + type + " x=" + x + " y=" + y
                + " gvPort=" + (gvPort != null) + " retry=" + retry);
        // 轮询通道：立即入队，页面下次 poll 时取走（主通道）
        try {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("type", type);
            m.put("x", x);
            m.put("y", y);
            m.put("dx", dx);
            m.put("dy", dy);
            pendingCmd = m;
        } catch (Exception ignored) {}
        // Port 通道：若 onConnect 已建立，也直接发（双保险）
        if (gvPort == null) {
            if (retry > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                            @Override public void run() {
                                postBridge(type, x, y, dx, dy, retry - 1);
                            }
                        }, 60);
            }
            return;
        }
        try {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("type", type);
            m.put("x", x);
            m.put("y", y);
            m.put("dx", dx);
            m.put("dy", dy);
            gvPort.postMessage(m);
        } catch (Exception ignored) {}
    }

    /** v1.38g JS 兜底主通道：GeckoSession.evaluateJavascript 直连页面（GeckoView 官方 JS 求值 API）
     *  背景：extensionsProcessEnabled(false) 下 WebExtension content script 从未连接
     *  （v1.38d/e/f logcat 实锤 gvPort=false，JS 兜底全程静默失效），改走内核直连，不依赖扩展进程。
     *  v1.38h：GeckoView 158 实测无 evaluateJavascript API（编译报错，新版已移除），
     *          唯一 JS 通道仍是 WebExtension → 恢复 postBridge 单通道，扩展进程改开（v1.38h 实验）。 */
    private void jsEval(String js) {
        // GeckoView 158 无 evaluateJavascript，此方法保留为空实现（占位），JS 兜底全走 postBridge
        android.util.Log.d("LUOXUE", "jsEval unavailable in GeckoView 158, use postBridge");
    }

    /** 点击 JS：elementFromPoint + pointer/mouse/touch 全套事件 + A 链接强制当前页跳转（MacCms _blank 兜底）
     *  坐标：Java 传 View 物理像素，页面内 ÷devicePixelRatio 转 CSS 像素 */
    private void clickJsAt(int x, int y) {
        String js = "(function(px,py){var d=window.devicePixelRatio||1,x=px/d,y=py/d;"
                + "var el=document.elementFromPoint(x,y);if(!el)return;var t=el;"
                + "for(var i=0;i<8&&t;i++,t=t.parentElement){"
                + "if(t.tagName==='A'||t.tagName==='BUTTON'||t.tagName==='INPUT'||t.tagName==='SELECT'"
                + "||(t.getAttribute&&(t.getAttribute('role')==='button'||t.getAttribute('onclick'))))break;}"
                + "if(!t)t=el;var r=t.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "function f(n,o){try{t.dispatchEvent(new MouseEvent(n,o));}catch(e){}}"
                + "var o={bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window,button:0,buttons:1};"
                + "f('pointerdown',o);f('mousedown',o);f('pointerup',o);f('mouseup',o);f('click',o);"
                + "if(t.tagName==='A'&&t.href){try{"
                + "if(t.target&&t.target!=='_self'){try{t.target='_self';}catch(e){}}"
                + "if(location.href.split('#')[0]!==t.href.split('#')[0])location.href=t.href;}catch(e){}}"
                + "})(" + x + "," + y + ");";
        jsEval(js);
        postBridge("click", x, y, 0, 0, 3); // 扩展通道双保险
    }

    /** 滚动 JS：找光标下可滚动祖先 scrollBy；无则滚主文档 */
    private void scrollJsAt(int x, int y, int dx, int dy) {
        String js = "(function(px,py,dx,dy){var d=window.devicePixelRatio||1,x=px/d,y=py/d;"
                + "var el=document.elementFromPoint(x,y);if(!el)return;var t=el,s=null;"
                + "for(var i=0;i<8&&t;i++,t=t.parentElement){"
                + "if(t.scrollHeight>t.clientHeight+1||t.scrollWidth>t.clientWidth+1){s=t;break;}}"
                + "if(s){s.scrollBy(dx,dy);}else{window.scrollBy(dx,dy);}"
                + "})(" + x + "," + y + "," + dx + "," + dy + ");";
        jsEval(js);
        postBridge("scroll", x, y, dx, dy, 3);
    }

    /** 悬停：模拟 mouseover/mouseenter */
    private void hoverJsAt(int x, int y) {
        postBridge("hover", x, y, 0, 0, 5);
    }

    /** 拖动开始（touchstart） */
    private void dragStartJs(int x, int y) {
        postBridge("dragStart", x, y, 0, 0, 5);
    }

    /** 拖动移动（touchmove） */
    private void dragMoveJs(int x, int y) {
        postBridge("dragMove", x, y, 0, 0, 2);
    }

    /** 拖动结束（touchend） */
    private void dragEndJs(int x, int y) {
        postBridge("dragEnd", x, y, 0, 0, 2);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullScreenActive) {
                session.exitFullScreen();
                return true;
            }
            if (canGoBack) {
                session.goBack();
                return true;
            }
            finish();
            overridePendingTransition(0, 0);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 移动虚拟光标（限制在视口内） */
    private void moveCursor(int dx, int dy) {
        int w = geckoView.getWidth();
        int h = geckoView.getHeight();
        if (w <= 0 || h <= 0) return;
        if (cursorX < 0 || cursorY < 0) {
            initCursor();
            return;
        }
        int nx = cursorX + dx;
        int ny = cursorY + dy;
        nx = Math.max(0, Math.min(nx, w));
        ny = Math.max(0, Math.min(ny, h));
        setCursor(nx, ny);
    }

    private void initCursor() {
        int w = geckoView.getWidth();
        int h = geckoView.getHeight();
        if (w <= 0 || h <= 0) return;
        setCursor(w / 2, h / 2);
    }

    private void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
        if (cursorBox == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cursorBox.getLayoutParams();
        lp.leftMargin = x - dp(CURSOR_SIZE) / 2;
        lp.topMargin = y - dp(CURSOR_SIZE) / 2;
        cursorBox.setLayoutParams(lp);
    }

    private void hideLoading() {
        if (loadingLayer == null || loadingLayer.getVisibility() != View.VISIBLE) return;
        loadingLayer.animate().alpha(0f).setDuration(350)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        loadingLayer.setVisibility(View.GONE);
                        loadingLayer.setAlpha(1f);
                    }
                }).start();
    }

    @Override
    protected void onPause() {
        // GeckoSession 不随 onPause 关闭（关闭需重新 open 且丢失页面状态）
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (session != null && !session.isOpen()) {
            session.open(getRuntime(this));
        }
        geckoView.requestFocus();
    }

    @Override
    protected void onDestroy() {
        dragHandler.removeCallbacks(dragStartRunnable);
        if (activeSession == session) activeSession = null;
        if (session != null) {
            try { session.close(); } catch (Exception e) {}
            session = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }
}
