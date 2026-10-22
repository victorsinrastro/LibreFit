# GZCLP product design audit

**Review date:** 21 October 2026
**Build and device:** `:app:assembleDebug` succeeded; debug APK installed on a headless Pixel 9 Android emulator, 1080 × 2424.
**Scope:** Design diagnosis and redesign specification. No production code was changed for this review. The emulator contains an illustrative program entered solely to inspect the experience; its weights are not training advice.

## 1. Executive verdict

**This is a capable GZCLP engine inside a general workout app, not yet a coherent GZCLP product.** The rules and persistence relieve the user of substantial programming work. The interface gives that benefit back as navigation, scrolling, repeated form fields, and visible configuration. The daily path still asks the user to choose between GZCLP, an empty workout, and routines. Once training starts, every set has two large input fields and optional controls even when no editing is needed. After generating warm-ups, the first working set can leave the viewport and appears as “Set 5.”

The most severe findings are concrete: the GZCLP header overlaps the Android status bar on the emulator; generating warm-ups again deletes existing warm-up rows, even completed ones; and historical corrections are marked but do not recompute subsequent progression. The latter behavior is disclosed in the UI, but it leaves a trust gap when a corrected workout is supposed to explain today's recommendation.

The answer to “would we be proud of every interaction?” is **no**. The next iteration should make the current exercise and next safe action obvious, preserve recorded work under every tool action, and keep advanced setup away from the daily path.

## 2. Product philosophy

**Promise:** Open the app, see the correct next GZCLP session, lift, log, and leave knowing what changed. The app chooses rotation, targets, rest defaults, and progression. The user chooses actual performance, equipment substitution, overrides, and deliberate resets.

**Three-second test:** The first screen must answer “What workout is next or in progress?”, “What is its first lift and weight?”, and “Where do I tap?” No generic workout action should compete with that answer for a configured GZCLP user.

**Single most important experience:** Completing an actual working set accurately while tired. A successful one-tap path is already present; the surrounding presentation makes it harder to find and verify. The app should expose the normal path first, then editing, warm-ups, plates, substitutions, and notes in context.

**What is worth keeping:** Continuous A1 → B1 → A2 → B2 rotation, tier-specific independent progression, persisted active sessions, explicit actual versus recommended weights, deadline-based rest, and offline data export. These directly support the promise. The value is in their correctness, not in placing every control on the main screen.

## 3. Method, architecture, and evidence boundary

I inspected navigation, Compose screens, theme, GZCLP ViewModel/repository/entities, backup, timer integration, tests, and the debug build. I launched the APK, configured a new GZCLP program through the UI, entered ten illustrative starting loads, started A1, opened the plate calculator, completed a set, observed automatic rest and PR feedback, generated warm-ups, expanded a substitution, and force-stopped/relaunched the app. The active session and completed set were restored on Home. Relevant captured screens are in [the screenshot directory](product-design-audit/screenshots/). Screenshot names are listed below.

The app uses Compose Navigation and a `MainScreen` shell with Library, Home, and Profile. `GzclpScreen` is a separate route containing its own Today, History, Progress, and Settings chips, plus onboarding, workout, and summary branches ([NavigationHost.kt](../app/src/main/java/org/librefit/nav/NavigationHost.kt), [GzclpScreen.kt](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt)). Room entities/DAO/repository are the workout source of truth; the ViewModel exposes Flows and serializes session edits ([GzclpViewModel.kt](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpViewModel.kt), [GzclpRepository.kt](../app/src/main/java/org/librefit/db/repository/GzclpRepository.kt)). Theme uses Material 3 expressive and Roboto Flex ([Theme.kt](../app/src/main/java/org/librefit/ui/theme/Theme.kt), [Type.kt](../app/src/main/java/org/librefit/ui/theme/Type.kt)).

**Runtime coverage:** GZCLP setup, Today, empty History, empty Progress, Settings top/middle, active workout, plate sheet, generated warm-ups, substitution expansion, and resume Home were visually inspected. Completion summary, populated History/Progress, reset dialogs, restore preview, notification shade, dark mode, large-font layout, landscape, and all legacy LibreFit destinations were **not** visually inspected. Their comments below are code-based and scored provisionally where applicable. No screenshot is claimed for those states. There was no Maestro executable or flow in this environment; ADB input, UI hierarchy dumps, and screenshots were used. A force-stop/relaunch shows persistence, not a proof of device-reboot timer recovery. No destructive backup restore was attempted.

