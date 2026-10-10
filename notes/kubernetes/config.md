# Config: settings kept out of the image

Build one image, run it anywhere, and give it its environment-specific settings
from outside. Non-secret settings go in a **ConfigMap**, credentials in a
**Secret**. Both are key/value maps used the same two ways.

## ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: ziantsabit-be, namespace: ziantsabit-prod }
data:
  POSTGRES_HOST: postgres
  POSTGRES_PORT: "5432"      # values are always strings: quote numbers and booleans
  SESSION_COOKIE_SECURE: "true"
```

Example: the API's settings in
[`ziantsabit-be.yaml`](../../deployments/k8s/ziantsabit/ziantsabit-be.yaml).

## Secret

Same shape, for passwords, tokens, keys, TLS certificates.

```bash
# Create by hand so the value never lands in git:
kubectl -n ziantsabit-prod create secret generic ziantsabit-be \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
kubectl -n ziantsabit-prod create secret generic cloudflared --from-literal=token="$TOKEN"
```

In YAML, `data:` values are **base64** (and `stringData:` takes plain text that
gets encoded for you). Base64 is encoding, not encryption -- anyone who can read
the Secret can read the value:

```bash
kubectl get secret ziantsabit-be -n ziantsabit-prod -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d
```

What makes Secrets safer than ConfigMaps is mostly *policy*: RBAC can let
someone read ConfigMaps but not Secrets, and the cluster can encrypt them at rest
(k3s: `--secrets-encryption`). To keep secrets in git, encrypt them first with a
tool like Sealed Secrets or SOPS, or pull them from a vault with External Secrets.

Secret types you'll meet: `Opaque` (default, anything), `kubernetes.io/tls`
(`tls.crt` + `tls.key`), `kubernetes.io/dockerconfigjson` (registry login, used
by `imagePullSecrets` -- `kubectl create secret docker-registry`).

## Using them in a pod

**All keys as env vars** -- what the API does:

```yaml
envFrom:
  - configMapRef: { name: ziantsabit-be }
  - secretRef:    { name: ziantsabit-be }
```

**One key as one env var** -- what cloudflared does:

```yaml
env:
  - name: TUNNEL_TOKEN
    valueFrom:
      secretKeyRef: { name: cloudflared, key: token }
```

**As files** -- each key becomes a file in the mounted folder:

```yaml
volumes:
  - name: config
    configMap: { name: nginx-conf }
containers:
  - volumeMounts:
      - { name: config, mountPath: /etc/nginx/conf.d, readOnly: true }
```

## Changes and restarts

- **Env vars** are read once, when the container starts. Editing the
  ConfigMap/Secret changes nothing in running pods until they restart:
  `kubectl rollout restart deploy/<name> -n <ns>`. Kustomize's
  `configMapGenerator` (what this site uses) avoids that: the ConfigMap's name
  ends in a hash of its contents, so a changed value means a new name in the
  Deployment, which rolls it.
- **Mounted files** do update in place after a short delay (about a minute) --
  but only if the app re-reads them, and not for `subPath` mounts.
- A pod referencing a ConfigMap/Secret that doesn't exist won't start
  (`CreateContainerConfigError`); it starts on its own once you create it.
- `immutable: true` on either kind forbids edits -- protects against accidents
  and lightens the API server's load. Change it by creating a new one with a new name.
