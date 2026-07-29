# Branded HTML e-mail layout

Contract for ORISO-UserService#914. The backend owns **one** canonical mail markup; every other
repository (Admin panel, Storybook) renders the *result* of that markup, never a copy of it.

## Where the markup lives

| Part | File |
|---|---|
| HTML skeleton | `src/main/resources/email/layout/branded-email.html` |
| Plain-text skeleton | `src/main/resources/email/layout/branded-email.txt` |
| Header (logo) | `src/main/resources/email/layout/header-logo.html` |
| Header (wordmark fallback) | `src/main/resources/email/layout/header-wordmark.html` |
| Call to action + fallback line | `src/main/resources/email/layout/cta.html`, `cta.txt` |
| Footer link | `src/main/resources/email/layout/footer-link.html` |

Renderer: `api/service/email/layout/BrandedEmailLayoutRenderer`. It only fills placeholders
(`{{UPPER_SNAKE}}`) in a **single pass**, so substituted values can never introduce further
placeholders.

## Where it is applied

`InviteMailDispatchService` is the single choke point. Callers pass *authored content* plus the
primary action; the dispatcher resolves branding, renders the layout and hands a
`multipart/alternative` message (plain text first, HTML last) to `JakartaInviteMailTransport`.
That covers the tenant-admin invite, the counsellor invite and every resend on this path. The
strict receipt-after-send contract from ORISO-Admin#569 / TEN-INV-U6 is unchanged, and the link
targets still come from `InviteAcceptUrlBuilder`.

## Branding resolution

`api/service/email/layout/EmailBrandingResolver`:

| Value | Order |
|---|---|
| Brand name | tenant name → `email.branding.name` (default `ORISO`) |
| Logo | tenant `theming.logo` → tenant `theming.associationLogo` → `email.branding.logo-url` → **text wordmark** |
| Accent colour | tenant `theming.primaryColor` → `#a5000a` |
| Imprint / privacy | `TenantTemplateSupplier` attributes → `${app.base.url}/impressum`, `/datenschutz` |

Only absolute `http(s)` URLs are accepted as a logo. Tenant theming may store an inline base64
image, and `data:` URIs are blocked by Gmail and Outlook — such a logo deliberately degrades to
the wordmark rather than rendering a broken image.

Every remote lookup is best-effort: a tenant-admin invite is sent *before* the tenant exists, so a
404 from TenantService is normal and simply yields platform branding.

### The colour rule (binding, #914)

The product has no real dark-mode token system yet, so the mail encodes a deliberate stopgap:

- **light rendering uses the dark accent** — that is what `theming.primaryColor` is, a light-mode
  token;
- **dark rendering inverts and uses the light accent** (the rose tone), never a derived variant of
  the dark one;
- **on a deep surface, text and icons are white** — never a mid-tone on a mid-tone.

Two things are deliberately *not* in the chain:

- `theming.secondaryColor` — ORISO-Admin's `buildSeedUpdate` writes it as `null` on every theming
  save, so a step reading it can never resolve and would only obscure the real fallback;
- `globalSmtpEmailThemeColor` (SMTP settings, "E-Mail Designfarbe") — the mail follows the product
  colour rule and nothing else; an SMTP transport setting is not a design token. The dispatcher
  still reads that payload for the SMTP connection, but the colour field in it is ignored.

The platform fallback `#a5000a` is the product's own `--oriso-app-accent-dark`
(`ORISO-Admin/src/app.css`), not an invented tone. The same source supplies every neutral in the
skeleton: `#e4e2e2` (`--admin-workspace-background`, the canvas around the card), `#ffffff`
(`--m3-surface-container-lowest`, the card), `#f0edee` (`--m3-surface-container`, the footer),
`#c4c7c8` (`--admin-field-outline`, borders), `#1b1b1c` (`--m3-on-surface`, headings), `#444748`
(`--m3-on-surface-variant`, body/footer text) and `#747878` (`--m3-outline`, the fallback hint).

**Dark half — blocked, with one seam.** `theming.accent` is not in the tenant contract
(`services/tenantservice.yaml → Theming`) and is dropped on save, tracked as
OpenResilienceInitiative/ORISO-TenantService#154, so the renderer cannot read the light accent
today. Nothing is derived as a substitute — that would hide the missing data — and the mail stays
light-only (`color-scheme: light only`). The single seam is
`EmailBrandingResolver#resolveAccentColor`; its javadoc states exactly what to add once #154 lands
(a `resolveDarkRenderingAccent`, a second component on `EmailBranding`, and a
`prefers-color-scheme: dark` block in the skeleton).

### Contrast guard

