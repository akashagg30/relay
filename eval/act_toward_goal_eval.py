#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Akash Agarwal
#
# R5 eval harness for the `act_toward_goal` MCP tool (Relay v1.2).
# Drives the REAL device endpoint exactly like the frontier does:
#   POST http://<phone>:8765/mcp  (Authorization: Bearer <token>)
#   JSON-RPC 2.0: initialize -> tools/list -> tools/call
#
# Python stdlib only: argparse, json, os, sys, time, urllib, datetime.
# No third-party dependencies (no `requests`, no MCP SDK).
#
# Usage:
#   RELAY_URL=http://<phone>:8765/mcp RELAY_TOKEN=<token> \
#       python3 eval/act_toward_goal_eval.py --device "Pixel 8, Android 15" --tier heuristic
#
# Exit codes:
#   0  >=5 flows ran and every goal response was structurally valid
#      (a flow landing on escalate/max_steps/paused/stuck is a MEASUREMENT,
#      not a harness failure -- it never crashes the run).
#   1  fewer than 5 flows ran, a transport/preflight error occurred, or any
#      goal response was structurally invalid.
#   2  missing URL/token configuration.
#
# Output: eval/results/<YYYYMMDD-HHMM>.json (raw audit trail, GITIGNORED --
# it contains real on-screen UI text) + eval/results/<YYYYMMDD-HHMM>.md
# (the committable report).

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

VALID_STATUSES = ("done", "escalate", "paused", "stuck", "blocked", "max_steps")
VALID_ENGINES = ("heuristic", "jev")
REQUIRED_SCHEMA_PROPS = (
    "goal",
    "maxSteps",
    "confidenceThreshold",
    "pauseBeforeKeywords",
)

# Ground-truth action names in flow files are written in the human vocabulary
# from the eval card (ACTION, INPUT_TEXT, TOGGLE, SWIPE, ...). The wire sends
# GoalAction values (click_node, input_text, submit, swipe, back, wait, none).
# A TOGGLE on a real Settings screen IS a click_node -- the loop has no
# separate toggle primitive -- so TOGGLE normalises to click_node.
ACTION_ALIASES = {
    "action": "click_node",
    "click": "click_node",
    "click_node": "click_node",
    "tap": "click_node",
    "toggle": "click_node",
    "input_text": "input_text",
    "input": "input_text",
    "type": "input_text",
    "submit": "submit",
    "press_enter": "submit",
    "enter": "submit",
    "swipe": "swipe",
    "scroll": "swipe",
    "back": "back",
    "wait": "wait",
    "none": "none",
}


class EvalError(Exception):
    """Fatal harness error (transport, preflight, config)."""


def pct(sorted_vals, p):
    """Percentile (0-100) with linear interpolation. None when empty."""
    n = len(sorted_vals)
    if n == 0:
        return None
    if n == 1:
        return float(sorted_vals[0])
    rank = (p / 100.0) * (n - 1)
    lo = int(rank)
    hi = min(lo + 1, n - 1)
    frac = rank - lo
    return float(sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * frac)


def conf_stats(values):
    """p10/p50/p50 + count over a list of confidences. None when empty."""
    s = sorted(float(v) for v in values)
    return {
        "p10": pct(s, 10),
        "p50": pct(s, 50),
        "p90": pct(s, 90),
        "count": len(s),
    }


def normalise_action(name):
    return ACTION_ALIASES.get(str(name or "").strip().lower(), str(name or "").strip().lower())


def action_precision(steps, expected):
    """Ordered greedy match of steps[].action (+element text) against the
    ground-truth expected_actions. Returns (matched, total, precision)."""
    total = len(expected)
    if total == 0:
        return 0, 0, 1.0
    si = 0
    matched = 0
    for exp in expected:
        want = normalise_action(exp.get("action", ""))
        needle = str(exp.get("text_contains") or "").lower()
        while si < len(steps):
            step = steps[si]
            si += 1
            if normalise_action(step.get("action", "")) != want:
                continue
            hay = str(step.get("text") or "")
            if needle and needle not in hay.lower():
                continue
            matched += 1
            break
    return matched, total, matched / total


