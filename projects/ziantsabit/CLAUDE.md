# CLAUDE.md — ziantsabit

Guidance for this project of the monorepo; the monorepo-wide conventions are in the root `CLAUDE.md`. Paths below are relative to `projects/ziantsabit/` unless they say otherwise.

## Overview

Personal portfolio website (ziantsabit.com). Two independent sub-projects, each with its own Dockerfile and docker-compose.yml — there is no root-level build, package manager, or orchestration that ties them together.

- `ziantsabit-fe/` — React 19 + TypeScript + Vite SPA (the live site)
- `ziantsabit-be/` — Spring Boot 4 / Java 25 content API

## Commands

Frontend (run from `ziantsabit-fe/`):

```bash
npm install
npm run dev        # Vite dev server
npm run build      # tsc -b (project references) then vite build
npm run lint       # eslint .
npm run preview    # serve the built dist/
docker compose up --build   # multi-stage build -> nginx on :8080
```

Backend (run from `ziantsabit-be/`):

```bash
./run.sh dev                       # loads config/dev.env, then bootRun: API on :8000, Swagger at /api/docs/
./run.sh prod --docker -d          # container with config/prod.env (+ docker-compose.prod.yml for stg/prod)
./gradlew test                     # whole suite (embedded Postgres, no Docker needed)
./gradlew test --tests BookApiTest # one class; 'BookApiTest$Pagination' for one @Nested group
./gradlew bootJar                  # build/libs/ziantsabit-be-2.0.0.jar
```

No test runner is configured for the frontend.

## Frontend architecture

### Adding a page

`src/App.tsx` is the whole routing table and the app shell — `<Header />` and `<Footer />` live there, so both persist across navigation and a new page inherits them for free. Every page renders beneath a fixed bar (56px on `xs`, 64px from `sm`). A new page is three coordinated edits:

1. `src/pages/X.tsx` — default-exported function component.
2. A `<Route path="/x" element={<X />} />` in `App.tsx`.
3. An entry in the `navItems` array in `src/components/Header.tsx` — that one array feeds both the desktop nav and the mobile `Drawer`, so adding it in one place covers both. Home is deliberately absent from it; the "Zian Tsabit" logo `Link` is what routes to `/`.

### Colour and the light/dark theme

`src/theme.ts` is the single source of colour. `main.tsx` wraps the app in `<ThemeProvider theme={theme} defaultMode="light">` plus `<CssBaseline />`, and the theme declares both schemes via MUI's `colorSchemes` with `cssVariables: { colorSchemeSelector: "class" }`.

**Never write a colour literal in a component.** `color: "white"` or `grey.900` resolves to the same thing in both schemes, so a literal silently breaks light mode — dark text on a dark card. Use semantic tokens only:

| Instead of | Use |
| --- | --- |
| `color: "white"` | `color: "text.primary"` |
| `color: "grey.400/500/600"` | `color: "text.secondary"` |
| `bgcolor: "black"` | `bgcolor: "background.default"` |
| `bgcolor: "grey.900"` | `bgcolor: "background.paper"` |
| `#6497b1` | `primary.main` |
| `borderColor: "grey.800"`, `bgcolor: "grey"` | `divider` |

The one custom token is `palette.headerScrolled` (declared through module augmentation in `theme.ts`), the translucent wash behind the scrolled header. Read it via `(theme.vars ?? theme).palette.headerScrolled` — with `cssVariables` on, `theme.vars` is the populated one, and the fallback keeps TypeScript happy since `vars` is optional on the type.

Note the two schemes do **not** share a link colour: dark uses `#6497b1`, light uses the darker `#1565c0`, because `#6497b1` on white is only ~3.1:1 — under the 4.5:1 AA floor for body text. If you add a colour, check it against both backgrounds.

**Mode selection** is `defaultMode="light"`: a first-time visitor gets Light whatever their OS prefers. Every visitor has the Light / Dark / Rain picker (`ColorModeToggle.tsx`, in the header) -- it was owner-only until 1.3.0. The picker never calls MUI directly: it goes through `useSiteTheme` (`src/services/useSiteTheme.ts`), the only code that should call `setMode`/`setColorScheme`, because Rain is "dark mode + the `rain` dark scheme" and setting only one half leaves the page half-changed. The choice persists in `mui-mode` and `mui-color-scheme-dark`. `mode` is `undefined` on the first render, so the toggle renders a hidden same-size placeholder until mounted rather than flashing the wrong icon.

