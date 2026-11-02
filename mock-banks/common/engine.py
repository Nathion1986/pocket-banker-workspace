"""Independent, durable mock ledgers. Monetary arithmetic uses integer minor units."""

import calendar
import contextlib
import datetime as dt
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import urllib.request
import urllib.error
import uuid


class BusinessError(Exception):
    def __init__(self, code, status=400):
        super().__init__(code)
        self.code, self.status = code, status


def minor(value):
    try:
        amount = Decimal(str(value))
        if (
            not amount.is_finite()
            or amount <= 0
            or amount.as_tuple().exponent < -2
            or amount > Decimal("9999999999999")
        ):
            raise ValueError()
        return int(amount * 100)
    except (ValueError, InvalidOperation, TypeError):
        raise BusinessError("invalid_amount")


def money(value):
    return format(Decimal(value) / 100, ".2f")


def digest(data):
    return hashlib.sha256(
        json.dumps(
            data, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode()
    ).hexdigest()


class Ledger:
    def __init__(self, path, kind, urls=None, token=""):
        fresh=not Path(path).exists()
        self.path, self.kind, self.urls, self.token = str(path), kind, urls or {}, token
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
              PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS clock(offset_seconds REAL NOT NULL);
              INSERT INTO clock SELECT 0 WHERE NOT EXISTS(SELECT 1 FROM clock);
              CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY,owner TEXT NOT NULL,balance INTEGER NOT NULL,frozen INTEGER NOT NULL DEFAULT 0,credit_limit INTEGER NOT NULL DEFAULT 0,limit_minor INTEGER NOT NULL DEFAULT 100000000);
              CREATE TABLE IF NOT EXISTS reservations(id TEXT PRIMARY KEY,account TEXT NOT NULL,owner TEXT NOT NULL,amount INTEGER NOT NULL,remaining INTEGER NOT NULL,purpose TEXT NOT NULL,state TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS transactions(id TEXT PRIMARY KEY,account TEXT NOT NULL,amount INTEGER NOT NULL,merchant TEXT NOT NULL,category TEXT NOT NULL,city TEXT NOT NULL,occurred REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS products(id TEXT PRIMARY KEY,name TEXT NOT NULL,min_minor INTEGER NOT NULL,risk INTEGER NOT NULL,rate TEXT NOT NULL,period INTEGER NOT NULL,settle_days INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS holdings(account TEXT NOT NULL,product TEXT NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(account,product));
              CREATE TABLE IF NOT EXISTS operations(key TEXT PRIMARY KEY,owner TEXT NOT NULL,request_hash TEXT NOT NULL,verb TEXT NOT NULL,account TEXT,amount INTEGER NOT NULL,result TEXT NOT NULL,state TEXT NOT NULL,due REAL,to_account TEXT,to_bank TEXT,delivery TEXT);
              CREATE TABLE IF NOT EXISTS clearing(key TEXT PRIMARY KEY,request_hash TEXT NOT NULL,result TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS cards(id TEXT PRIMARY KEY,account TEXT NOT NULL,owner TEXT NOT NULL,state TEXT NOT NULL,virtual INTEGER NOT NULL,limit_minor INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS credit_bills(id TEXT PRIMARY KEY,account TEXT NOT NULL,owner TEXT NOT NULL,amount INTEGER NOT NULL,paid INTEGER NOT NULL DEFAULT 0,due REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS subscriptions(id TEXT PRIMARY KEY,account TEXT NOT NULL,owner TEXT NOT NULL,merchant TEXT NOT NULL,amount INTEGER NOT NULL,state TEXT NOT NULL,next_due REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY,owner TEXT NOT NULL,amount INTEGER NOT NULL,state TEXT NOT NULL,cancel_until REAL NOT NULL,reservation TEXT,bank TEXT,account TEXT);
              CREATE TABLE IF NOT EXISTS faults(verb TEXT PRIMARY KEY,mode TEXT NOT NULL,remaining INTEGER NOT NULL);
            """)
            if fresh and os.environ.get("MOCK_INITIAL_TIME"):
                target=dt.datetime.fromisoformat(os.environ["MOCK_INITIAL_TIME"].replace("Z","+00:00")).timestamp()
                db.execute("UPDATE clock SET offset_seconds=?",(target-time.time(),))
            # Additive upgrades: old local SQLite databases remain readable.
            for table, columns in {
                "transactions": {"counterparty_bank": "TEXT", "counterparty_account": "TEXT", "counterparty_amount": "INTEGER", "status": "TEXT NOT NULL DEFAULT 'POSTED'", "operation_key": "TEXT"},
                "cards": {"daily_limit_minor": "INTEGER NOT NULL DEFAULT 100000000", "blocked_merchants": "TEXT NOT NULL DEFAULT '[]'"},
                "subscriptions": {"period": "TEXT NOT NULL DEFAULT 'monthly'", "evidence_count": "INTEGER NOT NULL DEFAULT 0"},
                "orders": {"fulfill_at": "REAL", "fulfilled_at": "REAL"},
            }.items():
                existing = {r[1] for r in db.execute("PRAGMA table_info("+table+")")}
                for column, definition in columns.items():
                    if column not in existing:
                        db.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition)
            db.execute("CREATE TABLE IF NOT EXISTS card_daily(card TEXT NOT NULL,day TEXT NOT NULL,spent INTEGER NOT NULL,PRIMARY KEY(card,day))")
            for i in range(1, 4):
                db.execute(
                    "INSERT OR IGNORE INTO products VALUES(?,?,?,?,?,?,?)",
                    (
                        f"{kind}-P{i}",
                        f"{kind} 理财 {i}",
                        10000 * i,
                        i,
                        str(Decimal("1.5") + Decimal(i) / 2),
                        30 * i,
                        0 if kind == "C" else i,
                    ),
                )

    @contextlib.contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            yield db
        finally:
            db.close()

    @contextlib.contextmanager
    def atomic(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield db
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise

    def now(self, db):
        return (
            time.time() + db.execute("SELECT offset_seconds FROM clock").fetchone()[0]
        )

    def account_id(self, user):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", user or ""):
            raise BusinessError("invalid_owner")
        return self.kind + "-" + user

    def ensure(self, db, user):
        account = self.account_id(user)
        if db.execute("SELECT 1 FROM accounts WHERE id=?", (account,)).fetchone():
            return account
        balances = {"A": 420000, "B": 180000, "C": 120000}
        db.execute(
            "INSERT INTO accounts(id,owner,balance) VALUES(?,?,?)",
            (account, user, balances.get(self.kind, 0)),
        )
        now = self.now(db)
        if self.kind in balances:
            db.execute("INSERT INTO credit_bills(id,account,owner,amount,due) VALUES(?,?,?,?,?)",
                       ("bill-" + account, account, user, 100000, now + 86400 * 15))
            for month in range(6):
                for suffix, amount, merchant, category, city in [
                    ("salary", 1200000, "示例雇主", "工资", "广州"),
                    ("rent", -450000, "房租", "居住", "广州"),
                    ("sub", -2500, "示例影音会员", "订阅", "广州"),
                ]:
                    # Stable calendar months make repeated subscriptions and rent observable.
                    day = (
                        dt.datetime.fromtimestamp(now, dt.timezone.utc)
                        .date()
                        .replace(day=1)
                    )
                    month_index = day.year * 12 + day.month - 1 - month
                    event_date = dt.datetime(
                        month_index // 12,
                        month_index % 12 + 1,
                        5 if suffix == "salary" else 15,
                        tzinfo=dt.timezone.utc,
                    )
                    db.execute(
                        "INSERT INTO transactions(id,account,amount,merchant,category,city,occurred) VALUES(?,?,?,?,?,?,?)",
                        (
                            f"{account}-{suffix}-{month}",
                            account,
                            amount,
                            merchant,
                            category,
                            city,
                            event_date.timestamp(),
                        ),
                    )
            db.execute(
                "INSERT INTO transactions(id,account,amount,merchant,category,city,occurred) VALUES(?,?,?,?,?,?,?)",
                (
                    account + "-anomaly",
                    account,
                    -800000,
                    "OVERSEAS POS",
                    "异常",
                    "境外",
                    now - 86400,
                ),
            )
            db.execute(
                "INSERT INTO holdings VALUES(?,?,?)",
                (account, f"{self.kind}-P1", 1500000),
            )
            db.execute(
                "INSERT INTO subscriptions(id,account,owner,merchant,amount,state,next_due) VALUES(?,?,?,?,?,?,?)",
                (
                    account + "-sub",
                    account,
                    user,
                    "示例影音会员",
                    2500,
                    "active",
                    now + 86400 * 15,
                ),
            )
        return account

    def account_view(self, db, id):
        row = db.execute("SELECT * FROM accounts WHERE id=?", (id,)).fetchone()
        if not row:
            raise BusinessError("account_not_found", 404)
        held = db.execute(
            "SELECT COALESCE(SUM(remaining),0) FROM reservations WHERE account=? AND state='active'",
            (id,),
        ).fetchone()[0]
        return {
            "id": id,
            "owner": row["owner"],
            "bank": self.kind,
            "currency": "CNY",
            "balance": money(row["balance"]),
            "reserved": money(held),
            "available": money(row["balance"] - held),
            "frozen": bool(row["frozen"]),
            "creditLimit": money(row["credit_limit"]),
            "singleLimit": money(row["limit_minor"]),
            "transferFeeRate": "0.001" if self.kind == "B" else "0",
            "settlementDays": 1 if self.kind == "A" else 0,
        }

    def accounts(self, user):
        with self.atomic() as db:
            id = self.ensure(db, user)
            return [self.account_view(db, id)]

    def account(self, id):
        with self.connect() as db:
            return self.account_view(db, id)

    def transactions(self, id, offset=0, limit=100, from_time=0, to_time=1e20):
        with self.connect() as db:
            self.account_view(db, id)
            return [
                {
                    "id": r["id"],
                    "accountId": id,
                    "amount": money(r["amount"]),
                    "currency": "CNY",
                    "merchant": r["merchant"],
                    "category": r["category"],
                    "city": r["city"],
                    "occurredAt": dt.datetime.fromtimestamp(r["occurred"], dt.timezone.utc).isoformat(),
                    "status": r["status"],
                    **({"counterpartyBank": r["counterparty_bank"], "counterpartyAccountId": r["counterparty_account"], "counterpartyAmount": money(r["counterparty_amount"])} if r["counterparty_bank"] and r["counterparty_account"] and r["counterparty_amount"] is not None else {}),
                }
                for r in db.execute(
                    "SELECT * FROM transactions WHERE account=? AND occurred>=? AND occurred<=? ORDER BY occurred DESC,id LIMIT ? OFFSET ?",
                    (id, from_time, to_time, min(limit, 250), max(offset, 0)),
                )
            ]

    def products(self):
        with self.connect() as db:
            return [
                {
                    "id": r["id"],
                    "name": r["name"],
                    "minAmount": money(r["min_minor"]),
                    "risk": r["risk"],
                    "annualRate": r["rate"],
                    "periodDays": r["period"],
                    "settleDays": r["settle_days"],
                }
                for r in db.execute("SELECT * FROM products ORDER BY id")
            ]

    def holdings(self, account):
        with self.connect() as db:
            self.account_view(db, account)
            return [
                {
                    "accountId": account,
                    "productId": r["product"],
                    "amount": money(r["amount"]),
                }
                for r in db.execute(
                    "SELECT * FROM holdings WHERE account=?", (account,)
                )
            ]

    def credit_bills(self, account):
        with self.connect() as db:
            self.account_view(db, account)
            return [{"billId": r["id"], "accountId": r["account"], "currency": "CNY",
                     "amount": money(r["amount"]), "paid": money(r["paid"]),
                     "outstanding": money(r["amount"] - r["paid"]),
                     "dueAt": dt.datetime.fromtimestamp(r["due"], dt.timezone.utc).isoformat(),
                     "status": "paid" if r["paid"] == r["amount"] else "outstanding"}
                    for r in db.execute("SELECT * FROM credit_bills WHERE account=? ORDER BY due,id", (account,))]

    def fault(self, verb, mode, count=1):
        if mode not in (
            "fail_before",
            "reject_before",
            "timeout_after",
            "malformed_after",
            "pretend_success",
            "none",
        ):
            raise BusinessError("invalid_fault")
        with self.atomic() as db:
            db.execute(
                "INSERT OR REPLACE INTO faults VALUES(?,?,?)",
                (verb, mode, max(0, int(count))),
            )

    def take_fault(self, verb):
        with self.atomic() as db:
            r = db.execute("SELECT * FROM faults WHERE verb=?", (verb,)).fetchone()
            if r and r["remaining"] > 0:
                db.execute(
                    "UPDATE faults SET remaining=remaining-1 WHERE verb=?", (verb,)
                )
                return r["mode"]
        return "none"

    def execute(self, key, user, verb, account, args):
        if not key or len(key) > 200:
            raise BusinessError("idempotency_key_required")
        request_hash = digest([user, verb, account, args])
        with self.atomic() as db:
            prior = db.execute(
                "SELECT * FROM operations WHERE key=?", (key,)
            ).fetchone()
            if prior:
                if prior["owner"] != user or prior["request_hash"] != request_hash:
                    raise BusinessError("idempotency_conflict", 409)
                return json.loads(prior["result"])
            now = self.now(db)
            amount = minor(args["amount"]) if "amount" in args else 0
            if account:
                acc = self.account_view(db, account)
                if acc["owner"] != user:
                    raise BusinessError("account_not_owned", 403)
            else:
                acc = None
            result = {
                "operationId": key,
                "status": "SUCCEEDED",
                "kind": verb,
                "amount": money(amount),
                "currency": "CNY",
            }
            due = None
            to_account = args.get("toAccountId")
            to_bank = args.get("toBank", self.kind)
            if verb in ("transfer", "purchase", "reserve", "capture_reservation", "card_payment", "credit_repay"):
                if not acc or acc["frozen"]:
                    raise BusinessError("account_frozen", 409)
                if amount > minor(acc["singleLimit"]):
                    raise BusinessError("single_limit_exceeded", 409)
                if verb != "capture_reservation" and amount > int(
                    Decimal(acc["available"]) * 100
                ):
                    raise BusinessError("insufficient_available", 409)
            if verb == "transfer":
                if to_bank == self.kind:
                    target = db.execute(
                        "SELECT * FROM accounts WHERE id=?", (to_account,)
                    ).fetchone()
                    if not target:
                        raise BusinessError("payee_not_found", 404)
                if to_bank not in ("A", "B", "C"):
                    raise BusinessError("invalid_to_bank")
                fee = (amount + 999) // 1000 if self.kind == "B" else 0
                if amount + fee > int(Decimal(acc["available"]) * 100):
                    raise BusinessError("insufficient_available", 409)
                db.execute(
                    "UPDATE accounts SET balance=balance-? WHERE id=?",
                    (amount + fee, account),
                )
                self.tx(
                    db, account, -amount - fee, args.get("toName", "转账"), "转账", now,
                    counterparty_bank=to_bank, counterparty_account=to_account,
                    counterparty_amount=amount, state="PENDING", operation=key
                )
                due = now + 86400 if self.kind == "A" else now
                result.update(
                    status="PENDING",
                    toAccountId=to_account,
                    toBank=to_bank,
                    fromAccountId=account,
                    fee=money(fee),
                    settleAt=dt.datetime.fromtimestamp(
                        due, dt.timezone.utc
                    ).isoformat(),
                )
            elif verb == "reserve":
                rid = "r-" + uuid.uuid4().hex
                purpose = args.get("purpose")
                if not purpose or len(purpose) > 100:
                    raise BusinessError("purpose_required")
                db.execute(
                    "INSERT INTO reservations VALUES(?,?,?,?,?,?,?)",
                    (rid, account, user, amount, amount, purpose, "active"),
                )
                result["reservationId"] = rid
            elif verb in ("release", "capture_reservation"):
                reserve = db.execute(
                    "SELECT * FROM reservations WHERE id=?",
                    (args.get("reservationId"),),
                ).fetchone()
                if (
                    not reserve
                    or reserve["owner"] != user
                    or reserve["account"] != account
                ):
                    raise BusinessError("reservation_not_owned", 403)
                if reserve["state"] != "active":
                    raise BusinessError("reservation_inactive", 409)
                if verb == "release":
                    db.execute(
                        "UPDATE reservations SET remaining=0,state='released' WHERE id=?",
                        (reserve["id"],),
                    )
                else:
                    if amount > reserve["remaining"]:
                        raise BusinessError("reservation_insufficient", 409)
                    db.execute(
                        "UPDATE reservations SET remaining=remaining-? WHERE id=?",
                        (amount, reserve["id"]),
                    )
                    db.execute(
                        "UPDATE accounts SET balance=balance-? WHERE id=?",
                        (amount, account),
                    )
                    self.tx(db, account, -amount, "商户付款", "购物", now)
                result["reservationId"] = reserve["id"]
            elif verb == "refund":
                original = db.execute(
                    "SELECT * FROM operations WHERE key=?", (args.get("originalKey"),)
                ).fetchone()
                if (
                    not original
                    or original["owner"] != user
                    or original["verb"] != "capture_reservation"
                    or original["account"] != account
                ):
                    raise BusinessError("refund_not_allowed", 409)
                if original["delivery"] == "refunded":
                    raise BusinessError("already_refunded", 409)
                db.execute(
                    "UPDATE operations SET delivery='refunded' WHERE key=?",
                    (original["key"],),
                )
                db.execute(
                    "UPDATE accounts SET balance=balance+? WHERE id=?",
                    (original["amount"], account),
                )
                reservation_id = json.loads(original["result"]).get("reservationId")
                if reservation_id:
                    db.execute(
                        "UPDATE reservations SET remaining=MIN(amount,remaining+?) WHERE id=? AND state='active'",
                        (original["amount"], reservation_id),
                    )
                self.tx(db, account, original["amount"], "退款", "退款", now)
                result["amount"] = money(original["amount"])
            elif verb in ("freeze", "unfreeze", "stop_payment"):
                db.execute(
                    "UPDATE accounts SET frozen=? WHERE id=?",
                    (0 if verb == "unfreeze" else 1, account),
                )
                result["frozen"] = verb != "unfreeze"
            elif verb in ("purchase", "redeem"):
                product = db.execute(
                    "SELECT * FROM products WHERE id=?", (args.get("productId"),)
                ).fetchone()
                if not product:
                    raise BusinessError("product_not_found", 404)
                holding = db.execute(
                    "SELECT amount FROM holdings WHERE account=? AND product=?",
                    (account, product["id"]),
                ).fetchone()
                held = holding[0] if holding else 0
                if verb == "purchase":
                    if amount < product["min_minor"]:
                        raise BusinessError("below_min_purchase")
                    db.execute(
                        "UPDATE accounts SET balance=balance-? WHERE id=?",
                        (amount, account),
                    )
                    db.execute(
                        "INSERT OR REPLACE INTO holdings VALUES(?,?,?)",
                        (account, product["id"], held + amount),
                    )
                    self.tx(db, account, -amount, "理财申购", "理财", now)
                else:
                    if held < amount:
                        raise BusinessError("insufficient_holdings", 409)
                    db.execute(
                        "UPDATE holdings SET amount=amount-? WHERE account=? AND product=?",
                        (amount, account, product["id"]),
                    )
                    due = now + product["settle_days"] * 86400
                    result.update(
                        status="PENDING",
                        settleAt=dt.datetime.fromtimestamp(
                            due, dt.timezone.utc
                        ).isoformat(),
                    )
                result["productId"] = product["id"]
            elif verb in ("apply_card", "virtual_card"):
                card = "card-" + uuid.uuid4().hex
                limit = minor(args.get("limit", "1000.00"))
                db.execute(
                    "INSERT INTO cards(id,account,owner,state,virtual,limit_minor,daily_limit_minor) VALUES(?,?,?,?,?,?,?)",
                    (card, account, user, "active", int(verb == "virtual_card"), limit, limit),
                )
                result.update(
                    cardId=card, limit=money(limit), virtual=verb == "virtual_card"
                )
            elif verb == "card_limits":
                card = db.execute(
                    "SELECT * FROM cards WHERE id=?", (args.get("cardId"),)
                ).fetchone()
                if not card or card["owner"] != user or card["account"] != account:
                    raise BusinessError("card_not_owned", 403)
                limit = minor(args["limit"])
                db.execute(
                    "UPDATE cards SET limit_minor=? WHERE id=?", (limit, card["id"])
                )
                daily = minor(args.get("dailyLimit", money(card["daily_limit_minor"])))
                blocked = args.get("blockedMerchants", json.loads(card["blocked_merchants"]))
                if not isinstance(blocked, list) or len(blocked) > 30 or any(not isinstance(x,str) or len(x)>100 for x in blocked):
                    raise BusinessError("invalid_merchant_restriction")
                db.execute("UPDATE cards SET daily_limit_minor=?,blocked_merchants=? WHERE id=?", (daily,json.dumps(blocked),card["id"]))
                result.update(cardId=card["id"],limit=money(limit),dailyLimit=money(daily),blockedMerchants=blocked)
            elif verb == "card_payment":
                card = db.execute("SELECT * FROM cards WHERE id=?", (args.get("cardId"),)).fetchone()
                if not card or card["owner"] != user or card["account"] != account or card["state"] != "active":
                    raise BusinessError("card_not_owned_or_active", 403)
                merchant = args.get("merchant")
                if not isinstance(merchant, str) or not merchant or len(merchant) > 100:
                    raise BusinessError("merchant_required")
                if merchant in json.loads(card["blocked_merchants"]):
                    raise BusinessError("merchant_blocked", 409)
                if amount > card["limit_minor"]:
                    raise BusinessError("card_single_limit_exceeded", 409)
                day = dt.datetime.fromtimestamp(now, dt.timezone(dt.timedelta(hours=8))).date().isoformat()
                used = db.execute("SELECT spent FROM card_daily WHERE card=? AND day=?", (card["id"], day)).fetchone()
                if amount + (used[0] if used else 0) > card["daily_limit_minor"]:
                    raise BusinessError("card_daily_limit_exceeded", 409)
                db.execute("INSERT INTO card_daily VALUES(?,?,?) ON CONFLICT(card,day) DO UPDATE SET spent=spent+excluded.spent", (card["id"], day, amount))
                db.execute("UPDATE accounts SET balance=balance-? WHERE id=?", (amount, account))
                self.tx(db, account, -amount, merchant, args.get("category", "购物"), now)
                result.update(cardId=card["id"], merchant=merchant, fromAccountId=account)
            elif verb == "credit_repay":
                bill = db.execute("SELECT * FROM credit_bills WHERE id=?", (args.get("billId"),)).fetchone()
                if not bill or bill["owner"] != user or bill["account"] != account:
                    raise BusinessError("credit_bill_not_owned", 403)
                outstanding = bill["amount"] - bill["paid"]
                if amount > outstanding:
                    raise BusinessError("repayment_exceeds_outstanding", 409)
                db.execute("UPDATE accounts SET balance=balance-? WHERE id=?", (amount, account))
                db.execute("UPDATE credit_bills SET paid=paid+? WHERE id=?", (amount, bill["id"]))
                self.tx(db, account, -amount, "信用账单还款", "还款", now)
                result.update(billId=bill["id"], fromAccountId=account,
                              outstandingBefore=money(outstanding), outstandingAfter=money(outstanding-amount))
            elif verb == "cancel_subscription":
                sub = db.execute(
                    "SELECT * FROM subscriptions WHERE id=?",
                    (args.get("subscriptionId"),),
                ).fetchone()
                if not sub or sub["owner"] != user or sub["account"] != account:
                    raise BusinessError("subscription_not_owned", 403)
                db.execute(
                    "UPDATE subscriptions SET state='cancelled' WHERE id=?",
                    (sub["id"],),
                )
                result.update(subscriptionId=sub["id"], subscriptionStatus="cancelled")
            else:
                raise BusinessError("unsupported_action", 404)
            db.execute(
                "INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    key,
                    user,
                    request_hash,
                    verb,
                    account,
                    amount,
                    json.dumps(result),
                    result["status"],
                    due,
                    to_account,
                    to_bank,
                    None,
                ),
            )
        # Each bank's durable pending state is reconciled via outcome; do not fabricate success here.
        return result

    def tx(self, db, account, amount, merchant, category, now, counterparty_bank=None, counterparty_account=None, counterparty_amount=None, state="POSTED", operation=None):
        ident = "tx-" + uuid.uuid4().hex
        db.execute(
            "INSERT INTO transactions(id,account,amount,merchant,category,city,occurred) VALUES(?,?,?,?,?,?,?)",
            (
                ident,
                account,
                amount,
                merchant,
                category,
                "广州",
                now,
            ),
        )
        db.execute("UPDATE transactions SET counterparty_bank=?,counterparty_account=?,counterparty_amount=?,status=?,operation_key=? WHERE id=?", (counterparty_bank,counterparty_account,counterparty_amount,state,operation,ident))


    def outcome(self, key, user):
        dispatch = None
        with self.atomic() as db:
            r = db.execute("SELECT * FROM operations WHERE key=?", (key,)).fetchone()
            if not r:
                raise BusinessError("operation_not_found", 404)
            if r["owner"] != user:
                raise BusinessError("operation_not_owned", 403)
            result = json.loads(r["result"])
            now = self.now(db)
            if r["state"] == "PENDING" and r["due"] <= now:
                if r["verb"] == "redeem":
                    db.execute(
                        "UPDATE accounts SET balance=balance+? WHERE id=?",
                        (r["amount"], r["account"]),
                    )
                    self.tx(db, r["account"], r["amount"], "理财赎回到账", "理财", now)
                elif r["to_bank"] == self.kind:
                    db.execute(
                        "UPDATE accounts SET balance=balance+? WHERE id=?",
                        (r["amount"], r["to_account"]),
                    )
                    self.tx(db, r["to_account"], r["amount"], "转账入账", "转账", now, self.kind, r["account"], r["amount"])
                else:
                    db.execute(
                        "UPDATE operations SET delivery='dispatching' WHERE key=?",
                        (key,),
                    )
                    dispatch = {
                        "key": key,
                        "sourceBank": self.kind,
                        "sourceAccountId": r["account"],
                        "accountId": r["to_account"],
                        "amount": money(r["amount"]),
                        "toBank": r["to_bank"],
                    }
                if dispatch is None:
                    db.execute("UPDATE transactions SET status='POSTED' WHERE operation_key=?",(key,))
                    result["status"] = "SUCCEEDED"
                    db.execute(
                        "UPDATE operations SET state='SUCCEEDED',result=? WHERE key=?",
                        (json.dumps(result), key),
                    )
        return self.dispatch_after(dispatch, result) if dispatch else result

    def dispatch_after(self, payload, result):
        base = self.urls.get(payload["toBank"])
        if not base:
            return result
        body = {k: v for k, v in payload.items() if k != "toBank"}
        request = urllib.request.Request(
            base + "/clearing/incoming",
            data=json.dumps(body).encode(),
            headers={
                "Content-Type": "application/json",
                "X-Mock-Service-Token": self.token,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                received = json.load(response)
            if received.get("status") != "SUCCEEDED":
                return result
        except (OSError, ValueError):
            return result
        result["status"] = "SUCCEEDED"
        with self.atomic() as db:
            db.execute("UPDATE transactions SET status='POSTED' WHERE operation_key=?",(payload["key"],))
            db.execute(
                "UPDATE operations SET state='SUCCEEDED',delivery='delivered',result=? WHERE key=?",
                (json.dumps(result), payload["key"]),
            )
        return result

    def incoming(self, payload):
        key = payload["sourceBank"] + ":" + payload["key"]
        hashed = digest(payload)
        with self.atomic() as db:
            prior = db.execute("SELECT * FROM clearing WHERE key=?", (key,)).fetchone()
            if prior:
                if prior["request_hash"] != hashed:
                    raise BusinessError("clearing_conflict", 409)
                return json.loads(prior["result"])
            self.account_view(db, payload["accountId"])
            amount = minor(payload["amount"])
            db.execute(
                "UPDATE accounts SET balance=balance+? WHERE id=?",
                (amount, payload["accountId"]),
            )
            self.tx(
                db, payload["accountId"], amount, "跨行清算入账", "转账", self.now(db),
                payload.get("sourceBank"), payload.get("sourceAccountId"), amount
            )
            result = {"status": "SUCCEEDED", "clearingId": key}
            db.execute(
                "INSERT INTO clearing VALUES(?,?,?)", (key, hashed, json.dumps(result))
            )
            return result

    def compensate(self, key, user):
        with self.atomic() as db:
            row = db.execute("SELECT * FROM operations WHERE key=?", (key,)).fetchone()
            if not row or row["owner"] != user:
                raise BusinessError("operation_not_owned", 403)
            if row["state"] == "CANCELLED":
                return json.loads(row["result"])
            if row["verb"] == "reserve":
                result = json.loads(row["result"])
                reserve = db.execute(
                    "SELECT * FROM reservations WHERE id=?", (result["reservationId"],)
                ).fetchone()
                if reserve["remaining"] != reserve["amount"]:
                    raise BusinessError("reservation_partly_consumed", 409)
                db.execute(
                    "UPDATE reservations SET remaining=0,state='released' WHERE id=?",
                    (reserve["id"],),
                )
            elif (
                row["verb"] == "transfer"
                and row["state"] == "PENDING"
                and row["delivery"] is None
            ):
                result = json.loads(row["result"])
                db.execute(
                    "UPDATE accounts SET balance=balance+? WHERE id=?",
                    (
                        row["amount"] + int(Decimal(result.get("fee", "0")) * 100),
                        row["account"],
                    ),
                )
            else:
                raise BusinessError("compensation_window_closed", 409)
            result = json.loads(row["result"])
            result["status"] = "CANCELLED"
            db.execute("UPDATE transactions SET status='CANCELLED' WHERE operation_key=?",(key,))
            db.execute(
                "UPDATE operations SET state='CANCELLED',result=? WHERE key=?",
                (json.dumps(result), key),
            )
            return result

    def reservation(self, id, user):
        with self.connect() as db:
            r = db.execute("SELECT * FROM reservations WHERE id=?", (id,)).fetchone()
            if not r or r["owner"] != user:
                raise BusinessError("reservation_not_owned", 403)
            return {
                "id": r["id"],
                "amount": money(r["amount"]),
                "remaining": money(r["remaining"]),
                "purpose": r["purpose"],
                "status": r["state"],
            }

    def cards(self, account):
        with self.connect() as db:
            self.account_view(db, account)
            return [{"cardId": r["id"], "accountId": r["account"], "status": r["state"], "virtual": bool(r["virtual"]), "limit": money(r["limit_minor"]), "dailyLimit": money(r["daily_limit_minor"]), "blockedMerchants": json.loads(r["blocked_merchants"])} for r in db.execute("SELECT * FROM cards WHERE account=? ORDER BY id", (account,))]

    def subscriptions(self, account):
        # Derive candidates from actual debits. Seed records alone do not imply a period.
        with self.atomic() as db:
            owner = self.account_view(db, account)["owner"]
            now = self.now(db)
            groups = {}
            for t in db.execute("SELECT * FROM transactions WHERE account=? AND amount<0 AND occurred<=? ORDER BY occurred", (account, now)):
                if t["category"] in ("转账", "理财", "异常", "退款"):
                    continue
                groups.setdefault((t["merchant"], t["amount"]), []).append(t["occurred"])
            for (merchant, negative), times in groups.items():
                if len(times) < 3:
                    continue
                last = times[-min(len(times), 6):]
                gaps = [(b-a)/86400 for a,b in zip(last,last[1:])]
                period = "weekly" if all(6<=g<=8 for g in gaps) else "monthly" if all(27<=g<=32 for g in gaps) else "yearly" if all(364<=g<=367 for g in gaps) else None
                if not period:
                    continue
                due = dt.datetime.fromtimestamp(times[-1], dt.timezone.utc)
                while due.timestamp() <= now:
                    if period == "weekly":
                        due += dt.timedelta(days=7)
                    else:
                        months = 1 if period == "monthly" else 12
                        index = due.year*12+due.month-1+months
                        year, month = index//12, index%12+1
                        due = due.replace(year=year, month=month, day=min(dt.datetime.fromtimestamp(times[-1],dt.timezone.utc).day,calendar.monthrange(year,month)[1]))
                old = db.execute("SELECT * FROM subscriptions WHERE account=? AND merchant=? AND amount=?", (account,merchant,-negative)).fetchone()
                if old:
                    db.execute("UPDATE subscriptions SET next_due=?,period=?,evidence_count=? WHERE id=?", (due.timestamp(),period,len(times),old["id"]))
                else:
                    sid = "sub-"+digest([account,merchant,negative])[:32]
                    db.execute("INSERT INTO subscriptions(id,account,owner,merchant,amount,state,next_due,period,evidence_count) VALUES(?,?,?,?,?,?,?,?,?)", (sid,account,owner,merchant,-negative,"active",due.timestamp(),period,len(times)))
            return [{"id":r["id"],"merchant":r["merchant"],"amount":money(r["amount"]),"status":r["state"],"nextDue":dt.datetime.fromtimestamp(r["next_due"],dt.timezone.utc).isoformat(),"period":r["period"],"evidenceCount":r["evidence_count"]} for r in db.execute("SELECT * FROM subscriptions WHERE account=? AND evidence_count>=3 ORDER BY id",(account,))]

    def import_transactions(self,account,rows):
        if not isinstance(rows,list) or len(rows)>10000:
            raise BusinessError("invalid_transaction_fixture")
        with self.atomic() as db:
            self.account_view(db,account)
            for item in rows:
                amount=Decimal(str(item["amount"]))
                if not amount.is_finite() or amount.as_tuple().exponent < -2 or abs(amount)>Decimal("9999999999999"):
                    raise BusinessError("invalid_transaction_amount")
                instant=dt.datetime.fromisoformat(item["occurredAt"].replace("Z","+00:00"))
                if instant.tzinfo is None:
                    raise BusinessError("transaction_timezone_required")
                ident=item.get("id","fixture-"+uuid.uuid4().hex)
                db.execute("INSERT INTO transactions(id,account,amount,merchant,category,city,occurred) VALUES(?,?,?,?,?,?,?)",(ident,account,int(amount*100),item.get("merchant","fixture"),item.get("category","购物"),item.get("city","广州"),instant.timestamp()))
                if item.get("counterpartyBank") and item.get("counterpartyAccountId"):
                    cp = minor(item.get("counterpartyAmount",str(abs(amount))))
                    if cp > int(abs(amount)*100):
                        raise BusinessError("invalid_counterparty_amount")
                    db.execute("UPDATE transactions SET counterparty_bank=?,counterparty_account=?,counterparty_amount=? WHERE id=?",(item["counterpartyBank"],item["counterpartyAccountId"],cp,ident))
        return {"status":"imported","count":len(rows)}

    def advance(self, seconds):
        if seconds < 0 or seconds > 86400 * 365:
            raise BusinessError("invalid_clock_advance")
        with self.atomic() as db:
            db.execute("UPDATE clock SET offset_seconds=offset_seconds+?", (seconds,))

    def reset(self):
        with self.atomic() as db:
            for table in (
                "operations",
                "transactions",
                "holdings",
                "reservations",
                "cards",
                "credit_bills",
                "card_daily",
                "subscriptions",
                "orders",
                "clearing",
                "accounts",
                "faults",
            ):
                db.execute("DELETE FROM " + table)
            initial=os.environ.get("MOCK_INITIAL_TIME")
            offset=dt.datetime.fromisoformat(initial.replace("Z","+00:00")).timestamp()-time.time() if initial else 0
            db.execute("UPDATE clock SET offset_seconds=?",(offset,))