Foreground colours are derived, never assumed (`EmailColors`):

- button/bar text = near-black or white, whichever wins the WCAG contrast ratio — a tenant
  `primaryColor` of `#f8e71c` therefore gets dark text, not white, while the platform accent
  `#a5000a` gets white;
- link text and the wordmark sit on the white card and are darkened until they clear 4.5:1;
- a near-white accent gets a darkened button border so the button stays visible.

### Dark mode

The layout does not rely on `prefers-color-scheme`. It declares `color-scheme: light only`
(meta + CSS), and every cell carries an explicit `bgcolor` attribute **and** an inline
`background-color`, plus an explicit `color` on every text cell — a client that inverts anyway
still has a defined foreground/background pair.

This is the light half of the colour rule above, and it is deliberate rather than final: the dark
rendering needs the light accent, which the tenant contract does not carry yet
(ORISO-TenantService#154). Until that lands, opting out is the honest behaviour — a fabricated rose
tone would look finished while being wrong.

## Author content: what is allowed

`InviteEmailTemplate.body` is untrusted input for HTML purposes. `EmailContentSanitizer` applies a
jsoup allow-list:

| | |
|---|---|
| Allowed tags | `p`, `br`, `b`, `strong`, `i`, `em`, `u`, `ul`, `ol`, `li`, `h1`, `h2`, `h3`, `blockquote`, `hr`, `a` |
| Allowed attributes | only `href` on `a`, restricted to `http`, `https`, `mailto` |
| Stripped, text kept | every other element (`div`, `span`, `table`, `font`, …) |
| Removed entirely | `script`, `style`, `iframe`, `object`, comments, **all** attributes (`style`, `class`, `id`, `on*`, `src`, …) |

`target`, `rel` and the inline link colour are set by the layout, never by the author.

Two authoring conveniences run after sanitisation:

1. **Plain-text paragraphing** — a body without block markup keeps its line breaks (blank line →
   new paragraph, single newline → `<br>`).
2. **Linkification** — bare `http(s)` URLs become anchors (#913). URLs already inside an `<a>` are
   left alone; trailing sentence punctuation is not swallowed into the URL.

The primary action is additionally rendered as a button **and** repeated as a visible full-URL
fallback line, so copy-paste always works.

## Preview endpoint

Authorisation for both variants: `AUTHORIZATION_TENANT_ADMIN`, `AUTHORIZATION_USER_ADMIN` or
`AUTHORIZATION_RESTRICTED_AGENCY_ADMIN` — the same guard as the other
`/useradmin/invite-email-templates` endpoints.

```
GET  /useradmin/invite-email-templates/preview
       ?templateId=<long>      optional — render a stored template
       &kind=TENANT_INVITE|COUNSELLOR_INVITE|DPA_FORWARD   optional
       &tenant_id=<long>       optional — preview a specific tenant's branding
       &language=de|en         optional

POST /useradmin/invite-email-templates/preview
{ "templateId": null, "kind": "TENANT_INVITE", "subject": "...", "body": "...",
  "tenantId": null, "language": "de" }
```

Both return `200` with the same shape:

```json
{
  "templateId": 5,
  "templateName": "Counsellor DE",
  "kind": "COUNSELLOR_INVITE",
  "language": "de",
  "subject": "Willkommen Erika",
  "html": "<!doctype html> …",
  "plainText": "ORISO\n===== …",
  "sampleAcceptUrl": "https://admin.oriso.org/admin/tenant-onboarding/SAMPLE-PREVIEW-TOKEN"
}
```

`404` if `templateId` does not exist, `400` for an unknown `kind`.

Without any parameter the endpoint renders the built-in sample invite with platform branding —
that is the fixture to snapshot for Storybook. `html` is a complete standalone document; drop it
into an iframe (`srcdoc`) or write it to a `.html` fixture file. The sample token is the literal
string `SAMPLE-PREVIEW-TOKEN`, so a preview never looks like a live invite link.

`InviteEmailPreviewServiceTest.preview_Should_renderExactlyWhatTheDispatcherBuilds` asserts the
preview output is byte-identical to what the dispatcher hands to the transport.

> Fixtures generated from this endpoint are checked into **ORISO-Admin**
> (`src/components/EmailPreview/fixtures/*.html`), not here. Any change to the palette or the
> skeleton means those fixtures must be regenerated in that lane — this repository has no checked-in
> rendered-mail fixture to refresh.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `email.branding.name` | `ORISO` | wordmark / brand name when no tenant name is known |
| `email.branding.logo-url` | *(empty)* | absolute platform logo URL used when a tenant has none |
| `app.base.url` | — | source of the imprint/privacy fallback URLs |
