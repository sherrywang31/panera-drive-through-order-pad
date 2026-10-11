#!/usr/bin/env python3
"""Evaluate transcript-to-bagel-order snapshots; never connects to Android or audio."""
from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(value):
    data = value if isinstance(value, str) else json.dumps(value, sort_keys=True)
    return hashlib.sha256(data.encode()).hexdigest()


def catalog():
    """Use the same fallback vocabulary as Kotlin, without maintaining a second menu."""
    source = (ROOT / "app/src/main/java/com/driveorderpad/bagels/BagelModels.kt").read_text()
    flavors = re.search(r"val fallback = BagelCatalog\(listOf\((.*?)\)\)", source)
    spreads = re.search(r"val spreads = listOf\((.*?)\)", source)
    if not flavors or not spreads:
        raise ValueError("Cannot read Kotlin BagelCatalog; update the catalog adapter.")
    return {"flavors": re.findall(r'"([^"\\]+)"', flavors[1]),
            "spreads": re.findall(r'"([^"\\]+)"', spreads[1])}


def schema(menu):
    def obj(properties):
        return {"type": "object", "properties": properties,
                "required": list(properties), "additionalProperties": False}

    def enum(values):
        return {"type": "string", "enum": values}

    def array(items):
        return {"type": "array", "items": items}

    quantity = {"type": ["integer", "null"], "minimum": 1, "maximum": 999}
    bagel = obj({"flavor": enum(menu["flavors"]), "quantity": quantity,
                 "slice": enum(["UNKNOWN", "SLICED", "UNSLICED"]),
                 "toast": enum(["UNKNOWN", "UNTOASTED", "TOASTED", "DOUBLE"])})
    spread = obj({"flavor": enum(menu["spreads"]), "quantity": quantity,
                  "size": enum(["UNKNOWN", "SINGLE", "TUB"])})
    group = obj({"kind": enum(["UNKNOWN", "INDIVIDUAL", "DOZEN"]),
                 "bagels": array(bagel), "spreads": array(spread)})
    car = obj({"number": {"type": "integer", "minimum": 1, "maximum": 1000},
               "status": enum(["UNCAPTURED", "ORDER", "NONE"]),
               "groups": array(group), "needsClarification": {"type": "boolean"}})
    return obj({"activeCarNumber": {"type": "integer", "minimum": 1, "maximum": 1000},
                "cars": array(car)})


def validate(value, spec, path="$", errors=None):
    """Validate the exact JSON Schema subset emitted above (not a general validator)."""
    errors = [] if errors is None else errors
    kinds = spec["type"] if isinstance(spec["type"], list) else [spec["type"]]
    matches = {"object": isinstance(value, dict), "array": isinstance(value, list),
               "string": isinstance(value, str), "boolean": type(value) is bool,
               "integer": type(value) is int, "null": value is None}
    if not any(matches[k] for k in kinds):
        errors.append(f"{path}: expected {kinds}")
        return errors
    if "enum" in spec and value not in spec["enum"]:
        errors.append(f"{path}: unsupported value {value!r}")
    if type(value) is int and not spec.get("minimum", value) <= value <= spec.get("maximum", value):
        errors.append(f"{path}: out of range")
    if isinstance(value, dict):
        props = spec["properties"]
        for key in spec["required"]:
            if key not in value:
                errors.append(f"{path}.{key}: missing")
        for key in value:
            if key not in props:
                errors.append(f"{path}.{key}: unexpected")
            else:
                validate(value[key], props[key], f"{path}.{key}", errors)
    elif isinstance(value, list):
        for i, item in enumerate(value):
            validate(item, spec["items"], f"{path}[{i}]", errors)
    return errors


def state_errors(state, spec):
    errors = validate(state, spec)
    if errors:
        return errors
    numbers = [car["number"] for car in state["cars"]]
    if len(numbers) != len(set(numbers)):
        errors.append("Duplicate car numbers")
    for car in state["cars"]:
        if car["status"] != "ORDER" and car["groups"]:
            errors.append(f"Car {car['number']}: non-order cannot contain groups")
    return errors


