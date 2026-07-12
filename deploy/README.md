# Deployment

The live order book at **https://orderbook.damianhoward.com** is deployed automatically on
every merge to `main` by [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

## Pipeline

1. **Build once.** The workflow runs the full quality gate (`clean check`) and packages
   the `installDist` distribution into a single artifact — the exact bytes that ship.
2. **Deploy that artifact.** The deploy job downloads the artifact, copies it to the host
   over SSH, syncs the version-controlled [`orderbook.service`](orderbook.service) unit,
   and restarts the service. The previous release is retained at `orderbook-prev/` for
   rollback.
3. **Verify.** A health check against `/healthz` gates success; a non-200 fails the deploy.

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
- **[`Caddyfile`](Caddyfile)** reverse-proxies `localhost:8080` and auto-provisions a
  Let's Encrypt certificate. `flush_interval -1` keeps SSE streams unbuffered.

systemd + Caddy rather than Docker: the Docker daemon is too heavy for the 1 GB box's
memory budget. Both unit and proxy config are version-controlled here so the host is
reproducible rather than hand-edited.
