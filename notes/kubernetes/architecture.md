# Architecture: servers and agents

A cluster is machines (**nodes**) in two roles. Upstream Kubernetes calls them
**control-plane** and **worker** nodes; k3s calls them **servers** and
**agents**.

This cluster (`kubectl get nodes -o wide`):

```
NAME      ROLES           INTERNAL-IP
k8s-cp1   control-plane   192.168.0.150   ← k3s server
k8s-w1    worker          192.168.0.151   ← k3s agent
k8s-w2    worker          192.168.0.152   ← k3s agent
```

```
                 kubectl
                    │ :6443
┌───────────────────▼──────────────────┐
│ server (k8s-cp1)                     │
│  API server ◄── datastore            │
│  scheduler, controller manager       │
│  + everything an agent runs          │
└───────▲──────────────────▲───────────┘
        │ :6443            │ :6443
┌───────┴────────┐  ┌──────┴─────────┐
│ agent (k8s-w1) │  │ agent (k8s-w2) │
│ kubelet        │  │ kubelet        │
│ containerd     │  │ containerd     │
│ kube-proxy     │  │ kube-proxy     │
│ flannel        │  │ flannel        │
└────────────────┘  └────────────────┘
```

## Server: the control plane

Where the cluster's state is stored and its decisions are made.

| Component | Does |
| --- | --- |
| **API server** | The front door. `kubectl`, the controllers and every agent talk only to it, on port 6443 |
| **Datastore** | Stores every object (Deployments, Secrets, EndpointSlices, ...). A single k3s server uses SQLite; several servers use embedded etcd. Upstream Kubernetes always uses etcd |
| **Scheduler** | Picks a node for each new pod, from its `requests`, node selectors, affinity, taints and spread constraints |
| **Controller manager** | Runs the controllers: the loops that compare what you asked for with what exists and fix the difference (e.g. make a new pod when one dies) |

A k3s server also runs everything an agent runs, so it can run pods too.
Upstream clusters usually taint control-plane nodes to keep ordinary pods off
them; k3s doesn't by default.

## Agent: a worker

Only runs workloads.

| Component | Does |
| --- | --- |
| **kubelet** | Asks the API server which pods belong on this node, starts them, runs their probes, reports status back |
| **containerd** | The container runtime: pulls images and runs containers |
| **kube-proxy** | Programs the routing that sends traffic for a Service IP to the addresses in its EndpointSlices |
| **Flannel** | The pod network (k3s's default CNI), so pods on different nodes can reach each other |

An agent joins by connecting to a server with the cluster token:

```bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.0.150:6443 K3S_TOKEN=<token> sh -
# token is on the server: /var/lib/rancher/k3s/server/node-token
```

Agents never talk to one another's components; everything goes through the
API server.

## How a Deployment turns into running containers

1. `kubectl apply` sends the Deployment to the **API server**, which saves it in the **datastore**.
2. The Deployment controller (**controller manager**) sees it and creates a ReplicaSet; the ReplicaSet controller creates pods. They have no node yet.
3. The **scheduler** sees unscheduled pods and assigns each a node.
4. That node's **kubelet** sees a pod assigned to it and has **containerd** pull the image and start it.
5. Once its readiness probe passes, the pod's IP goes into the Service's EndpointSlice, and **kube-proxy** on every node starts routing to it.

Each step only reads and writes objects through the API server. No component
calls another directly.

## What happens when a node goes down

- **An agent:** after about 5 minutes its pods are marked for eviction, and controllers recreate them on the remaining nodes. Spreading replicas across nodes is what keeps a service up meanwhile; a single-replica Deployment is down until its pod is rescheduled.
- **The only server:** pods already running keep running and keep serving traffic. Nothing can *change*, though: no `kubectl`, no deploys, no replacement of crashed pods, no rescheduling. A single point of failure for managing the cluster, not for serving it.

## High availability

Run **three servers** with embedded etcd (`--cluster-init` on the first). It
has to be an odd number, because etcd needs a majority to agree: three
survive losing one; two survive losing none. Put a fixed address in front of
them (a load balancer or a virtual IP) for agents and `kubectl` to use.

For a home cluster, one server plus backups is a reasonable choice. With
SQLite, back up `/var/lib/rancher/k3s/server/db/` and the token at
`/var/lib/rancher/k3s/server/token`; with etcd, use `k3s etcd-snapshot`.

## Useful commands

```bash
kubectl get nodes -o wide                          # roles, IPs, versions
kubectl get nodes -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints
kubectl describe node k8s-w1                       # capacity, allocated, conditions
kubectl get pods -A -o wide                        # which node each pod is on
kubectl get pods -n kube-system                    # k3s's own components: coredns, traefik, metrics-server, ...

# on the machines themselves
sudo systemctl status k3s          # on a server
sudo systemctl status k3s-agent    # on an agent
sudo k3s kubectl get nodes         # works on a server without a kubeconfig
```

See [cluster.md](cluster.md#node) for draining, cordoning, taints and node labels.
