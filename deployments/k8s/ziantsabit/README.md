# Kubernetes: ziantsabit

The site on the home k3s cluster -- the [`ziantsabit-fe`](../../../projects/zian-tsabit-com/ziantsabit-fe) SPA and the
[`ziantsabit-be`](../../../projects/zian-tsabit-com/ziantsabit-be) API -- in two environments, production and
staging, both reached only through one shared Cloudflare Tunnel:

```
                                           ziantsabit.com, www    -> ziantsabit-prod: Service ziantsabit-fe :80   -> nginx (2 pods)
                                           api.ziantsabit.com     -> ziantsabit-prod: Service ziantsabit-be :8000 -> API pod
visitor -> Cloudflare -> cloudflared ──────
           (one tunnel)  (ns cloudflared)  stg.ziantsabit.com     -> ziantsabit-stg:  Service ziantsabit-fe :80   -> nginx (1 pod)
                                           stg-api.ziantsabit.com -> ziantsabit-stg:  Service ziantsabit-be :8000 -> API pod
```

Built with [Kustomize](https://kustomize.io/) (part of `kubectl`, `apply -k`):
one shared `base/`, and an overlay per environment holding only what differs.

| Path | Holds |
| --- | --- |
| `base/` | Everything both environments share: the API and SPA Deployments and Services, `external.yaml` (Services `postgres` and `rustfs` for the two servers outside the cluster, each with a hand-written EndpointSlice holding its IP), and the API settings common to both (`ziantsabit-be.env`) |
| `overlays/prod/` | Namespace `ziantsabit-prod`, prod's API settings, and the image tags running in prod |
| `overlays/stg/` | Namespace `ziantsabit-stg`, staging's API settings, its image tags, and one SPA replica instead of two |
| `cloudflared/` | The tunnel connector, in its own namespace `cloudflared` since it serves both |

What differs between the two:

| | prod | stg |
| --- | --- | --- |
| Namespace | `ziantsabit-prod` | `ziantsabit-stg` |
| Site / API | `ziantsabit.com`, `www` / `api.ziantsabit.com` | `stg.ziantsabit.com` / `stg-api.ziantsabit.com` |
| Media | `media.ziantsabit.com` | `stg-media.ziantsabit.com` |
| Database and user | `zian_tsabit_be` | `stg_zian_tsabit_be` |
| Bucket | `ziantsabit-media` | `stg-ziantsabit-media` |
| SPA replicas | 2 | 1 |

Both use the same Postgres and RustFS servers. Postgres names use `_`, not
`-`: a hyphenated name has to be double-quoted in every SQL statement.

Each environment's API settings are generated into a ConfigMap whose name ends
in a hash of its contents, so editing an `.env` file and re-applying rolls the
API by itself -- no `rollout restart`. Secrets are never committed: the API's
come from Vault, through the backend's `config/<env>.env` references, and
cloudflared's is created by hand.

To see exactly what an overlay produces, or what applying it would change:

```bash
kubectl kustomize deployments/k8s/ziantsabit/overlays/stg
kubectl diff -k deployments/k8s/ziantsabit/overlays/stg
```

Every command below is run from the monorepo root.

## First deploy

1. **Fill in the RustFS IP** in `base/external.yaml` (it ships as `CHANGE-ME`),
   and check the cluster can reach both servers:

   ```bash
   kubectl run nettest --rm -it --image=busybox --restart=Never -- \
     sh -c 'nc -zvw3 10.0.0.50 5432; nc -zvw3 <rustfs-host> 9000'
   ```

2. **Create staging's database and bucket.** Nothing here creates them. On the
   Postgres server:

   ```sql
   CREATE USER stg_zian_tsabit_be WITH PASSWORD '<stg password>';
   CREATE DATABASE stg_zian_tsabit_be OWNER stg_zian_tsabit_be;
   ```

   In RustFS, create the bucket `stg-ziantsabit-media` with the same public-read
   policy as `ziantsabit-media`, ideally with an access key that can only touch
   that bucket. Serve it at `stg-media.ziantsabit.com` the same way
   `media.ziantsabit.com` serves prod's -- upload URLs are path-style,
   `https://stg-media.ziantsabit.com/stg-ziantsabit-media/<key>`.

3. **Push the images.** The cluster pulls from GHCR. The API image is the same
   for both environments. The SPA's API address is compiled into its bundle, so
   it is built once per environment -- leave the build arg out and every
   visitor's browser calls `localhost:8000`:

   ```bash
   docker build -t ghcr.io/ziantsabit/ziantsabit-be:1.0.0 projects/zian-tsabit-com/ziantsabit-be
   docker push ghcr.io/ziantsabit/ziantsabit-be:1.0.0

   docker build -t ghcr.io/ziantsabit/ziantsabit-fe:1.0.0-stg \
     --build-arg VITE_API_BASE_URL=https://stg-api.ziantsabit.com/api projects/zian-tsabit-com/ziantsabit-fe
   docker push ghcr.io/ziantsabit/ziantsabit-fe:1.0.0-stg

   docker build -t ghcr.io/ziantsabit/ziantsabit-fe:1.0.0 \
     --build-arg VITE_API_BASE_URL=https://api.ziantsabit.com/api projects/zian-tsabit-com/ziantsabit-fe
   docker push ghcr.io/ziantsabit/ziantsabit-fe:1.0.0
   ```

   If the packages are private, uncomment `imagePullSecrets` in both base
   Deployments and create the pull secret in each namespace:

   ```bash
   for ns in ziantsabit-stg ziantsabit-prod; do
     kubectl -n $ns create secret docker-registry ghcr \
       --docker-server=ghcr.io --docker-username=ziantsabit --docker-password=<PAT with read:packages>
   done
   ```

4. **Apply, then create the secrets.** Pods wait in `CreateContainerConfigError`
   until their Secret exists. The API's secret uses the app's names:

   ```bash
   kubectl apply -k deployments/k8s/ziantsabit/cloudflared
   kubectl apply -k deployments/k8s/ziantsabit/overlays/stg
   kubectl apply -k deployments/k8s/ziantsabit/overlays/prod

   kubectl -n cloudflared create secret generic cloudflared --from-literal=token=<tunnel token>

   # Every Vault reference in config/<env>.env, fetched and renamed to the
   # app's names (RUSTFS_* -> AWS_S3_*). Rerun after rotating a value in
   # Vault, then `kubectl -n ziantsabit-<env> rollout restart deploy/ziantsabit-be`.
   for env in stg prod; do
     projects/zian-tsabit-com/ziantsabit-be/vault-env.sh $env \
       | kubectl -n ziantsabit-$env create secret generic ziantsabit-be \
           --from-env-file=/dev/stdin --dry-run=client -o yaml \
       | kubectl apply -f -
   done
   ```

5. **Add staging's public hostnames** to the tunnel in the Cloudflare
   dashboard, as listed at the top of `cloudflared/cloudflared.yaml`:
   `stg.ziantsabit.com` and `stg-api.ziantsabit.com`.

6. **Watch it come up.** Flyway runs at startup:

   ```bash
   kubectl -n ziantsabit-stg rollout status deploy/ziantsabit-be
   kubectl -n ziantsabit-stg rollout status deploy/ziantsabit-fe
   kubectl -n ziantsabit-stg logs deploy/ziantsabit-be -f
   kubectl -n ziantsabit-stg port-forward svc/ziantsabit-be 8000:8000   # then curl localhost:8000/actuator/health
   ```

   The port-forward only answers `/actuator/health`; anything else is a 400
   because `localhost` is not in `ALLOWED_HOSTS`. Same for `ziantsabit-prod`.

## Cutting over from compose

Create a **new** tunnel for the cluster rather than reusing the one the
compose host runs. Connectors of one tunnel share its traffic, so a cluster pod
and the old host would each get half the requests, and the old host cannot
resolve a `.svc.cluster.local` route anyway.

Staging needs no cut-over: it has no compose counterpart, so its hostnames go
straight onto the new tunnel (step 5 above). For prod:

1. Create the tunnel in the dashboard, put its token in the `cloudflared`
   Secret, and give it throwaway hostnames (e.g. `api-k8s.ziantsabit.com`
   routed to `http://ziantsabit-be.ziantsabit-prod.svc.cluster.local:8000`, and
   `k8s.ziantsabit.com` to `http://ziantsabit-fe.ziantsabit-prod.svc.cluster.local:80`).
   Smoke-test them — expect `ALLOWED_HOSTS` to reject the API hostname until
   you add it. The SPA at its throwaway hostname still calls the real
   `api.ziantsabit.com`, and CORS will drop those responses unless
   `https://k8s.ziantsabit.com` is in `CORS_ALLOWED_ORIGINS` for the test.
2. Stop the API's compose container. Both would otherwise run against the same
   database with separate in-memory rate limits.
3. Move the `api.ziantsabit.com`, `ziantsabit.com` and `www.ziantsabit.com`
   public hostnames from the old tunnel to the new one, then stop the
   frontend's compose container.

Rolling back is the same in reverse; the database is untouched either way.

## Releasing a new version

Release to staging first, then promote the same version to prod. The API image
is promoted as-is; the SPA is rebuilt for prod's API address under the same
version number.

```bash
# API -- built once
docker build -t ghcr.io/ziantsabit/ziantsabit-be:<version> projects/zian-tsabit-com/ziantsabit-be && docker push ghcr.io/ziantsabit/ziantsabit-be:<version>

# SPA -- built per environment, with the build arg every time
docker build -t ghcr.io/ziantsabit/ziantsabit-fe:<version>-stg \
  --build-arg VITE_API_BASE_URL=https://stg-api.ziantsabit.com/api projects/zian-tsabit-com/ziantsabit-fe && docker push ghcr.io/ziantsabit/ziantsabit-fe:<version>-stg
```

Set the tags under `images:` in `overlays/stg/kustomization.yaml`, then
`kubectl apply -k deployments/k8s/ziantsabit/overlays/stg`. Once staging looks
right, build `ziantsabit-fe:<version>` with
`VITE_API_BASE_URL=https://api.ziantsabit.com/api`, set the same tags in
`overlays/prod/kustomization.yaml` and apply that overlay.

Change tags in the overlay files, not with `kubectl set image`, or the next
`apply -k` rolls back. The API and SPA are versioned independently.

An API schema change runs on the new pod's startup, before the old one
stops, so a migration must work for the version still running. Staging runs
every migration against its own database first.