def canonical(state):
    result = copy.deepcopy(state)
    key = lambda item: json.dumps(item, sort_keys=True)
    for car in result["cars"]:
        for group in car["groups"]:
            group["bagels"].sort(key=key)
            group["spreads"].sort(key=key)
        car["groups"].sort(key=key)
    result["cars"].sort(key=lambda car: car["number"])
    return result


def differences(expected, actual, path="$"):
    if type(expected) is not type(actual):
        return [{"path": path, "expected": expected, "actual": actual}]
    if isinstance(expected, dict):
        result = []
        for key in sorted(expected.keys() | actual.keys()):
            if key not in expected or key not in actual:
                result.append({"path": f"{path}.{key}", "expected": expected.get(key),
                               "actual": actual.get(key), "missing_or_extra": True})
            else:
                result.extend(differences(expected[key], actual[key], f"{path}.{key}"))
        return result
    if isinstance(expected, list):
        result = []
        for i in range(max(len(expected), len(actual))):
            if i >= len(expected) or i >= len(actual):
                result.append({"path": f"{path}[{i}]", "expected": expected[i] if i < len(expected) else None,
                               "actual": actual[i] if i < len(actual) else None, "missing_or_extra": True})
            else:
                result.extend(differences(expected[i], actual[i], f"{path}[{i}]"))
        return result
    return [] if expected == actual else [{"path": path, "expected": expected, "actual": actual}]


def score(expected, actual, spec):
    errors = state_errors(actual, spec)
    if errors:
        return {"exact": False, "schema_errors": errors, "diffs": [], "invented_unknowns": None}
    diffs = differences(canonical(expected), canonical(actual))
    return {"exact": not diffs, "schema_errors": [], "diffs": diffs,
            "invented_unknowns": sum(not d.get("missing_or_extra") and
                                     (d["expected"] is None or d["expected"] == "UNKNOWN") and
                                     d["actual"] is not None and d["actual"] != "UNKNOWN" for d in diffs)}


def read_cases(path, spec):
    cases = load(path)
    if not isinstance(cases, list) or not cases:
        raise ValueError("Dataset must be a nonempty list")
    ids = set()
    for case in cases:
        if case["id"] in ids or not case["turns"]:
            raise ValueError("Cases need unique IDs and nonempty turns")
        ids.add(case["id"])
        turns = set()
        for state in [case["initial_state"]] + [t["expected"] for t in case["turns"]]:
            errors = state_errors(state, spec)
            if errors:
                raise ValueError(f"Invalid reference in {case['id']}: {errors}")
        for turn in case["turns"]:
            if turn["id"] in turns or turn["kind"] not in {"final", "partial", "pause", "resume"}:
                raise ValueError("Turn IDs must be unique and kinds valid")
            turns.add(turn["id"])
            if turn["kind"] == "final" and not turn.get("segment_id"):
                raise ValueError("Final turns need segment_id for deduplication")
    return cases


def make_request(model, prompt, spec, menu, state, history, turn):
    request = {"model": model["model"], "messages": [
        {"role": "system", "content": prompt},
        {"role": "user", "content": json.dumps({"menu": menu, "current_state": state,
            "recent_finalized_segments": history, "new_segment": {
                "id": turn["segment_id"], "text": turn["text"]}}, ensure_ascii=False)}],
        "response_format": {"type": "json_schema", "json_schema": {
            "name": "bagel_order_state", "strict": True, "schema": spec}}}
    extra = model.get("request_options", {})
    if set(extra) & set(request):
        raise ValueError("request_options cannot override model, messages, or schema")
    request.update(extra)
    return request


