package cn.pocketbanker.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
  private WebView web;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    web = new WebView(this);
    setContentView(web);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(
        BuildConfig.DEBUG
            ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setWebChromeClient(new WebChromeClient());
    web.addJavascriptInterface(new CollectorBridge(this), "PocketCollector");
    web.addJavascriptInterface(new AccountSessionBridge(this), "PocketAccount");
    WebViewAssetLoader assets =
        new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public android.webkit.WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest req) {
            return assets.shouldInterceptRequest(req.getUrl());
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return !"appassets.androidplatform.net".equals(req.getUrl().getHost());
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            String base = BuildConfig.API_BASE.replace("\\", "\\\\").replace("'", "\\'");
            view.evaluateJavascript(
                "window.POCKET_LOCAL_DEBUG="
                    + BuildConfig.DEBUG
                    + ";window.POCKET_API_BASE='"
                    + base
                    + "';window.dispatchEvent(new Event('pocket-config'));",
                null);
          }
        });
    web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
  }

  @Override
  public void onBackPressed() {
    web.evaluateJavascript(
        "document.getElementById('overlay')?.classList.contains('open')",
        value -> {
          if ("true".equals(value))
            web.evaluateJavascript("document.getElementById('drawer-close')?.click()", null);
          else if (web.canGoBack()) web.goBack();
          else MainActivity.super.onBackPressed();
        });
  }

  void collectorChanged() {
    runOnUiThread(
        () -> {
          if (web != null)
            web.evaluateJavascript("window.dispatchEvent(new Event('pocket-collector'));", null);
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    new SmsCollector(this).schedule(true);
    collectorChanged();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    new SmsCollector(this).schedule(true);
    collectorChanged();
  }

  @Override
  protected void onDestroy() {
    web.destroy();
    super.onDestroy();
  }
}
