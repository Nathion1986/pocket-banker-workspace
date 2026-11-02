package cn.pocketbanker.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Pure policy: only explicitly selected senders, no historical inbox and no OTP forwarding. */
public final class SmsPolicy {
  private SmsPolicy() {}

  private static final Pattern OTP =
      Pattern.compile(
          "验证码|动态码|一次性密码|校验码|verification\\s*code|one[- ]time|\\botp\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern USEFUL =
      Pattern.compile(
          "学费|缴费|账单|还款|消费|支出|收入|入账|扣款|转账|余额|贷记|借记|tuition|payment|bill|credited|debited",
          Pattern.CASE_INSENSITIVE);

  public static String sender(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean accepts(String sender, String body, List<String> allowed) {
    return body != null
        && !body.isEmpty()
        && body.length() <= 32768
        && allowed.stream().anyMatch(s -> sender(s).equals(sender(sender)))
        && !OTP.matcher(body).find()
        && USEFUL.matcher(body).find();
  }

  public static String externalId(String source, String sender, long time, String body) {
    return "sms_" + hash(source + "\n" + sender(sender) + "\n" + time + "\n" + body);
  }

  public static String hash(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException("hash_unavailable");
    }
  }

  public static String occurredAt(long millis) {
    SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
    date.setTimeZone(TimeZone.getTimeZone("UTC"));
    return date.format(new Date(millis));
  }
}
