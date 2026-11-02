package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.*;
import com.pocketbanker.orchestration.ModelCoordinator;
import com.pocketbanker.perception.SourceService;
import com.pocketbanker.profile.ProfileService;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authorized read-side coordinator; no financial action execution and no raw flow copy in the main
 * DB.
 */
@Service
@RequiredArgsConstructor
public class FlowPerceptionCoordinator {
  private final BackendStore db;
  private final BankBindingService bindings;
  private final ProfileService profile;
  private final ModelCoordinator models;
  private final TransactionTemplate transaction;
  private final SourceService sources;
  private final com.pocketbanker.accounts.BoundTransactionReader statements;

  public Map<String, Object> sync(String user, String source) {
    long epoch =
        transaction.execute(
            status -> {
              var sensor = sources.lockOwned(user, source);
              sources.requireActive(sensor);
              if (!"FLOW".equals(sensor.getKind()))
                throw new BackendException(400, "source_kind_mismatch");
              return sensor.getConsentEpoch();
            });
    Instant observedAt = db.now();
    Instant windowStart = observedAt.minus(Duration.ofDays(90));
    List<Map<String, Object>> all =
        statements.read(user, Map.of()).stream()
            .filter(
                r -> {
                  Instant at = OffsetDateTime.parse(r.get("occurredAt").toString()).toInstant();
                  return !at.isBefore(windowStart) && !at.isAfter(observedAt);
                })
            .filter(r -> "POSTED".equals(r.getOrDefault("status", "POSTED")))
            .toList();
    if (all.size() > 100000) throw new BackendException(413, "flow_observation_limit");
    Object observedHome = profile.value(user, "home_city").orElse(null);
    Map<String, Long> groupCounts =
        all.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    this::patternKey, java.util.stream.Collectors.counting()));
    // All external reads and model reviews finish before taking write locks.
    Map<String, Boolean> reviews = new HashMap<>();
    for (var row : all) {
      String currency = Objects.toString(row.get("currency"), "CNY");
      if (!"CNY".equals(currency)) throw new BackendException(409, "currency_conversion_required");
      String key = patternKey(row);
      if (groupCounts.get(key) < 3) continue;
      if (!reviews.containsKey(key)) {
        var verdict =
            models.review(
                user,
                source,
                Map.of(
                    "kind",
                    "flow_pattern",
                    "amount",
                    new BigDecimal(row.get("amount").toString()).abs(),
                    "currency",
                    currency),
                "signal",
                true);
        reviews.put(key, "confirm".equals(verdict.decision()));
      }
    }
    var homeSamples = citySamples(all, windowStart, observedHome != null);
    String selectedHome = selectCity(homeSamples);
    if (selectedHome != null) {
      var verdict =
          models.review(
              user,
              source,
              Map.of(
                  "kind",
                  "home_city_statistic",
                  "city",
                  selectedHome,
                  "sampleDays",
                  homeSamples.get(selectedHome).size(),
                  "windowStart",
                  windowStart.toString()),
              "signal",
              true);
      reviews.put("home_city:" + selectedHome, "confirm".equals(verdict.decision()));
    }
    return transaction.execute(
        status -> {
          db.lockUser(user);
          sources.requireEpoch(sources.lockOwned(user, source), epoch);
          if (!Objects.equals(observedHome, profile.value(user, "home_city").orElse(null)))
            throw new BackendException(409, "flow_profile_changed");
          for (var row : all)
            bindings.requireScope(
                user, row.get("bank").toString(), row.get("accountId").toString(), "read");
          profile.refreshFinancialEdges(user, source, all, windowStart);
          return commit(user, source, all, windowStart, reviews);
        });
  }

  private String patternKey(Map<String, Object> row) {
    return row.get("bank")
        + ":"
        + row.get("accountId")
        + ":"
        + row.get("merchant")
        + ":"
        + (new BigDecimal(row.get("amount").toString()).signum() < 0 ? "debit" : "credit");
  }

  private Map<String, Set<LocalDate>> citySamples(
      List<Map<String, Object>> rows, Instant windowStart, boolean established) {
    Map<String, Set<LocalDate>> out = new HashMap<>();
    Instant since = established ? db.now().minus(Duration.ofDays(30)) : windowStart;
    for (var row : rows) {
      if (new BigDecimal(row.get("amount").toString()).signum() >= 0
          || Set.of("转账", "理财", "工资", "收入", "存款", "退款")
              .contains(Objects.toString(row.get("category"), ""))) continue;
      Instant at = OffsetDateTime.parse(row.get("occurredAt").toString()).toInstant();
      if (at.isBefore(since)) continue;
      String city = Objects.toString(row.get("city"), "unknown");
      if (!Set.of("境外", "unknown", "").contains(city))
        out.computeIfAbsent(city, k -> new TreeSet<>())
            .add(at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
    }
    return out;
  }

  private String selectCity(Map<String, Set<LocalDate>> cityDays) {
    String city =
        cityDays.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<String, Set<LocalDate>>>comparingInt(e -> e.getValue().size())
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    if (city == null) return null;
    var samples = new TreeSet<>(cityDays.get(city));
    int total = cityDays.values().stream().mapToInt(Set::size).sum();
    if (samples.size() < 3
        || java.time.temporal.ChronoUnit.DAYS.between(samples.first(), samples.last()) < 14
        || samples.size() * 10 < total * 6) return null;
    return city;
  }

  private Map<String, Object> commit(
      String user,
      String source,
      List<Map<String, Object>> all,
      Instant windowStart,
      Map<String, Boolean> reviews) {
    Map<String, Set<LocalDate>> cityDays =
        citySamples(all, windowStart, profile.value(user, "home_city").isPresent());
    String city = selectCity(cityDays);
    if (city != null && !Boolean.TRUE.equals(reviews.get("home_city:" + city))) city = null;
    if (city != null && !Objects.equals(profile.value(user, "home_city").orElse(null), city)) {
      String selectedCity = city;
      String evidence =
          com.pocketbanker.security.PerceptionAccess.hash(
              source
                  + ":"
                  + city
                  + ":"
                  + all.stream()
                      .filter(r -> selectedCity.equals(r.get("city")))
                      .map(r -> r.get("bank") + ":" + r.get("accountId") + ":" + r.get("id"))
                      .sorted()
                      .toList());
      if (db.one(
                  "SELECT event_id FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
                  user,
                  source,
                  evidence)
              .isEmpty()
          && db.one(
                  "SELECT id FROM events WHERE user_id=? AND kind='home_city_change' AND city=? AND status='candidate'",
                  user,
                  city)
              .isEmpty()) {
        String event = UUID.randomUUID().toString();
        insertEvent(
            event,
            user,
            "life",
            "home_city_change",
            "常驻城市统计候选",
            null,
            null,
            null,
            "candidate",
            source,
            Map.of(
                "city",
                city,
                "sampleDays",
                cityDays.get(city).size(),
                "windowStart",
                windowStart.toString(),
                "windowEnd",
                db.now().toString(),
                "complete",
                true,
                "sourceId",
                source,
                "evidenceHash",
                evidence));
        db.update("UPDATE events SET city=? WHERE id=?", city, event);
        db.update(
            "INSERT INTO backend_signal_aliases VALUES(?,?,?,?,?)",
            user,
            source,
            evidence,
            0,
            event);
      }
    }
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (var row : all) groups.computeIfAbsent(patternKey(row), k -> new ArrayList<>()).add(row);
    int candidates = 0;
    for (var group : groups.values()) {
      if (group.size() < 3) continue;
      group.sort(
          Comparator.comparing(
              r -> OffsetDateTime.parse(r.get("occurredAt").toString()).toInstant()));
      List<Long> intervals = new ArrayList<>();
      for (int i = 1; i < group.size(); i++)
        intervals.add(
            Duration.between(
                    OffsetDateTime.parse(group.get(i - 1).get("occurredAt").toString()),
                    OffsetDateTime.parse(group.get(i).get("occurredAt").toString()))
                .toDays());
      if (intervals.stream().anyMatch(d -> d < 27 || d > 32)) continue;
      BigDecimal amount = new BigDecimal(group.get(0).get("amount").toString()).abs();
      if (group.stream()
          .anyMatch(
              r ->
                  new BigDecimal(r.get("amount").toString())
                          .abs()
                          .subtract(amount)
                          .abs()
                          .compareTo(amount.multiply(new BigDecimal("0.05")))
                      > 0)) continue;
      boolean income = new BigDecimal(group.get(0).get("amount").toString()).signum() > 0;
      String kind = income ? "monthly_income" : "monthly_expense";
      String key =
          com.pocketbanker.security.PerceptionAccess.hash(patternKey(group.get(0)) + ":" + kind);
      if (db.one(
              "SELECT id FROM events WHERE user_id=? AND kind=? AND payload_json LIKE ? AND status IN ('candidate','confirmed')",
              user,
              kind,
              "%" + key + "%")
          .isPresent()) continue;
      if (!Boolean.TRUE.equals(reviews.get(patternKey(group.get(0))))) continue;
      int day =
          OffsetDateTime.parse(group.get(group.size() - 1).get("occurredAt").toString())
              .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
              .getDayOfMonth();
      String sampleHash =
          com.pocketbanker.security.PerceptionAccess.hash(
              source
                  + ":"
                  + key
                  + ":"
                  + group.stream().map(r -> r.get("id").toString()).sorted().toList());
      if (db.one(
              "SELECT event_id FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
              user,
              source,
              sampleHash)
          .isPresent()) continue;
      String event = UUID.randomUUID().toString();
      insertEvent(
          event,
          user,
          "finance",
          kind,
          income ? "周期收入待确认" : "周期支出待确认",
          amount,
          null,
          "FREQ=MONTHLY;BYMONTHDAY=" + day,
          "candidate",
          source,
          Map.of(
              "patternHash",
              key,
              "day",
              day,
              "amount",
              amount.toPlainString(),
              "bank",
              group.get(0).get("bank"),
              "sourceId",
              source,
              "accountId",
              group.get(0).get("accountId")));
      db.update(
          "INSERT INTO backend_signal_aliases VALUES(?,?,?,?,?)",
          user,
          source,
          sampleHash,
          0,
          event);
      candidates++;
    }
    return Map.of(
        "complete",
        true,
        "windowStart",
        windowStart.toString(),
        "readTransactions",
        all.size(),
        "candidatePatterns",
        candidates,
        "homeCity",
        city == null ? "unresolved" : city);
  }

  private void insertEvent(
      String id,
      String user,
      String type,
      String kind,
      String title,
      BigDecimal amount,
      String date,
      String recurring,
      String state,
      String source,
      Map<String, Object> payload) {
    String json;
    try {
      json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException();
    }
    db.update(
        "INSERT INTO events(id,user_id,event_type,kind,title,amount,currency,due_date,recurrence,occurred_at,source,confidence,extractor_version,status,created_at,expires_at,confirmed_at,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        user,
        type,
        kind,
        title,
        amount,
        amount == null ? null : "CNY",
        date,
        recurring,
        db.now(),
        "flow_mining",
        0.9,
        "flow-consensus-v1",
        state,
        db.now(),
        db.now().plusSeconds(86400 * 30),
        state.equals("confirmed") ? db.now() : null,
        json);
  }
}
