package com.pocketbanker.config;

import com.pocketbanker.data.BackendStore;
import com.pocketbanker.profile.ProfileService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 演示画像与确认事件种子（仅 local-demo，幂等）。
 * 原设计附录设定：通讯录含 妈妈/爱人/房东，往来频次分层——收款人解析、生日联动、反诈升档演示依赖这组画像；
 * 学费预测（场景F）依赖一条已确认的 tuition_due 事件。
 */
@Component
@RequiredArgsConstructor
public class DemoProfileSeeder implements CommandLineRunner {

  private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DemoProfileSeeder.class.getName());

  private final ProfileService profiles;
  private final BackendStore db;

  @Value("${backend.mode:local-demo}")
  private String mode;

  @Override
  public void run(String... args) {
    if (!"local-demo".equals(mode)) return;
    seedPersons();
    seedTuitionEvent();
  }

  private void seedPersons() {
    try {
      if (!profiles.persons("U001").isEmpty()) return;
      profiles.person(
          "U001",
          new ProfileService.PersonInput(
              "妈妈", "family", "1958-03-12", "广州", "A", "A-U001", null, "13800000001",
              List.of("我妈", "母亲")));
      profiles.person(
          "U001",
          new ProfileService.PersonInput(
              "爱人", "family", "1992-08-20", "深圳", "B", "B-U001", null, "13800000002",
              List.of("老婆", "妻子")));
      profiles.person(
          "U001",
          new ProfileService.PersonInput(
              "房东", "payee", null, "深圳", "C", "C-U001", null, "13800000003", List.of()));
      LOG.info("demo profile seeded: U001 妈妈/爱人/房东");
    } catch (Exception e) {
      LOG.warning("demo person seed skipped: " + e.getMessage());
    }
    try {
      // 默认常用银行：转账免问「从哪家扣款」（planner preferred_bank 槽）
      if (profiles.facts("U001").stream().noneMatch(f -> "preferred_bank".equals(f.get("fact_key"))))
        profiles.fact(
            "U001",
            new ProfileService.FactInput(
                null, "preferred_bank", "A", "preference", List.of(), null));
      LOG.info("demo preferred_bank=A seeded");
    } catch (Exception e) {
      LOG.warning("preferred_bank seed skipped: " + e.getMessage());
    }
  }

  /** 学费确认事件：感知源→回执→confirmed 事件 三连插，按 external_id_hash 幂等。 */
  private void seedTuitionEvent() {
    String external = "demo-tuition-2026-0901";
    try {
      var dup =
          db.one(
              "SELECT id FROM events WHERE user_id=? AND kind='tuition_due' AND status='confirmed' AND superseded_by IS NULL",
              "U001");
      if (dup.isPresent()) return;

      String sourceId =
          db.one("SELECT id FROM perception_sources WHERE user_id=? AND kind='SMS'", "U001")
              .map(r -> text(r, "id"))
              .orElseGet(() -> {
                String id = UUID.randomUUID().toString();
                db.update(
                    "INSERT INTO perception_sources(id,user_id,kind,consent_id,purpose,token_hash,allowed_senders_json,status,created_at,expires_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, "U001", "SMS", "consent-demo-sms", "账单与缴费提醒识别",
                    "seeded", "[\"95588\",\"95533\",\"1069\"]", "active",
                    db.now(), db.now().plusSeconds(31536000));
                return id;
              });

      String receiptId = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO perception_log(id,source_id,user_id,external_id_hash,content_hash,occurred_at,received_at,updated_at,status,attempts) "
              + "VALUES(?,?,?,?,?,?,?,?,?,?)",
          receiptId, sourceId, "U001",
          com.pocketbanker.security.PerceptionAccess.hash(external),
          com.pocketbanker.security.PerceptionAccess.hash("seed-tuition"),
          db.now(), db.now(), db.now(), "completed", 0);

      LocalDate due = LocalDate.now().plusMonths(2).withDayOfMonth(1);
      db.update(
          "INSERT INTO events(id,user_id,receipt_id,event_type,kind,title,amount,currency,due_date,occurred_at,source,confidence,extractor_version,status,created_at,expires_at,confirmed_at) "
              + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(), "U001", receiptId, "finance", "tuition_due",
          "孩子秋季学费缴费提醒", 20000, "CNY", due.toString(),
          db.now(), "sms", 0.95, "demo-seed-v1", "confirmed",
          db.now(), db.now().plusSeconds(15552000), db.now());
      LOG.info("demo tuition event seeded for U001, due " + due);
    } catch (Exception e) {
      LOG.warning("demo tuition seed skipped: " + e.getMessage());
    }
  }

  private static String text(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : row.get(key).toString();
  }
}