## 4. Screen inventory and scorecard

Scores are 1–10, where 10 means an exceptionally clear, efficient, refined, consistent, accessible experience. `C / E / V / K / A / O` = clarity / interaction efficiency / visual refinement / consistency / accessibility / overall. **Observed** means emulator screenshot or interaction; **code** means implementation inspection only. Scores for code-only states are provisional.

| Screen or significant state | Evidence | C | E | V | K | A | O | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| LibreFit Home, unconfigured | [01](product-design-audit/screenshots/01-launch.png), [HomeScreen.kt](../app/src/main/java/org/librefit/ui/screens/home/HomeScreen.kt) | 5 | 5 | 6 | 4 | 6 | 5 | GZCLP setup competes with empty workout and routine creation. |
| GZCLP onboarding | [02](product-design-audit/screenshots/02-onboarding-top.png), [04](product-design-audit/screenshots/04-onboarding-bottom.png) | 5 | 3 | 5 | 5 | 4 | 4 | Ten required weights and ten increment fields before first use; title collides with status icons. |
| GZCLP Today | [06](product-design-audit/screenshots/06-today.png) | 7 | 6 | 6 | 6 | 5 | 6 | Correct next workout, but action follows three large cards and explanatory copy. |
| Active workout, unmodified | [13](product-design-audit/screenshots/13-active-top.png) | 6 | 5 | 5 | 5 | 5 | 5 | One-tap Done exists; fields, cards, and all sets create a long scroll. |
| Active workout, rest and PR | [15](product-design-audit/screenshots/15-set-complete-timer.png) | 5 | 5 | 5 | 5 | 5 | 5 | Timer starts correctly; completed button becomes an unexplained checkmark, and three PR claims occupy two lines after the first-ever set. |
| Warm-ups generated | [17](product-design-audit/screenshots/17-warmups.png) | 3 | 3 | 4 | 5 | 4 | 3 | Working set is relabeled Set 5; warm-up edit/reorder controls dominate and push the task down. |
| Plate calculator sheet | [14](product-design-audit/screenshots/14-plate-sheet.png) | 8 | 8 | 7 | 7 | 7 | 7 | Correct, plain “1 × 20 kg each side” for 60 kg on a 20 kg bar; spare visual space is acceptable. |
| Substitution expansion | [18](product-design-audit/screenshots/18-substitution.png) | 6 | 5 | 5 | 5 | 5 | 5 | Explicit isolation of original lift is good; full inline form lengthens an already long exercise card. |
| Empty History | [07](product-design-audit/screenshots/07-history-empty.png) | 6 | 6 | 5 | 6 | 5 | 5 | Honest empty state, but the screen contributes little and header overlap remains. |
| Empty Progress | [08](product-design-audit/screenshots/08-progress-empty.png) | 4 | 4 | 5 | 5 | 5 | 4 | Says zero workouts while filling the screen with ten “starting weight” cards; no insight yet. |
| Program Settings | [10](product-design-audit/screenshots/10-settings-middle.png), [11](product-design-audit/screenshots/11-settings-bottom.png) | 3 | 2 | 4 | 4 | 4 | 3 | Raw `grams:count` and `percent:reps` syntax and many progression rows expose storage concepts. |
| Resume after force-stop | [19](product-design-audit/screenshots/19-home-resume.png), [20](product-design-audit/screenshots/20-resumed-session.png) | 7 | 8 | 6 | 5 | 6 | 7 | Resume is prominent and session survived; competing generic CTA remains. |
| Completion summary | [GzclpScreen.kt §SessionSummary](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt) **code only** | 7 | 6 | 5 | 6 | 5 | 6 | Actuals, volume, duration, reasons and next loads exist; density and visual hierarchy need runtime validation. |
| Populated History/session correction | same file **code only** | 6 | 5 | 5 | 6 | 5 | 5 | Inspection and correction exist, but correction does not replay later progression. |
| Populated Progress | same file **code only** | 5 | 4 | 4 | 5 | 5 | 5 | Tiny six-point Canvas trends, long per-lift list, and calculations inside composable. |
| Reset and restore dialogs | same file **code only** | 6 | 6 | 5 | 6 | 6 | 6 | Explicit confirmation is appropriate; exact content and accessibility need runtime inspection. |
| Generic Library/Profile/legacy workout and settings | [Route.kt](../app/src/main/java/org/librefit/nav/Route.kt) **code inventory only** | — | — | — | — | — | — | Reachable in the LibreFit shell; deliberately not assigned invented visual scores. |

