package com.pocketbanker.perception;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.data.PerceptionSourceEntity;
import com.pocketbanker.security.VaultCodec;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, minimized structured inputs. Text/ICS and raw health measurements never enter jobs. */
@Service
@RequiredArgsConstructor
public class StructuredSignalJobs {
  private final BackendStore db;
  private final VaultCodec vault;
  private final SourceService sources;

  public record Claim(
      String id,
      String user,
      String source,
      String kind,
      long epoch,
      long generation,
      Map<String, Object> input) {}

  @Transactional
  public String reserve(
      PerceptionSourceEntity initial,
      String kind,
      String recordHash,
      long revision,
      String digest,
      Map<String, Object> safeInput) {
    db.lockUser(initial.getUserId());
    var source = sources.lockOwned(initial.getUserId(), initial.getId());
    sources.requireEpoch(source, initial.getConsentEpoch());
    if (db.one(
            "SELECT id FROM backend_structured_jobs WHERE source_id=? AND kind=? AND record_hash=? AND revision>?",
            source.getId(),
            kind,
            recordHash,
            revision)
        .isPresent()) throw new BackendException(409, "structured_revision_stale");
    var prior =
        db.one(
            "SELECT * FROM backend_structured_jobs WHERE source_id=? AND kind=? AND record_hash=? AND revision=?",
            source.getId(),
            kind,
            recordHash,
            revision);
    if (prior.isPresent()) {
      var row = prior.get();
      if (!digest.equals(text(row, "content_hash")))
        throw new BackendException(409, "external_id_content_conflict");
      String id = text(row, "id");
      if (number(row, "consent_epoch") != source.getConsentEpoch()
          && !Set.of("completed", "needs_review", "filtered").contains(text(row, "state")))
        db.update(
            "UPDATE backend_structured_jobs SET consent_epoch=?,state='queued',generation=generation+1,attempts=0,lease_until=NULL,next_attempt_at=?,error_code=NULL WHERE id=?",
            source.getConsentEpoch(),
            db.now(),
            id);
      return id;
    }
    if (db.one(
            "SELECT id FROM backend_structured_jobs WHERE source_id=? AND kind=? AND record_hash=? AND revision>?",
            source.getId(),
            kind,
            recordHash,
            revision)
        .isPresent()) throw new BackendException(409, "structured_revision_stale");
    if (number(
            db.one(
                    "SELECT COUNT(*) AS count FROM backend_structured_jobs WHERE user_id=? AND state IN ('queued','processing','retry_required')",
                    source.getUserId())
                .orElseThrow(),
            "count")
        >= 1000) throw new BackendException(503, "structured_queue_full");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_structured_jobs(id,source_id,user_id,kind,record_hash,revision,content_hash,input_cipher,consent_epoch,state,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'queued',?,?,?)",
        id,
        source.getId(),
        source.getUserId(),
        kind,
        recordHash,
        revision,
        digest,
        vault.seal(source.getUserId(), safeInput),
        source.getConsentEpoch(),
        db.now(),
        db.now(),
        db.now());
    return id;
  }

  @Transactional
  public Claim claim(String id, boolean explicit) {
    var initial =
        db.one("SELECT user_id,source_id FROM backend_structured_jobs WHERE id=?", id)
            .orElseThrow();
    db.lockUser(text(initial, "user_id"));
    var source = sources.lockOwned(text(initial, "user_id"), text(initial, "source_id"));
    var row =
        db.one("SELECT * FROM backend_structured_jobs WHERE id=? FOR UPDATE", id).orElseThrow();
    if (Set.of("completed", "filtered", "cancelled").contains(text(row, "state"))) return null;
    if ("needs_review".equals(text(row, "state")) && !explicit) return null;
    if ("processing".equals(text(row, "state"))
        && time(row, "lease_until") != null
        && time(row, "lease_until").isAfter(db.now())) return null;
    if (!explicit && time(row, "next_attempt_at").isAfter(db.now())) return null;
    try {
      sources.requireEpoch(source, number(row, "consent_epoch"));
    } catch (PerceptionException e) {
      db.update(
          "UPDATE backend_structured_jobs SET state='blocked',error_code='source_inactive',lease_until=NULL,updated_at=? WHERE id=?",
          db.now(),
          id);
      return null;
    }
    if (number(row, "attempts") >= 6) {
      db.update(
          "UPDATE backend_structured_jobs SET state='needs_review',error_code='retry_budget_exhausted',lease_until=NULL WHERE id=?",
          id);
      return null;
    }
    if (db.one(
            "SELECT id FROM backend_structured_jobs WHERE source_id=? AND kind=? AND record_hash=? AND revision>?",
            source.getId(),
            text(row, "kind"),
            text(row, "record_hash"),
            number(row, "revision"))
        .isPresent()) {
      db.update(
          "UPDATE backend_structured_jobs SET state='cancelled',lease_until=NULL,error_code='version_superseded' WHERE id=?",
          id);
      return null;
    }
    long generation = number(row, "generation") + 1;
    db.update(
        "UPDATE backend_structured_jobs SET state='processing',attempts=attempts+1,generation=?,lease_until=?,updated_at=? WHERE id=?",
        generation,
        db.now().plusSeconds(60),
        db.now(),
        id);
    return new Claim(
        id,
        source.getUserId(),
        source.getId(),
        text(row, "kind"),
        source.getConsentEpoch(),
        generation,
        vault.map(source.getUserId(), text(row, "input_cipher")));
  }

  public void requireClaim(Claim claim) {
    sources.requireEpoch(sources.lockOwned(claim.user(), claim.source()), claim.epoch());
    var row =
        db.one(
                "SELECT generation,state,lease_until FROM backend_structured_jobs WHERE id=? FOR UPDATE",
                claim.id())
            .orElseThrow();
    if (number(row, "generation") != claim.generation()
        || !"processing".equals(text(row, "state"))
        || !time(row, "lease_until").isAfter(db.now()))
      throw new BackendException(409, "structured_lease_changed");
    if (db.one(
            "SELECT newer.id FROM backend_structured_jobs newer JOIN backend_structured_jobs old ON newer.source_id=old.source_id AND newer.kind=old.kind AND newer.record_hash=old.record_hash WHERE old.id=? AND newer.revision>old.revision",
            claim.id())
        .isPresent()) throw new BackendException(409, "structured_revision_stale");
  }

  @Transactional
  public void complete(Claim claim, Map<String, Object> result) {
    requireClaim(claim);
    String status = result.getOrDefault("status", "completed").toString();
    db.update(
        "UPDATE backend_structured_jobs SET state=?,result_cipher=?,lease_until=NULL,error_code=NULL,updated_at=? WHERE id=?",
        "needs_review".equals(status)
            ? "needs_review"
            : "filtered".equals(status) ? "filtered" : "completed",
        vault.seal(claim.user(), result),
        db.now(),
        claim.id());
  }

  @Transactional
  public void fail(Claim claim, String reason) {
    db.lockUser(claim.user());
    var row =
        db.one("SELECT * FROM backend_structured_jobs WHERE id=? FOR UPDATE", claim.id())
            .orElseThrow();
    if (number(row, "generation") != claim.generation() || !"processing".equals(text(row, "state")))
      return;
    db.update(
        "UPDATE backend_structured_jobs SET state=?,error_code=?,lease_until=NULL,next_attempt_at=?,updated_at=? WHERE id=?",
        number(row, "attempts") >= 6 ? "needs_review" : "retry_required",
        reason,
        db.now().plusSeconds(Math.min(300, 1L << number(row, "attempts"))),
        db.now(),
        claim.id());
  }

  public void cancelRecord(
      String user, String source, String kind, String recordHash, long revision) {
    if (db.one(
            "SELECT id FROM backend_structured_jobs WHERE user_id=? AND source_id=? AND kind=? AND record_hash=? AND revision>?",
            user,
            source,
            kind,
            recordHash,
            revision)
        .isPresent()) throw new BackendException(409, "structured_revision_stale");
    db.update(
        "UPDATE backend_structured_jobs SET state='cancelled',generation=generation+1,lease_until=NULL,result_cipher=NULL,input_cipher=?,error_code='source_record_deleted',updated_at=? WHERE user_id=? AND source_id=? AND kind=? AND record_hash=? AND revision<=?",
        vault.seal(user, Map.of()),
        db.now(),
        user,
        source,
        kind,
        recordHash,
        revision);
  }

  public List<String> due() {
    return db
        .rows(
            "SELECT id FROM backend_structured_jobs WHERE (state IN ('queued','retry_required') AND next_attempt_at<=?) OR (state='processing' AND lease_until<=?) ORDER BY created_at LIMIT 32",
            db.now(),
            db.now())
        .stream()
        .map(r -> text(r, "id"))
        .toList();
  }

  public List<Map<String, Object>> list(String user, String source) {
    var owned =
        db.one("SELECT user_id FROM perception_sources WHERE id=? AND user_id=?", source, user)
            .orElseThrow(() -> new BackendException(404, "source_not_found"));
    return db.rows(
        "SELECT id,kind,revision,state,attempts,error_code,created_at,updated_at FROM backend_structured_jobs WHERE user_id=? AND source_id=? ORDER BY created_at DESC LIMIT 100",
        user,
        source);
  }

  @Transactional
  public Map<String, Object> forSource(String token, String source, String id) {
    var owner = sources.authenticateLocked(source, token);
    var row =
        db.one("SELECT source_id,user_id FROM backend_structured_jobs WHERE id=?", id)
            .orElseThrow(() -> new BackendException(404, "structured_job_not_found"));
    if (!owner.getId().equals(row.get("source_id"))
        || !owner.getUserId().equals(row.get("user_id")))
      throw new BackendException(404, "structured_job_not_found");
    return result(id);
  }

  public Map<String, Object> result(String id) {
    var row = db.one("SELECT * FROM backend_structured_jobs WHERE id=?", id).orElseThrow();
    Map<String, Object> out = new LinkedHashMap<>();
    if (text(row, "result_cipher") != null
        && Set.of("completed", "needs_review", "filtered").contains(text(row, "state")))
      out.putAll(vault.map(text(row, "user_id"), text(row, "result_cipher")));
    else out.put("status", text(row, "state"));
    out.put("jobState", row.get("state"));
    out.put("jobId", id);
    return out;
  }
}
