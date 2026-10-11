# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

ZianTsabit's personal monorepo: the applications, the manifests that run them on a home k3s cluster, and notes. There is no root-level build, package manager or workspace tooling — each project builds on its own, from its own directory.

| Directory | Holds |
| --- | --- |
| `projects/<name>/` | One application per directory, self-contained: its own build, Dockerfile, compose files, `.gitignore`, `README.md` and `CLAUDE.md`. **Read the project's `CLAUDE.md` before working in it** — the conventions that matter are there, not here. |
| `deployments/k8s/<name>/` | Kustomize manifests for a project on the cluster, with a `README.md` holding the deploy steps. Kept out of `projects/` so the cluster-side config lives in one place. |
| `notes/` | Reference notes, by topic (`notes/kubernetes/`). Written as explanations, with examples pointing at the real manifests in `deployments/`. |
| `learnings/` | Small learning projects and learning plans. `learnings/k3s/` is the plan for the cluster. |

Current projects:

- `projects/zian-tsabit-com/` — ziantsabit.com: `ziantsabit-fe/` (React + Vite SPA) and `ziantsabit-be/` (Spring Boot API), deployed by `deployments/k8s/ziantsabit/`.

## Adding a project

1. `projects/<name>/` with a `README.md` (how to run it) and a `CLAUDE.md` (what is non-obvious), plus its own `.gitignore` — there is no root one.
2. A line for it in `projects/README.md` and in the list above.
3. If it runs on the cluster, `deployments/k8s/<name>/` in the same shape as the existing one (below).

## Deployments

The cluster is k3s: one server (`k8s-cp1`) and two agents (`k8s-w1`, `k8s-w2`); `notes/kubernetes/architecture.md` has the details. Public traffic arrives through a Cloudflare tunnel (`cloudflared`), not an exposed ingress.

A deployment directory is Kustomize: a shared `base/` and one overlay per environment under `overlays/` (`stg`, `prod`), each in its own namespace, `<name>-<env>`. Non-secret settings are `.env` files turned into a ConfigMap by a generator, so editing one and re-applying rolls the pods.

```bash
kubectl kustomize deployments/k8s/<name>/overlays/stg    # render what an overlay produces
kubectl diff -k deployments/k8s/<name>/overlays/stg      # what applying it would change
kubectl apply -k deployments/k8s/<name>/overlays/stg
```

- **Secrets are never committed.** A project's committed config holds a Vault reference (`NAME=vault:<path>[#field]`) instead of the value, and the Kubernetes Secret is generated from those references; anything else is created by hand, with the command in the deployment's README.
- **Images are pinned to a version tag** (`ghcr.io/ziantsabit/<image>:<version>`), never `latest`; a release is a tag bump in the manifest.
- **Databases and object storage are not in the cluster.** Postgres and RustFS are separate, already-running servers that the manifests point at.

## Cross-references

Project READMEs, `CLAUDE.md` files and `notes/` link to each other and into `deployments/` by relative path. When moving or renaming a file, grep the repo for the old path — nothing checks these links.
