package com.pocketbanker.orchestration;

import com.pocketbanker.contracts.BackendException;

import java.time.Duration;
import java.util.Map;

/** Generates plan structure from public contracts and opaque locally bound parameter references. */
public interface PlanningProvider {
  Map<String, Object> propose(Map<String, Object> request, Duration budget);

  /** 开放意图理解：规则未命中时的自然语言→意图+槽位（失败抛 503，由调用方兜底）。 */
  default Map<String, Object> understand(
        Map<String, Object> request, Duration budget, String overrideModel) {
    throw new BackendException(503, "planner_unavailable");
  }

  default String health() {
    return "configured_unprobed";
  }
}
