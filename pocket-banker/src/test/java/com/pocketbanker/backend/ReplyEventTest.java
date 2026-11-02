package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import com.pocketbanker.agent.ReplyEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplyEventTest {
  @Test
  void yellowShowsTheActualParametersBeforeOfferingApproval() {
    var reply =
        ReplyEvent.from(
            "message",
            Map.of(
                "id",
                "task",
                "state",
                "WAITING_CONFIRMATION",
                "version",
                1,
                "nodes",
                List.of(
                    Map.of(
                        "action_id",
                        "transfer",
                        "input",
                        Map.of(
                            "bank",
                            "A",
                            "amount",
                            "200.00",
                            "toName",
                            "妈妈",
                            "toAccountId",
                            "A-payee")))));
    assertThat(reply.text()).contains("200.00", "妈妈", "A-payee");
    assertThat(reply.markup()).isNotNull();
  }

  @Test
  void yellowHasBothButtonsAndKeepsTheVersion() {
    String task = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    var reply =
        ReplyEvent.from("msg", Map.of("id", task, "state", "WAITING_CONFIRMATION", "version", 7));
    assertThat(reply.type()).isEqualTo("confirm_request");
    assertThat(reply.markup().toString())
        .contains("confirm:" + task + ":7", "cancel:" + task + ":7");
    assertThat(("confirm:" + task + ":7").getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(64);
  }

  @Test
  void redHasNoOrdinaryApprovalAndExplainsBothFactors() {
    var reply =
        ReplyEvent.from("msg", Map.of("id", "red", "state", "WAITING_VERIFICATION", "version", 1));
    assertThat(reply.type()).isEqualTo("verify_request");
    assertThat(reply.markup()).isNull();
    assertThat(reply.text()).contains("第二因子", "/verify red 1");
  }

  @Test
  void transportEnvelopeCopiesItsPayloadWithoutMutatingTheCachedReply() {
    var source = new LinkedHashMap<String, Object>();
    source.put("kind", "thought");
    source.put("reply", "说明");
    var reply = ReplyEvent.from("msg", source);
    source.put("reply", "changed");
    assertThat(reply.text()).isEqualTo("说明");
    assertThat(reply.taskId()).isNull();
  }

  @Test
  void longRepliesGiveAReadableAppFallback() {
    var reply = ReplyEvent.from("msg", Map.of("reply", "x".repeat(5000)));
    assertThat(reply.text().length()).isLessThanOrEqualTo(4096);
    assertThat(reply.text()).contains("App");
  }
}
