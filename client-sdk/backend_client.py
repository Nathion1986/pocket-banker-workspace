"""Typed transport example for the backend contract; no UI or phone collection code."""

import json
import urllib.error
import urllib.request


class BackendClient:
    def __init__(self, base_url, user_token):
        self.base_url = base_url.rstrip("/")
        self.user_token = user_token

    def request(self, method, path, body=None):
        request = urllib.request.Request(
            self.base_url + "/api/v1" + path,
            data=None
            if body is None
            else json.dumps(body, ensure_ascii=False).encode(),
            method=method,
            headers={
                "Authorization": "Bearer " + self.user_token,
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def chat(self, session_id, request_id, text):
        return self.request(
            "POST",
            "/chat",
            {"sessionId": session_id, "requestId": request_id, "text": text},
        )

    def create_task(self, request_id, nodes, trigger, source_event_id=None):
        payload = {"requestId": request_id, "nodes": nodes, "trigger": trigger}
        if source_event_id is not None:
            payload["sourceEventId"] = source_event_id
        return self.request("POST", "/tasks", payload)

    def task(self, task_id):
        return self.request("GET", "/tasks/" + task_id)

    def confirm(self, task_id, version, approved=True):
        return self.request(
            "POST",
            "/tasks/" + task_id + "/confirm",
            {"version": version, "approved": approved},
        )

    def verify(self, task_id, version, code, second_factor):
        return self.request(
            "POST",
            "/tasks/" + task_id + "/verify",
            {"version": version, "code": code, "secondFactor": second_factor},
        )
