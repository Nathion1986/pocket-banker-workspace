package cn.pocketbanker.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class SmsOutbox extends SQLiteOpenHelper {
  SmsOutbox(Context c) {
    super(c, "sms_transport.db", null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE pending (id TEXT PRIMARY KEY, payload_cipher TEXT NOT NULL, receipt TEXT,"
            + " created_at INTEGER NOT NULL, blocked INTEGER NOT NULL DEFAULT 0)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int a, int b) {
    throw new IllegalStateException("unexpected_sms_schema_upgrade");
  }

  record Item(String id, String payload, String receipt, long created, boolean blocked) {}

  synchronized boolean add(String id, String payload, long now) throws Exception {
    if (count() >= 500) throw new IllegalStateException("outbox_full");
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("payload_cipher", CollectorVault.seal(payload));
    v.put("created_at", now);
    return getWritableDatabase()
            .insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        >= 0;
  }

  synchronized int count() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  synchronized int blockedCount() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending WHERE blocked=1", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  synchronized boolean retryable() {
    return count() > blockedCount();
  }

  synchronized List<Item> batch() throws Exception {
    var out = new ArrayList<Item>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,payload_cipher,receipt,created_at,blocked FROM pending ORDER BY"
                    + " created_at,id LIMIT 25",
                null)) {
      while (c.moveToNext())
        out.add(
            new Item(
                c.getString(0),
                c.getInt(4) != 0 ? "" : CollectorVault.open(c.getString(1)),
                c.getString(2),
                c.getLong(3),
                c.getInt(4) != 0));
    }
    return out;
  }

  synchronized void receipt(String id, String receipt) {
    ContentValues v = new ContentValues();
    v.put("receipt", receipt);
    getWritableDatabase().update("pending", v, "id=?", new String[] {id});
  }

  synchronized void block(String id) {
    ContentValues v = new ContentValues();
    v.put("blocked", 1);
    v.put("payload_cipher", "");
    getWritableDatabase().update("pending", v, "id=?", new String[] {id});
  }

  synchronized void remove(String id) {
    getWritableDatabase().delete("pending", "id=?", new String[] {id});
  }

  synchronized void clear() {
    getWritableDatabase().delete("pending", null, null);
  }

  synchronized int prune(long now) {
    return getWritableDatabase()
        .delete("pending", "created_at<?", new String[] {String.valueOf(now - 7L * 86400000)});
  }
}
