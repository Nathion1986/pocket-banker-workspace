package com.pocketbanker.prediction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Exact-decimal daily simulation. Unknown account assignments stay explicitly unallocated. */
public final class CashflowSimulation {
  private CashflowSimulation() {}

  public record Flow(LocalDate date, BigDecimal amount, String account, String evidence) {}

  public static Map<String, Object> run(
      List<Map<String, Object>> accounts, List<Flow> entries, LocalDate today, int days) {
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    for (var a : accounts)
      balances.put(
          a.get("bank") + ":" + a.get("id"), new BigDecimal(a.get("available").toString()));
    BigDecimal cash = balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    var total = timeline(cash, entries, today, days);
    List<Map<String, Object>> perAccount = new ArrayList<>();
    boolean assigned =
        entries.stream()
            .filter(f -> !f.date().isBefore(today) && !f.date().isAfter(today.plusDays(days)))
            .allMatch(f -> f.account() != null && balances.containsKey(f.account()));
    for (var a : accounts) {
      String key = a.get("bank") + ":" + a.get("id");
      var slice =
          timeline(
              balances.get(key),
              entries.stream().filter(f -> key.equals(f.account())).toList(),
              today,
              days);
      slice.put("bank", a.get("bank"));
      slice.put("accountId", a.get("id"));
      slice.put("complete", assigned);
      perAccount.add(slice);
    }
    total.put("accountTimelines", perAccount);
    total.put("accountAllocationComplete", assigned);
    total.put(
        "unallocatedFlows",
        entries.stream()
            .filter(
                f ->
                    (f.account() == null || !balances.containsKey(f.account()))
                        && !f.date().isBefore(today)
                        && !f.date().isAfter(today.plusDays(days)))
            .map(
                f ->
                    Map.of(
                        "date",
                        f.date().toString(),
                        "amount",
                        f.amount().toPlainString(),
                        "evidence",
                        f.evidence()))
            .toList());
    return total;
  }

  private static Map<String, Object> timeline(
      BigDecimal opening, List<Flow> entries, LocalDate today, int days) {
    Map<LocalDate, BigDecimal> flow = new TreeMap<>();
    for (var f : entries) flow.merge(f.date(), f.amount(), BigDecimal::add);
    BigDecimal cash = opening, min = cash;
    LocalDate first = null;
    List<Map<String, Object>> daysOut = new ArrayList<>();
    for (int n = 0; n <= days; n++) {
      LocalDate day = today.plusDays(n);
      var amount = flow.getOrDefault(day, BigDecimal.ZERO);
      cash = cash.add(amount);
      min = min.min(cash);
      if (cash.signum() < 0 && first == null) first = day;
      daysOut.add(
          Map.of(
              "date",
              day.toString(),
              "flow",
              amount.toPlainString(),
              "available",
              cash.toPlainString()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timeline", daysOut);
    out.put("minimumAvailable", min.toPlainString());
    out.put("firstGap", first == null ? null : first.toString());
    return out;
  }
}
