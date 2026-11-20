# Staging on a home laptop: setup guide

This guide builds the staging environment from [phase 1.5](../plan/phase-01b-staging.md) on two old laptops, at
no cost. Follow the parts in order. Each step ends with a check, and you should not move on until it passes.

| Machine | Hostname | Role |
|---|---|---|
| Laptop 2 (i5-4210H, 16 GB) | `rts-staging` | Runs the app and Postgres, serves the public URL |
| Laptop 1 (16 GB) | `rts-backup` | Keeps nightly database dumps |
| Your Mac | | Admin access over Tailscale, deploy key generation |

The end result is `https://rts-staging.<tailnet>.ts.net`, deployed on every merge to `main`.

You need: a USB stick of 1 GB or more, an Ethernet cable for Laptop 2 if you can, a free Tailscale account (sign in
with GitHub), and admin rights on the GitHub repository.

---

## Part 1. Prepare both laptops

Do steps 1.1 to 1.6 on both laptops. Use hostname `rts-staging` on Laptop 2 and `rts-backup` on Laptop 1.

### 1.1 Check the hardware

Boot any Linux live USB, or the existing OS, and check that each disk is an SSD.

```bash
lsblk -o NAME,SIZE,ROTA,MODEL
```

`ROTA=0` means SSD. If Laptop 2 still has a spinning disk (`ROTA=1`), replace it before you continue: Postgres and
the graph load are slow on a hard disk.

### 1.2 BIOS settings

Open the BIOS setup (usually F2, F10 or Del at power-on) and set these where the laptop has them:

- "Restore on AC power loss" or "Power on with AC": On.
- Wake on LAN: optional.
- Boot order: USB first for the install, then the internal disk.

Many laptops have no power-loss option. The battery then acts as a small UPS, which is enough for short cuts.

### 1.3 Install Debian 13

1. Download the Debian 13 "netinst" amd64 image from <https://www.debian.org/distrib/> and write it to the USB
   stick, for example with balenaEtcher or `dd`.
2. Boot from the stick and choose "Install" (the text installer is fine).
3. Hostname: `rts-staging` on Laptop 2, `rts-backup` on Laptop 1. Domain: leave empty.
4. Leave the root password empty. The installer then gives your user `sudo`.
5. Partitioning: "Guided, use entire disk", all files in one partition.
6. Software selection: untick every desktop environment. Tick only "SSH server" and "standard system utilities".
7. Finish and reboot, then remove the stick.

Check: log in on the laptop and run `hostnamectl`. It shows the right hostname and `Debian GNU/Linux 13`.

### 1.4 Keep the laptop awake with the lid closed

```bash
sudo sed -i 's/^#\?HandleLidSwitch=.*/HandleLidSwitch=ignore/' /etc/systemd/logind.conf
sudo sed -i 's/^#\?HandleLidSwitchExternalPower=.*/HandleLidSwitchExternalPower=ignore/' /etc/systemd/logind.conf
sudo sed -i 's/^#\?HandleLidSwitchDocked=.*/HandleLidSwitchDocked=ignore/' /etc/systemd/logind.conf
sudo systemctl restart systemd-logind
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```

Check: close the lid, wait two minutes, then `ping rts-staging.local` or the laptop's IP from your Mac. It still
answers. Keep the closed laptop on a hard surface with its vents clear, because a closed lid traps heat.

### 1.5 Network and updates

1. Plug Laptop 2 into the router with Ethernet if you can. Wi-Fi works, but it drops more often.
2. In the router's admin page, reserve a fixed DHCP address for each laptop. Tailscale makes this optional, but it
   helps when Tailscale itself is down.
3. Install security updates automatically:

```bash
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y unattended-upgrades curl rsync
sudo dpkg-reconfigure -plow unattended-upgrades   # answer Yes
```

