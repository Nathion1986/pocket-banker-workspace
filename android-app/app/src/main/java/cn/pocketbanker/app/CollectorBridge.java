package cn.pocketbanker.app;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

/** Reachable only from the bundled appassets page; no general filesystem or inbox bridge. */
public final class CollectorBridge {
  private final MainActivity activity;
  private final SmsCollector collector;

  CollectorBridge(MainActivity a) {
    activity = a;
    collector = new SmsCollector(a);
  }

  @JavascriptInterface
  public String getState() {
    return collector.state().toString();
  }

  @JavascriptInterface
  public void configure(String json) {
    try {
      collector.configure(new JSONObject(json));
    } catch (Exception e) {
      collector.error("invalid_source_configuration");
    }
    activity.collectorChanged();
  }

  @JavascriptInterface
  public void resume(String json) {
    try {
      collector.resume(new JSONObject(json));
    } catch (Exception e) {
      collector.error("source_resume_failed");
    }
    activity.collectorChanged();
  }

  @JavascriptInterface
  public void disable(boolean clear) {
    collector.disable(clear);
    activity.collectorChanged();
  }

  @JavascriptInterface
  public void retry() {
    collector.schedule(true);
    activity.collectorChanged();
  }

  @JavascriptInterface
  public void requestPermission() {
    activity.runOnUiThread(
        () -> {
          activity
              .getSharedPreferences("sms_transport", 0)
              .edit()
              .putBoolean("asked", true)
              .commit();
          activity.requestPermissions(new String[] {Manifest.permission.RECEIVE_SMS}, 7302);
        });
  }

  @JavascriptInterface
  public void openSettings() {
    activity.runOnUiThread(
        () ->
            activity.startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()))));
  }
}
