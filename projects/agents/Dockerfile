# One image for both halves of the issue agent: the poller (gh + kubectl) and
# the per-issue Job (Claude Code + the toolchains the repo's projects build
# with: JDK 25 for ziantsabit-be's Gradle tests, Node 24 for ziantsabit-fe).
FROM node:24-bookworm-slim AS node

FROM eclipse-temurin:25-jdk-noble

ARG CLAUDE_CODE_VERSION=2.1.319
ARG GH_VERSION=2.102.1
ARG KUBECTL_VERSION=1.36.5

RUN apt-get update \
 && apt-get install -y --no-install-recommends git jq curl ca-certificates gettext-base \
 && rm -rf /var/lib/apt/lists/*

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force

RUN curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_amd64.tar.gz" \
    | tar -xz -C /tmp \
 && mv "/tmp/gh_${GH_VERSION}_linux_amd64/bin/gh" /usr/local/bin/gh \
 && rm -rf /tmp/gh_* \
 && curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/v${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
 && chmod +x /usr/local/bin/kubectl

COPY bin/ /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh && mkdir -p /home/agent /work && chown 1000:1000 /home/agent /work

# The base image's `ubuntu` user is UID 1000.
ENV HOME=/home/agent \
    DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
USER 1000:1000
WORKDIR /work
