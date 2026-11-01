package com.cmcc.flowapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
 * 流量查询助手 v1.9.5
 * - 原生短信登录与流量余量展示
 * - 隐藏 WebView 仅访问指定余量查询入口，并通过页面 DOM 状态读取数据
 * - 消耗流量上方区域（头部+汇总卡）压缩至约半屏
 */
public class MainActivity extends Activity {

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
    private EditText phoneInput, smsInput;
    private Button getSmsBtn, loginBtn;
    private TextView loginErrorText;
    private final boolean[] remember = {true};
    private boolean pendingSms = false, pendingLogin = false;
    private boolean smsCommanded = false, loginCommanded = false;
    private boolean smsCountdownActive = false;
    private int latestSmsSeconds = 0;
    private int partialProbeCount = 0;
    private boolean partialRendered = false;
    private long probeDeadline = 0L;
    private int probeGeneration = 0;
    private Runnable probeRunnable;

    /* 过渡加载层 */
    private FrameLayout loadingOverlay;
    private TextView loadingText;

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

    /* ---------- 数据探测 ---------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(PRIMARY);
            if (android.os.Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
        }
        container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        setContentView(container);
        // 删除历史版本保存的跨域 Cookie 副本；WebView 自身 Cookie 由系统持久化。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("cookies").apply();
        buildDefaults();
        buildWebView();
        buildNativeScreen();
        buildLoginScreen();
        buildLoadingOverlay();
        fetchConfig();
        showLoginScreen();
        loadEntry("正在打开流量查询…", false);
    }

    @Override
    public void onBackPressed() {
        if (pendingSms || pendingLogin) {
            pendingSms = false;
            pendingLogin = false;
            smsCommanded = false;
            loginCommanded = false;
            stopProbe();
            resetLoginBtns();
            showLoginScreen();
            return;
        }
        super.onBackPressed();
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

    /* ================= WebView（仅加载流量查询入口，后台隐藏） ================= */

