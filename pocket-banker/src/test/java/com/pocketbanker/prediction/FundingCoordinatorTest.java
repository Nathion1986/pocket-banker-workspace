package com.pocketbanker.prediction;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.contracts.*;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.profile.ProfileService;
import com.pocketbanker.security.VaultCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FundingCoordinatorTest {
  @Test
  void splitPaymentFeasibilityMatchesIndependentExhaustiveCentAllocations() {
    var prediction = mock(PredictionService.class);
    var db = mock(BackendStore.class);
    var vault = mock(VaultCodec.class);
    var profile = mock(ProfileService.class);
    var banks = mock(BankAdapters.class);
    var cash = mock(CashflowLedger.class);
    var transaction = mock(org.springframework.transaction.support.TransactionTemplate.class);
    doAnswer(
            call -> {
              ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                      call.getArgument(0))
                  .accept(mock(org.springframework.transaction.TransactionStatus.class));
              return null;
            })
        .when(transaction)
        .executeWithoutResult(any());
    var planner = new FundingCoordinator(prediction, cash, transaction, db, vault, banks, profile);
    Instant now = Instant.parse("2026-10-06T12:00:00Z");
    when(db.now()).thenReturn(now);
    when(profile.facts(anyString())).thenReturn(List.of());
    var random = new Random(18012026);
    for (int scenario = 0; scenario < 200; scenario++) {
      List<Map<String, Object>> accounts = new ArrayList<>();
      int[] balances = new int[3], limits = new int[3], daily = new int[3], rates = new int[3];
      boolean[] frozen = new boolean[3];
      for (int n = 0; n < 3; n++) {
        balances[n] = random.nextInt(21);
        limits[n] = random.nextInt(16);
        daily[n] = random.nextInt(16);
        rates[n] = random.nextInt(4);
        frozen[n] = random.nextInt(6) == 0;
        accounts.add(
            Map.of(
                "id",
                "account-" + n,
                "bank",
                String.valueOf((char) ('A' + n)),
                "currency",
                "CNY",
                "available",
                BigDecimal.valueOf(balances[n], 2).toPlainString(),
                "singleLimit",
                BigDecimal.valueOf(limits[n], 2).toPlainString(),
                "remainingDailyLimit",
                BigDecimal.valueOf(daily[n], 2).toPlainString(),
                "transferFeeRate",
                BigDecimal.valueOf(rates[n], 2).toPlainString(),
                "frozen",
                frozen[n],
                "settlementDays",
                0));
      }
      when(prediction.liveAccounts("user")).thenReturn(accounts);
      int requested = 1 + random.nextInt(31), retain = random.nextInt(5);
      boolean feasible = false;
      // Independent integer oracle, including ceiling fees, retains, frozen and both limits.
      for (int a = 0; a <= Math.min(limits[0], daily[0]); a++)
        for (int b = 0; b <= Math.min(limits[1], daily[1]); b++) {
          int c = requested - a - b;
          if (c < 0 || c > Math.min(limits[2], daily[2])) continue;
          int[] assigned = {a, b, c};
          boolean allowed = true;
          for (int n = 0; n < 3; n++) {
            int fee = (assigned[n] * rates[n] + 99) / 100;
            if (assigned[n] > 0 && (frozen[n] || assigned[n] + fee + retain > balances[n]))
              allowed = false;
          }
          if (allowed) feasible = true;
        }
      var request =
          new FundingCoordinator.Request(
              "split_payment",
              null,
              "A",
              "payee",
              "收款人",
              BigDecimal.valueOf(requested, 2).toPlainString(),
              now.plusSeconds(30),
              null,
              BigDecimal.valueOf(retain, 2).toPlainString());
      if (!feasible) {
        assertThatThrownBy(() -> planner.propose("user", request))
            .isInstanceOf(BackendException.class)
            .hasMessage("no_eligible_funding_channel");
      } else {
        var result = planner.propose("user", request);
        var plan = (WorkflowPlan) result.get("plan");
        int total = 0;
        for (var node : plan.nodes()) {
          int n = node.params().get("bank").toString().charAt(0) - 'A';
          int amount =
              new BigDecimal(node.params().get("amount").toString())
                  .movePointRight(2)
                  .intValueExact();
          total += amount;
          assertThat(amount + (amount * rates[n] + 99) / 100 + retain)
              .isLessThanOrEqualTo(balances[n]);
          assertThat(amount).isLessThanOrEqualTo(Math.min(limits[n], daily[n]));
          assertThat(frozen[n]).isFalse();
        }
        assertThat(total).isEqualTo(requested);
      }
    }
    // The planner only reviews/persists suggestions; no bank operation may be invoked here.
    verifyNoInteractions(banks);
  }
}
