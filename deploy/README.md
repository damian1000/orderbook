# Deployment

The live order book at **https://orderbook.damianhoward.com** is deployed automatically on
every merge to `main` by [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

## Pipeline

1. **Build once.** The workflow runs the full quality gate (`clean build`) and packages
   the `installDist` distribution into a single artifact — the exact bytes that ship.
   `build` rather than `check`, because `check` does not depend on `jar` — so packaging
   sits outside it, and a gate that cannot see whether packaging works is not gating a
   pipeline whose output is a package.
2. **Deploy that artifact.** The deploy job downloads the artifact and copies it to the host
   over SSH, pinning the host key from [`known_hosts.pub`](known_hosts.pub) rather than
   trusting whatever answers on the address. The release unpacks into
   `~/releases/orderbook/<commit>` and `~/orderbook` is moved onto it with a symlink rename,
   so a restart can never see a half-copied install. The version-controlled
   [`orderbook.service`](orderbook.service) unit syncs only when it differs.
3. **Verify, or roll back.** A `/readyz` check gates success. If the new release does not come
   up, the same remote script flips the symlink back to the previous release and restarts —
   the decision is made on the box, so a runner that dies mid-deploy cannot leave a broken
   release serving. Three releases are retained.

Secrets (`DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`) live in GitHub Actions, never in
the repo. The deploy account needs passwordless sudo to install the unit file and manage the
service.

## Topology

A systemd-managed JVM behind Caddy, on a 1 GB micro VM:

- **[`orderbook.service`](orderbook.service)** runs the `installDist` launcher as a non-root
  user with `Restart=on-failure` and a capped heap (`-Xmx256m`). Logs go to `journalctl`.
  Host-specific config the unit shouldn't hard-code — the Kafka egress bootstrap address and
  the SCRAM-SHA-256 credentials it authenticates with — is read from a root-only
  `EnvironmentFile` on the box, declared optional so that when it is absent the server starts
  anyway with the egress off rather than failing to boot.
- **Caddy** reverse-proxies `localhost:8080` and auto-provisions a Let's Encrypt certificate;
  `flush_interval -1` keeps SSE streams unbuffered. The host's Caddy configuration is
  version-controlled, but not here and not by a deploy: it covers every site on the host, so it
  is owned as one whole file by the private infrastructure repository and installed by an
  operator-run script that validates it first. A bad Caddyfile takes down every site on the box
  at once, which is not a thing a service deploy should be able to do as a side effect.

The server binds loopback, so it is reachable only through that proxy. Nothing here is exposed
directly.

systemd + Caddy rather than Docker: the Docker daemon is too heavy for the 1 GB box's
memory budget. The unit here is synced on diff by a deploy and the proxy configuration is applied
from the infrastructure repository, so the host is reproducible rather than hand-edited either
way.
