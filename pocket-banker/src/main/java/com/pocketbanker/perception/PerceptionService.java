package com.pocketbanker.perception;

import com.pocketbanker.orchestration.RuleSignalExtractor;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class PerceptionService {
  private final SignalTransactions transactions;
  private final RuleSignalExtractor extractor;
  private final ThreadPoolTaskExecutor executor;
  private final Validator validator;
  private final com.pocketbanker.orchestration.ModelCoordinator models;
  private final com.pocketbanker.data.PerceptionSourceRepository sources;
  private final com.pocketbanker.agent.StructuredPlanningService understanding;

  public PerceptionService(
      SignalTransactions transactions,
      RuleSignalExtractor extractor,
      @Qualifier("perceptionExecutor") ThreadPoolTaskExecutor executor,
      Validator validator,
      com.pocketbanker.orchestration.ModelCoordinator models,
      com.pocketbanker.data.PerceptionSourceRepository sources,
      com.pocketbanker.agent.StructuredPlanningService understanding) {
    this.transactions = transactions;
    this.extractor = extractor;
    this.executor = executor;
    this.understanding = understanding;
    this.validator = validator;
    this.models = models;
    this.sources = sources;
  }

  public SignalTransactions.ReceiptView ingest(
      String token, String kind, PerceptionRequests.TextSignal signal) {
    var ticket = transactions.accept(token, kind, signal);
    if (ticket.dispatch()) {
      try {
        executor.execute(() -> process(ticket, signal));
      } catch (TaskRejectedException e) {
        transactions.retry(ticket, "queue_full");
        throw new PerceptionException(503, "queue_full_retry_same_external_id");
      }
    }
    return transactions.viewById(ticket.id());
  }

  /** 已在后台邮件线程内执行；成功终态才允许 IMAP 推进游标。 */
  public boolean processMail(String user, PerceptionRequests.TextSignal signal) {
    return processMail(user, signal, null);
  }

  public boolean processMail(String user, PerceptionRequests.TextSignal signal, Long epoch) {
    if (!validator.validate(signal).isEmpty())
      throw new PerceptionException(400, "invalid_mail_signal");
    var ticket = transactions.acceptMail(user, signal, epoch);
    if (ticket.dispatch()) process(ticket, signal);
    return Set.of("completed", "filtered", "needs_review")
        .contains(transactions.viewById(ticket.id()).status());
  }

  private void process(SignalTransactions.Ticket ticket, PerceptionRequests.TextSignal signal) {
    try {
      if (transactions.begin(ticket)) {
        var extraction = extractor.extract(signal.subject(), signal.text(), signal.occurredAt());
        if (extraction.candidate() == null) {
          // 规则未识别 → 大模型语义抽取接管，失败保持规则结论
          var llm = llmExtract(signal);
          if (llm != null) extraction = llm;
        }
        if (extraction.candidate() != null) {
          String user = sources.findById(ticket.sourceId()).orElseThrow().getUserId();
          String verdict;
          try {
            verdict =
                models.reviewCandidate(user, ticket.sourceId(), extraction.candidate()).decision();
          } catch (RuntimeException reviewUnavailable) {
            verdict = "abstain"; // 复核层故障不拦候选：感知≠画像，人工确认是最终闸门
          }
          // 候选事件本就须用户确认才入画像（感知≠画像红线）：模型明确反对才拦，
          // 弃权(超时/不可用)放行候选交人工确认——与对话任务 abstain→降级放行同一哲学
          if (verdict.equals("reject"))
            extraction =
                new com.pocketbanker.orchestration.RuleSignalExtractor.Extraction(
                    "needs_review", "model_vetoed", null);
        }
        transactions.complete(ticket, extraction);
      }
    } catch (RuntimeException e) {
      // Supplier/model/input text must never appear in logs or persistent error strings.
      java.util.logging.Logger.getLogger("perception.process")
          .warning(
              "processing_failed: "
                  + e.getClass().getName()
                  + ": "
                  + String.valueOf(e.getMessage()).replaceAll("[一-龥]", "#").substring(0, Math.min(500, String.valueOf(e.getMessage()).length())));
      transactions.retry(ticket, "processing_failed");
    }
  }

  private static final String SIGNAL_EXTRACT_INSTRUCTION =
      "You extract structured financial life events from a raw SMS or email notification."
          + " Return exactly JSON {\"kind\":string,\"title\":string,\"amount\":stringOrNull,"
          + "\"currency\":stringOrNull,\"dueDate\":\"YYYY-MM-DD\"orNull,"
          + "\"recurrence\":\"FREQ=MONTHLY;BYMONTHDAY=N\"orNull,\"accountLast4\":4digitsOrNull,"
          + "\"city\":stringOrNull,\"endDate\":\"YYYY-MM-DD\"orNull}."
          + " kind must be one of: tuition_due(学费教育待缴)|payment_due(账单月供房租待缴,周期性用recurrence)"
          + "|transaction_debit(已发生消费扣款)|transaction_credit(入账收入)|risk_transaction(可疑盗刷境外异常)"
          + "|life_event|flight_trip(行程,city+startDate+endDate)."
          + " Amounts plain number strings (8千=8000, 叁佰=300). Only include fields actually present."
          + " If the text is a verification code, marketing spam, or has no financial life event, return"
          + " {\"kind\":null}. Title is a short Chinese summary.";

  /** 大模型语义抽取：规则识别不了的原文交给 GLM 结构化；任何失败返回 null 保持规则结论。 */
  private RuleSignalExtractor.Extraction llmExtract(PerceptionRequests.TextSignal signal) {
    try {
      var request = new java.util.LinkedHashMap<String, Object>();
      request.put("_system", SIGNAL_EXTRACT_INSTRUCTION);
      request.put("subject", signal.subject());
      request.put("text", signal.text());
      request.put("occurredAt", signal.occurredAt().toString());
      var out = understanding.understand(request);
      if (out == null) return null;
      String kind = String.valueOf(out.get("kind"));
      if ("null".equals(kind) || kind.isBlank()) return null;
      java.util.function.Function<String, String> str =
          k ->
              out.get(k) == null || "null".equals(String.valueOf(out.get(k)))
                  ? null
                  : String.valueOf(out.get(k));
      String title = str.apply("title");
      if (title == null) return null;
      // 防御式净化：GLM 可能输出"元"/"XXXX"等越界值——逐字段白名单清洗而非整单丢弃
      java.util.function.Function<String, String> date =
          v -> {
            String raw = str.apply(v);
            if (raw == null) return null;
            try {
              return java.time.LocalDate.parse(raw).toString();
            } catch (RuntimeException bad) {
              return null;
            }
          };
      String amount = str.apply("amount");
      java.math.BigDecimal amountValue = null;
      if (amount != null)
        try {
          amountValue = new java.math.BigDecimal(amount);
        } catch (RuntimeException bad) {
          amountValue = null;
        }
      String currency = str.apply("currency");
      if (currency != null && !java.util.Set.of("CNY", "USD", "EUR", "GBP", "HKD").contains(currency))
        currency = amountValue == null ? null : "CNY"; // "元"→CNY
      String last4 = str.apply("accountLast4");
      if (last4 != null && !last4.matches("[0-9]{4}")) last4 = null;
      String recurrence = str.apply("recurrence");
      if (recurrence != null && !recurrence.matches("FREQ=MONTHLY;BYMONTHDAY=([1-9]|[12][0-9]|3[01])"))
        recurrence = null;
      var draft =
          new com.pocketbanker.profile.CandidateDraft(
              kind,
              title.length() > 160 ? title.substring(0, 160) : title,
              amountValue,
              currency,
              date.apply("dueDate") == null ? null : java.time.LocalDate.parse(date.apply("dueDate")),
              recurrence,
              last4,
              str.apply("city"),
              date.apply("endDate") == null ? null : java.time.LocalDate.parse(date.apply("endDate")));
      if (!validator.validate(draft).isEmpty()) return null;
      // 与 EventService 闸门一致：金融类候选必须带金额+币种，缺则放弃 LLM 结果保持规则结论
      if (!java.util.Set.of("life_event", "flight_trip").contains(kind)
          && (amountValue == null || currency == null)) return null;
      // status 是 perception_log 终态（枚举无 "candidate"），candidate 是否存在由第三参表达
      return new RuleSignalExtractor.Extraction("completed", "llm_understood", draft);
    } catch (RuntimeException malformed) {
      return null; // GLM 输出不合法/超时 → 保持规则结论（needs_review）
    }
  }

  @Scheduled(fixedDelayString = "${perception.recovery-ms:30000}")
  public void recover() {
    transactions.recoverExpired();
  }
}