### 1.6 Install Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
```

Do not run `tailscale up` yet. Part 2 sets up tags first.

---

## Part 2. Set up the tailnet

Do this part in the Tailscale admin console (<https://login.tailscale.com/admin>) and on the laptops.

### 2.1 Install Tailscale on your Mac

Install the Tailscale app from <https://tailscale.com/download> and sign in. This creates your tailnet. Your
tailnet's DNS name, for example `tail1234.ts.net`, is on the admin console's **DNS** page.

### 2.2 Turn on MagicDNS and HTTPS

On the **DNS** page:

1. Enable **MagicDNS** if it is off.
2. Under **HTTPS Certificates**, click **Enable HTTPS**.

Funnel needs both.

### 2.3 Edit the access policy

Open **Access controls**. The default policy lets every device reach every other device. Replace that with rules
that give each machine only what it needs. Merge these sections into the policy file and save:

```jsonc
{
  "tagOwners": {
    "tag:staging": ["autogroup:admin"],
    "tag:backup":  ["autogroup:admin"],
    "tag:ci":      ["autogroup:admin"],
  },

  "grants": [
    // You and your own devices reach everything (SSH, metrics, psql through SSH).
    {"src": ["autogroup:member"], "dst": ["*"], "ip": ["*"]},
    // GitHub Actions deploy jobs reach only SSH on the staging laptop.
    {"src": ["tag:ci"], "dst": ["tag:staging"], "ip": ["22"]},
    // The staging laptop copies backups to the backup laptop over SSH.
    {"src": ["tag:staging"], "dst": ["tag:backup"], "ip": ["22"]},
  ],

  "nodeAttrs": [
    // Funnel is allowed on the staging laptop. Tagged devices are not members, so the tag is listed explicitly.
    {"target": ["autogroup:member", "tag:staging"], "attr": ["funnel"]},
  ],
}
```

If your policy has an `"acls"` section with an allow-all rule (`"src": ["*"], "dst": ["*:*"]`), remove that rule.
Otherwise the CI node could reach every device in your tailnet.

### 2.4 Join the laptops with tags

On Laptop 2:

```bash
sudo tailscale up --advertise-tags=tag:staging
```

On Laptop 1:

```bash
sudo tailscale up --advertise-tags=tag:backup
```

Each command prints a login URL. Open it on your Mac and approve. Tagged devices are owned by the tailnet, not by
your user, and their keys do not expire, so the laptops do not drop off the tailnet after six months.

Check, from your Mac:

```bash
ssh <your-user>@rts-staging
```

```bash
ssh <your-user>@rts-backup
```

Both connect by MagicDNS name. From now on you can administer both laptops from the Mac, from anywhere.

### 2.5 Lock down SSH

On your Mac, copy your public key to each laptop, then turn off password logins:

```bash
ssh-copy-id <your-user>@rts-staging
```

```bash
ssh-copy-id <your-user>@rts-backup
```

Then, on each laptop:

```bash
echo 'PasswordAuthentication no' | sudo tee /etc/ssh/sshd_config.d/10-no-passwords.conf
sudo systemctl reload ssh
```

Check: `ssh <your-user>@rts-staging` still connects without a password.

---

## Part 3. Set up Laptop 2 (`rts-staging`)

Run every command in this part on Laptop 2 unless the step says otherwise.

### 3.1 Install Docker from Docker's apt repository

Docker documents the apt repository as the recommended method on Debian 13 (trixie). The convenience script is
meant only for testing.

```bash
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: $(. /etc/os-release && echo "$VERSION_CODENAME")
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Check: `sudo docker run --rm hello-world` prints "Hello from Docker!".