**Avoiding a flash of the wrong theme** takes two cooperating pieces, because MUI emits its palette as CSS variables injected by JavaScript — before React mounts there is nothing colouring the page:

1. `index.css` carries `prefers-color-scheme` media queries plus `html.light` / `html.dark` rules that set *only* the page background. They live in the static, render-blocking stylesheet, so the correct backdrop paints immediately.
2. An inline script in `index.html` reads `localStorage['mui-mode']` and applies the class before that stylesheet is parsed, so a stored choice that contradicts the OS is honoured pre-paint too.

Those background hex values are duplicated from `palette.background.default`; if you change one, change the other. That is the only place a colour literal belongs outside `theme.ts`.

### Page layout: the sticky-footer shell

`App.tsx` is a flex column at least `100vh` tall holding `<main>` and `<Footer />`. `<main>` carries `flex: 1` (plus `pt: HEADER_HEIGHT`, since the header is `position: fixed` and out of flow), so it absorbs the slack and the footer lands at the bottom of a short page rather than one screen below the fold.

**A page therefore sets `flex: 1`, never a height of its own.** There is deliberately no `PAGE_MIN_HEIGHT` constant any more — a page using `minHeight: 100vh` or `calc(100vh - header)` inside this shell is taller than the space available and reintroduces a permanent scrollbar. `HEADER_HEIGHT` in `src/constants/layout.ts` is the only remaining metric, shared by `Header.tsx` and `<main>`'s padding. Pages must not add their own top margin for the header; an earlier `marginTop: "36px"` under a 64px bar left content sitting behind it.

A child that should soak up leftover vertical space needs an unbroken `flex: 1` chain down from `<main>` — this is why `Home`'s `Container` is itself a flex column, so the typewriter block can grow instead of pinning a fixed `30vh` that pushed the page just past the viewport on a phone.

Pages are built from `Box`/`Container`/`Stack` with inline `sx` — no CSS modules, no styled-components except `Typewriter`. Existing pages follow one of two shapes; copy the closer one:

- **Content page** (`Home`, `CV`, `About`, `Books`): `Box` with `flex: 1`, `bgcolor: "transparent"`, `pt: { xs: 2, sm: 3 }`, wrapping a `Container maxWidth="md"`. Don't add bottom padding for breathing room — the footer's top border now terminates the page.
- **List page** (`Projects`, `Posts`): `bgcolor: "background.default"`, a `Container maxWidth="md"` with `py: { xs: 4, md: 6 }`. `Typewriter text="Coming soon..."` only ever appears now as the *empty state* a section's post list renders itself when it has nothing published — not as page content a page author writes. There is no Garage page any more; see "Frontend/backend seam" below.

Use `Container` (or explicit `px`) rather than a bare `maxWidth` on a `Box` — `Container` supplies the responsive side gutters that keep text off the screen edge on phones.

### Responsive conventions

Responsive values use the MUI breakpoint object form (`{ xs: "12px", sm: "14px", md: "16px", lg: "22px" }`, `direction={{ xs: "column", sm: "row" }}`) rather than media queries. Two rules the existing code now follows:

- **Justified text is `sm`-and-up only** — `textAlign: { xs: "left", sm: "justify" }`. A justified ~35-character phone line opens visible rivers of whitespace.
- **Nothing may widen the page.** `html, body` carry `overflow-x: hidden` as a backstop, but that only hides the symptom. If a block genuinely needs a fixed minimum width, give it its own `overflowX: "auto"` container so it pans inside its box while `document.scrollWidth` stays equal to the viewport.

### Component notes

