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

    // 桌面 Edge UA：与电脑端完全一致
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/135.0.7049.42 Safari/537.36 Edg/135.0.0.0";

    private static final int CURSOR_STEP = 48;
    private static final int SCROLL_STEP = 64;
    private static final int SCROLL_STEP_DRAG = 20;
    private static final int DRAG_HOLD_MS = 500;
    private static final int CURSOR_SIZE = 14;
    /** 焦点触边滚动：光标距视口边缘小于该值后，方向键才触发页面滚动 */
    private static final int EDGE_MARGIN = 100;
    private static final long MIN_LOADING_MS = 800; // 加载过渡页最短显示时长

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
                            ext.setMessageDelegate(new WebExtension.MessageDelegate() {
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
                                    try {
                                        return GeckoResult.fromValue(reply);
                                    } catch (Exception e) { return GeckoResult.fromValue(null); }
                                }
                            }, "gv_bridge");
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
                    .extensionsProcessEnabled(false)    // 不启扩展进程，省一个进程的内存
                    .fissionEnabled(false)              // 关站点隔离，单内容进程
                    .glMsaaLevel(0)                     // 关 MSAA，降 GPU 负载（视频/滚动更顺）
                    .isolatedProcessEnabled(false)      // 内容进程并入主进程，省进程级开销
                    .fontInflation(false)               // 关字体膨胀，防电视字体放大占内存
                    .build());
        }
        return geckoRuntime;
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

        // 导航委托：仅跟踪返回能力；页面跳转默认全部放行
        session.setNavigationDelegate(new NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean cgb) {
                canGoBack = cgb;
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

    /** 顶层按键分发：方向键=滚动+光标移动，确认键=点击/长按拖动 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (cursorMode) {
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(-CURSOR_STEP, 0); nativeDragMove(cursorX, cursorY); }
                        else if (cursorX > EDGE_MARGIN) { moveCursor(-CURSOR_STEP, 0); nativeMouseHover(cursorX, cursorY); }
                        else nativeScroll(-SCROLL_STEP, 0);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(CURSOR_STEP, 0); nativeDragMove(cursorX, cursorY); }
                        else if (cursorX < getViewHeight() - EDGE_MARGIN) { moveCursor(CURSOR_STEP, 0); nativeMouseHover(cursorX, cursorY); }
                        else nativeScroll(SCROLL_STEP, 0);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(0, -CURSOR_STEP); nativeDragMove(cursorX, cursorY); }
                        else if (cursorY > EDGE_MARGIN) { moveCursor(0, -CURSOR_STEP); nativeMouseHover(cursorX, cursorY); }
                        else nativeScroll(0, -SCROLL_STEP);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (dragActive) { moveCursor(0, CURSOR_STEP); nativeDragMove(cursorX, cursorY); }
                        else if (cursorY < getViewHeight() - EDGE_MARGIN) { moveCursor(0, CURSOR_STEP); nativeMouseHover(cursorX, cursorY); }
                        else nativeScroll(0, SCROLL_STEP);
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
                        // 短按=点击：原生触摸注入（内核手势入口，v1.36d）
                        nativeClick(cursorX, cursorY);
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

    /** v1.36c 原生滚动：PanZoomController.scrollBy 直驱内核，不依赖任何 JS/消息通道 */
    private void nativeScroll(int dx, int dy) {
        if (panZoom == null) return;
        try {
            if (dy != 0) {
                panZoom.scrollBy(ScreenLength.fromPixels(0), ScreenLength.fromPixels(dy));
            }
            if (dx != 0) {
                panZoom.scrollBy(ScreenLength.fromPixels(dx), ScreenLength.fromPixels(0));
            }
        } catch (Exception ignored) {}
    }

    /** v1.36d 原生点击：PanZoomController.onTouchEvent 注入完整触摸序列（内核手势入口） */
    private void nativeClick(int x, int y) {
        if (panZoom == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
            MotionEvent[] evs = { down, up };
            for (MotionEvent e : evs) {
                try {
                    e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                } catch (Throwable t1) {
                    try {
                        MotionEvent.class.getMethod("setSource", int.class)
                                .invoke(e, android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    } catch (Throwable t2) {}
                }
            }
            panZoom.onTouchEvent(down);
            panZoom.onTouchEvent(up);
            down.recycle();
            up.recycle();
            // 同时注入鼠标点击序列：兼容吃鼠标事件（hover 菜单/鼠标 UI）的站点
            nativeMouseClick(x, y);
        } catch (Exception ignored) {}
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

    private static final String BRIDGE_NAME = "gv_bridge";
    /** 向页面 content script 发送操作消息（port 未就绪时 300ms 内重试 5 次） */
    private void postBridge(final String type, final int x, final int y,
                            final int dx, final int dy, final int retry) {
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

    /** 点击：扩展内 elementFromPoint + 触发 pointer/mouse/touch 全套事件 + <a> 跳转兜底 */
    private void clickJsAt(int x, int y) {
        postBridge("click", x, y, 0, 0, 5);
    }

    /** 滚动：扩展找光标下可滚动祖先 scrollBy；无则滚主文档 */
    private void scrollJsAt(int x, int y, int dx, int dy) {
        postBridge("scroll", x, y, dx, dy, 5);
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
