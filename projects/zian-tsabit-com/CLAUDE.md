# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Personal portfolio website (ziantsabit.com). Two independent sub-projects in one repo, each with its own Dockerfile and docker-compose.yml — there is no root-level build, package manager, or orchestration that ties them together.

- `ziantsabit-fe/` — React 19 + TypeScript + Vite SPA (the live site)
- `ziantsabit-be/` — Spring Boot 4 / Java 25 content API (a port of the earlier Django backend, on the same database)

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
./gradlew bootJar                  # build/libs/ziantsabit-be-1.0.0.jar
```

No test runner is configured for the frontend.

## Frontend architecture

### Adding a page

`src/App.tsx` is the whole routing table and the app shell — `<Header />` and `<Footer />` live there, so both persist across navigation and a new page inherits them for free. Every page renders beneath a fixed bar (56px on `xs`, 64px from `sm`). A new page is three coordinated edits:

1. `src/pages/X.tsx` — default-exported function component.
2. A `<Route path="/x" element={<X />} />` in `App.tsx`.
3. An entry in the `navItems` array in `src/components/Header.tsx` — that one array feeds both the desktop nav and the mobile `Drawer`, so adding it in one place covers both. Home is deliberately absent from it; the "Zian Tsabit" logo `Link` is what routes to `/`.

### Colour and the light/dark theme

`src/theme.ts` is the single source of colour. `main.tsx` wraps the app in `<ThemeProvider theme={theme} defaultMode="system">` plus `<CssBaseline />`, and the theme declares both schemes via MUI's `colorSchemes` with `cssVariables: { colorSchemeSelector: "class" }`.

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

**Mode selection** is `defaultMode="system"`, so a first-time visitor follows their OS. `ColorModeToggle.tsx` calls `setMode()` to store an explicit override under the `mui-mode` localStorage key, which then wins over the OS. `mode` is `undefined` on the first render, so the toggle renders a hidden same-size placeholder until mounted rather than flashing the wrong icon.

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
- `toPlainText()` (exported from the same file) flattens Markdown for card previews, which fall back to the body when a post has no excerpt. It is regex, not a parse, on purpose: the output is a clamped teaser.

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

### Build gotcha

`tsconfig.app.json` sets `strict`, `noUnusedLocals`, and `noUnusedParameters`. `npm run dev` tolerates an unused import; `npm run build` runs `tsc -b` first and fails on it. Run `npm run build` before assuming a change is complete.

## Backend

Spring Boot 4.1 on Java 25: Spring MVC, Spring Security, Spring Session JDBC, Flyway, springdoc, plain `JdbcClient` SQL (no JPA), and the AWS SDK for the image bucket. **It replaced a Django + DRF backend in October 2026 and keeps that backend's database, URLs and JSON byte-for-byte**, because the SPA and the production data were both built against it. The Django code is in git history (and in the original repo's `prod` branch) if a question about intended behaviour comes up — its test suite was ported one-for-one into this one.

### Layout

`src/main/java/com/ziantsabit/api/`, one package per responsibility rather than per feature. A request goes **controller → service → repository**, and controllers never touch a repository directly:

| Package | Holds |
| --- | --- |
| `controller/` | HTTP only: routes, Swagger annotations, throttle checks, status codes. `PostController`, `BookController`, `CommentController`, `PageController`, `UploadController`, `AuthController`, `ApiRootController`, `RedocController`. |
| `service/` | The rules: validating a write, slug and publish-stamp on save, visibility 404s, toggling a reaction, stats, uploads. `PostService`, `PostStatsService`, `ReactionService`, `BookService`, `CommentService`, `PageService`, `UploadService`, `AdminBootstrap`. |
| `repository/` | SQL, one class per table: `PostRepository` (+ view-day tally), `ReactionRepository`, `BookRepository`, `CommentRepository`, `PageRepository`, `UserRepository` (`auth_user`). |
| `query/` | `PostQuery`, `BookQuery`, `CommentQuery` — which rows a request may see and in what order, built from its query params and passed from controller to repository. |
| `model/` | The stored things: `Post`, `Book`, `Comment`, `PageContent`, `UserAccount`, `Reaction` (the emoji vocabulary), and the writable halves `PostFields`/`BookFields`. `Post`, `Book` and `Comment` double as their JSON. |
| `dto/` | Response shapes that are not a model (`PageResponse`, `PostStats`, `ReactionSummary`, `SessionState`, `ViewCount`, `LabelCount`, `UploadedImage`) and `ApiDocs`, the request-body records that exist only for Swagger. |
| `validation/` | `Form` (field validation with DRF's exact messages), `QueryParams`, `Urls`, `Isbn`, `ImageFormat`, `PageDocuments` (the CV/About normaliser). |
| `security/` | `UserAccounts` (Spring's `UserDetailsService`), `DjangoPasswordEncoder`, `CsrfOriginCheck`, `Throttle`, `Viewer` (who is asking). |
| `exception/` | `ApiException`, `Throttled`, `ApiExceptionHandler` — every error body. |
| `storage/` | `ObjectStorage` and its S3/RustFS implementation; tests swap in an in-memory one. |
| `util/` | `Slugs`, `Labels`, `Sql`. |
| `config/` | `SecurityConfig`, `WebConfig` (trailing slashes, allowed hosts, throttle beans, clock), `OpenApiConfig`, `AppProperties`. |

Tests mirror it: the HTTP-level suites are in `src/test/java/.../controller/`, `AdminBootstrapTest` in `service/`, shared harness in `support/`.

### The database is still Django's

Table, column and index names are Django's (`myapp_post`, `auth_user`, `myapp_post_tags_aa0585_gin`, …). That is deliberate: the production database was built by Django's migrations 0001–0015, and keeping its names means no data migration on the switch and a rollback to the Django image needs nothing either.

Flyway (`src/main/resources/db/migration/`) bridges the two. `V1__schema.sql` restates Django's final schema with **every statement `IF NOT EXISTS`**, and `spring.flyway.baseline-on-migrate` with **baseline version 0**: on the production database Flyway finds no history table, baselines at 0, runs V1 — which finds everything already there and changes nothing — and carries on. On a fresh database V1 builds it. `V2` seeds the CV/About content (`ON CONFLICT DO NOTHING`, so never over the owner's edits), `V3` adds Spring Session's tables. **A schema change is a new `V4__…sql`, never an edit to V1** — V1 has already run everywhere.

Things that follow from Django's schema:

- **Foreign keys have no `ON DELETE CASCADE`** (Django cascades in Python), so `PostRepository.delete` removes comments, reactions and view-days first. Any new child table needs the same.
- **No column has a database default** on the production database (Django drops them after `AddField`), so every `INSERT` names every column.
- **Passwords are Django's `pbkdf2_sha256$iterations$salt$hash`**, read and written by `DjangoPasswordEncoder`. Any active `auth_user` row can write; there are no roles. A fresh database has no user: set `ADMIN_USERNAME`/`ADMIN_PASSWORD` and `AdminBootstrap` creates one on startup — only if missing, never resetting an existing one.
- Spring Session uses its own `spring_session` tables; Django's `django_session` is left alone. Switching backends logs the owner out once.

### Matching DRF, and where it was not free

- **Request bodies are read as plain JSON (`Object`) and validated by `Form`**, not bound to DTOs with Bean Validation. That is the only way to keep DRF's semantics: PATCH leaves absent fields alone, PUT requires required fields but also leaves absent optional ones alone, every field's error is collected at once, and the messages are DRF's words (the admin forms show them beside inputs). `ApiDocs` then has to describe each body by hand for Swagger — **keep it in step with the service's `read()` method.**
- **Error bodies are `{"detail": "..."}` or `{"field": ["..."]}`**, plus `non_field_errors`. The SPA's `describe()` in `services/api.ts` relies on both shapes, and retries a write once when a 403's `detail` contains "CSRF" — so those messages must keep saying CSRF.
- **Trailing slashes:** every SPA URL ends in `/`; Spring matches neither form to the other by default. `WebConfig` runs `UrlHandlerFilter` first so both work, and stashes the original path so pagination links echo it back. Controller mappings and security matchers are therefore written **without** the trailing slash.
- **CSRF is DRF's rule, not Spring's:** checked only for session-authenticated writes and for login (`SecurityConfig.needsCsrfCheck`). Anonymous writes (comments, reactions, `/view`) carry no token — the public pages never send one — and basic auth is never challenged (403, no `WWW-Authenticate`) so a browser never caches it. The token is a double-submit cookie named `csrftoken`, handed to the SPA in the body of `/api/auth/session/`, rotated on login and logout; `CsrfOriginCheck` adds Django's Origin check on top.
- **CORS never rejects.** An origin not in `CORS_ALLOWED_ORIGINS` just gets no CORS headers, as with django-cors-headers; Spring's default would answer it with a bare 403.
- **Visibility is decided once per resource** (`PostQuery`, `BookQuery`, `CommentQuery`) and used by the list *and* every route addressed by slug or id, so a draft is a 404 to a visitor everywhere. A new route on an existing resource should go through the same query.
- **Throttles** (`COMMENT_RATE_LIMIT`, `REACTION_RATE_LIMIT`) are in-memory sliding windows keyed by user or client address. Behind the tunnel the address is only per-visitor with `FORWARD_HEADERS_STRATEGY=native` (set in `docker-compose.prod.yml`).
- `Labels` (tags/genres) and `Slugs` reproduce Django's `clean_labels` and `slugify` exactly — slugs already in links have to keep matching.

### Tests

Every API test extends `support/ApiTestBase`: a real server on a random port, a real Postgres (zonky embedded binaries, started once per JVM) with the real migrations, and a fresh state per test (tables truncated, pages reseeded, throttles reset). Callers are `support/Api` instances over `java.net.http` — `client` (anonymous), `owner()` (basic auth, which skips CSRF as DRF's `force_authenticate` did), and `browser()` (keeps cookies, for the session/CSRF tests). Fixtures go through the services' `save()` so slugs and publish-stamping happen as in production; `update(table, id, columns)` writes columns directly for what the API cannot (view counts, timestamps). A test that needs different properties (rate limits, upload size) is its own class with `@TestPropertySource`, and so its own Spring context.

### Settings and the container

**Per-environment values are in `ziantsabit-be/config/<env>.env`** (`dev`, `stg`, `prod`; gitignored, only `example.env` is tracked), loaded by `run.sh`. They are written with the Compose names; for a host run `run.sh` maps `RUSTFS_*`, `SITE_ORIGINS` and `MEDIA_ORIGIN` onto the app's names and `host.docker.internal` onto `localhost`. A new compose-only rename needs the same mapping added there.

Everything environment-specific is in `application.yml`, reading the **same variable names the Django deployment used** (`POSTGRES_*`, `CORS_ALLOWED_ORIGINS`, `AWS_S3_*`, …; `DJANGO_ALLOWED_HOSTS` is read as a fallback for `ALLOWED_HOSTS`), so `docker-compose.prod.yml` works with the existing `.env` unchanged. `DJANGO_SECRET_KEY` is no longer used.

The `Dockerfile` is two stages (a Gradle-wrapper build on `eclipse-temurin:25-jdk`, then `eclipse-temurin:25-jre`), running as UID 1000. There is no entrypoint script: Flyway migrates at startup. **Postgres and RustFS are not in the compose stack** — they are separate, already-running servers named in `.env` (`POSTGRES_HOST`, `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`, `AWS_S3_ENDPOINT_URL`; `host.docker.internal` reaches the Docker host), and the bucket is created and made public-read by hand. With nothing to `depends_on`, a Postgres still booting fails Flyway and `restart: unless-stopped` retries. The healthcheck is `curl /actuator/health`, which is exempt from the allowed-hosts check because the container probes itself on 127.0.0.1.

**Kubernetes** (`ziantsabit-be/k8s/`, steps in its README) is the same image on the k3s cluster: namespace `ziantsabit`, one API replica behind a ClusterIP Service, and a two-pod `cloudflared` tunnel that routes straight to that Service. Three things in it are load-bearing:

- **The ConfigMap uses the app's variable names**, not the compose names in `config/<env>.env` — there is no `run.sh` in between to map them, so `RUSTFS_*` becomes `AWS_S3_ACCESS_KEY_ID`/`AWS_S3_SECRET_ACCESS_KEY` in the hand-made Secret.
- **One replica**, because the rate limits are in-memory per pod.
- **The tunnel bypasses Traefik.** `FORWARD_HEADERS_STRATEGY=native` trusts `X-Forwarded-For` from any private address; Traefik by default overwrites it with the caller's pod IP, which would put every visitor in one rate-limit bucket. Route through Traefik only after setting its `forwardedHeaders.trustedIPs`.

Probes use `/actuator/health/liveness` and `/readiness`, which Spring Boot enables itself on Kubernetes and which the allowed-hosts exemption (a path prefix) already covers.

## Frontend/backend seam

**This repo's frontend predates the backend it now talks to.** The section below describes the SPA as it is here, built against an early API with a fixed `?category=` filter. The backend (Django's final version, and this port of it) replaced categories with free-form `tags`, and added books, comments, reactions, page content and uploads — the newer SPA that uses those lives in the original `zian-tsabit-com` repository and has not been brought into this monorepo. Against this backend, `?category=` is simply ignored.

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
