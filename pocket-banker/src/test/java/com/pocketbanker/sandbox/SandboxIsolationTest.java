package com.pocketbanker.sandbox;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SandboxIsolationTest {
  @Test
  void processRunnerRejectsMutationReflectionImportsAndNetworking() {
    var sandbox = new ReadOnlySandbox(new ObjectMapper(), "python3", "process");
    for (String code :
        List.of(
            "import os; result=os.listdir('/')",
            "result = data.__class__",
            "data[0] = 7; result=data",
            "result=__import__('socket')"))
      assertThatThrownBy(() -> sandbox.analyze(code, List.of(Map.of("amount", "100.00"))))
          .isInstanceOf(BackendException.class);
  }

  @Test
  void resourceLimitsBoundUntrustedComputation() {
    var sandbox = new ReadOnlySandbox(new ObjectMapper(), "python3", "process");
    assertThatThrownBy(() -> sandbox.analyze("result=sum(x for x in range(1000000000))", List.of()))
        .isInstanceOf(BackendException.class);
  }
}
