package com.luoxue.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 洛雪影视导航 - 首页（在线影视）
 * 全屏导航页，两列网格卡片；站点带渐变色可用性标签（绿=可访问/红=不可访问/灰=检测中）；
 * 长按卡片置顶/取消置顶（持久化保存）；
 * 启动时自动从远程数据源拉取最新站点清单（失败回退本地数据）；
 * WebView 预热 + 无转场动画；点击任意站点全屏进入。
 */
public class MainActivity extends Activity {

    private static class Site {
        String name;
        String url;
        String desc;
        int status = 0;      // 0 检测中  1 可用  2 不可用
        View dot;            // 渐变标签
        boolean pinned = false;

        Site(String name, String url, String desc) {
            this.name = name;
            this.url = url;
            this.desc = desc;
        }
    }

    // 远程数据源（本仓库 resources/resources.json，自主可控）
    private static final String REMOTE_SOURCE =
            "https://raw.githubusercontent.com/Goodver/luoxue-tv/main/resources/resources.json";

    private static final String PREFS = "luoxue_nav";
    private static final String KEY_PINNED = "pinned_urls";

    private static final int[] GRAY_GRAD = {Color.rgb(209, 213, 219), Color.rgb(55, 65, 81)};
    private static final int[] GREEN_GRAD = {Color.rgb(134, 239, 172), Color.rgb(21, 128, 61)};
    private static final int[] RED_GRAD = {Color.rgb(252, 165, 165), Color.rgb(153, 27, 27)};

