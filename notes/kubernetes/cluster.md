# Cluster-level objects: organising, access, limits

## Namespace

A folder for objects. Names only have to be unique within a namespace, RBAC and
quotas can be set per namespace, and deleting a namespace deletes everything in
it. This site has three: `ziantsabit-stg` and `ziantsabit-prod` (one per
environment, from the overlays in
[`deployments/k8s/ziantsabit/`](../../deployments/k8s/ziantsabit/)) and `cloudflared`
for the tunnel both share.

- Most kinds are **namespaced** (Pod, Deployment, Service, ConfigMap, Secret, PVC).
- Some are **cluster-wide** (Namespace, Node, PersistentVolume, StorageClass,
  ClusterRole). `kubectl api-resources --namespaced=false` lists them.
- Built in: `default`, `kube-system` (cluster components -- in k3s that includes
  Traefik, CoreDNS, metrics-server), `kube-public`, `kube-node-lease`.
- Namespaces are **not** a network boundary: pods can reach Services in other
  namespaces unless a NetworkPolicy says otherwise.

The namespace has to exist before anything is created in it. Kustomize
(`kubectl apply -k`) takes care of the order, applying the Namespace first.

## Node

A machine in the cluster (k3s server or agent -- see [architecture.md](architecture.md)
for what runs on each). Created when the machine joins, not by you.

```bash
kubectl get nodes -o wide
kubectl describe node <name>        # capacity, what's allocated, conditions
kubectl cordon <name>               # no new pods here
kubectl drain <name> --ignore-daemonsets --delete-emptydir-data   # move pods off, for maintenance
kubectl uncordon <name>
```

Labels on nodes (`kubernetes.io/hostname`, or your own) are what
`nodeSelector`, affinity and `topologySpreadConstraints` match against. The
`topologyKey: kubernetes.io/hostname` in this site's manifests means "spread
across different nodes".

**Taints** on a node repel pods; a pod needs a matching **toleration** to land
there. Used to keep ordinary workloads off control-plane or special-purpose nodes.

## ServiceAccount

An identity for a *pod* (users are for people). Every namespace has a `default`
ServiceAccount, and pods use it unless `spec.serviceAccountName` says otherwise.
Its token is mounted into the pod, letting the app call the Kubernetes API with
whatever permissions RBAC gives it. Apps that never call the API can set
`automountServiceAccountToken: false`.

## RBAC: who may do what

Four kinds, two pairs:

| | Namespaced | Cluster-wide |
| --- | --- | --- |
| **The permissions** | Role | ClusterRole |
| **Who gets them** | RoleBinding | ClusterRoleBinding |

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: read-pods, namespace: ziantsabit-prod }
rules:
  - apiGroups: [""]               # "" = core group (pods, services, secrets, ...)
    resources: [pods, pods/log]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: ci-reads-pods, namespace: ziantsabit-prod }
subjects:
  - { kind: ServiceAccount, name: ci, namespace: ziantsabit-prod }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: read-pods }
```

- Permissions only add up; there's no "deny".
- A RoleBinding may point at a ClusterRole, granting it in just that namespace --
  handy for reusing the built-ins `view`, `edit`, `admin`.
- Check: `kubectl auth can-i list secrets -n ziantsabit-prod --as=system:serviceaccount:ziantsabit-prod:ci`.

## ResourceQuota

Caps a namespace's total: CPU and memory requests/limits, number of pods,
Services, PVCs, storage.

```yaml
apiVersion: v1
kind: ResourceQuota
metadata: { name: quota, namespace: ziantsabit-prod }
spec:
  hard:
    requests.cpu: "2"
    requests.memory: 4Gi
    pods: "20"
```

Once a quota covers CPU or memory, every pod in the namespace **must** declare
those requests/limits or it's rejected -- pair it with a LimitRange.

## LimitRange

Per-container defaults and bounds inside a namespace: fills in `requests`/`limits`
for containers that don't set them, and rejects ones outside min/max.

```yaml
apiVersion: v1
kind: LimitRange
metadata: { name: defaults, namespace: ziantsabit-prod }
spec:
  limits:
    - type: Container
      defaultRequest: { cpu: 50m, memory: 64Mi }
      default:        { memory: 256Mi }   # becomes the limit
```

## CustomResourceDefinition (CRD)

How the list of kinds grows. Installing a CRD teaches the API server a new kind
-- Traefik's `IngressRoute`, cert-manager's `Certificate` -- and the tool that
installed it runs a controller acting on those objects. `kubectl get crd` shows
what's installed.
