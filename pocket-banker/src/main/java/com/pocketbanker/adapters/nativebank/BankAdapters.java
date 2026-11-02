package com.pocketbanker.adapters.nativebank;

import com.pocketbanker.contracts.BackendException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BankAdapters {
  private final Map<String, BankAdapter> banks;

  public BankAdapters(
      NativeHttpClient http,
      @Value("${bank.bank-a.base-url}") String a,
      @Value("${bank.bank-b.base-url}") String b,
      @Value("${bank.bank-c.base-url}") String c,
      com.fasterxml.jackson.databind.ObjectMapper mapper,
      @Value("${bank.fineract.base-url:}") String fineractUrl,
      @Value("${bank.fineract.tenant:default}") String tenant,
      @Value("${bank.fineract.username:}") String username,
      @Value("${bank.fineract.password:}") String password,
      @Value("${bank.fineract.clients-json:{}}") String clients,
      @Value("${bank.fineract.allow-local-http:false}") boolean localHttp,
      @Value("${bank.fineract.timeout-ms:2000}") long timeout,
      @Value("${bank.fineract.payment-type-id:0}") long paymentTypeId,
      @Value("${bank.fineract.write-base-url:}") String writeBase) {
    banks =
        new LinkedHashMap<>(
            Map.of(
                "A",
                new HttpBankAdapter("A", a, http),
                "B",
                new HttpBankAdapter("B", b, http),
                "C",
                new HttpBankAdapter("C", c, http)));
    if (!fineractUrl.isBlank())
      banks.put(
          "F",
          new FineractBankAdapter(
              mapper,
              fineractUrl,
              tenant,
              username,
              password,
              clients,
              localHttp,
              timeout,
              paymentTypeId,
              writeBase));
  }

  public BankAdapter require(String bank) {
    var value = banks.get(bank);
    if (value == null) throw new BackendException(400, "bank_not_registered");
    return value;
  }

  public Collection<BankAdapter> list() {
    return banks.values();
  }
}
