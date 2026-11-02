package com.pocketbanker.perception.spi;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.perception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HealthSource implements PerceptionSource {
  private final SupplementalPerception service;

  public String type() {
    return "HEALTH";
  }

  public Map<String, Object> capabilities() {
    return Map.of(
        "type",
        type(),
        "input",
        "coverage-hint-only",
        "rawStorage",
        false,
        "durableRecovery",
        true,
        "queuePath",
        "/api/v1/perception/health/queue");
  }

  public Object ingest(String authorization, Object message) {
    if (!(message instanceof SupplementalPerception.HealthInput signal))
      throw new BackendException(400, "source_message_type_mismatch");
    return service.health(authorization, signal);
  }
}
