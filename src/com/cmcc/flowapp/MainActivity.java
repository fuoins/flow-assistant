package com.cmcc.flowapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流量查询助手 v1.7
 * - 原生登录界面（仿官方登录页元素：短信/密码双tab、验证码、协议、记住账号）
 * - 隐藏 WebView 后台自动完成官方登录（自动填表提交，风控滑块时才短暂显示官方页兜底）
 * - 全程不显示官方登录网页与流量详情网页
 * - 消耗流量上方区域（头部+汇总卡）压缩至约半屏
 */
public class MainActivity extends Activity {

    private static final String LOGIN_URL =
            "https://login.10086.cn/html/login/touch.html?channelID=12022&backUrl=https://wap.10086.cn/bj/index_100_100.html";
    private static final String FLOW_URL = "https://touch.10086.cn/i/mobile/flowquery.html";
    private static final String TOUCH_HOME = "https://touch.10086.cn/i/mobile/home.html";
    private static final String CONFIG_URL = "https://zy520.de5.net/flow.json";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /* ---------- 色板 ---------- */
    private static final int BG = 0xFFF2F5FA;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF14243C;
    private static final int SUB = 0xFF5A6B85;
    private static final int FAINT = 0xFF94A3B8;
    private static final int LINE = 0xFFE8EDF4;
    private static final int PRIMARY = 0xFF0B6BFF;
    private static final int PRIMARY2 = 0xFF3B9BFF;
    private static final int GREEN = 0xFF0E9F6E;
    private static final int ORANGE = 0xFFE8862A;
    private static final int RED = 0xFFE5484D;
    private static final int PURPLE = 0xFF8B5CF6;
    private static final int SLATE = 0xFF64748B;

    /* ---------- 视图 ---------- */
    private FrameLayout container;
    private WebView web;
    private ScrollView nativeScreen;
    private LinearLayout summaryBox;
    private TextView statusLine;
    private TextView headerRemainNum;
    private TextView headerRemainUnit;
    private TextView headerUsedTotal;
    private RatioBar headerBar;
    private LinearLayout detailPanel;
    private boolean detailOpen = false;
    private List<FlowItem> lastItems = new ArrayList<>();

    /* ---------- 原生登录界面 ---------- */
    private LinearLayout loginScreen;
    private EditText phoneInput, smsInput, pwdInput;
    private Button getSmsBtn, loginBtn;
    private TextView loginErrorText;
    private boolean smsMode = true;
    /** 协议与记住账号默认全部勾选（勾选框已隐藏） */
    private final boolean[] agree1 = {true}, agree2 = {true}, remember = {true};
    private boolean pendingSms = false, pendingLogin = false, pollRunning = false;
    private int pollTicks = 0;
    private boolean sliderSeen = false;
    private long sliderSince = 0;
    private int sliderRetries = 0;
    private Runnable loginWatchdog;
    private int loginWatchdogTicks = 0;

    /* 原生滑块（把官方 tianai-captcha 搬成原生控件，官方页全程隐藏） */
    /* 过渡加载层 */
    private FrameLayout loadingOverlay;
    private TextView loadingText;

    private FrameLayout sliderOverlay;    private ImageView sliderBgImg, sliderTplImg;
    private View sliderBtn;
    private TextView sliderTitle, sliderTips;
    private String captchaId;
    private int bgW, bgH, tplW, tplH;
    private float sliderScale = 1f;
    private int sliderMaxDx = 0;
    private long sliderStartTime;
    private float sliderStartRawX;
    private boolean sliderSubmitting = false;
    private List<JSONObject> trackArr = new ArrayList<>();
    private String lastUrl = "";
    private Runnable pollRunnable;
    private int countdownLeft = 0;
    private Runnable countdownRunnable;

    /* ---------- 消耗 ---------- */
    private TextView sourcePicker;
    private int sourceIndex = 0;
    private List<String> sourceNames = new ArrayList<>();
    private Button startBtn;
    private TextView wasteText;
    private EditText thresholdInput;
    private TextView ipText;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong wasted = new AtomicLong(0);
    private final ConcurrentLinkedQueue<HttpURLConnection> activeConn = new ConcurrentLinkedQueue<>();
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private List<String[]> sources = new ArrayList<>();
    private List<String[]> defaultClaims = new ArrayList<>();

