# Baby Tracker v1 review

Review of commits `81d00a2` (research), `73af55b` (MVP), and `HEAD` (sources screen).

Scope: what's shipped vs. what the evidence base says we should have.
Status colour: **done** / **partial** / **missing** / **bug**.

## 1. Module coverage

| Module | Evidence base | Guidance engine | Entry UI | List/history UI | Red flags surfaced |
|--------|---------------|-----------------|----------|-----------------|-------------------|
| Feeding | covered | FeedingTargets | FeedEntryActivity | **missing** | **partial** (no use-by timer) |
| Solids + allergens | covered | AllergenLadder | AllergenActivity | partial (per-allergen card) | done for reactions |
| Safe sleep | covered | SafeSleepChecklist | SleepEntryActivity | **missing** | partial |
| Growth | covered | GrowthPercentiles | GrowthEntryActivity | **missing** | partial (only HC flag) |
| Milestones | covered | MilestoneChecklist | **missing** | **missing** | **missing** |
| Diapers | covered | DiaperThresholds | DiaperEntryActivity | **missing** | done for stool colour |
| Vaccines | covered | VaccineSchedule | **missing** | **missing** (dashboard shows next only) | partial |
| Fever / medication | covered | FeverThresholds + MedicationDose | MedicationEntryActivity | **missing** | done |
| Activity / tummy time | covered | (none shipped) | **missing** | **missing** | **missing** |
| Pumping | covered | (subkind only) | **missing** | **missing** | **missing** |

## 2. Concrete gaps (candidates for issues)

### REV-001 Missing: milestone checklist UI
CDC 2022 has ~75 items across 12 age buckets and four domains. We ship 20 items and have **no Activity** that surfaces them. Parent sees nothing from the milestone data.

### REV-002 Missing: vaccine log + entry UI
`VaccineSchedule.nextDue()` drives the dashboard hint but there is no screen to **record a dose** as given, no list of previously-given doses, no way to set age-appropriate "HepB #2" etc. Dashboard says "next due" but the parent can't mark "given".

### REV-003 Missing: timeline / history view across all kinds
Everything can be logged, nothing can be reviewed. `TrackerStore.recent` / `since` are unused beyond counters. Parents need a scroll-back timeline grouped by day (per CM-OBS-005 pattern from the monitor side).

### REV-004 Missing: edit / delete logged events
`TrackerStore.update` and `TrackerStore.delete` exist but no UI invokes them. A typo becomes permanent.

### REV-005 Missing: tummy time / activity entry
WHO and AAP targets are in the evidence base but there is no Activity to log tummy time, screen time, restraint-session minutes, or outdoor time. The dashboard ignores plagiocephaly risk.

### REV-006 Missing: pumping session entry + breast-milk use-by timer
`FeedingTargets.BreastStorage` enum is wired but nothing consumes it. Parents with pumped milk can't store it with an auto-discard timer.

### REV-007 Missing: twin / multi-child switcher UI
`ChildProfileStore` supports multiple profiles and `setActiveId` but the UI only has a single profile editor. No tile, no tab, no toolbar spinner.

### REV-008 Partial: safer-sleep checklist re-asks every session
Required-items are unchecked every time, even when the parent just marked them 20 minutes ago. Should persist across sessions for the same cot/setup and let the parent flip individual items as they change.

### REV-009 Partial: growth percentile coverage
WHO LMS table in `GrowthPercentiles.kt` is abbreviated (11 monthly points 0-24mo per reference) and only male/female WFA/LFA/HCFA. Missing:
- Monthly points are hand-picked from the official tables; interpolation will be inaccurate between 6 and 9 months because we skip 7/8mo.
- No weight-for-length.
- No BMI-for-age.
- No CDC 2-5y bridging.
- No MUAC.
- Full LMS tables should ship as `assets/tracker/who_lms.json` and be parsed at runtime.

### REV-010 Partial: medication calculator (mL output + interleave)
- `doseMlFromMg()` is a helper but the main `Recommendation` only returns mg. mL is computed in the activity; should be part of the recommendation with strength as input.
- NICE NG143 §1.6.6 forbids **simultaneous** paracetamol + ibuprofen but allows alternating when distress persists. The calculator doesn't warn if the parent tries to log an ibuprofen dose within 60 minutes of a paracetamol dose (or vice versa).
- Weight-freshness: the Activity does not read the most-recent recorded weight; it makes the parent re-enter each time.

### REV-011 Partial: fever evaluator doesn't use NICE traffic-light checklist
`FeverThresholds.evaluate()` uses temperature alone. The NICE traffic-light also surfaces red/amber symptoms (non-blanching rash, grunting, reduced skin turgor, etc.) — these belong in the evaluator and in the in-app checklist.

### REV-012 Partial: allergen ladder doesn't handle NIAID pre-intro testing for high-risk infants
`ChildProfile.allergyRiskTier` exists but the allergen flow doesn't block or prompt HIGH-tier parents to go to a specialist before first peanut exposure (NIAID Guideline 1).

### REV-013 Partial: diaper daily-count tracker
`DiaperThresholds.evaluateWetCount` is called inside the entry screen on live state. There is no dashboard tile that shows "today's wet count vs target for day of life".

