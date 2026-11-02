package com.pocketbanker.profile;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 有限结构化字段；不接收任意 payload 或原文摘要。 */
public record CandidateDraft(
    @NotBlank
        @Pattern(
            regexp =
                "tuition_due|payment_due|transaction_debit|transaction_credit|risk_transaction|life_event|flight_trip")
        String kind,
    @NotBlank @Size(max = 160) String title,
    @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2)
        BigDecimal amount,
    @Pattern(regexp = "CNY|USD|EUR|GBP|HKD") String currency,
    LocalDate dueDate,
    @Pattern(regexp = "FREQ=MONTHLY;BYMONTHDAY=([1-9]|[12][0-9]|3[01])") String recurrence,
    @Pattern(regexp = "[0-9]{4}") String accountLast4,
    @Size(max = 100) String city,
    LocalDate endDate) {
  public CandidateDraft(
      String kind,
      String title,
      BigDecimal amount,
      String currency,
      LocalDate dueDate,
      String recurrence,
      String accountLast4) {
    this(kind, title, amount, currency, dueDate, recurrence, accountLast4, null, null);
  }
}