    /* ---------- 提取 ---------- */
    private int extractTries = 0;
    private int autoReloads = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(PRIMARY);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                getWindow().getDecorView().setSystemUiVisibility(0);
            }
        }
        container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        setContentView(container);

        buildDefaults();
        buildWebView();
        restoreCookies();
        buildNativeScreen();
        buildLoginScreen();
        buildSliderOverlay();
        buildLoadingOverlay();
        fetchConfig();

        showNative();
        showLoading("正在加载流量数据…");
        web.loadUrl(FLOW_URL);   // cookie 有效则后台直取，无效则落到登录页（隐藏）
    }

    /* ================= 默认配置（外部配置拉取失败时兜底） ================= */

    private void buildDefaults() {
        sources.add(new String[]{"今日头条", "https://lf9-static.bytednsdoc.com/obj/eden-cn/uhbfnupkbps/video/earth_v6.mp4", "通用"});
        sources.add(new String[]{"字节跳动①", "https://lf1-cdn-tos.bytescm.com/obj/static/ies/bytedance_official/_next/static/images/8-4@2x-f85835b5e482bccf94c824067caac899.png", "通用"});
        sources.add(new String[]{"字节跳动②", "https://lf3-cdn-tos.bytescm.com/obj/ttfe/ATSX/mainland/video-poster_1576231362701.png", "通用"});
        sources.add(new String[]{"移动云盘①", "https://yun.mcloud.139.com/hongseyunpan/2.43G.zip", "定向"});
        sources.add(new String[]{"移动云盘②", "https://open.yun.139.com/static/img/case_bg_1.16716f8f.png", "定向"});
        sources.add(new String[]{"咪咕视频①", "https://img.cmvideo.cn/publish/noms/2026/09/01/1O7GU70S9OLS2.gif", "定向"});
        sources.add(new String[]{"咪咕视频②", "https://img.cmvideo.cn/publish/noms/2022/10/14/1O3VIGPVP6HTS.jpg", "定向"});
        sources.add(new String[]{"咪咕爱看", "https://img.aikan.miguvideo.com/publish/noms/2024/04/22/1O5MNLF3AN2JL.gif", "定向"});
        sources.add(new String[]{"咪咕快游①", "https://ggv.cmvideo.cn/v1/iflyad/deliverysystem/dsp/mgc_transfiles/1290/2026/8/21/3NMgeOQQt1danQNEClrM10/48ca7568/custom_95113/3NMgeOQQt1danQNEClrM10custom_95113MP4.mp4", "定向"});
        sources.add(new String[]{"咪咕快游②", "https://freeserver.migufun.com/resource/beta/video/system/20210924112351666.mp4", "定向"});
        sources.add(new String[]{"移动", "https://img1.shop.10086.cn/goods/tcqtjwurkdsfcxgr_940x7200", "定向"});

        defaultClaims.add(new String[]{"辽宁专属活动", "流量嗨翻天活动",
                "https://lyh.lncmcc.com/lyhVue/index.html#/llhft?actNo=lljf0419&shopId=ff8080817356d7320173573c4ade03c4&pc=Ki1nxVQshUxGgwDA9ijpwc%2FWj6E5YaXHhCimHfcUjlX2C7ZKayCuhJ5ycKe1gDAPiTmP0Dp6UiAF%0AjejCgdJM3fJb8DEoBjWSwaDNZnFXV%2Fl43qfRx7YShvzpokFOICFhpcQfcRgX%2B5FMSWs2ABIbzOX6%0AyeJ4%2F%2FAHR18lKBRRUqBsWxYlmOU79lwATEeOAZkvWuohrqYk4O%2FneCF7PvGdzLnyxsUreeVFmjSx%0AbdGQhWVfXRm%2FYi3j3Y2ZMNYxtbkmxM84CChaPTUnSbN277%2F4uxzpJLUtZI6GnLfP%2BDVRrRahazAl%0Angy47FjZnsOw%2F7l6i7SJGiH9MjkFWLxeNQdaZ4sepri8bv9TpTrAy3a%2FZNdsrfSC4M0JYS1P7nDA%0A8Fo6%2BQgZaEvJpEGmt7sGDhjaBjFEfDEPzup66WLcz9nzOQfQn8SVikUwJXwY6ZTHPjZFqH39rpK%2F%0A89taHQuTxbB3qs2Vh3Csvhtdy0DBOQy74qrCG83Khgo1kGA%2FG6xy%2BdrQM18hC5BdGW38IAn7kFTS%0AQGMF%2FlP8EvfRm0QxnMA9WLO6ciFkDiDfpigQhfmksaSGzw%2BFt2%2FBqJ8ZVWj%2FtbZWDmi7e3sYdKkx%0ANDPNKXggpoRskfSMF8bU2hDbsxWorF2wZL9G%2Fd9ANzJs7G9MKQgYp9jRMgj0YYP3v47zP7dIa3Iz%0ANH%2FUwcFNkiPK11qbQb6ZakLcdevX3lUgb5We7RDy%2Ffg1TwAbznPgEz0y5CKIIc8emUVnxAM9s%2BfN%0A%2F89B4593z6%2FV9yFuiZI6IbepEk8yzMEikKL%2BB3b42K4d45aBtiThvF2pQOD8Fb2j%2Bx%2F%2F5PhIaJyb%0AvdY6gg7kP7qa24kAuXnsSIh9AVZA%2F%2FmL1yfRfKg5qsPeVlhfPeM8URT5gJTfqC1Rb7a5uUYrJCm3%0AZHOHyKSNiQUH2K5iH5wnvb4njWFnrDLLTIWJ29l6ZoP9lv2Y4osAXT1CuMmxBN%2BqqdDh9uoZfMjp%0AJ0sMVWTj1RPxrT%2FIUtPw2riMGfF56tbgD1cvVrQXB9iubOap419%2F8uqY6VT%2B1lCtnKlevU55HvA9%0AaZQ83wAXC9VMaKQjpBZVelJUtP9ZvYma5SKD5vcPybrzK1uapHNN2vNI3IBla0zjA5U%3D&pk=438DB3667C7DDD7497B387AAE8DDB717D62293A55E252647C7E0FC879C1E22A1282F621A5F8A64F6E87A3A9518AF32CEE1C1CA09D4C9ED5EF1CA6CADE38C80F57397798B6483F0D1A9228F25ECB75B49529F5992FC06CACCB7D831A1D3624A70B199939A49A5A044B5B0A6150446A4A46B8D592BD4456F4E09F9F64186B8AFF9&_sid_=-1"});
        defaultClaims.add(new String[]{"全国通用定向流量", "30GB 咪咕悦看定向流量", "https://n.cmread.com/nap/p/mlln30G.jsp?z=1&ln=100975_1015599_3801_8_380_L1&cm=00000000&vt=3&key=1&isShare=1"});
        defaultClaims.add(new String[]{"全国通用定向流量", "50GB 咪咕视频定向流量", "https://m.miguvideo.com/m/provincevml/e0fa9f12a77b471c9cdbb7a439b86679?needLazyimg=0&sharefrom=miguvideoapp&pwId=c195ee02662b436fbc533939e99e127c"});
        defaultClaims.add(new String[]{"全国通用定向流量", "50GB 咪咕快游定向流量", "https://www.migufun.com/miguplay/html/personalTraffic/personalTrafficUp50?statisticChannel=bSj&channel=40257748296&from=778135&appChannel=40257748296"});
        defaultClaims.add(new String[]{"全国通用定向流量", "30GB 咪咕阅读定向流量", "https://n.cmread.com/nap/p/dh25hl.jsp?cm=D0022802&mbid=2bJNkIDFqp6ak7ZTm+qhh0q8OivyoMI2aOHP3YCC1T8%3D&is_np=1"});
        defaultClaims.add(new String[]{"全国通用定向流量", "30GB 咪咕音乐定向流量", "https://h5.nf.migu.cn/app/v4/zt/2021/directed-flow/index.html#/home"});
        defaultClaims.add(new String[]{"全国通用定向流量", "30GB 移动云盘定向流量", "https://caiyun.feixin.10086.cn:7071/portal/caiyunOfficialAccount/index.html?path=zeroPurchase&sourceid=1283#/zeroPurchase"});
    }

    /* ================= WebView（登录 + 后台取数，全程隐藏） ================= */

    private void buildWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                lastUrl = url == null ? "" : url;
                saveCookies();
                if (isPage(url, "touch.10086.cn/i/mobile/flowquery.html")) {
                    stopPoll();
                    resetLoginBtns();
                    pendingSms = false;
                    pendingLogin = false;
                    extractTries = 0;
                    showLoading("正在获取流量数据…");
                    scheduleExtract();
                } else if (isWapHome(url)) {
                    // 登录成功落到 WAP 首页：跳过掌厅首页中转，直接取流量页
                    web.loadUrl(FLOW_URL);
                } else if (isPage(url, "touch.10086.cn/i/mobile/home.html")) {
                    web.loadUrl(FLOW_URL);
                } else if (pendingLogin && !isLoginPage(url)) {
                    // 登录后官方跳转链中途页面：不再等它，直接进流量页
                    pendingLogin = false;
                    web.loadUrl(FLOW_URL);
                } else if (isLoginPage(url)) {
                    if (pendingSms) { requestCaptcha(); }
                    else if (pendingLogin) { pendingLogin = false; submitLogin(); }
                    else showLoginScreen();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                handleJsAlert(message);
                result.confirm();
                return true;
            }
        });
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean isWapHome(String url) {
        return url != null && (url.startsWith("http://wap.10086.cn/bj/")
                || url.startsWith("https://wap.10086.cn/bj/"));
    }

    private boolean isPage(String url, String hostPath) {
        if (url == null) return false;
        return url.startsWith("http://" + hostPath) || url.startsWith("https://" + hostPath);
    }

    private boolean isLoginPage(String url) {
        return url != null && url.startsWith("https://login.10086.cn/html/login/");
    }

    private boolean onLoginPageNow() {
        return isLoginPage(lastUrl);
    }

    private void showLoginScreen() {
        hideLoading();
        if (web != null) web.setVisibility(View.INVISIBLE);
        if (nativeScreen != null) nativeScreen.setVisibility(View.GONE);
        if (loginScreen != null) loginScreen.setVisibility(View.VISIBLE);
    }

    private void showNative() {
        if (web != null) web.setVisibility(View.INVISIBLE);
        if (loginScreen != null) loginScreen.setVisibility(View.GONE);
        if (nativeScreen != null) nativeScreen.setVisibility(View.VISIBLE);
    }

    /** 风控（滑块/登录保护）兜底：短暂显示官方页面让用户完成验证 */
    private void showWebOnly() {
        hideLoading();
        if (loginScreen != null) loginScreen.setVisibility(View.GONE);
        if (nativeScreen != null) nativeScreen.setVisibility(View.GONE);
        if (web != null) web.setVisibility(View.VISIBLE);
    }

    /* 过渡加载层：登录成功取数 / 启动取数期间给用户进度反馈 */
    private void buildLoadingOverlay() {
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xF2FFFFFF);
        loadingOverlay.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(this);
        box.addView(pb, new LinearLayout.LayoutParams(dp(46), dp(46)));
        loadingText = new TextView(this);
        loadingText.setText("正在加载…");
        loadingText.setTextSize(13);
        loadingText.setTextColor(SUB);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(14);
        box.addView(loadingText, lp2);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        loadingOverlay.addView(box, bp);
        container.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showLoading(String text) {
        if (loadingOverlay == null) return;
        if (text != null && loadingText != null) loadingText.setText(text);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }

    /* ================= 原生登录界面（仿官方元素） ================= */

    private void buildLoginScreen() {
        loginScreen = new LinearLayout(this);
        loginScreen.setOrientation(LinearLayout.VERTICAL);
        loginScreen.setBackgroundColor(BG);
        loginScreen.setPadding(dp(18), dp(22), dp(18), dp(24));

        /* 头部 */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable hd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{PRIMARY, PRIMARY2});
        hd.setCornerRadius(dp(22));
        header.setBackground(hd);
        loginScreen.addView(header);

        TextView t1 = new TextView(this);
        t1.setText("登录");
        t1.setTextSize(22);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(0xFFFFFFFF);
        header.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("登录中国移动账号，自动同步流量余量");
        t2.setTextSize(12);
        t2.setTextColor(0xCCFFFFFF);
        t2.setPadding(0, dp(4), 0, 0);
        header.addView(t2);

        /* 主卡 */
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(18));
        card.setBackground(rippleBg(pillBg(CARD, dp(20))));
        card.setElevation(dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        loginScreen.addView(card, clp);

        /* 手机号 */
        card.addView(fieldLabel("手机号码"));
        phoneInput = makeField("请输入手机号码", android.text.InputType.TYPE_CLASS_PHONE);
        phoneInput.setMaxLines(1);
        phoneInput.setSingleLine(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        plp.topMargin = dp(4);
        card.addView(phoneInput, plp);

        /* 验证码 */
        card.addView(fieldLabel("短信随机码"));
        LinearLayout smsRow = new LinearLayout(this);
        smsRow.setOrientation(LinearLayout.HORIZONTAL);
        smsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        srlp.topMargin = dp(4);
        card.addView(smsRow, srlp);

        smsInput = makeField("6位短信随机码", android.text.InputType.TYPE_CLASS_NUMBER);
        smsInput.setMaxLines(1);
        smsInput.setSingleLine(true);
        smsRow.addView(smsInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        getSmsBtn = new Button(this);
        getSmsBtn.setText("获取验证码");
        getSmsBtn.setAllCaps(false);
        getSmsBtn.setTextSize(13);
        getSmsBtn.setTextColor(PRIMARY);
        getSmsBtn.setBackground(rippleBg(pillBg(0xFFE8F0FF, dp(14))));
        getSmsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doGetSms(); }
        });
        LinearLayout.LayoutParams gbl = new LinearLayout.LayoutParams(dp(110), dp(40));
        gbl.leftMargin = dp(10);
        smsRow.addView(getSmsBtn, gbl);

        /* 错误提示 */
        loginErrorText = new TextView(this);
        loginErrorText.setTextSize(12);
        loginErrorText.setTextColor(RED);
        loginErrorText.setPadding(0, dp(8), 0, 0);
        loginErrorText.setVisibility(View.GONE);
        card.addView(loginErrorText);

        /* 立即登录 */
        loginBtn = new Button(this);
        loginBtn.setText("立即登录");
        loginBtn.setAllCaps(false);
        loginBtn.setTextSize(17);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loginBtn.setTextColor(0xFFFFFFFF);
        loginBtn.setBackground(rippleBg(pillBg(PRIMARY, dp(26))));
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLogin(); }
        });
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lbp.topMargin = dp(14);
        card.addView(loginBtn, lbp);

        /* 记住的手机号回填（记住账号已默认勾选） */
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("remember_phone", "");
        if (!saved.isEmpty()) {
            phoneInput.setText(saved);
        }

        container.addView(loginScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView fieldLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUB);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText makeField(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setHint(hint);
        e.setHintTextColor(FAINT);
        e.setTextSize(14);
        e.setTextColor(INK);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(strokeBg(0xFFFFFFFF, dp(14), LINE, 1));
        return e;
    }

    private void loginError(String msg) {
        if (loginErrorText == null) return;
        if (msg == null || msg.isEmpty()) {
            loginErrorText.setText("");
            loginErrorText.setVisibility(View.GONE);
        } else {
            loginErrorText.setText(msg);
            loginErrorText.setVisibility(View.VISIBLE);
        }
    }

    private void resetLoginBtns() {
        if (loginBtn != null) { loginBtn.setEnabled(true); loginBtn.setText("立即登录"); }
        if (countdownLeft <= 0 && getSmsBtn != null) {
            getSmsBtn.setEnabled(true);
            getSmsBtn.setText("获取验证码");
        }
    }

    /* ================= 通过隐藏 WebView 自动执行官方登录 ================= */

    private static final String GETSMS_JS =
            "(function(){var n=document.getElementById('sms_nav');if(n&&n.className.indexOf('on')<0)n.click();" +
            "var p=document.getElementById('p_phone');if(p)p.value='%1$s';" +
            "var el=document.getElementById('getSMSpwd');if(el){el.click();return 'ok';}return 'no';})()";
    private static final String PWD_GETSMS_JS =
            "(function(){var n=document.getElementById('account_nav');if(n&&n.className.indexOf('on')<0)n.click();" +
            "var p=document.getElementById('p_phone_account');if(p)p.value='%1$s';" +
            "var el=document.getElementById('getPhoneSMSpwd');if(el){el.click();return 'ok';}return 'no';})()";
    private static final String LOGIN_JS =
            "(function(){var n=document.getElementById('sms_nav');if(n&&n.className.indexOf('on')<0)n.click();" +
            "var p=document.getElementById('p_phone');if(p)p.value='%1$s';" +
            "var c=document.getElementById('p_sms');if(c)c.value='%2$s';" +
            "var f=document.getElementById('fw');if(f&&f.className.indexOf('check_chk')<0)f.click();" +
            "var x=document.getElementById('xx');if(x&&x.className.indexOf('check_chk')<0)x.click();" +
            "var k=document.getElementById('chk');if(k&&k.className.indexOf('check_chk')<0)k.click();" +
            "var bt=document.getElementById('submit_bt');if(bt){bt.click();return 'ok';}return 'no';})()";
    private static final String PWD_LOGIN_JS =
            "(function(){var n=document.getElementById('account_nav');if(n&&n.className.indexOf('on')<0)n.click();" +
            "var p=document.getElementById('p_phone_account');if(p)p.value='%1$s';" +
            "var w=document.getElementById('p_pwd');if(w)w.value='%2$s';" +
            "var s=document.getElementById('phone_sms');if(s)s.value='%3$s';" +
            "var f=document.getElementById('fw');if(f&&f.className.indexOf('check_chk')<0)f.click();" +
            "var x=document.getElementById('xx');if(x&&x.className.indexOf('check_chk')<0)x.click();" +
            "var k=document.getElementById('chk');if(k&&k.className.indexOf('check_chk')<0)k.click();" +
            "var bt=document.getElementById('submit_bt');if(bt){bt.click();return 'ok';}return 'no';})()";
    private static final String POLL_JS =
            "(function(){var o={};" +
            "var cap=document.getElementById('captcha-box');var cs=cap?window.getComputedStyle(cap):null;o.captcha=!!(cs&&cs.display&&cs.display!=='none');" +
            "var pr=document.getElementById('login_protect_id');var ps=pr?window.getComputedStyle(pr):null;o.protect=!!(ps&&ps.display&&ps.display!=='none');" +
            "var e=document.getElementById('errinfo');o.err=e?e.textContent.replace(/^\\s+|\\s+$/g,''):'';" +
            "var ss=document.getElementById('sendSms');var s2=ss?window.getComputedStyle(ss):null;o.sent=!!(s2&&s2.display&&s2.display!=='none');" +
            "return JSON.stringify(o);})()";
    private static final String CLOSE_SEND_JS =
            "(function(){var b=document.getElementById('sendMsgQR');if(b)b.click();})()";

    private void doGetSms() {
        String phone = phoneInput.getText().toString().trim();
        if (phone.length() != 11) { loginError("请输入正确的11位手机号码"); return; }
        loginError("");
        pendingSms = true;
        pendingLogin = false;
        getSmsBtn.setEnabled(false);
        // 强制全新会话：清空 cookie + localStorage，避免沿用被风控标记的旧会话
        resetLoginSession();
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (pendingSms) web.loadUrl(LOGIN_URL);
            }
        }, 250);
    }

    /** 清空 WebView 全部 cookie 与本地存储，让登录/滑块/发短信都走全新会话 */
    private void resetLoginSession() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (android.os.Build.VERSION.SDK_INT >= 21) cm.removeAllCookies(null);
            else cm.removeAllCookie();
            if (web != null) {
                web.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
            }
        } catch (Exception ignored) {}
    }

    private void submitGetSms() {
        String phone = phoneInput.getText().toString().trim();
        String js = smsMode ? String.format(GETSMS_JS, phone) : String.format(PWD_GETSMS_JS, phone);
        eval(js);
    }

    private void doLogin() {
        String phone = phoneInput.getText().toString().trim();
        if (phone.length() != 11) { loginError("请输入正确的11位手机号码"); return; }
        if (smsInput.getText().toString().trim().isEmpty()) { loginError("请输入短信随机码"); return; }
        loginError("");
        if (remember[0]) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString("remember_phone", phone).apply();
        }
        pendingLogin = true;
        pendingSms = false;
        loginBtn.setEnabled(false);
        loginBtn.setText("登录中…");
        showLoading("正在登录，请稍候…");
        if (onLoginPageNow()) submitLogin();
        else web.loadUrl(LOGIN_URL);
        startPoll();
        startLoginWatchdog();
    }

    /* 登录看门狗：官方跳转链卡住时强制直达流量页，避免一直停在"正在登录" */
    private void startLoginWatchdog() {
        loginWatchdogTicks = 0;
        uiHandler.removeCallbacks(loginWatchdog);
        if (loginWatchdog == null) {
            loginWatchdog = new Runnable() {
                @Override public void run() {
                    if (!pendingLogin) return;
                    if (++loginWatchdogTicks >= 8) {   // 约 7.2 秒
                        pendingLogin = false;
                        web.loadUrl(FLOW_URL);
                        return;
                    }
                    uiHandler.postDelayed(this, 900);
                }
            };
        }
        uiHandler.postDelayed(loginWatchdog, 900);
    }

    private void submitLogin() {
        String phone = phoneInput.getText().toString().trim();
        String js = String.format(LOGIN_JS, phone, smsInput.getText().toString().trim());
        eval(js);
    }

    private void eval(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    private void startPoll() {
        pollRunning = true;
        pollTicks = 0;
        sliderSeen = false;
        sliderRetries = 0;
        if (pollRunnable == null) {
            pollRunnable = new Runnable() {
                @Override public void run() {
                    if (!pollRunning) return;
                    if (++pollTicks > 60) {
                        stopPoll();
                        resetLoginBtns();
                        pendingSms = false;
                        pendingLogin = false;
                        showLoginScreen();
                        loginError("操作超时，请重试");
                        return;
                    }
                    if (web != null) {
                        web.evaluateJavascript(POLL_JS, new android.webkit.ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                if (!pollRunning) return;
                                try {
                                    JSONObject o = new JSONObject(jsString(value));
                                    boolean captcha = o.optBoolean("captcha");
                                    boolean protect = o.optBoolean("protect");
                                    String err = o.optString("err");
                                    boolean sent = o.optBoolean("sent");

                                    if (captcha || protect) {
                                        // 风控出现：保持官方页可见，等用户完成
                                        sliderSeen = true;
                                        sliderSince = System.currentTimeMillis();
                                        if (loginScreen != null && loginScreen.getVisibility() == View.VISIBLE) {
                                            showWebOnly();
                                            Toast.makeText(MainActivity.this,
                                                    captcha ? "请完成滑动验证" : "请完成登录保护验证",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    } else if (sliderSeen) {
                                        // 滑块已滑完：留在官方页继续等待发送/登录结果，无结果自动重试
                                        if (!err.isEmpty()) {
                                            stopPoll();
                                            resetLoginBtns();
                                            pendingSms = false;
                                            pendingLogin = false;
                                            showLoginScreen();
                                            loginError(err);
                                            return;
                                        }
                                        if (pendingSms && sent) {
                                            stopPoll();
                                            resetLoginBtns();
                                            pendingSms = false;
                                            eval(CLOSE_SEND_JS);
                                            showLoginScreen();
                                            Toast.makeText(MainActivity.this,
                                                    "验证码已发送，请注意查收", Toast.LENGTH_SHORT).show();
                                            startCountdown();
                                            return;
                                        }
                                        if (System.currentTimeMillis() - sliderSince > 9000) {
                                            if (sliderRetries < 3) {
                                                sliderRetries++;
                                                sliderSince = System.currentTimeMillis();
                                                if (pendingSms) submitGetSms();
                                                else if (pendingLogin) submitLogin();
                                            } else {
                                                boolean smsFlow = pendingSms;
                                                stopPoll();
                                                resetLoginBtns();
                                                pendingSms = false;
                                                pendingLogin = false;
                                                showLoginScreen();
                                                loginError(smsFlow ? "验证码发送失败，请重试" : "登录失败，请重试");
                                                return;
                                            }
                                        }
                                    } else {
                                        // 未出现风控：直接返回结果
                                        if (web != null && web.getVisibility() == View.VISIBLE) {
                                            showLoginScreen();
                                        }
                                        if (!err.isEmpty()) {
                                            stopPoll();
                                            resetLoginBtns();
                                            loginError(err);
                                            return;
                                        }
                                        if (pendingSms && sent) {
                                            stopPoll();
                                            resetLoginBtns();
                                            pendingSms = false;
                                            eval(CLOSE_SEND_JS);
                                            Toast.makeText(MainActivity.this,
                                                    "验证码已发送，请注意查收", Toast.LENGTH_SHORT).show();
                                            startCountdown();
                                            return;
                                        }
                                    }
                                } catch (Exception ignored) {}
                                uiHandler.postDelayed(pollRunnable, 900);
                            }
                        });
                    }
                }
            };
        }
        uiHandler.postDelayed(pollRunnable, 900);
    }

    private void stopPoll() {
        pollRunning = false;
        if (pollRunnable != null) uiHandler.removeCallbacks(pollRunnable);
        if (loginWatchdog != null) uiHandler.removeCallbacks(loginWatchdog);
    }

    /**
     * 官方登录页用 alert() 提示发短信/登录结果（滑块验证成功后自动 sendsms -> sendDynamicPasswd，
     * 成功/失败都走 alert）。WebView 默认吞掉 alert，导致"滑完滑块 App 感知不到验证码已发送"。
     * 这里拦截并映射到原生登录状态机。
     */
    private void handleJsAlert(String msg) {
        if (msg == null || msg.isEmpty()) return;
        final String m = msg;
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (m.contains("已将短信随机码发送至手机")) {
                    if (pendingSms || pendingLogin) {
                        stopPoll();
                        resetLoginBtns();
                        pendingSms = false;
                        pendingLogin = false;
                        eval(CLOSE_SEND_JS);
                        closeSlider();
                        showLoginScreen();
                        Toast.makeText(MainActivity.this,
                                "验证码已发送，请注意查收", Toast.LENGTH_SHORT).show();
                        startCountdown();
                    }
                } else if (pendingSms || pendingLogin) {
                    stopPoll();
                    resetLoginBtns();
                    pendingSms = false;
                    pendingLogin = false;
                    eval(CLOSE_SEND_JS);
                    closeSlider();
                    showLoginScreen();
                    if (m.contains("一分钟以后再试") || m.contains("暂时不能发送")
                            || m.contains("发送次数过于频繁") || m.contains("发送失败")
                            || m.contains("连接超时")) {
                        // 运营商限频/临时失败：与官方一致禁用 60 秒再试
                        loginError("发送太频繁，请 60 秒后再试");
                        startCountdown();
                    } else {
                        loginError(m);
                    }
                }
            }
        });
    }

    private void eval(String js, android.webkit.ValueCallback<String> cb) {
        if (web != null) web.evaluateJavascript(js, cb);
    }

    /* ================= 原生滑块验证（替代官方滑块，官方页全程隐藏） =================
     * 官方滑块协议（tac.min.js）：
     *   POST /genCaptcha  -> {id, captcha:{type, backgroundImage, templateImage}}
     *   POST /check       -> body {id, data:{bgImageWidth, bgImageHeight,
     *        templateImageWidth, templateImageHeight, startTime, stopTime, trackList}}
     *   响应 {code:200} 成功，data.id 作为验证凭证；随后官方 sendsms(id) 发短信。
     * 请求在隐藏 WebView 的页面环境里 fetch（同源、同 cookie、同指纹上下文）。
     */

    private void buildSliderOverlay() {
        sliderOverlay = new FrameLayout(this);
        sliderOverlay.setBackgroundColor(0x99000000);
        sliderOverlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(strokeBg(0xFFFFFFFF, dp(16), 0xFFE0E0E0, 1));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        cp.setMargins(dp(40), 0, dp(40), 0);
        sliderOverlay.addView(card, cp);

        /* 标题行 */
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(titleRow);

        sliderTitle = new TextView(this);
        sliderTitle.setText("拖动滑块完成拼图");
        sliderTitle.setTextSize(15);
        sliderTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sliderTitle.setTextColor(INK);
        titleRow.addView(sliderTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("↻");
        refreshBtn.setTextSize(16);
        refreshBtn.setTextColor(SUB);
        refreshBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!sliderSubmitting) requestCaptcha();
            }
        });
        titleRow.addView(refreshBtn);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(16);
        closeBtn.setTextColor(SUB);
        closeBtn.setPadding(dp(10), dp(4), dp(2), dp(4));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingSms = false;
                closeSlider();
                resetLoginBtns();
                loginError("");
            }
        });
        titleRow.addView(closeBtn);

        /* 图片区（宽固定，高加载后按比例设置） */
        final FrameLayout imgBox = new FrameLayout(this);
        final int imgW = getResources().getDisplayMetrics().widthPixels - dp(40) * 2 - dp(32);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(imgW, dp(150));
        ibp.topMargin = dp(10);
        card.addView(imgBox, ibp);

        sliderBgImg = new ImageView(this);
        sliderBgImg.setScaleType(ImageView.ScaleType.FIT_XY);
        imgBox.addView(sliderBgImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sliderTplImg = new ImageView(this);
        sliderTplImg.setScaleType(ImageView.ScaleType.FIT_XY);
        imgBox.addView(sliderTplImg, new FrameLayout.LayoutParams(dp(52), dp(52)));

        /* 滑轨 + 拖动按钮 */
        final FrameLayout track = new FrameLayout(this);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(imgW, dp(44));
        trp.topMargin = dp(8);
        card.addView(track, trp);

        View trackBg = new View(this);
        trackBg.setBackground(strokePillBg(0xFFF2F2F2, dp(22), 0xFFDDDDDD, 1));
        track.addView(trackBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        sliderBtn = new View(this);
        sliderBtn.setBackground(pillBg(0xFF1E88E5, dp(8)));
        track.addView(sliderBtn, new FrameLayout.LayoutParams(dp(42), dp(42)));
        sliderBtn.setOnTouchListener(sliderTouch);

        sliderTips = new TextView(this);
        sliderTips.setText("请按住滑块向右拖动");
        sliderTips.setTextSize(12);
        sliderTips.setTextColor(FAINT);
        sliderTips.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tipP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipP.topMargin = dp(6);
        card.addView(sliderTips, tipP);

        container.addView(sliderOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final View.OnTouchListener sliderTouch = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent ev) {
            if (sliderSubmitting) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sliderStartRawX = ev.getRawX();
                    sliderStartTime = System.currentTimeMillis();
                    trackArr.clear();
                    sliderTips.setTextColor(FAINT);
                    sliderTips.setText("请按住滑块向右拖动");
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - sliderStartRawX;
                    if (dx < 0) dx = 0;
                    if (dx > sliderMaxDx) dx = sliderMaxDx;
                    sliderBtn.setTranslationX(dx);
                    sliderTplImg.setTranslationX(dx);
                    try {
                        JSONObject pt = new JSONObject();
                        pt.put("x", Math.round(dx / sliderScale));
                        pt.put("y", 0);
                        pt.put("type", "move");
                        pt.put("t", System.currentTimeMillis() - sliderStartTime);
                        trackArr.add(pt);
                    } catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    sliderSubmitting = true;
                    submitCheck();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    sliderSubmitting = false;
                    return true;
            }
            return true;
        }
    };

    /** 从隐藏 WebView 页面环境请求滑块数据（同源同 cookie） */
    private void requestCaptcha() {
        if (sliderOverlay == null) return;
        if (sliderOverlay.getVisibility() == View.VISIBLE && sliderSubmitting) return; // 防重入
        sliderOverlay.setVisibility(View.VISIBLE);
        sliderTitle.setText("拖动滑块完成拼图");
        sliderTips.setTextColor(FAINT);
        sliderTips.setText("正在加载验证…");
        sliderSubmitting = true;
        sliderBtn.setTranslationX(0);
        sliderTplImg.setTranslationX(0);
        sliderBgImg.setImageDrawable(null);
        sliderTplImg.setImageDrawable(null);
        eval("(function(){fetch('/genCaptcha',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})" +
                ".then(function(r){return r.json()})" +
                ".then(function(d){if(d&&d.captcha){d.captcha.backgroundImage=new URL(d.captcha.backgroundImage,location.href).href;" +
                "d.captcha.templateImage=new URL(d.captcha.templateImage,location.href).href;}window.__tacCaptcha=d;})" +
                ".catch(function(e){window.__tacCaptcha={err:1};});})()");
        readCaptcha(0);
    }

    private void readCaptcha(final int tries) {
        if (tries > 16) { sliderFail("验证码加载超时，请点刷新重试", false); return; }
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (sliderOverlay == null || sliderOverlay.getVisibility() != View.VISIBLE) return;
                eval("JSON.stringify(window.__tacCaptcha)", new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String s = v == null ? "" : v.trim();
                        if (s.isEmpty() || "null".equals(s) || "undefined".equals(s)) {
                            readCaptcha(tries + 1);
                            return;
                        }
                        try {
                            JSONObject o = new JSONObject(jsString(v));
                            JSONObject cap = o.optJSONObject("captcha");
                            String id = o.optString("id", "");
                            String bg = cap == null ? "" : cap.optString("backgroundImage", "");
                            String tpl = cap == null ? "" : cap.optString("templateImage", "");
                            if (id.isEmpty() || bg.isEmpty() || tpl.isEmpty()) {
                                readCaptcha(tries + 1);
                                return;
                            }
                            loadCaptcha(bg, tpl, id);
                        } catch (Exception e) { readCaptcha(tries + 1); }
                    }
                });
            }
        }, 500);
    }

    private void loadCaptcha(final String bgUrl, final String tplUrl, final String id) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bg = loadBitmap(bgUrl);
                final Bitmap tpl = loadBitmap(tplUrl);
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (bg == null || tpl == null) {
                            sliderFail("验证码图片加载失败，请点刷新重试", false);
                            return;
                        }
                        captchaId = id;
                        bgW = bg.getWidth();
                        bgH = bg.getHeight();
                        tplW = tpl.getWidth();
                        tplH = tpl.getHeight();
                        int imgW = sliderBgImg.getWidth();
                        if (imgW <= 0) imgW = bgW;
                        sliderScale = (float) imgW / bgW;
                        int imgH = Math.round(bgH * sliderScale);
                        int tplDispW = Math.round(tplW * sliderScale);
                        int tplDispH = Math.round(tplH * sliderScale);
                        sliderMaxDx = imgW - tplDispW;
                        if (sliderMaxDx < 0) sliderMaxDx = 0;

                        ViewGroup.LayoutParams p = sliderBgImg.getLayoutParams();
                        p.height = imgH;
                        sliderBgImg.setLayoutParams(p);
                        sliderBgImg.setImageBitmap(bg);

                        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) sliderTplImg.getLayoutParams();
                        tp.width = tplDispW;
                        tp.height = tplDispH;
                        tp.leftMargin = 0;
                        tp.topMargin = (imgH - tplDispH) / 2;
                        sliderTplImg.setLayoutParams(tp);
                        sliderTplImg.setImageBitmap(tpl);

                        sliderBtn.setTranslationX(0);
                        sliderTplImg.setTranslationX(0);
                        sliderSubmitting = false;
                        sliderTips.setTextColor(FAINT);
                        sliderTips.setText("请按住滑块向右拖动");
                    }
                });
            }
        }).start();
    }

    /** 松手后提交轨迹到 /check（在隐藏 WebView 页面环境 fetch） */
    private void submitCheck() {
        try {
            JSONObject data = new JSONObject();
            data.put("bgImageWidth", bgW);
            data.put("bgImageHeight", bgH);
            data.put("templateImageWidth", tplW);
            data.put("templateImageHeight", tplH);
            data.put("startTime", isoTime(sliderStartTime));
            data.put("stopTime", isoTime(System.currentTimeMillis()));
            data.put("trackList", new JSONArray(trackArr));
            double lastX = 0;
            if (!trackArr.isEmpty()) {
                JSONObject last = trackArr.get(trackArr.size() - 1);
                lastX = last.optDouble("x", 0);
            }
            data.put("movePercent", bgW > 0 ? Math.round(lastX * 10000 / bgW) / 10000.0 : 0);
            data.put("clickCount", 0);
            data.put("end", 242);
            JSONObject body = new JSONObject();
            body.put("id", captchaId);
            body.put("data", data);
            String bodyStr = body.toString();
            sliderTips.setTextColor(FAINT);
            sliderTips.setText("验证中…");
            final String js = "(function(){fetch('/check',{method:'POST',headers:{'Content-Type':'application/json'},body:'"
                    + bodyStr + "'}).then(function(r){return r.json()})" +
                    ".then(function(d){window.__tacCheck=d;})" +
                    ".catch(function(e){window.__tacCheck={code:-1};});})()";
            eval(js);
            readCheckResult(0);
        } catch (Exception e) {
            sliderFail("提交失败，请重试", true);
        }
    }

    private String isoTime(long ms) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private void readCheckResult(final int tries) {
        if (tries > 14) { sliderFail("验证超时，请重试", true); return; }
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (sliderOverlay == null || sliderOverlay.getVisibility() != View.VISIBLE) return;
                eval("JSON.stringify(window.__tacCheck)", new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String s = v == null ? "" : v.trim();
                        if (s.isEmpty() || "null".equals(s) || "undefined".equals(s)) {
                            readCheckResult(tries + 1);
                            return;
                        }
                        try {
                            JSONObject o = new JSONObject(jsString(v));
                            if (o.optInt("code") == 200) {
                                String cid = captchaId;
                                JSONObject dd = o.optJSONObject("data");
                                if (dd != null && !dd.optString("id", "").isEmpty()) cid = dd.optString("id");
                                sliderSuccess(cid);
                            } else {
                                sliderFail("验证失败，请重试", true);
                            }
                        } catch (Exception e) { readCheckResult(tries + 1); }
                    }
                });
            }
        }, 500);
    }

    /** 验证成功：把手机号填进官方页并调用官方 sendsms(id)，官方流程发短信，alert 由 handleJsAlert 拦截 */
    private void sliderSuccess(String id) {
        final String phone = phoneInput.getText().toString().trim();
        sliderTips.setTextColor(0xFF2E7D32);
        sliderTips.setText("验证成功，正在发送验证码…");
        String js = "(function(){var p=document.getElementById('p_phone');if(p)p.value='" + phone + "';" +
                "var s=document.getElementById('sms_nav');if(s&&s.className.indexOf('on')<0)s.click();" +
                "if(window.sendsms){sendsms('" + id + "');return 'ok';}return 'no';})()";
        eval(js, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v != null && v.contains("no")) {
                    sliderFail("页面未就绪，请重试", true);
                } else {
                    sliderTips.setText("验证码发送中…");
                }
            }
        });
    }

    private void sliderFail(final String msg, final boolean autoRetry) {
        sliderSubmitting = false;
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (sliderOverlay == null || sliderOverlay.getVisibility() != View.VISIBLE) return;
                sliderTips.setTextColor(0xFFE53935);
                sliderTips.setText(msg);
                if (autoRetry) {
                    uiHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (sliderOverlay != null && sliderOverlay.getVisibility() == View.VISIBLE
                                    && !sliderSubmitting) requestCaptcha();
                        }
                    }, 1200);
                }
            }
        });
    }

    private void closeSlider() {
        if (sliderOverlay != null) sliderOverlay.setVisibility(View.GONE);
        sliderSubmitting = false;
    }

    private Bitmap loadBitmap(String urlStr) {
        if (urlStr != null && urlStr.startsWith("data:image")) {
            int idx = urlStr.indexOf("base64,");
            if (idx > 0) {
                try {
                    byte[] b = android.util.Base64.decode(
                            urlStr.substring(idx + 7), android.util.Base64.DEFAULT);
                    return BitmapFactory.decodeByteArray(b, 0, b.length);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        java.io.InputStream in = null;
        try {
            java.net.URL u = new java.net.URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
            in = c.getInputStream();
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    private String jsString(String v) {
        if (v == null) return "";
        String s = v;
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private void startCountdown() {
        countdownLeft = 60;
        getSmsBtn.setEnabled(false);
        getSmsBtn.setText("重新获取(60s)");
        if (countdownRunnable == null) {
            countdownRunnable = new Runnable() {
                @Override public void run() {
                    if (countdownLeft <= 0) {
                        getSmsBtn.setEnabled(true);
                        getSmsBtn.setText("获取验证码");
                        return;
                    }
                    getSmsBtn.setText("重新获取(" + countdownLeft + "s)");
                    countdownLeft--;
                    uiHandler.postDelayed(this, 1000);
                }
            };
        }
        uiHandler.postDelayed(countdownRunnable, 1000);
    }

    /* ================= 原生主界面（上半区压缩至约半屏） ================= */

    private void buildNativeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(4), dp(14), dp(16));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 头部：渐变卡片（压缩） ---------- */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(9));
        GradientDrawable hd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{PRIMARY, PRIMARY2});
        hd.setCornerRadius(dp(18));
        header.setBackground(hd);
        header.setElevation(dp(3));
        root.addView(header);

        // 行1：标签 + 刷新/退出
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(row1);

        TextView headLabel = new TextView(this);
        headLabel.setText("流量余量");
        headLabel.setTextSize(12);
        headLabel.setTextColor(0xCCFFFFFF);
        row1.addView(headLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setAllCaps(false);
        refreshBtn.setTextSize(11);
        refreshBtn.setTextColor(PRIMARY);
        refreshBtn.setBackground(pillBg(0xFFFFFFFF, dp(13)));
        refreshBtn.setPadding(dp(12), 0, dp(12), 0);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshFlow(); }
        });
        row1.addView(refreshBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));

        Button logoutBtn = new Button(this);
        logoutBtn.setText("退出");
        logoutBtn.setAllCaps(false);
        logoutBtn.setTextSize(11);
        logoutBtn.setTextColor(0xFFFFFFFF);
        logoutBtn.setBackground(strokePillBg(0x66FFFFFF, dp(13), 0xFFFFFFFF, 1));
        logoutBtn.setPadding(dp(12), 0, dp(12), 0);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("cookies").apply();
                pendingSms = false;
                pendingLogin = false;
                stopPoll();
                resetLoginBtns();
                web.loadUrl(LOGIN_URL);
                showLoginScreen();
            }
        });
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
        lb.leftMargin = dp(8);
        row1.addView(logoutBtn, lb);

        // 行2：剩余大字 + 右侧已消耗（压缩）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, dp(4), 0, 0);
        header.addView(row2);

        LinearLayout remainCol = new LinearLayout(this);
        remainCol.setOrientation(LinearLayout.HORIZONTAL);
        remainCol.setGravity(Gravity.BOTTOM);
        headerRemainNum = new TextView(this);
        headerRemainNum.setText("--");
        headerRemainNum.setTextSize(26);
        headerRemainNum.setTypeface(Typeface.DEFAULT_BOLD);
        headerRemainNum.setTextColor(0xFFFFFFFF);
        remainCol.addView(headerRemainNum);
        headerRemainUnit = new TextView(this);
        headerRemainUnit.setText("GB");
        headerRemainUnit.setTextSize(12);
        headerRemainUnit.setTextColor(0xCCFFFFFF);
        headerRemainUnit.setPadding(dp(3), 0, 0, dp(3));
        remainCol.addView(headerRemainUnit);
        row2.addView(remainCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout usedCol = new LinearLayout(this);
        usedCol.setOrientation(LinearLayout.VERTICAL);
        usedCol.setGravity(Gravity.RIGHT);
        TextView usedLab = new TextView(this);
        usedLab.setText("已消耗");
        usedLab.setTextSize(10);
        usedLab.setTextColor(0x99FFFFFF);
        usedCol.addView(usedLab);
        headerUsedTotal = new TextView(this);
        headerUsedTotal.setText("--");
        headerUsedTotal.setTextSize(13);
        headerUsedTotal.setTypeface(Typeface.DEFAULT_BOLD);
        headerUsedTotal.setTextColor(0xFFFFFFFF);
        usedCol.addView(headerUsedTotal);
        row2.addView(usedCol);

        // 行3：进度条
        headerBar = new RatioBar(this);
        headerBar.setColors(0x33FFFFFF, 0xFFFFFFFF);
        headerBar.setRatio(0f);
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        hb.topMargin = dp(6);
        header.addView(headerBar, hb);

        // 行4：上次刷新 + 流量详情
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.setGravity(Gravity.CENTER_VERTICAL);
        row4.setPadding(0, dp(4), 0, 0);
        header.addView(row4);

        statusLine = new TextView(this);
        statusLine.setText("正在连接…");
        statusLine.setTextSize(10);
        statusLine.setTextColor(0xAAFFFFFF);
        row4.addView(statusLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView detailLink = new TextView(this);
        detailLink.setText("流量详情 ▾");
        detailLink.setTextSize(11);
        detailLink.setTypeface(Typeface.DEFAULT_BOLD);
        detailLink.setTextColor(0xFFFFFFFF);
        detailLink.setPadding(dp(6), dp(1), dp(2), dp(1));
        detailLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleDetail(v); }
        });
        row4.addView(detailLink);

        /* ---------- 流量详情面板（默认收起） ---------- */
        detailPanel = new LinearLayout(this);
        detailPanel.setOrientation(LinearLayout.VERTICAL);
        detailPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleCard(detailPanel);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        detailPanel.setLayoutParams(dlp);
        detailPanel.setVisibility(View.GONE);
        root.addView(detailPanel);

        /* ---------- 余量汇总区（两列小卡） ---------- */
        summaryBox = new LinearLayout(this);
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(summaryBox);

        /* ---------- 消耗流量区 ---------- */
        root.addView(sectionTitle("消耗流量", PRIMARY));

        LinearLayout consumeCard = new LinearLayout(this);
        consumeCard.setOrientation(LinearLayout.VERTICAL);
        consumeCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        styleCard(consumeCard);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccp.topMargin = dp(2);
        root.addView(consumeCard, ccp);

        TextView spinnerHint = new TextView(this);
        spinnerHint.setText("选择测速源");
        spinnerHint.setTextSize(11);
        spinnerHint.setTextColor(FAINT);
        spinnerHint.setPadding(0, 0, 0, dp(4));
        consumeCard.addView(spinnerHint);

        /* 自定义测速源选择器（卡片样式 + 弹窗单选） */
        LinearLayout pickerRow = new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER_VERTICAL);
        pickerRow.setPadding(dp(14), 0, dp(14), 0);
        pickerRow.setBackground(strokeBg(0xFFF7F9FC, dp(12), LINE, 1));
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        consumeCard.addView(pickerRow, prp);

        sourcePicker = new TextView(this);
        sourcePicker.setTextSize(14);
        sourcePicker.setTextColor(INK);
        sourcePicker.setSingleLine(true);
        sourcePicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pickerRow.addView(sourcePicker, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextSize(16);
        arrow.setTextColor(SUB);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(8), 0, 0, 0);
        pickerRow.addView(arrow, new LinearLayout.LayoutParams(dp(28),
                ViewGroup.LayoutParams.MATCH_PARENT));

        final Runnable showSourceDialog = new Runnable() {
            @Override public void run() {
                if (sourceNames.isEmpty()) {
                    Toast.makeText(MainActivity.this, "暂无可选测速源", Toast.LENGTH_SHORT).show();
                    return;
                }
                final String[] items = sourceNames.toArray(new String[0]);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("选择测速源")
                        .setSingleChoiceItems(items, sourceIndex,
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        sourceIndex = which;
                                        updateSourcePicker();
                                        d.dismiss();
                                    }
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }
        };
        pickerRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSourceDialog.run(); }
        });
        pickerRow.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showSourceDialog.run(); return true; }
        });
        applySourceList(sources);

        startBtn = new Button(this);
        startBtn.setText("开始消耗");
        startBtn.setAllCaps(false);
        startBtn.setTextSize(17);
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setTextColor(0xFFFFFFFF);
        startBtn.setBackground(rippleBg(pillBg(PRIMARY, dp(27))));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConsume(); }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        sbp.topMargin = dp(10);
        consumeCard.addView(startBtn, sbp);

        wasteText = new TextView(this);
        wasteText.setText("已消耗 0.00 B");
        wasteText.setTextSize(24);
        wasteText.setTypeface(Typeface.DEFAULT_BOLD);
        wasteText.setTextColor(INK);
        wasteText.setGravity(Gravity.CENTER);
        wasteText.setPadding(0, dp(14), 0, dp(2));
        consumeCard.addView(wasteText);

        Button resetBtn = new Button(this);
        resetBtn.setText("重置计数");
        resetBtn.setAllCaps(false);
        resetBtn.setTextSize(12);
        resetBtn.setTextColor(INK);
        resetBtn.setBackground(rippleBg(pillBg(0xFFE8EDF4, dp(16))));
        resetBtn.setPadding(dp(18), 0, dp(18), 0);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                wasted.set(0);
                wasteText.setText("已消耗 0.00 B");
                Toast.makeText(MainActivity.this, "已重置", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        rbp.gravity = Gravity.CENTER_HORIZONTAL;
        rbp.topMargin = dp(2);
        consumeCard.addView(resetBtn, rbp);

        LinearLayout thRow = new LinearLayout(this);
        thRow.setOrientation(LinearLayout.HORIZONTAL);
        thRow.setGravity(Gravity.CENTER_VERTICAL);
        thRow.setPadding(0, dp(10), 0, 0);
        TextView thLabel = new TextView(this);
        thLabel.setText("自动停止阈值");
        thLabel.setTextSize(14);
        thLabel.setTextColor(SUB);
        thRow.addView(thLabel);
        thresholdInput = new EditText(this);
        thresholdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        thresholdInput.setHint("0");
        thresholdInput.setTextSize(14);
        thresholdInput.setGravity(Gravity.CENTER);
        thresholdInput.setTextColor(INK);
        thresholdInput.setHintTextColor(FAINT);
        thresholdInput.setBackground(strokeBg(0xFFFFFFFF, dp(10), LINE, 1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(110), dp(42));
        tlp.leftMargin = dp(8);
        thRow.addView(thresholdInput, tlp);
        TextView thUnit = new TextView(this);
        thUnit.setText("GB");
        thUnit.setTextSize(14);
        thUnit.setTextColor(SUB);
        thUnit.setPadding(dp(6), 0, 0, 0);
        thRow.addView(thUnit);
        consumeCard.addView(thRow);

        ipText = new TextView(this);
        ipText.setText("查询IP中…");
        ipText.setTextSize(12);
        ipText.setTextColor(FAINT);
        ipText.setGravity(Gravity.CENTER);
        ipText.setPadding(0, dp(12), 0, 0);
        consumeCard.addView(ipText);

        /* ---------- 流量领取区 ---------- */
        root.addView(sectionTitle("流量领取", ORANGE));

        LinearLayout claimsBox = new LinearLayout(this);
        claimsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(claimsBox);
        claimsContainer = claimsBox;
        for (String[] c : defaultClaims) { addClaim(claimsBox, c[0], c[1], c[2]); }

        container.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeScreen = scroll;
        nativeScreen.setVisibility(View.GONE);
    }

    private LinearLayout claimsContainer;

    /* ---------- UI 工具 ---------- */

    private GradientDrawable pillBg(int color, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private GradientDrawable strokePillBg(int color, float radius, int strokeColor, int strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        gd.setStroke(strokeW, strokeColor);
        return gd;
    }

    private GradientDrawable strokeBg(int color, float radius, int strokeColor, int strokeW) {
        return strokePillBg(color, radius, strokeColor, strokeW);
    }

    private RippleDrawable rippleBg(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x1A000000), content, null);
    }

    private void styleCard(View v) {
        v.setBackground(rippleBg(pillBg(CARD, dp(16))));
        v.setElevation(dp(2));
    }

    private View sectionTitle(String text, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(5));

        View bar = new View(this);
        bar.setBackground(pillBg(color, dp(2)));
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(14)));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(INK);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private TextView iconBadge(int color, String glyph) {
        TextView v = new TextView(this);
        v.setText(glyph);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFFFFFFF);
        v.setGravity(Gravity.CENTER);
        v.setBackground(pillBg(color, dp(9)));
        return v;
    }

    /* ================= 流量详情面板 ================= */

    private void toggleDetail(View link) {
        detailOpen = !detailOpen;
        if (detailOpen) {
            detailPanel.removeAllViews();
            if (lastItems.isEmpty()) {
                TextView e = new TextView(this);
                e.setText("暂无数据");
                e.setTextSize(12);
                e.setTextColor(FAINT);
                e.setPadding(0, dp(4), 0, dp(4));
                detailPanel.addView(e);
            } else {
                for (FlowItem it : lastItems) {
                    String cat = (it.cat == null || it.cat.isEmpty()) ? "" : " · " + it.cat;
                    TextView line = new TextView(this);
                    line.setText(it.name + cat);
                    line.setTextSize(13);
                    line.setTextColor(INK);
                    line.setTypeface(Typeface.DEFAULT_BOLD);
                    detailPanel.addView(line);
                    TextView sub = new TextView(this);
                    String used = (it.used == null || it.used.isEmpty()) ? "—" : it.used;
                    String remain = (it.remain == null || it.remain.isEmpty()) ? "—" : it.remain;
                    String total = (it.total == null || it.total.isEmpty()) ? "—" : it.total;
                    sub.setText("已用 " + used + "　剩余 " + remain + "　总量 " + total);
                    sub.setTextSize(11);
                    sub.setTextColor(SUB);
                    sub.setPadding(0, dp(1), 0, dp(8));
                    detailPanel.addView(sub);
                }
            }
            detailPanel.setVisibility(View.VISIBLE);
            ((TextView) link).setText("流量详情 ▴");
        } else {
            detailPanel.setVisibility(View.GONE);
            ((TextView) link).setText("流量详情 ▾");
        }
    }

    /* ================= 余量刷新（隐藏 WebView 后台执行） ================= */

    private void refreshFlow() {
        statusLine.setText("正在刷新流量余量…");
        web.setVisibility(View.INVISIBLE);
        nativeScreen.setVisibility(View.VISIBLE);
        web.loadUrl(FLOW_URL);
    }

    /* ================= 数据提取 ================= */

    private static final String EXTRACT_JS =
            "(function(){" +
            "var out=[];" +
            "var lis=document.querySelectorAll('li.fn-li-plan');" +
            "for(var i=0;i<lis.length;i++){" +
            "var li=lis[i];" +
            "var n=li.querySelector('.fn-big-tit');" +
            "var c=li.querySelector('.fn-sm-tit');" +
            "var f=li.querySelectorAll('.ll-flux');" +
            "var name=n?n.textContent.replace(/^\\s+|\\s+$/g,''):'';" +
            "var cat=c?c.textContent.replace(/^\\s+|\\s+$/g,''):'';" +
            "var used=f.length>0?f[0].textContent.replace(/^\\s+|\\s+$/g,''):'';" +
            "var remain=f.length>1?f[1].textContent.replace(/^\\s+|\\s+$/g,''):'';" +
            "var total='';" +
            "var top=li.querySelector('.fn-top');" +
            "if(top){var m=top.textContent.match(/总量:\\s*([\\d.]+)\\s*(KB|MB|GB)/i);if(m)total=m[1]+m[2];}" +
            "if(name||cat)out.push({name:name,cat:cat,used:used,remain:remain,total:total});" +
            "}" +
            "return JSON.stringify(out);" +
            "})()";

    private void scheduleExtract() {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() { extractData(); }
        }, 1200);
    }

    private void extractData() {
        if (web == null) return;
        web.evaluateJavascript(EXTRACT_JS, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                try {
                    String json = (value == null) ? "[]" : value;
                    if (json.startsWith("\"") && json.endsWith("\"")) {
                        json = json.substring(1, json.length() - 1)
                                .replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    JSONArray arr = new JSONArray(json);
                    if (arr.length() > 0) {
                        renderResult(arr);
                    } else if (extractTries < 5) {
                        extractTries++;
                        uiHandler.postDelayed(new Runnable() {
                            @Override public void run() { extractData(); }
                        }, 1500);
                    } else if (autoReloads < 2) {
                        autoReloads++;
                        web.loadUrl(FLOW_URL);
                    } else {
                        statusLine.setText("未获取到数据，请点「刷新」重试");
                    }
                } catch (Exception e) {
                    if (extractTries < 5) {
                        extractTries++;
                        uiHandler.postDelayed(new Runnable() {
                            @Override public void run() { extractData(); }
                        }, 1500);
                    } else {
                        statusLine.setText("解析失败，请点「刷新」重试");
                    }
                }
            }
        });
    }

    /* ================= 汇总与展示 ================= */

    static class FlowItem { String name, cat, used, remain, total; }
    static class GroupSum {
        String key; double totalGB, remainGB, usedGB; int count; List<FlowItem> items = new ArrayList<>();
    }

    private void renderResult(JSONArray arr) {
        lastItems.clear();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                FlowItem it = new FlowItem();
                it.name = o.optString("name", "");
                it.cat = o.optString("cat", "");
                it.used = o.optString("used", "");
                it.remain = o.optString("remain", "");
                it.total = o.optString("total", "");
                lastItems.add(it);
            } catch (Exception ignored) {}
        }

        LinkedHashMap<String, GroupSum> groups = new LinkedHashMap<>();
        for (FlowItem it : lastItems) {
            String key;
            if (it.cat != null && it.cat.contains("通用")) key = "通用流量";
            else if ("APP专属流量".equals(it.cat)) {
                if (it.name != null && it.name.contains("咪咕视频")) key = "咪咕视频";
                else if (it.name != null && it.name.contains("云盘")) key = "移动云盘";
                else if (it.name != null && it.name.contains("咪咕快游")) key = "咪咕快游";
                else key = "其他APP专属";
            } else key = "其他流量";
            GroupSum g = groups.get(key);
            if (g == null) { g = new GroupSum(); g.key = key; groups.put(key, g); }
            g.totalGB += toGB(it.total);
            g.remainGB += toGB(it.remain);
            g.usedGB += toGB(it.used);
            g.count++;
            g.items.add(it);
        }

        hideLoading();
        showNative();
        summaryBox.removeAllViews();

        double allTotal = 0, allRemain = 0, allUsed = 0;
        for (GroupSum g : groups.values()) { allTotal += g.totalGB; allRemain += g.remainGB; allUsed += g.usedGB; }

        // 头部 hero
        String[] hero = splitNumUnit(allRemain);
        headerRemainNum.setText(hero[0]);
        headerRemainUnit.setText(hero[1]);
        headerUsedTotal.setText(fmt(allUsed));
        headerBar.setRatio(allTotal > 0 ? (float) (allRemain / allTotal) : 0f);

        // 总合计卡（全宽，压缩）
        summaryBox.addView(makeWideCard("总合计", "合", PRIMARY,
                "已消耗 " + fmt(allUsed) + " · " + groups.size() + " 类流量包",
                allTotal, allRemain, PRIMARY));

        // 两列小卡
        List<View> mini = new ArrayList<>();
        GroupSum general = groups.get("通用流量");
        if (general != null) {
            mini.add(makeMiniCard("通用流量", "通", GREEN, general.count + "包",
                    general.totalGB, general.remainGB, general.items, GREEN));
        }

        double appTotal = 0, appRemain = 0;
        int appCount = 0;
        for (Map.Entry<String, GroupSum> e : groups.entrySet())
            if (!"通用流量".equals(e.getKey())) { appTotal += e.getValue().totalGB; appRemain += e.getValue().remainGB; appCount += e.getValue().count; }
        if (appCount > 0) {
            mini.add(makeMiniCard("APP专属", "专", ORANGE, appCount + "个定向包",
                    appTotal, appRemain, null, ORANGE));
        }
        for (Map.Entry<String, GroupSum> e : groups.entrySet()) {
            String key = e.getKey();
            if ("通用流量".equals(key)) continue;
            int color = SLATE;
            String glyph = "专";
            if (key.contains("咪咕视频")) { color = RED; glyph = "视"; }
            else if (key.contains("云盘")) { color = ORANGE; glyph = "盘"; }
            else if (key.contains("快游")) { color = PURPLE; glyph = "游"; }
            String shortName = key;
            if (key.contains("其他APP")) shortName = "其他定向";
            mini.add(makeMiniCard(shortName, glyph, color, e.getValue().count + "包",
                    e.getValue().totalGB, e.getValue().remainGB, e.getValue().items, color));
        }
        for (int i = 0; i < mini.size(); i += 2) {
            View a = mini.get(i);
            View b = (i + 1 < mini.size()) ? mini.get(i + 1) : null;
            addMiniRow(summaryBox, a, b);
        }

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA);
        statusLine.setText("上次刷新 " + fmt.format(new java.util.Date()));
        if (detailOpen) { detailPanel.setVisibility(View.GONE); detailOpen = false; }
    }

    private String[] splitNumUnit(double gb) {
        if (gb >= 1024) return new String[]{String.format(java.util.Locale.CHINA, "%.2f", gb / 1024), "TB"};
        if (gb >= 1) return new String[]{String.format(java.util.Locale.CHINA, "%.2f", gb), "GB"};
        if (gb >= 0.001) return new String[]{String.format(java.util.Locale.CHINA, "%.1f", gb * 1024), "MB"};
        return new String[]{"0", "MB"};
    }

    private double toGB(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("([\\d.]+)\\s*(KB|MB|GB)", Pattern.CASE_INSENSITIVE).matcher(s.trim());
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(1));
        String u = m.group(2).toUpperCase();
        if (u.equals("KB")) return v / 1024 / 1024;
        if (u.equals("MB")) return v / 1024;
        return v;
    }

    private String fmt(double gb) {
        if (gb >= 1024) return String.format(java.util.Locale.CHINA, "%.2f TB", gb / 1024);
        if (gb >= 1) return String.format(java.util.Locale.CHINA, "%.2f GB", gb);
        if (gb >= 0.001) return String.format(java.util.Locale.CHINA, "%.1f MB", gb * 1024);
        return "0 MB";
    }

    /** 全宽合计卡（压缩版） */
    private View makeWideCard(String title, String glyph, int color, String sub,
                              double total, double remain, int barColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleCard(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head);

        TextView icon = iconBadge(color, glyph);
        head.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(8), 0, 0, 0);
        head.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(INK);
        mid.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(10);
        st.setTextColor(FAINT);
        st.setPadding(0, dp(1), 0, 0);
        mid.addView(st);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT);
        head.addView(right);

        LinearLayout remRow = new LinearLayout(this);
        remRow.setOrientation(LinearLayout.HORIZONTAL);
        remRow.setGravity(Gravity.BOTTOM);
        TextView rem = new TextView(this);
        rem.setText(fmt(remain));
        rem.setTextSize(14);
        rem.setTypeface(Typeface.DEFAULT_BOLD);
        rem.setTextColor(INK);
        remRow.addView(rem);
        TextView lab1 = new TextView(this);
        lab1.setText(" 剩余");
        lab1.setTextSize(9);
        lab1.setTextColor(FAINT);
        lab1.setPadding(0, 0, 0, dp(2));
        remRow.addView(lab1);
        right.addView(remRow);

        TextView tot = new TextView(this);
        tot.setText("总量 " + fmt(total));
        tot.setTextSize(9);
        tot.setTextColor(FAINT);
        tot.setGravity(Gravity.RIGHT);
        right.addView(tot);

        RatioBar bar = new RatioBar(this);
        bar.setColors(0xFFEDF1F7, barColor);
        bar.setRatio(total > 0 ? (float) (remain / total) : 0f);
        LinearLayout.LayoutParams barlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        barlp.topMargin = dp(6);
        card.addView(bar, barlp);
        return card;
    }

    /** 两列小卡（压缩版） */
    private View makeMiniCard(String title, String glyph, int color, String sub,
                              double total, double remain, final List<FlowItem> items, int barColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(9), dp(8), dp(9), dp(8));
        styleCard(card);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head);

        TextView icon = iconBadge(color, glyph);
        head.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, 0, 0);
        head.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(INK);
        mid.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(9);
        st.setTextColor(FAINT);
        st.setPadding(0, dp(1), 0, 0);
        mid.addView(st);

        final TextView arrow = new TextView(this);
        arrow.setText(items != null ? "▾" : "");
        arrow.setTextSize(11);
        arrow.setTextColor(FAINT);
        head.addView(arrow);

        TextView rem = new TextView(this);
        rem.setText(fmt(remain));
        rem.setTextSize(14);
        rem.setTypeface(Typeface.DEFAULT_BOLD);
        rem.setTextColor(INK);
        rem.setPadding(0, dp(5), 0, 0);
        card.addView(rem);

        TextView tot = new TextView(this);
        tot.setText("总量 " + fmt(total));
        tot.setTextSize(9);
        tot.setTextColor(FAINT);
        tot.setPadding(0, dp(1), 0, 0);
        card.addView(tot);

        RatioBar bar = new RatioBar(this);
        bar.setColors(0xFFEDF1F7, barColor);
        bar.setRatio(total > 0 ? (float) (remain / total) : 0f);
        LinearLayout.LayoutParams barlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barlp.topMargin = dp(5);
        card.addView(bar, barlp);

        if (items != null) {
            final LinearLayout det = new LinearLayout(this);
            det.setOrientation(LinearLayout.VERTICAL);
            det.setPadding(0, dp(5), 0, 0);
            det.setVisibility(View.GONE);
            for (FlowItem it : items) {
                TextView line = new TextView(this);
                line.setText(it.name);
                line.setTextSize(10);
                line.setTypeface(Typeface.DEFAULT_BOLD);
                line.setTextColor(INK);
                det.addView(line);
                TextView s2 = new TextView(this);
                String remainStr = (it.remain == null || it.remain.isEmpty()) ? "—" : it.remain;
                String totalStr = (it.total == null || it.total.isEmpty()) ? "—" : it.total;
                s2.setText("剩余 " + remainStr + " · " + totalStr);
                s2.setTextSize(9);
                s2.setTextColor(SUB);
                s2.setPadding(0, dp(1), 0, dp(4));
                det.addView(s2);
            }
            card.addView(det);

            head.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = det.getVisibility() == View.VISIBLE;
                    det.setVisibility(open ? View.GONE : View.VISIBLE);
                    arrow.setText(open ? "▾" : "▴");
                }
            });
        }
        return card;
    }

    /** 把两张小卡放进一行（左右两列） */
    private void addMiniRow(LinearLayout box, View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        box.addView(row, rlp);

        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        la.rightMargin = dp(4);
        row.addView(a, la);

        if (b != null) {
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lb.leftMargin = dp(4);
            row.addView(b, lb);
        }
    }

    /* ================= 流量消耗（原生多线程） ================= */

    private void toggleConsume() {
        if (running.get()) stopConsume();
        else startConsume();
    }

    private void startConsume() {
        int pos = sourceIndex;
        if (sources.isEmpty() || pos < 0 || pos >= sources.size()) {
            Toast.makeText(this, "无可用测速源", Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = sources.get(pos)[1];
        if (url == null || url.isEmpty()) { Toast.makeText(this, "测速源无效", Toast.LENGTH_SHORT).show(); return; }

        running.set(true);
        wasted.set(0);
        startBtn.setText("停止");
        startBtn.setBackground(rippleBg(pillBg(RED, dp(27))));
        wasteText.setText("已消耗 0.00 B");

        for (int i = 0; i < 4; i++) {
            new Thread(new Runnable() {
                @Override public void run() { consumeLoop(url); }
            }).start();
        }

        stopTicker();
        ticker = new Runnable() {
            @Override public void run() {
                if (!running.get()) return;
                wasteText.setText("已消耗 " + formatSize(wasted.get()));
                String t = thresholdInput.getText().toString().trim();
                if (!t.isEmpty()) {
                    try {
                        double gb = Double.parseDouble(t);
                        if (gb > 0 && wasted.get() >= gb * 1024 * 1024 * 1024) {
                            stopConsume();
                            Toast.makeText(MainActivity.this, "已达阈值，自动停止", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.postDelayed(ticker, 1000);
        fetchIP();
    }

    private void consumeLoop(String base) {
        byte[] buf = new byte[65536];
        while (running.get()) {
            HttpURLConnection c = null;
            try {
                String sep = base.contains("?") ? "&" : "?";
                URL u = new URL(base + sep + "r=" + Math.random());
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Cache-Control", "no-store");
                c.setRequestProperty("Connection", "keep-alive");
                c.connect();
                activeConn.add(c);
                InputStream in = c.getInputStream();
                int n;
                while (running.get() && (n = in.read(buf)) > 0) {
                    wasted.addAndGet(n);
                }
                in.close();
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    activeConn.remove(c);
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void stopConsume() {
        if (!running.getAndSet(false)) return;
        for (HttpURLConnection c : activeConn) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
        activeConn.clear();
        stopTicker();
        startBtn.setText("开始消耗");
        startBtn.setBackground(rippleBg(pillBg(PRIMARY, dp(27))));
        wasteText.setText("已消耗 " + formatSize(wasted.get()));
    }

    private void stopTicker() {
        if (ticker != null) uiHandler.removeCallbacks(ticker);
        ticker = null;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return String.format(java.util.Locale.CHINA, "%.2f B", (double) bytes);
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024);
        return String.format(java.util.Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /* ================= 外部配置 + IP ================= */

    private void fetchConfig() {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject o = null;
                String[] urls = {CONFIG_URL, CONFIG_URL.replace("https://", "http://")};
                for (String u : urls) {
                    try {
                        URL url = new URL(u);
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        c.setRequestProperty("User-Agent", UA);
                        c.connect();
                        InputStream in = c.getInputStream();
                        StringBuilder sb = new StringBuilder();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                        in.close();
                        c.disconnect();
                        o = new JSONObject(sb.toString());
                        break;
                    } catch (Exception ignored) {}
                }
                if (o == null) return;
                try {
                    final List<String[]> list = new ArrayList<>();
                    JSONArray ss = o.optJSONArray("speed_sources");
                    if (ss != null) for (int i = 0; i < ss.length(); i++) {
                        JSONObject s = ss.getJSONObject(i);
                        list.add(new String[]{s.optString("name"), s.optString("url"), s.optString("type", "")});
                    }
                    final List<String[]> claims = new ArrayList<>();
                    JSONArray cg = o.optJSONArray("claim_groups");
                    if (cg != null) for (int i = 0; i < cg.length(); i++) {
                        JSONObject g = cg.getJSONObject(i);
                        JSONArray links = g.optJSONArray("links");
                        if (links != null) for (int j = 0; j < links.length(); j++) {
                            JSONObject l = links.getJSONObject(j);
                            claims.add(new String[]{g.optString("title"), l.optString("name"), l.optString("url")});
                        }
                    }
                    final List<String[]> src = list;
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            if (!src.isEmpty()) applySourceList(src);
                            if (!claims.isEmpty() && claimsContainer != null) {
                                claimsContainer.removeAllViews();
                                for (String[] c : claims) addClaim(claimsContainer, c[0], c[1], c[2]);
                            }
                            Toast.makeText(MainActivity.this, "已加载外部配置", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void applySourceList(List<String[]> list) {
        if (list == null) list = sources;
        sources = list;
        sourceNames.clear();
        for (String[] s : list) {
            String label = s[0];
            if (s.length > 2 && s[2] != null && !s[2].isEmpty()) label += "（" + s[2] + "）";
            sourceNames.add(label);
        }
        if (sourceIndex >= sourceNames.size()) sourceIndex = 0;
        updateSourcePicker();
    }

    private void updateSourcePicker() {
        if (sourcePicker != null && !sourceNames.isEmpty()
                && sourceIndex >= 0 && sourceIndex < sourceNames.size()) {
            sourcePicker.setText(sourceNames.get(sourceIndex));
        }
    }

    private void addClaim(LinearLayout box, String group, String name, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        styleCard(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        View dot = new View(this);
        dot.setBackground(pillBg(ORANGE, dp(3)));
        row.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, 0, 0);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView g = new TextView(this);
        g.setText(group);
        g.setTextSize(10);
        g.setTextColor(FAINT);
        mid.addView(g);
        TextView l = new TextView(this);
        l.setText(name);
        l.setTextSize(14);
        l.setTextColor(INK);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(0, dp(1), 0, 0);
        mid.addView(l);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(FAINT);
        row.addView(chevron);

        final String target = url;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                }
            }
        });
        box.addView(row);
    }

    private void fetchIP() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL u = new URL("https://api.leqw.top/api/ip");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", UA);
                    c.connect();
                    InputStream in = c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    in.close();
                    c.disconnect();
                    final JSONObject o = new JSONObject(sb.toString());
                    final String ip = o.optJSONObject("data") != null
                            ? o.optJSONObject("data").optString("ip") : "";
                    final String area = o.optString("area", "");
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            ipText.setText("当前IP: " + ip + "（" + area + "）");
                        }
                    });
                } catch (Exception e) {
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            ipText.setText("IP查询失败");
                        }
                    });
                }
            }
        }).start();
    }

    /* ================= 进度条 ================= */

    static class RatioBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float ratio = 0f;

        public RatioBar(android.content.Context c) {
            super(c);
        }

        void setColors(int trackColor, int fillColor) {
            trackPaint.setColor(trackColor);
            fillPaint.setColor(fillColor);
        }

        void setRatio(float r) {
            ratio = Math.max(0f, Math.min(1f, r));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float h = getHeight();
            float w = getWidth();
            float r = h / 2f;
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, trackPaint);
            float fw = w * ratio;
            if (fw > r * 2) cv.drawRoundRect(new RectF(0, 0, fw, h), r, r, fillPaint);
        }
    }

    /* ================= 登录态 Cookie 备份/恢复 ================= */

    private static final String PREFS = "flow_cookies";

    /** 把当前登录 Cookie 备份到 SharedPreferences，防 WebView 数据被系统清理 */
    private void saveCookies() {
        try {
            String all = CookieManager.getInstance().getCookie("https://touch.10086.cn");
            if (all == null || all.isEmpty()) return;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString("cookies", all).apply();
        } catch (Exception ignored) {}
    }

    /** 启动时用备份恢复登录态 */
    private void restoreCookies() {
        try {
            String all = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("cookies", "");
            if (all.isEmpty()) return;
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String[] pairs = all.split(";");
            String[] hosts = {"https://touch.10086.cn", "https://login.10086.cn",
                    "https://wap.10086.cn", "http://wap.10086.cn",
                    "https://www.10086.cn", "https://shop.10086.cn"};
            for (String p : pairs) {
                p = p.trim();
                if (p.isEmpty()) continue;
                for (String h : hosts) {
                    try { cm.setCookie(h, p); } catch (Exception ignored) {}
                }
            }
            cm.flush();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        try {
            saveCookies();
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {}
        super.onPause();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        stopConsume();
        stopPoll();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
