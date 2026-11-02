package com.pocketbanker.profile;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.security.BackendAudit;
import com.pocketbanker.security.VaultCodec;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {
  private final BackendStore db;
  private final VaultCodec vault;
  private final ObjectMapper mapper;
  private final BackendAudit audit;
  private final com.pocketbanker.notifications.NotificationService notifications;

  public record PersonInput(
      String name,
      String relation,
      String birthday,
      String city,
      String bank,
      String accountId,
      String sourceEventId,
      String phone,
      List<String> aliases) {}

  public record FactInput(
      String subjectId,
      String key,
      Object value,
      String kind,
      List<String> evidence,
      Instant validTo) {}

  public record RelationInput(
      String fromId,
      String toId,
      String kind,
      String amount,
      String state,
      String evidenceEventId) {}

  private static final Set<String> FACT_KEYS =
      Set.of(
          "home_city",
          "risk_level",
          "school_stage",
          "income_schedule",
          "expense_schedule",
          "preferred_bank",
          "coverage",
          "spending_pattern",
          "travel_window",
          "birthday");

  @Transactional
  public Map<String, Object> person(String user, PersonInput input) {
    if (input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.relation() == null
        || !Set.of("self", "family", "colleague", "friend", "payee").contains(input.relation()))
      throw new BackendException(400, "invalid_person");
    if (input.birthday() != null)
      try {
        LocalDate.parse(input.birthday());
      } catch (Exception e) {
        throw new BackendException(400, "invalid_birthday");
      }
    if (input.bank() != null && !Set.of("A", "B", "C", "F").contains(input.bank()))
      throw new BackendException(400, "invalid_bank");
    if (input.accountId() != null
        && (input.bank() == null
            || input.accountId().isBlank()
            || input.accountId().length() > 160))
      throw new BackendException(400, "invalid_payee_account");
    if (input.sourceEventId() != null) confirmed(user, input.sourceEventId());
    db.lockUser(user);
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_persons VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        user,
        vault.seal(user, input.name()),
        input.relation(),
        input.birthday(),
        input.city(),
        input.bank(),
        input.accountId() == null ? null : vault.seal(user, input.accountId()),
        true,
        input.sourceEventId(),
        db.now());
    saveAliases(user, id, input.phone(), input.aliases());
    audit.write(
        user,
        null,
        null,
        "person_confirmed",
        Map.of("eventId", input.sourceEventId() == null ? "explicit_user" : input.sourceEventId()));
    return decodePerson(user, db.one("SELECT * FROM backend_persons WHERE id=?", id).orElseThrow());
  }

  @Transactional
  public Map<String, Object> updatePerson(String user, String id, PersonInput input) {
    requirePerson(user, id);
    if (input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.relation() == null
        || input.relation() == null
        || !Set.of("self", "family", "colleague", "friend", "payee").contains(input.relation()))
      throw new BackendException(400, "invalid_person");
    if (input.accountId() != null
        && (input.bank() == null
            || input.accountId().isBlank()
            || input.accountId().length() > 160))
      throw new BackendException(400, "invalid_payee_account");
    db.lockUser(user);
    var before =
        db.one("SELECT * FROM backend_persons WHERE user_id=? AND id=?", user, id).orElseThrow();
    String oldAccount =
        text(before, "account_cipher") == null
            ? null
            : vault.open(user, text(before, "account_cipher"), String.class);
    if (!Objects.equals(before.get("bank"), input.bank())
        || !Objects.equals(oldAccount, input.accountId())) invalidatePersonReferences(user, id);
    db.update(
        "UPDATE backend_persons SET name_cipher=?,relation=?,birthday=?,city=?,bank=?,account_cipher=? WHERE user_id=? AND id=?",
        vault.seal(user, input.name()),
        input.relation(),
        input.birthday(),
        input.city(),
        input.bank(),
        input.accountId() == null ? null : vault.seal(user, input.accountId()),
        user,
        id);
    if (input.bank() != null && !Set.of("A", "B", "C", "F").contains(input.bank()))
      throw new BackendException(400, "invalid_bank");
    if (input.birthday() != null)
      try {
        LocalDate.parse(input.birthday());
      } catch (Exception e) {
        throw new BackendException(400, "invalid_birthday");
      }
    if (input.sourceEventId() != null) {
      confirmed(user, input.sourceEventId());
      db.update(
          "UPDATE backend_persons SET source_event_id=? WHERE user_id=? AND id=?",
          input.sourceEventId(),
          user,
          id);
    }
    if (input.phone() != null || input.aliases() != null) {
      db.update("DELETE FROM backend_payee_aliases WHERE user_id=? AND person_id=?", user, id);
      saveAliases(user, id, input.phone(), input.aliases());
    }
    return decodePerson(
        user,
        db.one("SELECT * FROM backend_persons WHERE user_id=? AND id=?", user, id).orElseThrow());
  }

  public List<Map<String, Object>> persons(String user) {
    return db
        .rows("SELECT * FROM backend_persons WHERE user_id=? ORDER BY created_at,id", user)
        .stream()
        .map(p -> decodePerson(user, p))
        .toList();
  }

  private String normalizedAlias(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .strip()
        .toLowerCase(Locale.ROOT);
  }

  private void saveAliases(String user, String person, String phone, List<String> aliases) {
    Map<String, String> values = new LinkedHashMap<>();
    if (phone != null && !phone.isBlank()) {
      String digits = phone.replaceAll("[\\s()-]", "");
      if (!digits.matches("\\+?[0-9]{7,15}")) throw new BackendException(400, "invalid_phone");
      values.put(digits, "phone");
    }
    if (aliases != null) {
      if (aliases.size() > 30
          || aliases.stream().anyMatch(a -> a == null || a.isBlank() || a.length() > 100))
        throw new BackendException(400, "invalid_payee_aliases");
      aliases.forEach(a -> values.put(normalizedAlias(a), "alias"));
    }
    values.forEach(
        (alias, kind) ->
            db.update(
                "INSERT INTO backend_payee_aliases VALUES(?,?,?,?,?)",
                user,
                person,
                com.pocketbanker.security.PerceptionAccess.hash(normalizedAlias(alias)),
                vault.seal(user, alias),
                kind));
  }

  public Optional<Map<String, Object>> resolve(String user, String name) {
    String normalized = normalizedAlias(name);
    Set<String> aliasIds = new HashSet<>();
    db.rows(
            "SELECT person_id FROM backend_payee_aliases WHERE user_id=? AND alias_hash=?",
            user,
            com.pocketbanker.security.PerceptionAccess.hash(normalized))
        .forEach(r -> aliasIds.add(text(r, "person_id")));
    var matched =
        persons(user).stream()
            .filter(
                p ->
                    normalized.equals(normalizedAlias(p.get("name").toString()))
                        || name.equals(p.get("id"))
                        || aliasIds.contains(p.get("id")))
            .toList();
    if (matched.size() > 1) throw new BackendException(409, "recipient_ambiguous");
    return matched.stream().findFirst();
  }

  private Map<String, Object> decodePerson(String user, Map<String, Object> p) {
    Map<String, Object> v = new LinkedHashMap<>();
    for (String key : List.of("id", "relation", "birthday", "city", "bank", "source_event_id"))
      v.put(key, p.get(key));
    v.put("name", vault.open(user, text(p, "name_cipher"), String.class));
    if (text(p, "account_cipher") != null)
      v.put("accountId", vault.open(user, text(p, "account_cipher"), String.class));
    var aliases =
        db.rows(
            "SELECT alias_cipher,kind FROM backend_payee_aliases WHERE user_id=? AND person_id=?",
            user,
            text(p, "id"));
    v.put(
        "aliases",
        aliases.stream()
            .filter(a -> "alias".equals(a.get("kind")))
            .map(a -> vault.open(user, text(a, "alias_cipher"), String.class))
            .toList());
    aliases.stream()
        .filter(a -> "phone".equals(a.get("kind")))
        .findFirst()
        .ifPresent(a -> v.put("phone", vault.open(user, text(a, "alias_cipher"), String.class)));
    return v;
  }

  @Transactional
  public Map<String, Object> fact(String user, FactInput input) {
    if (input.key() == null
        || (!FACT_KEYS.contains(input.key())
            && !input.key().matches("(?:income|expense)_schedule:[a-f0-9]{16}")
            && !input.key().matches("travel_window:[a-f0-9-]{36}"))
        || input.kind() == null
        || !Set.of("static", "pattern", "preference", "derived", "risk").contains(input.kind())
        || input.value() == null
        || input.evidence() == null
        || input.evidence().size() > 30) throw new BackendException(400, "invalid_fact");
    if (input.subjectId() != null) requirePerson(user, input.subjectId());
    for (String id : input.evidence()) confirmed(user, id);
    if (input.key().equals("risk_level")
        && !Set.of("C1", "C2", "C3", "C4", "C5").contains(input.value().toString()))
      throw new BackendException(400, "invalid_risk_preference");
    validateFact(input);
    if (json(input.value()).length() > 4000) throw new BackendException(400, "fact_too_large");
    db.lockUser(user);
    var old =
        db.one(
            "SELECT id FROM backend_facts WHERE user_id=? AND (subject_id=? OR (subject_id IS NULL AND ? IS NULL)) AND fact_key=?",
            user,
            input.subjectId(),
            input.subjectId(),
            input.key());
    String id = old.map(r -> text(r, "id")).orElseGet(() -> UUID.randomUUID().toString());
    if (old.isPresent())
      db.update(
          "UPDATE backend_facts SET value_cipher=?,kind=?,valid_to=?,evidence_json=?,updated_at=? WHERE id=?",
          vault.seal(user, input.value()),
          input.kind(),
          input.validTo(),
          json(input.evidence()),
          db.now(),
          id);
    else
      db.update(
          "INSERT INTO backend_facts VALUES(?,?,?,?,?,?,?,?,?,?)",
          id,
          user,
          input.subjectId(),
          input.key(),
          vault.seal(user, input.value()),
          input.kind(),
          db.now(),
          input.validTo(),
          json(input.evidence()),
          db.now());
    refreshTags(user);
    return Map.of(
        "id", id, "key", input.key(), "value", input.value(), "evidence", input.evidence());
  }

  private void validateFact(FactInput input) {
    try {
      String key = input.key();
      Object value = input.value();
      if (input.validTo() != null && !input.validTo().isAfter(db.now()))
        throw new IllegalArgumentException();
      if (key.startsWith("income_schedule") || key.startsWith("expense_schedule")) {
        if (!(value instanceof Map<?, ?> v)) throw new IllegalArgumentException();
        int day = Integer.parseInt(Objects.toString(v.get("day"), ""));
        if (day < 1 || day > 31) throw new IllegalArgumentException();
        Money.positive(v.get("amount"));
        if (v.containsKey("bank") != v.containsKey("accountId"))
          throw new IllegalArgumentException();
        if (v.containsKey("bank")
            && (!Set.of("A", "B", "C", "F").contains(v.get("bank"))
                || !(v.get("accountId") instanceof String a)
                || a.isBlank()
                || a.length() > 160)) throw new IllegalArgumentException();
        if (v.containsKey("currency") && !"CNY".equals(v.get("currency")))
          throw new IllegalArgumentException();
      } else if (key.startsWith("travel_window")) {
        if (!(value instanceof Map<?, ?> v)) throw new IllegalArgumentException();
        LocalDate start = LocalDate.parse(Objects.toString(v.get("startDate"), ""));
        LocalDate end = LocalDate.parse(Objects.toString(v.get("endDate"), start.toString()));
        if (end.isBefore(start)
            || !(v.get("city") instanceof String city)
            || city.isBlank()
            || city.length() > 100) throw new IllegalArgumentException();
      } else if (key.equals("birthday")) {
        LocalDate.parse(value.toString());
      } else if (Set.of("home_city", "school_stage", "preferred_bank").contains(key)) {
        if (!(value instanceof String v) || v.isBlank() || v.length() > 100)
          throw new IllegalArgumentException();
        if (key.equals("preferred_bank") && !Set.of("A", "B", "C", "F").contains(value))
          throw new IllegalArgumentException();
      }
    } catch (RuntimeException invalid) {
      throw new BackendException(400, "invalid_fact_value");
    }
  }

  public List<Map<String, Object>> facts(String user) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r :
        db.rows(
            "SELECT * FROM backend_facts WHERE user_id=? AND (valid_to IS NULL OR valid_to>?) ORDER BY fact_key",
            user,
            db.now())) {
      Map<String, Object> v = new LinkedHashMap<>();
      for (String k : List.of("id", "subject_id", "fact_key", "kind", "valid_to", "updated_at"))
        v.put(k, r.get(k));
      v.put("value", vault.open(user, text(r, "value_cipher"), Object.class));
      v.put("evidence", parseList(text(r, "evidence_json")));
      out.add(v);
    }
    return out;
  }

  public Optional<Object> value(String user, String key) {
    return facts(user).stream()
        .filter(f -> key.equals(f.get("fact_key")))
        .map(f -> f.get("value"))
        .findFirst();
  }

  @Transactional
  public Map<String, Object> relation(String user, RelationInput input) {
    requirePerson(user, input.fromId());
    requirePerson(user, input.toId());
    if (input.kind() == null
        || input.state() == null
        || Set.of("lend", "owe").contains(input.kind()) && input.amount() == null)
      throw new BackendException(400, "invalid_relation");
    if (!Set.of("family", "colleague", "lend", "owe", "transfer").contains(input.kind())
        || !Set.of("active", "returned", "closed").contains(input.state()))
      throw new BackendException(400, "invalid_relation");
    if (input.evidenceEventId() != null) confirmed(user, input.evidenceEventId());
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO backend_relations VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        input.fromId(),
        input.toId(),
        input.kind(),
        input.amount() == null ? null : Money.positive(input.amount()),
        input.state(),
        input.evidenceEventId());
    return Map.of("id", id, "state", input.state());
  }

  public List<Map<String, Object>> relations(String user) {
    return db.rows("SELECT * FROM backend_relations WHERE user_id=?", user);
  }

  @Transactional
  public void closeRelation(String user, String id, String evidence) {
    confirmed(user, evidence);
    int n =
        db.update(
            "UPDATE backend_relations SET state='returned',evidence_event_id=? WHERE user_id=? AND id=?",
            evidence,
            user,
            id);
    if (n == 0) throw new BackendException(404, "relation_not_found");
  }

  @Transactional
  public Map<String, Object> snapshot(String user) {
    db.lockUser(user);
    refreshTags(user);
    return Map.of(
        "persons",
        persons(user),
        "facts",
        facts(user),
        "relations",
        relations(user),
        "financialEdges",
        financialEdges(user),
        "tags",
        db.rows("SELECT tag,confidence FROM backend_tags WHERE user_id=?", user));
  }

  @Transactional
  public void forgetFact(String user, String id) {
    db.lockUser(user);
    var fact =
        db.one("SELECT evidence_json FROM backend_facts WHERE user_id=? AND id=?", user, id)
            .orElseThrow(() -> new BackendException(404, "fact_not_found"));
    notifications.cancelSubject(user, id);
    for (String event : parseList(text(fact, "evidence_json"))) invalidateDependents(user, event);
    db.update("DELETE FROM backend_facts WHERE user_id=? AND id=?", user, id);
    refreshTags(user);
  }

  @Transactional
  public void forgetPerson(String user, String id) {
    requirePerson(user, id);
    db.lockUser(user);
    notifications.cancelSubject(user, id);
    invalidatePersonReferences(user, id);
    var person =
        db.one("SELECT bank,account_cipher FROM backend_persons WHERE user_id=? AND id=?", user, id)
            .orElseThrow();
    if (text(person, "account_cipher") != null)
      db.update(
          "DELETE FROM backend_payee_stats WHERE user_id=? AND bank=? AND account_hash=?",
          user,
          person.get("bank"),
          com.pocketbanker.security.PerceptionAccess.hash(
              vault.open(user, text(person, "account_cipher"), String.class)));
    db.update("DELETE FROM backend_person_edges WHERE user_id=? AND person_id=?", user, id);
    for (var fact :
        db.rows("SELECT id FROM backend_facts WHERE user_id=? AND subject_id=?", user, id))
      forgetFact(user, text(fact, "id"));
    for (var event : db.rows("SELECT id FROM events WHERE user_id=? AND subject_id=?", user, id))
      invalidateDependents(user, text(event, "id"));
    db.update("DELETE FROM backend_payee_aliases WHERE user_id=? AND person_id=?", user, id);
    db.update(
        "DELETE FROM backend_relations WHERE user_id=? AND (from_id=? OR to_id=?)", user, id, id);
    db.update("DELETE FROM backend_facts WHERE user_id=? AND subject_id=?", user, id);
    db.update("DELETE FROM backend_persons WHERE user_id=? AND id=?", user, id);
    refreshTags(user);
  }

  @Transactional
  public void cleanupSuperseded(String user, String event) {
    notifications.cancelSubject(user, event);
    for (var row : db.rows("SELECT id,evidence_json FROM backend_facts WHERE user_id=?", user))
      if (parseList(text(row, "evidence_json")).contains(event))
        db.update("DELETE FROM backend_facts WHERE id=?", text(row, "id"));
    invalidateDependents(user, event);
    refreshTags(user);
  }

  @Transactional
  public void cleanupEvidence(String user, String event) {
    for (var row : db.rows("SELECT id,evidence_json FROM backend_facts WHERE user_id=?", user))
      if (parseList(text(row, "evidence_json")).contains(event))
        db.update("DELETE FROM backend_facts WHERE id=?", text(row, "id"));
    db.update("DELETE FROM backend_relations WHERE user_id=? AND evidence_event_id=?", user, event);
    for (var row :
        db.rows(
            "SELECT id FROM backend_persons WHERE user_id=? AND source_event_id=?", user, event))
      forgetPerson(user, text(row, "id"));
    invalidateDependents(user, event);
    refreshTags(user);
  }

  public void capturePersonReferences(
      String user, String type, String reference, List<WorkflowPlan.Node> nodes) {
    Set<String> ids = new LinkedHashSet<>();
    var known = persons(user);
    for (var node : nodes) {
      var params = node.params();
      for (var person : known) {
        boolean account =
            params.get("toAccountId") != null
                && Objects.equals(person.get("accountId"), params.get("toAccountId"))
                && Objects.equals(person.get("bank"), params.get("toBank"));
        boolean name =
            params.get("recipient") != null
                && Objects.equals(person.get("name"), params.get("recipient"));
        if (account || name) ids.add(person.get("id").toString());
      }
      if (params.get("participants") instanceof List<?> participants)
        for (var participant : participants) {
          requirePerson(user, participant.toString());
          ids.add(participant.toString());
        }
    }
    for (String id : ids)
      db.update(
          "INSERT INTO backend_person_references(user_id,person_id,reference_type,reference_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
          user,
          id,
          type,
          reference);
  }

  public Map<String, String> referenceSnapshot(String user, List<WorkflowPlan.Node> nodes) {
    var out = new LinkedHashMap<String, String>();
    for (var person : persons(user)) {
      boolean used =
          nodes.stream()
              .anyMatch(
                  node -> {
                    var params = node.params();
                    return params.get("toAccountId") != null
                            && Objects.equals(params.get("toAccountId"), person.get("accountId"))
                            && Objects.equals(params.get("toBank"), person.get("bank"))
                        || params.get("recipient") != null
                            && Objects.equals(params.get("recipient"), person.get("name"))
                        || params.get("participants") instanceof List<?> list
                            && list.contains(person.get("id"));
                  });
      if (used)
        out.put(
            person.get("id").toString(),
            com.pocketbanker.security.PerceptionAccess.hash(json(person)));
    }
    return out;
  }

  public void requireReferenceSnapshot(String user, Map<String, String> snapshot) {
    for (var entry : snapshot.entrySet()) {
      var person =
          persons(user).stream().filter(p -> entry.getKey().equals(p.get("id"))).findFirst();
      if (person.isEmpty()
          || !entry
              .getValue()
              .equals(com.pocketbanker.security.PerceptionAccess.hash(json(person.get()))))
        throw new BackendException(409, "proposal_person_changed");
    }
  }

  public boolean personReferencesCurrent(String user, String type, String reference) {
    return db.one(
            "SELECT r.person_id FROM backend_person_references r LEFT JOIN backend_persons p ON p.id=r.person_id AND p.user_id=r.user_id WHERE r.user_id=? AND r.reference_type=? AND r.reference_id=? AND (r.invalidated=TRUE OR p.id IS NULL)",
            user,
            type,
            reference)
        .isEmpty();
  }

  private void invalidatePersonReferences(String user, String person) {
    notifications.cancelSubject(user, person);
    db.update(
        "UPDATE backend_person_references SET invalidated=TRUE WHERE user_id=? AND person_id=?",
        user,
        person);
    for (var row :
        db.rows(
            "SELECT reference_type,reference_id FROM backend_person_references WHERE user_id=? AND person_id=?",
            user,
            person)) {
      if ("task".equals(row.get("reference_type")))
        db.update(
            "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,approved_version=NULL,authorization_until=NULL,updated_at=? WHERE user_id=? AND id=? AND state IN ('READY','SCHEDULED','RUNNING','RETRY_WAIT','RECONCILING','WAITING_USER','WAITING_CONFIRMATION','WAITING_VERIFICATION')",
            db.now(),
            user,
            row.get("reference_id"));
      else
        db.update(
            "UPDATE backend_proposals SET state='withdrawn' WHERE user_id=? AND id=? AND state='pending'",
            user,
            row.get("reference_id"));
    }
  }

  public List<Map<String, Object>> financialEdges(String user) {
    return db
        .rows(
            "SELECT person_id,summary_cipher,updated_at FROM backend_person_edges WHERE user_id=? ORDER BY person_id",
            user)
        .stream()
        .map(
            row -> {
              Map<String, Object> result =
                  new LinkedHashMap<>(vault.map(user, text(row, "summary_cipher")));
              result.put("personId", row.get("person_id"));
              result.put("updatedAt", row.get("updated_at"));
              result.put("kind", "verified_transaction_aggregate");
              return result;
            })
        .toList();
  }

  public void refreshFinancialEdges(
      String user, String source, List<Map<String, Object>> rows, Instant since) {
    db.update("DELETE FROM backend_person_edges WHERE user_id=?", user);
    for (var person : persons(user)) {
      if (person.get("accountId") == null) continue;
      List<Map<String, Object>> matched =
          rows.stream()
              .filter(r -> "POSTED".equals(r.getOrDefault("status", "POSTED")))
              .filter(
                  row ->
                      Objects.equals(row.get("counterpartyBank"), person.get("bank"))
                          && Objects.equals(
                              row.get("counterpartyAccountId"), person.get("accountId")))
              .toList();
      if (matched.isEmpty()) continue;
      java.math.BigDecimal incoming = java.math.BigDecimal.ZERO,
          outgoing = java.math.BigDecimal.ZERO;
      Instant last = since;
      List<String> hashes = new ArrayList<>();
      for (var row : matched) {
        var amount = new java.math.BigDecimal(row.get("amount").toString());
        // Counterparty amounts exclude fees if the upstream reports that field.
        if (row.get("counterpartyAmount") != null)
          amount =
              new java.math.BigDecimal(row.get("counterpartyAmount").toString())
                  .multiply(java.math.BigDecimal.valueOf(amount.signum()));
        if (amount.signum() > 0) incoming = incoming.add(amount);
        else outgoing = outgoing.subtract(amount);
        Instant at = OffsetDateTime.parse(row.get("occurredAt").toString()).toInstant();
        if (at.isAfter(last)) last = at;
        hashes.add(
            com.pocketbanker.security.PerceptionAccess.hash(
                row.get("bank") + ":" + row.get("accountId") + ":" + row.get("id")));
      }
      Collections.sort(hashes);
      Map<String, Object> summary =
          Map.of(
              "count",
              matched.size(),
              "incoming",
              incoming.toPlainString(),
              "outgoing",
              outgoing.toPlainString(),
              "currency",
              "CNY",
              "lastAt",
              last.toString(),
              "windowStart",
              since.toString(),
              "windowEnd",
              db.now().toString(),
              "evidenceHash",
              com.pocketbanker.security.PerceptionAccess.hash(hashes.toString()),
              "unknownCounterparties",
              rows.stream().filter(r -> r.get("counterpartyAccountId") == null).count());
      db.update(
          "INSERT INTO backend_person_edges VALUES(?,?,?,?,?)",
          user,
          person.get("id"),
          source,
          vault.seal(user, summary),
          db.now());
    }
  }

  private void invalidateDependents(String user, String event) {
    db.lockUser(user);
    notifications.cancelSubject(user, event);
    db.update(
        "UPDATE backend_proposals SET state='withdrawn' WHERE user_id=? AND source_event_id=? AND state='pending'",
        user,
        event);
    // Keep sent nodes and their operation keys. The paused executor may reconcile them,
    // but cannot dispatch another forward action using removed evidence.
    db.update(
        "UPDATE backend_tasks SET state='PAUSED',desired_control='PAUSE',approved_risk=NULL,approved_version=NULL,authorization_until=NULL,updated_at=? WHERE user_id=? AND source_event_id=? AND state IN ('READY','SCHEDULED','RUNNING','RETRY_WAIT','RECONCILING','WAITING_USER','WAITING_CONFIRMATION','WAITING_VERIFICATION')",
        db.now(),
        user,
        event);
  }

  private void refreshTags(String user) {
    db.update("DELETE FROM backend_tags WHERE user_id=?", user);
    for (var f : facts(user)) {
      String key = String.valueOf(f.get("fact_key"));
      String tag =
          key.equals("home_city")
              ? "常驻" + f.get("value")
              : key.equals("risk_level")
                  ? String.valueOf(f.get("value"))
                  : key.equals("school_stage") ? "有子女·" + f.get("value") : null;
      if (tag != null && tag.length() <= 100)
        db.update(
            "MERGE INTO backend_tags KEY(user_id,tag) VALUES(?,?,?,?,?)",
            user,
            tag,
            1.0,
            json(f.get("evidence")),
            db.now());
    }
  }

  public Map<String, Object> confirmed(String user, String id) {
    var e =
        db.one("SELECT * FROM events WHERE user_id=? AND id=?", user, id)
            .orElseThrow(() -> new BackendException(404, "event_not_found"));
    if (!"confirmed".equals(text(e, "status"))
        || "prediction".equals(text(e, "event_type"))
        || text(e, "superseded_by") != null)
      throw new BackendException(409, "confirmed_fact_required");
    return e;
  }

  private void requirePerson(String user, String id) {
    if (db.one("SELECT id FROM backend_persons WHERE user_id=? AND id=?", user, id).isEmpty())
      throw new BackendException(404, "person_not_found");
  }

  private String json(Object object) {
    try {
      return mapper.writeValueAsString(object);
    } catch (Exception e) {
      throw new BackendException(400, "invalid_profile_value");
    }
  }

  private List<String> parseList(String s) {
    try {
      return mapper.readValue(s, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }
}
