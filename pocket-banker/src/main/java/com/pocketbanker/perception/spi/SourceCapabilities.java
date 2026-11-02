package com.pocketbanker.perception.spi;

import com.pocketbanker.contracts.BackendException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceCapabilities {
  private final List<PerceptionSource> sources;

  public List<Map<String, Object>> list() {
    return sources.stream().map(PerceptionSource::capabilities).toList();
  }

  public Object ingest(String kind, String authorization, Object message) {
    return sources.stream()
        .filter(s -> s.type().equals(kind))
        .findFirst()
        .orElseThrow(() -> new BackendException(400, "source_adapter_not_registered"))
        .ingest(authorization, message);
  }
}
