package com.pocketbanker.channels.web;

import com.pocketbanker.agent.AppGatewayFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = {"https://appassets.androidplatform.net"})
public class AppGatewayController {
  private final AppGatewayFacade app;
  private final com.pocketbanker.agent.AppOverviewFacade overview;

  @GetMapping("/overview")
  public Map<String, Object> overview(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @RequestParam(defaultValue = "30") int days) {
    return overview.overview(auth, session, days);
  }

  public record AppMessage(
      @NotBlank @Size(max = 8192) String text,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,160}") String clientMessageId) {}

  public record AppDecision(@NotBlank String decision, @NotNull @Min(1) Long version) {}

  public record AppVerification(
      @NotBlank String code, @NotBlank String secondFactor, @NotNull @Min(1) Long version) {}

  public record AuthChallenge(@NotNull @Min(1) Long version) {}

  public record AuthVerify(
      @NotBlank @Size(max = 36) String challengeId,
      @NotNull @Min(1) Long version,
      @NotBlank @Size(max = 128) String password,
      @NotNull @Pattern(regexp = "[0-9]{6}") String totp) {}

  @PostMapping("/proposals/{id}/auth-challenge")
  public Map<String, Object> authChallenge(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @PathVariable String id,
      @Valid @RequestBody AuthChallenge input) {
    return app.authChallenge(auth, session, id, input.version());
  }

  @PostMapping("/proposals/{id}/auth-verify")
  public Map<String, Object> authVerify(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @Valid @RequestBody AuthVerify input) {
    return app.authVerify(
        auth,
        session,
        id,
        input.challengeId(),
        input.version(),
        input.password(),
        input.totp(),
        key);
  }

  @GetMapping("/link-status")
  public Map<String, Object> status(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session) {
    return app.status(auth, session);
  }

  @PostMapping("/link-code")
  public Map<String, Object> link(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session) {
    return app.link(auth, session);
  }

  @DeleteMapping("/link")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void unlink(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session) {
    app.unlink(auth, session);
  }

  @PostMapping("/messages")
  public Map<String, Object> message(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @Valid @RequestBody AppMessage input) {
    return app.message(auth, session, input.clientMessageId(), input.text());
  }

  @GetMapping("/events")
  public Map<String, Object> events(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @RequestParam(defaultValue = "0") long since,
      @RequestParam(defaultValue = "100") int limit) {
    return app.events(auth, session, since, limit);
  }

  @GetMapping("/proposals/{id}")
  public Map<String, Object> task(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @PathVariable String id) {
    return app.task(auth, session, id);
  }

  @PostMapping("/proposals/{id}/decision")
  public Map<String, Object> decide(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @Valid @RequestBody AppDecision input) {
    return app.decide(auth, session, id, input.decision(), input.version(), key);
  }

  @PostMapping("/proposals/{id}/verify")
  public Map<String, Object> verify(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader("X-Demo-Session") String session,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @Valid @RequestBody AppVerification input) {
    return app.verify(auth, session, id, input.code(), input.secondFactor(), input.version(), key);
  }
}