class RelayClient:
    def __init__(self, url, token, timeout):
        self.url = url
        self.token = token
        self.timeout = timeout
        self._next_id = 0

    def _rpc(self, method, params):
        self._next_id += 1
        rid = str(self._next_id)
        body = json.dumps(
            {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
        ).encode("utf-8")
        req = urllib.request.Request(
            self.url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.token,
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            raise EvalError("HTTP %s on %s: %s" % (e.code, method, e.read()[:300]))
        except urllib.error.URLError as e:
            raise EvalError("unreachable %s (%s): %s" % (self.url, method, e.reason))
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            raise EvalError("non-JSON reply to %s: %s" % (method, raw[:300]))
        if "error" in msg and msg["error"]:
            raise EvalError("JSON-RPC error on %s: %s" % (method, msg["error"]))
        return msg.get("result", {})

    def initialize(self):
        return self._rpc(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "act-toward-goal-eval", "version": "1.0"},
            },
        )

    def tools_list(self):
        return self._rpc("tools/list", {})

    def tool_call(self, name, arguments):
        result = self._rpc("tools/call", {"name": name, "arguments": arguments or {}})
        content = result.get("content", [])
        texts = [c.get("text", "") for c in content if isinstance(c, dict)]
        return {
            "isError": bool(result.get("isError", False)),
            "texts": texts,
            "raw_result": result,
        }


def preflight(client):
    """Prove act_toward_goal is advertised with all four schema params."""
    tools = client.tools_list().get("tools", [])
    by_name = {t.get("name"): t for t in tools if isinstance(t, dict)}
    if "act_toward_goal" not in by_name:
        raise EvalError(
            "preflight FAILED: act_toward_goal not in tools/list "
            "(got %d tools: %s)" % (len(by_name), sorted(
                k for k in by_name.keys() if isinstance(k, str))[:20])
        )
    props = (by_name["act_toward_goal"].get("inputSchema", {}) or {}).get(
        "properties", {}
    ) or {}
    missing = [p for p in REQUIRED_SCHEMA_PROPS if p not in props]
    check = {
        "tool_found": True,
        "tool_count": len(by_name),
        "schema_properties": sorted(props.keys()),
        "required_present": [p for p in REQUIRED_SCHEMA_PROPS if p in props],
        "required_missing": missing,
    }
    if missing:
        raise EvalError("preflight FAILED: schema missing %s" % missing)
    return check


def validate_goal_response(resp):
    """Structural validation. Returns a list of problem strings (empty = valid).

    elementIds is non-empty IFF status == escalate.
    """
    problems = []
    if not isinstance(resp, dict):
        return ["top-level response is not a JSON object"]
    status = resp.get("status")
    if status not in VALID_STATUSES:
        problems.append("status %r not in %s" % (status, list(VALID_STATUSES)))
    steps = resp.get("steps")
    if not isinstance(steps, list):
        problems.append("steps is not a list")
        steps = []
    else:
        for i, s in enumerate(steps):
            if not isinstance(s, dict):
                problems.append("steps[%d] is not an object" % i)
                continue
            if "action" not in s:
                problems.append("steps[%d] missing action" % i)
            c = s.get("confidence")
            if not isinstance(c, (int, float)):
                problems.append("steps[%d] confidence not a number" % i)
    conf = resp.get("confidence")
    if not isinstance(conf, dict):
        problems.append("confidence block missing")
    else:
        for k in ("threshold", "min", "max", "mean", "count"):
            if k not in conf:
                problems.append("confidence.%s missing" % k)
            elif not isinstance(conf[k], (int, float)):
                problems.append("confidence.%s not a number" % k)
    if resp.get("engine") not in VALID_ENGINES:
        problems.append("engine %r not in %s" % (resp.get("engine"), list(VALID_ENGINES)))
    eids = resp.get("elementIds", [])
    if not isinstance(eids, list):
        problems.append("elementIds is not a list")
    elif status == "escalate" and len(eids) == 0:
        problems.append("escalate with empty elementIds")
    elif status != "escalate" and len(eids) != 0:
        problems.append(
            "non-escalate status %r carries %d elementIds" % (status, len(eids))
        )
    return problems


