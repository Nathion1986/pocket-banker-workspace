package cn.pocketbanker.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run only in this project's dedicated synthetic emulator, never on a personal phone. */
@RunWith(AndroidJUnit4.class)
public class AccountSessionRuntimeTest {
  @Test
  public void refreshUsesIndependentKeyOriginAndUserBindingWithoutChangingSmsState() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var accounts = context.getSharedPreferences("account_refresh", 0);
    String saved = accounts.getString("session", null);
    var sms = context.getSharedPreferences("sms_transport", 0);
    var before = new java.util.TreeMap<>(sms.getAll());
    var bridge = new AccountSessionBridge(context);
    String origin = "https://synthetic-api.example.test";
    String token = "rt_" + "a".repeat(64);
    String payload = new JSONObject().put("apiBase", origin).put("userId", "fixture")
        .put("refreshToken", token).toString();
    try {
      assertTrue(bridge.save(payload));
      String raw = accounts.getString("session", "");
      assertFalse(raw.contains(token));
      assertEquals(token, new JSONObject(bridge.read(origin)).getString("refreshToken"));
      assertEquals("{}", bridge.read("https://other.example.test"));
      bridge.clear("https://other.example.test");
      assertEquals(token, new JSONObject(bridge.read(origin)).getString("refreshToken"));
      var changed = new JSONObject(raw).put("userId", "different-user");
      accounts.edit().putString("session", changed.toString()).commit();
      assertEquals("{}", bridge.read(origin));
      accounts.edit().putString("session", raw).commit();
      bridge.clear(origin);
      assertEquals("{}", bridge.read(origin));
      assertFalse(bridge.save(new JSONObject(payload).put("apiBase", "http://untrusted.example.test").toString()));
      assertEquals(before, new java.util.TreeMap<>(sms.getAll()));
    } finally {
      if (saved == null) accounts.edit().remove("session").commit();
      else accounts.edit().putString("session", saved).commit();
    }
  }
}
