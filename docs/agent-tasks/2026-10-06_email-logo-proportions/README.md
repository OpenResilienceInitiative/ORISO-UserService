# Email logo proportions

User request: preserve logo proportions, render every logo at48px high, and define Storybook variants. Through3:1 the platform wordmark remains; wider logos hide it on phones.

Frontend branch fix/email-logo-variants, commit21f79866a510fbb19ad941c397f945f7bc7a5c9a. Canonical script synced510files and pins that source in manifest.json and EmailTemplateIntegrityTest.

Backend supplies optional intrinsic dimensions from the selected persisted inline PNG/JPEG logo, without external fetches. Renderer derives proportional widths and controlled wide-layout classes. Missing/invalid dimensions keep width:auto at48px high and retain the adjacent name.

Validation:116focused tests aftersync passed, including loadedtemplate class andmobileCSS assertions. Frontend11012unit tests,5919email tests,28Storybook browser tests, both lint gates andproduction build passed. Independent touchedscope security/spec reviews returned no actionable findings. Full backend package passes, with5923unit tests andzero failures/errors in this run.

Open choice: exceptionally wide ratios above6:1 cannot fit320px while preserving48pxheight. User was asked whether shrinking is allowed; currentcodepreservesheight.

Resume: implementation andlocalcommit complete; request approval before opening review PRs againstdev. No PR opened, no merge/deploy/mailbox proof. FrontendAGENTSline16 requires explicitapproval beforePRcreation.


## Review correction

A long unbroken platform name beside a 3:1 logo overflowed 320px. Frontend commit d6aa08398c956a9471c69fb0da59149c3df5c404 adds wordmark wrapping and a real browser regression (560px before; 320px after). Generated artifacts are synchronized from that commit. Backend package validation passed; CI will validate the follow-up push.