### REV-014 Missing: confirmation / undo on destructive events
Saving marks everything permanent with a toast. No undo, no "Are you sure" for anaphylaxis reactions or deleted doses.

### REV-015 Missing: data export / share-with-pediatrician
No export bundle, no CSV, no PDF. `ConfigExportImport` exists elsewhere in the codebase but tracker data doesn't plug in.

### REV-016 Missing: notifications + reminders
The app can't remind the parent to feed, or to give the next safe dose at the safe time, or that a vaccine is overdue. `EventNotifier` (added on maxtt) exists but tracker doesn't use it.

### REV-017 Bug: dashboard "today" summary uses 24-hour window, not calendar day
The 24h sliding window is fine for "hours since last feed" but the user sees "Diapers: 6 wet / 3 dirty" that rolls over mid-day. Separate the 24h counter from a calendar-day counter and label each.

### REV-018 Bug: age in months = days / 30
`chronologicalAgeMonths(now) = chronologicalAgeDays(now) / 30`. For a 60-day-old baby this returns 2 months; for an 89-day-old it also returns 2. Should use calendar-based math (Calendar field arithmetic) so "6 months" corresponds to the 6-month birthday, not day 180.

### REV-019 Bug: timezone drift for multi-day entries
Stored timestamps use `System.currentTimeMillis()`. If the device timezone changes between sessions, histogram buckets shift. Should store device timezone at entry time in `extra_json` so history can be re-rendered.

### REV-020 Bug: `MonotonicClock` used in tracker but unit tests hit "method not mocked"
`MonotonicClock.nowMs()` wraps `SystemClock.elapsedRealtime()` which the JVM test target doesn't stub. We enabled `returnDefaultValues=true`, but tests that call through to it get `0` which may mask real bugs. Mark pure-math methods that don't need real time.

### REV-021 Missing: accessibility (CM-UX-001)
- Hardcoded hex colours on tracker activities; no theme-awareness.
- No content descriptions.
- Touch targets match 48dp but large-text / font-scaling has not been verified.
- TalkBack role descriptions not added to dashboard tiles.

### REV-022 Partial: manifest + themes
Tracker activities don't declare `android:theme="@style/AppTheme"`; they fall back to the activity default. Visually consistent today only because layouts hardcode dark-blue colours.

### REV-023 Missing: Health Connect bridge
Research doc calls for it (`GROWTH_WEIGHT → WeightRecord` etc.). No `implementation 'androidx.health.connect:connect-client:...'` dependency, no bridge class.

### REV-024 Missing: safety disclaimer / not-medical-advice banner
The medication calculator blocks unsafe doses but never tells the parent that the output is **not** a prescription. Legal + safety both want an obvious disclaimer on first run and on every medication entry.

### REV-025 Missing: allergen "high-risk infant" pathway
Tied to REV-012; also implies a first-run education card for HIGH tier parents.

### REV-026 Missing: activity entry / activity targets surfaced
WHO 2019 under-5 movement targets are live in the evidence base but the app does not let the parent record tummy-time minutes or flag outdoor time.

### REV-027 Partial: allergen reaction severity + action
Anaphylaxis shows a toast. It should:
- show a full-screen red card,
- link to ASCIA Action Plan PDF,
- provide a "call emergency services" one-tap button (tel:999 or tel:911 depending on jurisdiction).

### REV-028 Missing: weight growth chart visualisation
Percentile is computed per-measurement but there is no chart over time. Without trend we can't detect the "crossed 2 major percentile bands down" red flag.

### REV-029 Missing: save-then-view next feed target
After saving a feed the app returns to the dashboard but does not highlight when the next feed is likely due based on `FeedingTargets.intervalHoursTarget`.

### REV-030 Missing: on-device image attachments (stool photo, rash)
AAP poop page effectively asks parents to compare against a photo. We don't capture or store one. Also useful for plastered-on rashes, eye symptoms, etc.

## 3. What's correct and shipped

- **Evidence-base doc** with every citation.
- **Sources screen** inside the app linking to each primary page.
- **TrackerStore** schema is clean and future-proof.
- **ChildProfile** correctly models corrected age for preterms.
- **MedicationDose calculator** blocks: aspirin <16, age gates, weight gates, 24h cap, interval.
- **AllergenLadder** state machine: correct for LEAP + ASCIA maintenance.
- **DiaperThresholds** red-flag pathways: pale stool → biliary atresia, blood, melena after day 5.
- **FeverThresholds**: <3mo any >=38°C is urgent; 40°C+ emergency at any age.
- **SafeSleepChecklist** phrases verbatim from CDC / AAP / Lullaby Trust.
- **GrowthPercentiles** WHO LMS method correctly implemented.
- **VaccineSchedule** covers US / UK / AU 0-24mo; next-due sort order correct.

## 4. Recommended roll-up order

1. REV-002 + REV-004 + REV-003 (can't trust a tracker you can't review or edit).
2. REV-017 + REV-018 + REV-019 (timekeeping correctness).
3. REV-009 (extend WHO LMS + add CDC 2-5y via JSON asset).
4. REV-011 + REV-010 + REV-024 (medical correctness + safety disclaimer).
5. REV-005 / REV-006 (pumping + activity coverage).
6. REV-001 + REV-007 (milestones + multi-child).
7. REV-016 + REV-023 + REV-015 (notifications, Health Connect, export).
