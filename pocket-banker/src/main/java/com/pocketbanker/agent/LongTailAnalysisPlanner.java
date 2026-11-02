package com.pocketbanker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.orchestration.HttpPlanningProvider;
import com.pocketbanker.orchestration.PlanningProvider;
import com.pocketbanker.sandbox.AnalysisVerifier;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Mode C: code generation sees only schema and a locally normalized question shape. */
@Service
public class LongTailAnalysisPlanner {
  private final PlanningProvider provider;
  private final long timeout;

  public LongTailAnalysisPlanner(
      ObjectMapper mapper,
      @Value("${backend.analysis-code.url:}") String url,
      @Value("${backend.analysis-code.api-key:}") String key,
      @Value("${backend.analysis-code.model:}") String model,
      @Value("${backend.model-mode:mock}") String mode,
      @Value("${backend.analysis-code.timeout-ms:2000}") long timeout) {
    if (timeout < 100 || timeout > 10000)
      throw new IllegalArgumentException("invalid_analysis_timeout");
    this.timeout = timeout;
    provider =
        url.isBlank()
            ? null
            : new HttpPlanningProvider(
                mapper,
                url,
                key,
                model,
                mode.equals("local-protocol"),
                "Generate read-only Python code using only data, Decimal, len, sum and safe comprehensions. Return exactly JSON {code:string}. No imports, filesystem, network or hardcoded financial outputs. data contains every calendar day including zero expense. Compute weekendTotal, weekdayTotal, weekendDays, weekdayDays, weekendDailyMean, weekdayDailyMean, difference in result. Money outputs are exact two-decimal strings using Decimal HALF_EVEN, or null when no group days exist. Never access private original data or execute actions.");
  }

  public String generate(String type) {
    if (!"weekend_daily_spend".equals(type))
      throw new BackendException(400, "analysis_scope_required");
    if (provider == null) throw new BackendException(503, "analysis_provider_not_configured");
    var shape =
        Map.<String, Object>of(
            "schema",
            AnalysisVerifier.schema(),
            "question",
            Map.of(
                "operation",
                "compare_groups",
                "groupField",
                "dayType",
                "groups",
                List.of("weekend", "weekday"),
                "measure",
                "expenseMinor",
                "aggregation",
                "mean_per_calendar_day"));
    var output = provider.propose(shape, Duration.ofMillis(timeout));
    if (!output.keySet().equals(Set.of("code"))
        || !(output.get("code") instanceof String code)
        || code.isBlank()
        || code.length() > 16000)
      throw new BackendException(502, "analysis_code_contract_violation");
    return code;
  }

  public String health() {
    return provider == null ? "disabled" : provider.health();
  }
}
