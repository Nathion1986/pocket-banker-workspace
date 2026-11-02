package com.pocketbanker.prediction;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class CashflowSimulationTest {
  @Test
  void balancesMatchAnIndependentIntegerCentPrefixOracleAcrossCalendarBoundaries() {
    var random = new Random(20261006);
    for (LocalDate start :
        List.of(LocalDate.of(2026, 12, 29), LocalDate.of(2028, 2, 26), LocalDate.of(2026, 9, 30))) {
      for (int scenario = 0; scenario < 100; scenario++) {
        List<Map<String, Object>> accounts = new ArrayList<>();
        long[] opening = new long[3];
        for (int n = 0; n < 3; n++) {
          opening[n] = random.nextInt(1000000);
          accounts.add(
              Map.of(
                  "bank",
                  String.valueOf((char) ('A' + n)),
                  "id",
                  "account-" + n,
                  "available",
                  BigDecimal.valueOf(opening[n], 2).toPlainString()));
        }
        long[][] delta = new long[3][40];
        long[] global = new long[40];
        List<CashflowSimulation.Flow> flows = new ArrayList<>();
        for (int n = 0; n < 80; n++) {
          int day = random.nextInt(40), account = random.nextInt(3);
          long cents = random.nextInt(200001) - 100000;
          delta[account][day] += cents;
          global[day] += cents;
          flows.add(
              new CashflowSimulation.Flow(
                  start.plusDays(day),
                  BigDecimal.valueOf(cents, 2),
                  accounts.get(account).get("bank") + ":" + accounts.get(account).get("id"),
                  "fixture-" + n));
        }
        var result = CashflowSimulation.run(accounts, flows, start, 39);
        var timeline = (List<Map<String, Object>>) result.get("timeline");
        long sum = Arrays.stream(opening).sum(), minimum = sum;
        for (int day = 0; day < 40; day++) {
          sum += global[day];
          minimum = Math.min(minimum, sum);
          assertThat(new BigDecimal(timeline.get(day).get("available").toString()))
              .isEqualByComparingTo(BigDecimal.valueOf(sum, 2));
          assertThat(new BigDecimal(timeline.get(day).get("flow").toString()))
              .isEqualByComparingTo(BigDecimal.valueOf(global[day], 2));
        }
        assertThat(new BigDecimal(result.get("minimumAvailable").toString()))
            .isEqualByComparingTo(BigDecimal.valueOf(minimum, 2));
        var per = (List<Map<String, Object>>) result.get("accountTimelines");
        for (int n = 0; n < 3; n++) {
          long balance = opening[n];
          var individual = (List<Map<String, Object>>) per.get(n).get("timeline");
          for (int day = 0; day < 40; day++) {
            balance += delta[n][day];
            assertThat(new BigDecimal(individual.get(day).get("available").toString()))
                .isEqualByComparingTo(BigDecimal.valueOf(balance, 2));
          }
        }
      }
    }
  }

  @Test
  void unspecifiedPayerDoesNotGetSilentlyAssignedToTheFirstAccount() {
    var day = LocalDate.of(2026, 10, 6);
    var accounts =
        List.<Map<String, Object>>of(Map.of("bank", "A", "id", "one", "available", "100.00"));
    var result =
        CashflowSimulation.run(
            accounts,
            List.of(new CashflowSimulation.Flow(day, new BigDecimal("-50.00"), null, "bill")),
            day,
            1);
    assertThat(result.get("accountAllocationComplete")).isEqualTo(false);
    assertThat((List<?>) result.get("unallocatedFlows")).hasSize(1);
    var slice = ((List<Map<String, Object>>) result.get("accountTimelines")).get(0);
    assertThat(slice.get("complete")).isEqualTo(false);
    assertThat(((List<Map<String, Object>>) slice.get("timeline")).get(0).get("available"))
        .isEqualTo("100.00");
    assertThat(((List<Map<String, Object>>) result.get("timeline")).get(0).get("available"))
        .isEqualTo("50.00");
  }
}
