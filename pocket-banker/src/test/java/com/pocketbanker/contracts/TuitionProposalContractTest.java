package com.pocketbanker.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketbanker.contracts.WorkflowPlan.Node;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * 场景F回归：tuition_planning 输出的 proposal.steps 曾直接放 Node record，
 * SchemaValidator 的 object 分支要求 instanceof Map → action_result_contract_violation。
 * 修复后 steps 必须是纯 Map；本测试锁定该行为（教训：内存对象先转 Map 再过契约）。
 */
class TuitionProposalContractTest {

  private Map<String, Object> sample(Object steps) {
    Map<String, Object> proposal = new LinkedHashMap<>();
    proposal.put("id", UUID.randomUUID().toString());
    proposal.put("type", "proposal");
    proposal.put("status", "pending");
    proposal.put("gap", "13600.00");
    proposal.put("required", "20000.00");
    proposal.put("steps", steps);
    proposal.put("dueDate", "2027-01-01");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("operationId", "op-1");
    out.put("status", "SUCCEEDED");
    out.put("proposal", proposal);
    return out;
  }

  @Test
  void mapStepsPassContract() {
    Node n = new Node("redeem", "wealth_redeem",
        Map.of("bank", "A", "accountId", "A-U001", "amount", "13600.00", "productId", "P1"),
        List.of());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", n.id());
    m.put("actionId", n.actionId());
    m.put("params", n.params());
    m.put("dependsOn", n.dependsOn());
    assertDoesNotThrow(() ->
        SchemaValidator.validate(sample(List.of(m)), ContractSchemas.output("tuition_planning", "system"), 502, "x"));
  }

  @Test
  void pojoStepsFailContract() {
    Node n = new Node("redeem", "wealth_redeem",
        Map.of("bank", "A", "accountId", "A-U001", "amount", "13600.00", "productId", "P1"),
        List.of());
    assertThrows(RuntimeException.class, () ->
        SchemaValidator.validate(sample(List.of(n)), ContractSchemas.output("tuition_planning", "system"), 502, "x"));
  }

  @Test
  void emptyStepsPassContract() {
    assertDoesNotThrow(() ->
        SchemaValidator.validate(sample(List.of()), ContractSchemas.output("tuition_planning", "system"), 502, "x"));
  }
}
