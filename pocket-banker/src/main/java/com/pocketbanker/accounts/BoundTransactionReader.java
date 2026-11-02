package com.pocketbanker.accounts;

import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.contracts.BackendException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Exhaust pagination or fail explicitly; never silently classify a truncated bank statement. */
@Service
@RequiredArgsConstructor
public class BoundTransactionReader {
  private final BankBindingService bindings;
  private final BankAdapters banks;

  public List<Map<String, Object>> read(String user, Map<String, Object> input) {
    List<Map<String, Object>> output = new ArrayList<>();
    var selected =
        bindings.list(user).stream()
            .filter(
                b ->
                    "active".equals(b.get("state"))
                        && (!input.containsKey("bank") || input.get("bank").equals(b.get("bank"))))
            .toList();
    if (selected.isEmpty()) throw new BackendException(409, "bank_binding_required");
    if (input.containsKey("accountId")
        && selected.stream().noneMatch(b -> input.get("accountId").equals(b.get("account_id"))))
      throw new BackendException(403, "account_not_bound");
    for (var b : selected) {
      String bank = b.get("bank").toString(), account = b.get("account_id").toString();
      if (input.containsKey("accountId") && !account.equals(input.get("accountId"))) continue;
      bindings.requireScope(user, bank, account, "read");
      Set<String> seen = new HashSet<>();
      for (int offset = 0; ; offset += 250) {
        if (offset >= 1000000) throw new BackendException(413, "statement_exceeds_safe_page_limit");
        var rows = banks.require(bank).transactions(account, offset, 250);
        if (rows.size() > 250) throw new BackendException(502, "bank_page_size_violation");
        for (var row : rows) {
          if (!seen.add(row.get("id").toString()))
            throw new BackendException(502, "bank_pagination_repeated_transaction");
          if (row.containsKey("counterpartyAccountId")
              || row.containsKey("counterpartyBank")
              || row.containsKey("counterpartyAmount")) {
            if (!row.containsKey("counterpartyAccountId")
                || !row.containsKey("counterpartyBank")
                || !row.containsKey("counterpartyAmount"))
              throw new BackendException(502, "incomplete_counterparty_identity");
            var amount = new java.math.BigDecimal(row.get("counterpartyAmount").toString());
            if (amount.signum() <= 0
                || amount.compareTo(new java.math.BigDecimal(row.get("amount").toString()).abs())
                    > 0) throw new BackendException(502, "invalid_counterparty_amount");
          }
          Map<String, Object> copy = new LinkedHashMap<>(row);
          copy.put("bank", bank);
          output.add(copy);
        }
        if (rows.size() < 250) break;
      }
    }
    output.sort(
        Comparator.comparing(
            r -> java.time.OffsetDateTime.parse(r.get("occurredAt").toString()).toInstant()));
    return output;
  }
}
