# Kustomize: one set of manifests, several environments

`kustomization.yaml` is **not** a Kubernetes object -- the cluster never sees
it. It's a recipe that `kubectl` reads on your machine to *produce* ordinary
YAML (Deployments, Services, ...), which is then applied as usual. Kustomize is
built into `kubectl`, so there's nothing to install.

The problem it solves: stg and prod are 95% the same. Copying the folder means
fixing every bug twice and watching the copies drift apart. Kustomize keeps one
**base** and, per environment, an **overlay** holding only the differences.

This site's layout, in [`deployments/k8s/ziantsabit/`](../../deployments/k8s/ziantsabit/):

```
base/                    # shared: Deployments, Services, external.yaml, shared settings
  kustomization.yaml
overlays/
  stg/kustomization.yaml # namespace ziantsabit-stg, stg settings, tags, 1 SPA replica
  prod/kustomization.yaml# namespace ziantsabit-prod, prod settings, tags
cloudflared/             # standalone, not an overlay: one tunnel serves both
  kustomization.yaml
```

## Commands

```bash
kubectl kustomize <dir>      # print the YAML it produces -- check this first
kubectl diff -k <dir>        # what applying would change on the cluster
kubectl apply -k <dir>       # build + apply in one go
kubectl delete -k <dir>      # delete everything it produces
```

`-k` takes a folder containing a `kustomization.yaml`; `-f` takes plain YAML.
Pointing `-f` at a folder with a kustomization ignores the kustomization and
applies the raw files -- with no namespace, in this layout.

## The base

Plain manifests, listed under `resources`, plus anything common to every
environment:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - external.yaml
  - ziantsabit-be.yaml
  - ziantsabit-fe.yaml
configMapGenerator:
  - name: ziantsabit-be
    envs: [ziantsabit-be.env]
```

The base's manifests leave out `metadata.namespace`; the overlay decides.

## An overlay

Points `resources` at the base and layers changes on top:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: ziantsabit-stg        # stamped onto every object
resources:
  - namespace.yaml
  - ../../base                   # a folder = another kustomization
configMapGenerator:
  - name: ziantsabit-be
    behavior: merge              # add/override keys of the base's ConfigMap
    envs: [ziantsabit-be.env]
replicas:
  - name: ziantsabit-fe
    count: 1
images:
  - name: ghcr.io/ziantsabit/ziantsabit-fe
    newTag: 1.0.0-stg
```

## The fields worth knowing

| Field | Does |
| --- | --- |
| `resources` | Files or folders to include. A folder is built as its own kustomization first. Can also be a remote git URL |
| `namespace` | Sets `metadata.namespace` on every namespaced object, overriding whatever the files say |
| `namePrefix` / `nameSuffix` | Renames every object (`stg-ziantsabit-be`) and fixes references to them -- an alternative to separate namespaces |
| `labels` | Adds labels to every object; with `includeSelectors: true` also to selectors (careful: a Deployment's selector can't change after creation) |
| `images` | Swaps an image's tag (`newTag`), name (`newName`) or pins a `digest`, wherever that image appears |
| `replicas` | Overrides `replicas` on a named Deployment/StatefulSet |
| `configMapGenerator` | Builds a ConfigMap from `envs` files, `files`, or `literals` |
| `secretGenerator` | Same for Secrets -- but the values then sit in git (see below) |
| `patches` | Edit anything else in the base's objects (see below) |
| `components` | Optional, reusable chunks of changes an overlay can opt into (e.g. "add a NetworkPolicy"); for when two overlays share a change the base shouldn't have |

## Generators and the hash suffix

A generated ConfigMap's name gets a hash of its contents appended:
`ziantsabit-be` becomes `ziantsabit-be-6b997kg8g7`. Kustomize also rewrites
every reference to it -- the Deployment's `envFrom.configMapRef` -- to the new
name. So changing one value means a new name, which changes the Deployment's
pod template, which **rolls the pods**. No forgetting `rollout restart` after
editing config.

Sources:

```yaml
configMapGenerator:
  - name: app
    envs: [app.env]                 # KEY=value lines -> one key each
    files: [nginx.conf]             # whole file -> one key named after it
    literals: [LOG_LEVEL=info]
```

In an `envs` file the rest of the line after `=` is the value, spaces and all;
don't quote it, or the quotes become part of the value. `#` lines are comments.

`behavior` in an overlay: `create` (default; errors if the base already has
one), `merge` (add and override keys), `replace` (throw the base's keys away).

Old hashed ConfigMaps are left behind on the cluster after each change. They're
harmless; clean up now and then, or use `kubectl apply --prune` (needs care).

`options: { disableNameSuffixHash: true }` turns the suffix off -- for a
ConfigMap something outside the kustomization refers to by its fixed name.

**secretGenerator** works the same way, but its source files are plaintext in
the repo. Keep using hand-made Secrets (or SOPS / Sealed Secrets) and only
*reference* them by name, as this site does -- Kustomize leaves references to
objects it didn't generate alone.

## Patches

For changes the dedicated fields don't cover. Two styles:

**Strategic merge** -- write the parts that change, in the object's own shape;
lists of containers are matched by `name`:

```yaml
patches:
  - target: { kind: Deployment, name: ziantsabit-be }
    patch: |-
      apiVersion: apps/v1
      kind: Deployment
      metadata: { name: ziantsabit-be }
      spec:
        template:
          spec:
            containers:
              - name: api
                resources: { requests: { memory: 256Mi } }
```

**JSON 6902** -- a list of exact operations by path; good for one precise edit:

```yaml
patches:
  - target: { kind: EndpointSlice, name: postgres }
    patch: |-
      - op: replace
        path: /endpoints/0/addresses/0
        value: "10.0.0.60"
```

`patch:` can also be `path: some-file.yaml`. `target` can select by `kind`,
`name`, `namespace`, or `labelSelector`, so one patch can hit many objects.

## Gotchas

- **Check the output.** `kubectl kustomize <dir>` before every apply is cheap
  and catches most mistakes -- a typo'd `name` in `images` or `replicas`
  doesn't error, it silently matches nothing.
- **Only references Kustomize knows about get rewritten.** It fixes
  `configMapRef`, `secretRef`, volumes, Service selectors and so on, but not a
  name buried in an env var value or a command-line argument.
- **`namespace:` does not fix hardcoded DNS names.**
  `ziantsabit-be.ziantsabit-stg.svc.cluster.local` written in a file stays as
  written. Bare Service names (`postgres`, `rustfs`) resolve within the pod's own
  namespace, which is why the base uses them.
- **Don't change tags with `kubectl set image`** on a Kustomize-managed
  Deployment: the next `apply -k` puts the overlay's tag back. Change
  `images:` in the overlay instead.
- **The Kustomize in kubectl lags the standalone one** a little.
  `kubectl version --client` shows which you have.

## Kustomize vs Helm

| | Kustomize | Helm |
| --- | --- | --- |
| Approach | Patch plain YAML | Templates with variables (`{{ .Values.x }}`) |
| Base files | Valid manifests you can read and apply | Templates, unreadable until rendered |
| Install/upgrade/rollback as a unit | No (just `apply`) | Yes, a "release" with history |
| Sweet spot | Your own apps across a few environments | Packaging an app for others; installing third-party software |

Common combination: install other people's software (Traefik, cert-manager,
Longhorn) with Helm, and deploy your own with Kustomize. Kustomize can even
render a Helm chart via `helmCharts:` (needs `--enable-helm`).
