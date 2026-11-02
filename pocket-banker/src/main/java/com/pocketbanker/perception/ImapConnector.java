package com.pocketbanker.perception;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.PerceptionSourceEntity;
import com.pocketbanker.data.PerceptionSourceRepository;
import com.pocketbanker.security.PerceptionAccess;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class ImapConnector {
  public record Account(
      String userId,
      String host,
      int port,
      String username,
      String password,
      String mailbox,
      boolean ssl) {
    @Override
    public String toString() {
      return "ImapAccount[redacted]";
    }
  }

  public record SyncState(
      String sourceId, String status, int processed, String reason, Instant updatedAt) {}

  private final Map<String, Account> accounts;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, SyncState> states = new ConcurrentHashMap<>();
  private final PerceptionSourceRepository sources;
  private final SourceService sourceService;
  private final PerceptionService perception;
  private final MailCheckpoint checkpoint;
  private final MailBodyReader bodyReader;
  private final ThreadPoolTaskExecutor executor;
  private final Clock clock;

  public ImapConnector(
      @Value("${perception.imap-accounts-json:{}}") String config,
      ObjectMapper mapper,
      PerceptionSourceRepository sources,
      SourceService sourceService,
      PerceptionService perception,
      MailCheckpoint checkpoint,
      MailBodyReader bodyReader,
      @Qualifier("perceptionMailExecutor") ThreadPoolTaskExecutor executor,
      Clock perceptionClock) {
    try {
      accounts = mapper.readValue(config, new TypeReference<>() {});
      for (var a : accounts.values()) {
        if (a.userId() == null
            || a.host() == null
            || a.username() == null
            || a.password() == null
            || a.mailbox() == null
            || a.mailbox().isBlank()
            || a.port() < 1
            || a.port() > 65535
            || (!a.ssl() && !Set.of("localhost", "127.0.0.1", "::1").contains(a.host()))) {
          throw new IllegalArgumentException();
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("invalid_perception_imap_accounts_json");
    }
    this.sources = sources;
    this.sourceService = sourceService;
    this.perception = perception;
    this.checkpoint = checkpoint;
    this.bodyReader = bodyReader;
    this.executor = executor;
    this.clock = perceptionClock;
  }

  public void validateBinding(String user, String ref) {
    if (ref == null) return;
    var account = accounts.get(ref);
    if (account == null || !user.equals(account.userId()))
      throw new PerceptionException(400, "imap_account_not_configured_for_user");
  }

  public SyncState requestSync(String user, String sourceId) {
    var source = owned(user, sourceId);
    sourceService.requireActive(source);
    if (!"EMAIL".equals(source.getKind()) || source.getConnectorRef() == null) {
      throw new PerceptionException(400, "imap_source_required");
    }
    validateBinding(user, source.getConnectorRef());
    if (!running.add(sourceId)) return state(user, sourceId);
    set(sourceId, "queued", 0, null);
    try {
      executor.execute(() -> sync(source));
    } catch (TaskRejectedException e) {
      running.remove(sourceId);
      set(sourceId, "retry_required", 0, "mail_queue_full");
      throw new PerceptionException(503, "mail_queue_full");
    }
    return state(user, sourceId);
  }

  public SyncState state(String user, String sourceId) {
    owned(user, sourceId);
    return states.getOrDefault(sourceId, new SyncState(sourceId, "idle", 0, null, clock.instant()));
  }

  @Scheduled(
      fixedDelayString = "${perception.imap-poll-ms:60000}",
      initialDelayString = "${perception.imap-initial-ms:60000}")
  public void poll() {
    if (accounts.isEmpty()) return;
    for (var source : sources.findByKindAndStatus("EMAIL", "active")) {
      if (source.getConnectorRef() == null
          || source.isPaused()
          || !source.getExpiresAt().isAfter(clock.instant())) continue;
      try {
        requestSync(source.getUserId(), source.getId());
      } catch (PerceptionException e) {
        /* next scheduled run may retry; never log mailbox credentials */
      }
    }
  }

  private void sync(PerceptionSourceEntity source) {
    int count = 0;
    String id = source.getId();
    set(id, "running", count, null);
    Account account = accounts.get(source.getConnectorRef());
    String protocol = account.ssl() ? "imaps" : "imap";
    Properties props = new Properties();
    props.setProperty("mail." + protocol + ".peek", "true");
    props.setProperty("mail." + protocol + ".connectiontimeout", "10000");
    props.setProperty("mail." + protocol + ".timeout", "10000");
    props.setProperty("mail." + protocol + ".writetimeout", "10000");
    props.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
    String scope =
        PerceptionAccess.hash(
            account.host()
                + "|"
                + account.port()
                + "|"
                + account.username()
                + "|"
                + account.mailbox());
    try (Store store = Session.getInstance(props).getStore(protocol)) {
      store.connect(account.host(), account.port(), account.username(), account.password());
      try (Folder folder = store.getFolder(account.mailbox())) {
        folder.open(Folder.READ_ONLY);
        if (!(folder instanceof UIDFolder uidFolder))
          throw new MessagingException("uid_not_supported");
        long validity = uidFolder.getUIDValidity();
        if (validity <= 0 || uidFolder.getUIDNext() <= 0)
          throw new MessagingException("invalid_uid_metadata");
        long last = checkpoint.read(source.getUserId(), id, scope, validity).lastUid();
        long upper = Math.min(last + 500, uidFolder.getUIDNext() - 1);
        Message[] messages =
            upper > last ? uidFolder.getMessagesByUID(last + 1, upper) : new Message[0];
        // Process at most 50 per run; next poll resumes at the last committed UID.
        Arrays.sort(messages, Comparator.comparingLong(m -> uid(uidFolder, m)));
        for (Message message : messages) {
          if (count >= 50) break;
          sourceService.requireEpoch(sources.findById(id).orElseThrow(), source.getConsentEpoch());
          long uid = uidFolder.getUID(message);
          if (uid <= last) continue;
          String sender = sender(message);
          if (sourceService.senderAllowed(source, sender)) {
            try {
              String text = bodyReader.read(message);
              if (!text.isBlank()) {
                String subject = message.getSubject();
                if (subject != null && subject.length() > 500)
                  throw new MailBodyReader.ContentLimit();
                Date received = message.getReceivedDate();
                if (received == null) received = message.getSentDate();
                // No wall-clock fallback: identical delivery must retain an identical digest.
                Instant occurred =
                    received == null ? Instant.parse("2000-01-01T00:00:00Z") : received.toInstant();
                var signal =
                    new PerceptionRequests.TextSignal(
                        id, scope + ":" + validity + ":" + uid, occurred, sender, subject, text);
                if (!perception.processMail(source.getUserId(), signal, source.getConsentEpoch()))
                  throw new MessagingException("processing_not_finished");
              }
            } catch (MailBodyReader.ContentLimit limit) {
              checkpoint.quarantine(
                  source.getUserId(),
                  id,
                  scope + ":" + validity + ":" + uid,
                  source.getConsentEpoch());
            }
          }
          // Only a persisted quarantine permits advancing past content limits; transport errors
          // retry.
          checkpoint.advance(
              source.getUserId(), id, scope, validity, uid, source.getConsentEpoch());
          last = uid;
          count++;
        }
        // UID gaps are legal after deletions; move across empty windows without fetching an
        // unbounded mailbox.
        if (messages.length == 0 && upper > last)
          checkpoint.advance(
              source.getUserId(), id, scope, validity, upper, source.getConsentEpoch());
      }
      set(id, "completed", count, null);
    } catch (Exception e) {
      set(id, "retry_required", count, "mail_sync_failed");
    } finally {
      running.remove(id);
    }
  }

  private PerceptionSourceEntity owned(String user, String id) {
    var s =
        sources.findById(id).orElseThrow(() -> new PerceptionException(404, "source_not_found"));
    if (!user.equals(s.getUserId())) throw new PerceptionException(404, "source_not_found");
    return s;
  }

  private static long uid(UIDFolder folder, Message message) {
    try {
      return folder.getUID(message);
    } catch (MessagingException e) {
      throw new IllegalStateException("uid_unavailable");
    }
  }

  private static String sender(Message message) throws MessagingException {
    Address[] from = message.getFrom();
    return from != null && from.length == 1 && from[0] instanceof InternetAddress address
        ? address.getAddress().toLowerCase(Locale.ROOT)
        : "";
  }

  private void set(String id, String status, int count, String reason) {
    states.put(id, new SyncState(id, status, count, reason, clock.instant()));
  }
}
