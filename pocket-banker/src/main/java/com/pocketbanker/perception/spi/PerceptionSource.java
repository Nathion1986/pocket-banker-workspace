package com.pocketbanker.perception.spi;

import java.util.Map;

public interface PerceptionSource {
  String type();

  Map<String, Object> capabilities();

  Object ingest(String authorization, Object message);
}