def call_model(model, request, timeout):
    base = model["base_url"].rstrip("/")
    parsed = urlparse(base)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("Use HTTPS for remote endpoints; HTTP is allowed only for localhost")
    if parsed.username or parsed.password:
        raise ValueError("Credentials must not be placed in endpoint URLs")
    headers = {"Content-Type": "application/json"}
    env = model.get("api_key_env")
    if env:
        key = os.environ.get(env)
        if not key:
            raise ValueError(f"Missing environment variable {env}")
        headers["Authorization"] = "Bearer " + key
    started = time.perf_counter()
    req = Request(base + "/chat/completions", data=json.dumps(request).encode(), headers=headers, method="POST")
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    try:
        with build_opener(NoRedirect).open(req, timeout=timeout) as response:
            raw = json.load(response)
    except HTTPError as exc:
        # Do not log response bodies: providers sometimes echo credentials or private requests.
        raise RuntimeError(f"Provider HTTP {exc.code}; no automatic retry") from None
    except URLError:
        raise RuntimeError("Provider connection failed; no automatic retry") from None
    elapsed = time.perf_counter() - started
    choice = raw["choices"][0]
    if choice.get("finish_reason") != "stop" or choice["message"].get("refusal"):
        raise RuntimeError("Provider refused or did not return a complete response")
    prediction = json.loads(choice["message"]["content"])
    usage = raw.get("usage") or {}
    if not isinstance(usage, dict):
        usage = {}
    return prediction, {"latency_seconds": elapsed, "usage": usage, "returned_model": raw.get("model")}


def estimated_cost(usage, model):
    prices = model.get("price_per_million_tokens")
    if not prices or not all(k in usage for k in ("prompt_tokens", "completion_tokens")):
        return None
    cached = usage.get("prompt_tokens_details", {}).get("cached_tokens", 0)
    # Completion usage includes billed reasoning tokens where the provider reports them.
    return ((usage["prompt_tokens"] - cached) * prices["input"] +
            cached * prices.get("cached_input", prices["input"]) +
            usage["completion_tokens"] * prices["output"]) / 1_000_000


def summarize(rows):
    groups = {}
    for row in rows:
        groups.setdefault((row["model"], row["prompt"]), []).append(row)
    summaries = []
    for (model, prompt), items in groups.items():
        conversations = {}
        for row in items:
            conversations.setdefault((row["case_id"], row["repeat"]), []).append(row)
        latencies = [r["latency_seconds"] for r in items if r.get("latency_seconds") is not None]
        calls = [r for r in items if r["called"]]
        model_turns = [r for r in items if r["needs_model"]]
        costs = [r.get("estimated_cost_usd") for r in calls]
        summaries.append({"model": model, "prompt": prompt, "turns": len(items),
            "exact_turn_rate": sum(r["exact"] for r in items) / len(items),
            "model_turn_exact_rate": sum(r["exact"] for r in model_turns) / len(model_turns) if model_turns else None,
            "all_turns_correct_conversation_rate": sum(all(r["exact"] for r in v) for v in conversations.values()) / len(conversations),
            "final_state_exact_rate": sum(v[-1]["exact"] for v in conversations.values()) / len(conversations),
            "errors": sum(bool(r.get("error") or r["schema_errors"]) for r in items),
            "invented_unknown_fields": sum(r["invented_unknowns"] or 0 for r in items),
            "api_calls": len(calls), "latency_p50_seconds": statistics.median(latencies) if latencies else None,
            "latency_p95_seconds": sorted(latencies)[max(0, int(len(latencies)*.95+.999)-1)] if latencies else None,
            "estimated_cost_usd": sum(costs) if costs and all(c is not None for c in costs) else None,
            "prompt_tokens": sum(r.get("usage", {}).get("prompt_tokens", 0) for r in calls),
            "completion_tokens": sum(r.get("usage", {}).get("completion_tokens", 0) for r in calls)})
    return summaries