    private final List<Site> sites = new ArrayList<>();
    private ExecutorService checker;
    private LinearLayout listRoot;
    private LinearLayout cardContainer;
    private int gridCols = 2;      // 自适应列数：手机2 / 小平板3 / 平板4 / 大平板5
    private float uiScale = 1.0f;  // 大屏放大系数（字号/间距/图标随屏放大）
    private boolean tvMode = false; // 电视/盒子环境（遥控器优化）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        // 禁用系统默认焦点高亮：电视多列网格下系统焦点框会错位画到非焦点卡片上
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getWindow().getDecorView().setDefaultFocusHighlightEnabled(false);
        }

        // 检测是否电视/盒子环境
        android.app.UiModeManager uim =
                (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        tvMode = uim != null
                && uim.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;

        // 按屏幕宽度(dp)计算自适应列数与放大系数
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        if (widthDp >= 1080) {
            gridCols = 5;
            uiScale = 1.3f;
        } else if (widthDp >= 840) {
            gridCols = 4;
            uiScale = 1.2f;
        } else if (widthDp >= 600) {
            gridCols = 3;
            uiScale = 1.1f;
        } else {
            gridCols = 2;
            uiScale = 1.0f;
        }
        // 电视：至少 5 列，整体 UI 进一步放大，便于远距离观看
        if (tvMode) {
            gridCols = Math.max(gridCols, 5);
            uiScale = Math.max(uiScale, 1.4f);
        }

        loadData();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 24));
        scroll.setFillViewport(true);

        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(dp(14), dp(22), dp(14), dp(30));
        scroll.addView(listRoot, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题行：标题 + 更新检测
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("洛雪影视导航");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * uiScale);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("检测更新");
        refresh.setTextColor(Color.rgb(124, 77, 255));
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * uiScale);
        refresh.setGravity(Gravity.CENTER_VERTICAL);
        refresh.setPadding(dp(14), dp(8), dp(14), dp(8));
        refresh.setClickable(true);
        refresh.setFocusable(true);
        final GradientDrawable refreshBg = rounded(dp(18), Color.rgb(31, 41, 55));
        refresh.setBackground(refreshBg);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateDataAndCheck();
            }
        });
        refresh.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                refreshBg.setColor(hasFocus ? Color.rgb(55, 65, 81) : Color.rgb(31, 41, 55));
            }
        });
        titleRow.addView(refresh, lpWrap());
        listRoot.addView(titleRow, lpWrap());

        // 可用性颜色提示（顶部标题下方，自动换行完整显示）
        TextView hint = new TextView(this);
        hint.setText("绿=可访问  红=不可访问 | 请勿轻易相信或点击站点广告 | 长按卡片可置顶");
        hint.setTextColor(Color.rgb(150, 150, 165));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * uiScale);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, dp(6), 0, dp(10));
        listRoot.addView(hint, lpWrap());

        // 卡片容器（重建列表时只替换此容器内容）
        cardContainer = new LinearLayout(this);
        cardContainer.setOrientation(LinearLayout.VERTICAL);
        listRoot.addView(cardContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部数据来源
        TextView foot = new TextView(this);
        foot.setText("数据来源 awesome-zhuiju-free");
        foot.setTextColor(Color.rgb(110, 110, 125));
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * uiScale);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(14), 0, 0);
        listRoot.addView(foot, lpWrap());

        setContentView(scroll);

        fillCards();

        // 预热：提前初始化 GeckoRuntime（内核加载），减少首次进站延迟；
        // 必须走 GeckoWebActivity.warm 统一单例，否则二次 create 崩溃
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                GeckoWebActivity.warm(MainActivity.this);
            }
        }, 500);

        // 启动：先检测本地数据可用性，同时后台拉远程新数据
        checker = Executors.newFixedThreadPool(6);
        runChecks();
        fetchRemoteData();
    }

    // ============ 数据加载 ============

    /** 加载站点列表：优先本地缓存，其次内置 assets */
    private void loadData() {
        String cached = readCache();
        if (cached != null) {
            List<Site> list = parseSites(cached);
            if (list != null && !list.isEmpty()) {
                applyList(list);
                return;
            }
        }
        try {
            InputStream is = getAssets().open("resources.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            applyList(parseSites(bos.toString("UTF-8")));
        } catch (Exception e) {
            // 兜底：空列表
        }
    }

    /** 解析站点 JSON（兼容已过滤格式；未过滤时只取在线影视） */
    private List<Site> parseSites(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items == null) return null;
            List<Site> out = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                String cat = o.optString("cat", "");
                if (!cat.isEmpty() && !"online_video".equals(cat)) continue;
                out.add(new Site(
                        o.optString("name", "?"),
                        o.optString("url", ""),
                        o.optString("desc", "")));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 应用新列表（保留置顶状态），重建界面；首次（无本地文件）时落地为本地 JSON */
    private void applyList(List<Site> fresh) {
        Set<String> pinned = loadPinned();
        sites.clear();
        for (Site s : fresh) {
            s.pinned = pinned.contains(s.url);
            sites.add(s);
        }
        if (!cacheFile().exists()) saveCache();
        fillCards();
    }

    // ============ 远程更新 ============

    /** 启动后台拉取：成功则写入本地 JSON 并从本地文件应用，失败则程序继续读本地 JSON */
    private void fetchRemoteData() {
        if (checker == null) return;
        checker.execute(new Runnable() {
            @Override
            public void run() {
                String data = tryFetch(REMOTE_SOURCE);
                if (data == null) return;
                saveRawJson(data);
                final List<Site> fresh = parseSites(readCache());
                if (fresh == null || fresh.isEmpty()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyList(fresh);
                        runChecks();
                    }
                });
            }
        });
    }

    /** 按钮：拉取远程 JSON 写入本地（失败则保留本地已有 JSON），再从本地文件应用并检测可用性 */
    private void updateDataAndCheck() {
        if (checker == null) return;
        checker.execute(new Runnable() {
            @Override
            public void run() {
                String data = tryFetch(REMOTE_SOURCE);
                if (data != null) saveRawJson(data);
                final List<Site> fresh = parseSites(readCache());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fresh != null && !fresh.isEmpty()) {
                            applyList(fresh);
                        }
                        runChecks();
                    }
                });
            }
        });
    }

    /** 拉取 URL 内容（限 512KB） */
    private String tryFetch(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "LuoxueNav/1.6");
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) break;
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ============ 可用性检测 ============

    /** 可用性检测：全部站点重新检测 */
    private void runChecks() {
        if (checker == null) return;
        for (final Site s : sites) {
            s.status = 0;
            setTag(s, GRAY_GRAD);
            checker.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = ping(s.url);
                    s.status = ok ? 1 : 2;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setTag(s, ok ? GREEN_GRAD : RED_GRAD);
                        }
                    });
                }
            });
        }
    }

    /** 探测站点可达性（GET 头，不下载内容） */
    private boolean ping(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void setTag(Site s, int[] grads) {
        if (s.dot != null) {
            ((GradientDrawable) s.dot.getBackground()).setColors(grads);
        }
    }

    /** 检测状态 → 渐变颜色：0 检测中(灰) 1 可用(绿) 2 不可用(红) */
    private int[] gradForStatus(int status) {
        if (status == 1) return GREEN_GRAD;
        if (status == 2) return RED_GRAD;
        return GRAY_GRAD;
    }

    // ============ 界面构建 ============

    /** 重建卡片列表：置顶站点独立分区，其余为全部站点 */
    private void fillCards() {
        if (cardContainer == null) return;
        cardContainer.removeAllViews();

        List<Site> pinned = new ArrayList<>();
        List<Site> rest = new ArrayList<>();
        for (Site s : sites) {
            if (s.pinned) pinned.add(s);
            else rest.add(s);
        }

        if (!pinned.isEmpty()) {
            addSectionTitle("★ 已置顶");
            renderGrid(pinned);
        }
        addSectionTitle("全部站点");
        renderGrid(rest);

        // 电视：默认聚焦第一个站点，遥控器开机即可直接操作
        if (tvMode) {
            final View first = findFirstCard();
            if (first != null) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        first.requestFocus();
                    }
                });
            }
        }
    }

    /** 找到第一个可聚焦的卡片 */
    private View findFirstCard() {
        for (int i = 0; i < cardContainer.getChildCount(); i++) {
            View row = cardContainer.getChildAt(i);
            if (row instanceof LinearLayout) {
                LinearLayout rl = (LinearLayout) row;
                for (int j = 0; j < rl.getChildCount(); j++) {
                    View c = rl.getChildAt(j);
                    if (c != null && c.isFocusable()) return c;
                }
            }
        }
        return null;
    }

    /** 分区标题 */
    private void addSectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(124, 77, 255));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * uiScale);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(2), dp(14), 0, dp(2));
        cardContainer.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 渲染一组站点为自适应列数网格（手机2 / 小平板3 / 平板4 / 大平板5） */
    private void renderGrid(List<Site> sub) {
        for (int i = 0; i < sub.size(); i += gridCols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            cardContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int c = 0; c < gridCols; c++) {
                int idx = i + c;
                if (idx < sub.size()) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(c == 0 ? 0 : 4), dp(4),
                            dp(c == gridCols - 1 ? 0 : 4), dp(4));
                    row.addView(makeGridCard(sub.get(idx)), lp);
                } else {
                    View ph = new View(this);
                    row.addView(ph, new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
        }
    }

    /** 网格卡片：渐变标签+名称 / 简介；长按置顶/取消 */
    private View makeGridCard(final Site s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setClickable(true);
        card.setFocusable(true);
        // 禁用卡片默认焦点高亮：系统高亮在多列网格滚动/重建时会错位、乱跳到未聚焦卡片上
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            card.setDefaultFocusHighlightEnabled(false);
        }

        final GradientDrawable bg = rounded(dp(14), Color.rgb(31, 41, 55));
        card.setBackground(bg);

        // 顶行：渐变标签（独立可聚焦组件）+ 名称
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final View tag = new View(this);
        // 重建时按当前检测状态恢复渐变颜色，不做无谓的"重新检测"效果
        final GradientDrawable tagBg = ovalGrad(gradForStatus(s.status));
        tag.setBackground(tagBg);
        // 触屏模式：标签为独立可聚焦键；电视模式：整卡为唯一焦点单元，避免遥控器焦点在小圆点间跳动
        tag.setFocusable(!tvMode);
        tag.setClickable(true);
        tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        top.addView(tag, new LinearLayout.LayoutParams(dp(16), dp(16)));
        s.dot = tag;
        tag.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                tagBg.setStroke(hasFocus ? dp(2) : 0,
                        hasFocus ? Color.WHITE : Color.TRANSPARENT);
            }
        });
        tag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSite(s);
            }
        });

        TextView name = new TextView(this);
        name.setText(s.pinned ? "★ " + s.name : s.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * uiScale);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(8), 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 简介
        TextView desc = new TextView(this);
        desc.setText(s.desc == null || s.desc.isEmpty() ? s.url : s.desc);
        desc.setTextColor(Color.rgb(160, 160, 175));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * uiScale);
        desc.setSingleLine(true);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.setPadding(0, dp(5), 0, 0);
        card.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 焦点高亮：电视用白色粗边框（远距离清晰可见），手机保持底色变化
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (tvMode) {
                    bg.setStroke(hasFocus ? dp(3) : 0,
                            hasFocus ? Color.WHITE : Color.TRANSPARENT);
                    bg.setColor(hasFocus ? Color.rgb(71, 85, 105) : Color.rgb(31, 41, 55));
                } else {
                    bg.setColor(hasFocus ? Color.rgb(55, 65, 81) : Color.rgb(31, 41, 55));
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSite(s);
            }
        });
        // 长按（触屏）：置顶 / 取消置顶
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                togglePin(s);
                return true;
            }
        });
        // 绑定站点对象，供遥控器菜单键操作（getTag）
        card.setTag(s);
        tag.setTag(s);
        return card;
    }

    /** 切换置顶状态并重建列表 */
    private void togglePin(Site s) {
        s.pinned = !s.pinned;
        savePinned();
        fillCards();
    }

    private void openSite(Site s) {
        if (s.url == null || s.url.isEmpty()) return;
        // v1.31+: 进站用 GeckoView 内核（Firefox 158）——现代特性全支持，
        // 5 个 X5 89 渲染失败的站点已全部正常
        Intent it = new Intent(this, GeckoWebActivity.class);
        it.putExtra("url", s.url);
        it.putExtra("title", s.name);
        startActivity(it);
        // 无转场动画：点击即切换，减少跳转延迟感
        overridePendingTransition(0, 0);
    }

    // ============ 置顶持久化 ============

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Set<String> loadPinned() {
        return new HashSet<>(prefs().getStringSet(KEY_PINNED, new HashSet<String>()));
    }

    private void savePinned() {
        Set<String> urls = new HashSet<>();
        for (Site s : sites) if (s.pinned) urls.add(s.url);
        prefs().edit().putStringSet(KEY_PINNED, urls).apply();
    }

    // ============ 本地 JSON（程序的数据来源） ============

    private File cacheFile() {
        return new File(getFilesDir(), "nav_data.json");
    }

    /** 把拉取到的远程 JSON 原样写入本地文件 */
    private void saveRawJson(String data) {
        try {
            FileOutputStream fos = new FileOutputStream(cacheFile());
            fos.write(data.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 兜底：仅当本地文件不存在时，把当前列表序列化成本地 JSON（首次安装） */
    private void saveCache() {
        try {
            JSONObject root = new JSONObject();
            JSONArray items = new JSONArray();
            for (Site s : sites) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("desc", s.desc);
                o.put("cat", "online_video");
                items.put(o);
            }
            root.put("items", items);
            FileOutputStream fos = new FileOutputStream(cacheFile());
            fos.write(root.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private String readCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            fis.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 按键与生命周期 ============

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 电视遥控器：菜单键切换当前站点置顶/取消置顶
        if (tvMode && keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            Object o = (f != null) ? f.getTag() : null;
            if (o instanceof Site) {
                togglePin((Site) o);
                return true;
            }
        }
        // 遥控器确认键：触发当前焦点卡片
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            View f = getCurrentFocus();
            if (f != null) {
                f.performClick();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (checker != null) {
            checker.shutdownNow();
            checker = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v * uiScale, getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable rounded(int r, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        g.setColor(color);
        return g;
    }

    /** 圆形渐变背景（左上到右下） */
    private GradientDrawable ovalGrad(int[] colors) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setColors(colors);
        return g;
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
