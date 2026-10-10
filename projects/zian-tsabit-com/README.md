# ziantsabit.com

My portfolio website — a React SPA plus a Spring Boot content API.

The two halves are independent projects that happen to share a repo. Each has its own `Dockerfile` and `docker-compose.yml`, and there is **no root-level build, package manager or compose file** tying them together: you run and build them separately, from their own directories.

```
ziantsabit-fe/        React 19 + TypeScript + Vite  (the live site)
ziantsabit-be/        Java 25 + Spring Boot 4       (content API)
```

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| Node.js | **20.19+ or 22.12+** | Required by Vite 7. On Node 18, `npm run build` still passes but `npm run dev` fails with `TypeError: crypto.hash is not a function`. |
| Java | 25 | Any JDK 25. Gradle is not needed: `./gradlew` fetches it. |
| PostgreSQL | 16+ | Required to *run* the backend, even outside Docker — there is no in-memory fallback. `docker compose up db` starts one for you. The test suite brings its own. |
| Docker | Compose v2 | Optional — only for the containerised runs below. |

---

## Frontend

All commands run from `ziantsabit-fe/`.

```bash
cd ziantsabit-fe
npm install
npm run dev
```

The dev server prints its URL, normally <http://localhost:5173>. Hot reload is on, and client-side routes (`/about`, `/books`, …) work directly.

| Command | What it does |
| --- | --- |
| `npm run dev` | Vite dev server with hot reload |
| `npm run build` | `tsc -b` then `vite build` → output in `dist/` |
| `npm run preview` | Serves the built `dist/` locally |
| `npm run lint` | ESLint over the project |

### Building

```bash
npm run build
```

`build` type-checks before it bundles, under `strict`, `noUnusedLocals` and `noUnusedParameters`. **An unused import that `npm run dev` happily ignores will fail the build**, so run this before considering a change finished.

### Frontend in Docker

```bash
cd ziantsabit-fe
docker compose up --build
```

Multi-stage build: Node compiles the bundle, then nginx serves it on <http://localhost:8080>.

Routing is client-side, so `dist/` contains no `about.html` — any static host must rewrite unknown paths to `index.html` or a refresh on `/about` returns 404. `nginx.conf` does that with `try_files $uri $uri/ /index.html`; `npm run dev` and `npm run preview` have the same fallback built in. If you deploy the `dist/` folder somewhere else, that rewrite is on you.

---

## Backend

Full documentation is in [`ziantsabit-be/README.md`](ziantsabit-be/README.md); the essentials follow.

All commands run from `ziantsabit-be/`. It is a Spring Boot app over Postgres, with no in-memory fallback, so start a database first — the compose file's `db` service is published on `127.0.0.1:5432` with the credentials the app defaults to:

```bash
cd ziantsabit-be
docker compose up -d db rustfs storage-init   # Postgres, plus the image bucket
ADMIN_USERNAME=me ADMIN_PASSWORD=change-me ./gradlew bootRun
```

The API is then at <http://localhost:8000/api/> and Swagger UI at <http://localhost:8000/api/docs/>. Flyway creates the schema on startup. `ADMIN_USERNAME`/`ADMIN_PASSWORD` create the account writes need, if it does not exist yet; they replace `manage.py createsuperuser`, and never touch an existing account.

**It was a Django backend until October 2026, and it still uses Django's database.** The tables keep their Django names (`myapp_post`, `auth_user`, …), Django's password hashes still verify, and every response has the same JSON shape — a database the Django backend built is picked up as-is, with no data migration, and the Django image could be pointed back at it. See `CLAUDE.md` for how.

### Endpoints

| Method | Path | |
| --- | --- | --- |
| `GET` | `/api/posts/` | List, 20 per page. `?tag=`, `?status=`, `?ordering=recent\|updated\|views`, `?published_after=`, `?published_before=` |
| `POST` | `/api/posts/` | Create |
| `GET` `PUT` `PATCH` `DELETE` | `/api/posts/{slug}/` | One post |
| `POST` | `/api/posts/{slug}/view/` | Count a read (open to anyone) |
| `GET` `POST` | `/api/posts/{slug}/reactions/` | The emoji bar; POST toggles one (open to anyone, rate-limited) |
| `GET` | `/api/posts/tags/` | Every tag in use, with counts |
| `GET` | `/api/posts/stats/` | Statistics for the admin page (owner only) |
| `GET` `POST` | `/api/books/` | The book catalogue, 12 per page. `?genre=`, `?search=`, `?status=`, `?ordering=` |
| `GET` `PUT` `PATCH` `DELETE` | `/api/books/{slug}/` | One book |
| `GET` | `/api/books/genres/` | Every genre in use, with counts |
| `GET` `POST` | `/api/comments/` | Comments; `?post=<slug>` for one thread. POST open to anyone, rate-limited |
| `GET` `PUT` `PATCH` `DELETE` | `/api/comments/{id}/` | One comment (writes: owner only) |
| `GET` | `/api/pages/` | The CV and About page documents |
| `GET` `PUT` `PATCH` | `/api/pages/{cv\|about}/` | One page |
| `POST` | `/api/uploads/images/` | Upload an image, get its URL back (owner only) |
| `GET` / `POST` / `POST` | `/api/auth/session/`, `/login/`, `/logout/` | Session login for the SPA's admin page |
| `GET` | `/api/docs/` · `/api/redoc/` · `/api/schema/` | Swagger UI · ReDoc · OpenAPI 3 document |
| `GET` | `/actuator/health` | Health check |