### 3.2 Create the `deploy` user and the folder layout

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /srv/rts/osm /srv/rts/backups
sudo chown -R deploy:deploy /srv/rts
```

`deploy` has no password and logs in only with SSH keys. Membership in the `docker` group is equal to root on this
machine, so treat the deploy key as a root key.

Layout:

| Path | Content |
|---|---|
| `/srv/rts/.env` | Secrets, mode 600 |
| `/srv/rts/compose.yml`, `deploy.sh`, `backup.sh` | Copied in by each deploy |
| `/srv/rts/current-tag` | The SHA that is running now |
| `/srv/rts/osm/hanoi.osm` | The road graph, mounted read-only |
| Docker volume `rts-staging_osm-cache` | The binary graph cache, `hanoi.graph`, mounted at `/app/data/osm/cache` |
| `/srv/rts/backups/` | Local dumps, 14 days |

### 3.3 Write the secrets file

```bash
sudo -u deploy bash -c 'umask 077; cat > /srv/rts/.env' <<EOF
DB_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)
STAGING_ACCESS_TOKEN=$(openssl rand -hex 24)
EOF
sudo ls -l /srv/rts/.env
```

Check: the file shows `-rw------- deploy deploy`. Hex values are used because they contain no characters that
need quoting when `deploy.sh` reads the file. `openssl rand -hex 24` gives 48 characters. The app refuses to start
in staging when `STAGING_ACCESS_TOKEN` is missing or shorter than 32 characters.

If you wrote this file earlier with a `DB_MIGRATEONSTART=true` line, delete that line. `deploy.sh` now runs the
migrations itself, with `/app/bin/migrate`.

Save `STAGING_ACCESS_TOKEN` in your password manager. Testers need it to get tokens:

```bash
sudo grep STAGING_ACCESS_TOKEN /srv/rts/.env
```

### 3.4 Download the Hanoi road graph

```bash
sudo -u deploy curl --fail --show-error --user-agent 'rts-osm-download/1.0' --output /srv/rts/osm/hanoi.osm \
  --data-urlencode 'data=[out:xml][timeout:180];way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$"](20.95,105.75,21.10,105.90);(._;>;);out body;' \
  https://overpass-api.de/api/interpreter
ls -lh /srv/rts/osm/hanoi.osm
```

This is the same query as [`docs/data/osm.md`](../data/osm.md). Expect about 30 MB. Keep `--user-agent`: Overpass
answered 406 without it. If it answers 429, 502 or 504, wait a minute and retry.

Check: the file exists, is larger than 20 MB and ends with `</osm>` (`tail -c 20 /srv/rts/osm/hanoi.osm`). Do this before the first deploy. If the file is missing when the
container starts, Docker creates an empty directory at that path and the graph load fails.

The first start after a new `hanoi.osm` parses the XML, which takes tens of seconds on this laptop, and writes a
binary cache to the `osm-cache` volume. Later starts hash the OSM file, find a matching cache and read it in well under
a second. Check it in the app logs:

```bash
sudo docker logs rts-staging-app-1 2>&1 | grep -i "road graph"
```

The first start logs "Wrote the road graph cache". Later starts log "Read the road graph from the cache". A new
download of `hanoi.osm` changes its hash, so the next start rebuilds the cache by itself. To force a rebuild, remove
the volume while the app is stopped: `sudo docker volume rm rts-staging_osm-cache`.

### 3.5 Turn on Funnel

```bash
sudo tailscale funnel --bg 8080
tailscale funnel status
```

`--bg` keeps Funnel running in the background and brings it back after a reboot. The public port is 443 and the
target is `http://127.0.0.1:8080`. Funnel allows only public ports 443, 8443 and 10000.

Check: `tailscale funnel status` shows `https://rts-staging.<tailnet>.ts.net` with `proxy http://127.0.0.1:8080`
and says the address is available on the internet. Public DNS for the name can take up to 10 minutes to appear.

Funnel points at 8080 only. The app's admin port, 8081 (phase 12a), must never be behind Funnel. On 8080 every
`/api/v1/admin` route and `/ws/v1/admin` socket answers 404, so the internet never reaches the admin API.

Until the app runs, the URL answers 502. That is expected.

### 3.6 Create the deploy key

On your **Mac**, create a key used only by GitHub Actions:

```bash
ssh-keygen -t ed25519 -N "" -C "rts-staging-deploy" -f ~/.ssh/rts-staging-deploy
```

Install the public half for the `deploy` user on Laptop 2:

```bash
ssh <your-user>@rts-staging "sudo -u deploy bash -c 'install -d -m 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'" < ~/.ssh/rts-staging-deploy.pub
```

Record the laptop's host key, which GitHub will trust:

```bash
ssh-keyscan -t ed25519 rts-staging > ~/rts-staging-known-hosts
cat ~/rts-staging-known-hosts
```

Check: `ssh -i ~/.ssh/rts-staging-deploy deploy@rts-staging docker ps` lists no containers and no error.

---

