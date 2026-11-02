package com.pocketbanker.channels.web;

import com.pocketbanker.agent.BackendFacade;
import com.pocketbanker.contracts.*;
import com.pocketbanker.profile.ProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class BackendController {
  private final BackendFacade facade;

  public record Binding(
      @NotBlank String accountId,
      @NotBlank @Size(max = 100) String consentId,
      Set<String> scopes) {}

  public record Approval(boolean approved, @Min(1) long version) {}

  public record Verification(
      @NotBlank String code, @NotBlank String secondFactor, @Min(1) long version) {}

  public record Control(@NotBlank String command, String value, @Min(1) long version) {}

  public record Tuition(
      @NotBlank String eventId, @NotBlank String amount, @NotBlank String dueDate) {}

  public record Recipient(
      @NotBlank String bank, @NotBlank String accountId, @NotBlank String name) {}

  public record Policy(@Min(2) @Max(3) int reviewers, @AssertTrue boolean redRequiresTwoFactors) {}

  public record Payment(@NotBlank String personId, @NotBlank String reference) {}

  @PostMapping("/perception/flows/{source}/sync")
  public Map<String, Object> flows(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String source) {
    return facade.flows(token, source);
  }

  @GetMapping("/analysis/datasets")
  public Map<String, Object> schemas(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.datasetSchemas(token);
  }

  @GetMapping("/actions")
  public List<ActionSpec> actions(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.actions(token);
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody WorkflowPlan input) {
    return facade.create(token, input);
  }

  @GetMapping("/tasks")
  public List<Map<String, Object>> tasks(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.taskList(token, page);
  }

  @GetMapping("/tasks/{id}")
  public Map<String, Object> task(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.task(token, id);
  }

  @PostMapping("/tasks/{id}/confirm")
  public Map<String, Object> confirm(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Approval input) {
    return com.pocketbanker.agent.ReplyEvent.envelope(
        "confirm:" + id + ":" + input.version(),
        facade.confirm(token, id, input.approved(), input.version()));
  }

  @GetMapping("/tasks/{id}/audit")
  public List<Map<String, Object>> taskAudit(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.taskAudit(token, id, page);
  }

  @PostMapping("/tasks/{id}/verify")
  public Map<String, Object> verify(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Verification input) {
    return com.pocketbanker.agent.ReplyEvent.envelope(
        "verify:" + id + ":" + input.version(),
        facade.verify(token, id, input.code(), input.secondFactor(), input.version()));
  }

  @GetMapping("/tasks/{id}/demo-challenge")
  public Map<String, Object> demo(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.demo(token, id);
  }

  @PostMapping("/tasks/{id}/control")
  public Map<String, Object> control(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Control input) {
    return facade.control(token, id, input.command(), input.value(), input.version());
  }

  @GetMapping("/banks/{bank}/accounts")
  public List<Map<String, Object>> discover(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String bank) {
    return facade.discover(token, bank);
  }

  @PostMapping("/banks/{bank}/bindings")
  public Map<String, Object> bind(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String bank,
      @Valid @RequestBody Binding input) {
    return facade.bind(token, bank, input.accountId(), input.consentId(), input.scopes());
  }

  @DeleteMapping("/banks/{bank}/bindings/{account}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unbind(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String bank,
      @PathVariable String account) {
    facade.unbind(token, bank, account);
  }

  @GetMapping("/banks/bindings")
  public List<Map<String, Object>> bindings(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.bindings(token);
  }

  @GetMapping("/profile")
  public Map<String, Object> profile(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.profile(token);
  }

  @PostMapping("/profile/persons")
  public Map<String, Object> person(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestBody ProfileService.PersonInput input) {
    return facade.person(token, input);
  }

  @PutMapping("/profile/persons/{id}")
  public Map<String, Object> updatePerson(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestBody ProfileService.PersonInput input) {
    return facade.updatePerson(token, id, input);
  }

  @PostMapping("/profile/relations/{id}/returned")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void returned(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestBody Map<String, String> input) {
    facade.closeRelation(token, id, input.get("eventId"));
  }

  @PostMapping("/profile/facts")
  public Map<String, Object> fact(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestBody ProfileService.FactInput input) {
    return facade.fact(token, input);
  }

  @PostMapping("/profile/relations")
  public Map<String, Object> relation(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestBody ProfileService.RelationInput input) {
    return facade.relation(token, input);
  }

  @DeleteMapping("/profile/facts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forgetFact(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    facade.forgetFact(token, id);
  }

  @DeleteMapping("/profile/persons/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forgetPerson(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    facade.forgetPerson(token, id);
  }

  @GetMapping("/predictions/liquidity")
  public Map<String, Object> forecast(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "30") int days) {
    return facade.forecast(token, days);
  }

  @PostMapping("/proposals/funding")
  public Map<String, Object> funding(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestBody com.pocketbanker.prediction.FundingCoordinator.Request input) {
    return facade.funding(token, input);
  }

  @PostMapping("/proposals/{id}/activate")
  public Map<String, Object> activateFunding(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.acceptFunding(token, id);
  }

  @PostMapping("/proposals/tuition")
  public Map<String, Object> tuition(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Tuition input) {
    return facade.tuition(token, input.eventId(), input.amount(), input.dueDate());
  }

  @GetMapping("/proposals")
  public List<Map<String, Object>> proposals(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.proposals(token);
  }

  @PostMapping("/proposals/{id}/confirm")
  public Map<String, Object> accept(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Recipient input) {
    return facade.acceptProposal(token, id, input.bank(), input.accountId(), input.name());
  }

  @PostMapping("/proposals/{id}/reject")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reject(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    facade.rejectProposal(token, id);
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> notifications(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.notifications(token, page);
  }

  @PostMapping("/notifications/{id}/ack")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void acknowledge(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    facade.acknowledge(token, id);
  }

  @GetMapping("/aa/{id}")
  public Map<String, Object> aa(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.aa(token, id);
  }

  @PostMapping("/aa/{id}/payments")
  public Map<String, Object> paid(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Payment input) {
    return facade.aaPaid(token, id, input.personId(), input.reference());
  }

  @PostMapping("/aa/{id}/cancel")
  public Map<String, Object> cancelAa(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.cancelAa(token, id);
  }

  @GetMapping("/aa/invitations/{id}")
  public Map<String, Object> invitation(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestParam(required = false) String shareToken) {
    return facade.invitation(token, id, shareToken);
  }

  public record InvitationPayment(@NotBlank String reference) {}

  @PostMapping("/aa/invitations/{id}/payments")
  public Map<String, Object> invitationPaid(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody InvitationPayment input) {
    return facade.invitationPaid(token, id, input.reference());
  }

  @GetMapping("/models/policy")
  public Map<String, Object> policy(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.modelPolicy(token);
  }

  @PutMapping("/models/policy")
  public Map<String, Object> policy(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Policy input) {
    return facade.modelPolicy(token, input.reviewers(), input.redRequiresTwoFactors());
  }
}
