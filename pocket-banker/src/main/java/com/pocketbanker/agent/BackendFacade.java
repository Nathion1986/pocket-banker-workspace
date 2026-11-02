package com.pocketbanker.agent;

import com.pocketbanker.accounts.BankBindingService;
import com.pocketbanker.actions.workflow.SystemActions;
import com.pocketbanker.agent.rules.RuleBasedIntent;
import com.pocketbanker.contracts.*;
import com.pocketbanker.notifications.NotificationService;
import com.pocketbanker.orchestration.ModelCoordinator;
import com.pocketbanker.prediction.PredictionService;
import com.pocketbanker.profile.ProfileService;
import com.pocketbanker.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BackendFacade {
  private final PerceptionAccess access;
  private final WorkflowTransactions tasks;
  private final WorkflowExecutor executor;
  private final BackendPlanner planner;
  private final ConversationTransactions conversations;
  private final ModelCoordinator models;
  private final ActionCatalog catalog;
  private final ActionAvailability availability;
  private final BankBindingService banks;
  private final ProfileService profile;
  private final PredictionService prediction;
  private final NotificationService notifications;
  private final BackendAudit audit;
  private final InjectionDetector injection;
  private final BackendPermissionService permission;
  private final com.pocketbanker.shadow.ScopedShadowGateway shadow;
  private final SystemActions system;
  private final FlowPerceptionCoordinator flowCoordinator;
  private final com.pocketbanker.prediction.FundingCoordinator funding;

  public String user(String token) {
    return access.requireUser(token);
  }

  public Map<String, Object> task(String token, String id) {
    return tasks.view(user(token), id);
  }

  public List<Map<String, Object>> taskList(String token, int page) {
    return tasks.list(user(token), page);
  }

  public Map<String, Object> create(String token, WorkflowPlan plan) {
    return createOwned(user(token), plan);
  }

  public Map<String, Object> createOwned(String user, WorkflowPlan plan) {
    return createOwned(user, plan, null);
  }

  private Map<String, Object> createOwned(String user, WorkflowPlan plan, String proposal) {
    permission.requireUnlocked(user);
    tasks.validate(plan, user);
    for (var node : plan.nodes()) {
      if (!node.actionId().equals("sandbox_analysis") && unsafe(node.params())) {
        conversations.injectionFailure(user);
        throw new BackendException(400, "injection_blocked");
      }
    }
    Map<String, Object> features =
        Map.of(
            "kind",
            "plan",
            "type",
            plan.trigger().type(),
            "nodes",
            plan.nodes().stream()
                .map(
                    n ->
                        Map.of(
                            "actionId",
                            n.actionId(),
                            "params",
                            n.actionId().equals("sandbox_analysis")
                                ? Map.of(
                                    "schema",
                                    shadow.analysisSchema(n.params().get("datasetId").toString()))
                                : n.params()))
                .toList());
    String planSession = "plan-" + UUID.randomUUID();
    String risk = "GREEN";
    for (var node : plan.nodes())
      risk =
          BackendPermissionService.max(
              risk,
              permission.risk(
                  user,
                  catalog.require(node.actionId()),
                  node.params(),
                  plan.modelDegraded(),
                  null));
    var review =
        models.review(
            user,
            planSession,
            features,
            risk.equals("GREEN") ? "green" : risk.equals("RED") ? "red_action" : "plan",
            false);
    // fail-closed=更严而非更松：模型明确反对才拒；弃权(超时/不可用)放行但标记 degraded → 权限自动升档
    boolean degradedByAbstain = review.decision().equals("abstain");
    if (review.decision().equals("reject"))
      throw new BackendException(409, "model_review_requires_clarification");
    var effective =
        new WorkflowPlan(
            plan.requestId(),
            plan.nodes(),
            plan.trigger(),
            plan.sourceEventId(),
            plan.modelDegraded() || review.degraded() || degradedByAbstain,
            plan.requireCompensation(),
            plan.executionDeadline());
    String id =
        proposal == null
            ? tasks.create(user, effective)
            : tasks.createProposal(user, proposal, effective);
    audit.linkModelTrace(user, PerceptionAccess.hash(user + ":" + planSession), id);
    audit.write(
        user,
        id,
        null,
        "plan_model_review",
        Map.of(
            "providers",
            review.providers(),
            "decision",
            review.decision(),
            "policyVersion",
            review.policyVersion(),
            "degraded",
            review.degraded()));
    executor.enqueue(id);
    return tasks.view(user, id);
  }

  public Map<String, Object> confirmOwned(String user, String id, boolean approved, long version) {
    var view = tasks.confirm(user, id, approved, version);
    executor.enqueue(id);
    return view;
  }

  public Map<String, Object> confirm(String token, String id, boolean approved, long version) {
    String user = user(token);
    var view = tasks.confirm(user, id, approved, version);
    executor.enqueue(id);
    return view;
  }

  public Map<String, Object> verify(
      String token, String id, String code, String factor, long version) {
    return verifyOwned(user(token), id, code, factor, version);
  }

  public Map<String, Object> verifyOwned(
      String user, String id, String code, String factor, long version) {
    var view = tasks.verify(user, id, code, factor, version);
    if (!view.containsKey("error")) executor.enqueue(id);
    return view;
  }

  public Map<String, Object> control(
      String token, String id, String command, String value, long version) {
    String user = user(token);
    var view = tasks.control(user, id, command, value, version);
    executor.enqueue(id);
    return view;
  }

  public Map<String, Object> demo(String token, String id) {
    return tasks.demoChallenge(user(token), id);
  }

  public List<ActionSpec> actions(String token) {
    user(token);
    return availability.list(user(token));
  }

  public Map<String, Object> chat(String token, String session, String message, String text) {
    return handle(
        IncomingMessage.builder()
            .userId(user(token))
            .channel("web")
            .text(text)
            .msgId(message)
            .meta(Map.of("sessionId", session))
            .build());
  }

  public Map<String, Object> handle(IncomingMessage message) {
    if (message == null
        || !Set.of("web", "telegram").contains(message.getChannel())
        || message.getMeta() == null
        || !(message.getMeta().get("sessionId") instanceof String session))
      throw new BackendException(400, "invalid_incoming_message");
    return chatOwned(message.getUserId(), session, message.getMsgId(), message.getText());
  }

  public Map<String, Object> cachedChat(String token, String session, String message, String text) {
    return conversations.cached(user(token), session, message, text);
  }

  public Map<String, Object> chatOwned(String user, String session, String message, String text) {
    permission.requireUnlocked(user);
    var started = conversations.begin(user, session, message, text);
    if (started.replay() != null) return started.replay();
    Map<String, Object> context = new LinkedHashMap<>(started.context());
    if (started.recoveredTask() != null)
      return conversations.complete(
          user, session, message, reply(tasks.view(user, started.recoveredTask())), context);
    boolean ruleBlocked = injection.detect(text) != null;
    // The models see structured local inspection signals, never raw private chat text.
    // 分级复核：规则已通过的消息走 fast 单模型确认（成本与延迟友好）；规则命中才升级双模型共识
    var inputReview =
        models.review(
            user,
            session,
            Map.of(
                "kind", ruleBlocked ? "unsafe" : "instruction", "injectionDetected", ruleBlocked),
            ruleBlocked ? "injection" : "green",
            ruleBlocked);
    if (ruleBlocked || !inputReview.decision().equals("confirm")) {
      conversations.injectionFailure(user);
      return conversations.complete(
          user,
          session,
          message,
          Map.of("error", "injection_blocked", "reply", "输入包含不允许的指令，已拦截。"),
          context);
    }
    var intent = new RuleBasedIntent().recognize(text, message, tasks.now());
    if (intent.getSlots().containsKey("operationIntent")) {
      if ("negated_operation".equals(intent.getSlots().get("operationIntent"))
          && Objects.equals(context.get("pendingIntent"), intent.getSlots().get("negatedIntent"))) {
        context.remove("pendingIntent");
        context.remove("pendingSlots");
      }
      boolean clarify = Boolean.TRUE.equals(intent.getSlots().get("operationIntentClarify"));
      return conversations.complete(
          user,
          session,
          message,
          clarify
              ? Map.of(
                  "kind",
                  "clarification",
                  "missing",
                  List.of("operation_intent"),
                  "reply",
                  "请明确是否现在执行哪一个操作；条件或多个操作不能省略。")
              : Map.of(
                  "kind", "thought", "reply", ThoughtReplies.explain(text) + " 本条消息没有创建或修改任务。"),
          context);
    }
    if (Boolean.TRUE.equals(intent.getSlots().get("amountInvalid")))
      return conversations.complete(
          user,
          session,
          message,
          Map.of(
              "kind",
              "clarification",
              "missing",
              List.of("amount"),
              "reason",
              intent.getSlots().get("amountError"),
              "reply",
              "金额必须是完整、明确的正数，最多两位小数；请重新确认金额。"),
          context);
    if (intent.getSlots().containsKey("currency")
        && !"CNY".equals(intent.getSlots().get("currency")))
      return conversations.complete(
          user,
          session,
          message,
          Map.of(
              "kind",
              "clarification",
              "missing",
              List.of("supported_currency"),
              "reply",
              "当前资金操作只支持人民币，请明确币种与金额。"),
          context);
    text = com.pocketbanker.agent.rules.OperationSpeech.inspect(text).text();
    if (context.get("pendingSlots") instanceof Map<?, ?> saved
        && BackendPlanner.bareAmountWouldReplaceKnownValue(text, intent, saved))
      return conversations.complete(
          user,
          session,
          message,
          Map.of(
              "kind",
              "clarification",
              "missing",
              List.of("explicit_amount_revision"),
              "reply",
              "已有金额保持不变；如要改价，请明确说改成多少元。"),
          context);
    if (text.contains("改成") && context.get("lastSlots") instanceof Map<?, ?> previous) {
      // A replacement needs a new valid operand before touching the old task or its authority.
      if (!intent.getSlots().containsKey("amount"))
        return conversations.complete(
            user,
            session,
            message,
            Map.of(
                "kind",
                "clarification",
                "missing",
                List.of("amount"),
                "reply",
                "原任务保持不变；请明确新的有效金额。"),
            context);
      context.put("pendingSlots", new LinkedHashMap<>((Map<String, Object>) previous));
      context.put("pendingIntent", context.getOrDefault("lastIntent", "transfer"));
      Object previousTask = context.get("lastTask");
      if (previousTask != null) {
        var old = tasks.view(user, previousTask.toString());
        if (!Set.of("WAITING_CONFIRMATION", "WAITING_VERIFICATION").contains(old.get("state")))
          return conversations.complete(
              user,
              session,
              message,
              Map.of(
                  "kind",
                  "clarification",
                  "missing",
                  List.of("started_task_control"),
                  "reply",
                  "已开始的任务不能直接改金额。请查看实际结果；如需再次付款，请明确提出新任务。"),
              started.context());
        if (Set.of("WAITING_CONFIRMATION", "WAITING_VERIFICATION").contains(old.get("state")))
          tasks.control(
              user,
              previousTask.toString(),
              "cancel",
              null,
              ((Number) old.get("version")).longValue());
      }
    }
    if (intent.getIntent().equals("task_control")) {
      String command = intent.getSlots().get("taskCommand").toString();
      if (context.get("lastTask") == null) {
        if (command.equals("cancel")) {
          context.remove("pendingSlots");
          context.remove("pendingIntent");
          return conversations.complete(
              user, session, message, Map.of("kind", "thought", "reply", "待补充的任务草案已取消。"), context);
        }
        return conversations.complete(
            user,
            session,
            message,
            Map.of(
                "kind", "clarification", "missing", List.of("task_choice"), "reply", "请指定要控制的任务。"),
            context);
      }
      if (command.equals("reschedule") && !"at".equals(intent.getSlots().get("triggerType")))
        return conversations.complete(
            user,
            session,
            message,
            Map.of(
                "kind",
                "clarification",
                "missing",
                List.of("execution_time"),
                "reply",
                "请给出新的具体执行日期和时间。"),
            context);
      String task = context.get("lastTask").toString();
      var current = tasks.view(user, task);
      conversations.requireCurrent(user, session, message, context);
      var result =
          tasks.control(
              user,
              task,
              command,
              Objects.toString(intent.getSlots().get("triggerValue"), null),
              ((Number) current.get("version")).longValue());
      if (Set.of("READY", "RUNNING", "COMPENSATING", "CANCELLING").contains(result.get("state")))
        executor.enqueue(task);
      return conversations.complete(user, session, message, reply(result), context);
    }
    if (intent.getIntent().equals("task_list"))
      return conversations.complete(
          user,
          session,
          message,
          Map.of("reply", "任务状态已读取。", "tasks", tasks.list(user, 0)),
          context);
    var planned = planner.plan(user, message, text, intent, context);
    Map<String, Object> details =
        new LinkedHashMap<>((Map<String, Object>) context.getOrDefault("slotDetails", Map.of()));
    details.putAll(intent.getSlotDetails());
    context.put("slotDetails", details);
    if (!planned.missing().isEmpty()) {
      context.put("pendingSlots", planned.slots());
      context.put("pendingIntent", planned.intent());
      return conversations.complete(
          user,
          session,
          message,
          Map.of(
              "kind",
              "clarification",
              "missing",
              planned.missing(),
              "slots",
              planned.slots(),
              "slotDetails",
              intent.getSlotDetails(),
              "reply",
              planned.missing().contains("model_ask")
                      && planned.slots().get("ask") instanceof String ask
                  ? ask
                  : clarifySpeech(planned.intent(), planned.missing())),
          context);
    }
    if (planned.plan() == null)
      return conversations.complete(
          user,
          session,
          message,
          Map.of("kind", "thought", "reply", ThoughtReplies.explain(text)),
          context);
    conversations.requireCurrent(user, session, message, context);
    var result = createOwned(user, planned.plan());
    audit.linkModelTrace(
        user, PerceptionAccess.hash(user + ":" + message), result.get("id").toString());
    context.remove("pendingSlots");
    context.remove("pendingIntent");
    context.put("lastSlots", planned.slots());
    context.put("lastIntent", planned.intent());
    context.put("lastTask", result.get("id"));
    return conversations.complete(user, session, message, reply(result), context);
  }

  /** 澄清话术：把内部槽位名翻译成用户能直接照做的中文。 */
  private static final Map<String, String> SLOT_SPEECH = Map.ofEntries(
      Map.entry("bank", "哪一家银行（回复如：银行A / 银行B / 银行C）"),
      Map.entry("amount", "金额（回复如：500元）"),
      Map.entry("recipient", "收款人是谁（回复如：给妈妈转）"),
      Map.entry("confirmed_recipient_account", "收款人还没在您的人物库里。先在对话里告诉我收款人和账号（如：妈妈是银行A账号A-U001），或直接回复对方姓名和账号"),
      Map.entry("recipient_choice", "匹配到多位同名收款人，请用更完整的称呼"),
      Map.entry("confirmed_participant", "参与人还没在人物库里，请先告诉我参与人姓名"),
      Map.entry("participant_choice", "参与人匹配到多位，请用更完整的称呼"),
      Map.entry("productId", "想看或买哪款理财？回复「有什么理财产品」可查看列表"),
      Map.entry("subscriptionId", "要操作哪个订阅？回复「我有什么订阅」可查看列表"),
      Map.entry("confirmed_tuition_event", "还没有确认过的学费提醒。在感知页确认学费短信后重试，或告诉我金额和缴费日期"),
      Map.entry("dueDate", "缴费截止日期是哪天？（回复如：9月1日）"),
      Map.entry("dueAt", "事项在哪一天发生？（回复如：8月20日）"),
      Map.entry("flowerAmount", "鲜花预算多少？（回复如：鲜花600元）"),
      Map.entry("cakeAmount", "蛋糕预算多少？（回复如：蛋糕400元）"),
      Map.entry("analysis_period", "分析哪个月的账单？（回复如：这个月 / 上个月）"),
      Map.entry("execution_time", "什么时间执行？（回复如：明天上午10点）"),
      Map.entry("originalOperation", "要取消哪一笔订单？回复订单对应的原事项"),
      Map.entry("supported_currency", "目前只支持人民币（CNY）"),
      Map.entry("cardId", "要操作哪张卡？（回复如：A行的卡）"),
      Map.entry("limit", "要调整到多少额度？（回复如：5000元）"));

  private String clarifySpeech(String intent, List<String> missing) {
    if (missing == null || missing.isEmpty()) return "请补充更多信息。";
    List<String> parts = new ArrayList<>();
    for (String m : missing) parts.add(SLOT_SPEECH.getOrDefault(m, m));
    StringBuilder sb = new StringBuilder("需要补充一些信息才能继续：");
    for (int i = 0; i < parts.size(); i++) sb.append(i == 0 ? "" : "；").append(parts.get(i));
    if (missing.contains("confirmed_recipient_account"))
      sb.append("。提示：把常用收款人加入人物库后，以后直接说「给妈妈转500」就能自动识别。");
    return sb.toString();
  }

  private Map<String, Object> reply(Map<String, Object> task) {
    Map<String, Object> response = new LinkedHashMap<>(task);
    response.put("taskId", task.get("id"));
    String state = String.valueOf(task.get("state"));
    if ("STOPPED".equals(response.get("trigger_state"))) response.put("reply", "后续周期已停止，已完成的轮次保留。");
    if ("PAUSED".equals(response.get("trigger_state"))) response.put("reply", "后续周期已暂停，已完成的轮次保留。");
    if (!response.containsKey("reply"))
      response.put(
          "reply",
          state.equals("WAITING_VERIFICATION")
              ? "此操作需要两项强验证。"
              : state.equals("WAITING_CONFIRMATION")
                  ? "请确认此任务及参数。"
                  : state.equals("SUCCEEDED") ? "任务已完成，结果已核验。" : "任务已登记，请通过任务状态查看执行结果。");
    return response;
  }

  public Map<String, Object> flows(String token, String source) {
    return flowCoordinator.sync(user(token), source);
  }

  public List<Map<String, Object>> discover(String token, String bank) {
    return banks.discover(user(token), bank);
  }

  public Map<String, Object> bind(String token, String bank, String account, String consent) {
    return banks.bind(user(token), bank, account, consent);
  }

  public Map<String, Object> bind(
      String token, String bank, String account, String consent, Set<String> scopes) {
    return banks.bind(user(token), bank, account, consent, scopes);
  }

  public void unbind(String token, String bank, String account) {
    banks.unbind(user(token), bank, account);
  }

  public List<Map<String, Object>> bindings(String token) {
    return banks.list(user(token));
  }

  public Map<String, Object> profile(String token) {
    return profile.snapshot(user(token));
  }

  public Map<String, Object> person(String token, ProfileService.PersonInput input) {
    return profile.person(user(token), input);
  }

  public Map<String, Object> updatePerson(
      String token, String id, ProfileService.PersonInput input) {
    return profile.updatePerson(user(token), id, input);
  }

  public void closeRelation(String token, String id, String event) {
    profile.closeRelation(user(token), id, event);
  }

  public Map<String, Object> fact(String token, ProfileService.FactInput input) {
    return profile.fact(user(token), input);
  }

  public Map<String, Object> relation(String token, ProfileService.RelationInput input) {
    return profile.relation(user(token), input);
  }

  public void forgetFact(String token, String id) {
    profile.forgetFact(user(token), id);
  }

  public void forgetPerson(String token, String id) {
    profile.forgetPerson(user(token), id);
  }

  public Map<String, Object> funding(
      String token, com.pocketbanker.prediction.FundingCoordinator.Request input) {
    return funding.propose(user(token), input);
  }

  public Map<String, Object> acceptFunding(String token, String id) {
    String user = user(token);
    return createOwned(user, funding.accept(user, id), id);
  }

  public Map<String, Object> forecast(String token, int days) {
    return prediction.forecast(user(token), days);
  }

  public List<Map<String, Object>> proposals(String token) {
    return prediction.proposals(user(token));
  }

  public Map<String, Object> tuition(String token, String event, String amount, String date) {
    return prediction.tuition(user(token), event, amount, date);
  }

  public Map<String, Object> acceptProposal(
      String token, String id, String bank, String account, String name) {
    String user = user(token);
    return createOwned(user, prediction.proposalPlan(user, id, bank, account, name), id);
  }

  public void rejectProposal(String token, String id) {
    prediction.reject(user(token), id);
  }

  public List<Map<String, Object>> notifications(String token, int page) {
    return notifications.list(user(token), page);
  }

  public void acknowledge(String token, String id) {
    notifications.acknowledge(user(token), id);
  }

  public List<Map<String, Object>> audit(String token, int page) {
    return audit.trail(user(token), page);
  }

  public List<Map<String, Object>> taskAudit(String token, String id, int page) {
    String user = user(token);
    tasks.view(user, id);
    return audit.taskTrail(user, id, page);
  }

  public Map<String, Object> aa(String token, String id) {
    return system.aaView(user(token), id);
  }

  public Map<String, Object> aaPaid(String token, String id, String person, String reference) {
    return system.aaPaid(user(token), id, person, reference);
  }

  public Map<String, Object> cancelAa(String token, String id) {
    return system.cancelAa(user(token), id);
  }

  public Map<String, Object> invitation(String token, String id, String shared) {
    return system.invitation(user(token), id, shared);
  }

  public Map<String, Object> invitationPaid(String token, String id, String reference) {
    return system.invitationPaid(user(token), id, reference);
  }

  public Map<String, Object> modelPolicy(String token) {
    return models.policy(user(token));
  }

  public Map<String, Object> modelPolicy(String token, int reviewers, boolean factors) {
    return models.updatePolicy(user(token), reviewers, factors);
  }

  public Map<String, Object> datasetSchemas(String token) {
    user(token);
    return system.datasetSchemas();
  }

  private boolean unsafe(Object value) {
    if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(this::unsafe);
    if (value instanceof List<?> list) return list.stream().anyMatch(this::unsafe);
    return value instanceof String text && injection.detect(text) != null;
  }
}