- `Header.tsx` owns everything in `sx` — there is no longer a `Header.css`. A scroll listener flips `isScrolled` past 50px, which swaps `bgcolor` between `transparent` and `palette.headerScrolled` under a `background-color` transition. It had to move out of CSS because a hardcoded `rgba(0,0,0,0.85)` cannot follow the colour scheme.
- `Footer.tsx` is copyright plus LinkedIn/GitHub/Email. It uses `@mui/icons-material` icons rather than the CDN devicon images the CV header uses, so the glyphs take `currentColor` and follow the theme instead of staying fixed-colour.
- `SectionHeading.tsx` and `TagChip.tsx` (with its `TagChipRow` export) are shared by the CV and About pages — they were duplicated inline before, so both pages drifting apart was a matter of time. Reuse them rather than restyling a heading or pill locally.
- `TimelineItem.tsx` renders one LinkedIn-style entry: an absolutely positioned dot plus a `&::before` rail that runs from under the dot to the bottom of the entry, so consecutive items join into one continuous line. Pass `last` on the final entry of a section to suppress the trailing rail. Title and date sit on one row at `sm`+ and stack at `xs`.
- `Typewriter.tsx` is the one emotion `styled()` component. Width is driven by a `--characters` CSS custom property computed from `text.length`, typed via `interface CustomStyles extends React.CSSProperties`; the animation loops forever, so it reads as a placeholder, not a one-shot reveal.
- **`@mui/joy` was removed; don't add it back.** Joy and Material read the same theme context, so a Joy component under the Material `ThemeProvider` crashes the whole app with `Cannot read properties of undefined (reading 'xl')` — a blank white page, not a degraded one. Joy also has its own independent colour-scheme system, so it would never follow the light/dark toggle. `Header.tsx` used Joy's `List`/`ListItem`; those are now plain `Box component="ul"/"li"` with the menubar roles kept. `@mui/material` is the only component library.
- `src/css/Home.css` and `src/css/Projects.css` are empty leftovers imported by nothing.

### Markdown

Post bodies are Markdown. `src/components/Markdown.tsx` renders them and is the **only** place that should — the admin's Preview tab renders through the same component as the published page, which is what stops the preview from drifting away from the real output.

