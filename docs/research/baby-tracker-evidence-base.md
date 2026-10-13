# Baby Tracker Evidence Base

Status: Living document. All recommendations cite current (2024-2026) primary guidance.
Last updated: 2026-04-18.

## Purpose

Child Monitor is local-first. We want the tracker module to be **evidence-based by default** so that parents can trust the targets, thresholds, and prompts the app surfaces. This document is the source of truth for:

- Which data points are recorded on a given event (field schema).
- Which thresholds / targets are surfaced in the UI.
- The authoritative guidance we cite when the app nudges the parent.

## Non-goals

- Replacing a pediatrician. Every red-flag we detect ends with "contact your clinician".
- Storing clinical identifiers (MRN, insurance). Tracker data is on-device only.
- Prescribing medication or dose changes. We only repeat public dosing tables; the parent enters what was given.

## Primary sources, by jurisdiction

| Topic | Global | US | UK | AU |
|-------|--------|----|----|----|
| Feeding | WHO | CDC + AAP + HealthyChildren | NHS Start for Life | eatforhealth.gov.au + ABA |
| Solids / allergens | WHO | NIAID + FARE + AAP | NHS Weaning | ASCIA |
| Safe sleep | WHO 24h movement | AAP Policy 2022 + NICHD Safe to Sleep | Lullaby Trust | Red Nose |
| Growth | WHO 0-5y Child Growth Standards | CDC 2-5y (extended 2022) | RCPCH-WHO | RCPCH-WHO |
| Elimination | WHO IMCI | AAP + CDC | NHS | Royal Children's Hospital |
| Vaccines | WHO schedule DB | CDC/ACIP | NHS | Australian Immunisation Handbook |
| Milestones | WHO motor windows | CDC "Learn the Signs" 2022 | NHS health visits | CHECKS/ASQ |
| Fever / meds | WHO IMCI | AAP / CDC | NICE NG143 + BNFc | Royal Children's + health.gov.au |
| Activity | WHO 24h movement u5 | AAP / CDC | NHS / UK CMO | 24h movement AUS |

## Module coverage

### 1. Feeding
*Status: Research pending (§Feeding below).*

### 2. Solid food + allergen introduction
*Status: Research pending (§Solids below).*

### 3. Sleep + safe sleep
*Status: Research pending (§Sleep below).*

### 4. Growth + milestones
*Status: Research pending (§Growth below).*

### 5. Diapers + elimination
*Status: Research pending (§Diapers below).*

### 6. Vaccinations
*Status: Research pending (§Vaccines below).*

### 7. Fever, temperature, medication
*Status: Research pending (§Fever below).*

### 8. Activity + tummy time
*Status: Research pending (§Activity below).*

## §Feeding

### Breastfeeding session
Fields per session: start/end timestamps, duration (derived), side (`L`/`R`/`BOTH`), last-side-used flag, optional LATCH score 0-10 (Latch, Audible swallow, nipple Type, Comfort, Hold), hunger-cue boolean set (rooting, lip-smacking, hand-to-mouth).

Frequency targets (feeds/24h):

