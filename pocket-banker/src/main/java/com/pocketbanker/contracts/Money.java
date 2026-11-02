package com.pocketbanker.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public record Money(String currency, BigDecimal amount) {
  public Money {
    if (!Set.of("CNY", "USD", "EUR", "GBP", "HKD").contains(currency)
        || amount == null
        || amount.scale() > 2
        || amount.precision() - amount.scale() > 15) {
      throw new BackendException(400, "invalid_money");
    }
    amount = amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  public long minorUnits() {
    return amount.movePointRight(2).longValueExact();
  }

  public static BigDecimal positive(Object value) {
    try {
      var amount = new BigDecimal(String.valueOf(value));
      if (amount.signum() <= 0
          || amount.scale() > 2
          || amount.compareTo(new BigDecimal("9999999999999")) > 0)
        throw new IllegalArgumentException();
      return amount.setScale(2);
    } catch (Exception e) {
      throw new BackendException(400, "invalid_amount");
    }
  }
}