Every path works with or without its trailing slash. Posts and books are addressed by **slug, not id**; a slug is generated from the title when you leave it out.

**Reads are public; writes need the owner** — except a visitor's comment, reaction or read count. From a terminal, basic auth:

```bash
curl http://localhost:8000/api/posts/

curl -u me:change-me -X POST http://localhost:8000/api/posts/ \
  -H "Content-Type: application/json" \
  -d '{"title": "Clean Code", "tags": ["Books"], "status": "published"}'
```

Anonymous callers only ever see published posts and books — on the detail routes too, not just the lists. An unrecognised `?status=` or `?ordering=` is a `400`, not an empty list.

There is no Django admin any more; the SPA's own `/admin` page is the editor.

### Tests

```bash
./gradlew test                                    # whole suite (312 tests)
./gradlew test --tests BookApiTest                # one class
./gradlew test --tests 'BookApiTest$Pagination'   # one nested group
```

The suite starts its own Postgres (an embedded binary, no Docker needed) and drives the real server over HTTP, so cookies, CORS and CSRF are exercised as a browser meets them. It is a port of the Django suite, test for test, plus checks that Django's password hashes still verify.

### Backend in Docker

```bash
cd ziantsabit-be
docker compose up --build
```

Starts `db` (Postgres 16), `rustfs` (S3-compatible storage for uploaded images) and a one-shot `storage-init` that creates the bucket, then `api` once they are ready. Flyway migrates on startup. The image is built from source — there is no bind mount or autoreload, so for an edit loop run `./gradlew bootRun` on the host against `docker compose up db rustfs storage-init`.

Production is `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`, reading secrets from `ziantsabit-be/.env`; add `-f docker-compose.external-db.yml` for a Postgres on another host. The variable names are unchanged from the Django deployment, so an existing `.env` works as-is.

```bash
docker compose logs -f api
docker compose down
```

### Environment variables

Read by `src/main/resources/application.yml`; the defaults are what a bare `./gradlew bootRun` uses.

| Variable | Default | |
| --- | --- | --- |
| `POSTGRES_DB` / `_USER` / `_PASSWORD` | `zian_tsabit_be` / `zian_tsabit_be` / `postgres` | Dev-only password default |
| `POSTGRES_HOST` / `_PORT` | `localhost` / `5432` | `db` inside docker-compose |
| `POSTGRES_SSLMODE` / `_SSLROOTCERT` | `prefer` / — | For a database across a network |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | — | Create this account at startup if missing |
| `ALLOWED_HOSTS` | `localhost 127.0.0.1 [::1]` | Space-separated. `DJANGO_ALLOWED_HOSTS` is still read as a fallback |
| `CORS_ALLOWED_ORIGINS` | `:5173`, `:4173`, `:8080` on localhost and 127.0.0.1 | Space-separated. Must include whichever origin serves the SPA |
| `CSRF_TRUSTED_ORIGINS` | the CORS list | Origins allowed to make session-authenticated writes |
| `SESSION_COOKIE_SECURE` / `CSRF_COOKIE_SECURE` | `false` | `true` behind HTTPS |
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` behind a TLS-terminating proxy |
| `AWS_S3_ENDPOINT_URL` / `AWS_S3_PUBLIC_ENDPOINT_URL` | `http://localhost:9000` | API→bucket and browser→bucket addresses |
| `AWS_STORAGE_BUCKET_NAME`, `AWS_S3_ACCESS_KEY_ID`, `AWS_S3_SECRET_ACCESS_KEY` | `ziantsabit-media`, `rustfsadmin`, `rustfsadmin` | |
| `MAX_UPLOAD_SIZE` | `10485760` | Bytes |
| `COMMENT_RATE_LIMIT` / `REACTION_RATE_LIMIT` | `20/hour` / `60/min` | Per client address |

See `ziantsabit-be/.env.example` for a copy-pasteable `.env`.

---

## Ports

| | |
| --- | --- |
| `5173` | Frontend, `npm run dev` |
| `4173` | Frontend, `npm run preview` |
| `8080` | Frontend, Docker (nginx) |
| `8000` | Backend, local or Docker |

## Running the two together

`/books`, `/projects` and `/posts` fetch their content from the API, so they need the backend up. Two terminals:

```bash
# terminal 1
cd ziantsabit-be && docker compose up -d db && ./gradlew bootRun

# terminal 2
cd ziantsabit-fe && npm run dev
```

The SPA reads its API location from `VITE_API_BASE_URL`, defaulting to `http://localhost:8000/api` — the default is correct for the setup above, so there is nothing to configure locally. To point it elsewhere, copy `ziantsabit-fe/.env.example` to `.env`. **Vite inlines env vars at build time**, so a change means restarting `npm run dev` or rebuilding.

The two are always on different origins, so every request is cross-origin. `CORS_ALLOWED_ORIGINS` on the backend already lists `:5173`, `:4173` and `:8080`; **an origin missing from that list gets its responses discarded by the browser**, which surfaces as the same "Could not reach the API" message as a backend that is simply down.

Each of those three pages has four states: a spinner, an error with a **Retry** button, the original `Coming soon...` placeholder when the category has no posts, and a list of cards once it does. Add content through the SPA's `/admin` page or `POST /api/posts/` — remember `status: "published"`, since drafts are invisible to the site.

## Not wired up yet

Home's "Latest Updates" section is still a `Coming soon...` placeholder, and `src/services/Updates.tsx` is still an empty stub — posts have no "update" category, so that feed has no backend behind it. `/`, `/about` and `/curriculum-vitae` are hardcoded copy by design.
