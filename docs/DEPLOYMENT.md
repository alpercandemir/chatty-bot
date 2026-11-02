# Oracle VM deployment

Live URL: **https://chatty.138.2.168.194.sslip.io**

Chatty shares the existing Docker engine on `138.2.168.194` with rparadise and the other hosted app. Its Compose project is `chatty-bot`; its backend, PostgreSQL database, named data volume, and private network are separate. Only the frontend joins `rparadise-tracker_default`, where the existing Caddy proxy reaches it as `chatty:80`. No additional host ports are published.

## Costs and resource boundaries

This deployment creates no Oracle cloud resources and does not change the account subscription, VM shape, disk allocation, VCN, public IP, or load balancer. The inspected VM is `VM.Standard.A1.Flex` with **1 OCPU and 6 GB RAM** in Frankfurt. This fits the current [Oracle Always Free compute allowance](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), assuming the existing instance is in the tenancy's home region and total tenancy usage stays within its allowances. SSH access does not independently verify the tenancy's billing status or other resource usage.

The containers have memory ceilings of 768 MiB (backend), 256 MiB (database), and 64 MiB (frontend), plus CPU limits. Each rotates Docker logs at three 10 MB files. Images are built on GitHub's standard public-repository ARM runners, not on the VM. sslip.io supplies the hostname without domain registration; the existing Caddy obtains and renews HTTPS certificates.

Gemini usage is separate from Oracle hosting. The existing Gemini key and selected model are reused. Nothing enables provider billing, changes the Google project's billing tier, or selects a paid fallback. The application cannot verify that project's tier; its existing quota and billing settings still apply. Disk usage, retained releases/backups, and outbound traffic should remain within the existing VM and tenancy allowances.

## Private configuration

The server file `/home/ubuntu/chatty-bot/.env` is owned by `ubuntu`, mode **600**, in a mode **700** directory. Use [the placeholder configuration](../deploy/production.env.example) as its schema. The Gemini key is supplied only to the backend container. The database password is independently generated and supplied to the backend and Chatty's database.

The hosted Compose configuration forces `AI_MODE=live`, `AI_LIVE_ONLY=true`, and `SECURE_COOKIES=true`. Rehearsal is absent from the hosted mode selector and requests to create scripted conversations are rejected. Local development and CI retain deterministic fixtures for testing. Provider failures remain explicit and do not fall back to rehearsal.

`.env`, local deployment keys, and build outputs are Git-ignored and excluded from image build contexts. Real credentials must never be added to workflows, Dockerfiles, frontend variables, or this documentation. Do not print `docker compose config` or full container inspection output: resolved environments contain secrets. Use `config --quiet` for validation.

## CI/CD

`main` requires a pull request approved by the code owner, `@alpercandemir`, plus the passing GitHub Actions `ci` check on an up-to-date branch. New commits dismiss old approvals; review conversations must be resolved. Force pushes and branch deletion are blocked. The repository owner retains GitHub's administrative override for owner-authored changes and recovery; ordinary contributors cannot bypass the review rule. Public fork PRs run tests but do not publish images or receive deployment secrets.

The workflow follows the same pattern as rparadise:

1. A push or pull request targeting `main` runs Java 25 verification, frontend compilation, and ARM container builds.
2. CI starts a disposable PostgreSQL-backed stack and runs all 50 scripted HTTP cases without a Gemini credential. The hosted-mode regression test separately checks secure cookies and rejection of scripted API requests.
3. Successful pushes to `main` publish backend and frontend images to GHCR, tagged with the full commit SHA.
4. The Deploy workflow runs after successful push CI, validates that SHA belongs to `main`, copies versioned runtime configuration, and deploys over SSH using the `production` environment.
5. The server pulls images using an ephemeral job token, backs up an existing Chatty database, waits for all services to become healthy, checks HTTPS, and advances the `current` symlink. A failed rollout restores the previous runtime when available.

The repository's `ORACLE_SSH_KEY` Actions secret contains a dedicated deployment key. Its public key is installed with SSH's `restrict` option to disable forwarding and PTY access. The pinned VM host key is committed in the workflow. Neither the Gemini key nor database password is stored in GitHub Actions. GHCR credentials are held in a temporary server directory and removed when deployment ends.

Deployment paths:

```text
/home/ubuntu/chatty-bot/.env
/home/ubuntu/chatty-bot/releases/<full-commit-sha>/
/home/ubuntu/chatty-bot/current -> releases/<full-commit-sha>
/home/ubuntu/chatty-bot/backups/before-<sha>-<timestamp>.sql.gz
```

## Proxy setup and operation

[The Caddy fragment](../deploy/Caddyfile.chatty) is appended once to `/home/ubuntu/rparadise-tracker/Caddyfile`. Back up that file, preserve the other site blocks, validate the configuration inside the existing Caddy container, and reload Caddy. No additional proxy or Oracle networking rule is required. If a host-side edit replaces the file inode, the existing file bind mount can remain stale: recreate only the Caddy service with `docker compose -f compose.yaml -f compose.observability.yaml up -d --no-deps --force-recreate caddy`, then check the file inside the container and verify every site. Future edits to the shared Caddyfile must preserve every hosted site's block.

For status and logs, after SSHing to the VM:

```sh
cd /home/ubuntu/chatty-bot/current
sha=$(basename "$(pwd -P)")
export CHATTY_BACKEND_IMAGE="ghcr.io/alpercandemir/chatty-bot-backend:sha-$sha"
export CHATTY_FRONTEND_IMAGE="ghcr.io/alpercandemir/chatty-bot-frontend:sha-$sha"
sudo --preserve-env=CHATTY_BACKEND_IMAGE,CHATTY_FRONTEND_IMAGE docker compose -p chatty-bot \
  --env-file /home/ubuntu/chatty-bot/.env -f compose.production.yaml ps
sudo --preserve-env=CHATTY_BACKEND_IMAGE,CHATTY_FRONTEND_IMAGE docker compose -p chatty-bot \
  --env-file /home/ubuntu/chatty-bot/.env -f compose.production.yaml logs --tail 100 backend
```

To redeploy or roll back, run the GitHub **Deploy** workflow with an older full `main` commit SHA whose images are still retained. The database volume is preserved. Database migrations must remain compatible with the older application; automatic runtime rollback does not reverse migrations. For incompatible schema changes, use the pre-deploy backup with an explicitly planned database restore.

Verify with the public `/api/v1/health` endpoint, the browser UI, and a small real-provider smoke test:

```sh
python3 scripts/live-smoke.py --url https://chatty.138.2.168.194.sslip.io
```

This smoke test uses the already-configured backend key; it reads no local secret and makes a small number of real provider calls.
