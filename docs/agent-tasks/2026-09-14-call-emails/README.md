# Call email templates — developer handoff

Verified locally on 14 September 2026 after clean dev-backed reconstruction:
17 focused tests passed; package and Spotless passed. Independent Standards and Spec
source reviews found no remaining blocking extraction finding. This is not combined
integration, browser, deployment or mailbox acceptance.

Owner: **Shazia (`shazia-k`)**. Separate source-only draft based on dev
`57570b8ef95fccc1018165cb961564dfb52142f1`.

Includes 18 previously local HTML/plain-text templates: call invitation, reminder and
missed-call variants in German formal/informal and English; catalogue entries and
renderer tests. This package does not establish send triggers or recipient contracts.

```sh
# Java 21
./mvnw -B -Dtest=OrisoEmailRendererTest test
./mvnw -B package -Dskip.unit-tests=true -Dskip.integration-tests=true
./mvnw -B spotless:check
```

Check against the frontend email-source/export package before changing generated copy.
Actual outbound dispatch, received email, links in deployed environments and visual
acceptance remain unproved. No appointment-booking implementation is included.