- **Raw HTML is not rendered.** `react-markdown` ignores it unless `rehype-raw` is added; leave it out. Bodies are stored and replayed verbatim, so a `<script>` in one should stay text.
- **Headings are demoted one level** — a `#` becomes an `<h2>`, because the page already spends its `<h1>` on the post title. Visual size still follows what was typed.
- **`remark-breaks` is load-bearing.** Bodies written before Markdown existed were rendered with `whiteSpace: "pre-line"`; without this plugin every one of them silently reflows into a single paragraph.
- **Wide blocks scroll in their own box.** `<pre>` and `<table>` carry `overflowX: auto` — see the "nothing may widen the page" rule above.
- **Mermaid fences render as diagrams.** The `pre` component spots a ```` ```mermaid ```` block and hands it to `src/components/MermaidDiagram.tsx`. Mermaid (pinned exactly in `package.json`) is reached only through `import("mermaid")` there, so it is a set of chunks fetched by the first diagram on a page, never part of the main bundle — don't import it statically. It is initialised with `securityLevel: "strict"` (labels sanitised, no click handlers; a `%%{init}%%` directive cannot loosen it) and drawn with `mermaid.render` plus `suppressErrorRendering`, never `mermaid.run`, so it never touches the page's DOM and leaves no error SVG on `<body>`. The theme follows `useColorScheme().colorScheme` (mermaid `default` for light, `dark` for dark and rain), which also covers a post's own theme, and re-renders on a switch. Until the SVG is ready — or for good, if the diagram is invalid or the chunk fails — the ordinary code block shows, plus a one-line error note when it failed. The SVG is pinned to its natural width inside an `overflowX: auto` box rather than shrunk to the column.
- `toPlainText()` (in `src/components/markdownText.ts`, beside the component) flattens Markdown for card previews, which fall back to the body when a post has no excerpt. It is regex, not a parse, on purpose: the output is a clamped teaser. Fenced blocks, mermaid included, are dropped from it whole.

The editor is `src/components/admin/MarkdownEditor.tsx` (Write/Preview tabs, toolbar, shortcuts) over the pure transforms in `markdownCommands.ts`. Two things there are deliberate and easy to break:

- **Every edit goes through `document.execCommand("insertText")`.** It is deprecated and it is still the only way to make a programmatic edit that the browser's native undo stack knows about. Assign to the textarea's value instead and Ctrl+Z after a toolbar click throws away the whole field.
- **Tab is trapped, and Escape releases it for one keypress.** Without that opt-out a keyboard-only user cannot get from the body to the Save button.

### Content and assets

All copy is hardcoded in components. `CV.tsx` is the outlier and the one page with a real data shape: module-level `summary`, `experience`, `projects`, `skills`, and `education` arrays declared above the component, mapped into `<TimelineItem />`. Its content is a manual transcription of the owner's CV, so keeping it current is a hand edit.

**No CV PDF is shipped, deliberately.** The site used to serve `public/Ghazian_Tsabit_Alkamil.pdf` behind a "Download CV" button; both were removed because the document contains a personal phone number. Don't reintroduce a downloadable CV without checking what personal data is in it — a PDF in `public/` is world-readable to anyone who guesses the URL, with no link required.

Static files live in `public/` and are referenced by absolute path (`/pp-github.png` on Home, `/professional-photo.jpeg` on About). These are untyped string literals that Vite will not check, so renaming anything in `public/` means grepping for the old filename — the CV download button silently 404'd for months after `c77e4ac` renamed its target.

The Ubuntu font comes from a Google Fonts `<link>` in `index.html`, not from npm, and **that URL must keep requesting the `ital` axis**. `index.css` sets `font-synthesis: none`, so with a regular-only font the browser will not fake a slant: `<em>` and every `fontStyle: "italic"` (the Books quote, `TimelineItem`'s company blurb) render identically to body text. That was the case until the axis was added — the markup looked right and the output was silently wrong.

External links are inconsistent: `CV.tsx` routes them through react-router's `Link` (`to="https://..."`, `to="mailto:..."`) while `Home.tsx` uses a plain `<a>`. The plain anchor is the correct one for off-site URLs.

### Serving the built site

Routing is client-side, so `/about`, `/books`, … exist only in `App.tsx` — there is no matching file in `dist/`. Any static host must rewrite unknown paths to `index.html` or a direct hit (or a refresh, or a shared link) returns 404 while in-app navigation works fine.

`nginx.conf` does this for the Docker image via `try_files $uri $uri/ /index.html`, and the Dockerfile copies it to `/etc/nginx/conf.d/default.conf`. Two details there are deliberate: `/assets/` uses `try_files $uri =404` so a missing bundle fails as a 404 instead of silently returning HTML that the browser then tries to parse as JavaScript, and `index.html` is sent `Cache-Control: no-store` so a returning visitor never gets an old shell pointing at asset hashes that no longer exist.

`npm run dev` and `npm run preview` both have this fallback built in, so this class of bug only ever shows up in the container.

On the k3s cluster the same image runs as `deployments/k8s/ziantsabit/base/ziantsabit-fe.yaml` (monorepo root), in a staging and a production overlay: nginx pods behind a Service — ClusterIP behind the `cloudflared` tunnel in prod, a LAN-only NodePort in staging. There is no ConfigMap — `VITE_API_BASE_URL` is a build arg, so the image is built per environment (`ziantsabit-fe:<version>-stg` with `http://192.168.0.150:30800/api`, `ziantsabit-fe:<version>` with `https://api.ziantsabit.com/api`), or every visitor's browser calls `localhost:8000`.

### Build gotcha

`tsconfig.app.json` sets `strict`, `noUnusedLocals`, and `noUnusedParameters`. `npm run dev` tolerates an unused import; `npm run build` runs `tsc -b` first and fails on it. Run `npm run build` before assuming a change is complete.

## Backend

Spring Boot 4.1 on Java 25: Spring MVC, Spring Security, Spring Session JDBC, Bean Validation, Flyway, springdoc, plain `JdbcClient` SQL (no JPA), and the AWS SDK for the image bucket. Version 2.0.0 removed the last of the compatibility layer it was first ported with (October 2026) — see "Upgrading from 1.x" in `ziantsabit-be/README.md`. **The HTTP contract the SPA uses is fixed**: URLs with or without a trailing slash, snake_case JSON, `{count,next,previous,results}` pages, `{"detail"}` / `{"field": [...]}` / `non_field_errors` errors, the `X-CSRFToken` header. Change any of it only together with the SPA.

