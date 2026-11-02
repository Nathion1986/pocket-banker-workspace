package com.pocketbanker.channels.web;

import com.pocketbanker.security.AppAuthentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "https://appassets.androidplatform.net")
@RequiredArgsConstructor
public class AppAuthController {
  private final AppAuthentication auth;

  public record Login(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

  public record Setup(
      @NotBlank @Size(max = 256) String setupKey,
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(min = 12, max = 128) String password) {}

  public record MfaLogin(
      @NotBlank @Size(max = 100) String challengeId,
      @Pattern(regexp = "[0-9]{6}") @NotNull String totp) {}

  public record Refresh(@NotBlank @Size(max = 100) String refreshToken) {}

  public record Password(@NotBlank @Size(max = 128) String password) {}

  public record Code(@Pattern(regexp = "[0-9]{6}") @NotNull String totp) {}

  public record Change(
      @NotBlank @Size(max = 128) String password,
      @NotBlank @Size(min = 12, max = 128) String newPassword,
      @Pattern(regexp = "[0-9]{6}") @NotNull String totp) {}

  public record Recovery(
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 128) String password,
      @NotBlank @Size(max = 100) String recoveryCode) {}

  @GetMapping("/config")
  public Map<String, Object> config() {
    return auth.config();
  }

  @PostMapping("/setup")
  public Map<String, Object> setup(@Valid @RequestBody Setup in) {
    return auth.setup(in.setupKey(), in.username(), in.password());
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody Login in) {
    return auth.login(in.username(), in.password());
  }

  @PostMapping("/login/mfa")
  public Map<String, Object> loginMfa(@Valid @RequestBody MfaLogin in) {
    return auth.loginMfa(in.challengeId(), in.totp());
  }

  @PostMapping("/refresh")
  public Map<String, Object> refresh(@Valid @RequestBody Refresh in) {
    return auth.refresh(in.refreshToken());
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(
      @RequestHeader(value = "Authorization", required = false) String token) {
    auth.logout(token);
    return Map.of("loggedOut", true);
  }

  @GetMapping("/me")
  public Map<String, Object> me(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return auth.me(token);
  }

  @PostMapping("/mfa/enroll")
  public Map<String, Object> enroll(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Password in) {
    return auth.enroll(token, in.password());
  }

  @PostMapping("/mfa/confirm")
  public Map<String, Object> confirm(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Code in) {
    return auth.confirmMfa(token, in.totp());
  }

  @PostMapping("/mfa/recover")
  public Map<String, Object> recover(@Valid @RequestBody Recovery in) {
    return auth.recover(in.username(), in.password(), in.recoveryCode());
  }

  @PostMapping("/password/change")
  public Map<String, Object> change(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Change in) {
    auth.changePassword(token, in.password(), in.totp(), in.newPassword());
    return Map.of("changed", true, "loginRequired", true);
  }
}
