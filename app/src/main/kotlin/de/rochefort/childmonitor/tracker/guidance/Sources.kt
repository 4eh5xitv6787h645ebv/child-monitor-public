package de.rochefort.childmonitor.tracker.guidance

/**
 * Single source of truth for every citation the tracker surfaces in the UI.
 *
 * Grouped by module so the in-app Sources screen matches the layout of
 * docs/research/baby-tracker-evidence-base.md. When a new guideline is added
 * to a guidance engine, add its URL here too.
 */
object Sources {

    data class Source(val title: String, val url: String, val note: String = "")
    data class Group(val heading: String, val description: String, val items: List<Source>)

    val ALL: List<Group> = listOf(
        Group(
            heading = "Feeding (breast, bottle, pumping)",
            description = "WHO / CDC / AAP / NHS / NHMRC guidance used by the Feed entry screen and Feeding targets.",
            items = listOf(
                Source("WHO Infant and young child feeding / breastfeeding",
                    "https://www.who.int/health-topics/breastfeeding"),
                Source("WHO Guideline: complementary feeding 6-23 months (2023)",
                    "https://www.who.int/publications/i/item/9789240081864"),
                Source("CDC Breast-milk preparation and storage",
                    "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
                Source("CDC Infant formula preparation and storage",
                    "https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html"),
                Source("CDC Alcohol while breastfeeding",
                    "https://www.cdc.gov/breastfeeding-special-circumstances/hcp/vaccine-medication-drugs/alcohol.html"),
                Source("AAP: how often and how much should your baby eat",
                    "https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/how-often-and-how-much-should-your-baby-eat.aspx"),
                Source("AAP: formula schedule and volumes",
                    "https://www.healthychildren.org/English/ages-stages/baby/formula-feeding/Pages/amount-and-schedule-of-formula-feedings.aspx"),
                Source("AAP: vitamin D and iron supplementation",
                    "https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Vitamin-Iron-Supplements.aspx"),
                Source("NHS: making up baby formula",
                    "https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/bottle-feeding/making-up-baby-formula/"),
                Source("LactMed (maternal medications)",
                    "https://www.ncbi.nlm.nih.gov/books/NBK501922/",
                    note = "Look up drug-specific safety before nursing; not cached in the app."),
                Source("La Leche League (LATCH, paced bottle feeding)",
                    "https://lllusa.org/bfinfo/")
            )
        ),
        Group(
            heading = "Solid food + allergen introduction",
            description = "LEAP / NIAID / ASCIA / FARE / AAP / NHS / WHO guidance behind the Allergen ladder and solids advice.",
            items = listOf(
                Source("NIAID Addendum Guidelines: peanut allergy prevention",
                    "https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/"),
                Source("LEAP trial (Randomized Trial of Peanut Consumption in Infants)",
                    "https://pmc.ncbi.nlm.nih.gov/articles/PMC4416404/"),
                Source("LEAP - NEJM listing",
                    "https://www.nejm.org/doi/full/10.1056/NEJMoa1414850"),
                Source("ASCIA: how to introduce solid foods to babies",
                    "https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies"),
                Source("ASCIA Action Plan (allergic reactions)",
                    "https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf"),
                Source("CDC: when/what/how to introduce solid foods",
                    "https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/when-what-and-how-to-introduce-solid-foods.html"),
                Source("CDC: foods & drinks to avoid or limit",
                    "https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/foods-and-drinks-to-avoid-or-limit.html"),
                Source("CDC: choking hazards",
                    "https://www.cdc.gov/infant-toddler-nutrition/foods-and-drinks/choking-hazards.html"),
                Source("AAP: starting solid foods",
                    "https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/Starting-Solid-Foods.aspx"),
                Source("AAP: allergen introduction tips",
                    "https://www.healthychildren.org/English/healthy-living/nutrition/Pages/when-to-introduce-egg-peanut-butter-and-other-common-food-allergens-to-your-baby-food-allergy-prevention-tips.aspx"),
                Source("AAP: choking prevention",
                    "https://www.healthychildren.org/English/health-issues/injuries-emergencies/Pages/Choking-Prevention.aspx"),
                Source("NHS: first foods from around 6 months",
                    "https://www.nhs.uk/best-start-in-life/baby/weaning/what-to-feed-your-baby/from-around-6-months/"),
                Source("NHS: foods to avoid giving babies and young children",
                    "https://www.nhs.uk/baby/weaning-and-feeding/foods-to-avoid-giving-babies-and-young-children/"),
                Source("WHO Complementary Feeding 2023 (NCBI)",
                    "https://www.ncbi.nlm.nih.gov/books/NBK596423/"),
                Source("FARE: peanut early introduction",
                    "https://www.foodallergy.org/resources/peanut-early-introduction-guidelines")
            )
        ),
        Group(
            heading = "Safe sleep and naps",
            description = "AAP 2022 / CDC / NICHD / NHS / Lullaby Trust / AASM guidance powering the Sleep entry screen.",
            items = listOf(
                Source("AAP Safe Sleep landing",
                    "https://www.aap.org/en/patient-care/safe-sleep/"),
                Source("AAP HealthyChildren: parents' guide to safe sleep",
                    "https://healthychildren.org/English/ages-stages/baby/sleep/Pages/A-Parents-Guide-to-Safe-Sleep.aspx"),
                Source("AAP HealthyChildren: back to sleep, tummy to play",
                    "https://www.healthychildren.org/English/ages-stages/baby/sleep/Pages/back-to-sleep-tummy-to-play.aspx"),
                Source("CDC helping babies sleep safely",
                    "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
                Source("CDC about SUID / SIDS",
                    "https://www.cdc.gov/sudden-infant-death/about/index.html"),
                Source("NICHD Safe to Sleep",
                    "https://safetosleep.nichd.nih.gov/safe-sleep"),
                Source("NHS reducing the risk of SIDS",
                    "https://www.nhs.uk/baby/caring-for-a-newborn/reduce-the-risk-of-sudden-infant-death-syndrome/"),
                Source("Lullaby Trust safer-sleep advice",
                    "https://www.lullabytrust.org.uk/safer-sleep-advice/"),
                Source("Lullaby Trust room temperature",
                    "https://www.lullabytrust.org.uk/safer-sleep-advice/room-temperature/"),
                Source("Lullaby Trust swaddling guidance",
                    "https://www.lullabytrust.org.uk/baby-safety/baby-product-information/swaddling/"),
                Source("AASM 2016 pediatric sleep-duration consensus",
                    "https://pmc.ncbi.nlm.nih.gov/articles/PMC4877308/"),
                Source("WHO under-5 movement / sleep guidelines",
                    "https://www.who.int/news/item/24-04-2019-to-grow-up-healthy-children-need-to-sit-less-and-play-more")
            )
        ),
        Group(
            heading = "Growth, percentiles, milestones",
            description = "WHO / CDC / RCPCH / Bright Futures / M-CHAT behind growth charts and milestone flags.",
            items = listOf(
                Source("WHO Child Growth Standards (0-5 y)",
                    "https://www.who.int/tools/child-growth-standards"),
                Source("WHO motor development milestones (windows)",
                    "https://www.who.int/tools/child-growth-standards/standards/motor-development-milestones"),
                Source("CDC Growth Charts",
                    "https://www.cdc.gov/growthcharts/index.htm"),
                Source("CDC Extended BMI chart (2022)",
                    "https://www.cdc.gov/growthcharts/extended-bmi.htm"),
                Source("CDC Learn the Signs milestones (2022 revision)",
                    "https://www.cdc.gov/milestones/index.html"),
                Source("RCPCH UK-WHO growth charts (0-4 y)",
                    "https://www.rcpch.ac.uk/resources/uk-who-growth-charts-0-4-years"),
                Source("AAP Bright Futures periodicity schedule",
                    "https://downloads.aap.org/AAP/PDF/periodicity_schedule.pdf"),
                Source("M-CHAT-R/F autism screen (18 and 24 mo)",
                    "https://mchatscreen.com/mchat-rf/")
            )
        ),
        Group(
            heading = "Diapers, stool colour, elimination",
            description = "NHS / NICE / AAP / CLDF citations behind the diaper red-flag logic.",
            items = listOf(
                Source("NHS: enough milk (wet-count-by-day-of-life)",
                    "https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/breastfeeding-problems/enough-milk/"),
                Source("NICE CG84: diarrhoea and vomiting in under-5s",
                    "https://www.nice.org.uk/guidance/cg84/chapter/Recommendations"),
                Source("NICE NG224: UTI in under-16s",
                    "https://www.nice.org.uk/guidance/ng224"),
                Source("AAP HealthyChildren: many colours of poop",
                    "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx"),
                Source("AAP HealthyChildren: infant constipation",
                    "https://www.healthychildren.org/English/ages-stages/baby/diapers-clothing/Pages/Infant-Constipation.aspx"),
                Source("NHS nappy rash",
                    "https://www.nhs.uk/conditions/nappy-rash/"),
                Source("CLDF Yellow Alert stool chart (biliary atresia)",
                    "https://childliverdisease.org/wp-content/uploads/2019/04/CLDF-Yellow-Alert-Stool-Chart.pdf")
            )
        ),
        Group(
            heading = "Vaccines (US, UK, Australia)",
            description = "Primary schedule URLs and reporting endpoints behind the next-dose calculator.",
            items = listOf(
                Source("CDC Child / Adolescent Immunization Schedule",
                    "https://www.cdc.gov/vaccines/hcp/imz-schedules/child-adolescent-age.html"),
                Source("CDC RSV infant and young children guidance",
                    "https://www.cdc.gov/rsv/hcp/vaccine-clinical-guidance/infants-young-children.html"),
                Source("NHS UK vaccinations schedule",
                    "https://www.nhs.uk/vaccinations/nhs-vaccinations-and-when-to-have-them/"),
                Source("Australian National Immunisation Program schedule",
                    "https://www.health.gov.au/topics/immunisation/when-to-get-vaccinated/national-immunisation-program-schedule"),
                Source("WHO Immunization Data portal (CSV)",
                    "https://immunizationdata.who.int/"),
                Source("VAERS - report a vaccine reaction (US)",
                    "https://vaers.hhs.gov/reportevent.html"),
                Source("Yellow Card - report a vaccine reaction (UK)",
                    "https://yellowcard.mhra.gov.uk/"),
                Source("TGA - reporting problems (Australia)",
                    "https://www.tga.gov.au/reporting-problems")
            )
        ),
        Group(
            heading = "Fever, medication, temperature",
            description = "NICE / NHS / AAP / BNFc behind the fever evaluator and dose calculator.",
            items = listOf(
                Source("NICE NG143: fever in under-5s",
                    "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"),
                Source("NHS fever in children",
                    "https://www.nhs.uk/conditions/fever-in-children/"),
                Source("NHS paracetamol for children",
                    "https://www.nhs.uk/medicines/paracetamol-for-children/"),
                Source("AAP HealthyChildren: when to call about fever",
                    "https://www.healthychildren.org/English/health-issues/conditions/fever/Pages/When-to-Call-the-Pediatrician.aspx"),
                Source("AAP HealthyChildren: acetaminophen dosing for fever",
                    "https://www.healthychildren.org/English/safety-prevention/at-home/medication-safety/Pages/Acetaminophen-for-Fever-and-Pain.aspx"),
                Source("NHS Reye's syndrome (aspirin under 16)",
                    "https://www.nhs.uk/conditions/reyes-syndrome/"),
                Source("MedlinePlus: pediatric acetaminophen",
                    "https://medlineplus.gov/ency/patientinstructions/000783.htm"),
                Source("MedlinePlus: pediatric ibuprofen",
                    "https://medlineplus.gov/ency/patientinstructions/000772.htm")
            )
        ),
        Group(
            heading = "Activity, tummy time, plagiocephaly",
            description = "WHO / AAP / NHS guidance used by activity targets and safe-sleep advisories.",
            items = listOf(
                Source("WHO guidelines on physical activity, sedentary behaviour and sleep for children under 5 (NCBI)",
                    "https://www.ncbi.nlm.nih.gov/books/NBK541169/"),
                Source("AAP HealthyChildren: back to sleep, tummy to play",
                    "https://www.healthychildren.org/English/ages-stages/baby/sleep/Pages/back-to-sleep-tummy-to-play.aspx"),
                Source("NHS vitamins and minerals - vitamin D",
                    "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-d/")
            )
        ),
        Group(
            heading = "Interoperability (for future export)",
            description = "Android Health Connect and FHIR / LOINC profiles the tracker schema aligns with.",
            items = listOf(
                Source("Android Health Connect - data types",
                    "https://developer.android.com/health-and-fitness/health-connect/data-types"),
                Source("FHIR Observation - Vital Signs",
                    "https://hl7.org/fhir/observation-vitalsigns.html"),
                Source("US Core Pediatric Weight for Height Observation",
                    "https://hl7.org/fhir/us/core/STU5.0.1/StructureDefinition-pediatric-weight-for-height.html"),
                Source("US Core Pediatric Head OFC Percentile",
                    "https://hl7.org/fhir/us/core/STU5.0.1/StructureDefinition-head-occipital-frontal-circumference-percentile.html")
            )
        )
    )

    val COUNT: Int get() = ALL.sumOf { it.items.size }
}
