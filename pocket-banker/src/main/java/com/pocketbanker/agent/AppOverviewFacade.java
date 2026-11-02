package com.pocketbanker.agent;

import static com.pocketbanker.data.BackendStore.*;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.prediction.PredictionService;
import com.pocketbanker.security.PerceptionAccess;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read projection: one forecast snapshot supplies totals and graph; never manufactures assets. */
@Service
@RequiredArgsConstructor
public class AppOverviewFacade {
  private final PerceptionAccess access;
  private final PredictionService predictions;
  private final BankBindingService bindings;
  private final BackendStore db;
  private final AppEventJournal journal;

  @SuppressWarnings("unchecked")
  public Map<String, Object> overview(String authorization, String session, int days) {
    String user = access.requireUser(authorization);
    journal.ensure(user, session);
    if (days < 1 || days > 366) throw new BackendException(400, "invalid_forecast_days");
    Map<String, Object> forecast;
    List<Map<String, Object>> accounts;
    List<Map<String, Object>> failures = new ArrayList<>();
    boolean complete;
    try {
      forecast = predictions.forecast(user, days);
      accounts = (List<Map<String, Object>>) forecast.get("accounts");
      complete = Boolean.TRUE.equals(forecast.get("complete"));
      failures.addAll((List<Map<String, Object>>) forecast.get("failures"));
    } catch (BackendException e) {
      forecast = Map.of("timeline", List.of(), "accountTimelines", List.of(), "complete", false);
      accounts = List.of();
      complete = false;
      failures.add(Map.of("reason", e.getMessage()));
    }
    List<Map<String, Object>> visible = new ArrayList<>();
    BigDecimal cash = BigDecimal.ZERO, available = BigDecimal.ZERO;
    for (var a : accounts) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("bank", a.get("bank"));
      item.put("accountId", a.get("id"));
      item.put("currency", a.get("currency"));
      item.put("balance", a.get("balance").toString());
      item.put("available", a.get("available").toString());
      item.put("frozen", Boolean.TRUE.equals(a.get("frozen")));
      item.put("asOf", db.now().toString());
      visible.add(item);
      cash = cash.add(new BigDecimal(a.get("balance").toString()));
      available = available.add(new BigDecimal(a.get("available").toString()));
    }
    var pending =
        db.rows(
            "SELECT t.id,t.version,t.state,t.required_risk AS risk,t.updated_at FROM backend_tasks t JOIN app_task_refs r ON r.task_id=t.id WHERE t.user_id=? AND r.user_id=? AND r.session_id=? AND t.state IN ('WAITING_CONFIRMATION','WAITING_VERIFICATION','READY','RUNNING','SCHEDULED','RETRY_WAIT','RECONCILING') ORDER BY t.updated_at DESC LIMIT 100",
            user,
            user,
            session);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("asOf", db.now().toString());
    out.put("userId", user);
    out.put("environment", "local_synthetic");
    out.put("currency", "CNY");
    out.put("complete", complete);
    out.put("failures", failures);
    out.put("accounts", visible);
    out.put(
        "activeBindings",
        bindings.list(user).stream().filter(b -> "active".equals(b.get("state"))).count());
    out.put(
        "totals",
        Map.of(
            "cash",
            cash.toPlainString(),
            "availableCash",
            available.toPlainString(),
            "complete",
            complete,
            "otherAssetsIncluded",
            false));
    out.put("forecast", forecast);
    out.put("pendingActions", pending);
    out.put("source", "已绑定的独立模拟银行与本人已确认事实；未确认短信和邮件不计入预测");
    return out;
  }
}