    private void buildWebView() {
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isEntryUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return false;
                return !isEntryUrl(request.getUrl() == null ? null : request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isEntryUrl(url)) return;
                scheduleProbe(probeGeneration, 100);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame() || !isEntryUrl(request.getUrl().toString())) return;
                int code = error == null ? 0 : error.getErrorCode();
                finishProbeFailure("流量页面加载失败(" + code + ")，请检查网络后重试");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request == null || !request.isForMainFrame() || !isEntryUrl(request.getUrl().toString())) return;
                int code = response == null ? 0 : response.getStatusCode();
                finishProbeFailure("流量页面返回错误(" + code + ")，请稍后重试");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (error != null && isEntryUrl(error.getUrl()))
                    finishProbeFailure("安全连接失败，请检查系统时间与网络后重试");
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean isEntryUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "wx.10086.cn".equalsIgnoreCase(uri.getHost())
                    && "/cmcc-service/app-pages/main/marginQuery".equals(uri.getPath())
                    && (uri.getQuery() == null || uri.getQuery().isEmpty());
        } catch (Exception ignored) { return false; }
    }

    private void loadEntry(String message, boolean showProgress) {
        stopProbe();
        probeGeneration++;
        pendingSms = false;
        pendingLogin = false;
        smsCommanded = false;
        loginCommanded = false;
        latestSmsSeconds = 0;
        partialProbeCount = 0;
        partialRendered = false;
        if (countdownRunnable != null) uiHandler.removeCallbacks(countdownRunnable);
        countdownLeft = 0;
        smsCountdownActive = false;
        probeDeadline = System.currentTimeMillis() + 60000L;
        if (showProgress) showLoading(message);
        else hideLoading();
        if (web != null) web.loadUrl(MarginPageBridge.ENTRY_URL);
    }

    private void startProbeWindow() {
        probeDeadline = System.currentTimeMillis() + 60000L;
        scheduleProbe(probeGeneration, 0);
    }

    private void scheduleProbe(final int generation, long delayMs) {
        if (generation != probeGeneration || web == null) return;
        if (probeRunnable != null) uiHandler.removeCallbacks(probeRunnable);
        probeRunnable = new Runnable() {
            @Override public void run() { runProbe(generation); }
        };
        uiHandler.postDelayed(probeRunnable, delayMs);
    }

    private void stopProbe() {
        if (probeRunnable != null) uiHandler.removeCallbacks(probeRunnable);
        probeRunnable = null;
        probeGeneration++;
    }

    private void runProbe(final int generation) {
        if (generation != probeGeneration || web == null) return;
        if (System.currentTimeMillis() >= probeDeadline) {
            if (!pendingSms && !pendingLogin && smsCommanded) {
                stopProbe();
                latestSmsSeconds = countdownLeft = 0;
                smsCountdownActive = false;
                if (countdownRunnable != null) uiHandler.removeCallbacks(countdownRunnable);
                resetLoginBtns();
                showLoginScreen();
                loginError("官网60秒提交窗口已结束，请重新获取验证码");
                return;
            }
            finishProbeFailure("等待页面或登录操作超时，请重试");
            return;
        }
        web.evaluateJavascript(MarginPageBridge.PROBE_JS, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String raw) {
                if (generation != probeGeneration || web == null) return;
                try {
                    JSONObject result = new JSONObject(decodeEvalString(raw));
                    handleProbeResult(result, generation);
                } catch (Exception e) {
                    scheduleProbe(generation, 1200);
                }
            }
        });
    }

    private void handleProbeResult(JSONObject result, int generation) {
        String state = result.optString("state", "loading");
        boolean authenticated = result.optBoolean("authenticated", false);
        int smsSeconds = Math.max(0, result.optInt("smsSeconds", 0));
        boolean smsSending = result.optBoolean("smsSending", false);
        String pageError = result.optString("error", "").trim();
        latestSmsSeconds = smsSeconds;
        if (smsSeconds > 0) syncSmsCountdown(smsSeconds);
        if (authenticated) {
            pendingSms = pendingLogin = false;
            smsCommanded = loginCommanded = false;
            if (smsInput != null && smsInput.length() > 0) smsInput.setText("");
        }
        if (pendingSms && smsSeconds > 0) {
            probeDeadline = System.currentTimeMillis() + 60000L;
            pendingSms = false;
            loginError("验证码已发送，请在倒计时结束前输入并登录");
            showLoginScreen();
        }

        if ("ready".equals(state)) {
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) {
                finishProbeFailure("已登录，但页面没有返回可显示的流量项目");
                return;
            }
            pendingSms = pendingLogin = false;
            if (result.optBoolean("partial", false) && partialProbeCount < 4) {
                partialProbeCount++;
                if (!partialRendered) {
                    renderResult(items);
                    partialRendered = true;
                }
                if (statusLine != null) statusLine.setText("流量数据已加载，附加项目仍在读取…");
                scheduleProbe(generation, 1500);
            } else {
                stopProbe();
                renderResult(items);
                if (result.optBoolean("partial", false) && statusLine != null)
                    statusLine.setText("已显示当前可用流量，部分附加项目仍未加载");
            }
            return;
        }
        if ("empty".equals(state)) {
            finishProbeFailure(pageError.isEmpty() ? "当前没有可显示的流量数据" : pageError);
            return;
        }
        if ("error".equals(state)) {
            finishProbeFailure(pageError.isEmpty() ? "流量页面暂时无法读取，请稍后重试" : pageError);
            return;
        }
        if (authenticated || "loading".equals(state)) {
            if (authenticated) showLoading("登录成功，正在加载流量数据…");
            else if (pendingLogin) showLoading("正在验证短信随机码…");
            else showLoading("正在加载流量数据…");
            scheduleProbe(generation, 1200);
            return;
        }
        if ("login".equals(state)) {
            if (((pendingSms && smsCommanded) || pendingLogin) && !smsSending
                    && !pageError.isEmpty() && !pageError.contains("成功") && !pageError.contains("已发送")) {
                finishProbeFailure(pageError);
                return;
            }
            if (pendingSms && !smsCommanded) {
                smsCommanded = true;
                getSmsBtn.setEnabled(false);
                loginError("正在向页面提交短信验证码请求…");
                runBridgeCommand(MarginPageBridge.requestSms(phoneInput.getText().toString().trim()), true);
                scheduleProbe(generation, 1200);
                return;
            }
            if (pendingLogin) {
                showLoading("正在验证短信随机码…");
                scheduleProbe(generation, 1200);
                return;
            }
            showLoginScreen();
            if (pendingSms && smsSending) loginError("正在发送短信验证码，请稍候…");
            if (pendingSms || smsCountdownActive || smsSending) scheduleProbe(generation, 1200);
            else stopProbe();
            return;
        }
        scheduleProbe(generation, 1200);
    }

    private void runBridgeCommand(String expression, final boolean smsRequest) {
        if (web == null) return;
        final int commandGeneration = probeGeneration;
        web.evaluateJavascript(expression, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String raw) {
                if (web == null || commandGeneration != probeGeneration) return;
                try {
                    JSONObject reply = new JSONObject(decodeEvalString(raw));
                    if (!reply.optBoolean("ok", false)) {
                        String msg = reply.optString("error", "请求未成功，请检查页面后重试");
                        if (smsRequest) {
                            pendingSms = false;
                            smsCommanded = false;
                            stopProbe();
                            resetLoginBtns();
                            showLoginScreen();
                            loginError(msg);
                        } else {
                            pendingLogin = false;
                            loginCommanded = false;
                            stopProbe();
                            resetLoginBtns();
                            showLoginScreen();
                            loginError(msg);
                        }
                    }
                } catch (Exception e) {
                    // Keep probing for the state transition; the command is never repeated automatically.
                }
            }
        });
    }

    private String decodeEvalString(String raw) throws Exception {
        if (raw == null || "null".equals(raw)) return "";
        JSONArray wrapper = new JSONArray("[" + raw + "]");
        Object value = wrapper.get(0);
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private void finishProbeFailure(String message) {
        boolean authOperation = pendingSms || pendingLogin;
        stopProbe();
        pendingSms = pendingLogin = false;
        smsCommanded = loginCommanded = false;
        resetLoginBtns();
        hideLoading();
        if (authOperation) {
            showLoginScreen();
            loginError(message);
            return;
        }
        showNative();
        if (statusLine != null) {
            statusLine.setText(lastItems != null && !lastItems.isEmpty()
                    ? message + "；当前显示的是上次成功数据" : message);
        }
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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        t2.setText("通过短信验证码登录，同步流量余量");
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


    /* ================= 原生短信登录，通过 MarginPageBridge 与页面 DOM 交互 ================= */

    private void doGetSms() {
        String phone = phoneInput.getText().toString().trim();
        if (phone.length() != 11) { loginError("请输入正确的11位手机号码"); return; }
        loginError("");
        if (remember[0]) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("remember_phone", phone).apply();
        pendingSms = true;
        pendingLogin = false;
        smsCommanded = false;
        loginCommanded = false;
        getSmsBtn.setEnabled(false);
        startProbeWindow();
    }

    private void doLogin() {
        String phone = phoneInput.getText().toString().trim();
        String code = smsInput.getText().toString().trim();
        if (phone.length() != 11) { loginError("请输入正确的11位手机号码"); return; }
        if (!code.matches("\\d{6}")) { loginError("请输入6位短信随机码"); return; }
        if (latestSmsSeconds <= 0) { loginError("请先获取短信验证码，并在有效时间内登录"); return; }
        if (loginCommanded) return;
        loginError("");
        if (remember[0]) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("remember_phone", phone).apply();
        pendingSms = false;
        pendingLogin = true;
        loginCommanded = true;
        loginBtn.setEnabled(false);
        loginBtn.setText("登录中…");
        showLoading("正在验证短信随机码…");
        startProbeWindow();
        runBridgeCommand(MarginPageBridge.submitLogin(phone, code), false);
    }

    private void resetLoginBtns() {
        if (loginBtn != null) { loginBtn.setEnabled(true); loginBtn.setText("立即登录"); }
        if (countdownLeft <= 0 && getSmsBtn != null) {
            getSmsBtn.setEnabled(true);
            getSmsBtn.setText("获取验证码");
        }
    }

    private void syncSmsCountdown(int seconds) {
        if (seconds <= 0) return;
        latestSmsSeconds = seconds;
        if (!smsCountdownActive) {
            startCountdown(seconds);
            return;
        }
        if (seconds < countdownLeft) countdownLeft = seconds;
        getSmsBtn.setEnabled(false);
        getSmsBtn.setText("重新获取(" + countdownLeft + "s)");
    }

    private void startCountdown(int seconds) {
        countdownLeft = seconds;
        smsCountdownActive = true;
        getSmsBtn.setEnabled(false);
        getSmsBtn.setText("重新获取(" + seconds + "s)");
        if (countdownRunnable != null) uiHandler.removeCallbacks(countdownRunnable);
        if (countdownRunnable == null) {
            countdownRunnable = new Runnable() {
                @Override public void run() {
                    if (countdownLeft <= 0) {
                        getSmsBtn.setEnabled(true);
                        getSmsBtn.setText("获取验证码");
                        smsCountdownActive = false;
                        latestSmsSeconds = 0;
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

    private void clearWebSessionAndReload() {
        stopProbe();
        pendingSms = pendingLogin = false;
        smsCommanded = loginCommanded = false;
        lastItems.clear();
        if (summaryBox != null) summaryBox.removeAllViews();
        if (headerRemainNum != null) headerRemainNum.setText("--");
        if (headerUsedTotal != null) headerUsedTotal.setText("--");
        if (headerBar != null) headerBar.setRatio(0f);
        resetLoginBtns();
        if (countdownRunnable != null) uiHandler.removeCallbacks(countdownRunnable);
        countdownLeft = 0;
        smsCountdownActive = false;
        latestSmsSeconds = 0;
        final int clearGeneration = ++probeGeneration;
        if (web != null) {
            web.stopLoading();
            web.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
        }
        android.webkit.WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
            @Override public void onReceiveValue(Boolean removed) {
                if (clearGeneration != probeGeneration || web == null) return;
                CookieManager.getInstance().flush();
                loadEntry("正在重新打开流量查询…", false);
                showLoginScreen();
            }
        });
        showLoginScreen();
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
                clearWebSessionAndReload();
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

    /* ================= 余量刷新（始终仅访问指定流量入口） ================= */

    private void refreshFlow() {
        statusLine.setText("正在刷新流量余量…");
        showNative();
        loadEntry("正在刷新流量数据…", true);
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
            else if (it.cat != null && (it.cat.contains("定向") || "APP专属流量".equals(it.cat))) {
                if (it.name != null && it.name.contains("咪咕视频")) key = "咪咕视频";
                else if (it.name != null && it.name.contains("云盘")) key = "移动云盘";
                else if (it.name != null && it.name.contains("咪咕快游")) key = "咪咕快游";
                else key = "其他APP专属";
            } else if (it.cat != null && it.cat.contains("共享")) key = "共享流量";
            else if (it.cat != null && it.cat.contains("国际")) key = "国际流量";
            else if (it.cat != null && it.cat.contains("WLAN")) key = "WLAN流量";
            else if (it.cat != null && it.cat.contains("每日")) key = "每日流量";
            else if (it.cat != null && it.cat.contains("套餐外")) key = "套餐外流量";
            else if (it.cat != null && it.cat.contains("国内其他")) key = "国内其他流量";
            else key = "其他流量";
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
        for (Map.Entry<String, GroupSum> e : groups.entrySet()) {
            String key = e.getKey();
            if ("共享流量".equals(key) || "国际流量".equals(key) || "每日流量".equals(key)
                    || "套餐外流量".equals(key)) continue;
            allTotal += e.getValue().totalGB;
            allRemain += e.getValue().remainGB;
            allUsed += e.getValue().usedGB;
        }

        // 头部 hero
        String[] hero = splitNumUnit(allRemain);
        headerRemainNum.setText(hero[0]);
        headerRemainUnit.setText(hero[1]);
        headerUsedTotal.setText(fmt(allUsed));
        headerBar.setRatio(allTotal > 0 ? (float) (allRemain / allTotal) : 0f);

        // 总合计卡（全宽，压缩）
        summaryBox.addView(makeWideCard("基础套餐合计", "合", PRIMARY,
                "已消耗 " + fmt(allUsed) + " · 按国内套餐汇总 · 共享信息另列",
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
        for (Map.Entry<String, GroupSum> e : groups.entrySet()) {
            String key = e.getKey();
            if ("咪咕视频".equals(key) || "移动云盘".equals(key) || "咪咕快游".equals(key)
                    || "其他APP专属".equals(key)) {
                appTotal += e.getValue().totalGB;
                appRemain += e.getValue().remainGB;
                appCount += e.getValue().count;
            }
        }
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
            else if (key.contains("共享")) { color = PRIMARY; glyph = "享"; }
            else if (key.contains("国际")) { color = PURPLE; glyph = "境"; }
            else if (key.contains("每日")) { color = GREEN; glyph = "日"; }
            else if (key.contains("WLAN")) { color = PRIMARY; glyph = "网"; }
            else if (key.contains("套餐外")) { color = RED; glyph = "外"; }
            else if (key.contains("其他")) { glyph = "他"; }
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
        Matcher m = Pattern.compile("([\\d.]+)\\s*(KB|MB|GB|TB)", Pattern.CASE_INSENSITIVE).matcher(s.trim());
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(1));
        String u = m.group(2).toUpperCase();
        if (u.equals("KB")) return v / 1024 / 1024;
        if (u.equals("MB")) return v / 1024;
        if (u.equals("TB")) return v * 1024;
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

        boolean unlimited = false;
        double usageOnly = 0;
        if (items != null) {
            for (FlowItem item : items) {
                if ((item.remain != null && item.remain.contains("不限"))
                        || (item.total != null && item.total.contains("不限"))) unlimited = true;
                usageOnly += toGB(item.used);
            }
        }
        String remLabel = fmt(remain);
        String totalLabel = "总量 " + fmt(total);
        if (total <= 0.000001 && items != null) {
            if (unlimited) {
                remLabel = "不限";
                totalLabel = "套餐含不限量数据";
            } else if (usageOnly > 0) {
                remLabel = fmt(usageOnly);
                totalLabel = "已用（无总量字段）";
            } else if (remain <= 0) totalLabel = "总量未提供";
        }
        TextView rem = new TextView(this);
        rem.setText(remLabel);
        rem.setTextSize(14);
        rem.setTypeface(Typeface.DEFAULT_BOLD);
        rem.setTextColor(INK);
        rem.setPadding(0, dp(5), 0, 0);
        card.addView(rem);

        TextView tot = new TextView(this);
        tot.setText(totalLabel);
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
                String usedStr = (it.used == null || it.used.isEmpty()) ? "—" : it.used;
                String remainStr = (it.remain == null || it.remain.isEmpty()) ? "—" : it.remain;
                String totalStr = (it.total == null || it.total.isEmpty()) ? "—" : it.total;
                s2.setText("已用 " + usedStr + " · 剩余 " + remainStr + " · 总量 " + totalStr);
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

    private static final String PREFS = "flow_cookies";

    @Override
    protected void onPause() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        super.onPause();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        stopConsume();
        stopProbe();
        uiHandler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
