package com.pocketbanker.orchestration;

import com.pocketbanker.shadow.ScopedShadowGateway.Prompt;
import java.util.Map;

public interface ModelProvider {
  String id();

  default String identity() {
    return id();
  }

  default String role() {
    return "unspecified";
  }

  default java.util.Set<String> capabilities() {
    return java.util.Set.of("recognition", "review", "arbitration");
  }

  default String health() {
    return "unprobed";
  }

  Map<String, Object> invoke(Prompt prompt, String purpose);
}
