# Storage: data that outlives a container

A container's filesystem is thrown away when the container is replaced.
Anything that must survive goes on a **volume**. This site keeps no data in the
cluster -- Postgres and RustFS run outside it -- so none of this appears in its
manifests yet.

## Volumes in a pod

Declared in `spec.volumes`, mounted per container with `volumeMounts`.
Common kinds:

| Volume | Lives as long as | Use for |
| --- | --- | --- |
| `emptyDir` | The pod | Scratch space, cache, files shared between containers in the pod |
| `configMap` / `secret` | -- (read from the object) | Config files, certificates (see [config.md](config.md)) |
| `persistentVolumeClaim` | The claim, independent of any pod | Real data: databases, uploads |
| `hostPath` | The node's disk | Node agents only; ties the pod to one node, and is a security risk |

```yaml
spec:
  volumes:
    - name: tmp
      emptyDir: {}
  containers:
    - volumeMounts:
        - { name: tmp, mountPath: /tmp }
```

`emptyDir` is also the fix for `readOnlyRootFilesystem: true` when an app needs
somewhere writable like `/tmp`.

## PersistentVolumeClaim (PVC)

A request for storage: "give me 10Gi I can mount read-write". The app asks for
a claim; it doesn't care where the disk comes from.

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: data, namespace: ziantsabit }
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: local-path
  resources: { requests: { storage: 10Gi } }
```

Then in the pod: `volumes: [{ name: data, persistentVolumeClaim: { claimName: data } }]`.

**Access modes:**

- `ReadWriteOnce` (RWO) -- mounted read-write by one **node** at a time. The usual one.
- `ReadWriteOncePod` -- by one **pod** only.
- `ReadOnlyMany` (ROX) / `ReadWriteMany` (RWX) -- many nodes at once; needs shared
  storage such as NFS or Longhorn's RWX mode.

## PersistentVolume (PV)

The actual piece of storage -- a disk, an NFS share, a folder on a node. A PVC
**binds** to one PV, one-to-one. You almost never create PVs by hand anymore: a
StorageClass creates them when a claim appears (*dynamic provisioning*).

`kubectl get pv,pvc -A` shows both; a PVC stuck in `Pending` means nothing could
provide what it asked for.

## StorageClass

A recipe for making PVs: which provisioner, with which options. A PVC names one
(or gets the default).

- k3s ships **`local-path`** as the default: a folder on the node where the pod
  first ran. Simple, but the data is tied to that node -- if the node dies, so
  does the data, and the pod can't move elsewhere.
- For data that should survive a node, use replicated storage such as
  **Longhorn**, or an NFS provisioner.

Two settings worth knowing:

- `reclaimPolicy: Delete` (default for dynamic) deletes the disk when the PVC is
  deleted; `Retain` keeps it for manual cleanup. Check this before deleting a PVC.
- `volumeBindingMode: WaitForFirstConsumer` waits to create the disk until a pod
  uses it, so it's made on the node the pod was scheduled to.

## StatefulSet + volumeClaimTemplates

For workloads where each replica needs *its own* disk, a StatefulSet creates one
PVC per pod (`data-db-0`, `data-db-1`, ...) and reattaches the same one when the
pod is recreated. Those PVCs are not deleted when the StatefulSet is scaled down
or deleted unless you set `persistentVolumeClaimRetentionPolicy`.