## Part 4. The deploy files in the repository (tasks T1b.5 and T1b.7)

These files are in the repository. Nothing to type in. Each deploy copies the first three to `/srv/rts/`.

| File | What it does |
|---|---|
| [`deploy/staging/compose.yml`](../../deploy/staging/compose.yml) | The stack: `db` (Postgres 16, no published port) and `app` (the image `:<sha>`, public port `127.0.0.1:8080`, admin port `127.0.0.1:8081`, 2 GB limit, the road graph mounted read-only). One instance and no Redis: `REDIS_URL` is empty |
| [`deploy/staging/deploy.sh`](../../deploy/staging/deploy.sh) | `bash /srv/rts/deploy.sh <full-sha>`: pull, run `/app/bin/migrate`, start the app, wait up to 180 s for `/health/ready`, and start the previous tag again if the new one never gets ready |
| [`deploy/staging/backup.sh`](../../deploy/staging/backup.sh) | Nightly `pg_dump`, copy to `rts-backup`, keep 14 days on both laptops |
| [`.github/workflows/deploy-staging.yml`](../../.github/workflows/deploy-staging.yml) | After CI succeeds on a push to `main`: join the tailnet, copy the files, run `deploy.sh`, then a smoke test through Funnel from a second runner and a job summary. `workflow_dispatch` with `image_sha` redeploys any earlier image |

Points worth knowing:

- Binding the app to `127.0.0.1` matters. Docker publishes ports by writing its own iptables rules, which bypass
  `ufw`. A plain `"8080:8080"` would expose the app to everyone on your home network.
- The app listens on two ports (phase 12a). 8080 is the public port behind Funnel: drivers, guests, trips, routes, dev
  tokens, health and metrics. 8081 is the admin port (`HTTP_ADMINPORT`). The `/api/v1/admin` routes and the
  `/ws/v1/admin` sockets answer only there, and the routes of 8080 answer there too. Both are on loopback. The admin
  dashboard reaches 8081 through the tailnet only, which slice 12f sets up.
- `deploy.sh` runs the migrations with `docker compose run --rm --no-deps --entrypoint /app/bin/migrate app`. The
  `--entrypoint` flag matters, because the image's `ENTRYPOINT` is `/app/bin/app`. If a migration fails, the script
  stops and the old version keeps serving.
- The workflow does nothing until the repository variable `STAGING_URL` exists (step 5.2). Merges before that only
  show a skipped run.
- `backup.sh` uses `docker exec rts-staging-db-1`, not `docker compose exec`, because compose would need
  `IMAGE_TAG` to read `compose.yml`.
- CI runs `shellcheck` on both scripts.

A rollback with `workflow_dispatch` to a SHA older than these files fails at the copy step, because that commit has
no `deploy/staging/` folder. Roll back only to commits after phase 1.5 merged.

---

## Part 5. Connect GitHub to the tailnet (task T1b.6)

### 5.1 Create the Tailscale OAuth client

In the admin console, open **Settings**, then **OAuth clients** (newer consoles may call these trust credentials).

1. Click **Generate OAuth client**.
2. Scope: **Auth Keys**, with **Write**.
3. Tag: `tag:ci`.
4. Copy the client ID and the secret. The secret is shown only once.

The action uses this client to create an ephemeral node for each job. The node disappears when the job ends.

### 5.2 Create the GitHub environment

In the repository, open **Settings**, then **Environments**, then **New environment**, and name it `staging`.

1. Under **Deployment branches and tags**, choose **Selected branches and tags** and add `main`.
2. Add these **environment secrets**:

| Secret | Value |
|---|---|
| `TS_OAUTH_CLIENT_ID` | From step 5.1 |
| `TS_OAUTH_SECRET` | From step 5.1 |
| `STAGING_SSH_KEY` | The whole private key: `cat ~/.ssh/rts-staging-deploy` |
| `STAGING_KNOWN_HOSTS` | `cat ~/rts-staging-known-hosts` |

