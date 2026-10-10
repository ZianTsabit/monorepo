# Kubernetes: ziantsabit

The site on the home k3s cluster -- the [`ziantsabit-fe`](../../../projects/zian-tsabit-com/ziantsabit-fe) SPA and the
[`ziantsabit-be`](../../../projects/zian-tsabit-com/ziantsabit-be) API -- reached only through a Cloudflare Tunnel:

```
                                              ziantsabit.com, www -> Service ziantsabit-fe :80   -> nginx (2 pods)
visitor -> Cloudflare -> cloudflared (2 pods)
                                              api.ziantsabit.com  -> Service ziantsabit-be :8000 -> API pod
```

| File | Holds |
| --- | --- |
| `00-namespace.yaml` | Namespace `ziantsabit` -- its own file so it sorts first under `kubectl apply -f` on this folder |
| `ziantsabit-be.yaml` | The API: the non-secret ConfigMap, the Deployment and its ClusterIP Service |
| `ziantsabit-fe.yaml` | The SPA: nginx Deployment and its ClusterIP Service; no ConfigMap, the API URL is baked into the image |
| `cloudflared.yaml` | The tunnel connector |
| `external.yaml` | Services `postgres` and `rustfs` for the two servers outside the cluster, each with a hand-written EndpointSlice holding its IP |

Postgres and RustFS stay outside the cluster, as under compose. Secrets are
never committed: the two Secrets below are created by hand.

Every command below is run from the monorepo root.

## First deploy

1. **Fill in the RustFS IP** in `external.yaml` (it ships as `CHANGE-ME`), and check the cluster can reach both servers:

   ```bash
   kubectl run nettest --rm -it --image=busybox --restart=Never -- \
     sh -c 'nc -zvw3 10.0.0.50 5432; nc -zvw3 <rustfs-host> 9000'
   ```

2. **Push the images.** The cluster pulls from GHCR. The SPA's API address is
   compiled into its bundle, so it has to be passed at build time -- leave it
   out and every visitor's browser calls `localhost:8000`:

   ```bash
   docker build -t ghcr.io/ziantsabit/ziantsabit-be:1.0.0 projects/zian-tsabit-com/ziantsabit-be
   docker push ghcr.io/ziantsabit/ziantsabit-be:1.0.0

   docker build -t ghcr.io/ziantsabit/ziantsabit-fe:1.0.0 \
     --build-arg VITE_API_BASE_URL=https://api.ziantsabit.com/api projects/zian-tsabit-com/ziantsabit-fe
   docker push ghcr.io/ziantsabit/ziantsabit-fe:1.0.0
   ```

   If the packages are private, create a pull secret and uncomment
   `imagePullSecrets` in both Deployments:

   ```bash
   kubectl -n ziantsabit create secret docker-registry ghcr \
     --docker-server=ghcr.io --docker-username=ziantsabit --docker-password=<PAT with read:packages>
   ```

3. **Apply, then create the secrets.** Pods wait in `CreateContainerConfigError`
   until their Secret exists. The API's secret uses the app's names, mapped from
   the compose names in the backend's `config/prod.env`:

   ```bash
   kubectl apply -f deployments/k8s/ziantsabit/
   set -a; source projects/zian-tsabit-com/ziantsabit-be/config/prod.env; set +a
   kubectl -n ziantsabit create secret generic ziantsabit-be \
     --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
     --from-literal=AWS_S3_ACCESS_KEY_ID="$RUSTFS_ACCESS_KEY" \
     --from-literal=AWS_S3_SECRET_ACCESS_KEY="$RUSTFS_SECRET_KEY"
   kubectl -n ziantsabit create secret generic cloudflared --from-literal=token=<tunnel token>
   ```

4. **Watch it come up.** Flyway runs at startup:

   ```bash
   kubectl -n ziantsabit rollout status deploy/ziantsabit-be
   kubectl -n ziantsabit rollout status deploy/ziantsabit-fe
   kubectl -n ziantsabit logs deploy/ziantsabit-be -f
   kubectl -n ziantsabit port-forward svc/ziantsabit-be 8000:8000   # then curl localhost:8000/actuator/health
   ```

   The port-forward only answers `/actuator/health`; anything else is a 400
   because `localhost` is not in `ALLOWED_HOSTS`.

## Cutting over from compose

Create a **new** tunnel for the cluster rather than reusing the one the
compose host runs. Connectors of one tunnel share its traffic, so a cluster pod
and the old host would each get half the requests, and the old host cannot
resolve a `.svc.cluster.local` route anyway.

1. Create the tunnel in the dashboard, put its token in the `cloudflared`
   Secret, and give it throwaway hostnames (e.g. `api-k8s.ziantsabit.com`
   routed to `http://ziantsabit-be.ziantsabit.svc.cluster.local:8000`, and
   `k8s.ziantsabit.com` to `http://ziantsabit-fe.ziantsabit.svc.cluster.local:80`).
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

```bash
# API
docker build -t ghcr.io/ziantsabit/ziantsabit-be:<version> projects/zian-tsabit-com/ziantsabit-be && docker push ghcr.io/ziantsabit/ziantsabit-be:<version>
kubectl -n ziantsabit set image deploy/ziantsabit-be api=ghcr.io/ziantsabit/ziantsabit-be:<version>

# SPA -- the build arg every time, it is not remembered between builds
docker build -t ghcr.io/ziantsabit/ziantsabit-fe:<version> \
  --build-arg VITE_API_BASE_URL=https://api.ziantsabit.com/api projects/zian-tsabit-com/ziantsabit-fe && docker push ghcr.io/ziantsabit/ziantsabit-fe:<version>
kubectl -n ziantsabit set image deploy/ziantsabit-fe web=ghcr.io/ziantsabit/ziantsabit-fe:<version>
```

Then bump the tag in `ziantsabit-be.yaml` / `ziantsabit-fe.yaml` too, or the
next `kubectl apply` rolls back. The two are versioned independently.

An API schema change runs on the new pod's startup, before the old one
stops, so a migration must work for the version still running.
