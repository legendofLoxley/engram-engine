# Interpretation-stage eval

Measures how well a live model chooses the memory operations `Interpreter` asks it for
(record / resolve / correct / file / focus / attention / link / hold), using the prompt and tool
schema **exactly as the Kotlin code ships them**.

```bash
# 1. dump the shipped system prompt, tool schema and rendered fixture snapshots
INTERPRETER_PROMPT_DUMP=/tmp/shipped.json ./gradlew test --rerun --tests '*InterpreterPromptDumpTest'

# 2. run the turns in cases.py against the local llama.cpp server
python3 deploy/eval/interpreter/eval.py --shipped /tmp/shipped.json
python3 deploy/eval/interpreter/eval.py --shipped /tmp/shipped.json --sets set3 --verbose
```

Standard library only. `--url` points it at any OpenAI-compatible `/v1/chat/completions` endpoint.

## Reading the numbers

- **raw** is what the model chose; **guarded** also applies an emulation of the one code-side check that
  changes outcomes, `OperationParser` refusing a `resolve_item` whose clause is negated or a question.
  The Kotlin parser is the real check; this only shows what it would have caught.
- **hygiene** counts ungrounded quotes and invented refs. The parser rejects both, so they cost nothing
  at runtime; the count shows how often the model tried.
- The three sets are not equally trustworthy. `set1` was tuned against, `set2` partly, and `set3`'s
  *first* run (before any tuning on it) was the fairest measure. Add a new set for a fresh measure
  rather than tuning against the old ones.

## What was measured (Qwen3.6-35B-A3B via llama.cpp, temperature 0)

| | first run, before tuning on it | final shipped prompt |
|---|---|---|
| set1 | (developed on) | 39/41 |
| set2 | 31/33 | 31/33 |
| set3 | 28/32 | 32/33 (after tuning on it, so not independent) |

Total 102/107 (95%). Median latency about 1.3 s, p90 about 1.6 s, roughly 3.5k prompt tokens. With
`--interleave` (an unrelated chat call before each case, as the live pipeline makes) the score is the same
but the median is about 2 s, because the prompt cache is cold. Expect roughly 88-95% on turns the prompt
was not tuned against.

**Known limitation: identical input can give different answers live.** In the harness, repeated runs of
the same prompt return identical results. In the running pipeline, the same five turns at temperature 0
produced different operations on two attempts (a stressed message was held once and missed once). It was
not reproducible here, even with interleaved calls, so treat live accuracy as about 90% with run-to-run
variation, and rely on the code-side guards and on the reply reporting what changed.

Things worth knowing when changing the prompt:

- A flat tool schema (every field optional but `verb` and `quote`) let the model emit a record with no
  `kind`, or spray unrelated fields onto a correction. One strict variant per verb fixed that.
- Prose rules were a weak lever for date arithmetic ("the 24th" came back as October's, even with an
  explicit rule). Tagging each calendar row with its day number turned it into a lookup and fixed it.
- The score moves by about 3 cases with almost any wording edit, and adding or removing an example
  (especially one whose answer is `[]`) can flip simple cases to `[]`. Change one thing at a time, and
  don't read a 1-3 case difference as a real improvement.
- Asking for a `title` was optional and often ignored on corrections; making it required on
  `correct_item` (where the quote is the worst possible label) is what made it reliable. It cost about
  17 ms median.