### Screen-specific redesign notes

**Home.** Purpose: route to training. Primary action: start/resume GZCLP. Working: full next lift list and resume from persisted session. Broken: “Start empty workout,” “Your routines,” and “Create routine” create a second product decision and can outrank GZCLP visually ([01](product-design-audit/screenshots/01-launch.png), [19](product-design-audit/screenshots/19-home-resume.png)). Remove these from the configured GZCLP default Home; keep them in Library or an explicit “Other workout” menu. Consolidate Home and GZCLP Today into one destination. Redesign hierarchy to **Resume/Start → first lift and load → remaining tiers → small History/Progress entry**. Benefit: first action is unambiguous and one navigation step disappears.

**Onboarding.** Purpose: establish units, frequency, and honest starting loads. Primary action: create program. Working: no fabricated user strength and sensible increments. Broken: ten required values and equal visual prominence for every increment; no apparent draft recovery; the top bar overlaps Android system UI ([02](product-design-audit/screenshots/02-onboarding-top.png), [04](product-design-audit/screenshots/04-onboarding-bottom.png)). Remove all increment fields from the first pass; put defaults behind “Adjust increments.” Consolidate repeated lift names in a staged form: four T1 loads, then three unique T2/T3 groups with explicit independence. Save a draft after each value. Benefit: less typing anxiety and clear progress without pretending a load is known.

**Today.** Purpose: answer what is next. Primary action: Start A1. Working: correct rotation and prescriptions. Broken: four top-level chips use the same visual weight as the task, three large cards delay the CTA, and “training week” can imply calendar binding even though rotation is session-based ([06](product-design-audit/screenshots/06-today.png)). Remove the explanatory footer from the daily view; consolidate exercise cards into compact rows; place Start near the top and a secondary full plan beneath it. Benefit: fast scanning and a truthful mental model.

**Active workout.** Purpose: record the next real set. Primary action: complete/edit that set. Working: target prefill, one-tap Done, AMRAP label, automatic rest, inline notes/RIR, set persistence. Broken: all exercises expanded; each set consumes a tall weight field, reps field, RIR control, and completion button; timer is above the scrollable exercise list and can disappear while scrolling; Finish is at the very bottom ([13](product-design-audit/screenshots/13-active-top.png), [15](product-design-audit/screenshots/15-set-complete-timer.png), [16](product-design-audit/screenshots/16-active-lower.png)). Remove always-visible optional controls and repeated labels from normal sets. Consolidate completed sets to a compact read-only line with a clear Undo action. Redesign around a pinned current-exercise header and a persistent next-set action; expand editing only on tap. Benefit: far less scrolling and fewer targets to decode while fatigued.

**Warm-ups.** Purpose: prepare for a working load. Primary action: log the next warm-up or return to the first working set. Working: generated feasible loads and separation in the data model. Broken: generated rows are inserted before working rows; `forEachIndexed` labels the first working row “Set 5”; each warm-up shows Remove/Move up/Move down controls ([17](product-design-audit/screenshots/17-warmups.png), [GzclpScreen.kt §ActiveWorkout](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt)). Reopen “Warm up” must never delete completed rows: the repository currently deletes every existing warm-up before regenerating ([GzclpRepository.kt §generateWarmups](../app/src/main/java/org/librefit/db/repository/GzclpRepository.kt)). Use `W1–Wn` and `Working 1–N`; collapse completed warm-ups; keep edit/reorder in a contextual menu. Benefit: preserves trust and returns attention to the actual lift.

**Plate sheet.** Purpose: load a bar safely. Primary action: read per-side plates. Working: the observed 60 kg calculation is correct and immediately comprehensible ([14](product-design-audit/screenshots/14-plate-sheet.png)). Keep the compact sheet. Add a clear unavailable-exact-load line only when needed; expose profile changes from the sheet, not raw inventory syntax on a primary settings page. Benefit: direct gym utility without another destination.

