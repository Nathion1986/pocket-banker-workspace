package com.pocketbanker.channels.web;

import com.pocketbanker.agent.HealthProbe;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {
  private final HealthProbe probe;

  @GetMapping({"/health", "/health/dependencies"})
  public Map<String, Object> health() {
    return probe.health();
  }
}
