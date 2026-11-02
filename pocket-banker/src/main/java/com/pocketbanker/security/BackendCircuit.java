package com.pocketbanker.security;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.data.BackendStore;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BackendCircuit {
  private final BackendStore db;
  private final BackendAudit audit;

  @Transactional
  public void failure(String user, String reason) {
    var row = db.lockUser(user);
    Instant last = time(row, "updated_at");
    long count =
        last != null && last.plusSeconds(60).isAfter(db.now()) ? number(row, "failures") + 1 : 1;
    db.update(
        "UPDATE backend_users SET failures=?,updated_at=?,locked_until=? WHERE user_id=?",
        count,
        db.now(),
        count >= 5 ? db.now().plusSeconds(60) : time(row, "locked_until"),
        user);
    audit.write(
        user,
        null,
        null,
        count >= 5 ? "circuit_opened" : "circuit_failure",
        java.util.Map.of("reason", reason, "attempt", count));
  }

  @Transactional
  public void modelResult(String user, boolean disagreed, boolean rejected) {
    db.lockUser(user);
    db.update(
        "DELETE FROM backend_model_signals WHERE user_id=? AND created_at<?",
        user,
        db.now().minusSeconds(300));
    db.update(
        "INSERT INTO backend_model_signals VALUES(?,?,?,?,?)",
        java.util.UUID.randomUUID().toString(),
        user,
        disagreed,
        rejected,
        db.now());
    var rows =
        db.rows(
            "SELECT disagreed FROM backend_model_signals WHERE user_id=? AND created_at>?",
            user,
            db.now().minusSeconds(60));
    long conflicts = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("disagreed"))).count();
    if (disagreed && rows.size() >= 5 && conflicts * 2 >= rows.size()) {
      db.update(
          "UPDATE backend_users SET locked_until=?,updated_at=? WHERE user_id=?",
          db.now().plusSeconds(60),
          db.now(),
          user);
      audit.write(
          user,
          null,
          null,
          "circuit_opened",
          java.util.Map.of("reason", "model_disagreement_rate"));
    } else if (rejected) failure(user, "model_rejection");
  }
}