**Substitution.** Purpose: continue training when equipment is unavailable. Primary action: choose a session-only substitute. Working: it clearly says original progression stays unchanged. Broken: suggestions, free-text field, and Use once appear inside the long set card ([18](product-design-audit/screenshots/18-substitution.png)). Put it in a bottom sheet with suggested alternatives, equipment, and consequence; require deliberate confirmation for T1/T2. Keep undo near the substituted heading. Benefit: the exception is explicit without crowding normal sessions.

**History and session detail.** Purpose: verify what happened and correct mistakes. Primary action: open a session. Working: chronological list and actual sets/notes exist in code. Broken: the empty page is mostly unused space; historical correction is flagged rather than reconciled with subsequent program state ([07](product-design-audit/screenshots/07-history-empty.png), [GzclpRepository.kt §saveSet](../app/src/main/java/org/librefit/db/repository/GzclpRepository.kt)). Empty state should give one useful route back to Today. A correction must show whether it changes present recommendations, offer deterministic replay with conflict review, and never silently mutate later lifts. Benefit: history becomes trustworthy, not merely editable.

**Progress.** Purpose: answer whether the four primary lifts are improving. Primary action: select a lift/tier and inspect a trend. Working: no fabricated history; separate tier states. Broken: new users see ten starting-load cards despite a zero-workout message; populated trends are 64 dp high in code and buried in a long list ([08](product-design-audit/screenshots/08-progress-empty.png), [GzclpScreen.kt §Progress](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt)). Empty state should show how to create the first point. Populated state should lead with four primary lifts, selectable T1/T2 and a readable dated chart; deeper volume/AMRAP/reset detail appears below. Benefit: an answer rather than a dashboard of available metrics.

**Program Settings.** Purpose: change exceptional configuration. Primary action: find one setting. Working: broad customizability and backup access. Broken: data export, frequency, units, timer, raw plate/warm-up serialization, ten progression records, and templates occupy one long scroll; “Restart this lift” repeats prominently ([10](product-design-audit/screenshots/10-settings-middle.png), [11](product-design-audit/screenshots/11-settings-bottom.png)). Replace raw strings with structured plate rows and warm-up steps. Organize into Program, Equipment, Timing, and Data; show per-lift overrides from lift detail. Put destructive reset behind a confirmation with its exact effect. Benefit: advanced capability without exposing implementation storage or accidental progression edits.

