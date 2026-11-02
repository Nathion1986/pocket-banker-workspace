package com.pocketbanker.accounts;

import static com.pocketbanker.data.BackendStore.text;

import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.BackendStore;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BankBindingService {
  private final BackendStore db;
  private final BankAdapters banks;

  public List<Map<String, Object>> discover(String user, String bank) {
    return banks.require(bank).accounts(user);
  }

  @Transactional
  public Map<String, Object> bind(String user, String bank, String account, String consent) {
    return bind(user, bank, account, consent, Set.of("read", "write"));
  }

  @Transactional
  public Map<String, Object> bind(
      String user, String bank, String account, String consent, Set<String> scopes) {
    if (scopes == null) scopes = Set.of("read", "write");
    if (!scopes.contains("read") || !Set.of("read", "write").containsAll(scopes))
      throw new BackendException(400, "invalid_bank_scopes");
    String granted = String.join(",", new java.util.TreeSet<>(scopes));
    if (consent == null || consent.isBlank() || consent.length() > 100)
      throw new BackendException(400, "bank_consent_required");
    var found = banks.require(bank).account(account);
    if (!user.equals(found.get("owner"))) throw new BackendException(403, "account_not_owned");
    db.lockUser(user);
    if (db.one(
            "SELECT * FROM bank_bindings WHERE user_id=? AND bank=? AND account_id=?",
            user,
            bank,
            account)
        .isPresent())
      db.update(
          "UPDATE bank_bindings SET state='active',consent_id=?,scopes=? WHERE user_id=? AND bank=? AND account_id=?",
          consent,
          granted,
          user,
          bank,
          account);
    else
      db.update(
          "INSERT INTO bank_bindings(user_id,bank,account_id,state,consent_id,created_at,scopes) VALUES(?,?,?,?,?,?,?)",
          user,
          bank,
          account,
          "active",
          consent,
          db.now(),
          granted);
    return found;
  }

  @Transactional
  public void unbind(String user, String bank, String account) {
    db.lockUser(user);
    db.update(
        "UPDATE bank_bindings SET state='revoked' WHERE user_id=? AND bank=? AND account_id=?",
        user,
        bank,
        account);
  }

  public List<Map<String, Object>> list(String user) {
    return db.rows(
        "SELECT bank,account_id,state,consent_id,scopes FROM bank_bindings WHERE user_id=? ORDER BY bank,account_id",
        user);
  }

  public String requireAccount(String user, String bank, Object requested) {
    var active =
        db.rows(
            "SELECT * FROM bank_bindings WHERE user_id=? AND bank=? AND state='active'",
            user,
            bank);
    if (requested != null) {
      String id = requested.toString();
      if (active.stream().noneMatch(r -> id.equals(text(r, "account_id"))))
        throw new BackendException(403, "account_not_bound");
      return id;
    }
    if (active.size() != 1)
      throw new BackendException(
          409, active.isEmpty() ? "bank_not_bound" : "account_selection_required");
    return text(active.get(0), "account_id");
  }

  public void requireScope(String user, String bank, String account, String scope) {
    requireAccount(user, bank, account);
    var row =
        db.one(
                "SELECT scopes FROM bank_bindings WHERE user_id=? AND bank=? AND account_id=?",
                user,
                bank,
                account)
            .orElseThrow();
    if (!Set.of(text(row, "scopes").split(",")).contains(scope))
      throw new BackendException(403, "bank_scope_required_" + scope);
  }

  public Map<String, Object> account(String user, String bank, Object requested) {
    String id = requireAccount(user, bank, requested);
    var row = banks.require(bank).account(id);
    if (!user.equals(row.get("owner"))) throw new BackendException(403, "account_not_owned");
    return row;
  }
}
