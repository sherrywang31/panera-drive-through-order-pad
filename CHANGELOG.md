# Changes

## 0.2.0

The entry screen now shows a growing, numbered list of every meal for the current car. Tap a listed meal to unfold its box and edit it; separate Default and You Pick Two meals stay intact. All four program boxes can fold. Default, You Pick Two and Bagel Tuesday start open, and Mix & Match starts folded.

Bagel Tuesday uses a compact flavor grid. The whole box adds one and the separate minus button removes one. Additions stop at 13 even during rapid taps; older saved quantities above 13 or in duplicate flavor rows remain available for readback and subtraction. Its summary entry opens the bagel box even when additions are disabled.

Pickers return to the originating meal and field while preserving entry/ticket scroll state. Required sides block Next car and Entered at register for You Pick Two and applicable Default foods. Hot coffee and tea offer 16 oz and 20 oz capture choices. Queue display numbers start at zero and renumber after cars are entered, while internal IDs and saved notes remain stable.

The app retains schema-1 storage and keeps the existing atomic saving and Undo behavior. No order notes are reset by this update. All 28 tests, lint and both APK builds passed locally; CI must pass before the public APK is published.
