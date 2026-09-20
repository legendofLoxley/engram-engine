#!/usr/bin/env python3
"""Measure a live model against the interpretation prompt exactly as it ships.

    INTERPRETER_PROMPT_DUMP=/tmp/shipped.json ./gradlew test --rerun --tests '*InterpreterPromptDumpTest'
    python3 deploy/eval/interpreter/eval.py --shipped /tmp/shipped.json

Sends each case in cases.py to an OpenAI-compatible endpoint (default: the local llama.cpp server) with
the dumped system prompt and tool schema, the tool forced and temperature 0 — the same request the
Interpreter sends — and scores the operations it returns.

Two numbers per set: "raw" is what the model chose; "guarded" also applies an emulation of the one
code-side check that changes outcomes (OperationParser refusing a resolve whose clause is negated or a
question). The Kotlin parser is the real thing; this only shows what it would have caught.
Ungrounded quotes and invented refs are reported separately as "hygiene": the parser rejects those too.
"""
import argparse, json, re, statistics, sys, time, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import cases as corpus

NEGATION = re.compile(r"\b(not|never|no longer|isn't|wasn't|don't|doesn't|didn't|won't|can't|cannot|haven't|hasn't|hadn't|without)\b|n't\b", re.I)
CLAUSE = re.compile(r"(?<=[.!?;])\s+|\s+(?:but|however|although|though|yet|while|whereas)\s+", re.I)


def norm(text):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9\s]", "", text.lower())).strip()


def call_model(url, shipped, snapshot_text, utterance):
    body = {
        "model": "local", "max_tokens": 768, "temperature": 0.0,
        "messages": [{"role": "system", "content": shipped["system"]},
                     {"role": "user", "content": f"{snapshot_text}\n\nUser message:\n{utterance}"}],
        "tools": shipped["tools"],
        "tool_choice": {"type": "function", "function": {"name": "apply_operations"}},
        "chat_template_kwargs": {"enable_thinking": False},
    }
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=180) as response:
        out = json.loads(response.read())
    elapsed_ms = (time.time() - started) * 1000
    message = out["choices"][0]["message"]
    calls = []
    for tool_call in message.get("tool_calls") or []:
        try:
            args = json.loads(tool_call["function"]["arguments"])
        except Exception:
            calls.append({"name": "?", "args": {"__malformed__": True}})
            continue
        for op in args.get("ops") or []:
            if isinstance(op, dict):
                op = dict(op)
                calls.append({"name": op.pop("verb", "?"), "args": op})
    return calls, elapsed_ms


def guard(calls, utterance):
    """Emulates OperationParser refusing a resolve whose clause is negated or a question."""
    kept = []
    for call in calls:
        if call["name"] == "resolve_item":
            quote = norm(call["args"].get("quote", ""))
            clauses = [c for c in CLAUSE.split(utterance) if c and c.strip()]
            clause = next((c for c in clauses if quote and quote in norm(c)), utterance)
            if NEGATION.search(clause) or clause.strip().endswith("?"):
                continue
        kept.append(call)
    return kept


def hygiene(calls, snapshot_text, utterance):
    refs = set(re.findall(r"\b([ci]\d+)\b", snapshot_text))
    problems = []
    for call in calls:
        args = call["args"]
        quote = args.get("quote")
        if isinstance(quote, str) and norm(quote) and norm(quote) not in norm(utterance):
            problems.append(f"{call['name']}: ungrounded quote {quote!r}")
        for key in ("item", "target", "into", "other_item", "project", "parent"):
            value = args.get(key)
            if isinstance(value, str) and re.fullmatch(r"[ci]\d+", value) and value not in refs:
                problems.append(f"{call['name']}: invented ref {value}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--shipped", required=True, help="JSON written by InterpreterPromptDumpTest")
    parser.add_argument("--url", default="http://127.0.0.1:8081/v1/chat/completions")
    parser.add_argument("--sets", default="set1,set2,set3")
    parser.add_argument("--only", default="", help="substring of a case id")
    parser.add_argument("--verbose", action="store_true", help="print passing cases too")
    ns = parser.parse_args()

    shipped = json.loads(Path(ns.shipped).read_text())
    placeholders = {"@empty": shipped["snapshots"]["empty"], "@focus": shipped["snapshots"]["focus"], "@nofocus": shipped["snapshots"]["nofocus"]}

    grand = dict(raw=0, guarded=0, n=0, hygiene=0)
    latencies = []
    for set_name in ns.sets.split(","):
        raw_ok = guarded_ok = n = hyg = 0
        print(f"\n== {set_name} ==")
        for case in corpus.SETS[set_name]:
            if ns.only and ns.only not in case["id"]:
                continue
            snapshot_text = placeholders.get(case["snapshot"], case["snapshot"])
            try:
                calls, ms = call_model(ns.url, shipped, snapshot_text, case["utterance"])
            except Exception as error:
                calls, ms = [], 0
                print(f"ERROR {case['id']}: {error}")
            latencies.append(ms)
            raw = bool(case["check"](calls))
            guarded_calls = guard(calls, case["utterance"])
            guarded = bool(case["check"](guarded_calls))
            problems = hygiene(calls, snapshot_text, case["utterance"])
            n += 1; raw_ok += raw; guarded_ok += guarded; hyg += bool(problems)
            if ns.verbose or not guarded:
                shown = json.dumps([{"n": c["name"], **{k: v for k, v in c["args"].items() if k != "quote"}} for c in calls])[:220]
                print(f"{'PASS' if guarded else 'FAIL'} {case['id']:<26} {ms:6.0f}ms {shown}" + (f"  HYGIENE:{problems}" if problems else ""))
                if not guarded:
                    print(f"     wanted: {case['why']}\n     said:   {case['utterance']}")
        print(f"{set_name}: raw {raw_ok}/{n}, guarded {guarded_ok}/{n}, {hyg} with hygiene problems")
        for key, value in (("raw", raw_ok), ("guarded", guarded_ok), ("n", n), ("hygiene", hyg)):
            grand[key] += value

    if latencies:
        ordered = sorted(latencies)
        print(f"\nTOTAL: raw {grand['raw']}/{grand['n']}, guarded {grand['guarded']}/{grand['n']}, {grand['hygiene']} with hygiene problems; "
              f"latency median {statistics.median(latencies):.0f}ms, p90 {ordered[int(len(ordered) * 0.9) - 1]:.0f}ms, max {max(latencies):.0f}ms")


if __name__ == "__main__":
    main()
