# Localization policy

Child Monitor currently ships one complete user-interface locale: English (`en`).

Older German, Japanese, Dutch, and Brazilian Portuguese resource files translated only 31 of
more than 700 user-facing resources. Android silently filled the rest from English, so those
devices displayed a mixed-language interface—including pairing, monitoring, settings, alerts,
and health-tracker flows. Those incomplete locale sets were removed from the current development
line rather than continuing to present them as supported.

The default catalog remains fully translator-ready: dynamic values use formatted strings or
plurals, and non-language values are explicitly marked `translatable="false"`. Android lint is a
release-blocking CI gate for `MissingTranslation`, `SetTextI18n`, and `HardcodedText`.

## Adding a language

A locale can be restored when all of these checks are complete:

1. Translate every translatable resource in `app/src/main/res/values/strings.xml`, including all
   plural quantities required by the language.
2. Have a fluent reviewer check safety-critical terminology for pairing, disconnects, alerts,
   medication, temperature, and privacy/security states.
3. Run `./gradlew :app:lintRelease :app:testReleaseUnitTest` and confirm zero findings for the
   three localization lint gates.
4. Exercise setup, QR pairing, child and parent monitoring, reconnect, alerts, settings, and the
   tracker with the locale selected. Also smoke-test Android's `en-XA` pseudo-locale for expansion
   and `ar-XB` for bidirectional layout before release.

Do not add a partial locale resource set. Translation work may be staged outside `res/`, but a
language is supported only once its complete reviewed catalog and flow evidence can ship together.
