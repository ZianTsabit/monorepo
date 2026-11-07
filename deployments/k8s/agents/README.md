# Kubernetes: agents

The GitHub-issue agent from [`projects/agents`](../../../projects/agents) on the k3s
cluster, in namespace `agents`. One environment, no overlays.

| Path | Holds |
| --- | --- |
| `kustomization.yaml` | Namespace, RBAC, CronJob, and two generated ConfigMaps. `replacements` copies `IMAGE` from `agents.env` into the CronJob, so the version is set in one place |
| `agents.env` | `REPO`, `OWNER_LOGIN`, `NAMESPACE`, `IMAGE` |
| `rbac.yaml` | `issue-poller` may get/list/create/patch Jobs in `agents`, nothing else. `agent-runner` has no permissions and no token mounted |
| `poller.yaml` | CronJob `issue-poller`, every 3 minutes, `concurrencyPolicy: Forbid` |
| `job-template.yaml` | Not applied: mounted into the poller, which fills it in with `envsubst` for each issue |

## Secrets

Never committed. Kept in Vault at `kv/k3s/agents`:

| Field | What | Used by |
| --- | --- | --- |
| `GITHUB_TOKEN` | Fine-grained PAT, **only** `ZianTsabit/monorepo`: Contents read/write, Issues read/write, Pull requests read/write (Metadata read is implied) | poller, `publish` |
| `ANTHROPIC_API_KEY` *or* `CLAUDE_CODE_OAUTH_TOKEN` | Claude credential: an API key with a spend limit set in the Anthropic Console, or a subscription token from `claude setup-token` | `agent` |

Every command below is run from the monorepo root, with
`VAULT_ADDR=https://192.168.0.114:8200` and `VAULT_CACERT=$HOME/.config/vault/ca.pem`.

## Deploy

```bash
kubectl apply -k deployments/k8s/agents

# Pull secret: the same read:packages PAT as ziantsabit.
PAT=$(vault kv get -mount=kv -field=PAT k3s/ghcr)
jq -cn --arg a "$(printf 'ziantsabit:%s' "$PAT" | base64 -w0)" '{auths:{"ghcr.io":{auth:$a}}}' \
  | kubectl -n agents create secret generic ghcr --type=kubernetes.io/dockerconfigjson \
      --from-file=.dockerconfigjson=/dev/stdin --dry-run=client -o yaml | kubectl apply -f -
unset PAT

vault kv get -mount=kv -field=GITHUB_TOKEN k3s/agents \
  | kubectl -n agents create secret generic agents-github --from-file=token=/dev/stdin \
      --dry-run=client -o yaml | kubectl apply -f -

vault kv get -format=json -mount=kv k3s/agents \
  | jq -r '.data.data | to_entries[] | select(.key == "ANTHROPIC_API_KEY" or .key == "CLAUDE_CODE_OAUTH_TOKEN") | "\(.key)=\(.value)"' \
  | kubectl -n agents create secret generic agents-claude --from-env-file=/dev/stdin \
      --dry-run=client -o yaml | kubectl apply -f -
```

Rerun a secret's command after rotating it in Vault; the next Job picks it up.

## Operate

```bash
kubectl -n agents get cronjob,jobs                            # what ran
kubectl -n agents create job --from=cronjob/issue-poller poll-now   # poll immediately
kubectl -n agents logs job/agent-issue-<n> -c agent -f        # the agent at work
kubectl -n agents logs job/agent-issue-<n> -c publish         # the PR step
kubectl -n agents patch cronjob issue-poller -p '{"spec":{"suspend":true}}'   # pause
```

Finished Jobs are kept for a week (`ttlSecondsAfterFinished`) for their logs.

## Releasing a new image

Build and push `ghcr.io/ziantsabit/agent-runner:<version>` (see the project README),
set `IMAGE` in `agents.env`, then `kubectl apply -k deployments/k8s/agents`.