def run_flow(client, flow, threshold):
    """Run setup steps, then act_toward_goal. Returns the flow record."""
    setup_records = []
    setup_ok = True
    for st in flow.get("setup", []):
        t0 = time.time()
        try:
            out = client.tool_call(st.get("tool", ""), st.get("args", {}))
            ok = not out["isError"]
        except EvalError as e:
            out = {"isError": True, "texts": [str(e)], "raw_result": {}}
            ok = False
        setup_ok = setup_ok and ok
        setup_records.append(
            {
                "tool": st.get("tool", ""),
                "args": st.get("args", {}),
                "ok": ok,
                "texts": out["texts"],
                "ms": int((time.time() - t0) * 1000),
            }
        )
        time.sleep(0.5)

    args = {"goal": flow["goal"], "maxSteps": int(flow.get("maxSteps", 5))}
    if threshold is not None:
        args["confidenceThreshold"] = threshold
    t0 = time.time()
    out = client.tool_call("act_toward_goal", args)
    elapsed_ms = int((time.time() - t0) * 1000)
    raw_text = out["texts"][0] if out["texts"] else ""
    try:
        resp = json.loads(raw_text) if raw_text else {}
    except json.JSONDecodeError:
        resp = {"_unparseable": raw_text[:500]}
    if out["isError"] and not resp:
        resp = {"_tool_error": raw_text[:500]}

    problems = validate_goal_response(resp)
    steps = resp.get("steps", []) if isinstance(resp.get("steps"), list) else []
    step_confs = []
    for s in steps:
        if not isinstance(s, dict):
            continue
        c = s.get("confidence")
        if isinstance(c, (int, float)):
            step_confs.append(float(c))
    matched, total, precision = action_precision(
        steps, flow.get("expected_actions", [])
    )
    n_steps = int(resp.get("stepsTaken", len(steps)))
    avoided = 2 * n_steps - 1
    if avoided < 0:
        avoided = 0  # 0 steps means no baseline calls either; floor, don't go negative

    record = {
        "flow_file": flow.get("_file", "?"),
        "name": flow.get("name", "?"),
        "goal": flow.get("goal", ""),
        "maxSteps": int(flow.get("maxSteps", 5)),
        "expected_final": flow.get("expected_final", "done"),
        "setup_ok": setup_ok,
        "setup": setup_records,
        "goal_args": args,
        "goal_response": resp,
        "transport_ms": elapsed_ms,
        "tool_isError": out["isError"],
        "structural_problems": problems,
        "structurally_valid": len(problems) == 0,
        "metrics": {
            "steps": n_steps,
            "status": resp.get("status"),
            "reason": resp.get("reason"),
            "expected_final": flow.get("expected_final", "done"),
            "final_match": resp.get("status") == flow.get("expected_final", "done"),
            "action_precision": round(precision, 4),
            "precision_matched": matched,
            "precision_total": total,
            "escalations": 1 if resp.get("status") == "escalate" else 0,
            "frontier_calls_avoided": avoided,
            "confidence": conf_stats(step_confs),
            "engine": resp.get("engine"),
            "fallbackReason": resp.get("fallbackReason"),
            "elementIds_count": len(resp.get("elementIds", []))
            if isinstance(resp.get("elementIds"), list)
            else None,
        },
    }
    return record


def aggregate(records):
    precisions = [r["metrics"]["action_precision"] for r in records]
    statuses = [r["metrics"]["status"] for r in records]
    all_confs = []
    for r in records:
        steps = r["goal_response"].get("steps", [])
        for s in steps if isinstance(steps, list) else []:
            if not isinstance(s, dict):
                continue
            c = s.get("confidence")
            if isinstance(c, (int, float)):
                all_confs.append(float(c))
    return {
        "flows_run": len(records),
        "flows_valid": sum(1 for r in records if r["structurally_valid"]),
        "mean_precision": round(sum(precisions) / len(precisions), 4)
        if precisions
        else None,
        "escalation_rate": round(
            sum(1 for s in statuses if s == "escalate") / len(statuses), 4
        )
        if statuses
        else None,
        "status_counts": {s: statuses.count(s) for s in sorted(set(statuses))},
        "global_confidence": conf_stats(all_confs),
        "total_steps": sum(r["metrics"]["steps"] for r in records),
        "total_frontier_calls_avoided": sum(
            r["metrics"]["frontier_calls_avoided"] for r in records
        ),
    }


def fmt_conf(v):
    return "n/a" if v is None else "%.3f" % v


