# Deployment

The live order book at **https://orderbook.damianhoward.com** is deployed automatically on
every merge to `main` by [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

## Pipeline

1. **Build once.** The workflow runs the full quality gate (`clean check`) and packages
   the `installDist` distribution into a single artifact — the exact bytes that ship.
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
the repo. The deploy user has least-privilege passwordless sudo scoped to managing the unit.

## Topology

A systemd-managed JVM behind Caddy, on a 1 GB micro VM:

- **[`orderbook.service`](orderbook.service)** runs the `installDist` launcher as a non-root
  user with `Restart=on-failure` and a capped heap (`-Xmx256m`). Logs go to `journalctl`.
  Host-specific config the unit shouldn't hard-code — the Kafka egress bootstrap address and
  the SCRAM-SHA-256 credentials it authenticates with — lives root-600 in
  `/etc/orderbook/egress.env` on the box, loaded via an optional `EnvironmentFile`; when the
  file is absent the server runs with the egress off.
- **Caddy** reverse-proxies `localhost:8080` and auto-provisions a Let's Encrypt certificate;
  `flush_interval -1` keeps SSE streams unbuffered. The host's Caddy configuration is
  version-controlled and deployed automatically, but not from this repository: it covers every
  site on the host, so it is maintained as one file in one place rather than as per-service
  fragments.

The server binds loopback, so it is reachable only through that proxy. Nothing here is exposed
directly.

systemd + Caddy rather than Docker: the Docker daemon is too heavy for the 1 GB box's
memory budget. Both the unit here and the proxy configuration are version-controlled and synced
on diff by a deploy, so the host is reproducible rather than hand-edited.
