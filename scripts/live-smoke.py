#!/usr/bin/env python3
"""Small, synthetic read-only smoke test. Requires an already running keyed backend."""
import json
import time
import urllib.request
import http.cookiejar

base = "http://127.0.0.1:8080/api/v1"
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        base + path, data=data, headers={"Content-Type": "application/json"}
    )
    with client.open(req, timeout=65) as res:
        return json.load(res)


call("/demo/presenter", {})
call("/demo/sessions", {"pack": "commerce", "principalId": "customer-aylin"})
c = call("/conversations", {"mode": "live"})
run = call(
    "/conversations/" + c["id"] + "/messages",
    {"text": "Where is order NS-1002?", "clientMessageId": "live-smoke-" + str(time.time_ns())},
)
started = time.time()
while time.time() - started < 140:
    result = call("/conversations/" + c["id"])
    if result.get("run", {}).get("status") not in ("QUEUED", "RUNNING"):
        r = result["run"]
        print(
            json.dumps(
                {
                    "status": r["status"],
                    "model": r["model"],
                    "modelRequests": r["modelRequests"],
                    "toolCalls": r["toolCalls"],
                    "responseOrigin": result["messages"][-1].get("responseOrigin"),
                    "elapsedMs": round((time.time() - started) * 1000),
                    "usage": r.get("usage"),
                    "answer": result["messages"][-1]["text"],
                    "providerCauseType": r.get("providerCauseType"),
                    "providerError": r.get("providerError"),
                    "errors": [
                        e["detail"].get("code") for e in r["events"] if e["type"] == "error"
                    ],
                },
                indent=2,
            )
        )
        raise SystemExit(0 if r["status"] == "COMPLETED" and r["toolCalls"] > 0 else 1)
    time.sleep(0.5)
else:
    raise RuntimeError("Smoke run did not finish")
