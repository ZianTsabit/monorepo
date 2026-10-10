# Kubernetes

The API on the home k3s cluster, reached only through a Cloudflare Tunnel:

```
visitor -> Cloudflare -> cloudflared (2 pods) -> Service ziantsabit-be :8000 -> API pod
```

| File | Holds |
| --- | --- |
| `00-namespace.yaml` | Namespace `ziantsabit` -- its own file so it sorts first under `kubectl apply -f k8s/` |
| `ziantsabit-be.yaml` | The non-secret ConfigMap, the Deployment and its ClusterIP Service |
| `cloudflared.yaml` | The tunnel connector |

Postgres and RustFS stay outside the cluster, as under compose. Secrets are
never committed: the two Secrets below are created by hand.

## First deploy

1. **Fill in `AWS_S3_ENDPOINT_URL`** in the ConfigMap (it ships as `CHANGE-ME`),
   and check the cluster can reach both servers:

   ```bash
   kubectl run nettest --rm -it --image=busybox --restart=Never -- \
     sh -c 'nc -zvw3 10.0.0.50 5432; nc -zvw3 <rustfs-host> 9000'
   ```

2. **Push the image.** The cluster pulls from GHCR:

   ```bash
   docker build -t ghcr.io/ziantsabit/ziantsabit-be:1.0.0 .
   docker push ghcr.io/ziantsabit/ziantsabit-be:1.0.0
   ```

   If the package is private, create a pull secret and uncomment
   `imagePullSecrets` in the Deployment:

   ```bash
   kubectl -n ziantsabit create secret docker-registry ghcr \
     --docker-server=ghcr.io --docker-username=ziantsabit --docker-password=<PAT with read:packages>
   ```

3. **Apply, then create the secrets.** Pods wait in `CreateContainerConfigError`
   until their Secret exists. The API's secret uses the app's names, mapped from
   the compose names in `config/prod.env`:

   ```bash
   kubectl apply -f k8s/
   set -a; source config/prod.env; set +a
   kubectl -n ziantsabit create secret generic ziantsabit-be \
     --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
     --from-literal=AWS_S3_ACCESS_KEY_ID="$RUSTFS_ACCESS_KEY" \
     --from-literal=AWS_S3_SECRET_ACCESS_KEY="$RUSTFS_SECRET_KEY"
   kubectl -n ziantsabit create secret generic cloudflared --from-literal=token=<tunnel token>
   ```

4. **Watch it come up.** Flyway runs at startup:

   ```bash
   kubectl -n ziantsabit rollout status deploy/ziantsabit-be
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
   Secret, and give it a throwaway hostname (e.g. `api-k8s.ziantsabit.com`)
   routed to `http://ziantsabit-be.ziantsabit.svc.cluster.local:8000`. Smoke-test
   it — expect `ALLOWED_HOSTS` to reject that hostname until you add it.
2. Stop the compose container. Both would otherwise run against the same
   database with separate in-memory rate limits.
3. Move the `api.ziantsabit.com` public hostname from the old tunnel to the new
   one.

Rolling back is the same in reverse; the database is untouched either way.

## Releasing a new version

```bash
docker build -t ghcr.io/ziantsabit/ziantsabit-be:<version> . && docker push ghcr.io/ziantsabit/ziantsabit-be:<version>
kubectl -n ziantsabit set image deploy/ziantsabit-be api=ghcr.io/ziantsabit/ziantsabit-be:<version>
```

Then bump the tag in `ziantsabit-be.yaml` too, or the next `kubectl apply`
rolls back. A schema change runs on the new pod's startup, before the old one
stops, so a migration must work for the version still running.
