package com.pocketbanker.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record WorkflowPlan(
    @NotBlank @Size(max = 160) String requestId,
    @NotNull @Size(min = 1, max = 30) List<@Valid Node> nodes,
    @NotNull @Valid Trigger trigger,
    @Size(max = 36) String sourceEventId,
    boolean modelDegraded,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        boolean requireCompensation,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Size(max = 40)
        String executionDeadline) {
  public WorkflowPlan(
      String requestId,
      List<Node> nodes,
      Trigger trigger,
      String sourceEventId,
      boolean modelDegraded) {
    this(requestId, nodes, trigger, sourceEventId, modelDegraded, false, null);
  }

  public record Node(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String id,
      @NotBlank @Size(max = 80) String actionId,
      @NotNull Map<String, Object> params,
      @NotNull @Size(max = 30) List<@Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String> dependsOn) {}

  public record Trigger(
      @NotBlank @Pattern(regexp = "immediate|at|recurring|event") String type,
      @Size(max = 300) String value) {}
}
