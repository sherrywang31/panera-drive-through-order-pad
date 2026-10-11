# Standing cashier app design rules

- Capture orders in batches: one held voice session may include several numbered cars. No per-car selection or Next car step is required for batch capture.
- Keep voice capture and the ordered batch readback in one workspace.
- Keep Android's native on-device speech recognition as the capture engine. Compare LLM text interpretation separately in `script/`; do not add paid speech recognition to the app.
- The next live-capture design should accumulate follow-up answers and corrections without a mandatory readback/confirmation gate. Preserve missing details when moving to another car. This goal is not yet implemented in the released batch workflow.
- Show clean, readable car summaries. Each car has one Edit button opening a dedicated section for that car only. This is the user's approved exception to minimizing navigation.
- In the car editor, expose bagel counts, slicing, toasting and spread counts/sizes directly. Minimize nested pickers; auto-save with Undo. Return to the prior batch scroll position in one action.
- Keep uncertainty and the original heard text. A skipped car is uncaptured, never implicitly no bagels. Never discard excess dozen bagels or infer selling eligibility from count.
- Prefer large reachable controls. Compare total capture/review/correction effort and final order accuracy in future iterations.
- Run unit tests, native Compose checks, lint, debug and optimized release builds before publishing an installable prototype. Phone microphone/noise testing is separate from automated verification.
