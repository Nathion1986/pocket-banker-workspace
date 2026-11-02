package com.pocketbanker.channels.web;

import com.pocketbanker.agent.TelegramFacade;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channels/telegram")
@RequiredArgsConstructor
public class TelegramController {
  private final TelegramFacade facade;

  @PostMapping("/link")
  public Map<String, Object> link(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody(required = false) Map<String, String> input) {
    return facade.link(auth, input == null ? "shared" : input.getOrDefault("sessionId", "shared"));
  }

  @GetMapping("")
  public Map<String, Object> status(
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return facade.status(auth);
  }

  @DeleteMapping("")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void unlink(@RequestHeader(value = "Authorization", required = false) String auth) {
    facade.unlink(auth);
  }

  @GetMapping("/deliveries")
  public java.util.List<Map<String, Object>> deliveries(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam(defaultValue = "0") int page) {
    return facade.deliveries(auth, page);
  }

  @PostMapping("/webhook")
  public Map<String, Object> webhook(
      @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String key,
      @RequestBody Map<String, Object> update) {
    return facade.receive(key, update);
  }
}
