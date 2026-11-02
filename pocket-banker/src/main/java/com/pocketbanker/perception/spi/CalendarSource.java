package com.pocketbanker.perception.spi;

import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.perception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CalendarSource implements PerceptionSource {
  private final SupplementalPerception service;
  private final CalendarConnector calendar;

  public String type() {
    return "CALENDAR";
  }

  public Map<String, Object> capabilities() {
    return Map.of(
        "type",
        type(),
        "input",
        "structured-revision-import",
        "icsImport",
        true,
        "durableRecovery",
        true,
        "queuePath",
        "/api/v1/perception/calendar/queue",
        "pull",
        "CalDAV",
        "rawStorage",
        false);
  }

  public Object ingest(String authorization, Object message) {
    if (message instanceof CalendarConnector.Import input)
      return calendar.ingest(authorization, input);
    if (!(message instanceof SupplementalPerception.CalendarInput signal))
      throw new BackendException(400, "source_message_type_mismatch");
    return service.calendar(authorization, signal);
  }
}
