package com.pocketbanker.perception.spi;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.perception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmsSource implements PerceptionSource {
  private final PerceptionService service;

  public String type() {
    return "SMS";
  }

  public Map<String, Object> capabilities() {
    return Map.of("type", type(), "input", "webhook", "rawStorage", false);
  }

  public Object ingest(String authorization, Object message) {
    if (!(message instanceof PerceptionRequests.TextSignal signal))
      throw new BackendException(400, "source_message_type_mismatch");
    return service.ingest(authorization, "SMS", signal);
  }
}
