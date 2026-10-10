# Kubernetes

Notes on the kinds of objects Kubernetes has, what each is for, and how they
connect. Examples point at the real manifests in
[`deployments/k8s/ziantsabit/`](../../deployments/k8s/ziantsabit/) where there is one.

| File | Covers |
| --- | --- |
| [architecture.md](architecture.md) | Servers (control plane) and agents (workers): what runs on each, how a Deployment becomes containers, what fails when a node goes down |
| [workloads.md](workloads.md) | Pod, Deployment, ReplicaSet, StatefulSet, DaemonSet, Job, CronJob, HorizontalPodAutoscaler, PodDisruptionBudget |
| [networking.md](networking.md) | Service (and its types), EndpointSlice, Ingress, Gateway API, NetworkPolicy |
| [config.md](config.md) | ConfigMap, Secret |
| [storage.md](storage.md) | PersistentVolume, PersistentVolumeClaim, StorageClass, volumes in general |
| [cluster.md](cluster.md) | Namespace, ServiceAccount, RBAC, ResourceQuota, LimitRange, Node |
| [kustomize.md](kustomize.md) | Not an object: `kustomization.yaml`, base and overlays, generators, patches -- how this site gets stg and prod from one set of manifests |

## The one idea everything rests on

You don't tell Kubernetes *what to do*; you tell it *what you want to exist*.
Each object is a record of desired state, and a **controller** keeps comparing
it with reality and fixing the difference. Delete a pod a Deployment owns and a
new one appears, because the Deployment still says "2 replicas".

`kubectl apply -f file.yaml` just stores the record. Everything after that is
controllers doing their job.

## Shape of every object

```yaml
apiVersion: apps/v1        # API group + version this kind lives in
kind: Deployment           # which type of object
metadata:
  name: ziantsabit-fe      # unique per kind, per namespace
  namespace: ziantsabit-prod   # omitted for cluster-wide kinds (Namespace, Node, ...)
  labels:                  # key/value tags; how objects find each other
    app: ziantsabit-fe
  annotations: {}          # key/value notes for tools; never used for selection
spec: {}                   # what you want -- you write this
status: {}                 # what is -- Kubernetes writes this; never put it in YAML
```

`apiVersion` differs by kind: core kinds (Pod, Service, ConfigMap, Secret,
Namespace) are plain `v1`; Deployments are `apps/v1`; EndpointSlices are
`discovery.k8s.io/v1`; Ingress and NetworkPolicy are `networking.k8s.io/v1`.
`kubectl api-resources` lists every kind with its group.

## Labels and selectors: the glue

Objects rarely reference each other by name. Instead one object carries
labels and another *selects* on them:

```
Deployment ──creates──► Pods  (labels: app=ziantsabit-fe)
                          ▲
Service ──selector: app=ziantsabit-fe── finds them by label
```

So a typo in a label doesn't error -- it silently matches nothing. When a
Service "doesn't work", compare its `selector` with the pods' labels first:

```bash
kubectl get pods -n ziantsabit-prod --show-labels
kubectl get endpointslices -n ziantsabit-prod   # empty = selector matched no ready pods
```

## How a request reaches this site

```
visitor → Cloudflare → cloudflared pods ──► Service ziantsabit-fe ──► fe pods
           (tunnel)    (Deployment)     └─► Service ziantsabit-be ──► be pod ──► Service postgres ──► 10.0.0.50
                                                                            └──► Service rustfs   ──► RustFS server
                                                    ConfigMap + Secret ──env──┘
```

## kubectl you'll use every day

```bash
kubectl get <kind> -n <ns>                 # list (add -o wide or -o yaml)
kubectl describe <kind> <name> -n <ns>     # details + recent events: read this first when stuck
kubectl logs deploy/ziantsabit-be -n ziantsabit-prod [-f] [--previous]
kubectl exec -it deploy/ziantsabit-be -n ziantsabit-prod -- sh
kubectl apply -f <file-or-folder>          # create or update to match the file
kubectl diff -f <file-or-folder>           # what apply would change
kubectl delete -f <file>
kubectl rollout restart|status|undo deploy/<name> -n <ns>
kubectl explain deployment.spec.strategy   # built-in docs for any field
kubectl get events -n <ns> --sort-by=.lastTimestamp
```

Tip: `kubectl config set-context --current --namespace=ziantsabit-prod` saves
typing `-n ziantsabit-prod` every time.