def render_markdown(meta, preflight_check, records, agg):
    L = []
    L.append("# act_toward_goal real-device eval report")
    L.append("")
    L.append("- Date (local): %s" % meta["started_local"])
    L.append("- Device: %s" % meta["device"])
    L.append("- Tier selected in app settings: %s" % meta["tier"])
    L.append("- Endpoint: %s" % meta["url_host"])
    L.append("- Flows: %s" % meta["flow_files"])
    L.append(
        "- Preflight: act_toward_goal advertised with %s"
        % ", ".join(preflight_check["required_present"])
    )
    L.append("")
    L.append("## Per-flow results")
    L.append("")
    L.append(
        "| flow | status | steps | precision | esc | avoided | conf p50 | engine | fallback | reason |"
    )
    L.append(
        "|---|---|---|---|---|---|---|---|---|---|"
    )
    for r in records:
        m = r["metrics"]
        c = m["confidence"]
        L.append(
            "| %s | %s | %d | %.2f (%d/%d) | %d | %d | %s | %s | %s | %s |"
            % (
                r["name"],
                m["status"],
                m["steps"],
                m["action_precision"],
                m["precision_matched"],
                m["precision_total"],
                m["escalations"],
                m["frontier_calls_avoided"],
                fmt_conf(c["p50"]),
                m["engine"],
                (m["fallbackReason"] or "-"),
                str(m["reason"] or "-")[:120],
            )
        )
    L.append("")
    L.append("## Aggregate")
    L.append("")
    L.append("```json")
    L.append(json.dumps(agg, indent=2))
    L.append("```")
    L.append("")
    L.append("## Threshold honesty")
    L.append("")
    g = agg["global_confidence"]
    if g["count"] == 0:
        L.append(
            "No step confidences were recorded (all flows ended with zero "
            "steps). No statement about the 0.50-0.60 expectation can be made "
            "-- this is itself a finding: the loop never acted."
        )
    else:
        p50 = g["p50"]
        if 0.45 <= p50 <= 0.65:
            verdict = (
                "CONSISTENT with the ~0.50-0.60 expectation from local "
                "experience."
            )
        elif p50 < 0.45:
            verdict = (
                "BELOW the ~0.50-0.60 expectation: the median decision sits at "
                "%.3f. Finding for threshold tuning -- do NOT fix by editing "
                "this report." % p50
            )
        else:
            verdict = (
                "ABOVE the ~0.50-0.60 expectation: the median decision sits at "
                "%.3f. The guard threshold may be conservative for these "
                "flows." % p50
            )
        L.append(
            "Measured step-confidence distribution over %d real-scene steps: "
            "p10=%s p50=%s p90=%s. %s"
            % (g["count"], fmt_conf(g["p10"]), fmt_conf(p50), fmt_conf(g["p90"]), verdict)
        )
    L.append("")
    L.append("## What failed and why")
    L.append("")
    failures = [
        r
        for r in records
        if r["metrics"]["status"] != r["metrics"]["expected_final"]
        or not r["structurally_valid"]
        or not r["setup_ok"]
    ]
    if not failures:
        L.append("Nothing failed: every flow ended on its expected final status "
                 "with a structurally valid response and clean setup.")
    else:
        for r in failures:
            m = r["metrics"]
            steps = r["goal_response"].get("steps", [])
            last = steps[-1] if isinstance(steps, list) and steps else {}
            L.append(
                "- **%s**: status=%s (expected %s), steps=%d, precision=%.2f, "
                "setup_ok=%s, structurally_valid=%s%s; reason=%s; last step=%s"
                % (
                    r["name"],
                    m["status"],
                    m["expected_final"],
                    m["steps"],
                    m["action_precision"],
                    r["setup_ok"],
                    r["structurally_valid"],
                    (" problems=" + "; ".join(r["structural_problems"]))
                    if r["structural_problems"]
                    else "",
                    str(m["reason"] or "-")[:200],
                    json.dumps(last)[:300] if last else "-",
                )
            )
            if m["status"] in ("escalate", "paused", "stuck"):
                eids = r["goal_response"].get("elementIds", [])
                L.append(
                    "  - elementIds offered: %d; engine=%s fallbackReason=%s"
                    % (
                        len(eids) if isinstance(eids, list) else -1,
                        m["engine"],
                        (m["fallbackReason"] or "-"),
                    )
                )
    L.append("")
    L.append("## Notes")
    L.append("")
    L.append(
        "- Raw JSON audit trail retained alongside this file but intentionally "
        "NOT committed to git (eval/results/*.json is gitignored: it embeds "
        "real on-screen UI text). Metrics above were computed by "
        "`eval/act_toward_goal_eval.py`, not typed by hand."
    )
    L.append("- Tier actually in effect is read from each response's `engine` "
              "field, not assumed from the settings screen.")
    n_jev = sum(1 for r in records if r["metrics"]["engine"] == "jev")
    n_heu = sum(1 for r in records if r["metrics"]["engine"] == "heuristic")
    L.append("- Engine mix observed: heuristic=%d jev=%d." % (n_heu, n_jev))
    return "\n".join(L) + "\n"


def load_flows(flows_dir):
    import glob as _glob

    paths = sorted(_glob.glob(os.path.join(flows_dir, "*.json")))
    flows = []
    for p in paths:
        with open(p, "r", encoding="utf-8") as f:
            flow = json.load(f)
        for key in ("name", "goal", "expected_actions"):
            if key not in flow:
                raise EvalError("flow %s missing required key %r" % (p, key))
        flow.setdefault("maxSteps", 5)
        flow.setdefault("setup", [])
        flow.setdefault("expected_final", "done")
        flow["_file"] = os.path.basename(p)
        flows.append(flow)
    return flows


