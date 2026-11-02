package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pocketbanker.accounts.BoundTransactionReader;
import com.pocketbanker.actions.workflow.SystemActions;
import com.pocketbanker.data.BackendStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StatementPrecisionTest {
  private Map<String, Object> analyze(String precision) {
    var store = mock(BackendStore.class);
    when(store.now()).thenReturn(Instant.parse("2026-10-04T12:00:00Z"));
    var reader = mock(BoundTransactionReader.class);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 1; i <= 3; i++)
      rows.add(
          Map.of(
              "id", "tx-" + i,
              "accountId", "F-1",
              "bank", "F",
              "amount", "-20.00",
              "merchant", "Transfer",
              "city", "未知",
              "occurredAt", "2026-10-04T00:00:00Z",
              "timePrecision", precision));
    when(reader.read("alpha", Map.of())).thenReturn(rows);
    return new SystemActions(null, null, store, null, null, null, null, null, null, reader, null)
        .anomalies("alpha");
  }

  @Test
  void dateOnlyStatementsMustNotInventMinuteLevelFraudSignals() {
    assertThat((List<?>) analyze("day").get("anomalies")).isEmpty();
  }

  @Test
  void preciseStatementsStillDetectDuplicateAndHighFrequencyDebits() {
    assertThat(analyze("instant").toString()).contains("duplicate_debit", "high_frequency");
  }
}