### Layout

`src/main/java/com/ziantsabit/api/`, one package per responsibility rather than per feature. A request goes **controller → service → repository**, and controllers never touch a repository directly:

| Package | Holds |
| --- | --- |
| `controller/` | HTTP only: routes, Swagger annotations, throttle checks, status codes. `PostController`, `BookController`, `CommentController`, `PageController`, `UploadController`, `AuthController`. |
| `service/` | The rules: turning a request into fields to store, slug and publish-stamp on save, visibility 404s, toggling a reaction, stats, uploads. `PostService`, `PostStatsService`, `ReactionService`, `BookService`, `CommentService`, `PageService`, `UploadService`, `AdminBootstrap`. |
| `repository/` | SQL, one class per table: `PostRepository` (+ view-day tally), `ReactionRepository`, `BookRepository`, `CommentRepository`, `PageRepository`, `UserRepository`. |
| `query/` | `PostQuery`, `BookQuery`, `CommentQuery` — which rows a request may see and in what order, built from its query params and passed from controller to repository. |
| `model/` | The stored things: `Post`, `Book`, `Comment`, `PageContent`, `UserAccount`, `Reaction` (the emoji vocabulary), and `PostFields`/`BookFields` (what a save writes). `Post`, `Book` and `Comment` double as their JSON. |
| `dto/` | Request records with their Bean Validation constraints (`PostRequest`, `BookRequest`, `CommentRequest`, `ReactionRequest`, `PageRequest`, `LoginRequest`) and response shapes that are not a model (`PageResponse`, `PostStats`, `ReactionSummary`, `SessionState`, …). |
| `validation/` | `RequestBodies` (read / merge / validate a body), the constraints `@Required`, `@OneOf`, `@HttpUrl`, their `Messages`, and `QueryParams`, `Isbn`, `ImageFormat`, `PageDocuments` (the CV/About normaliser). |
| `security/` | `UserAccounts` (`UserDetailsService` + `UserDetailsPasswordService`), `LegacyPbkdf2PasswordEncoder`, `CsrfOriginCheck`, `Throttle`, `Viewer` (who is asking). |
| `exception/` | `ApiException`, `Throttled`, `ApiExceptionHandler` — every error body. |
| `storage/` | `ObjectStorage` and its S3/RustFS implementation; tests swap in an in-memory one. |
| `util/` | `Slugs`, `Labels`, `Sql`. |
| `config/` | `SecurityConfig`, `WebConfig` (trailing slashes, allowed hosts, throttle beans, clock), `OpenApiConfig`, `AppProperties`. |

Tests mirror it: the HTTP-level suites are in `src/test/java/.../controller/`, `PasswordTest` in `security/`, `AdminBootstrapTest` in `service/`, shared harness in `support/`.

### Database

Tables `post`, `post_view_day`, `book`, `comment`, `reaction`, `page_content`, `app_user`, plus Spring Session's `spring_session*`. Flyway (`src/main/resources/db/migration/`) owns the schema. V1–V3 are history: they built the schema under its pre-2.0 names, and **V4 renamed everything** to the above, rebuilt constraints and indexes under descriptive names, and made the child tables' foreign keys `ON DELETE CASCADE`. **A schema change is a new `V5__…sql`, never an edit to an existing one** — they have all run everywhere, and Flyway checksums them.

- **V4 finds old constraint and index names through the catalogue** (`pg_constraint`, `pg_index`) rather than naming them, so an extra or differently named one on a database that predates Flyway cannot survive it. `LegacySchemaMigrationTest` replays production's real path — the pre-Flyway schema (a schema-only `pg_dump` of production, `src/test/resources/legacy-schema.sql`), baselined at 0, then V1–V4 — on embedded Postgres 18.4, production's version. Postgres 18 lists NOT NULL in `pg_constraint` too (`contype = 'n'`); V4 leaves those alone.
- **Deleting a post is one statement**; the cascade takes its comments, reactions and view days. A new child table of `post` should cascade too.
- Timestamps and counters have column defaults (`now()`, `0`), so an `INSERT` need not name them.
- `V2`'s seed is `ON CONFLICT DO NOTHING`, so it never overwrites the owner's edits.

