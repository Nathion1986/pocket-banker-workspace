package com.pocketbanker.sandbox;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalysisVerifierTest {
  AnalysisVerifier verifier = new AnalysisVerifier();

  @Test
  void zeroSpendDaysAndShanghaiMidnightAreIncludedInCorrectGroup() {
    var rows =
        List.<Map<String, Object>>of(
            Map.of("amount", "-2.00", "occurredAt", "2026-09-04T16:30:00Z"),
            Map.of("amount", "-1.00", "occurredAt", "2026-09-07T02:00:00Z"),
            Map.of("amount", "-100.00", "occurredAt", "2026-09-30T16:30:00Z"));
    var days = verifier.daily(rows, 2026, 9, Instant.parse("2026-09-30T12:00:00Z"));
    assertThat(days).hasSize(30);
    assertThat(verifier.expected(days))
        .containsEntry("weekendTotal", "2.00")
        .containsEntry("weekdayTotal", "1.00")
        .containsEntry("weekendDays", 8)
        .containsEntry("weekdayDays", 22)
        .containsEntry("difference", "0.20");
  }

  @Test
  void currentMonthStopsAtTodayAndUnavailableComparisonIsNull() {
    var days = verifier.daily(List.of(), 2026, 10, Instant.parse("2026-10-01T01:00:00Z"));
    assertThat(days).hasSize(1);
    assertThat(verifier.expected(days))
        .containsEntry("weekendDailyMean", null)
        .containsEntry("difference", null);
  }

  @Test
  void codeThatProducesMadeUpNumbersIsRejectedDespiteSuccessfulSandbox() {
    var days = verifier.daily(List.of(), 2026, 9, Instant.parse("2026-09-30T12:00:00Z"));
    var wrong = new LinkedHashMap<>(verifier.expected(days));
    wrong.put("difference", "999.99");
    assertThatThrownBy(() -> verifier.verify(wrong, days)).hasMessage("analysis_result_mismatch");
    assertThat(verifier.verify(verifier.expected(days), days)).isEqualTo(verifier.expected(days));
  }

  @Test
  void futureOrInvalidPeriodsDoNotGenerateFakeZeroSpendReports() {
    assertThatThrownBy(
            () -> verifier.daily(List.of(), 2027, 1, Instant.parse("2026-09-30T12:00:00Z")))
        .hasMessage("analysis_period_in_future");
    assertThatThrownBy(
            () -> verifier.daily(List.of(), 2026, 13, Instant.parse("2026-09-30T12:00:00Z")))
        .hasMessage("invalid_bill_period");
  }
}
