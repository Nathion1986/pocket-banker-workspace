package cn.pocketbanker.app;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Separate account namespace: never touches SMS configuration, keys or pending queue. */
public final class AccountSessionBridge {
  private static final String ALIAS = "pocket_account_refresh_v1";
  private final Context context;

  AccountSessionBridge(Context context) { this.context = context.getApplicationContext(); }

  private static synchronized SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }

  private static boolean origin(String base) {
    Uri uri = Uri.parse(base);
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || uri.getPath() != null && !uri.getPath().isEmpty()) return false;
    if ("https".equals(uri.getScheme()) && uri.getHost() != null) return true;
    return BuildConfig.DEBUG && "http".equals(uri.getScheme())
        && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "10.0.2.2".equals(uri.getHost()));
  }

  @JavascriptInterface
  public synchronized boolean save(String json) {
    try {
      JSONObject input = new JSONObject(json);
      String base = input.getString("apiBase"), user = input.getString("userId"), token = input.getString("refreshToken");
      if (!origin(base) || !user.matches("[A-Za-z0-9_-]{1,100}") || !token.matches("rt_[a-f0-9]{64}")) return false;
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
      cipher.updateAAD((base + "\n" + user).getBytes(StandardCharsets.UTF_8));
      JSONObject stored = new JSONObject().put("apiBase", base).put("userId", user)
          .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
          .put("cipher", Base64.encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
      return context.getSharedPreferences("account_refresh", 0).edit().putString("session", stored.toString()).commit();
    } catch (Exception e) { return false; }
  }

  @JavascriptInterface
  public synchronized String read(String base) {
    try {
      if (!origin(base)) return "{}";
      JSONObject stored = new JSONObject(context.getSharedPreferences("account_refresh", 0).getString("session", "{}"));
      if (!base.equals(stored.optString("apiBase"))) return "{}";
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)));
      cipher.updateAAD((base + "\n" + stored.getString("userId")).getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(Base64.decode(stored.getString("cipher"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) { return "{}"; }
  }

  @JavascriptInterface
  public synchronized void clear(String base) {
    try {
      JSONObject stored = new JSONObject(context.getSharedPreferences("account_refresh", 0).getString("session", "{}"));
      if (base.equals(stored.optString("apiBase"))) context.getSharedPreferences("account_refresh", 0).edit().remove("session").commit();
    } catch (Exception ignored) {}
  }
}
