# ziantsabit-be

The content API behind [ziantsabit.com](https://ziantsabit.com): posts, a book catalogue, comments, reactions, the CV and About pages, and image uploads for the admin editor.

Spring Boot 4.1 on Java 25, over PostgreSQL 18: Spring MVC, Spring Security with Spring Session JDBC, Bean Validation, Flyway, springdoc, and plain `JdbcClient` SQL.

## Contents

- [Quick start](#quick-start)
- [Commands](#commands)
- [Project layout](#project-layout)
- [API](#api)
- [Authentication](#authentication)
- [Configuration](#configuration)
- [Database and migrations](#database-and-migrations)
- [Tests](#tests)
- [Docker and deployment](#docker-and-deployment)
- [Upgrading from 1.x](#upgrading-from-1x)

## Quick start

You need **JDK 25**, a reachable Postgres server and an S3-compatible bucket (RustFS); neither is started by this repo. Gradle is not needed — `./gradlew` downloads it.

```bash
cp config/example.env config/dev.env   # then point it at your Postgres + RustFS
./run.sh dev                           # loads config/dev.env, then ./gradlew bootRun
```

- API: <http://localhost:8000/api/>
- Swagger UI: <http://localhost:8000/api/docs/> · OpenAPI document: <http://localhost:8000/api/schema/>
- Health: <http://localhost:8000/actuator/health>

Flyway creates the schema on startup. `ADMIN_USERNAME` / `ADMIN_PASSWORD` create the account that writes need, if it does not exist yet — they never change an existing account, so leaving them set is harmless.

## Commands

| Command | |
| --- | --- |
| `./gradlew bootRun` | Run the API on `:8000` |
| `./gradlew test` | The whole suite (starts its own Postgres, no Docker needed) |
| `./gradlew test --tests BookApiTest` | One test class |
| `./gradlew test --tests 'BookApiTest$Pagination'` | One `@Nested` group |
| `./gradlew bootJar` | Build `build/libs/ziantsabit-be-2.0.0.jar` |
| `./run.sh <dev\|stg\|prod>` | `bootRun` with `config/<env>.env` loaded; extra arguments go to `bootRun` |
| `./run.sh <dev\|stg\|prod> --docker` | The API in a container with that file (`stg`/`prod` add the production override); extra arguments go to `docker compose up` |

## Project layout

```
src/main/java/com/ziantsabit/api/
├── controller/   HTTP routes only — parameters, status codes, Swagger annotations
├── service/      Business rules: validation, slugs, visibility, reactions, stats, uploads
├── repository/   SQL, one class per table (JdbcClient, no ORM)
├── query/        PostQuery, BookQuery, CommentQuery — which rows a request may see, in what order
├── model/        Stored records: Post, Book, Comment, PageContent, UserAccount, Reaction, *Fields
├── dto/          Request records (PostRequest, BookRequest, …; Bean Validation) and response shapes
├── validation/   RequestBodies (read, merge, validate), @Required/@OneOf/@HttpUrl, QueryParams, Isbn, ImageFormat, PageDocuments
├── security/     UserAccounts, LegacyPbkdf2PasswordEncoder, CsrfOriginCheck, Throttle, Viewer
├── exception/    ApiException, Throttled, ApiExceptionHandler
├── storage/      ObjectStorage + its S3 implementation
├── util/         Slugs, Labels, Sql
└── config/       SecurityConfig, WebConfig, OpenApiConfig, AppProperties

src/main/resources/
├── application.yml
└── db/migration/   V1–V4 (see Database and migrations)

src/test/java/com/ziantsabit/api/
├── controller/   HTTP-level suites, one per resource
├── security/     PasswordTest — bcrypt, and the upgrade of pre-V4 hashes on login
├── service/      AdminBootstrapTest
├── support/      ApiTestBase, the HTTP client, in-memory storage
└── PlatformTest  Accounts, schema, health, hosts, error shapes
```

A request always goes **controller → service → repository**; controllers never use a repository directly.

## API

All paths work with or without a trailing slash. Lists are paginated as `{count, next, previous, results}`, with `?page=N`.

### Posts

| Method | Path | |
| --- | --- | --- |
| `GET` | `/api/posts/` | List, 20 per page |
| `POST` | `/api/posts/` | Create |
| `GET` `PATCH` `DELETE` | `/api/posts/{slug}/` | One post |
| `POST` | `/api/posts/{slug}/view/` | Count a read — open to anyone |
| `GET` `POST` | `/api/posts/{slug}/reactions/` | The emoji bar; `POST {"emoji", "visitor"}` toggles one — open to anyone, rate-limited |
| `GET` | `/api/posts/tags/` | Every tag in use, with counts, commonest first |
| `GET` | `/api/posts/stats/` | Aggregates for the admin statistics page — owner only |

List parameters: `?tag=` (case-insensitive), `?status=draft|published` (owner only), `?ordering=recent|updated|views`, `?published_after=YYYY-MM-DD`, `?published_before=YYYY-MM-DD`.

### Books

| Method | Path | |
| --- | --- | --- |
| `GET` `POST` | `/api/books/` | List (12 per page) / create |
| `GET` `PATCH` `DELETE` | `/api/books/{slug}/` | One book |
| `GET` | `/api/books/genres/` | Every genre in use, with counts |

List parameters: `?genre=`, `?search=` (title, author or ISBN, hyphens optional), `?status=` (owner only), `?ordering=recent|title|author|year`.

### Comments, pages, uploads

| Method | Path | |
| --- | --- | --- |
| `GET` `POST` | `/api/comments/` | List (oldest first) / post one — `POST` is open to anyone and rate-limited |
| `GET` `PATCH` `DELETE` | `/api/comments/{id}/` | One comment; writes are the owner's (moderation) |
| `GET` | `/api/pages/` | Both page documents |
| `GET` `PATCH` | `/api/pages/{cv\|about}/` | One page; only `data` is writable |
| `POST` | `/api/uploads/images/` | `multipart/form-data` with a `file`; returns `{url, name}` — owner only |

Comment list parameters: `?post=<slug>`, `?status=published|hidden` (owner only), `?search=`, `?ordering=oldest|newest`.

### Behaviour worth knowing

- **Posts and books are addressed by slug.** Leave `slug` out and one is generated from the title; send it to pin a URL. A blank slug is a `400`.
- **Visitors only ever see published content** — on detail routes too, so a draft is a `404` to anyone not logged in. Hidden comments, and comments on drafts, are invisible to visitors the same way.
- **An unknown `?status=` or `?ordering=` is a `400`, not an empty list.** An unused tag or genre is an empty list, since those are free text.
- **`PATCH` is a JSON merge** onto the stored record: a field left out keeps its value, an explicit `null` clears a nullable one, and the result is validated like a create — so a required field stays required. Read-only fields sent back (`id`, `view_count`, …) are ignored. There is no `PUT`.
- **Errors** are `{"detail": "..."}`, or `{"field": ["..."]}` for validation, every bad field at once (with `non_field_errors` for problems not tied to one field).
- Publishing a post without `published_at` stamps it with the current time.

## Authentication

Reads are public. Writes need the owner — any enabled account in `app_user` — except a visitor's comment, reaction or read count.

**From a terminal**, use basic auth:

```bash
curl -u me:change-me -X POST http://localhost:8000/api/posts/ \
  -H "Content-Type: application/json" \
  -d '{"title": "Clean Code", "tags": ["Books"], "status": "published"}'
```

**From the SPA's admin page**, use the session flow:

1. `GET /api/auth/session/` → `{authenticated, username, csrf_token}`
2. `POST /api/auth/login/` with `{username, password}` and the token in `X-CSRFToken` → a `SESSION` cookie and a **new** `csrf_token`
3. Every write sends `X-CSRFToken` with the latest token and the cookie (`credentials: "include"`)
4. `POST /api/auth/logout/` ends the session

A refused anonymous write is a `403`, never a `401`, so browsers never show their own login prompt. CSRF is a double-submit token: the `XSRF-TOKEN` cookie (HttpOnly; the SPA takes the value from the session response instead) must match the `X-CSRFToken` header. It is checked only on session-authenticated writes and on login; anonymous and basic-auth requests need none.

Passwords are stored by Spring Security's `DelegatingPasswordEncoder` as `{bcrypt}…`. A hash from before V4 is stored as `{pbkdf2-legacy}…`, verified by `LegacyPbkdf2PasswordEncoder`, and rewritten as bcrypt on the first successful login (`UserAccounts` is the `UserDetailsPasswordService`).

## Configuration

Everything is set by environment variables, read in `src/main/resources/application.yml`. The defaults suit local development.

Each environment's values live in `config/<env>.env` — `dev.env`, `stg.env`, `prod.env` — and `./run.sh <env>` loads one. Copy [`config/example.env`](config/example.env) to get started. The files are committed, so secrets are written as Vault references, `POSTGRES_PASSWORD=vault:secret/ziantsabit/prod/be` (the field defaults to the variable's name; `#FIELD` names another), and fetched with `vault kv get` when the file is loaded — see [`vault-env.sh`](vault-env.sh). That needs `VAULT_ADDR` and a `vault login`; a file without references needs neither. Write the files with the Compose names (`RUSTFS_*`, `SITE_ORIGINS`, `MEDIA_ORIGIN`, `host.docker.internal`): for a host run, `run.sh` maps them onto the names below, so one file serves both modes.

| Variable | Default | |
| --- | --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `zian_tsabit_be` / `zian_tsabit_be` / `postgres` | |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` | `host.docker.internal` for a server on the Docker host |
| `POSTGRES_SSLMODE` / `POSTGRES_SSLROOTCERT` | `prefer` / — | `require` or `verify-full` for a database across a network |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | — | Create this account at startup if it is missing |
| `ALLOWED_HOSTS` | `localhost 127.0.0.1 [::1]` | Space-separated hostnames this API answers to |
| `CORS_ALLOWED_ORIGINS` | `:5173`, `:4173`, `:8080` on localhost | Space-separated. **Must include the SPA's origin**, or the browser discards every response |
| `CSRF_TRUSTED_ORIGINS` | the CORS list | Origins allowed to make session-authenticated writes |
| `SESSION_COOKIE_SECURE` / `CSRF_COOKIE_SECURE` | `false` | `true` behind HTTPS |
| `SESSION_COOKIE_SAMESITE` / `CSRF_COOKIE_SAMESITE` | `lax` | |
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` behind a TLS-terminating proxy, so the scheme and client address come from `X-Forwarded-*` |
| `AWS_S3_ENDPOINT_URL` | `http://localhost:9000` | Where the API reaches the bucket |
| `AWS_S3_PUBLIC_ENDPOINT_URL` | the endpoint | Where **browsers** load images from — written into every upload's URL |
| `AWS_STORAGE_BUCKET_NAME` / `AWS_S3_ACCESS_KEY_ID` / `AWS_S3_SECRET_ACCESS_KEY` | `ziantsabit-media` / `rustfsadmin` / `rustfsadmin` | |
| `MAX_UPLOAD_SIZE` | `10485760` | Bytes |
| `COMMENT_RATE_LIMIT` / `REACTION_RATE_LIMIT` | `20/hour` / `60/min` | Per client address, `<count>/<second\|minute\|hour\|day>` |

## Database and migrations

Postgres only — there is no in-memory or embedded fallback for running the app.

Migrations are Flyway scripts in `src/main/resources/db/migration/`, applied automatically at startup:

| Script | |
| --- | --- |
| `V1__schema.sql` | The original schema, every statement `IF NOT EXISTS` |
| `V2__seed_page_content.sql` | The initial CV and About content; never overwrites existing rows |
| `V3__spring_session.sql` | Spring Session's tables |
| `V4__plain_schema.sql` | The schema under its own names: tables renamed, constraints and indexes rebuilt, cascading foreign keys, column defaults, the account table trimmed |

**To change the schema, add a new `V5__description.sql`. Never edit an existing script**: they have already run everywhere. V1–V3 still name the tables as they were before V4; that is history, and V4 is where the current names come from.

The tables are `post`, `post_view_day`, `book`, `comment`, `reaction`, `page_content` and `app_user`, plus Spring Session's. A post's comments, reactions and view days reference it `ON DELETE CASCADE`, so deleting a post is one statement; a new child table should do the same.

## Tests

```bash
./gradlew test
```

328 tests. The suite needs no Docker and no database of your own: it downloads and starts an embedded PostgreSQL 18.4 (production's version) once per run, applies the real migrations, and drives the real server on a random port over HTTP — so cookies, CORS and CSRF are exercised as a browser meets them. Uploads go to an in-memory bucket.

Each test starts from a clean database (only the seeded pages). Three callers are available in `ApiTestBase`: `client` (anonymous), `owner()` (basic auth) and `browser()` (keeps cookies, for session tests). A test that needs different configuration, such as a lower rate limit, is its own class with `@TestPropertySource`.

## Docker and deployment

```bash
./run.sh dev --docker     # = docker compose up --build, with config/dev.env exported
```

Starts `api` only. Postgres and RustFS are your own servers, named in the env file: `POSTGRES_HOST`, `POSTGRES_PASSWORD`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY` and `AWS_S3_ENDPOINT_URL` are required (see [`config/example.env`](config/example.env)). From inside the container, a server on the Docker host is `host.docker.internal`. The bucket must already exist, be public-read on its objects (`s3:GetObject`, not `s3:ListBucket`) and allow cross-origin `GET`/`HEAD` — the admin's story cards draw covers onto a canvas, which needs it.

There is no `depends_on`: if Postgres is still booting, Flyway fails, the container exits and `restart: unless-stopped` retries until it connects. The image is built from source in two stages (Gradle on a JDK, then a JRE-only runtime) and runs as UID 1000. There is no source mount, so for an edit loop use `./gradlew bootRun`.

**Production** (and staging) layers an override on top, reading secrets from `config/prod.env` (or `stg.env`):

```bash
./run.sh prod --docker -d
# = docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build -d, with config/prod.env exported
```

It additionally requires `ALLOWED_HOSTS`, `SITE_ORIGINS` and `MEDIA_ORIGIN`, and binds the API to `127.0.0.1:8000` for the Cloudflare tunnel.

The container health check is `GET /actuator/health`.

**Kubernetes.** [`deployments/k8s/ziantsabit/`](../../../deployments/k8s/ziantsabit/) at the monorepo root runs the same image on the k3s cluster, in a staging and a production namespace, behind one in-cluster `cloudflared`, with the env as a ConfigMap plus a hand-made Secret. See [its README](../../../deployments/k8s/ziantsabit/README.md) for the first deploy, the cut-over from compose and releases.

## Upgrading from 1.x

2.0.0 runs V4 on startup, which renames every table and drops the ones the API never read. Before deploying it to an environment:

- **Take a `pg_dump` of that database.** After V4 the 1.x image cannot read it, so a rollback is restoring the dump and redeploying 1.x — not just moving the image tag back.
- **Rename `DJANGO_ALLOWED_HOSTS` to `ALLOWED_HOSTS`** in any `.env` used with compose; the old name is no longer read.
- **Expect one fresh login.** The session and CSRF cookies are now Spring's (`SESSION`, `XSRF-TOKEN`). Your existing password keeps working and is re-hashed as bcrypt when you log in.
- **No `PUT` any more**, and no `/api/` index or `/api/redoc/` page. The SPA uses none of them.

Validation messages are worded differently (e.g. "Must be at most 200 characters."); the JSON shapes are unchanged.
