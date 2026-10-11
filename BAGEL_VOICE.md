# Bagel batch voice prototype (0.4.0)

## Phone workflow

The app now opens a bagels-only batch workspace with an empty queue. Hold the large microphone button once and dictate multiple numbered cars. Release to organize the finalized transcript. Review the proposed summaries and tap **Use whole batch** once. The queue stays in car-number order. Each car has a clean summary and one **Edit** button into that car's dedicated editor. Counts, prep, spread size and flavor can be corrected directly. Changes save automatically; Undo restores the most recent order transaction. Back to batch returns to the prior scroll position. No per-car capture selector or Next car action is required.

Examples:

- `Car 1, 8 cinnamon swirl and raisin sliced toasted, 5 chocolate chip, 3 asiago, plain cream cheese and honey walnut. Car 3, 13 sesame, tub of plain and honey walnut. Car 4 no bagels.`
- `Car 5 baker's dozen, 13 sesame unsliced double toasted, 1 tub of plain cream cheese and 1 tub of honey walnut.`
- `Car 1 make chocolate chip four.` sets the uniquely identified line's quantity to 4.
- `Car 1 add three chocolate chip.` adds 3 to the uniquely identified existing line.
- `Car 1 remove chocolate chip.` removes the uniquely identified line.

Missing prep/counts/sizes are explicitly unspecified. Sixteen bagels remain sixteen. A count of 13 does not automatically establish a dozen. Explicit `baker's dozen` selects a group that is reviewed against 13 bagels and 2 spread selections, following the requested prototype rule. This selling rule and available flavors need café confirmation. Skipping Car 2 leaves it uncaptured; **Skipped cars: no bagels** confirms all uncaptured cars in one action. Entered-at-register with unresolved details requires explicit acknowledgment. Display numbers do not renumber after handoff.

## Speech and interpretation

Real microphone capture uses Android's on-device SpeechRecognizer (Android 12/API 31+ with an installed English recognition service/model). Older or unsupported phones still support typed batch entry and the complete touch editor; there is no cloud fallback, internet permission, API key or ongoing API charge. Model installation depends on the phone's recognizer/settings.

Android recognition is utterance-oriented. While held, final utterances are accumulated and recognition restarts after a short pause; this is not guaranteed gapless streaming. The display shows interim words; interim callbacks never create order items. Release requests a final result instead of canceling it. Each recognizer instance accepts one terminal callback and old generations are ignored. A final-result timeout, recognition error, backgrounding or rotation preserves the available words and marks the draft interrupted. The cashier must review the last phrase for incompleteness.

The first implementation uses a bounded, local bagel interpreter rather than a paid language model. It resolves the bundled flavors, spoken numbers zero through twenty, quantities up to 999, three toast states, sliced/unsliced, individual/dozen labels, spread sizes and the clear correction commands above. Unsupported words and ambiguous correction targets are preserved as unresolved text alongside the heard transcript, not silently discarded. Mixed multiple-dozen speech requires review and assignment into separate groups in the editor. This is a supported vocabulary prototype, not arbitrary natural-language order understanding.

Android native on-device speech recognition is the chosen capture engine. The standalone [`script/`](script/README.md) harness compares LLM text interpretation on finalized transcripts; no LLM is configured in this APK. No API credential or backend was supplied. Keep the `OfflineBagelSpeech → BagelViewModel → BagelParser → BagelChanges → BagelRepository` separation if adding interpretation providers. Supply cloud credentials through a backend or short-lived authorization, never in a distributed APK. Benchmark interpretation accuracy, latency and cashier effort before replacing the bounded parser. Speech recognition quality still needs testing on the actual phone.

## Persistence and failure recovery

`bagel-batches-v1.json` is a new application-scoped typed DataStore document, separate from the old prototype's `orders-v1.json`. Existing general-menu notes are preserved on disk and are not imported, changed, or displayed in the bagel-only queue. The new document stores stable IDs, order revision, next car number, orders, heard draft and interrupted state. Draft saving does not change the order revision. A batch captured against stale orders cannot overwrite a later manual edit without re-organizing and reviewing. Applying a batch replaces explicitly named existing cars only, with an upfront warning; other cars remain. Entered cars cannot be overwritten. The queue is capped at 1000 numbered cars for this experiment; entered cars remain to prevent accidental number reuse.

Every successful order change waits for an atomic write before reporting success. Storage failure leaves the previous committed state and blocks further writes until Retry. Corrupt/unsupported documents are not reset. Draft source survives parsing failures and interrupted capture. The latest order Undo is in memory only and intentionally unavailable after a full restart. Android backup/transfer exclusions continue to cover local notes. Uninstalling still removes all local notes.

## Feasibility trial

On the cashier's actual phone, first confirm offline English recognition in airplane mode after model setup. Then compare touch vs voice on 30–50 counterbalanced representative bagel batches, measuring total capture, review and correction time, touches, re-dictations and complete final order accuracy. Include café noise, long pauses, three-car batches, skipped cars, repeated flavors with different prep, dozen overflow, spreads, explicit corrections, rotation/backgrounding and copying to the register. Record speech errors separately from interpretation errors. Automated checks do not establish microphone quality or phone latency.
