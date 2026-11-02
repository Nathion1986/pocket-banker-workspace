import concurrent.futures
import http.client
from http.server import ThreadingHTTPServer
import json
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from engine import Ledger, BusinessError
from server import Handler


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "a.sqlite3"
        self.bank = Ledger(self.path, "A")
        self.source = self.bank.accounts("alpha")[0]["id"]
        self.payee = self.bank.accounts("bravo")[0]["id"]

    def tearDown(self):
        self.temp.cleanup()

    def execute(self, key, verb, args):
        return self.bank.execute(key, "alpha", verb, self.source, args)

    def test_http_bulk_posts_explicitly_close_connections_without_losing_rows(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.ledger = self.bank
        server.admin_token = "fixture-http-admin-token"
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        try:
            for batch in range(5):
                rows = [{"id": f"wire-{batch}-{i}", "amount": "-1.00", "merchant": "合成商户",
                         "category": "教学账单", "occurredAt": "2024-12-31T16:00:01Z"} for i in range(250)]
                body = json.dumps({"accountId": self.source, "rows": rows}, ensure_ascii=False).encode()
                client.request("POST", "/admin/transactions", body,
                               {"Content-Type": "application/json", "X-Mock-Admin-Token": server.admin_token})
                response = client.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(response.getheader("Connection"), "close")
                self.assertEqual(json.loads(response.read())["count"], 250)
            with self.bank.connect() as db:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions WHERE account=? AND id LIKE 'wire-%'", (self.source,)).fetchone()[0], 1250)
        finally:
            client.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_credit_repayment_reduces_debt_and_cash_once_without_overpayment(self):
        bill = self.bank.credit_bills(self.source)[0]
        args = {"amount": "100.00", "billId": bill["billId"]}
        result = self.execute("repay", "credit_repay", args)
        self.assertEqual(result["outstandingBefore"], "1000.00")
        self.assertEqual(result["outstandingAfter"], "900.00")
        self.assertEqual(self.execute("repay", "credit_repay", args), result)
        again = Ledger(self.path, "A")
        self.assertEqual(again.credit_bills(self.source)[0]["outstanding"], "900.00")
        self.assertEqual(again.account(self.source)["balance"], "4100.00")
        with self.assertRaises(BusinessError):
            self.execute("overpay", "credit_repay", {"amount": "901.00", "billId": bill["billId"]})
        with self.assertRaises(BusinessError):
            self.execute("other-bill", "credit_repay", {"amount": "1.00", "billId": "bill-" + self.payee})
        self.assertEqual(again.credit_bills(self.source)[0]["outstanding"], "900.00")
        with again.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM operations WHERE verb='credit_repay'").fetchone()[0], 1)

    def test_transfer_idempotency_and_real_t_plus_one_settlement(self):
        before = self.bank.account(self.payee)["balance"]
        r = self.execute(
            "tx1", "transfer", {"amount": "500.00", "toAccountId": self.payee}
        )
        self.assertEqual(r["status"], "PENDING")
        self.assertEqual(
            self.execute(
                "tx1", "transfer", {"amount": "500.00", "toAccountId": self.payee}
            ),
            r,
        )
        self.assertEqual(self.bank.account(self.source)["balance"], "3700.00")
        self.assertEqual(self.bank.account(self.payee)["balance"], before)
        self.bank.advance(86401)
        self.assertEqual(self.bank.outcome("tx1", "alpha")["status"], "SUCCEEDED")
        self.assertEqual(self.bank.account(self.payee)["balance"], "4700.00")
        self.bank.outcome("tx1", "alpha")
        self.assertEqual(self.bank.account(self.payee)["balance"], "4700.00")
        with self.assertRaises(BusinessError):
            self.execute(
                "tx1", "transfer", {"amount": "600.00", "toAccountId": self.payee}
            )

    def test_counterparty_identity_and_posting_state_follow_real_transfer_lifecycle(self):
        args = {"amount": "15.25", "toAccountId": self.payee}
        self.execute("edge-pending", "transfer", args)
        debit = next(r for r in self.bank.transactions(self.source) if r.get("counterpartyAccountId") == self.payee)
        self.assertEqual(debit["status"], "PENDING")
        self.assertEqual(debit["counterpartyAmount"], "15.25")
        self.bank.advance(86401)
        self.bank.outcome("edge-pending", "alpha")
        posted = next(r for r in self.bank.transactions(self.source) if r["id"] == debit["id"])
        self.assertEqual(posted["status"], "POSTED")
        credit = next(r for r in self.bank.transactions(self.payee) if r.get("counterpartyAccountId") == self.source)
        self.assertEqual(credit["counterpartyBank"], "A")
        self.assertEqual(credit["counterpartyAmount"], "15.25")
        self.execute("edge-cancel", "transfer", args)
        self.bank.compensate("edge-cancel", "alpha")
        self.assertEqual(sum(r.get("status") == "CANCELLED" for r in self.bank.transactions(self.source)), 1)

    def test_parallel_duplicate_debits_once_and_survives_restart(self):
        def debit(i):
            return self.execute(
                "parallel", "transfer", {"amount": "100.00", "toAccountId": self.payee}
            )

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(debit, range(16)))
        self.assertEqual(len({r["operationId"] for r in results}), 1)
        self.assertEqual(self.bank.account(self.source)["balance"], "4100.00")
        again = Ledger(self.path, "A")
        self.assertEqual(again.outcome("parallel", "alpha")["status"], "PENDING")

    def test_reserve_freeze_unfreeze_and_scope_release(self):
        a = self.execute("r1", "reserve", {"amount": "1000.00", "purpose": "birthday"})
        b = self.execute("r2", "reserve", {"amount": "500.00", "purpose": "tuition"})
        self.assertEqual(self.bank.account(self.source)["available"], "2700.00")
        self.execute("freeze", "freeze", {})
        with self.assertRaises(BusinessError):
            self.execute(
                "blocked", "transfer", {"amount": "1.00", "toAccountId": self.payee}
            )
        self.execute("unfreeze", "unfreeze", {})
        self.execute("release", "release", {"reservationId": a["reservationId"]})
        self.assertEqual(self.bank.account(self.source)["reserved"], "500.00")
        self.assertEqual(
            self.bank.reservation(b["reservationId"], "alpha")["status"], "active"
        )

    def test_purchase_and_redemption_changes_holdings_then_cash(self):
        self.execute("buy", "purchase", {"amount": "100.00", "productId": "A-P1"})
        self.assertEqual(self.bank.holdings(self.source)[0]["amount"], "15100.00")
        self.execute("redeem", "redeem", {"amount": "1000.00", "productId": "A-P1"})
        self.assertEqual(self.bank.holdings(self.source)[0]["amount"], "14100.00")
        self.assertEqual(self.bank.account(self.source)["balance"], "4100.00")
        self.bank.advance(86401)
        self.bank.outcome("redeem", "alpha")
        self.assertEqual(self.bank.account(self.source)["balance"], "5100.00")

    def test_pending_cancel_refunds_once(self):
        self.execute("tx", "transfer", {"amount": "500.00", "toAccountId": self.payee})
        self.bank.compensate("tx", "alpha")
        self.bank.compensate("tx", "alpha")
        self.assertEqual(self.bank.account(self.source)["balance"], "4200.00")

    def test_money_ownership_and_insufficient_balance(self):
        for amount in ["-1", "0", "1.001", "NaN", "Infinity"]:
            with self.assertRaises(BusinessError):
                self.execute(
                    "invalid-" + amount,
                    "transfer",
                    {"amount": amount, "toAccountId": self.payee},
                )
        with self.assertRaises(BusinessError):
            self.bank.execute("foreign", "bravo", "freeze", self.source, {})
        with self.assertRaises(BusinessError):
            self.execute(
                "large", "transfer", {"amount": "5000.00", "toAccountId": self.payee}
            )

    def test_seed_cash_deficit_subscription_series_and_expense_sign(self):
        cash = sum(
            Decimal(
                Ledger(Path(self.temp.name) / (kind + ".db"), kind).accounts("alpha")[
                    0
                ]["available"]
            )
            for kind in ("A", "B", "C")
        )
        self.assertLess(cash, Decimal("20000"))
        transactions = self.bank.transactions(self.source)
        self.assertTrue(
            all(
                Decimal(t["amount"]) < 0
                for t in transactions
                if t["category"] == "异常"
            )
        )
        self.assertEqual(len([t for t in transactions if t["category"] == "订阅"]), 6)

    def test_merchant_refund_restores_reservation_for_reverse_compensation(self):
        reserved = self.execute(
            "reserve", "reserve", {"amount": "1000.00", "purpose": "birthday"}
        )
        self.execute(
            "capture",
            "capture_reservation",
            {"amount": "200.00", "reservationId": reserved["reservationId"]},
        )
        self.execute("refund", "refund", {"originalKey": "capture"})
        self.bank.compensate("reserve", "alpha")
        self.assertEqual(self.bank.account(self.source)["balance"], "4200.00")
        self.assertEqual(self.bank.account(self.source)["reserved"], "0.00")

    def test_refund_must_return_to_original_payment_account(self):
        second = "A-alpha-secondary"
        with self.bank.atomic() as db:
            db.execute("INSERT INTO accounts(id,owner,balance) VALUES(?,?,?)", (second, "alpha", 0))
        r = self.execute("refund-reserve", "reserve", {"amount": "100.00", "purpose": "birthday"})
        self.execute("refund-capture", "capture_reservation", {"amount": "100.00", "reservationId": r["reservationId"]})
        with self.assertRaises(BusinessError):
            self.bank.execute("wrong-refund", "alpha", "refund", second, {"originalKey": "refund-capture"})
        self.assertEqual(self.bank.account(second)["balance"], "0.00")
        self.assertEqual(self.bank.account(self.source)["balance"], "4100.00")
        self.execute("right-refund", "refund", {"originalKey": "refund-capture"})
        self.assertEqual(self.bank.account(self.source)["balance"], "4200.00")

    def test_card_limits_are_independent_and_payments_are_idempotent(self):
        card1 = self.execute("card1", "virtual_card", {"limit": "100.00"})["cardId"]
        card2 = self.execute("card2", "apply_card", {"limit": "500.00"})["cardId"]
        with self.assertRaises(BusinessError):
            self.execute("too-big", "card_payment", {"cardId": card1, "amount": "101.00", "merchant": "shop"})
        args = {"cardId": card2, "amount": "200.00", "merchant": "shop"}
        self.execute("card-payment", "card_payment", args)
        self.execute("card-payment", "card_payment", args)
        self.assertEqual(self.bank.account(self.source)["balance"], "4000.00")
        self.execute("limit1", "card_limits", {"cardId": card1, "limit": "50.00", "dailyLimit": "80.00", "blockedMerchants": ["blocked"]})
        self.assertEqual(self.bank.account(self.source)["singleLimit"], "1000000.00")
        with self.assertRaises(BusinessError):
            self.execute("blocked-shop", "card_payment", {"cardId": card1, "amount": "1.00", "merchant": "blocked"})
        self.execute("small1", "card_payment", {"cardId": card1, "amount": "50.00", "merchant": "shop"})
        with self.assertRaises(BusinessError):
            self.execute("daily-over", "card_payment", {"cardId": card1, "amount": "40.00", "merchant": "shop"})
        self.bank.advance(86400)
        self.execute("next-day", "card_payment", {"cardId": card1, "amount": "40.00", "merchant": "shop"})

    def test_subscription_is_inferred_from_transactions_not_seed(self):
        import datetime as dt
        with self.bank.atomic() as db:
            db.execute("DELETE FROM subscriptions WHERE account=?", (self.source,))
            db.execute("DELETE FROM transactions WHERE account=?", (self.source,))
            now = self.bank.now(db)
            for i in range(3):
                self.bank.tx(db, self.source, -1990, "new-periodic-service", "订阅", now - (3-i)*7*86400)
        records = self.bank.subscriptions(self.source)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["period"], "weekly")
        self.assertEqual(records[0]["amount"], "19.90")
        self.assertGreater(dt.datetime.fromisoformat(records[0]["nextDue"]).timestamp(), now)
        self.execute("unsubscribe-real", "cancel_subscription", {"subscriptionId": records[0]["id"]})
        self.assertEqual(self.bank.subscriptions(self.source)[0]["status"], "cancelled")

    def test_frozen_account_rejects_every_spending_path(self):
        reserve = self.execute("before-freeze", "reserve", {"amount": "200.00", "purpose": "birthday"})
        card = self.execute("before-card", "virtual_card", {"limit": "100.00"})["cardId"]
        self.execute("stop", "stop_payment", {})
        for verb, args in [("reserve", {"amount": "1.00", "purpose": "new"}), ("purchase", {"amount": "100.00", "productId": "A-P1"}), ("capture_reservation", {"amount": "1.00", "reservationId": reserve["reservationId"]}), ("card_payment", {"amount": "1.00", "cardId": card, "merchant": "shop"})]:
            with self.assertRaises(BusinessError):
                self.execute("frozen-"+verb, verb, args)
        self.assertEqual(self.bank.account(self.source)["balance"], "4200.00")

    def test_parallel_outcomes_after_due_credit_once(self):
        self.execute("parallel-settle", "transfer", {"amount": "100.00", "toAccountId": self.payee})
        self.bank.advance(86401)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results=list(pool.map(lambda _: self.bank.outcome("parallel-settle","alpha"),range(16)))
        self.assertTrue(all(r["status"]=="SUCCEEDED" for r in results))
        self.assertEqual(self.bank.account(self.payee)["balance"],"4300.00")
        self.assertEqual(self.bank.account(self.source)["balance"],"4100.00")

    def test_purchase_duplicate_and_over_redemption_leave_balances_consistent(self):
        args={"amount":"100.00","productId":"A-P1"}
        self.execute("purchase-one","purchase",args)
        self.execute("purchase-one","purchase",args)
        self.assertEqual(self.bank.account(self.source)["balance"],"4100.00")
        self.assertEqual(self.bank.holdings(self.source)[0]["amount"],"15100.00")
        with self.assertRaises(BusinessError):
            self.execute("over-redeem","redeem",{"amount":"15100.01","productId":"A-P1"})
        self.assertEqual(self.bank.holdings(self.source)[0]["amount"],"15100.00")

    def test_concurrent_distinct_reservations_cannot_overdraw_available(self):
        def reserve(i):
            try:
                return self.execute("parallel-budget-"+str(i),"reserve",{"amount":"1000.00","purpose":"budget-"+str(i)})
            except BusinessError:
                return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results=list(pool.map(reserve,range(16)))
        self.assertEqual(len([r for r in results if r]),4)
        self.assertEqual(self.bank.account(self.source)["balance"],"4200.00")
        self.assertEqual(self.bank.account(self.source)["reserved"],"4000.00")
        self.assertEqual(self.bank.account(self.source)["available"],"200.00")

    def test_connection_context_closes_connection(self):
        import sqlite3

        with self.bank.connect() as db:
            db.execute("SELECT 1")
        with self.assertRaises(sqlite3.ProgrammingError):
            db.execute("SELECT 1")

    def test_receiving_clearing_is_idempotent(self):
        payload = {
            "sourceBank": "C",
            "key": "cross",
            "accountId": self.source,
            "amount": "100.00",
        }
        self.bank.incoming(payload)
        self.bank.incoming(payload)
        self.assertEqual(self.bank.account(self.source)["balance"], "4300.00")


if __name__ == "__main__":
    unittest.main()