def parse_args(argv):
    ap = argparse.ArgumentParser(
        description="Real-device eval harness for act_toward_goal (Relay v1.2). "
        "Drives POST <url> with JSON-RPC initialize -> tools/list -> tools/call."
    )
    ap.add_argument("--url", default=os.environ.get("RELAY_URL", ""),
                    help="MCP endpoint (env RELAY_URL)")
    ap.add_argument("--token", default=os.environ.get("RELAY_TOKEN", ""),
                    help="Bearer token (env RELAY_TOKEN; never committed)")
    ap.add_argument("--flows", default=None,
                    help="flows directory (default: eval/flows next to this script)")
    ap.add_argument("--out-dir", default=None,
                    help="results directory (default: eval/results next to this script)")
    ap.add_argument("--threshold", type=float, default=None,
                    help="optional confidenceThreshold override for every flow "
                    "(omit = device default from settings)")
    ap.add_argument("--timeout", type=float, default=180.0,
                    help="per-HTTP-call timeout in seconds (default 180)")
    ap.add_argument("--device", default="unknown (fill in: model + Android version)",
                    help="human device label for the report header")
    ap.add_argument("--tier", default="heuristic",
                    help="tier selected in app settings for this run (recorded, "
                    "then verified against each response's engine field)")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])
    here = os.path.dirname(os.path.abspath(__file__))
    flows_dir = args.flows or os.path.join(here, "flows")
    out_dir = args.out_dir or os.path.join(here, "results")

    if not args.url or not args.token:
        print(
            "error: missing MCP endpoint configuration.",
            file=sys.stderr,
        )
        print(
            "Set RELAY_URL (e.g. http://<phone-ip>:8765/mcp) and RELAY_TOKEN, "
            "or pass --url/--token. The token is never written to disk.",
            file=sys.stderr,
        )
        return 2

    try:
        flows = load_flows(flows_dir)
    except (OSError, json.JSONDecodeError, EvalError) as e:
        print("error: cannot load flows from %s: %s" % (flows_dir, e), file=sys.stderr)
        return 1
    if len(flows) < 5:
        print(
            "error: need >=5 flows, found %d in %s" % (len(flows), flows_dir),
            file=sys.stderr,
        )
        return 1

    client = RelayClient(args.url, args.token, args.timeout)
    try:
        init = client.initialize()
        print("initialize ok: server=%s" % json.dumps(init.get("serverInfo", {})))
        check = preflight(client)
    except EvalError as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    print(
        "preflight ok: act_toward_goal advertised with %s (%d tools total)"
        % (", ".join(check["required_present"]), check["tool_count"])
    )

    records = []
    for flow in flows:
        print("--- flow %s: goal=%r" % (flow["name"], flow["goal"]))
        try:
            rec = run_flow(client, flow, args.threshold)
        except EvalError as e:
            print("error: transport failure on flow %s: %s" % (flow["name"], e),
                  file=sys.stderr)
            return 1
        m = rec["metrics"]
        print(
            "  status=%s steps=%d precision=%.2f engine=%s valid=%s%s"
            % (
                m["status"],
                m["steps"],
                m["action_precision"],
                m["engine"],
                rec["structurally_valid"],
                "" if rec["structurally_valid"]
                else " problems=" + "; ".join(rec["structural_problems"]),
            )
        )
        records.append(rec)
        time.sleep(1.0)

    agg = aggregate(records)
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    os.makedirs(out_dir, exist_ok=True)
    host = args.url.split("@")[-1].split("/")[2] if "://" in args.url else args.url
    meta = {
        "started_local": datetime.now().isoformat(timespec="seconds"),
        "device": args.device,
        "tier": args.tier,
        "url_host": host,
        "flow_files": [f["_file"] for f in flows],
        "threshold_override": args.threshold,
        "preflight": check,
    }
    payload = {"meta": meta, "flows": records, "aggregate": agg}
    json_path = os.path.join(out_dir, stamp + ".json")
    md_path = os.path.join(out_dir, stamp + ".md")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(render_markdown(meta, check, records, agg))
    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    print(
        "aggregate: flows=%d valid=%d mean_precision=%.3f escalation_rate=%s "
        "total_steps=%d avoided=%d"
        % (
            agg["flows_run"],
            agg["flows_valid"],
            agg["mean_precision"] if agg["mean_precision"] is not None else -1,
            agg["escalation_rate"],
            agg["total_steps"],
            agg["total_frontier_calls_avoided"],
        )
    )

    if agg["flows_run"] < 5 or agg["flows_valid"] != agg["flows_run"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
