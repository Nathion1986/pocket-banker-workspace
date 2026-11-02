package com.pocketbanker.contracts;

import java.util.Map;

public interface SystemActionPort {
  Map<String, Object> execute(
      String user, String operation, String action, Map<String, Object> input);

  Map<String, Object> compensate(
      String user, String operation, String action, Map<String, Object> input);
}
