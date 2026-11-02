package com.pocketbanker.channels.web;

import com.pocketbanker.agent.AppPerceptionFacade;
import com.pocketbanker.profile.CandidateDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/perception")
@CrossOrigin(origins = "https://appassets.androidplatform.net")
@RequiredArgsConstructor
@Validated
public class AppPerceptionController {
  private final AppPerceptionFacade facade;

  public record AppCandidateCorrection(
      @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String fingerprint,
      @NotNull @Valid CandidateDraft draft) {}

  public record AppEventDecision(
      @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String fingerprint,
      @NotBlank @Pattern(regexp = "confirm|reject|forget|merge|separate") String decision) {}

  @GetMapping
  public Map<String, Object> overview(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int candidatePage,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int factPage,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int sourcePage,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int receiptPage) {
    return facade.overview(token, candidatePage, factPage, sourcePage, receiptPage);
  }

  @PostMapping("/events/{id}/correction")
  public Map<String, Object> correct(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody AppCandidateCorrection input) {
    return facade.correct(token, id, input.fingerprint(), input.draft());
  }

  @PostMapping("/events/{id}/decision")
  public Map<String, Object> decide(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody AppEventDecision input) {
    return facade.decide(token, id, input.fingerprint(), input.decision());
  }
}
