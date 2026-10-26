package com.luoxue.tv.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 全屏网页页 - 无地址栏、无状态栏、无任何浏览器 UI。
 * 从导航页点入的站点在此全屏打开，页面内跳转、视频全屏、遥控器确认键均正常。
 */
public class WebActivity extends Activity {

    // 桌面 Chrome UA：广告系统对桌面 UA 不投广告（电视端实测有效）；
    // 配合窄视口（setUseWideViewPort(false)）让响应式站点按移动版 CSS 排版
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    // 广告域名黑名单：命中即拦截（shouldInterceptRequest 返回空响应）
    // 国外广告联盟 + 国内移动广告联盟（穿山甲/优量汇/百度联盟/汇川等）
    private static final String[] AD_HOSTS = {
            "yk392.com",
            "googlesyndication.com", "doubleclick.net", "googleadservices.com",
            "googletagservices.com", "adservice.google.com", "amazon-adsystem.com",
            "adnxs.com", "taboola.com", "outbrain.com", "adsafeprotected.com",
            "criteo.com", "criteo.net", "pubmatic.com", "openx.net", "rubiconproject.com",
            "pangle.io", "pangleglobal.com", "bytedance.com",
            "gdt.qq.com", "adsmind.com", "weishi.qq.com",
            "pos.baidu.com", "cpro.baidu.com", "bdstatic.com",
            "union.360.cn", "duomai.com", "cnzz.com", "hm.baidu.com"
    };

    // 广告容器 CSS 隐藏（页面加载完成后注入，跟随滚动吸附顶层的横幅/漂浮）
    private static final String JS_HIDE_ADS =
            "(function(){"
            + "var css='.ads,.footerad,.mac_ad,.right-ad,.adsbox,[class*=\"ad-container\"],"
            + "[id*=\"adsense\"],iframe[src*=\"ads\"],iframe[src*=\"ad_\"]{display:none!important}';"
            + "var st=document.createElement('style');"
            + "st.type='text/css';st.textContent=css;"
            + "(document.head||document.documentElement).appendChild(st);"
            + "})();";

    // 浏览器特征伪装：与桌面 UA 保持一致（Win32 桌面 Chrome 特征）
    private static final String JS_FAKE_BROWSER =
            "(function(){"
            + "try{"
            + "if(!window.chrome){window.chrome={};}"
            + "try{Object.defineProperty(window.chrome,'runtime',{get:function(){return {id:undefined};}});}catch(e){}"
            + "try{Object.defineProperty(navigator,'vendor',{get:function(){return 'Google Inc.';}});}catch(e){}"
            + "try{Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';}});}catch(e){}"
            + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;}});}catch(e){}"
            + "try{Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 16;}});}catch(e){}"
            + "try{"
            + "if(!navigator.userAgentData){"
            + "Object.defineProperty(navigator,'userAgentData',{get:function(){return {"
            + "brands:[{brand:'Chromium',version:'126'},{brand:'Not_A Brand',version:'24'},{brand:'Google Chrome',version:'126'}],"
            + "mobile:false,platform:'Windows'};}});"
            + "}"
            + "}catch(e){}"
            + "}catch(e){}"
            + "})();";

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;
    private ProgressBar progress;
    private FrameLayout loadingLayer;   // 加载中过场过渡层
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        String url = getIntent().getStringExtra("url");
        final String title = getIntent().getStringExtra("title");
        if (url == null) url = "about:blank";

        // 电视/盒子环境检测（过场文字放大，便于远距离观看）
        android.app.UiModeManager uim =
                (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        boolean tvMode = uim != null
                && uim.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;

        // 容器：进度条 + WebView
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 桌面 UA（广告系统不投广告）
        s.setUserAgentString(DESKTOP_UA);
        // 允许网页视频/音频自动播放
        s.setMediaPlaybackRequiresUserGesture(false);
        // 窄视口（设备宽度）+ 内容适配：桌面 UA 下响应式站点仍按移动版 CSS 排版
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 兼容站点内的 http 图片/资源
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // 页面内跳转全部在本 WebView 内完成，不跳出；广告域名请求拦截
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String ad : AD_HOSTS) {
                        if (host.equals(ad) || host.endsWith("." + ad)) {
                            return new WebResourceResponse("text/plain", "UTF-8",
                                    new java.io.ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 页面脚本执行前尽早伪装浏览器特征，让广告系统按真 Chrome 对待
                try {
                    view.evaluateJavascript(JS_FAKE_BROWSER, null);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 标题栏显示站点名（放系统任务栏，不影响画面）
                if (title != null) setTitle(title);
                // 兜底注入：特征伪装（覆盖 iframe/动态加载）+ 隐藏广告容器
                try {
                    view.evaluateJavascript(JS_FAKE_BROWSER + ";" + JS_HIDE_ADS, null);
                } catch (Exception ignored) {
                }
                hideLoading();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progress != null) {
                    progress.setProgress(newProgress);
                    progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
                if (newProgress >= 100) {
                    hideLoading();
                }
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                // 网页内 HTML5 视频进入全屏
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer = new FrameLayout(WebActivity.this);
                fullscreenContainer.setBackgroundColor(Color.BLACK);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                addContentView(fullscreenContainer, lp);
                fullscreenContainer.addView(customView, lp);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                if (fullscreenContainer != null) {
                    fullscreenContainer.removeView(customView);
                    fullscreenContainer.setVisibility(View.GONE);
                }
                customView = null;
                customViewCallback = null;
                fullscreenContainer = null;
                hideSystemUi();
            }
        });

        // 加载中过场过渡层：转圈 + 站点名，页面加载完成后淡出
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

        // 层叠顺序：webView(最底) → loadingLayer(加载过场) → progress(最顶)
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(loadingLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部细进度条
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(124, 77, 255)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        root.addView(progress, plp);

        setContentView(root);
        webView.loadUrl(url);
        webView.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                // 视频全屏中，先退出全屏
                webView.getWebChromeClient().onHideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            finish();
            overridePendingTransition(0, 0);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 隐藏加载中过渡层（淡出） */
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
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (webView != null) {
            webView.onResume();
            webView.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
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
