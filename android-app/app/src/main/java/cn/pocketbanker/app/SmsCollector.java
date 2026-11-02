package cn.pocketbanker.app;

import android.Manifest;
import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class SmsCollector {
  static final int JOB = 7301;
  private static final Object LOCK = new Object();
  private final Context context;
  private final SharedPreferences prefs;

  SmsCollector(Context context) {
    this.context = context.getApplicationContext();
    prefs = this.context.getSharedPreferences("sms_transport", Context.MODE_PRIVATE);
  }

  String deviceId() {
    synchronized (LOCK) {
      String id = prefs.getString("device", null);
      if (id == null) {
        id = "android_" + UUID.randomUUID();
        prefs.edit().putString("device", id).commit();
      }
      return id;
    }
  }

  JSONObject config() throws Exception {
    synchronized (LOCK) {
      String cipher = prefs.getString("config", null);
      return cipher == null ? null : new JSONObject(CollectorVault.open(cipher));
    }
  }

  boolean accepting() {
    return prefs.getBoolean("enabled", false) && prefs.contains("config") && permission();
  }

  boolean permission() {
    return context.checkSelfPermission(Manifest.permission.RECEIVE_SMS)
        == PackageManager.PERMISSION_GRANTED;
  }

  String background() {
    PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    return power.isPowerSaveMode() || (Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted())
        ? "restricted"
        : "allowed";
  }

  JSONObject state() {
    JSONObject out = new JSONObject();
    try (SmsOutbox box = new SmsOutbox(context)) {
      JSONObject c = config();
      out.put("deviceId", deviceId());
      out.put("enabled", prefs.getBoolean("enabled", false));
      out.put("owner", c == null ? JSONObject.NULL : c.optString("owner"));
      out.put("sourceId", c == null ? JSONObject.NULL : c.optString("sourceId"));
      out.put(
          "permission",
          permission()
              ? "granted"
              : prefs.getBoolean("asked", false) ? "denied" : "not_determined");
      out.put("background", background());
      out.put("pendingCount", box.count());
      out.put("blockedCount", box.blockedCount());
      out.put("lastReceivedId", prefs.getString("received", ""));
      out.put("lastError", prefs.getString("error", ""));
    } catch (Exception e) {
      try {
        out.put("enabled", false);
        out.put("lastReceivedId", prefs.getString("received", ""));
        out.put("lastError", "local_storage_unavailable");
      } catch (Exception ignored) {
      }
    }
    return out;
  }

  void error(String value) {
    prefs.edit().putString("error", value).commit();
  }

  void configure(JSONObject c) throws Exception {
    URI uri = new URI(c.getString("base"));
    String base = c.getString("base").replaceAll("/$", "");
    String host = uri.getHost();
    boolean local =
        BuildConfig.DEBUG
            && "http".equals(uri.getScheme())
            && Arrays.asList("127.0.0.1", "localhost", "10.0.2.2").contains(host);
    if ((!"https".equals(uri.getScheme()) && !local)
        || host == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
      throw new IllegalArgumentException("invalid_api_address");
    if (!c.getString("sourceId").matches("[a-fA-F0-9-]{36}")
        || c.getString("sourceToken").length() < 32
        || c.getString("owner").isEmpty()
        || c.getJSONArray("senders").length() == 0)
      throw new IllegalArgumentException("invalid_source_configuration");
    c.put("base", base);
    c.put("version", UUID.randomUUID().toString());
    synchronized (LOCK) {
      JSONObject old = config();
      try (SmsOutbox box = new SmsOutbox(context)) {
        if (old == null
            || !old.optString("sourceId").equals(c.getString("sourceId"))
            || !old.optString("owner").equals(c.getString("owner"))) box.clear();
      }
      prefs
          .edit()
          .putString("config", CollectorVault.seal(c.toString()))
          .putBoolean("enabled", true)
          .putString("error", "")
          .commit();
    }
    schedule(false);
  }

  void resume(JSONObject input) throws Exception {
    JSONObject c = config();
    if (c == null || !c.getString("owner").equals(input.getString("owner")))
      throw new IllegalArgumentException("source_owner_mismatch");
    c.put("consentEpoch", input.getLong("consentEpoch"));
    configure(c);
  }

  void disable(boolean clear) {
    synchronized (LOCK) {
      prefs.edit().putBoolean("enabled", false).commit();
      if (clear) {
        try (SmsOutbox box = new SmsOutbox(context)) {
          box.clear();
        }
        prefs.edit().remove("config").commit();
      }
    }
    ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB);
  }

  boolean current(JSONObject c) {
    try {
      JSONObject now = config();
      return prefs.getBoolean("enabled", false)
          && permission()
          && now != null
          && now.getString("version").equals(c.getString("version"));
    } catch (Exception e) {
      return false;
    }
  }

  void receive(String sender, String body, long timestamp) {
    try {
      JSONObject c = config();
      if (c == null || !current(c)) return;
      List<String> allowed = new ArrayList<>();
      JSONArray numbers = c.getJSONArray("senders");
      for (int i = 0; i < numbers.length(); i++) allowed.add(numbers.getString(i));
      if (!SmsPolicy.accepts(sender, body, allowed)) return;
      String id = SmsPolicy.externalId(c.getString("sourceId"), sender, timestamp, body);
      JSONObject signal =
          new JSONObject()
              .put("sourceId", c.getString("sourceId"))
              .put("externalId", id)
              .put("occurredAt", SmsPolicy.occurredAt(timestamp))
              .put("sender", sender)
              .put("text", body);
      synchronized (LOCK) {
        if (!current(c)) return;
        try (SmsOutbox box = new SmsOutbox(context)) {
          box.prune(System.currentTimeMillis());
          box.add(id, signal.toString(), System.currentTimeMillis());
          prefs.edit().putString("received", id).commit();
        }
      }
      schedule(false);
    } catch (Exception e) {
      error(e instanceof IllegalStateException ? "outbox_full" : "local_storage_unavailable");
    }
  }

  void schedule(boolean immediate) {
    if (!prefs.getBoolean("enabled", false)) return;
    JobInfo.Builder job =
        new JobInfo.Builder(JOB, new ComponentName(context, SmsUploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    if (immediate) job.setMinimumLatency(0);
    else job.setMinimumLatency(1000);
    int result =
        ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE))
            .schedule(job.build());
    if (result != JobScheduler.RESULT_SUCCESS) error("background_job_rejected");
  }

  static final class HttpFailure extends IOException {
    final int status;

    HttpFailure(int status) {
      super("HTTP " + status);
      this.status = status;
    }
  }

  JSONObject http(JSONObject c, String path, JSONObject body) throws Exception {
    HttpURLConnection conn =
        (HttpURLConnection) new URL(c.getString("base") + path).openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(10000);
    conn.setInstanceFollowRedirects(false);
    conn.setRequestProperty("Authorization", "Bearer " + c.getString("sourceToken"));
    try {
      if (body != null) {
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream stream = conn.getOutputStream()) {
          stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) throw new HttpFailure(code);
      try (InputStream stream = conn.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[2048];
        int n;
        while ((n = stream.read(b)) != -1) {
          out.write(b, 0, n);
          if (out.size() > 131072) throw new IOException("response_too_large");
        }
        return new JSONObject(out.toString("UTF-8"));
      }
    } finally {
      conn.disconnect();
    }
  }

  boolean flush(java.util.function.BooleanSupplier cancelled) {
    try {
      JSONObject c = config();
      if (c == null || !current(c) || cancelled.getAsBoolean()) {
        if (!permission()) error("sms_permission_not_granted");
        return false;
      }
      try (SmsOutbox box = new SmsOutbox(context)) {
        if (box.prune(System.currentTimeMillis()) > 0) error("expired_pending_removed");
        for (SmsOutbox.Item row : box.batch()) {
          if (!current(c) || cancelled.getAsBoolean()) return false;
          if (row.blocked()) continue;
          try {
            JSONObject result =
                row.receipt() == null
                    ? http(c, "/api/v1/perception/sms", new JSONObject(row.payload()))
                    : http(
                        c,
                        "/api/v1/perception/sources/"
                            + c.getString("sourceId")
                            + "/receipts/"
                            + row.receipt(),
                        null);
            if (!current(c) || cancelled.getAsBoolean()) return false;
            String status = result.getString("status");
            if (Arrays.asList("completed", "filtered", "needs_review").contains(status)) {
              box.remove(row.id());
              error("");
            } else if ("revoked".equals(status)) {
              disable(false);
              error("source_revoked");
              return false;
            } else if ("retry_required".equals(status)) {
              box.receipt(row.id(), null);
              error("retry_required");
            } else {
              box.receipt(row.id(), result.getString("id"));
              error("waiting_for_receipt");
            }
          } catch (HttpFailure e) {
            if (e.status == 401 || e.status == 403) {
              disable(false);
              error("source_access_denied");
              return false;
            }
            if (e.status == 400 || e.status == 409 || e.status == 404) {
              box.block(row.id());
              error("blocked_signal_" + e.status);
            } else {
              error("network_retry_pending");
              break;
            }
          }
        }
        if (current(c) && !cancelled.getAsBoolean()) {
          long sequence = prefs.getLong("sequence", 0) + 1;
          prefs.edit().putLong("sequence", sequence).commit();
          JSONObject report =
              new JSONObject()
                  .put("protocolVersion", 1)
                  .put("deviceId", deviceId())
                  .put("platform", "android")
                  .put("osVersion", Build.VERSION.RELEASE)
                  .put("consentEpoch", c.getLong("consentEpoch"))
                  .put("sequence", sequence)
                  .put("observedAt", SmsPolicy.occurredAt(System.currentTimeMillis()))
                  .put("collectionMode", "automatic")
                  .put("permission", "granted")
                  .put("background", background())
                  .put("pendingCount", box.count());
          http(c, "/api/v1/perception/sources/" + c.getString("sourceId") + "/collector", report);
        }
        return box.retryable();
      }
    } catch (Exception e) {
      error("network_or_storage_retry_pending");
      return prefs.getBoolean("enabled", false);
    }
  }
}
