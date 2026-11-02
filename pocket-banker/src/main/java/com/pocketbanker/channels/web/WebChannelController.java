package com.pocketbanker.channels.web;

import com.pocketbanker.agent.BackendFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class WebChannelController {
  private final BackendFacade facade;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public record Message(
      @NotBlank @Size(max = 8192) String text,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String sessionId,
      @NotBlank @Size(max = 160) String requestId) {}

  public record Approval(boolean approved, @Min(1) long version) {}

  public record Verification(
      @NotBlank String code, @NotBlank String secondFactor, @Min(1) long version) {}

  @PostMapping("/chat")
  public Map<String, Object> chat(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody Message input) {
    var result =
        new LinkedHashMap<>(facade.chat(token, input.sessionId(), input.requestId(), input.text()));
    result.put("replyEvent", com.pocketbanker.agent.ReplyEvent.from(input.requestId(), result));
    return result;
  }

  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestHeader(value = "Last-Event-ID", required = false) String last,
      @Valid @RequestBody Message input) {
    int after = -1;
    if (last != null && !last.isBlank()) {
      for (int i = 0; i < 3; i++)
        if (last.equals(input.requestId() + ":" + List.of("status", "result", "done").get(i))
            || i == 1 && last.equals(input.requestId() + ":error")) after = i;
      if (after < 0)
        throw new com.pocketbanker.contracts.BackendException(400, "invalid_stream_cursor");
    }
    var result =
        after < 0
            ? facade.chat(token, input.sessionId(), input.requestId(), input.text())
            : facade.cachedChat(token, input.sessionId(), input.requestId(), input.text());
    var event = com.pocketbanker.agent.ReplyEvent.from(input.requestId(), result);
    int resumeAfter = after;
    SseEmitter emitter = new SseEmitter(15000L);
    java.util.concurrent.CompletableFuture.runAsync(
        () -> {
          try {
            if (resumeAfter < 0)
              emitter.send(
                  SseEmitter.event()
                      .id(input.requestId() + ":status")
                      .name("status")
                      .data(Map.of("state", event.state(), "requestId", input.requestId())));
            if (resumeAfter < 1) {
              var body = new LinkedHashMap<>(result);
              body.put("replyEvent", event);
              emitter.send(
                  SseEmitter.event()
                      .id(input.requestId() + (result.containsKey("error") ? ":error" : ":result"))
                      .name(result.containsKey("error") ? "error" : "result")
                      .data(body));
            }
            if (resumeAfter < 2)
              emitter.send(
                  SseEmitter.event()
                      .id(input.requestId() + ":done")
                      .name("done")
                      .data(Map.of("requestId", input.requestId(), "transportComplete", true)));
            emitter.complete();
          } catch (Exception e) {
            emitter.completeWithError(e);
          }
        });
    return emitter;
  }

  @GetMapping(value = "/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter taskStream(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    Map<String, Object> state = facade.task(token, id);
    if (last != null
        && !last.isBlank()
        && !last.matches(java.util.regex.Pattern.quote(id) + ":[a-f0-9]{64}(?::(?:state|done))?"))
      throw new com.pocketbanker.contracts.BackendException(400, "invalid_stream_cursor");
    SseEmitter emitter = new SseEmitter(15000L);
    java.util.concurrent.CompletableFuture.runAsync(
        () -> {
          try {
            String eventId =
                id
                    + ":"
                    + com.pocketbanker.security.PerceptionAccess.hash(
                        mapper.writeValueAsString(state));
            if (!eventId.equals(last)
                && !(eventId + ":state").equals(last)
                && !(eventId + ":done").equals(last))
              emitter.send(
                  SseEmitter.event().id(eventId + ":state").name("task_state").data(state));
            if (!(eventId + ":done").equals(last))
              emitter.send(
                  SseEmitter.event()
                      .id(eventId + ":done")
                      .name("done")
                      .data(Map.of("taskId", id, "transportComplete", true)));
            emitter.complete();
          } catch (Exception e) {
            emitter.completeWithError(e);
          }
        });
    return emitter;
  }

  @PostMapping("/confirm/{id}")
  public Map<String, Object> confirm(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Approval input) {
    return com.pocketbanker.agent.ReplyEvent.envelope(
        "confirm:" + id + ":" + input.version(),
        facade.confirm(token, id, input.approved(), input.version()));
  }

  @PostMapping("/verify/{id}")
  public Map<String, Object> verify(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Verification input) {
    return com.pocketbanker.agent.ReplyEvent.envelope(
        "verify:" + id + ":" + input.version(),
        facade.verify(token, id, input.code(), input.secondFactor(), input.version()));
  }

  @GetMapping("/audit")
  public Map<String, Object> audit(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return Map.of("code", 0, "data", facade.audit(token, page));
  }
}
