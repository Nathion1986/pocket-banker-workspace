package cn.pocketbanker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** Exported system SMS receiver; Android requires the sender's BROADCAST_SMS permission. */
public final class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
    if (!new SmsCollector(context).accepting()) return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                if (!new SmsCollector(context).accepting()) return;
                SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (parts == null || parts.length == 0) return;
                String sender = parts[0].getOriginatingAddress();
                long timestamp = parts[0].getTimestampMillis();
                StringBuilder body = new StringBuilder();
                for (SmsMessage sms : parts) {
                  if (!java.util.Objects.equals(sender, sms.getOriginatingAddress())) return;
                  body.append(sms.getMessageBody());
                }
                new SmsCollector(context).receive(sender, body.toString(), timestamp);
              } finally {
                pending.finish();
              }
            },
            "sms-receive")
        .start();
  }
}
