# Transcript-to-order evaluation

Android native on-device speech recognition remains the capture engine. This
folder evaluates **text interpretation only**. It does not change the Android
app, add networking to the APK, or connect a live LLM to the cashier UI.

Python 3.10+; standard library only. Run commands from the repository root.

## Start without an API key

```sh
python script/order_eval.py --dry-run
python -m unittest discover -s script -p 'test_*.py' -v
```

Dry-run validates all reference snapshots and exports the schema, request
examples and a reproducibility manifest. It makes no calls and produces no
accuracy scores. The offline tests use reference replay and fake responses to
verify the harness, **not to establish model accuracy**.

## Compare models and prompts

Set `OPENAI_API_KEY` in your shell/environment outside the repository. Never put
credentials into the APK, command-line arguments, model config or test cases.
Then:

```sh
python script/order_eval.py \
  --models gpt-4o-mini gpt-5.4-mini \
  --prompts baseline contextual \
  --repeat 3
```

This is a paid live run: the default suite has 24 conversations, 42 checkpoints,
and 38 unique finalized segments. The example matrix above makes 456 requests.
`--dry-run` writes the exact planned call count into `manifest.json` before a run.
There are no automatic retries or hidden model escalation. HTTP errors, refusal,
truncated output, invalid JSON and invalid order structure count as failures.

The two prompt files are intentionally comparable: `baseline.txt` contains
minimal extraction guidance; `contextual.txt` explicitly handles corrections,
questions, missing fields, independent dozens and car transitions. Add a new
`script/prompts/name.txt` and select it with `--prompts name`.

For other models, copy `models.example.json` to the ignored `models.local.json`:

```sh
python script/order_eval.py --config script/models.local.json --models my-model
```

The initial adapter supports **OpenAI-compatible Chat Completions** endpoints
with strict `json_schema` response formatting. Not every provider implements
this interface; add an adapter rather than assuming compatibility. An OpenAI-
compatible local server can use a localhost HTTP base URL and omit `api_key_env`.
Remote endpoints require HTTPS; redirects are rejected to protect credentials.
Per-model `request_options` can select reasoning effort, token limits or other
provider options without replacing the prompt/schema. Pin model snapshots in
config when conducting a reproducible comparison.

Prices in the example config were checked against official model pages on
2026-10-11; update them before comparing costs. Remove prices if unknown. Cost
estimates use returned prompt, cached-input and completion token counts, not
character counts. Reported completion usage includes reasoning tokens where the
provider reports them. Failed calls with missing usage make the total unknown.
Latency is request wall time; it excludes Android recognition, audio capture,
and screen updates. No paid speech recognition is part of this harness.

## Conversations and ground truth

`cases/bagel_conversations.json` contains **synthetic, manually specified**
reference states after every turn. Tags cover:

- Natural follow-ups and tentative questions; missing information remains unknown.
- Add versus set versus remove, repeated totals and flavor replacement.
- Negation, toast scope, repeated flavors with different preparation.
- Pauses, partial transcripts, duplicate terminal callbacks and resume.
- Car boundaries, skipped cars and corrections to earlier cars.
- Individual versus dozen packaging, excess counts and independent dozens.
- Cream-cheese flavors, quantities and sizes; unsupported requests.

Replace or extend these with de-identified finalized transcripts from the
cashier's actual Android phone. Preserve actual recognition errors rather than
quietly fixing them. The reference should describe the customer's intended
order where it is supported by captured evidence; never ask a text parser to
recover an unheard answer. Cases with insufficient evidence should expect
UNKNOWN/null and/or `needsClarification=true`. Human-verify labels, especially
question-versus-answer distinctions that may lack punctuation in native output.
Keep development and held-out cases separate using different `--cases` files.

Each case has `id`, `tags`, `initial_state` and `turns`. Each turn has a unique
`id`, a `kind` (`final`, `partial`, `pause`, `resume`), a `segment_id`, `text`, and
the full `expected` snapshot. A repeated finalized `segment_id` is a duplicate
callback, not a new spoken repetition. Partial/pause/resume events do not call
the model or change the order. Those orchestration checkpoints are scored
separately from model-required turns in the summary.

Default **rollout** supplies the model's own last valid prediction to the next
turn, along with finalized conversation history. Earlier mistakes can therefore
propagate or be corrected. The expected state is never sent to the model.
`--mode teacher-forced` supplies the correct previous state instead, for isolated
turn debugging; do not compare its scores directly to rollout. All history is
kept within each short fixture, never across conversations or test cases.

The semantic snapshot follows the current `BagelModels.kt` fields and enum
values: car number/status, independent groups, packaging, bagel flavor/count/
slice/toast, and spread flavor/count/size. The vocabulary is read from Kotlin's
`BagelCatalog.fallback` and spread list, so these tests do not maintain a second
menu. The café's actual roster still needs verification. Snapshot serialization
omits random IDs, original heard text and persistence metadata. It adds
`activeCarNumber` for live conversation routing and `needsClarification` for
unresolved intent, avoiding an exact-text grade on free-form notes. Ordinary
missing fields do not alone set that flag. Production integration would map it
to actual unresolved notes and must preserve IDs/revisions/heard text.

This evaluates resulting states, not an LLM action/patch protocol. When adding
production patch parsing, retain these end-state checks and add transition,
stable-ID, revision and delayed-response tests. The current Android bounded
parser is not evaluated by this Python runner; it has its own Kotlin tests.

## Scores and artifacts

Each run creates an ignored `script/results/<timestamp>/` directory containing:

- `manifest.json`: dataset, prompt, schema and catalog hashes, model settings,
  source commit, rollout mode, repeats and planned calls. No credential values.
- `results.jsonl`: every checkpoint, prediction, structured difference, errors,
  model-reported usage and request latency; flushed after every checkpoint.
- `summary.json`, `summary.csv`, `report.md`: comparison and failed cases.
- `schema.json`: the exact constrained output contract used in the run.

**All-turns-correct conversation rate** is the strict main metric: every
checkpoint must be correct, not merely the final order after several repairs.
Also compare model-turn exact rate, all-checkpoint exact rate, final-state exact
rate, invalid/error counts, unknown fields filled without evidence, and latency.
Exactness includes all cars, independent groups, line multiplicity, quantities,
prep, spread details and clarification flags. List display order is ignored;
duplicates are not. Schema validation accepts null/UNKNOWN, preserves overfull
dozens and rejects unsupported enums and malformed/non-order groups. Missing
detail and eligibility rules are not treated as syntactic validation errors.
Diff paths and invented-unknown counts are diagnostics on canonically sorted
states; if lines/groups are missing or changed, inspect the full snapshots
rather than treating those counts as perfectly aligned field error rates.

Errors remain in the denominator. Run repeats to expose variation. Small,
synthetic suites cannot establish a production accuracy guarantee. Especially
track **silent wrong orders** versus explicit unresolved requests on a held-out
set of actual phone transcripts. Measure live speech and cashier effort
separately before adding the LLM to the APK.

## Re-score saved outputs offline

```sh
python script/order_eval.py \
  --models gpt-4o-mini --prompts contextual \
  --predictions path/to/results.jsonl
```

Replay rows must identify `model`, `prompt`, `case_id`, `turn_id`, `repeat` and
`prediction`. Missing rows on model-required turns fail; duplicate keys fail
configuration validation. Select the same case set, model/prompt names and
repeat count as the source run. Replay does not make requests or report live
latency/cost. Output folders must be new. Exit codes: `0` all checkpoints exact
(or successful dry-run), `1` evaluation failures, `2` invalid configuration.
