package com.pocketbanker.adapters.nativebank;

import java.util.*;

public interface BankAdapter {
  String id();

  default boolean supports(String action) {
    return true;
  }

  default void requireSupported(String action, Map<String, Object> params) {
    if (!supports(action))
      throw new com.pocketbanker.contracts.BackendException(400, "bank_action_not_supported");
  }

  default boolean supportsCompensation(String action) {
    return true;
  }

  List<Map<String, Object>> accounts(String owner);

  Map<String, Object> account(String id);

  List<Map<String, Object>> transactions(String account, int offset, int limit);

  List<Map<String, Object>> products();

  List<Map<String, Object>> holdings(String account);

  Map<String, Object> reservation(String id, String owner);

  List<Map<String, Object>> cards(String account);

  default List<Map<String, Object>> creditBills(String account) {
    throw new com.pocketbanker.contracts.BackendException(400, "credit_bills_not_supported");
  }

  List<Map<String, Object>> subscriptions(String account);

  Map<String, Object> execute(
      String key, String owner, String verb, String account, Map<String, Object> args);

  Map<String, Object> outcome(String key, String owner);

  Map<String, Object> compensate(String key, String owner);

  boolean healthy();
}