### Accounts and passwords

Any enabled `app_user` row can write; there are no roles. A fresh database has no user: set `ADMIN_USERNAME`/`ADMIN_PASSWORD` and `AdminBootstrap` creates one on startup — only if missing, never resetting an existing one.

Passwords go through Spring Security's `DelegatingPasswordEncoder`: new hashes are `{bcrypt}`. A hash from before V4 is `{pbkdf2-legacy}…` and verified, never written, by `LegacyPbkdf2PasswordEncoder`; because `UserAccounts` is the `UserDetailsPasswordService` and `DaoAuthenticationProvider` is given it, **the first successful login rewrites it as bcrypt** (basic auth and session login alike). Once `SELECT count(*) FROM app_user WHERE password LIKE '{pbkdf2-legacy}%'` is 0 in every environment, delete the class and its registration in `SecurityConfig`. A hash with no known prefix matches nothing.

### Request bodies and errors

- **Controllers take `JsonNode` and services call `RequestBodies`**, which turns it into a request record and runs Bean Validation. `read()` is a create. `patch()` is a JSON merge: the stored record (`XRequest.of(model)`) as a tree, the body's fields laid over it, then the same validation — so a field left out keeps its value, `null` clears a nullable one, and required fields stay required without a partial mode. Unknown and read-only fields are ignored. **There is no PUT.**
- **Every problem is reported at once**: `{"snake_case_field": ["message", ...]}`. A value of the wrong JSON type is reported against its field ("Must be a list.", "Enter a valid date and time, as ISO 8601."); a body that is not an object is `non_field_errors`. Messages live in `validation/Messages` and on the annotations, written for the person filling the admin form in.
- **Rules that need the database or more than one value stay in the services** and are raised as field errors with `ApiException.field/invalid`: slug taken or blank, ISBN checksum, release year ≤ next year, the same book twice, a comment's post existing and accepting comments, an emoji on offer.
- `@Required` exists so that a missing field says "This field is required." and a blank one "This field may not be blank." — never both, which `@NotNull` + `@NotBlank` would.

### HTTP behaviour worth keeping

- **Trailing slashes:** every SPA URL ends in `/`; Spring matches neither form to the other by default. `WebConfig` runs Spring's `UrlHandlerFilter` first so both work, and stashes the original path so pagination links echo it back. Controller mappings and security matchers are therefore written **without** the trailing slash.
- **CSRF is checked only for session-authenticated writes and for login** (`SecurityConfig.needsCsrfCheck`). Anonymous writes (comments, reactions, `/view`) carry no token — the public pages never send one — and basic auth is never challenged (403, no `WWW-Authenticate`) so a browser never caches it. The token is a double-submit `XSRF-TOKEN` cookie (HttpOnly) plus the `X-CSRFToken` header; the SPA gets the value from the body of `/api/auth/session/`, and it is rotated on login and logout. `CsrfOriginCheck` refuses an untrusted `Origin` first. **A CSRF refusal's `detail` must keep containing "CSRF"** — the SPA retries once on that.
- **CORS never rejects.** An origin not in `CORS_ALLOWED_ORIGINS` just gets no CORS headers; Spring's default would answer it with a bare 403.
- **Visibility is decided once per resource** (`PostQuery`, `BookQuery`, `CommentQuery`) and used by the list *and* every route addressed by slug or id, so a draft is a 404 to a visitor everywhere. A new route on an existing resource should go through the same query.
- **Throttles** (`COMMENT_RATE_LIMIT`, `REACTION_RATE_LIMIT`) are in-memory sliding windows keyed by user or client address. Behind the tunnel the address is only per-visitor with `FORWARD_HEADERS_STRATEGY=native` (set in `docker-compose.prod.yml`).
- `Slugs` and `Labels` decide the slugs and tags already in the database and in shared links; changing their rules changes what an edited title or tag becomes.

### Tests