| Age | Target |
|-----|--------|
| 0-1 mo | 8-12 per 24h, ≤ 2-3h daytime / 4h overnight between feeds ([AAP](https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/how-often-and-how-much-should-your-baby-eat.aspx)) |
| 1-3 mo | every 2-3h |
| 3-6 mo | every 3-4h |
| 6-12 mo | 4-5 feeds/day alongside solids |
| 12+ mo | continue up to 2 y or beyond ([WHO](https://www.who.int/health-topics/breastfeeding), [WHO complementary feeding guideline 2023](https://www.who.int/publications/i/item/9789240081864)) |

One-time event: `first_feed_within_1h_of_birth` (WHO/UNICEF).

### Pumping / expressed milk
Per-session: side, volume, duration. Auto-compute "use by" using [CDC breast-milk storage](https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html):

| State | Max |
|-------|-----|
| Room temp ≤ 77 °F / 25 °C | 4 h |
| Refrigerator | 4 days |
| Freezer | 6 mo optimal, 12 mo acceptable |
| Thawed in fridge | 24 h |
| Warmed to room temp | 2 h |
| Leftover in started bottle | 2 h then discard |

### Formula prep & bottle feeding
- `water_boiled_to_70C` boolean + cool-time minutes ([NHS making up formula](https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/bottle-feeding/making-up-baby-formula/)).
- `sterile_water_required` if < 2 mo / preterm / immunocompromised ([CDC formula prep](https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html)).
- Discard rules (different from breast milk!):
  - Prepared + untouched at room temp: 2 h
  - Prepared in fridge unused: 24 h
  - After feed started: 1 h then discard
- Bottle volume targets:
  - Week 1: 1-2 oz (30-60 mL) / feed
  - Month 1: 3-4 oz (90-120 mL) / feed; ~32 oz / day
  - By 6 mo: 6-8 oz (180-240 mL) / feed, 4-5 feeds / day
  - Daily cap: ~32 oz (960 mL) ([AAP schedule](https://www.healthychildren.org/English/ages-stages/baby/formula-feeding/Pages/amount-and-schedule-of-formula-feedings.aspx))
- `iron_fortified_formula` boolean; require true for <12 mo.
- Warming: `warming_method` enum {warm_water, microwave}; hard-block microwave ([CDC](https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html)).

### Paced bottle feeding
Target session 15-20 min; flag if finished in <5 min. Slow-flow nipple boolean, bottle-horizontal-hold boolean, rest prompt every 20-30 s ([La Leche League](https://lllusa.org/bfinfo/)).

### Weight-based volume math
Stored `child_weight_kg` drives a daily-target calc: ~2.5 oz (75 mL) formula per lb body weight per day, equivalent to 150-180 mL / kg / day for young infants, divided over ~8 feeds ([AAP](https://www.healthychildren.org/English/ages-stages/baby/formula-feeding/Pages/amount-and-schedule-of-formula-feedings.aspx)).

### Water & fluoride
`water_source` enum {tap_fluoridated, tap_non_fluoridated, bottled, distilled}. `low_fluoride_bottled` boolean. <6 mo: no fluoride supplement. >6 mo: supplement only if water <0.3 ppm ([CDC fluoride FAQ](https://www.cdc.gov/fluoridation/faq/)).

### Cup introduction
Single event; target age ~6 mo when solids start. Limit water 4-8 oz / day ([AAP](https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Discontinuing-the-Bottle.aspx)).

### Maternal medication & alcohol
- Medication log links out to [LactMed](https://www.ncbi.nlm.nih.gov/books/NBK501922/) per drug. **Do not cache** safety assertions.
- Alcohol log: standard-drink count + start time → tracker computes "safe to nurse at" = `start + 2h × drinks`. Pump-and-dump does NOT accelerate clearance ([CDC](https://www.cdc.gov/breastfeeding-special-circumstances/hcp/vaccine-medication-drugs/alcohol.html)).

### Supplementation flags
- Vitamin D daily log (IU). AAP: 400 IU / day for all breastfed or partially breastfed infants from day 1 through 12 mo; 600 IU / day from 1 y.
- Iron supplement: 1 mg / kg / day from 4 mo for breastfed infants until iron-rich solids are established; earlier if preterm / LBW ([AAP supplementation](https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Vitamin-Iron-Supplements.aspx)).

### Phase flags
- `exclusive_bf_phase` (0-6 mo) per [WHO](https://www.who.int/health-topics/breastfeeding) / [NHMRC](https://www.eatforhealth.gov.au/guidelines/guidelines) / AAP.
- `continued_bf_phase` through ≥12 mo ([AAP](https://www.healthychildren.org/English/ages-stages/baby/breastfeeding/Pages/Where-We-Stand-Breastfeeding.aspx)) or ≥ 2 y ([WHO](https://www.who.int/publications/i/item/9789240081864)).

## §Solids

### Start age
- `solids_start_date` (date), `solids_start_age_weeks` (int, derived).
- Default 26 weeks (6 mo) per [WHO 2023 complementary feeding](https://www.ncbi.nlm.nih.gov/books/NBK596423/) / [CDC](https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/when-what-and-how-to-introduce-solid-foods.html); allow 17-26 weeks (4-6 mo) only with readiness signs per [AAP Starting Solids](https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Starting-Solid-Foods.aspx). Hard-block < 17 weeks.

### Readiness-signs checklist (all must be true before start)
`readiness_signs.*` booleans: `sits_with_support`, `head_neck_control`, `opens_mouth_for_food`, `swallows_not_tongue_thrust`, `shows_interest` ([CDC](https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/when-what-and-how-to-introduce-solid-foods.html), [AAP](https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Starting-Solid-Foods.aspx)).

### High-risk classification ([NIAID addendum](https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/))
- `allergy_risk_tier` enum {HIGH, MODERATE, LOW}. HIGH = severe eczema OR diagnosed egg allergy. MODERATE = mild-to-moderate eczema. LOW = neither.
- `family_allergy_history` enum {none, parent, sibling, both} — tracked but does NOT upgrade tier under NIAID.

### Pre-introduction testing (HIGH tier only)
- `pre_intro_test` enum {sIgE, SPT, none_required}, `test_result_value`, `test_result_interpretation`.
- Thresholds: sIgE < 0.35 kUA/L → home intro; ≥ 0.35 → specialist. SPT ≤ 2 mm → home; 3-7 mm → supervised OFC; ≥ 8 mm → likely allergic / specialist ([AAFP NIAID summary](https://www.aafp.org/pubs/afp/issues/2017/0715/p130.html)).

### Peanut dosing (LEAP protocol)
- `peanut_first_dose_date`, `peanut_protein_grams_per_session`, `peanut_sessions_per_week`, `peanut_form` enum {smooth_peanut_butter_thinned, bamba, peanut_puff}.
- Target: **≥ 6 g peanut protein / week across ≥ 3 sessions** (e.g., 2 g × 3). Equivalents: 2 g protein = ~1 heaping tsp peanut butter = ~2/3 bag (~8 g) Bamba. Maintain through age 5 ([LEAP trial](https://pmc.ncbi.nlm.nih.gov/articles/PMC4416404/), [NEJM listing](https://www.nejm.org/doi/full/10.1056/NEJMoa1414850)).

### Egg introduction
- `egg_first_dose_date`, `egg_form` enum {scrambled, hard_boiled, mashed} — must be fully cooked. Target ~1/3 whole egg ([NHS foods to avoid](https://www.nhs.uk/baby/weaning-and-feeding/foods-to-avoid-giving-babies-and-young-children/)).

### Allergen panel (9 items, all by 12 mo)
`allergens[]` objects with `{name, first_exposure_date, form, reaction_observed, status}`. Covered: peanut, egg, cow's milk (yogurt/cheese — milk-as-drink < 12 mo blocked), wheat, soy, sesame, tree nuts (ground/butter only), fish, shellfish. No mandated order beyond prioritising peanut + egg. Maintenance: ≥ 1 × / week once tolerated ([ASCIA infant feeding](https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies), [AAP allergen intro](https://www.healthychildren.org/English/healthy-living/nutrition/Pages/when-to-introduce-egg-peanut-butter-and-other-common-food-allergens-to-your-baby-food-allergy-prevention-tips.aspx)).

### Reaction logging (ASCIA Action Plan)
Per event: `allergen`, `exposure_timestamp`, `onset_minutes_after`, `severity` enum {mild_moderate, anaphylaxis}, `systems[]` multi-select (oral / skin / GI / respiratory / cardiovascular / neuro), `symptoms[]`, `treatment_given` enum {antihistamine, adrenaline, none}, `ed_visited` boolean.

Mild-moderate symptom list: lip/face/eye swelling, hives / welts, tingling mouth, abdominal pain, vomiting.

Anaphylaxis (any one sign triggers 999 card): difficult/noisy breathing, tongue swelling, hoarse voice, persistent cough / wheeze, persistent dizziness / collapse, pale and floppy (infant). Source: [ASCIA Action Plan 2023](https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf).

### Texture progression
`current_texture_stage` enum {smooth_puree, mashed_lumpy, soft_finger_foods, family_foods}. Age-band prompts: 6 mo smooth puree + soft finger; 7-9 mo mashed / lumpy; 9-12 mo chopped finger foods; 12 mo+ family foods ([NHS](https://www.nhs.uk/best-start-in-life/baby/weaning/what-to-feed-your-baby/from-around-6-months/)).

### Iron-rich first foods
`iron_foods_given[]` multi-select: iron-fortified infant cereal, pureed meat (beef / lamb / chicken / turkey), fish, eggs, beans, lentils, tofu. Pair with vitamin C source ([WHO 2023](https://www.ncbi.nlm.nih.gov/books/NBK596423/)).

### Hard blocks
- `honey_introduced_date` — validation: ≥ 365 days from DOB. Hard-block before 12 mo (botulism) ([CDC avoid/limit](https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/foods-and-drinks-to-avoid-or-limit.html)).
- `cows_milk_as_main_drink_date` — block < 12 mo. Yogurt / cheese allowed from 6 mo.
- `added_salt` bool target false < 12 mo.
- `added_sugar` bool target false < 24 mo.
- `juice_ml_per_day` target 0 < 12 mo.

### Choking hazards (block acknowledgement until ~4 y)
`choking_hazard_warnings_dismissed[]` list: whole grapes, whole / chopped nuts & seeds, hot dogs / meat sticks round, hard / gooey / sticky candy, chewing gum, popcorn, raw carrots / apple chunks, thick nut-butter globs, marshmallows, chunks of meat / cheese, whole corn kernels. Prep rules: grapes quartered, hot dogs lengthwise then diced, nut butter thin-spread, ≤ ½ inch pieces ([AAP Choking Prevention](https://www.healthychildren.org/English/health-issues/injuries-emergencies/Pages/Choking-Prevention.aspx), [CDC choking hazards](https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/choking-hazards.html)).

### Feeding method
`feeding_method` enum {spoon, BLW, combination} — informational. Both AAP-compatible.

### Vegetarian / vegan
`diet_type` enum {omnivore, vegetarian, vegan}, `supplements[]` (B12, vitamin D, iron, iodine). Vegan triggers clinical-guidance card: B12 ≥ 5 mcg/day, vitamin D 10 mcg/day, monitor ferritin / zinc at 9-12 mo ([ESPGHAN review](https://pmc.ncbi.nlm.nih.gov/articles/PMC12580465/)).

### Allergen "established" ladder (state machine)
For each of the 9 allergens:

1. `not_started` → `introduced` when `first_exposure_date` logged AND no reaction within 2 h.
2. `introduced` → `established` when ALL true:
   - ≥ 3 total exposures logged.
   - Maintenance dose achieved:
     - Peanut: ≥ 6 g protein / week across ≥ 3 sessions for ≥ 2 consecutive weeks (LEAP).
     - Others: ≥ 1 serving / week for ≥ 4 consecutive weeks (ASCIA).
   - Zero mild/moderate reactions in the last 4 weeks.
3. Any logged reaction → `avoid` (locks allergen; surfaces ASCIA Action Plan link).
4. `established` regressing (gap > 14 days without exposure) → soft warning "maintenance lapsing — re-offer this week."

## §Sleep

### Safety banner (show on every sleep-start card)
Verbatim phrases from authoritative sources:

| Banner | Source |
|--------|--------|
| "Place your baby on his or her back for all sleep times — naps and at night." | [CDC Sleep Safely](https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html) |
| "Use a firm, flat (not at an angle or inclined) sleep surface." | CDC |
| "Keep your baby's sleep area in the same room where you sleep, ideally until your baby is at least 6 months old." | CDC |
| "Keep soft bedding out of your baby's sleep area. This includes blankets, pillows, bumper pads, and soft toys." | CDC |
| "Do not cover your baby's head or allow your baby to get too hot." | CDC |
| "Offer your baby a pacifier at nap time and bedtime." | CDC |
| "Feed your baby breastmilk." | CDC |
| "Keep the room temperature between 16-20 °C." | [Lullaby Trust room temperature](https://www.lullabytrust.org.uk/safer-sleep-advice/room-temperature/) |
| "When your baby looks like they're trying to roll over, you should stop swaddling them." | [AAP HealthyChildren Safe Sleep](https://healthychildren.org/English/ages-stages/baby/sleep/Pages/A-Parents-Guide-to-Safe-Sleep.aspx) |
| "Avoid sleep on a couch or armchair or in a seating device, like a swing or car safety seat." | [AAP Safe Sleep](https://www.aap.org/en/patient-care/safe-sleep/) |

Localise °C/°F per user locale (16-20 °C = 60.8-68 °F).

### Sleep-start safety checklist (boolean/enum, prompt before session)

| Field | Recommended | Source |
|-------|-------------|--------|
| `position` {back, side, stomach} | back — flag otherwise | CDC, AAP |
| `surface_firm_flat` | true | CDC |
| `surface_incline_deg` (≤10° per CPSC June 2022 standard) | warn > 10° | AAP HealthyChildren |
| `room_share` | true < 6 mo strongly, ideally < 12 mo | CDC, NHS |
| `bed_share` | warn when true | CDC, AAP, NHS |
| `soft_items_absent` (pillows, blankets, bumpers, toys, wedges, pods, nests) | true | CDC, Lullaby Trust |
| `smoke_exposure_today` | false | [CDC](https://www.cdc.gov/sudden-infant-death/about/index.html), [NHS SIDS](https://www.nhs.uk/baby/caring-for-a-newborn/reduce-the-risk-of-sudden-infant-death-syndrome/) |
| `overheating_check` (chest/neck not hot/sweaty) | true | Lullaby Trust |
| `head_uncovered` | true | NHS, Lullaby Trust |
| `pacifier_offered` | optional; after BF established | CDC |
| `breastfed_feeding` | protective flag | CDC |

### Sleep locations
`location` enum with safety flags:
- Safe: `crib`, `bassinet`, `portable_play_yard`, `bedside_sleeper`, `moses_basket`.
- **Unsafe**: `parent_bed`, `couch_or_armchair`.
- **Not for routine sleep**: `car_seat`, `swing`, `bouncer`, `carrier`, `inclined_sleeper`.

### Swaddle
`swaddled` only with `position = back`. `rolling_signs_observed` → UI locks swaddling and shows "stop swaddling" banner (typically 3-4 mo) ([Lullaby Trust swaddling](https://www.lullabytrust.org.uk/baby-safety/baby-product-information/swaddling/)).

### Total 24h sleep duration ([AASM 2016 consensus](https://pmc.ncbi.nlm.nih.gov/articles/PMC4877308/))

| Age band | Target hours / 24h |
|----------|--------------------|
| 0-3 mo | 14-17 ([WHO under-5 guidelines](https://www.who.int/news/item/24-04-2019-to-grow-up-healthy-children-need-to-sit-less-and-play-more); AASM excludes < 4 mo) |
| 4-12 mo | 12-16 (incl. naps) |
| 1-2 y | 11-14 (incl. naps) |
| 3-5 y | 10-13 (incl. naps) |

Store `target_sleep_min_h` and `target_sleep_max_h`.

### Nap count + wake windows (label as "typical consensus — individual variation")

| Age | Naps typical | Wake window min | max |
|-----|--------------|-----------------|-----|
| 0-3 mo | 4-6 (unstructured) | 45 min | 90 min |
| 3-6 mo | 3-4 | 1.5 h | 2.5 h |
| 6-9 mo | 2-3 | 2 h | 3 h |
| 9-12 mo | 2 | 2.5 h | 3.5 h |
| 12-18 mo | 1-2 | 3 h | 4 h |
| 18-36 mo | 1 | 4 h | 6 h |

### Night-waking expectations (display as normalising text)
- 3 mo: 2-3 wakings typical.
- 6 mo: one or more wakings typical; some self-soothe.
- 9 mo: 1-2 wakings typical; separation-anxiety peak.

Field: `night_wakings_count` int. Do not flag "high" — just record.

### SIDS risk factors (per-baby profile, informational)
Binary inputs: `preterm` (<37 wk), `low_birth_weight` (<2.5 kg), `smoke_exposure_prenatal_or_household`, `ever_stomach_sleeping`, `unsafe_surface_used`, `never_or_not_breastfed`, `alcohol_drug_exposure_prenatal`, `no_prenatal_care`.

**Do not** compute a single numeric SIDS score. Surface a "modifiable factors identified: N" summary instead.

### Sleep regressions
`regression_flagged` bool, `regression_label`. Tag as parent-education: 4 mo, 8-10 mo, 12 mo, 18 mo, 2 y.

### Settling style (parent choice — never prescribe)
`settling_method` enum {extinction, graduated_ferber, chair_method, pick_up_put_down, responsive_settling, co_regulation, other}. UI copy: "These are parent-choice approaches; no guideline endorses one over another."

### Room temperature
`room_temp_c` float. Target 16-20 °C; flag < 16 (cold) or > 20 (overheating risk).

### Sleep association (descriptive)
`sleep_association` enum {fed_to_sleep, rocked_to_sleep, held_to_sleep, in_motion, pacifier_sleep, self_soothed, other}.

## §Growth

### Core anthropometric fields

| Field | Type | Ages | Source |
|-------|------|------|--------|
| `weight_kg` | float (0.01 kg) | all | [WHO](https://www.who.int/tools/child-growth-standards) + [CDC](https://www.cdc.gov/growthcharts/index.htm) |
| `length_cm_supine` | float (0.1 cm) | 0 - <24 mo | WHO |
| `height_cm_standing` | float (0.1 cm) | ≥24 mo | CDC |
| `head_circumference_cm` | float (0.1 cm) | 0 - <36 mo | WHO + AAP Bright Futures |
| `muac_cm` | float (0.1 cm, optional) | 3-59 mo | WHO arm circumference |
| `position_used` | enum {supine, standing} | required | method affects value interpretation |

### Visit schedule (AAP Bright Futures 2025)
Scheduled measurement events: `Newborn, 3-5d, By 1mo, 2mo, 4mo, 6mo, 9mo, 12mo, 15mo, 18mo, 24mo, 30mo, 3y, 4y, 5y…`. Length/Height + Weight at every visit; Head Circumference through 24 mo; Weight-for-Length through 18 mo; BMI starts at 24 mo. Source: [Bright Futures periodicity schedule](https://downloads.aap.org/AAP/PDF/periodicity_schedule.pdf).

### Derived values (store both z-score and percentile)
| Field | Type | Notes |
|-------|------|-------|
| `z_wfa`, `z_lhfa`, `z_wfl`, `z_bmifa`, `z_hcfa` | float | from WHO LMS (0-60 mo) |
| `pct_wfa`, `pct_lhfa`, `pct_wfl`, `pct_bmifa`, `pct_hcfa` | 0-100 | UI-facing |
| `reference_used` | enum {WHO_0_24, CDC_2_20, CDC_EXT_BMI} | CDC extended BMI ≥95th [chart](https://www.cdc.gov/growthcharts/extended-bmi.htm) |

### Red-flag triggers (boolean per measurement)
- `flag_percentile_crossed_2_major_down` — dropped across two major percentile bands (5/10/25/50/75/90/95) vs prior visit.
- `flag_wfl_below_5th` — weight-for-length < 5th percentile.
- `flag_hc_below_3rd_or_above_97th` — z < -1.88 or > +1.88.
- `flag_newborn_weight_loss_gt_10pct` — (`birth_weight - current`) / `birth_weight` > 0.10 in week 1.
- `flag_bmi_above_95th` — switch to CDC extended BMI chart.
- `flag_muac_lt_125mm` / `flag_muac_lt_115mm` — moderate / severe acute malnutrition (WHO).

### Prematurity correction
| Field | Meaning |
|-------|---------|
| `gestational_age_weeks` | 22-42 |
| `chronological_age_days` | derived |
| `corrected_age_days` | `chronological - (40 - GA) × 7` |
| `use_corrected_until` | DOB + 24 mo (preterm <37 wk) |
| `display_mode` | show both labels, plot on WHO using corrected age |

UK-WHO growth charts support 32-36 wk preterms on the main chart: [RCPCH UK-WHO growth charts](https://www.rcpch.ac.uk/resources/uk-who-growth-charts-0-4-years).

### BMI
`bmi = weight_kg / (height_m)^2`. Calculated automatically from 24 mo per AAP periodicity; WHO BMI-for-age is available 0-60 mo but UI surfaces from 24 mo.

### Milestones
Checklist keyed by age bucket (CDC 2022 revision, 75% attainment threshold). Age buckets: 2, 4, 6, 9, 12, 15, 18, 24, 30, 36, 48, 60 months ([CDC Milestones](https://www.cdc.gov/milestones/index.html)).

Four domains: `SOCIAL_EMOTIONAL`, `LANGUAGE_COMMUNICATION`, `COGNITIVE`, `MOVEMENT_PHYSICAL`.

Per-item fields: `item_id`, `age_bucket`, `domain`, `text`, `status` {YES, NOT_YET, NOT_SURE}, `observed_date`.

Red-flag triggers (each field is a boolean surfacing a "talk to the pediatrician" card):

| Age | Trigger |
|-----|---------|
| 2 mo | no smile at people; no reaction to loud sounds; does not watch/track |
| 4 mo | does not bring hands to mouth; does not coo; does not push up on tummy |
| 6 mo | does not try to get things in reach; no affection for caregivers |
| 9 mo | no babble; does not respond to name; does not sit without support |
| 12 mo | no gestures (wave/point); does not search for hidden object; no single words |
| 15 mo | does not try to say 1-3 words besides mama/dada; does not copy other children |
| 18 mo | **does not walk**; does not try to say 3+ words; no notice when caregiver leaves/returns |
| 24 mo | **does not use 2-word phrases**; does not know what to do with common objects; loses skills |
| 30 mo | does not use 2-3 word sentences; cannot follow simple instructions |
| 36 mo | unclear speech; not playing pretend |
| 48 mo | cannot retell favorite story; ignores other children |
| 60 mo | cannot give first and last name; superficial response only |

Universal: `flag_lost_skill` boolean at any age. Source: individual CDC age pages under [cdc.gov/act-early/milestones/](https://www.cdc.gov/act-early/milestones/).

### WHO gross-motor windows (2006)
Store empirical 1st-99th percentile windows for: sitting without support, hands-and-knees crawling, standing with assistance, walking with assistance, standing alone, walking alone. Flag when observed age > 99th percentile age. Source: [WHO motor development milestones](https://www.who.int/tools/child-growth-standards/standards/motor-development-milestones).

### Autism screening (M-CHAT-R/F)
Entity: `age_months` 16-30, `score` 0-20, `risk_band` {LOW 0-2, MEDIUM 3-7, HIGH 8-20}. Scheduled at 18 mo and 24 mo per AAP Bright Futures. Source: [M-CHAT-R/F](https://mchatscreen.com/mchat-rf/).

### Vision / hearing flags
- `newborn_hearing_screen_result` enum {PASS, REFER, NOT_DONE}. Flag REFER.
- `newborn_cchd_pulse_ox` same.
- Objective vision screen at 4 y and 5 y.
- Hearing screen flags at 4 / 5 / 6 / 8 / 10 y per Bright Futures.

## §Diapers

### Wet + dirty count thresholds
Fields: `wet_count_24h`, `dirty_count_24h`.

| Age / day of life | Target | Flag |
|-------------------|--------|------|
| Day 1 | ≥ 1 wet | < 1 |
| Day 2 | ≥ 2 wet | < 2 |
| Day 3 | ≥ 3 wet | < 3 |
| Day 5+ | ≥ 6 heavy wet / 24h | < 6 |
| 1 week+ | ≥ 8 wet / 24h (NHS) | < 8 |
| Breastfed newborn | ≥ 2 soft yellow stools / day from day 4 | < 2 |

Source: [NHS enough milk](https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/breastfeeding-problems/enough-milk/).

### Stool colour (infant-specific, NOT Bristol stool chart < 3 y)
`stool_color` enum with red-flag mapping:
- Normal: `yellow_mustard` (BF), `tan_brown` (FF), `green` (after day 5).
- Meconium: `black_tarry` — expected days 0-2 only.
- **Red flags** (surface "call clinician" card):
  - `red_blood`
  - `black_melena` (after day 5, not meconium)
  - `white_pale_chalky` / `acholic` → possible biliary atresia, time-critical ([CLDF Yellow Alert Stool Chart](https://childliverdisease.org/wp-content/uploads/2019/04/CLDF-Yellow-Alert-Stool-Chart.pdf), [AAP Many Colors of Poop](https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx)).

### Stool consistency
`stool_consistency` enum {seedy (BF), pasty, formed, hard_pellets, watery}. Do not use Bristol stool chart under ~3 y.

### Dehydration signals (`dehydration_flags[]`)
Flags: < 6 wet nappies / 24 h after day 5, dark concentrated urine, dry mouth / mucous membranes, sunken fontanelle, sunken eyes, no tears, lethargy / irritability, decreased skin turgor, cold mottled extremities (shock) ([NICE CG84](https://www.nice.org.uk/guidance/cg84/chapter/Recommendations)).

### Diarrhoea red flags (`diarrhea_alert` bool)
- More than 5 diarrhoeal stools / 24 h (NICE CG84 — more conservative than the "6" sometimes quoted).
- Blood / mucus in stool → consider stool microbiology.
- Temp ≥ 38 °C < 3 mo or ≥ 39 °C ≥ 3 mo → non-GE cause.
- **Bilious (green) vomit** → urgent referral.

### Constipation (`constipation_flag` bool)
Flag if < 3 stools / week AND hard / dry / pellet consistency, painful straining, or blood streaks. Soft infrequent stools in breastfed babies after 6 weeks are NOT constipation (breastfed babies may normally go 7-10 days) ([AAP Infant Constipation](https://www.healthychildren.org/English/ages-stages/baby/diapers-clothing/Pages/Infant-Constipation.aspx)).

### Nappy rash (`rash_severity` enum {none, mild, moderate, severe}, `treatment[]`)
NHS / AAP: change frequently, air-dry, barrier cream (zinc oxide). GP if spreads, blisters, bleeds, or lasts > 7 days ([NHS nappy rash](https://www.nhs.uk/conditions/nappy-rash/)).

### UTI red flags (`uti_flags[]`)
Fever without source (especially < 3 mo), foul-smelling urine, crying on urination, poor feeding, vomiting. NICE NG224: possible UTI < 3 mo → immediate paediatric referral ([NICE NG224](https://www.nice.org.uk/guidance/ng224)).

### Optional tracking
`brand` string, `size` enum {N, 1…6}, `leak_bool`.

## §Vaccines

### US schedule (CDC/ACIP, 0-24 mo)
Fields per dose: `name`, `dose_num`, `age_given`, `lot`, `site`. [CDC Child / Adolescent Immunization Schedule](https://www.cdc.gov/vaccines/hcp/imz-schedules/child-adolescent-age.html).

| Age | Doses |
|-----|-------|
| Birth | HepB #1 |
| 1-2 mo | HepB #2 |
| 2 mo | DTaP #1, Hib #1, PCV15/PCV20 #1, IPV #1, RV #1 |
| 4 mo | DTaP #2, Hib #2, PCV #2, IPV #2, RV #2 |
| 6 mo | DTaP #3, PCV #3, RV #3 (if RV5), HepB #3, IPV #3, flu (annual ≥ 6 mo), COVID per ACIP |
| 12-15 mo | MMR #1, Varicella #1, Hib booster, PCV #4, HepA #1 |
| 15-18 mo | DTaP #4, HepA #2 |
| Seasonal | RSV nirsevimab / clesrovimab (Oct-Mar, < 8 mo) if not maternally protected ([CDC RSV](https://www.cdc.gov/rsv/hcp/vaccine-clinical-guidance/infants-young-children.html)) |

### UK schedule (NHS) ([NHS vaccinations](https://www.nhs.uk/vaccinations/nhs-vaccinations-and-when-to-have-them/))

| Age | Doses |
|-----|-------|
| 8 wk | 6-in-1 #1, Rotavirus #1, MenB #1 |
| 12 wk | 6-in-1 #2, Rotavirus #2, MenB #2 (+PCV #1 in some) |
| 16 wk | 6-in-1 #3, PCV #1 |
| 1 y | MMRV #1 (births from 1 Jan 2025), PCV #2, MenB #3, Hib/MenC |
| 3 y 4 m | 4-in-1 preschool booster, MMRV #2 |
| Maternal | RSV from 28 wk pregnancy |

### Australia schedule (NIP) ([Australian NIP schedule](https://www.health.gov.au/topics/immunisation/when-to-get-vaccinated/national-immunisation-program-schedule))

| Age | Doses |
|-----|-------|
| Birth | HepB |
| 2 mo | DTPa-IPV-HepB-Hib, PCV13, Rota #1 |
| 4 mo | + Rota #2 |
| 6 mo | DTPa-IPV-HepB-Hib, PCV13, flu (annual ≥ 6 mo) |
| 12 mo | MenACWY, MMR, PCV13 (ATSI) |
| 18 mo | MMRV, DTPa, Hib |
| 4 y | DTPa-IPV |
| ATSI children | extra HepA + MenB |

### Birth doses (`birth_doses[]`)
Universal HepB (US, AU). UK: none universal at birth. BCG in high-TB countries (WHO list).

### Catch-up (`min_interval_days`)
Store minimum intervals per CDC catch-up table (e.g. DTaP #1 → #2: 4 wk; #3 → #4: 6 mo).

### Adverse event log (`ae`: struct {type, onset_hours, severity, reported})
Types: injection-site redness / swelling, fever, irritability, anaphylaxis (emergency), seizure. Reporting URLs:
- US: [VAERS](https://vaers.hhs.gov/reportevent.html)
- UK: [Yellow Card](https://yellowcard.mhra.gov.uk/)
- AU: [AusVaxSafety / SmartVax](https://www.tga.gov.au/reporting-problems)

### Seasonal / passive immunisation
- RSV nirsevimab: Oct-Mar northern hemisphere, < 8 mo entering first season; or maternal RSV (Pfizer Abrysvo) at 32-36 wk gestation US, 28 wk+ UK/AU.
- Flu: annual from 6 mo.
- COVID-19: per current ACIP (shared clinical decision-making tier as of Jan 2026).

### Next-dose computation (`next_due_date` computed)
`max(min_age_for_dose, last_dose_date + min_interval)`.

### Machine-readable data
- [WHO immunisation data portal](https://immunizationdata.who.int/) offers CSV downloads (no public JSON REST API).
- CDC: PDF canonical; no JSON API.
- App ships hand-curated JSON per country; versioned update check on user demand.

### Notable recent changes (2024-2026)
- UK: MMRV replaced separate MMR for births from 1 Jan 2025 — schema must key off region + birthdate.
- Jan 2026 CDC update re-categorised RSV, HepA/B, flu, COVID, rotavirus into high-risk / shared-clinical-decision tiers. Render a "category" field per recommendation.

## §Fever

### Fever thresholds by age
| Age | Trigger | Source |
|-----|---------|--------|
| < 3 mo | any ≥ 38.0 °C → URGENT | [NICE NG143](https://www.nice.org.uk/guidance/ng143/chapter/Recommendations) §1.2.12, [AAP](https://www.healthychildren.org/English/health-issues/conditions/fever/Pages/When-to-Call-the-Pediatrician.aspx) |
| 3-6 mo | ≥ 39.0 °C → intermediate risk | NICE §1.2.13 |
| > 6 mo | temp alone not diagnostic — use traffic-light | NICE §1.2.11 |
| any | ≥ 40.0 °C persistently → call clinician | AAP |

### Measurement route (NICE §1.1)
| Age | Allowed routes |
|-----|----------------|
| < 4 weeks | axillary electronic thermometer only |
| 4 wk - 5 y | axillary OR tympanic OR chemical dot |
| routine 0-5 y | oral / rectal NOT recommended |

`thermometer_type` required for audit.

### Paracetamol / ibuprofen dosing
Fields per dose: `name`, `strength_mg_per_ml`, `dose_mg` (computed), `dose_ml` (computed), `route`, `given_at`, `weight_kg_used`, `weight_source`, `next_safe_dose_at` (computed), `daily_cumulative_mg` (rolling 24h), `over_max_flag`.

| Drug | Dose | Interval | Max / 24h | Min age | Min weight |
|------|------|----------|-----------|---------|------------|
| Paracetamol | 10-15 mg/kg | ≥ 4h | 4 doses, 60 mg/kg/day (BNFc) | ≥ 2 mo | — |
| Ibuprofen | 5-10 mg/kg | 6-8h | 3-4 doses | ≥ 3 mo | ≥ 5 kg |
| Aspirin | — | — | — | **blocked < 16 y (Reye's)** | — |

Do **not** give paracetamol + ibuprofen simultaneously; alternate only if distress persists before the next dose ([NICE NG143 §1.6.6](https://www.nice.org.uk/guidance/ng143/chapter/Recommendations)).

Calculator formulas:

```
Paracetamol (oral)
dose_mg        = clamp(weight_kg * 15, min=60, max=1000)
next_safe_dose = given_at + 4h
daily_cap_mg   = weight_kg * 60
block if sum_mg_24h + dose_mg > daily_cap_mg OR doses_24h >= 4 OR age_months < 2

Ibuprofen (oral, with food)
dose_mg        = clamp(weight_kg * 7.5, min=50, max=400)
next_safe_dose = given_at + 6h
daily_cap_mg   = weight_kg * 30
block if age_months < 3 OR weight_kg < 5
block if sum_mg_24h + dose_mg > daily_cap_mg
```

Weight source priority: `last_measured (≤ 30 d)` → `parent_entered` → block save.

### Traffic-light red flags (NICE NG143 Table 2 in-app checklist)
`pale_mottled_ashen_blue`, `unresponsive_or_hard_to_rouse`, `weak_high_pitched_cry`, `grunting`, `apnoea`, `resp_rate_gt_60`, `tachycardia_for_age` (<12mo >160; 12-24mo >150; 2-5y >140 bpm), `reduced_skin_turgor`, `non_blanching_rash` (glass test), `stiff_neck`, `photophobia`, `bulging_fontanelle`, `seizure`. Each true flag triggers the "call 111 / 999" card.

### Dehydration signals (cross-linked with diarrhoea/vomit)
`sunken_fontanelle`, `dry_mouth`, `sunken_eyes`, `no_tears_when_crying`, `reduced_urine_output`, `cap_refill_gt_3s`. Auto-flag if ≥2 true OR paired with `vomit_events_24h ≥ 3` / `diarrhoea_events_24h ≥ 6` ([NICE §1.7.2](https://www.nice.org.uk/guidance/ng143/chapter/Recommendations)).

## §Activity

### Tummy time (AAP + WHO)
| Field | Target |
|-------|--------|
| `tummy_time_session_minutes` | start day of discharge; 2-3 sessions × 3-5 min |
| `tummy_time_daily_total_min` | work up to 15-30 min/day by 7 weeks |
| `tummy_time_7d_avg` | flag if < 30 min / day after 7 weeks ([AAP Tummy to Play](https://www.healthychildren.org/English/ages-stages/baby/sleep/Pages/back-to-sleep-tummy-to-play.aspx)) |

### Screen time ([WHO under-5 guidelines](https://www.ncbi.nlm.nih.gov/books/NBK541169/))
| Age | Max screen min / day |
|-----|----------------------|
| < 1 y | 0 (hard flag) |
| 1 y | 0 |
| 2 y | ≤ 60 |
| 3-4 y | ≤ 60 (less is better) |

### Sedentary restraint
| Field | Rule |
|-------|------|
| `restraint_session_minutes` (stroller/high-chair/car-seat/carrier) | flag at 60 |
| `restraint_event_type` | stroller / high_chair / car_seat / baby_carrier / bouncer |

### Physical activity target
| Age | Target |
|-----|--------|
| < 1 y | several times / day interactive floor play + tummy time |
| 1-2 y | ≥ 180 min / day any intensity |
| 3-4 y | ≥ 180 min / day, ≥ 60 min MVPA |

Fields: `active_minutes_day`, `mvpa_minutes_day`, `activity_type` enum {floor_play, crawl, walk, run, climb, outdoor}.

### Outdoor / vitamin D
`outdoor_minutes_day` (soft target ≥ 60 min / day). NHS vitamin D supplement: 400 IU / day < 1 y and 1-4 y ([NHS vitamin D](https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-d/)).

### Motor-milestone hooks (AAP)
Booleans: `rolls_tummy_to_back` (~4 mo), `rolls_back_to_tummy` (~6 mo), `sits_unsupported` (~6-9 mo), `crawls` (~9 mo), `pulls_to_stand` (~12 mo), `walks_independently` (~12-18 mo). Referral flags: no rolling by 6 mo, no sitting by 9 mo, no walking by 18 mo.

### Plagiocephaly risk
Computed `plagio_risk_score` = `restraint_minutes_day > 180` OR `tummy_time_daily_total_min < 30` after 8 weeks OR `back_sleeping_only_no_head_rotation`. Surfaces AAP rotation strategies ([Back to Sleep / Tummy to Play](https://www.healthychildren.org/English/ages-stages/baby/sleep/Pages/back-to-sleep-tummy-to-play.aspx)).

## Implementation outline

### Data model
The tracker reuses the existing `events.db` SQLite store (events table, added on the `maxtt` branch for CM-TIME-001 / CM-STORE-003). Each tracker event is a row in `tracker_events` with columns:

| column | type | notes |
|--------|------|-------|
| id | INTEGER PK | |
| child_id | TEXT | user-assigned id; supports multi-child |
| ts_wall_ms | INTEGER | `System.currentTimeMillis()` at entry |
| ts_mono_ms | INTEGER | `MonotonicClock.nowMs()` for duration math |
| kind | TEXT | see enum below |
| subkind | TEXT | e.g. feed kind = BREAST_L / BREAST_R / BOTTLE / PUMP |
| value_num | REAL | primary numeric payload (ml, grams, minutes, temp °C) |
| value_num2 | REAL | secondary payload (e.g. left side min vs right side min) |
| started_ms | INTEGER | open events (sleep, feeding, talkback) |
| ended_ms | INTEGER | null until closed |
| extra_json | TEXT | structured overflow (allergen_dose, med_route, stool_color) |
| source | TEXT | DEVICE_MONITOR / DEVICE_TRACKER / USER_ENTRY / IMPORT |
| note | TEXT | parent-entered free text |
| trace_id | TEXT | links to monitor trace id |

Event `kind` enum:
`FEED_BREAST`, `FEED_BOTTLE`, `FEED_PUMP`, `FEED_SOLID`, `FEED_ALLERGEN`,
`DIAPER_WET`, `DIAPER_DIRTY`, `DIAPER_MIXED`,
`SLEEP_START`, `SLEEP_END`, `NAP_START`, `NAP_END`,
`GROWTH_WEIGHT`, `GROWTH_LENGTH`, `GROWTH_HEAD`, `GROWTH_BMI`,
`MILESTONE`, `VACCINE`, `MEDICATION`, `TEMPERATURE`, `ILLNESS`,
`ACTIVITY_TUMMY`, `ACTIVITY_OUTDOOR`, `ACTIVITY_SCREEN`,
`NOTE`, `PHOTO`.

### Profile
Per-child profile stored in `child_profiles` (id, name, date_of_birth_ms, due_date_ms, sex, any flags: preterm, high_allergy_risk, feeding_mode). `date_of_birth_ms` drives every age-based threshold; `due_date_ms` drives corrected age for preterms.

### Thresholds / guidance JSON
Thresholds are shipped as a single JSON resource (`assets/tracker/guidance.json`) so we can update guidance without changing code. The file carries:

```json
{
  "source_revision": "2026-04-18",
  "fever": { "under_3mo_urgent_c": 38.0, ... },
  "diaper": { "wet_by_day_of_life": [1,2,3,4,5,6,6] },
  "milestones": { "2mo": [...], "4mo": [...] },
  "vaccines": { "US": [...], "UK": [...], "AU": [...] }
}
```

### UI surfaces
- `BabyTimelineActivity` — chronological feed of tracker events (reuses the
  existing `TimelineActivity`).
- `BabyDashboardActivity` — today's counters: last feed, last nap, diaper count,
  next vaccine due, next tummy time window.
- `GrowthChartActivity` — plots height/weight/head circumference against
  WHO/CDC percentiles rendered from the shipped LMS tables.
- `AllergenLadderActivity` — one card per major allergen, with the current
  status (NOT_STARTED / IN_PROGRESS / ESTABLISHED / REACTION_REPORTED).
- `VaccineActivity` — next-dose calculator driven by the jurisdiction the
  parent selects.

### Privacy & offline
- All data stays on-device by default.
- Optional encrypted export via the existing `ConfigExportImport` format
  (maxtt branch).
- No telemetry leaves the device. Guidance JSON is bundled; periodic updates
  require an explicit user action ("Check for guideline updates").

## Citations policy
Every recommendation must have at least one URL citation pointing at the
primary source. Cite the official PDF or guidance page, not a news summary.
If guidance differs by jurisdiction, list both and let the parent pick.

## Interop alignment

### Android Health Connect
Where possible, tracker events that map to a standard Health Connect type use
the same fields + units so we can later expose a read/write bridge:

| Tracker event | Health Connect record |
|---------------|-----------------------|
| `GROWTH_WEIGHT` | `WeightRecord` (kg) |
| `GROWTH_LENGTH` / `GROWTH_HEIGHT` | `HeightRecord` (m) |
| `TEMPERATURE` | `BodyTemperatureRecord` |
| `SLEEP_*` | `SleepSessionRecord` |
| `FEED_BOTTLE` volume | `HydrationRecord` (mL) |
| `FEED_SOLID` | `NutritionRecord` |
| `ACTIVITY_*` | `ExerciseSessionRecord` |

Head circumference, diaper events, pumping output, and medication doses have
**no** Health Connect analogue — they stay app-local. Source: [Health Connect data types](https://developer.android.com/health-and-fitness/health-connect/data-types).

### FHIR / LOINC (future healthcare export)
For the "share with pediatrician" PDF export we align with the US Core
pediatric profiles so the printable summary matches what a chart ingest would
expect:

| Measure | LOINC | FHIR profile |
|---------|-------|--------------|
| Body weight | 29463-7 | [US Core Vital Signs](https://hl7.org/fhir/observation-vitalsigns.html) |
| Body length / height | 8302-2 | US Core Vital Signs |
| Head circumference | 9843-4 / 8289-1 (percentile) | US Core Pediatric Head OFC Percentile |
| Weight-for-length | 77606-2 | [US Core Pediatric Weight for Height](https://hl7.org/fhir/us/core/STU5.0.1/StructureDefinition-pediatric-weight-for-height.html) |
| Temperature | 8310-5 | FHIR Observation |
| BMI | 39156-5 | FHIR Observation |

### Existing reference implementations reviewed
- [archiewood/baby-tracker](https://github.com/archiewood/baby-tracker) — Evidence / Svelte dashboard that ingests Huckleberry exports and compares against guidelines. Confirms the "ingest + compare" pattern we're adopting for the export bundle.
- [Nara Baby](https://naraorganics.com/nara-baby/) and [Huckleberry](https://huckleberrycare.com/) — feature set review informed by the [BetterOO 2026 guide](https://betteroo.ai/guides/best-baby-tracker-app/).

## Implementation backlog issues

Each module below maps to a GitHub issue in `4eh5xitv6787h645ebv/child-monitor` once the feature work begins. Placeholders:

- CM-TRK-001 Feeding sessions + supply log
- CM-TRK-002 Allergen ladder + reactions
- CM-TRK-003 Safe-sleep banner + sleep sessions
- CM-TRK-004 Growth + WHO/CDC LMS bundled chart
- CM-TRK-005 CDC 2022 milestone checklist
- CM-TRK-006 Diaper + stool colour red-flag
- CM-TRK-007 Vaccine schedules (US / UK / AU) + next-due
- CM-TRK-008 Fever + medication dosing calculator
- CM-TRK-009 Activity + tummy time + plagio risk
- CM-TRK-010 Multi-child profile + prematurity correction
- CM-TRK-011 Health Connect bridge (weight / height / temp / sleep / hydration)
- CM-TRK-012 Shareable pediatrician PDF with LOINC-aligned fields

## Change log

- 2026-04-18 — Initial compilation. Research agents reported across 8 modules; doc now self-contained with primary citations.
