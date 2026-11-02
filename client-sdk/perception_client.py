"""Source-scoped protocol example; collection and durable retry storage belong to the caller."""

import json
import urllib.parse
import urllib.request


class PerceptionClient:
    def __init__(self, base_url, source_id, source_token):
        self.base_url = base_url.rstrip("/")
        self.source_id = source_id
        self._source_token = source_token

    def _request(self, method, path, body=None):
        request = urllib.request.Request(
            self.base_url + "/api/v1" + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            method=method,
            headers={
                "Authorization": "Bearer " + self._source_token,
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    def report_collector(self, report):
        """Keep the same sequence/content on retry; increment sequence for a new observation."""
        source = urllib.parse.quote(self.source_id, safe="")
        return self._request("POST", f"/perception/sources/{source}/collector", report)

    def submit_sms(self, external_id, occurred_at, sender, text, subject=None):
        """202/queued is not completion. Retry with the original ID, timestamp and content."""
        return self._request("POST", "/perception/sms", {
            "sourceId": self.source_id,
            "externalId": external_id,
            "occurredAt": occurred_at,
            "sender": sender,
            "text": text,
            "subject": subject,
        })

    def receipt(self, receipt_id):
        source = urllib.parse.quote(self.source_id, safe="")
        receipt = urllib.parse.quote(receipt_id, safe="")
        return self._request("GET", f"/perception/sources/{source}/receipts/{receipt}")

    def queue_health(self, body):
        """The caller owns collection and consent. The backend stores only minimized features."""
        return self._request("POST", "/perception/health/queue", {**body, "sourceId": self.source_id})

    def queue_calendar(self, body):
        return self._request("POST", "/perception/calendar/queue", {**body, "sourceId": self.source_id})

    def job(self, job_id):
        source = urllib.parse.quote(self.source_id, safe="")
        job = urllib.parse.quote(job_id, safe="")
        return self._request("GET", f"/perception/sources/{source}/jobs/{job}")