3. Add the variable `STAGING_URL` = `https://rts-staging.<tailnet>.ts.net`, with no trailing slash, as a
   **repository variable**: **Settings**, then **Secrets and variables**, then **Actions**, then the **Variables**
   tab. Do not add it to the environment: a job-level `if` cannot read environment variables, and the workflow skips
   itself while `vars.STAGING_URL` is empty. Add it last, once the secrets are in place, because it turns the
   deploys on.

Keep `~/.ssh/rts-staging-deploy` on the Mac for manual deploys (Part 6). The known-hosts file is no longer needed:

```bash
rm ~/rts-staging-known-hosts
```

### 5.3 Ask for approval on fork pull requests

In **Settings**, then **Actions**, then **General**, under **Fork pull request workflows**, choose **Require
approval for all external contributors**. The deploy workflow never runs for pull requests, but this keeps other
workflows from running unreviewed code with your Actions minutes.

---

## Part 6. First deploy, by hand

Do the first deploy manually so that problems show up one at a time.

### 6.1 Copy the files and deploy the current `main`

On your **Mac**, from the repository root on an up-to-date `main` that includes phase 1.5:

```bash
scp -i ~/.ssh/rts-staging-deploy deploy/staging/compose.yml deploy/staging/deploy.sh deploy/staging/backup.sh deploy@rts-staging:/srv/rts/
```

```bash
ssh -i ~/.ssh/rts-staging-deploy deploy@rts-staging "bash /srv/rts/deploy.sh $(git rev-parse origin/main)"
```

`origin/main` has an image in GHCR, because CI pushed it. A local SHA that was never pushed has none.

Expect the pull to take a few minutes the first time. Then `deploy.sh` waits for readiness and prints `Ready: <sha>`.

### 6.2 Check it from the laptop

```bash
ssh <your-user>@rts-staging
```

```bash
cd /srv/rts && cat current-tag
sudo -u deploy IMAGE_TAG=$(cat current-tag) docker compose -p rts-staging -f compose.yml ps
curl -s 127.0.0.1:8080/health/ready
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8080/api/v1/admin/overview   # 404: no admin API on the public port
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8081/api/v1/admin/overview   # 401: the admin port asks for a token
sudo docker stats --no-stream
```

Your own user is not in the `docker` group, so plain `docker` commands need `sudo` (the `deploy` user does not).

Check: both containers are `running` and `db` is `healthy`, `/health/ready` reports UP including `graph`, and the
app uses well under its 2 GB limit.

### 6.3 Check it from a phone on mobile data

Turn Wi-Fi off on the phone. Open `https://rts-staging.<tailnet>.ts.net/health/ready` in its browser. It shows the
readiness JSON.

Then test a WebSocket. Tailscale's Funnel docs do not say whether WebSockets are supported, so this is the most
important check (TC-1b-NET-01). From any computer that is **not** on your tailnet, or from the phone with a WebSocket
client app:

```bash
npx wscat -c wss://rts-staging.<tailnet>.ts.net/ws/v1/echo
```

Type `{"hello":"phone"}`. The same object comes back. Leave it open for 10 minutes: the 15 s server ping keeps
the connection alive.

If the socket fails while plain HTTPS works, use the Cloudflare quick tunnel instead
(`cloudflared tunnel --url http://localhost:8080` on the laptop) and note the result in the phase plan.

### 6.4 Get a token through the staging gate

From any machine, with the token from step 3.3:

```bash
curl -fsS -X POST https://rts-staging.<tailnet>.ts.net/api/v1/dev/tokens -H "X-Staging-Token: <STAGING_ACCESS_TOKEN>" -H "Content-Type: application/json" -d '{"role":"DRIVER"}'
```

Check: the answer holds a `token`. Without the header, or with a wrong token, the answer is 401 with code
`UNAUTHORIZED`. Keep the token out of shell history on shared machines.

### 6.5 Check Funnel's forwarding headers (T1b.4)

The app has a flag, `HTTP_TRUSTFORWARDEDHEADERS`, that makes the rate limit count each client by the address in
`X-Forwarded-For`. It is off. Before you turn it on, see what Funnel actually sends.

1. On Laptop 2, stop the app for a minute and listen on its port with netcat:

   ```bash
   sudo -u deploy IMAGE_TAG=$(cat /srv/rts/current-tag) docker compose -p rts-staging -f /srv/rts/compose.yml stop app
   ```

   ```bash
   nc -l 127.0.0.1 8080
   ```

