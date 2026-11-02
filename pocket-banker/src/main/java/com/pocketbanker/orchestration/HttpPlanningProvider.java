package com.pocketbanker.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** OpenAI-compatible protocol only; configured credentials and private slots are never logged. */
public final class HttpPlanningProvider implements PlanningProvider {
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final String key, model, instruction;
  private final HttpClient client;

  public HttpPlanningProvider(
      ObjectMapper mapper, String base, String key, String model, boolean local) {
    this(
        mapper,
        base,
        key,
        model,
        local,
        "Return exactly JSON {nodes:[{id,actionId,params,dependsOn}]}. Use each requested id/action exactly once. Parameter values must be the provided opaque refs; never invent values or execute tools. Preserve mandatory dependencies.");
  }

  public HttpPlanningProvider(
      ObjectMapper mapper,
      String base,
      String key,
      String model,
      boolean local,
      String instruction) {
    URI uri = URI.create(base);
    if ((!"https".equals(uri.getScheme())
            && !(local && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getHost() == null
        || key.isBlank()
        || model.isBlank()) throw new IllegalArgumentException("invalid_planner_configuration");
    this.mapper = mapper;
    this.endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
    this.key = key;
    this.model = model;
    this.instruction = instruction;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  private static final String UNDERSTAND_INSTRUCTION =
      "You are the intent-understanding module of a personal banking assistant. Map the user's"
          + " colloquial Chinese message to exactly one intent from the catalog and extract slots."
          + " Return exactly JSON {\"intent\":string,\"slots\":object,\"ask\":stringOrNull}."
          + " Amounts become plain number strings (8万=80000, 五千=5000). Relative dates become"
          + " YYYY-MM-DD using the provided today. Person names should match provided persons when"
          + " close. Choose intent \"chat\" only when nothing fits; then reply is a short helpful"
          + " Chinese sentence. When the intent is clear but one required slot is unguessable, set"
          + " ask to a short natural Chinese follow-up question. Catalog: query_balance(查余额资产)"
          + "|query_transactions(流水明细)|bill_analysis(分析统计花在哪)|anomaly_detect(异常盗刷)"
          + "|subscription_detect(订阅代扣)|wealth_query(理财产品)|wealth_purchase(申购)"
          + "|wealth_redeem(赎回)|transfer(转账汇款房租给某人钱,slots:amount,to_name,bank,executionDate,memo)"
          + "|reserve(预留留出未来确定支出,slots:amount,executionDate,bank)"
          + "|tuition_planning(学费教育,slots:amount,dueDate)|birthday_dag(生日鲜花蛋糕安排,slots:to_name,flowerAmount,cakeAmount)"
          + "|aa_collect(AA凑钱拼单,slots:amount,participants,dueAt)|freeze_card(冻结挂失止付)"
          + "|unfreeze_card(解冻)|apply_card(申请卡)|card_limits(额度)|cancel_subscription(取消订阅)"
          + "|morning_report(晨报日报)|reminder(提醒,slots:title,dueAt)|chat(其他闲聊).";

  @Override
  public Map<String, Object> understand(
      Map<String, Object> request, Duration budget, String overrideModel) {
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var requestBody = new java.util.LinkedHashMap<String, Object>();
      requestBody.put("model", overrideModel == null || overrideModel.isBlank() ? model : overrideModel);
      requestBody.put("temperature", 0);
      requestBody.put("response_format", Map.of("type", "json_object"));
      // 请求可带 _system 覆盖默认理解指令（感知抽取等场景复用同通道），并从用户载荷中剔除
      Object systemOverride = request.get("_system");
      Map<String, Object> payload = new java.util.LinkedHashMap<>(request);
      payload.remove("_system");
      requestBody.put(
          "messages",
          List.of(
              Map.of(
                  "role",
                  "system",
                  "content",
                  systemOverride instanceof String sys && !sys.isBlank()
                      ? sys
                      : UNDERSTAND_INSTRUCTION),
              Map.of("role", "user", "content", mapper.writeValueAsString(payload))));
      var req =
          HttpRequest.newBuilder(endpoint)
              .timeout(budget)
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(requestBody)))
              .build();
      pending = client.sendAsync(req, info -> new BoundedBody());
      var response = pending.get(budget.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 429 || response.statusCode() >= 500)
          throw new BackendException(503, "planner_unavailable");
      if (response.statusCode() != 200)
          throw new BackendException(502, "planner_protocol_rejected");
      var tree = mapper.readTree(response.body());
      String content = tree.path("choices").path(0).path("message").path("content").asText();
      if (content.length() > 65536) throw new BackendException(502, "planner_response_limit");
      return mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BackendException(503, "planner_unavailable");
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  @Override
  public String health() {
    return com.pocketbanker.orchestration.ModelAvailabilityProbe.read(
        mapper, endpoint.toString().replaceFirst("/chat/completions$", ""), key, model);
  }

  @Override
  public Map<String, Object> propose(Map<String, Object> request, Duration budget) {
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var body =
          Map.of(
              "model",
              model,
              "temperature",
              0,
              "response_format",
              Map.of("type", "json_object"),
              "messages",
              List.of(
                  Map.of("role", "system", "content", instruction),
                  Map.of("role", "user", "content", mapper.writeValueAsString(request))));
      var req =
          HttpRequest.newBuilder(endpoint)
              .timeout(budget)
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
              .build();
      pending = client.sendAsync(req, info -> new BoundedBody());
      var response = pending.get(budget.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 429 || response.statusCode() >= 500)
        throw new BackendException(503, "planner_unavailable");
      if (response.statusCode() != 200)
        throw new BackendException(502, "planner_protocol_rejected");
      var tree = mapper.readTree(response.body());
      String content = tree.path("choices").path(0).path("message").path("content").asText();
      if (content.length() > 65536) throw new BackendException(502, "planner_response_limit");
      return mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BackendException(503, "planner_unavailable");
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long bytes;

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) bytes += buffer.remaining();
      if (bytes > 262144) {
        subscription.cancel();
        delegate.onError(new BackendException(502, "planner_response_limit"));
      } else delegate.onNext(buffers);
    }

    public void onError(Throwable e) {
      delegate.onError(e);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }
}