**System states.** The GZCLP top `Row` sits under system icons in every observed GZCLP screen, so tap targets and text can collide with status UI ([02](product-design-audit/screenshots/02-onboarding-top.png), [06](product-design-audit/screenshots/06-today.png), [GzclpScreen.kt:50](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt#L50)). Fix system insets before visual polish. Loading uses a centered spinner; errors are a bare red line at the top in code. Provide a retry action and contextual error placement. Summary and dialogs require a second runtime pass after implementation.

## 5. Connected journeys and measured friction

Counts below are from the observed Pixel 9 path unless marked **code-only**. “At least” avoids inventing taps for keyboard dismissal or scroll. Timing was not benchmarked. All weights entered were illustrative.

| Journey/task | Observed current path | Proposed path | Friction removed |
|---|---|---|---|
| A: first session setup | Home → GZCLP (1 tap); **10 required weight entries**, at least 10 field-focus taps, two large vertical swipes to reach Start, then Start (1 tap). Ten increment fields are also visible but optional. | Home → short setup with unit/frequency and T1 loads; remaining distinct tier loads on progressive steps, defaults hidden; draft saved. | Reduce visible decisions and recover interruption. Do **not** invent weights. |
| B: start from fresh launch | Home GZCLP card (1 tap) → Today Start A1 (1 tap). | Configured Home Start A1 (1 tap). | One route and duplicated screen eliminated. |
| C: first ordinary working set | Active screen has prefills; tap Done once after reaching row. Rest begins automatically. | Same one tap, but pinned next-set action; inline actuals only if edited. | No extra logging tap; reduce search/scroll. |
| C: plate loading | Tap “Plates for 60 kg” once, read bottom sheet, dismiss once. | Keep. | Existing interaction is already economical. |
| C: warm-up generation | Scroll to end of all five T1 working sets, tap Warm up once; generated warm-ups shift and renumber the working rows. | Warm up in current exercise header (1 tap); return to Working 1 with one action; retain logged warm-ups. | Remove long scroll and numbering confusion. |
| C: AMRAP | **Code-only:** final set reps prefilled; edit reps field then tap Done; AMRAP guidance present. | Inline rep entry on current-set surface with numeric keyboard and Done; keep 1–2 RIR guidance. | Shorter visual search; no extra mandatory input. |
| D: completion | **Code-only:** Finish is after all exercise cards; summary is another branch with reasons and next loads. | Persistent Finish when all required work is resolved; short summary led by changes to next prescription. | Remove end-of-list hunt. |
| E: failed lift | **Code-only:** engine changes stage; summary gives reason and may ask reset confirmation. | Show miss → next scheme in one plain line, with optional details. | Make deterministic decision understandable. |
| F: interruption | Force-stop/relaunch → Home shows Resume (1 tap); set/warm-ups remained in Room. | Preserve and make Resume the only primary action. | Existing recovery works; reduce competing CTA. |
| F: set accident | Observed completed row becomes a checkmark button; tapping again is implemented as toggle (**code-only for undo tap**). | “Undo” label/snackbar for a short interval plus edit in row menu. | Make recovery discoverable. |
| G: progress | Empty state inspected: Progress chip (1 tap), then zero workouts and ten starting-weight cards. Populated path untested. | One Progress destination; four lift summaries, tier selector, readable chart; tap point for session. | Reduce initial noise and improve answer quality. |

**Unverified situations:** notification-denied/locked-phone timer, old-session edit followed by progression replay, kg→lb change, skipped-days rotation, reset confirmation, restore from SAF, and completed-workout summary were inspected in code only or not reached. They require scripted journey tests in the implementation phase. The rotation code is session-index based, so missed calendar days should not change the next session; this was not tested by changing the emulator date.

## 6. Functionality audit

| Capability | Decision | Why and precise direction |
|---|---|---|
| Onboarding/program setup | **Refine** | Keep honest starting weights and tier independence; stage the ten inputs, hide optional increments, persist draft. |
| Rotation/progression | **Keep** | Domain rule is the core value; change explanation and correction handling, not GZCLP rules. |
| Exercise logging | **Refine** | Keep one-tap Done and immediate persistence; compact normal rows, pin current set, label Undo. |
| Warm-up generation | **Refine, P0 fix** | Preserve completed warm-ups and separate numbering; generated loading itself is useful. |
| Plate calculator | **Keep** | Observed output is clear and correct; move equipment configuration to structured editor. |
| Rest timer | **Refine** | Automatic deadline works after logging; keep compact control visible while scrolling and verify notification/reboot behavior. |
| Personal records | **Simplify** | First-ever set announces three records at once. Give one restrained indicator; detail on demand. |
| Substitutions | **Hide contextually** | Keep independent history and explicit original-lift consequence; show a sheet only when requested. |
| RPE/RIR and notes | **Hide contextually** | Optionality is correct; remove repeated `+ RIR` from every default set and show cue near the exercise. |
| Completion | **Refine** | Prioritize next prescription and reason; actuals/volume secondary; place Finish where it can be found. |
| History | **Refine** | Keep correction; add replay/conflict review before changing current recommendation. |
| Analytics | **Refine** | Keep factual data; eliminate start-weight cards from empty state and enlarge/select useful trends. |
| Settings | **Merge/simplify** | Split current monolithic form into coherent groups; per-lift settings belong with lift detail. |
| Backup/restore | **Keep** | Versioned backup, CSV, SAF and validation belong in Data settings; verify full round trip on device. |
| Generic routines/empty workout | **Hide from GZCLP Home** | Useful LibreFit capability, but an equal competing primary action for the GZCLP user. Retain in Library. |
| Exercise metadata/equipment | **Refine** | Suggestions exist; show why an option is related and what equipment it needs. |
| Error/recovery states | **Missing refinement** | Errors are bare text; add action, location, and preserved edits. |

## 7. Design system assessment

There is a theme, but GZCLP is largely a set of default Material components assembled screen by screen. The type scale is generally legible; bold display headings are overused on routine pages. Material surface/card language is consistent enough, but every lift and set is given a large container, making all information seem equally important. T1/T2/T3 meaning is conveyed by text, which is accessible; there is no disciplined tier-specific visual treatment. The cyan/teal accent is a general action color, so it does not explain tier or state. Large controls often meet touch-size needs, yet status-bar overlap directly violates safe-area accessibility. Dark mode and increased text scaling were not visually tested.

**Proposed small system:**

1. **Type:** one display size for workout identity; title for active exercise; body for target and actual; label for tier, set number, and context. Use a numeric style with tabular figures for weight/reps/time.
2. **Spacing:** 8 dp base; 16–20 dp screen gutters; 8–12 dp between a set's data and action; 24 dp between exercises. Compact completed rows, generous active target.
3. **Color:** theme primary only for the next safe action; T1/T2/T3 each has a subtle semantic accent plus explicit text. Success is a persistent check/label, never color alone. Error and reset use error/tertiary semantics with plain wording.
4. **Surfaces:** one primary current-exercise surface; secondary exercises as compact rows; bottom sheets for plate/substitution/options; dialogs only for destructive or exceptional decisions.
5. **Buttons:** one filled primary action per screen state; text/outlined actions for tools. Completed set affordance says “Undo” or has an accessible description.
6. **Motion/haptics:** one brief confirmation when a set is saved, a state change when rest starts/ends, no celebratory motion for routine PRs; respect system reduced motion. Test with gloves/sweaty hands and large text.
7. **Charts:** dates and units always visible; separate T1/T2; touch a point to inspect its actual session; no implied precision from a tiny sparkline.

## 8. Ruthless simplification and ideal navigation

**Top level for configured GZCLP:** **Today · History · Progress.** Today is the default launch and owns Start/Resume. History owns session detail/correction. Progress owns lift trends and records. Program/Equipment/Timing/Data live under a single settings entry, not a fourth peer chip inside a nested route. Library remains an explicit escape to general LibreFit tools; Profile need not be on the daily workout bar. The current `MainScreen → GzclpScreen → inner chips` structure gives two navigation shells for one task ([MainScreen.kt](../app/src/main/java/org/librefit/ui/screens/MainScreen.kt), [GzclpScreen.kt](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt)).

**Ideal daily flow:**

1. **Home/Today:** “A1 next” or “A1 in progress” with first lift `Squat · 60 kg · 5×3+`; a single Start/Resume button; compact T2/T3 preview. No generic workout CTA in this state.
2. **Start:** open current exercise at its first unresolved set. Prior performance is a single adjacent line when available. Do not force a plan confirmation.
3. **Warm up and load:** contextual Warm up and Plates actions beside the weight. Warm-up sheet shows achievable steps. Preserve logged warm-ups and keep working set numbering stable.
4. **Active:** current set occupies the main touch area; one tap records the prefilled target; tap actual weight/reps to edit; AMRAP requires easy rep entry. Completed sets collapse. Timer remains compact and visible without covering the set action.
5. **Between sets:** show “Rest 2:58” with pause/±15/skip; when rest expires, state moves to “Ready for Set 2.” No navigation transition. Haptic/sound only if opted in and permitted.
6. **Finish:** make completion available when required sets are resolved; show each changed prescription and reason first, then brief actuals/PR/volume. Exceptional resets ask for confirmation with the exact next load.
7. **Progress:** select Squat → choose T1/T2 → read dated working-weight trend; tap a point to open its actual session. Volume/AMRAP/reset detail is secondary.

### Before / after wireframes

These are redesign specifications, **not screenshots**.

```text
TODAY — CURRENT                         TODAY — PROPOSED
GZCLP  Close                            Today                 Settings
[Today][History][Progress][Settings]     A1 next · Squat
Next workout                             60 kg · 5×3+   [Start A1]
A1                                       T2 Bench 40 kg · 3×10
Training week 1 · Session 1 ...          T3 Pulldown 30 kg · 3×15+
[large T1 card]                          History          Progress
[large T2 card]
[large T3 card]
[Start workout A1]
```

```text
ACTIVE — CURRENT                         ACTIVE — PROPOSED
Workout A1 · 1/11                       A1 · Squat · T1       1/11
Rest 2:58 Skip −15 +15 Pause            Rest 2:58      −15  Pause  +15
[large Squat card]                       60 kg · 5×3+   Plates · Warm up
  Set 1 [60] [3] [✓]                    Previous: 57.5 kg × 3,3,5
  + RIR · 3 PR labels                   Working 2 of 5
  Set 2 [60] [3] [Done]                 60 kg × 3       [Complete set]
  + RIR                                 Completed: 1 × 60 kg × 3 [Undo]
  ... Set 5 AMRAP ...                   Next: Set 3; final set AMRAP
  Warm up +Warm-up +Set                 More · Notes · Substitute
  Notes · Substitute · Skip             [Bench] [Pulldown]    Finish
```

```text
WARM-UP — CURRENT                        WARM-UP — PROPOSED
Warm-up [kg][reps][Done]                Warm-up for 60 kg
Remove set · Move up · Move down         W1  20 kg × 5   [Done]
...                                     W2  35 kg × 3   [Done]
Set 5 [60][3][✓]                        W3  47.5 kg × 2 [Done]
Set 6 [60][3][Done]                     Working 1  60 kg × 3 [Complete]
                                        Edit warm-ups · Regenerate pending
```

## 9. Microinteractions

| Moment | Current evidence | Revised interaction |
|---|---|---|
| Set completion | Done becomes ✓; progress and timer update ([15](product-design-audit/screenshots/15-set-complete-timer.png)). | Brief tactile confirmation; compact saved row; explicit Undo affordance; idempotent rapid taps. |
| Timer | Inline row with four text actions near top, scrolls away. | Persistent compact strip, deadline-based state, no timer after final set; background notification verified separately. |
| AMRAP | Labeled “Set 5 · AMRAP” with prefilled target; guidance is above all sets. | When AMRAP becomes current, reps field expands and 1–2 RIR reminder sits next to Complete. |
| PR | First-ever set reports three records in a wrapped text block. | One subtle “First recorded set” or strongest relevant PR; full record details on tap. |
| Weight editing | Outlined fields always visible. | Tap weight to edit; keypad focused; save/cancel state explicit; reflect actual versus recommendation. |
| Exercise expansion | Every exercise expanded. | Only current exercise expanded; next exercises compact and tappable. |
| Failure | Summary text exists in code. | One line: “Missed 5×3 target. Next: 6×2+ at 60 kg.” No shame language. |
| Recovery/error | Bare top error line in code. | Preserve input, show local explanation and Retry; avoid reopening a long form. |
| Finish | Button after all cards in code. | Persistent Finish when eligible; concise reason-first summary. |

## 10. Top ten product problems

1. **System UI collision** on every observed GZCLP screen: header text and Close overlap status icons. This is a basic readability/touch defect ([02](product-design-audit/screenshots/02-onboarding-top.png)).
2. **Warm-up regeneration can delete logged work** because all existing warm-up sets are removed before new rows are inserted ([GzclpRepository.kt:143](../app/src/main/java/org/librefit/db/repository/GzclpRepository.kt#L143)).
3. **Historical edits do not reconcile current progression.** The repository marks `historicalCorrection`; subsequent states remain as they were. The app discloses this, but a user cannot know their corrected record and next recommendation agree ([GzclpRepository.kt:131](../app/src/main/java/org/librefit/db/repository/GzclpRepository.kt#L131)).
4. **Two competing product paths on Home** make “start training” ambiguous ([01](product-design-audit/screenshots/01-launch.png)).
5. **All working sets and optional controls are expanded at once**, forcing long scrolling for the next set ([13](product-design-audit/screenshots/13-active-top.png), [16](product-design-audit/screenshots/16-active-lower.png)).
6. **Warm-ups corrupt visible set numbering** and obscure the first working set ([17](product-design-audit/screenshots/17-warmups.png)).
7. **Onboarding demands ten weights before value is delivered**, while displaying ten extra increment fields ([04](product-design-audit/screenshots/04-onboarding-bottom.png)).
8. **Settings expose serialization formats** and destructive progression controls in a monolithic form ([10](product-design-audit/screenshots/10-settings-middle.png)).
9. **Timer and Finish lose visibility during the long workout scroll** ([GzclpScreen.kt §ActiveWorkout](../app/src/main/java/org/librefit/ui/screens/gzclp/GzclpScreen.kt)).
10. **Progress starts as a list of starting weights rather than insight**, and populated chart affordances are tiny in code ([08](product-design-audit/screenshots/08-progress-empty.png)).

## 11. Prioritized implementation backlog

Complexity is relative (S/M/L); confidence is based on observed runtime or inspected code. Frequency is per relevant workout unless noted. This is a backlog for a later implementation phase, not a code change in this audit.

| Priority | Problem / evidence | Concrete solution | User impact / frequency | Complexity | Confidence |
|---|---|---|---|---|---|
| **P0** | Header overlaps status bar; screenshots 02–18 | Use a proper top app bar/insets and test status/navigation bars plus large font. | Readability and tap safety / every GZCLP visit | S | High, observed |
| **P0** | Re-generating warm-ups deletes completed rows; repository line 143 | Generate only missing/pending rows; require explicit review before replacing edited rows; add regression test. | Prevent recorded-work loss / occasional, severe | M | High, code |
| **P0** | Historical corrections do not reconcile downstream state; repository line 131 | Add deterministic progression replay preview from correction point, identify later overrides/resets, apply atomically only after confirmation. | Program trust / rare, severe | L | High, code |
| **P0** | Working set shown as Set 5 after warm-ups; screenshot 17 | Number warm-ups and working sets separately; collapse completed warm-ups. | Immediate logging correctness / every warm-up workout | S | High, observed |
| **P1** | Home has competing GZCLP, empty workout, routine actions; screenshot 01 | Make GZCLP Today the configured default Home; move generic actions to Library/overflow. | Faster start / every visit | M | High, observed |
| **P1** | Active screen expands all rows and optional tools; screenshots 13/16 | Pinned current exercise/next set, compact completed rows, contextual tool sheet. | Less scrolling and error / every set | L | High, observed |
| **P1** | Timer and Finish scroll out of view; screenshot 16/code | Persistent compact timer and finish action at appropriate state. | One-handed use / every workout | M | High |
| **P1** | Onboarding has ten mandatory entries and optional fields upfront; screenshots 02–05 | Stage loads, draft persistence, contextual increment editing; test incomplete process death. | First-use completion / once per program | M | High |
| **P1** | Raw plate/warm-up syntax in Settings; screenshot 10 | Structured equipment profiles and editable warm-up rows with units/validation. | Fewer configuration errors / occasional | M | High |
| **P1** | Populated analytics mini charts are 64 dp; code | Lift/tier selector and readable dated chart backed by derived state. | Useful progress decisions / weekly | M | Medium, code-only |
| **P2** | Three PR labels after first-ever set; screenshot 15 | One restrained feedback line; detailed records in lift detail. | Calmer workout / many firsts | S | High |
| **P2** | Completed ✓ does not state Undo; screenshot 15 | Explicit Undo/action description and brief undo feedback. | Accidental-tap recovery / occasional | S | High |
| **P2** | Substitution form expands inline; screenshot 18 | Bottom sheet with suggestion, equipment, consequence, and T1/T2 confirmation. | Less clutter / exceptional | M | High |
| **P2** | Empty Progress lists starting weights; screenshot 08 | Purposeful empty state and one return-to-workout action. | Clear first-use state / until first workout | S | High |
| **P2** | Error shown as top-line text; code | Contextual error + Retry; preserve local edits. | Recoverability / rare | M | Medium, code-only |
| **P3** | Visual density and card/elevation variation | Apply type/spacing/surface rules above; audit light/dark/large text. | Refinement / continuous | M | Medium |
| **P3** | Generic shell still offers unrelated routes | Reassess destination prominence after core flow fixes; retain useful LibreFit capabilities. | Clarity / frequent | M | Medium |

## 12. Verification, assets, and limitations

- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug --no-daemon` **passed** (45 tasks reported up to date). No production tests were rerun during this design-only audit; passing compilation is not a usability or correctness guarantee.
- APK installed and launched on the Pixel 9 emulator. ADB UI interactions and screenshots are real captures, not mockups. [Screenshot directory](product-design-audit/screenshots/) contains the numbered checkpoints; transitional captures were discarded, leaving some gaps in the numbering. The final `14-plate-sheet.png` is the open sheet, and `19-home-resume.png` shows Home after splash.
- The force-stop/relaunch returned to Home with a **Resume GZCLP workout** action and the A1 session still in progress. This verifies this one persistence path on this device. It does not verify device reboot, notification delivery, or a timer continuing under all background restrictions.
- I did not reach a completed workout, populated analytics, a reset, or a restore confirmation on-device. Their critique uses code evidence and is labeled accordingly. Dark mode, high contrast, TalkBack, 200% font, and smaller screens need an accessibility pass before visual scores can be finalized.
- Existing source tree already had many modified/untracked production files before this audit; this document and screenshot directory are the only deliverables created for the review. No GZCLP rules or production UI code were changed.

**Release standard for the next iteration:** A new user can configure honestly, start in one clear path, see and complete the next working set without searching, recover every recorded set, understand every progression change, and trust that editing history or using warm-up tools cannot silently lose or contradict their training data.
