package com.pocketbanker.channels.web;

import com.pocketbanker.agent.OperationalFacade;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OperationalController {
  private final OperationalFacade facade;

  @PostMapping("/risk/check")
  public Map<String, Object> risk(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.risk(token);
  }
}