Every API test extends `support/ApiTestBase`: a real server on a random port, a real Postgres (zonky embedded binaries, started once per JVM) with the real migrations, and a fresh state per test (tables truncated, the seeded pages restored from a snapshot taken before the first test, throttles reset). Callers are `support/Api` instances over `java.net.http` — `client` (anonymous), `owner()` (basic auth, which needs no CSRF token), and `browser()` (keeps cookies, for the session/CSRF tests). Fixtures go through the services' `save()` so slugs and publish-stamping happen as in production; `update(table, id, columns)` writes columns directly for what the API cannot (view counts, timestamps). A test that needs different properties (rate limits, upload size) is its own class with `@TestPropertySource`, and so its own Spring context.

### Settings and the container

**Per-environment values are in `ziantsabit-be/config/<env>.env`** (`dev`, `stg`, `prod`; committed), loaded by `run.sh`. **Never write a secret into one**: it is a Vault reference, `NAME=vault:<path>[#field]`, resolved by `vault-env.sh` when `run.sh` loads the file. They are written with the Compose names; for a host run `run.sh` maps `RUSTFS_*`, `SITE_ORIGINS` and `MEDIA_ORIGIN` onto the app's names and `host.docker.internal` onto `localhost`. A new compose-only rename needs the same mapping added there.

Everything environment-specific is in `application.yml` (`POSTGRES_*`, `ALLOWED_HOSTS`, `CORS_ALLOWED_ORIGINS`, `AWS_S3_*`, …).

The `Dockerfile` is two stages (a Gradle-wrapper build on `eclipse-temurin:25-jdk`, then `eclipse-temurin:25-jre`), running as UID 1000. There is no entrypoint script: Flyway migrates at startup. **Postgres and RustFS are not in the compose stack** — they are separate, already-running servers named in `.env` (`POSTGRES_HOST`, `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`, `AWS_S3_ENDPOINT_URL`; `host.docker.internal` reaches the Docker host), and the bucket is created and made public-read by hand. With nothing to `depends_on`, a Postgres still booting fails Flyway and `restart: unless-stopped` retries. The healthcheck is `curl /actuator/health`, which is exempt from the allowed-hosts check because the container probes itself on 127.0.0.1.

