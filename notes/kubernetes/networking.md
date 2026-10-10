# Networking: how traffic finds pods

Pods come and go with new IPs. Networking objects give them stable names and
decide what may reach them.

## Service

A stable virtual IP and DNS name in front of a set of pods, load-balancing
across the **ready** ones (pods failing their readiness probe are left out).

```yaml
apiVersion: v1
kind: Service
metadata: { name: ziantsabit-fe, namespace: ziantsabit-prod }
spec:
  selector: { app: ziantsabit-fe }   # which pods
  ports:
    - name: http
      port: 80          # what clients connect to
      targetPort: http  # the container port (by name or number)
```

**DNS names.** Every Service gets
`<service>.<namespace>.svc.cluster.local`. From a pod in the same namespace the
bare name works (`http://ziantsabit-be:8000`); from another namespace use at
least `<service>.<namespace>`.

### Service types

| Type | Reachable from | Notes |
| --- | --- | --- |
| `ClusterIP` (default) | Inside the cluster only | What this site uses; the Cloudflare tunnel is the only way in |
| `NodePort` | `<any node IP>:<30000-32767>` | Opens the port on every node |
| `LoadBalancer` | An external IP | Cloud providers create a real load balancer; k3s uses its built-in ServiceLB (klipper) to expose it on node IPs |
| `ExternalName` | -- | Pure DNS alias (CNAME) to an outside hostname; no proxying, no IPs |

**Headless Service** (`clusterIP: None`): no virtual IP; DNS returns the pod
IPs directly. Used with StatefulSets so each pod gets its own name.

**Service without a selector**: matches no pods; you write the EndpointSlice
yourself. This is how [`external.yaml`](../../deployments/k8s/ziantsabit/external.yaml)
gives Postgres and RustFS -- servers outside the cluster -- in-cluster names.

## EndpointSlice

The actual list of addresses (IP + port, plus ready/not-ready) behind a Service.

- **Service with a selector:** Kubernetes writes and updates it as pods change.
  You only ever read it.
- **Service without a selector:** you write it, and it never changes on its
  own -- and has no health checking, so traffic keeps going to a dead address.

kube-proxy on each node watches these and programs the routing that sends
traffic for the Service IP to one of the listed addresses.

```yaml
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: postgres
  labels:
    kubernetes.io/service-name: postgres                     # ties it to the Service
    endpointslice.kubernetes.io/managed-by: ziantsabit-manifests   # "not the controller's"
addressType: IPv4
ports:
  - name: postgres   # must equal the Service's port name
    port: 5432
endpoints:
  - addresses: ["10.0.0.50"]
```

"Slice" because a big Service's addresses are split across several objects
(100 each by default). It replaced the older single-list **Endpoints** object,
deprecated since 1.33 -- old tutorials still show `kind: Endpoints`.

Debugging: `kubectl get endpointslices -n <ns>` -- an empty list means the
Service's selector matched no ready pods.

## Ingress

HTTP(S) routing from outside into Services by hostname and path, with TLS. It's
only a set of rules: an **Ingress controller** must be running to act on them.
k3s ships **Traefik** as its controller.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
spec:
  ingressClassName: traefik
  tls: [{ hosts: [example.com], secretName: example-tls }]
  rules:
    - host: example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: web, port: { number: 80 } } }
```

This site doesn't use one: cloudflared points straight at the Services. Going
through Traefik would put its own address in `X-Forwarded-For` and the API would
see every visitor as one client (see `FORWARD_HEADERS_STRATEGY` in
`ziantsabit-be.yaml`).

## Gateway API

The successor to Ingress: `GatewayClass` → `Gateway` (the listener, owned by
whoever runs the cluster) → `HTTPRoute` (the routing, owned by each app).
More expressive -- header matching, traffic splitting, TCP/gRPC routes -- and
splits responsibility cleanly. Ingress still works and isn't going away, but new
features land in Gateway API. Traefik supports both.

## NetworkPolicy

A firewall between pods. With no policy, every pod can reach every other pod.
Once *any* policy selects a pod, only traffic a policy allows is let in (or out,
for `Egress`).

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: be-only-from-cloudflared, namespace: ziantsabit-prod }
spec:
  podSelector: { matchLabels: { app: ziantsabit-be } }   # who this protects
  policyTypes: [Ingress]
  ingress:
    - from:
        # Both in ONE list item = cloudflared pods in the cloudflared namespace.
        # A bare podSelector only looks in the policy's own namespace.
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: cloudflared } }
          podSelector: { matchLabels: { app: cloudflared } }
      ports: [{ port: 8000 }]
```

Careful with the dashes: `namespaceSelector` and `podSelector` under one `-`
mean "both must match"; as two separate `-` items they mean "either", which
would let in every pod of the cloudflared namespace *and* every `app:
cloudflared` pod anywhere.

Needs a network plugin that enforces it; k3s enforces it out of the box (its
built-in network policy controller with Flannel).
