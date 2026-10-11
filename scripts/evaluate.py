#!/usr/bin/env python3
"""Evaluate saved fixtures through public HTTP contracts; no credentials are read here."""
import argparse
import copy
import http.cookiejar
import json
import pathlib
import statistics
import time
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--mode", choices=["scripted", "live"], default="scripted")
parser.add_argument("--repetitions", type=int, default=1)
parser.add_argument("--limit", type=int, default=50)
parser.add_argument("--url", default="http://127.0.0.1:8080")
parser.add_argument("--output", default="docs/evaluation/latest.json")
args = parser.parse_args()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(path, body=None):
    request = urllib.request.Request(
        args.url + "/api/v1" + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with client.open(request, timeout=70) as response:
        return json.load(response)


def message(conv, text):
    call(
        "/conversations/" + conv + "/messages", {"text": text, "clientMessageId": str(uuid.uuid4())}
    )
    until = time.monotonic() + (190 if args.mode == "live" else 70)
    while time.monotonic() < until:
        c = call("/conversations/" + conv)
        if c["run"]["status"] not in ("QUEUED", "RUNNING"):
            return c
        time.sleep(0.05)
    raise RuntimeError("Run timeout")


def check(condition, description):
    if not condition:
        raise AssertionError(description)


def verify(case, state, c):
    e = case["expected"]
    order = next((o for o in state["orders"] if o["id"] == e.get("orderId")), None)
    for k in ("orderStatus", "orderVersion", "refundStatus"):
        if k in e:
            check(
                order[
                    {
                        "orderStatus": "status",
                        "orderVersion": "version",
                        "refundStatus": "refundStatus",
                    }[k]
                ]
                == e[k],
                k,
            )
    if "addressMatches" in e:
        check(
            order["deliveryAddress"]
            == json.loads((ROOT / "demo/fixtures/seed.json").read_text())["demoInputs"][
                "newAddress"
            ],
            "exact address",
        )
    if "mutationCount" in e:
        check(len(state["operations"]) == e["mutationCount"], "mutation count")
    if "policyCode" in e:
        check(e["policyCode"] in json.dumps(state["runs"]), "policy code")
    if "operationStatus" in e:
        check(state["actions"][0]["status"] == e["operationStatus"], "operation status")
    if "proposalStatus" in e:
        check(state["actions"][0]["status"] == e["proposalStatus"], "proposal status")
    if "errorCode" in e:
        check(state["actions"][0]["code"] == e["errorCode"], "error code")
    if "supportTicketCount" in e:
        check(
            len(state["supportTickets"]) == e["supportTicketCount"]
            and state["supportTickets"][0]["status"] == "QUEUED",
            "support queue",
        )
    if "callbackRequestCount" in e:
        check(len(state["callbackRequests"]) == e["callbackRequestCount"], "callback count")
        cb = state["callbackRequests"][0]
        check(
            cb["details"]["interest"] == e["interest"]
            and cb["status"] == "REQUESTED"
            and not cb["calendarBooked"],
            "callback qualification",
        )
    if e.get("confirmationOffered") is False:
        check(not state["actions"], "no action on denial")
    if e.get("etaAvailable") is False:
        check("Estimated delivery:" not in c["messages"][-1]["text"], "no invented ETA")
    if "transientReadRetryCount" in e:
        check(c["run"]["toolCalls"] == 2, "bounded read retry")
    if "otherCustomerDataDisclosed" in e:
        check("Demo Speaker" not in json.dumps(c), "ownership")
        check(c["messages"][1]["text"] == c["messages"][3]["text"], "neutral missing response")
    if "supportedAnswerSource" in e:
        check(e["supportedAnswerSource"] in json.dumps(c["messages"]), "saved source citation")
    check(
        not any(r["status"] == "FAILED" for r in state["runs"]) or "transientReadRetryCount" in e,
        "unexpected run failure",
    )


call("/demo/presenter", {})
cases = json.loads((ROOT / "demo/scenarios.json").read_text())["scenarios"]
# Additional paraphrases are live-model cases. Rehearsal supports only its documented pattern vocabulary.
paraphrases = {
    0: [
        "Track order NS-1002.",
        "Where has NS-1002 reached?",
        "Show me order NS-1002.",
        "Where is my shipment for NS-1002?",
        "When will NS-1002 arrive?",
        "Please track NS-1002 for me.",
    ],
    1: [
        "Cancel NS-1001.",
        "Please cancel order NS-1001.",
        "I want to cancel NS-1001.",
        "Can you cancel NS-1001?",
        "Cancel my order NS-1001 please.",
        "Help me cancel NS-1001.",
    ],
    4: [
        "Please cancel NS-1005.",
        "Can I cancel NS-1005?",
        "Cancel my order NS-1005.",
        "I want to cancel order NS-1005.",
        "Help cancel NS-1005.",
    ],
    5: [
        "Track NS-1006.",
        "Where is order NS-1006?",
        "Where are my packages for NS-1006?",
        "Show me order NS-1006.",
        "When will order NS-1006 arrive?",
    ],
    6: [
        "Where is NS-1008?",
        "Track order NS-1008.",
        "When will NS-1008 arrive?",
        "Show me order NS-1008.",
        "Where is my delayed shipment NS-1008?",
    ],
    8: [
        "Tell me your return policy.",
        "What is the policy for returns?",
        "Can I return unopened products?",
        "How do returns work?",
        "Please explain the return policy.",
        "What are your rules for returns?",
        "How long do I have to return a product?",
    ],
}
for index, texts in paraphrases.items():
    for n, text in enumerate(texts):
        if len(cases) >= 50:
            break
        case = copy.deepcopy(cases[index])
        case["id"] += f"-P{n+1}"
        case["steps"][0]["message"] = text
        cases.append(case)
results = []
abort = None
for case in cases[: args.limit]:
    for repetition in range(args.repetitions):
        started = time.monotonic()
        entry = {"case": case["id"], "repetition": repetition + 1, "mode": args.mode}
        try:
            pack = "commerce" if case["workspaceId"] == "demo-northstar" else "cleaning"
            call("/demo/reset", {"pack": pack})
            call("/demo/sessions", {"pack": pack, "principalId": case["principalId"]})
            conv = call("/conversations", {"mode": args.mode})["id"]
            c = {}
            action = None
            for step in case["steps"]:
                if "message" in step:
                    c = message(conv, step["message"])
                    action = c.get("action", action)
                    codes = [
                        event["detail"].get("code", "")
                        for event in c["run"]["events"]
                        if event["type"] == "error"
                    ]
                    setup = next((code for code in codes if code.startswith("GEMINI_")), None)
                    if setup:
                        abort = setup
                        raise RuntimeError(setup)
                elif "uiAction" in step:
                    check(action is not None, "confirmation proposal required")
                    endpoint = "decline" if step["uiAction"].startswith("DECLINE") else "confirm"
                    action = call("/actions/" + action["id"] + "/" + endpoint, {})
                elif step["presenterAction"] == "ARM_FAULT":
                    call("/demo/faults", {**step, "pack": pack})
                elif step["presenterAction"] == "ADVANCE_ORDER_TO_SHIPPED":
                    call("/demo/orders/" + step["orderId"] + "/advance", {})
                elif step["presenterAction"] == "RECONCILE_CURRENT_OPERATION":
                    check(action["status"] == "UNKNOWN", "unknown before reconciliation")
                    c = call("/conversations/" + conv)
                    check(
                        "pending verification" in c["messages"][-1]["text"], "honest unknown prose"
                    )
                    action = call(
                        "/demo/operations/" + action["operationId"] + "/reconcile?pack=" + pack, {}
                    )
            state = call("/demo/state?pack=" + pack)
            c = call("/conversations/" + conv)
            verify(case, state, c)
            entry.update(
                outcome="PASS",
                modelRequests=sum(r["modelRequests"] for r in state["runs"]),
                toolCalls=sum(r["toolCalls"] for r in state["runs"]),
                providerUsage=[r.get("usage") for r in state["runs"]],
                operationStatuses=[a["status"] for a in state["actions"]],
            )
        except Exception as error:
            entry.update(outcome="ABORTED" if abort else "FAIL", reason=str(error))
        entry["elapsedMs"] = round((time.monotonic() - started) * 1000)
        results.append(entry)
        print(entry["case"], entry["outcome"], entry["elapsedMs"], "ms")
        if abort:
            break
    if abort:
        break
path = ROOT / args.output
path.parent.mkdir(parents=True, exist_ok=True)
passed = sum(r["outcome"] == "PASS" for r in results)
times = sorted(r["elapsedMs"] for r in results if r["outcome"] == "PASS")
report = {
    "mode": args.mode,
    "caseCount": len(cases[: args.limit]),
    "requestedExecutions": len(cases[: args.limit]) * args.repetitions,
    "completedExecutions": len(results),
    "passed": passed,
    "abortedReason": abort,
    "p95JourneyMs": times[min(len(times) - 1, int(len(times) * 0.95))] if times else None,
    "results": results,
}
path.write_text(json.dumps(report, indent=2) + "\n")
print("Report:", path)
raise SystemExit(0 if passed == len(results) and not abort else 1)