2. From the phone on mobile data, open `https://rts-staging.<tailnet>.ts.net/health/live`. Netcat prints the raw
   request with every header. Look for `X-Forwarded-For` and `X-Forwarded-Proto`, and note the address in them.
3. Press Ctrl-C, then start the app again:

   ```bash
   sudo -u deploy IMAGE_TAG=$(cat /srv/rts/current-tag) docker compose -p rts-staging -f /srv/rts/compose.yml up -d app
   ```

If `X-Forwarded-For` holds the phone's public address, add `HTTP_TRUSTFORWARDEDHEADERS=true` to `/srv/rts/.env` and
restart the app. Then check the result with the rate limit: the app trusts the headers only from a loopback peer,
and inside the container Funnel's connection may arrive from the Docker bridge gateway (such as `172.18.0.1`)
instead. If it does, the app still counts all testers as one client, and the trusted peers need a small code change
(phase plan, As built). Record what you saw in the phase plan.

If Funnel sends no usable header, keep the flag off and raise `HTTP_RATELIMITPERMINUTE` for staging in `.env`,
with a note in the phase plan.

---

## Part 7. Automatic deploys

### 7.1 Merge the branch

The deploy files are already on `main`. Once step 5.2 has set `STAGING_URL`, the next merge to `main` deploys on its
own: CI runs, and when it succeeds, **Deploy staging** starts.

Check (TC-1b-DEP-01): within about 10 minutes, the run's summary shows the URL and the merged SHA, and
`cat /srv/rts/current-tag` on the laptop shows the same SHA.

### 7.2 Prove the failure paths once (task T1b.8)

Do these on a throwaway branch. Run **Deploy staging** with `workflow_dispatch` and the branch's pushed SHA. That SHA
needs an image in GHCR, so build and push it first from the Mac (`docker login ghcr.io` with a personal access token that has
`write:packages`, then `docker buildx build --platform linux/amd64 -f deploy/Dockerfile -t ghcr.io/veeci/route-tracking-server:<sha> --push .`),
or temporarily let CI push branch images.

| Test | How | Expected |
|---|---|---|
| DEP-02 bad migration | Add a migration with invalid SQL | Deploy job red, `current-tag` unchanged, staging still answers |
| DEP-03 never ready | Make `/health/ready` return DOWN | `deploy.sh` restarts the previous tag, job red |
| DEP-05 manual rollback | `workflow_dispatch` with an older good SHA | Staging runs that SHA |
| DEP-06 reboot | `sudo reboot` on Laptop 2 | App, database and Funnel return without help |

Record the run links in the pull request.

---

## Part 8. Set up Laptop 1 (`rts-backup`) and nightly backups

### 8.1 Backup user on Laptop 1

On Laptop 1:

```bash
sudo adduser --disabled-password --gecos "" backup
sudo mkdir -p /srv/rts-backups
sudo chown backup:backup /srv/rts-backups
```

### 8.2 Key from Laptop 2 to Laptop 1

On Laptop 2, as `deploy`:

```bash
sudo -u deploy ssh-keygen -t ed25519 -N "" -C "rts-backup-push" -f /home/deploy/.ssh/id_ed25519
sudo cat /home/deploy/.ssh/id_ed25519.pub
```

Copy the printed line. On Laptop 1:

```bash
sudo -u backup bash -c 'install -d -m 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
```

Paste the line, press Enter, then Ctrl-D.

Back on Laptop 2, accept the host key once:

```bash
sudo -u deploy ssh backup@rts-backup true
```

Answer `yes`.

### 8.3 Schedule the backup

On Laptop 2:

```bash
sudo -u deploy bash /srv/rts/backup.sh
```

Check: it prints `backup ok`, and `ls /srv/rts-backups` on Laptop 1 shows today's dump. Then schedule it:

```bash
echo '30 3 * * * /bin/bash /srv/rts/backup.sh >> /srv/rts/backup.log 2>&1' | sudo crontab -u deploy -
sudo crontab -u deploy -l
```

