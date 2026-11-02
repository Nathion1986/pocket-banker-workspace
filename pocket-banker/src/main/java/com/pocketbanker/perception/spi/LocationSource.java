package com.pocketbanker.perception.spi;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.perception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocationSource implements PerceptionSource {
  private final LocationService service;

  public String type() {
    return "LOCATION";
  }

  public Map<String, Object> capabilities() {
    return Map.of("type", type(), "input", "short-lived-challenge", "rawStorage", false);
  }

  public Object ingest(String authorization, Object message) {
    if (!(message instanceof PerceptionRequests.LocationReply signal))
      throw new BackendException(400, "source_message_type_mismatch");
    return service.reply(authorization, signal);
  }
}
