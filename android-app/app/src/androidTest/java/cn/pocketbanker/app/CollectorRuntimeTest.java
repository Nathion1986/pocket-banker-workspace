package cn.pocketbanker.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Runs only on an explicitly prepared, owned synthetic emulator; no real device or inbox. */
@RunWith(AndroidJUnit4.class)
public final class CollectorRuntimeTest {
  Context context;
  SmsCollector collector;
  JSONObject config;

  @Before
  public void prepare() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File fixture = new File(context.getFilesDir(), "native-test-fixture.json");
    assertTrue("Operator must provision a synthetic loopback fixture", fixture.isFile());
    config =
        new JSONObject(new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8));
    assertTrue(config.getString("base").startsWith("http://10.0.2.2:"));
    context
        .getSharedPreferences("sms_transport", 0)
        .edit()
        .putString("device", config.getString("deviceId"))
        .commit();
    collector = new SmsCollector(context);
    collector.disable(true);
    collector.configure(config);
    context.getSystemService(android.app.job.JobScheduler.class).cancel(SmsCollector.JOB);
  }

  @After
  public void cleanup() {
    collector.disable(true);
  }

  @Test
  public void encryptedReplayQueueSurvivesNewInstances() throws Exception {
    try (SmsOutbox box = new SmsOutbox(context)) {
      String body = "消费123.45元";
      String id = SmsPolicy.externalId(config.getString("sourceId"), "95500", 1000, body);
      assertTrue(box.add(id, body, System.currentTimeMillis()));
      assertFalse(box.add(id, body, System.currentTimeMillis()));
      assertEquals(1, box.count());
      try (var cursor =
          box.getReadableDatabase().rawQuery("SELECT payload_cipher FROM pending", null)) {
        assertTrue(cursor.moveToFirst());
        assertFalse(cursor.getString(0).contains(body));
      }
    }
    try (SmsOutbox reopened = new SmsOutbox(context)) {
      assertEquals("消费123.45元", reopened.batch().get(0).payload());
    }
    String cipher = context.getSharedPreferences("sms_transport", 0).getString("config", "");
    assertFalse(cipher.contains(config.getString("sourceToken")));
    assertEquals(
        config.getString("sourceId"), new SmsCollector(context).config().getString("sourceId"));
  }

  @Test
  public void blockedCipherTombstoneCannotPoisonOtherRows() throws Exception {
    try (SmsOutbox box = new SmsOutbox(context)) {
      box.add("blocked", "old", System.currentTimeMillis());
      box.add("healthy", "new", System.currentTimeMillis());
      box.block("blocked");
      assertEquals(2, box.batch().size());
      assertEquals(
          "new",
          box.batch().stream().filter(i -> i.id().equals("healthy")).findFirst().get().payload());
      assertTrue(box.retryable());
      box.remove("healthy");
      assertFalse(box.retryable());
    }
  }

  @Test
  public void actualHttpReplayCompletesAndDeletesRawBuffer() throws Exception {
    assertTrue("SMS permission must be granted in the owned emulator", collector.permission());
    String body = "学校通知学费20000元，请于" + config.getString("dueDate") + "前缴纳";
    long occurred = config.getLong("occurredMillis");
    collector.receive("95500", body, occurred);
    collector.receive("95500", body, occurred);
    context.getSystemService(android.app.job.JobScheduler.class).cancel(SmsCollector.JOB);
    try (SmsOutbox box = new SmsOutbox(context)) {
      assertEquals(1, box.count());
    }
    long until = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < until) {
      collector.flush(() -> false);
      try (SmsOutbox box = new SmsOutbox(context)) {
        if (box.count() == 0) break;
      }
      Thread.sleep(200);
    }
    try (SmsOutbox box = new SmsOutbox(context)) {
      assertEquals(0, box.count());
    }
  }

  @Test
  public void whitelistOtpAndDisableCannotQueueMessages() throws Exception {
    collector.receive("95501", "消费100元", System.currentTimeMillis());
    collector.receive("95500", "消费验证码123456", System.currentTimeMillis());
    try (SmsOutbox box = new SmsOutbox(context)) {
      assertEquals(0, box.count());
    }
    collector.disable(true);
    assertFalse(collector.accepting());
    collector.receive("95500", "消费100元", System.currentTimeMillis());
    assertFalse(collector.state().getBoolean("enabled"));
    try (SmsOutbox box = new SmsOutbox(context)) {
      assertEquals(0, box.count());
    }
  }

  @Test
  public void systemSmsBroadcastReachesEncryptedOutbox() throws Exception {
    assertTrue(collector.permission());
    Files.write(
        new File(context.getFilesDir(), "sms-broadcast-ready").toPath(),
        config.getString("sourceId").getBytes(StandardCharsets.UTF_8));
    String before = collector.state().optString("lastReceivedId");
    long until = System.currentTimeMillis() + 45000;
    while (System.currentTimeMillis() < until
        && before.equals(collector.state().optString("lastReceivedId"))) Thread.sleep(100);
    assertFalse(
        "System broadcast did not enter queue: " + collector.state(),
        before.equals(collector.state().optString("lastReceivedId")));
    for (int i = 0; i < 30; i++) {
      collector.flush(() -> false);
      try (SmsOutbox box = new SmsOutbox(context)) {
        if (box.count() == 0) break;
      }
      Thread.sleep(100);
    }
    try (SmsOutbox box = new SmsOutbox(context)) {
      assertEquals(0, box.count());
    }
  }
}