### 8.4 Restore drill (TC-1b-OPS-01)

Run this once now and again whenever the schema changes a lot. On Laptop 2:

```bash
sudo docker exec rts-staging-db-1 createdb -U rts rts_restore
sudo docker exec -i rts-staging-db-1 pg_restore -U rts -d rts_restore < /srv/rts/backups/rts-$(date +%F).dump
sudo docker exec rts-staging-db-1 psql -U rts -d rts_restore -c "select count(*) from flyway_schema_history"
sudo docker exec rts-staging-db-1 dropdb -U rts rts_restore
```

Check: the count matches the same query against `rts`.

---

## Day-to-day operations

All commands run on Laptop 2. Reach it with `ssh <your-user>@rts-staging` from anywhere.

| Task | Command |
|---|---|
| See what runs | `cat /srv/rts/current-tag` |
| Follow app logs | `sudo -u deploy IMAGE_TAG=$(cat /srv/rts/current-tag) docker compose -p rts-staging -f /srv/rts/compose.yml logs -f app` |
| Open `psql` | `sudo docker exec -it rts-staging-db-1 psql -U rts -d rts` |
| Read metrics | `curl -s 127.0.0.1:8080/metrics` |
| Call the admin API on the laptop | `curl -s -H "Authorization: Bearer <staff token>" 127.0.0.1:8081/api/v1/admin/overview` (port 8080 answers 404) |
| Roll back | GitHub, then Actions, then **Deploy staging**, then **Run workflow** with an older SHA |
| Restart the app | `sudo -u deploy IMAGE_TAG=$(cat /srv/rts/current-tag) docker compose -p rts-staging -f /srv/rts/compose.yml restart app` |
| Funnel state | `tailscale funnel status` |
| Pause public access | `sudo tailscale funnel reset`, then `sudo tailscale funnel --bg 8080` to restore |
| Disk use | `df -h /` and `sudo docker system df` |
| Refresh the road graph | Repeat step 3.4, then restart the app |

### Rotate a secret

1. Edit `/srv/rts/.env` as `deploy`.
2. For `JWT_SECRET` or `STAGING_ACCESS_TOKEN`: restart the app. Every issued token stops working, so testers request new ones.
3. For `DB_PASSWORD`: first change it in Postgres (`sudo docker exec -it rts-staging-db-1 psql -U rts -d rts -c "alter user rts password '<new>'"`), then edit `.env`, then restart the app. The `POSTGRES_PASSWORD` variable only applies when the volume is first created.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Public URL answers 502 | App not running or not on `127.0.0.1:8080` | Check `docker ps` and the app logs |
| An admin route answers 404 | It was called on 8080 or through Funnel. Admin routes answer only on the admin port | Call `127.0.0.1:8081` on the laptop |
| Public URL does not resolve | Public DNS not out yet, or Funnel off | Wait 10 minutes. Check `tailscale funnel status` |
| `/health/ready` stays DOWN on `graph` | `hanoi.osm` missing, or a directory instead of a file | `ls -l /srv/rts/osm/`, redo step 3.4, restart the app |
| Every start takes tens of seconds and logs "Could not write the road graph cache" | The `osm-cache` volume is not writable by the `app` user | Stop the app, `sudo docker volume rm rts-staging_osm-cache`, deploy again so the volume is created from the image |
| Deploy job fails at "Join the tailnet" | OAuth client lacks `auth_keys` write, or `tag:ci` is not in `tagOwners` | Fix in the admin console |
| Deploy job fails at `scp` with "Host key verification failed" | Laptop reinstalled, so its host key changed | Redo `ssh-keyscan` and update `STAGING_KNOWN_HOSTS` |
| Deploy job fails at `scp` with a timeout | Laptop off, or the grant for `tag:ci` to `tag:staging` port 22 is missing | Check the laptop and the policy |
| `deploy.sh` stops at the migrate step | A migration failed | Fix the migration in a new commit. The old version is still serving |
| Laptop unreachable after closing the lid | Sleep not masked | Redo step 1.4 |
| Everything slow, fans loud | Thermal throttling | Clean the vents, raise the laptop, check `sensors` (package `lm-sensors`) |
