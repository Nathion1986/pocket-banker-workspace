package com.pocketbanker.perception;

import com.pocketbanker.data.MailCursorEntity;
import com.pocketbanker.data.MailCursorRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MailCheckpoint {
  private final SourceService sources;
  private final MailCursorRepository cursors;
  private final Clock perceptionClock;
  private final com.pocketbanker.data.SignalReceiptRepository receipts;

  public record Cursor(long uidValidity, long lastUid) {}

  @Transactional
  public Cursor read(String user, String sourceId, String scope, long validity) {
    sources.requireActive(sources.lockOwned(user, sourceId));
    var c = cursors.findById(sourceId).orElse(null);
    return c == null || !scope.equals(c.getScopeHash()) || c.getUidValidity() != validity
        ? new Cursor(validity, 0)
        : new Cursor(validity, c.getLastUid());
  }

  @Transactional
  public void quarantine(String user, String sourceId, String externalId, long epoch) {
    sources.requireEpoch(sources.lockOwned(user, sourceId), epoch);
    String hash = com.pocketbanker.security.PerceptionAccess.hash(externalId);
    if (receipts.findBySourceIdAndExternalIdHash(sourceId, hash).isPresent()) return;
    var receipt = new com.pocketbanker.data.SignalReceiptEntity();
    receipt.setId(java.util.UUID.randomUUID().toString());
    receipt.setSourceId(sourceId);
    receipt.setUserId(user);
    receipt.setExternalIdHash(hash);
    // A bounded read could not produce a content digest. This hashes a typed metadata marker.
    receipt.setContentHash(
        com.pocketbanker.security.PerceptionAccess.hash("unread-content-limit:" + externalId));
    receipt.setOccurredAt(perceptionClock.instant());
    receipt.setReceivedAt(perceptionClock.instant());
    receipt.setUpdatedAt(perceptionClock.instant());
    receipt.setAttempts(1);
    receipt.setStatus("needs_review");
    receipt.setReason("mail_content_limit");
    receipts.saveAndFlush(receipt);
  }

  @Transactional
  public void advance(String user, String sourceId, String scope, long validity, long uid) {
    advance(user, sourceId, scope, validity, uid, null);
  }

  @Transactional
  public void advance(
      String user, String sourceId, String scope, long validity, long uid, Long epoch) {
    var source = sources.lockOwned(user, sourceId);
    sources.requireActive(source);
    if (epoch != null) sources.requireEpoch(source, epoch);
    var c = cursors.findById(sourceId).orElseGet(MailCursorEntity::new);
    if (!scope.equals(c.getScopeHash()) || c.getUidValidity() != validity) c.setLastUid(0);
    c.setSourceId(sourceId);
    c.setScopeHash(scope);
    c.setUidValidity(validity);
    c.setLastUid(Math.max(c.getLastUid(), uid));
    c.setUpdatedAt(perceptionClock.instant());
    cursors.save(c);
  }
}
