"""Native HTTP protocols for three heterogeneous banks and two merchants."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import hmac
import json
import os
import time
import urllib.parse
import urllib.request
import uuid
from engine import Ledger, BusinessError, minor, money, digest

PREFIXES = {
    "A": "/api/v2",
    "B": "/v2",
    "C": "/v1",
    "flower": "/merchant/v1",
    "cake": "/merchant/v1",
}
OPERATIONS = (
    "transfer",
    "reserve",
    "release",
    "capture_reservation",
    "refund",
    "freeze",
    "unfreeze",
    "stop_payment",
    "purchase",
    "redeem",
    "apply_card",
    "virtual_card",
    "card_limits",
    "card_payment",
    "credit_repay",
    "cancel_subscription",
)


def wrap(kind, value):
    if kind == "A":
        return {"code": 0, "message": "ok", "data": value}
    if kind == "C":
        return {"data": {"type": "resource", "attributes": value}}
    return value


def unwrap(kind, value):
    return (
        value["data"]
        if kind == "A"
        else value["data"]["attributes"]
        if kind == "C"
        else value
    )


def bank_request(ledger, bank, path, body=None):
    base = ledger.urls.get(bank)
    if not base:
        raise BusinessError("bank_unavailable", 503)
    request = urllib.request.Request(
        base + PREFIXES[bank] + path,
        data=None if body is None else json.dumps(body).encode(),
        method="GET" if body is None else "POST",
        headers={
            "Content-Type": "application/json",
            "X-Mock-Service-Token": ledger.token,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            return unwrap(bank, json.load(response))
    except urllib.error.HTTPError as error:
        raise BusinessError("bank_operation_rejected", error.code)
    except OSError:
        raise BusinessError("bank_result_unknown", 503)


def bank_body(bank, key, user, verb, account, args):
    if bank == "B":
        return {
            "requestKey": key,
            "customerId": user,
            "fromAccount": account,
            "arguments": args,
        }
    body = {"idempotencyKey": key, "userId": user, "accountId": account, "params": args}
    return (
        {"data": {"type": verb, "id": key, "attributes": body}} if bank == "C" else body
    )


def merchant_order(ledger, key, user, args):
    hashed = digest([user, args])
    bank = args.get("bank")
    account = args.get("accountId")
    amount = minor(args.get("amount"))
    with ledger.connect() as clock_db:
        now = ledger.now(clock_db)
    with ledger.atomic() as db:
        old = db.execute("SELECT * FROM operations WHERE key=?", (key,)).fetchone()
        if old:
            if old["owner"] != user or old["request_hash"] != hashed:
                raise BusinessError("idempotency_conflict", 409)
            if old["state"] != "PENDING":
                return json.loads(old["result"])
            result = json.loads(old["result"])
        else:
            id = "order-" + uuid.uuid4().hex
            result = {
                "operationId": key,
                "orderId": id,
                "status": "PENDING",
                "kind": "order_" + ledger.kind,
                "fulfillmentStatus": "pending_payment",
                "amount": money(amount),
                "currency": "CNY",
            }
            db.execute(
                "INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    key,
                    user,
                    hashed,
                    "order",
                    account,
                    amount,
                    json.dumps(result),
                    "PENDING",
                    None,
                    None,
                    bank,
                    json.dumps(args),
                ),
            )
            db.execute(
                "INSERT INTO orders(id,owner,amount,state,cancel_until,reservation,bank,account) VALUES(?,?,?,?,?,?,?,?)",
                (
                    id,
                    user,
                    amount,
                    "pending_payment",
                    now + 120,
                    args.get("reservationId"),
                    bank,
                    account,
                ),
            )
    reserve = bank_request(
        ledger,
        bank,
        "/reservations/"
        + urllib.parse.quote(args.get("reservationId", ""))
        + "?owner="
        + urllib.parse.quote(user),
    )
    # First attempt checks the live reservation; retry may see it already captured, so query stable payment key first.
    capture_key = key + ":capture"
    try:
        captured = bank_request(
            ledger,
            bank,
            "/outcomes/"
            + urllib.parse.quote(capture_key)
            + "?owner="
            + urllib.parse.quote(user),
        )
    except BusinessError:
        if reserve["status"] != "active" or minor(reserve["remaining"]) < amount:
            raise BusinessError("reservation_insufficient", 409)
        captured = bank_request(
            ledger,
            bank,
            "/actions/capture_reservation",
            bank_body(
                bank,
                capture_key,
                user,
                "capture_reservation",
                account,
                {"reservationId": args["reservationId"], "amount": money(amount)},
            ),
        )
    if captured.get("status") != "SUCCEEDED":
        return result
    result.update(status="SUCCEEDED", paymentOperationId=capture_key)
    with ledger.atomic() as db:
        db.execute(
            "UPDATE operations SET state='SUCCEEDED',result=? WHERE key=?",
            (json.dumps(result), key),
        )
        db.execute("UPDATE orders SET state='paid',fulfill_at=? WHERE id=?", (now + 3600, result["orderId"]))
    result["fulfillmentStatus"] = "paid"
    with ledger.atomic() as db:
        db.execute("UPDATE operations SET result=? WHERE key=?", (json.dumps(result), key))
    return result


def merchant_cancel(ledger, key, user):
    with ledger.connect() as db:
        op = db.execute("SELECT * FROM operations WHERE key=?", (key,)).fetchone()
        if not op or op["owner"] != user:
            raise BusinessError("order_not_owned", 403)
        result = json.loads(op["result"])
        order = db.execute(
            "SELECT * FROM orders WHERE id=?", (result["orderId"],)
        ).fetchone()
        if order["state"] == "cancelled":
            return result
        if ledger.now(db) > order["cancel_until"] or order["state"] == "fulfilled":
            raise BusinessError("cancellation_window_closed", 409)
    if op["state"] == "SUCCEEDED":
        bank_request(
            ledger,
            order["bank"],
            "/actions/refund",
            bank_body(
                order["bank"],
                key + ":refund",
                user,
                "refund",
                order["account"],
                {"originalKey": key + ":capture"},
            ),
        )
    else:
        # A payment in flight must be reconciled before cancellation; no pretend rollback.
        try:
            captured = bank_request(
                ledger,
                order["bank"],
                "/outcomes/"
                + urllib.parse.quote(key + ":capture")
                + "?owner="
                + urllib.parse.quote(user),
            )
        except BusinessError as e:
            if e.status != 404:
                raise BusinessError("payment_result_unknown", 409)
            captured = {"status": "NOT_FOUND"}
        if captured.get("status") == "SUCCEEDED":
            bank_request(
                ledger,
                order["bank"],
                "/actions/refund",
                bank_body(
                    order["bank"],
                    key + ":refund",
                    user,
                    "refund",
                    order["account"],
                    {"originalKey": key + ":capture"},
                ),
            )
        elif captured.get("status") != "NOT_FOUND":
            raise BusinessError("payment_result_unknown", 409)
    result["status"] = "CANCELLED"
    result["fulfillmentStatus"] = "cancelled"
    with ledger.atomic() as db:
        db.execute("UPDATE orders SET state='cancelled' WHERE id=?", (order["id"],))
        db.execute(
            "UPDATE operations SET state='CANCELLED',result=? WHERE key=?",
            (json.dumps(result), key),
        )
    return result


def openapi(kind):
    prefix = PREFIXES[kind]
    paths = {
        prefix + "/health": {"get": {"responses": {"200": {"description": "health"}}}}
    }
    if kind in ("A", "B", "C"):
        for path in (
            "/customers/{owner}/accounts",
            "/accounts/{id}",
            "/accounts/{id}/transactions",
            "/accounts/{id}/holdings",
            "/accounts/{id}/subscriptions",
            "/accounts/{id}/cards",
            "/accounts/{id}/credit-bills",
            "/products",
            "/outcomes/{key}",
            "/reservations/{id}",
        ):
            paths[prefix + path] = {
                "get": {
                    "responses": {
                        "200": {"description": "native resource"},
                        "404": {"description": "not found"},
                    }
                }
            }
        required = (["requestKey", "customerId", "fromAccount", "arguments"] if kind == "B"
                    else ["data"] if kind == "C" else ["idempotencyKey", "userId", "accountId", "params"])
        native_schema = {"type": "object", "required": required}
        if kind == "C":
            native_schema["properties"] = {"data": {"type": "object", "required": ["type", "id", "attributes"],
                "properties": {"attributes": {"type": "object", "required": ["idempotencyKey", "userId", "accountId", "params"]}}}}
        for action in OPERATIONS:
            paths[prefix + "/actions/" + action] = {
                "post": {
                    "requestBody": {
                        "required": True,
                        "content": {"application/json": {"schema": native_schema}},
                    },
                    "responses": {
                        "200": {"description": "durable outcome"},
                        "409": {"description": "business conflict"},
                    },
                }
            }
    else:
        paths[prefix + "/orders"] = {
            "post": {"responses": {"200": {"description": "durable order"}}}
        }
        paths[prefix + "/outcomes/{key}"] = {
            "get": {"responses": {"200": {"description": "verified order state"}}}
        }
    paths[prefix + "/outcomes/{key}/cancel"] = {
        "post": {
            "responses": {
                "200": {"description": "compensated"},
                "409": {"description": "not reversible"},
            }
        }
    }
    return {
        "openapi": "3.0.3",
        "info": {"title": "PocketBanker Mock " + kind, "version": "1.0.0"},
        "paths": paths,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "PocketBankerMock/1.0"

    def log_message(self, format, *args):
        pass  # don't log account URLs, payloads, or credentials

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 131072:
            raise BusinessError("body_too_large", 413)
        try:
            return json.loads(self.rfile.read(length)) if length else {}
        except (ValueError, UnicodeError):
            raise BusinessError("invalid_json")

    def authorize(self, admin=False):
        expected = self.server.admin_token if admin else self.server.ledger.token
        actual = self.headers.get(
            "X-Mock-Admin-Token" if admin else "X-Mock-Service-Token", ""
        )
        if not expected or not hmac.compare_digest(expected, actual):
            raise BusinessError("mock_token_required", 401)

    def handle_request(self, method):
        ledger = self.server.ledger
        kind = ledger.kind
        mode = "none"
        try:
            parsed = urllib.parse.urlsplit(self.path)
            path = urllib.parse.unquote(parsed.path)
            query = urllib.parse.parse_qs(parsed.query)
            body = self.body() if method == "POST" else None
            if path == "/openapi.json":
                result = openapi(kind)
            elif path == "/admin/reset" and method == "POST":
                self.authorize(True)
                ledger.reset()
                result = {"status": "reset"}
            elif path == "/admin/advance" and method == "POST":
                self.authorize(True)
                ledger.advance(float(body.get("seconds", 0)))
                result = {"status": "advanced"}
            elif path == "/admin/transactions" and method == "POST":
                self.authorize(True)
                result=ledger.import_transactions(body["accountId"],body["rows"])
            elif path == "/admin/fault" and method == "POST":
                self.authorize(True)
                ledger.fault(body["operation"], body["mode"], body.get("count", 1))
                result = {"status": "configured"}
            elif path == "/clearing/incoming" and method == "POST":
                self.authorize()
                mode = ledger.take_fault("clearing")
                if mode == "fail_before":
                    raise BusinessError("injected_failure", 503)
                result = ledger.incoming(body)
            else:
                prefix = PREFIXES[kind]
                if not path.startswith(prefix + "/"):
                    raise BusinessError("not_found", 404)
                tail = path[len(prefix) :]
                parts = tail.strip("/").split("/")
                if tail == "/health":
                    mode=ledger.take_fault("health")
                    if mode=="fail_before":raise BusinessError("injected_failure",503)
                    result = {"status": "up", "provider": kind}
                else:
                    self.authorize()
                    if (
                        parts[0] == "customers"
                        and len(parts) == 3
                        and parts[2] == "accounts"
                        and method == "GET"
                    ):
                        result = ledger.accounts(parts[1])
                    elif tail == "/products" and method == "GET":
                        result = ledger.products()
                    elif parts[0] == "accounts" and len(parts) >= 2 and method == "GET":
                        if len(parts) == 2:
                            mode = ledger.take_fault("account")
                            if mode == "fail_before":
                                raise BusinessError("injected_failure", 503)
                            result = ledger.account(parts[1])
                        elif parts[2] == "transactions":
                            result = ledger.transactions(
                                parts[1],
                                int(query.get("offset", ["0"])[0]),
                                int(query.get("limit", ["100"])[0]),
                                float(query.get("from", ["0"])[0]),
                                float(query.get("to", ["1e20"])[0]),
                            )
                        elif parts[2] == "holdings":
                            result = ledger.holdings(parts[1])
                        elif parts[2] == "cards":
                            result = ledger.cards(parts[1])
                        elif parts[2] == "credit-bills":
                            result = ledger.credit_bills(parts[1])
                        elif parts[2] == "subscriptions":
                            result = ledger.subscriptions(parts[1])
                        else:
                            raise BusinessError("not_found", 404)
                    elif parts[0] == "reservations" and method == "GET":
                        result = ledger.reservation(
                            parts[1], query.get("owner", [""])[0]
                        )
                    elif parts[0] == "outcomes":
                        owner = (
                            query.get("owner", [""])[0]
                            if method == "GET"
                            else body.get("userId", "")
                        )
                        if (
                            len(parts) == 3
                            and parts[2] == "cancel"
                            and method == "POST"
                        ):
                            mode = ledger.take_fault("cancel")
                            if mode == "fail_before":
                                raise BusinessError("injected_failure", 503)
                            result = (
                                merchant_cancel(ledger, parts[1], owner)
                                if kind in ("flower", "cake")
                                else ledger.compensate(parts[1], owner)
                            )
                        elif method == "GET":
                            if kind in ("flower", "cake"):
                                with ledger.connect() as db:
                                    row = db.execute(
                                        "SELECT * FROM operations WHERE key=? AND owner=?",
                                        (parts[1], owner),
                                    ).fetchone()
                                    if not row:
                                        raise BusinessError("operation_not_found", 404)
                                    result = json.loads(row["result"])
                                with ledger.atomic() as db:
                                    order = db.execute("SELECT * FROM orders WHERE id=?", (result["orderId"],)).fetchone()
                                    if order and order["state"] == "paid" and order["fulfill_at"] is not None and order["fulfill_at"] <= ledger.now(db):
                                        db.execute("UPDATE orders SET state='fulfilled',fulfilled_at=? WHERE id=?", (ledger.now(db),order["id"]))
                                        result["fulfillmentStatus"] = "fulfilled"
                                        db.execute("UPDATE operations SET result=? WHERE key=?", (json.dumps(result),parts[1]))
                                if row["state"] == "PENDING":
                                    result = merchant_order(
                                        ledger,
                                        parts[1],
                                        owner,
                                        json.loads(row["delivery"]),
                                    )
                            else:
                                result = ledger.outcome(parts[1], owner)
                        else:
                            raise BusinessError("not_found", 404)
                    elif (
                        tail == "/orders"
                        and kind in ("flower", "cake")
                        and method == "POST"
                    ):
                        mode = ledger.take_fault("order")
                        if mode == "fail_before":
                            raise BusinessError("injected_failure", 503)
                        if mode == "reject_before":
                            raise BusinessError("injected_rejection", 409)
                        result = merchant_order(
                            ledger,
                            body["idempotencyKey"],
                            body["userId"],
                            body["params"],
                        )
                    elif parts[0] == "actions" and len(parts) == 2 and method == "POST":
                        mode = ledger.take_fault(parts[1])
                        if mode == "fail_before":
                            raise BusinessError("injected_failure", 503)
                        if mode == "reject_before":
                            raise BusinessError("injected_rejection", 409)
                        if kind == "B":
                            key, user, account, args = (
                                body["requestKey"],
                                body["customerId"],
                                body.get("fromAccount"),
                                body["arguments"],
                            )
                        else:
                            payload = (
                                body["data"]["attributes"] if kind == "C" else body
                            )
                            key, user, account, args = (
                                payload["idempotencyKey"],
                                payload["userId"],
                                payload.get("accountId"),
                                payload["params"],
                            )
                        if mode=="pretend_success" and parts[1] in ("unfreeze","release"):
                            result={"operationId":key,"status":"SUCCEEDED","kind":parts[1],"amount":"0.00","currency":"CNY"}
                            if parts[1]=="unfreeze":result["frozen"]=False
                            else:result["reservationId"]=args["reservationId"]
                        else:
                            result = ledger.execute(key, user, parts[1], account, args)
                    else:
                        raise BusinessError("not_found", 404)
                result = wrap(kind, result)
            if mode == "timeout_after":
                time.sleep(3)
            if mode == "malformed_after":
                result = {"malformed": True}
            self.respond(200, result)
        except BusinessError as e:
            self.respond(e.status, {"error": e.code})
        except (ValueError, KeyError, TypeError):
            self.respond(400, {"error": "invalid_request"})
        except Exception:
            self.respond(500, {"error": "mock_internal_error"})

    def respond(self, status, value):
        payload = json.dumps(value, ensure_ascii=False).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            # This HTTP/1.0 server closes after each response. Explicitly advertise that
            # so pooled clients do not reuse a socket while its peer is closing it.
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass


def serve(kind, default_port):
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--port", type=int, default=int(os.environ.get("PORT", default_port))
    )
    parser.add_argument(
        "--host", default=os.environ.get("MOCK_BIND_ADDRESS", "127.0.0.1")
    )
    parser.add_argument(
        "--db",
        default=os.environ.get("MOCK_DB_PATH", "local-data/" + kind + ".sqlite3"),
    )
    args = parser.parse_args()
    urls = {
        bank: os.environ.get(
            "BANK_" + bank + "_URL", "http://127.0.0.1:" + str(8000 + ord(bank) - 64)
        )
        for bank in ("A", "B", "C")
    }
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.ledger = Ledger(
        args.db, kind, urls, os.environ.get("MOCK_SERVICE_TOKEN", "")
    )
    server.admin_token = os.environ.get("MOCK_ADMIN_TOKEN", "")
    server.serve_forever()
