package com.pocketbanker.perception;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.*;
import com.pocketbanker.security.PerceptionAccess;
import java.net.URI;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CalDavConnector {
  public record Account(
      String userId,
      String url,
      String username,
      String password,
      String timezone,
      boolean allowLocalHttp) {
    @Override
    public String toString() {
      return "CalDavAccount[redacted]";
    }
  }

  private final Map<String, Account> accounts;
  private final SourceService sources;
  private final PerceptionSourceRepository repository;
  private final CalendarConnector calendar;
  private final BackendStore db;
  private final TransactionTemplate transaction;
  private final ThreadPoolTaskExecutor executor;
  private final CalDavClient client = new CalDavClient();
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, ImapConnector.SyncState> states = new ConcurrentHashMap<>();

  public CalDavConnector(
      @Value("${backend.calendars-json:${BACKEND_CALENDARS_JSON:{}}}") String config,
      ObjectMapper mapper,
      SourceService sources,
      PerceptionSourceRepository repository,
      CalendarConnector calendar,
      BackendStore db,
      TransactionTemplate transaction,
      @Qualifier("perceptionMailExecutor") ThreadPoolTaskExecutor executor) {
    try {
      accounts = mapper.readValue(config, new TypeReference<>() {});
      for (var a : accounts.values()) {
        URI uri = URI.create(a.url());
        if (a.userId() == null
            || a.username() == null
            || a.username().contains(":")
            || a.password() == null
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getQuery() != null
            || uri.getFragment() != null
            || !uri.getPath().endsWith("/")
            || !uri.equals(uri.normalize())
            || !uri.getRawPath().equals(uri.getPath())
            || !("https".equals(uri.getScheme())
                || "http".equals(uri.getScheme())
                    && a.allowLocalHttp()
                    && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost())))
          throw new IllegalArgumentException();
        ZoneId.of(a.timezone());
      }
    } catch (Exception e) {
      throw new IllegalStateException("invalid_backend_calendars_json");
    }
    this.sources = sources;
    this.repository = repository;
    this.calendar = calendar;
    this.db = db;
    this.transaction = transaction;
    this.executor = executor;
  }

  public void validateBinding(String user, String ref) {
    if (ref == null) return;
    if (!accounts.containsKey(ref) || !user.equals(accounts.get(ref).userId()))
      throw new PerceptionException(400, "calendar_account_not_configured_for_user");
  }

  public ImapConnector.SyncState requestSync(String user, String id) {
    var source = active(user, id);
    if (source.getConnectorRef() == null)
      throw new PerceptionException(400, "caldav_source_required");
    validateBinding(user, source.getConnectorRef());
    if (!running.add(id)) return state(user, id);
    set(id, "queued", 0);
    try {
      executor.execute(
          () -> sync(user, id, accounts.get(source.getConnectorRef()), source.getConsentEpoch()));
    } catch (TaskRejectedException e) {
      running.remove(id);
      set(id, "retry_required", 0);
      throw new PerceptionException(503, "calendar_queue_full");
    }
    return state(user, id);
  }

  public ImapConnector.SyncState state(String user, String id) {
    var source =
        repository.findById(id).orElseThrow(() -> new PerceptionException(404, "source_not_found"));
    if (!user.equals(source.getUserId())) throw new PerceptionException(404, "source_not_found");
    return states.getOrDefault(id, new ImapConnector.SyncState(id, "idle", 0, null, db.now()));
  }

  @Scheduled(
      fixedDelayString = "${perception.caldav-poll-ms:60000}",
      initialDelayString = "${perception.caldav-initial-ms:60000}")
  public void poll() {
    if (accounts.isEmpty()) return;
    for (var source : repository.findByKindAndStatus("CALENDAR", "active")) {
      if (source.getConnectorRef() == null
          || source.isPaused()
          || !source.getExpiresAt().isAfter(db.now())) continue;
      try {
        requestSync(source.getUserId(), source.getId());
      } catch (PerceptionException ignored) {
        /* Next poll retries, credentials never logged. */
      }
    }
  }

  private PerceptionSourceEntity active(String user, String id) {
    return transaction.execute(
        status -> {
          var source = sources.lockOwned(user, id);
          sources.requireActive(source);
          if (!"CALENDAR".equals(source.getKind()))
            throw new PerceptionException(400, "source_kind_mismatch");
          return source;
        });
  }

  private void sync(String user, String id, Account account, long epoch) {
    int processed = 0;
    set(id, "running", 0);
    try {
      sources.requireEpoch(active(user, id), epoch);
      var listing = client.list(account);
      Map<String, String> bodies = new LinkedHashMap<>();
      Map<String, Map<String, Object>> old = new LinkedHashMap<>();
      for (var row : db.rows("SELECT * FROM backend_caldav_resources WHERE source_id=?", id))
        old.put(text(row, "resource_hash"), row);
      // Fetch every changed object before mutating state. A failed/inconsistent listing cannot
      // delete facts.
      for (var item : listing.entrySet()) {
        var previous = old.get(item.getKey());
        if (previous == null
            || Boolean.TRUE.equals(previous.get("deleted"))
            || !PerceptionAccess.hash(item.getValue().etag()).equals(text(previous, "etag_hash"))
            || time(previous, "updated_at")
                .atZone(ZoneId.of(account.timezone()))
                .toLocalDate()
                .isBefore(db.now().atZone(ZoneId.of(account.timezone())).toLocalDate()))
          bodies.put(item.getKey(), client.fetch(account, item.getValue()));
      }
      if (!client.list(account).equals(listing))
        throw new PerceptionException(409, "calendar_changed_during_sync");
      for (var item : bodies.entrySet()) {
        sources.requireEpoch(active(user, id), epoch);
        var previous = old.get(item.getKey());
        boolean sameVersion =
            previous != null
                && !Boolean.TRUE.equals(previous.get("deleted"))
                && PerceptionAccess.hash(listing.get(item.getKey()).etag())
                    .equals(text(previous, "etag_hash"));
        long revision = previous == null ? 1 : number(previous, "revision") + (sameVersion ? 0 : 1);
        var result =
            calendar.ingestOwned(
                user,
                new CalendarConnector.Import(
                    id, item.getKey(), revision, item.getValue(), account.timezone(), false),
                epoch);
        Object results = result.get("results");
        if (results instanceof List<?> entries
            && entries.stream()
                .anyMatch(e -> e instanceof Map<?, ?> m && "needs_review".equals(m.get("status"))))
          throw new PerceptionException(503, "calendar_review_pending");
        save(
            user,
            id,
            item.getKey(),
            PerceptionAccess.hash(listing.get(item.getKey()).etag()),
            revision,
            false,
            epoch);
        processed++;
      }
      for (var item : old.entrySet()) {
        if (listing.containsKey(item.getKey())
            || Boolean.TRUE.equals(item.getValue().get("deleted"))) continue;
        sources.requireEpoch(active(user, id), epoch);
        long revision = number(item.getValue(), "revision") + 1;
        calendar.ingestOwned(
            user,
            new CalendarConnector.Import(
                id, item.getKey(), revision, null, account.timezone(), true),
            epoch);
        save(user, id, item.getKey(), PerceptionAccess.hash("deleted"), revision, true, epoch);
        processed++;
      }
      set(id, "completed", processed);
    } catch (Exception e) {
      set(id, "retry_required", processed);
    } finally {
      running.remove(id);
    }
  }

  private void save(
      String user,
      String id,
      String resource,
      String etag,
      long revision,
      boolean deleted,
      long epoch) {
    transaction.executeWithoutResult(
        status -> {
          sources.requireEpoch(sources.lockOwned(user, id), epoch);
          int changed =
              db.update(
                  "UPDATE backend_caldav_resources SET etag_hash=?,revision=?,deleted=?,updated_at=? WHERE source_id=? AND resource_hash=?",
                  etag,
                  revision,
                  deleted,
                  db.now(),
                  id,
                  resource);
          if (changed == 0)
            db.update(
                "INSERT INTO backend_caldav_resources VALUES(?,?,?,?,?,?)",
                id,
                resource,
                etag,
                revision,
                deleted,
                db.now());
        });
  }

  private void set(String id, String state, int count) {
    states.put(
        id,
        new ImapConnector.SyncState(
            id,
            state,
            count,
            state.equals("retry_required") ? "calendar_sync_failed" : null,
            db.now()));
  }
}