def write_reports(output, rows):
    summary = summarize(rows)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    fields = list(summary[0]) if summary else []
    with (output / "summary.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(summary)
    lines = ["# Order parsing evaluation", "", "Exactness includes missing information and clarification flags. Errors count as failures.", "",
             "| Model | Prompt | All checkpoints exact | Model turns exact | All turns correct | Final state exact | Errors | p95 seconds | Estimated USD |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for item in summary:
        latency = item["latency_p95_seconds"]
        cost = item["estimated_cost_usd"]
        model_rate = item["model_turn_exact_rate"]
        lines.append(f"| {item['model']} | {item['prompt']} | {item['exact_turn_rate']:.1%} | "
                     f"{format(model_rate, '.1%') if model_rate is not None else 'n/a'} | "
                     f"{item['all_turns_correct_conversation_rate']:.1%} | {item['final_state_exact_rate']:.1%} | "
                     f"{item['errors']} | {latency if latency is not None else 'n/a'} | {cost if cost is not None else 'n/a'} |")
    failures = [r for r in rows if not r["exact"]]
    lines += ["", "## Failures", ""]
    for row in failures:
        lines += [f"### {row['model']} / {row['prompt']} / {row['case_id']} / {row['turn_id']} / repeat {row['repeat']}",
                  "", "```json", json.dumps({"error": row.get("error"), "schema_errors": row["schema_errors"], "diffs": row["diffs"]}, indent=2), "```", ""]
    (output / "report.md").write_text("\n".join(lines) + "\n")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=HERE / "cases/bagel_conversations.json")
    parser.add_argument("--config", type=Path, default=HERE / "models.example.json")
    parser.add_argument("--models", nargs="+", help="Config model names; default all")
    parser.add_argument("--prompts", nargs="+", default=["baseline", "contextual"])
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--mode", choices=["rollout", "teacher-forced"], default="rollout")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--dry-run", action="store_true", help="Validate fixtures and export sample requests; no API calls")
    parser.add_argument("--predictions", type=Path, help="Score saved JSONL predictions offline; no API calls")
    parser.add_argument("--output", type=Path, default=HERE / "results" / time.strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args(argv)
    if args.repeat < 1 or args.timeout <= 0 or (args.dry_run and args.predictions):
        parser.error("Use positive repeats/timeout and choose dry-run or predictions")
    menu = catalog()
    spec = schema(menu)
    cases = read_cases(args.cases, spec)
    models = load(args.config)["models"]
    allowed = {"name", "model", "base_url", "api_key_env", "request_options",
               "price_per_million_tokens", "pricing_checked", "pricing_source"}
    for model in models:
        if set(model) - allowed:
            raise ValueError("Unknown model config fields; credentials belong in environment variables")
        for key in ("name", "model", "base_url"):
            if not isinstance(model.get(key), str) or not model[key]:
                raise ValueError(f"Each model requires a nonempty {key}")
    names = [m["name"] for m in models]
    if len(names) != len(set(names)) or (args.models and set(args.models)-set(names)):
        raise ValueError("Unknown or duplicate config model names")
    if args.models:
        models = [m for m in models if m["name"] in args.models]
    if not models:
        raise ValueError("Select at least one model")
    prompts = {p: (HERE / "prompts" / (p + ".txt")).read_text() for p in args.prompts if re.fullmatch(r"[a-zA-Z0-9_-]+", p)}
    if set(prompts) != set(args.prompts):
        raise ValueError("Invalid prompt name")
    replay = {}
    if args.predictions:
        for line in args.predictions.read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            key = (row["model"], row["prompt"], row["case_id"], row["turn_id"], row.get("repeat", 1))
            if key in replay:
                raise ValueError(f"Duplicate replay entry: {key}")
            replay[key] = row
    elif not args.dry_run:
        for model in models:
            env = model.get("api_key_env")
            if env and not os.environ.get(env):
                raise ValueError(f"Missing {env}; use --dry-run or configure credentials outside the repository")
    args.output.mkdir(parents=True, exist_ok=False)
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    manifest = {"mode": args.mode, "dry_run": args.dry_run, "offline_replay": bool(args.predictions),
        "repeat": args.repeat, "dataset_sha256": digest(cases), "schema_sha256": digest(spec),
        "catalog_sha256": digest(menu), "prompt_sha256": {p: digest(t) for p,t in prompts.items()},
        "git_commit": commit, "runner_sha256": digest(Path(__file__).read_text()),
        "python_version": sys.version.split()[0],
        "models": [{k:v for k,v in m.items() if k != "api_key_env"} for m in models],
        "case_count": len(cases), "turn_count": sum(len(c["turns"]) for c in cases),
        "fixture_source": "synthetic; not observed cashier accuracy"}
    calls_per_suite = sum(len({t["segment_id"] for t in c["turns"] if t["kind"] == "final"}) for c in cases)
    manifest["planned_model_calls"] = calls_per_suite * len(models) * len(prompts) * args.repeat
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (args.output / "schema.json").write_text(json.dumps(spec, indent=2) + "\n")
    if args.dry_run:
        sample = cases[0]
        turn = next(t for t in sample["turns"] if t["kind"] == "final")
        requests = {m["name"]: {p: make_request(m, text, spec, menu, sample["initial_state"], [], turn)
                               for p,text in prompts.items()} for m in models}
        (args.output / "sample_requests.json").write_text(json.dumps(requests, indent=2) + "\n")
        print(f"Validated {len(cases)} synthetic conversations; no models called. {args.output}")
        return 0
    rows = []
    with (args.output / "results.jsonl").open("w") as stream:
        for model in models:
            for prompt_name, prompt in prompts.items():
                for repeat in range(1, args.repeat + 1):
                    for case in cases:
                        state = copy.deepcopy(case["initial_state"])
                        history, seen = [], set()
                        for index, turn in enumerate(case["turns"]):
                            if args.mode == "teacher-forced":
                                state = copy.deepcopy(case["initial_state"] if index == 0 else case["turns"][index-1]["expected"])
                            call = turn["kind"] == "final" and turn["segment_id"] not in seen
                            row = {"model": model["name"], "prompt": prompt_name, "case_id": case["id"],
                                   "tags": case.get("tags", []), "turn_id": turn["id"], "repeat": repeat,
                                   "called": call and not bool(args.predictions), "needs_model": call,
                                   "source": "replay" if args.predictions else "live",
                                   "latency_seconds": None, "usage": {}, "estimated_cost_usd": None}
                            prediction = copy.deepcopy(state)
                            try:
                                if call:
                                    if args.predictions:
                                        saved = replay[(model["name"], prompt_name, case["id"], turn["id"], repeat)]
                                        if saved.get("error"):
                                            raise RuntimeError(saved["error"])
                                        prediction = saved["prediction"]
                                    else:
                                        request = make_request(model, prompt, spec, menu, state, history, turn)
                                        prediction, details = call_model(model, request, args.timeout)
                                        row.update(details)
                                        row["estimated_cost_usd"] = estimated_cost(row["usage"], model)
                                row.update(score(turn["expected"], prediction, spec))
                                if not row["schema_errors"]:
                                    state = copy.deepcopy(prediction)
                            except (ValueError, RuntimeError, KeyError, IndexError, TypeError, OSError) as exc:
                                row.update({"exact": False, "schema_errors": [], "diffs": [],
                                            "invented_unknowns": None, "error": str(exc)})
                            if call:
                                seen.add(turn["segment_id"])
                                history.append({"id": turn["segment_id"], "text": turn["text"]})
                            row["prediction"] = prediction
                            rows.append(row)
                            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                            stream.flush()
    summary = write_reports(args.output, rows)
    print(json.dumps(summary, indent=2))
    print(f"Reports: {args.output}")
    return 0 if all(row["exact"] for row in rows) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError) as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        sys.exit(2)
