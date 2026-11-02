package com.pocketbanker.contracts;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.adapters.nativebank.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ActionAvailability {
  private final ActionCatalog catalog;
  private final BankAdapters banks;
  private final BankBindingService bindings;
  private final NativeHttpClient http;

  @Value("${backend.flower-url:http://127.0.0.1:8004}")
  private String flower;

  @Value("${backend.cake-url:http://127.0.0.1:8005}")
  private String cake;

  public void require(String user, ActionSpec spec, Map<String, Object> params) {
    if (spec.provider().equals("bank")) {
      String selected = Objects.toString(params.get("bank"), "");
      if (spec.id().equals("transfer")
          && ("F".equals(selected) || "F".equals(params.get("toBank")))
          && !selected.equals(params.get("toBank")))
        throw new BackendException(400, "fineract_cross_bank_not_supported");
      if (!selected.isEmpty()) {
        banks.require(selected).requireSupported(spec.id(), params);
        if (!banks.require(selected).healthy())
          throw new BackendException(503, "action_provider_unavailable");
        if (params.get("accountId") == null
            || !params.get("accountId").toString().startsWith("$")) {
          String account = bindings.requireAccount(user, selected, params.get("accountId"));
          boolean read =
              Set.of("query_balance", "query_transactions", "wealth_query", "card_query")
                  .contains(spec.id());
          bindings.requireScope(user, selected, account, read ? "read" : "write");
        }
      } else if (bindings.list(user).stream()
          .filter(b -> "active".equals(b.get("state")))
          .noneMatch(
              b ->
                  banks.require(b.get("bank").toString()).supports(spec.id())
                      && banks.require(b.get("bank").toString()).healthy()))
        throw new BackendException(503, "action_provider_unavailable");
    }
    if (Set.of("flower", "cake").contains(spec.provider()))
      try {
        var health =
            NativeHttpClient.object(
                http.send(
                    spec.provider().equals("flower") ? flower : cake,
                    "/merchant/v1/health",
                    "GET",
                    null));
        if (!"up".equals(health.get("status"))) throw new IllegalStateException();
      } catch (Exception e) {
        throw new BackendException(503, "action_provider_unavailable");
      }
  }

  public boolean bankSupports(String bank, String action) {
    return banks.require(bank).supports(action);
  }

  public boolean compensationSupported(ActionSpec spec, Map<String, Object> params) {
    if (!spec.reversible()) return false;
    if (!spec.provider().equals("bank")) return true;
    Object bank = params.get("bank");
    return bank != null
        && !bank.toString().startsWith("$")
        && banks.require(bank.toString()).supportsCompensation(spec.id());
  }

  public List<ActionSpec> list(String user) {
    return catalog.list().stream()
        .filter(
            s -> {
              try {
                require(user, s, Map.of());
                return true;
              } catch (BackendException e) {
                return false;
              }
            })
        .toList();
  }
}