**Kubernetes** (`deployments/k8s/ziantsabit/` at the monorepo root, outside this project; steps in its README) is the same image on the k3s cluster, as Kustomize: a shared `base/` and two overlays, namespaces `ziantsabit-stg` and `ziantsabit-prod`, each with one API replica. In prod it sits behind a ClusterIP Service, and one two-pod `cloudflared` tunnel, in its own `cloudflared` namespace, routes straight to it (and to the frontend's, `base/ziantsabit-fe.yaml`). Staging is not on the tunnel: its overlay makes both Services NodePorts (`:30800` API, `:30080` SPA), reachable on the LAN by node IP over plain HTTP, so it also turns off `Secure` cookies and sets `FORWARD_HEADERS_STRATEGY=none`. Staging uses the same Postgres and RustFS servers as prod with its own database (`stg_zian_tsabit_be`) and bucket (`stg-ziantsabit-media`). Three things in it are load-bearing:

- **The ConfigMap uses the app's variable names**, not the compose names in `config/<env>.env` — there is no `run.sh` in between to map them, so `RUSTFS_*` becomes `AWS_S3_ACCESS_KEY_ID`/`AWS_S3_SECRET_ACCESS_KEY`. The Secret is `vault-env.sh <env>`'s output: the file's Vault-backed values, renamed the same way (`APP_ALIASES`).
- **One replica**, because the rate limits are in-memory per pod.
- **The tunnel bypasses Traefik.** `FORWARD_HEADERS_STRATEGY=native` trusts `X-Forwarded-For` from any private address; Traefik by default overwrites it with the caller's pod IP, which would put every visitor in one rate-limit bucket. Route through Traefik only after setting its `forwardedHeaders.trustedIPs`.

Probes use `/actuator/health/liveness` and `/readiness`, which Spring Boot enables itself on Kubernetes and which the allowed-hosts exemption (a path prefix) already covers.

## Frontend/backend seam

**This repo's frontend predates the backend it now talks to.** The section below describes the SPA as it is here, built against an early API with a fixed `?category=` filter. The backend replaced categories with free-form `tags`, and added books, comments, reactions, page content and uploads — the newer SPA that uses those lives in the original `zian-tsabit-com` repository and has not been brought into this monorepo. Against this backend, `?category=` is simply ignored.

`/books`, `/projects`, `/posts` and each one's `/…/:slug` detail route render live data from `GET /api/posts/`. `/` and the CV/About pages are still hardcoded copy, except Home's "Latest Updates" block, which is also live (see below).

**There is no `/garage` page.** `Post.Category.GARAGE_SALE` is still a valid backend category — the admin console can still file a post under it — but nothing public links there any more; the page, its route, and its nav item were deleted. `VISIBLE_CATEGORIES` in `posts.ts` (`posts`, `books`, `projects`) is the list every cross-category view filters to, so a stray `garage_sale` post can never end up linked from a page that no longer exists.

**`/posts` is the odd one out: it browses every visible category, not just its own.** `/books` and `/projects` are still single-category pages built on `PostList` + `usePosts`, exactly as before. `/posts` instead has its own category filter (`TextField select`, defaulting to "All categories") and numbered `Pagination` instead of a "Load more" button — see `usePaginatedPosts.ts` and `Posts.tsx`.

No HTTP client dependency anywhere — `fetch` plus a handful of hooks:

- **`src/services/posts.ts`** — types mirroring `PostSerializer`, plus every fetch function: `fetchPosts(category, signal)` (one category), `fetchPost(slug, signal)` (one post, for a detail page — throws `PostNotFoundError` on a 404 so a "not found" state is distinguishable from a real error), `fetchLatestPosts(signal)` (newest posts across all categories, unfiltered — the API's default ordering is already newest-first), `fetchPostsPage({category, page}, signal)` (one numbered page, category optional), and the shared `fetchPostPage(url, signal)` they all funnel through. Also `CATEGORY_LABELS`, `CATEGORY_BASE_PATHS` (category → route, needed only where a caller doesn't already know its own category statically) and `VISIBLE_CATEGORIES`.
- **`src/services/usePosts.ts`** — one category, loading/error/empty/populated, appends pages via `loadMore`. Used by `Books`/`Projects`.
- **`src/services/usePost.ts`** — one post by slug, for a `PostDetail` page; adds a `not-found` phase on top of the usual three.
- **`src/services/useLatestPosts.ts`** — newest `limit` posts across `VISIBLE_CATEGORIES`, no pagination. Backs Home's `LatestUpdates` component.
- **`src/services/usePaginatedPosts.ts`** — one numbered page, category optional; backs `Posts.tsx`. When no category is given it filters `garage_sale` out client-side, so `count`/`totalPages` can run slightly high if any such post exists — not worth a backend change for a category the site no longer surfaces.
- **`src/components/PostList.tsx`** — renders the four states for a single-category page; also exports `PostCard` so `LatestUpdates` and `Posts.tsx`'s all-categories view can reuse the same card without a second implementation. **Needs an unbroken `flex: 1` chain from `<main>`**, which is why every page using it makes its `Container` a flex column; without it the loading spinner and the empty placeholder stop being vertically centred. `src/components/Centered.tsx` is that centring wrapper, shared by `PostList`, `PostDetail` and `LatestUpdates`.

`API_BASE_URL` is `import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8000/api"`, typed in `src/vite-env.d.ts` and documented in `.env.example`. Trailing slashes are stripped, because `//posts/` earns an `APPEND_SLASH` redirect instead of a response.

Points worth keeping intact:

- **An aborted request is not an error.** Every fetch takes an `AbortSignal`; the effect cancels on unmount and on a category change, and both the client and the hook check `isAbort` before reporting anything. Skip that and a fast navigation logs a spurious "Could not reach the API", or a resolved request writes state into an unmounted component.
- **`fetch` rejects identically for a dead backend and a CORS failure** — the browser only ever says `Failed to fetch`. `posts.ts` rewrites that into `Could not reach the API at <url>. Is the backend running?` with a Retry button, because the raw message tells nobody anything.
- **The empty state is the old `Coming soon...` typewriter.** A section with no posts looks exactly as the page did before it was wired, so publishing the first post is what changes the page.
- **`CORS_ALLOWED_ORIGINS` must list whatever origin serves the SPA.** Defaults cover `:5173` (dev), `:4173` (preview) and `:8080` (nginx container); a deployed site needs its real origin added or every response is discarded by the browser.
