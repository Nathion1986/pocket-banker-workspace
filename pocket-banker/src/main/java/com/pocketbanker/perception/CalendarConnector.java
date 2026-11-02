package com.pocketbanker.perception;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.PerceptionAccess;
import jakarta.validation.constraints.*;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A versioned calendar resource snapshot; only hashes, structured instances and cursors persist.
 */
@Service
@RequiredArgsConstructor
public class CalendarConnector {
  private final CalendarIcsReader parser;
  private final SupplementalPerception supplemental;
  private final SourceService sources;
  private final BackendStore db;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;

  public record Import(
      @NotBlank String sourceId,
      @NotBlank @Size(max = 160) String resourceId,
      @Min(0) long revision,
      @Size(max = 100000) String ics,
      @NotBlank @Size(max = 100) String timezone,
      boolean deleted) {}

  public Map<String, Object> ingest(String token, Import input) {
    return ingest(token, null, input, null);
  }

  Map<String, Object> ingestOwned(String owner, Import input, long epoch) {
    return ingest(null, owner, input, epoch);
  }

  private com.pocketbanker.data.PerceptionSourceEntity authenticate(
      String token, String owner, String id) {
    if (owner == null) return sources.authenticateLocked(id, token, "CALENDAR");
    var source = sources.lockOwned(owner, id);
    sources.requireActive(source);
    if (!"CALENDAR".equals(source.getKind()))
      throw new BackendException(400, "source_kind_mismatch");
    return source;
  }

  private Map<String, Object> ingest(String token, String owner, Import input, Long epoch) {
    var source = transaction.execute(status -> authenticate(token, owner, input.sourceId()));
    if (epoch != null) sources.requireEpoch(source, epoch);
    List<CalendarIcsReader.Entry> entries =
        input.deleted()
            ? List.of()
            : parser.read(
                input.ics(),
                db.now().atZone(ZoneId.of(input.timezone())).toLocalDate(),
                input.timezone());
    String resource = PerceptionAccess.hash(input.resourceId());
    String digest =
        PerceptionAccess.hash(
            input.deleted() + ":" + input.timezone() + ":" + Objects.toString(input.ics(), ""));
    List<String> current = entries.stream().map(e -> resource + ":" + e.identity()).toList();
    boolean[] rolling = {false};
    List<String> previous =
        transaction.execute(
            status -> {
              sources.requireEpoch(
                  authenticate(token, owner, input.sourceId()), source.getConsentEpoch());
              var row =
                  db.one(
                      "SELECT * FROM backend_calendar_resources WHERE source_id=? AND resource_hash=? FOR UPDATE",
                      input.sourceId(),
                      resource);
              if (row.isPresent()) {
                long version = number(row.get(), "revision");
                rolling[0] = version == input.revision();
                if (version > input.revision())
                  throw new BackendException(409, "calendar_revision_stale");
                if (version == input.revision() && !digest.equals(text(row.get(), "content_hash")))
                  throw new BackendException(409, "external_id_content_conflict");
              }
              List<String> old = row.map(r -> strings(text(r, "members_json"))).orElse(List.of());
              Set<String> pendingMembers = new LinkedHashSet<>(old);
              pendingMembers.addAll(current);
              String members;
              try {
                members = mapper.writeValueAsString(pendingMembers);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              if (row.isEmpty())
                db.update(
                    "INSERT INTO backend_calendar_resources VALUES(?,?,?,?,?,?,?)",
                    input.sourceId(),
                    resource,
                    source.getUserId(),
                    input.revision(),
                    digest,
                    members,
                    db.now());
              else
                db.update(
                    "UPDATE backend_calendar_resources SET revision=?,content_hash=?,members_json=?,updated_at=? WHERE source_id=? AND resource_hash=?",
                    input.revision(),
                    digest,
                    members,
                    db.now(),
                    input.sourceId(),
                    resource);
              return old;
            });
    List<Map<String, Object>> results = new ArrayList<>();
    for (var entry : entries) {
      String external = resource + ":" + entry.identity();

      if (entry.cancelled()) {
        results.add(
            supplemental.cancelCalendarEntry(
                token,
                owner,
                input.sourceId(),
                external,
                input.revision(),
                source.getConsentEpoch()));
      } else {
        results.add(
            supplemental.calendarEntry(
                token,
                owner,
                new SupplementalPerception.CalendarInput(
                    input.sourceId(),
                    external,
                    input.revision(),
                    entry.kind(),
                    entry.start(),
                    entry.end(),
                    entry.city(),
                    null,
                    entry.amount(),
                    entry.currency()),
                entry,
                source.getConsentEpoch()));
      }
    }
    for (String missing : previous)
      if (!rolling[0] && !current.contains(missing))
        supplemental.cancelCalendarEntry(
            token, owner, input.sourceId(), missing, input.revision(), source.getConsentEpoch());
    transaction.executeWithoutResult(
        status -> {
          sources.requireEpoch(
              authenticate(token, owner, input.sourceId()), source.getConsentEpoch());
          var row =
              db.one(
                  "SELECT * FROM backend_calendar_resources WHERE source_id=? AND resource_hash=? FOR UPDATE",
                  input.sourceId(),
                  resource);
          if (row.isPresent() && number(row.get(), "revision") > input.revision())
            throw new BackendException(409, "calendar_revision_stale");
          String members;
          try {
            Set<String> retained = new LinkedHashSet<>(current);
            if (rolling[0]) retained.addAll(previous);
            members = mapper.writeValueAsString(retained);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          if (row.isEmpty())
            db.update(
                "INSERT INTO backend_calendar_resources VALUES(?,?,?,?,?,?,?)",
                input.sourceId(),
                resource,
                source.getUserId(),
                input.revision(),
                digest,
                members,
                db.now());
          else
            db.update(
                "UPDATE backend_calendar_resources SET revision=?,content_hash=?,members_json=?,updated_at=? WHERE source_id=? AND resource_hash=?",
                input.revision(),
                digest,
                members,
                db.now(),
                input.sourceId(),
                resource);
        });
    return Map.of(
        "status",
        "processed",
        "revision",
        input.revision(),
        "results",
        results,
        "deleted",
        input.deleted(),
        "rawStored",
        false);
  }

  private List<String> strings(String json) {
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("invalid_calendar_cursor");
    }
  }
}
