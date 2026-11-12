# Migrating a child distro to the Ozone CLI (single `ozone.yml`) pattern

Audience: implementers of a child distro (e.g. ozone-c2c) currently using the
old scripts/-based setup: `scripts/start-ozone.sh`, `scripts/docker-compose-files.txt`,
a distro-specific `scripts/distro-<name>.env`, and `scripts/docker-compose-<x>-overrides.yml`
files. This guide maps each old mechanism to its new equivalent.

The new pattern, in one line: the distro declares a single `ozone.yml` at its
root with `selection:`, `vars:` and `model-overrides:` sections; the build
(`ozone resolve`, wired via `maven-parent`) flattens the inheritance chain into
`resolved/` plus `resolved/info.yml`; deployment runs the `ozone`
command, which runs the exact `docker compose ...` command (or prints it with
`--print`). See `README.md` in this repo for the full model.

## Old vs new

| Old mechanism | Old file | New equivalent |
|---|---|---|
| Start script | `scripts/start-ozone.sh` (run from `run/docker/scripts`) | `ozone --compose-root <run/docker> --distro <distro dir> --hostname <h> up` - runs the stack (add `--print` to only print the command) |
| Compose file list | `scripts/docker-compose-files.txt` | `ozone.yml` `selection:` section (apps + addons); the compose file chain and profiles are computed from the compose model |
| Distro env vars | `scripts/distro-<name>.env`, merged into `concatenated.env` at build time | `ozone.yml` `vars:` section, merged over the parent's `resolved/vars.yml` at build time |
| Compose override file | `scripts/docker-compose-<x>-overrides.yml`, appended to the `-f` chain | keep the file inside the distro and declare it in `ozone.yml` `model-overrides:` section (path relative to the distro dir); partial service tweaks (volumes, command, labels, extra env) stay in that compose file |
| Database init list | env list consumed by `data/mysql/create_db.sh` / `data/postgresql/create_db.sh` (`DB_INIT_LIST`) | the compose model's `db-init` per app; the CLI computes `DB_INIT_LIST` from the selection - no distro-side list needed |
| Distro identity | `ozone-info.json` patched by the build | `resolved/info.yml`, written from the pom's artifactId / version / description at build time |

## Steps

1. **Pom**: use a `maven-parent` version that has the `ozone-yml` profile; declare
   your own `<name>`, `<version>` and `<description>` (the description is
   recorded in `info.yml` - if omitted, Maven inherits the parent's).
2. **Create `ozone.yml` at the distro root** with:
   - `selection:` - the apps you select and the addons you enable (converted
     from `docker-compose-files.txt`; note the selection *replaces* the
     parent's wholesale - disabling a service means omitting it here). SSO is
     the `keycloak` app: list it under `apps:`.
   - `vars:` - your env values (converted from `distro-<name>.env`), in
     blocks mirroring the model (apps / integrations / addons / databases).
     `paths:` values are relative to the distro dir. Do not patch the
     `env-substitution` service to inject values - put them under `vars:`.
   - `model-overrides:` - compose additions only (additive; never removes).
     Reference your override compose files here with distro-relative paths.
3. **Move override compose files** (e.g. `docker-compose-bahmniemr-overrides.yml`)
   into the distro (e.g. under `compose/`) and reference them from
   the `model-overrides:` section. An overlay may not declare a file that belongs to the
   base compose model - that is a loud build error.
4. **Remove the old files**: `scripts/docker-compose-files.txt`,
   `scripts/*.env`, the start scripts. Keep `scripts/erpnext/` and
   `scripts/openmrs/` if you use them. `configs/`, `data/`, `binaries/` stay
   as they are.
5. **Build**: `mvn clean install`. The published zip then contains
   `distro/ozone.yml`, `distro/resolved/` (merged chain)
   and `distro/resolved/info.yml` carrying *your* name/version/description.
   Nothing is generated in the source tree; `resolved/`, `.env`, `manifest.yml`
   are gitignored.
6. **Deploy**: from the unpacked artifact, put the CLI on your PATH and run
   the bundled launcher:
   `. ./bin/ozone-path.sh` (in fish: `source ./bin/ozone-path.fish`; or simply
   `export PATH="$PWD/bin:$PATH"`), then
   `ozone --compose-root run/docker --distro distro --hostname <host> up`
   (add `--print` to only print the multi-line command and paste it yourself).
   (`java -jar bin/cli.jar ...` works too.) `HOST_NAME` and `DISTRO_PATH` are
   provided by that command, never stored in files. Use `info` to re-print the
   services and credentials later.

## Merge rules to know before editing vars or overrides

Maps merge recursively, lists are appended, scalars: child wins, type
conflicts fail the build loudly. At deployment the CLI reads only
`resolved/` and `ozone.yml` `selection:` section - the source files are build inputs.

## Verifying

- Build succeeds and `target/<artifactId>-<version>/distro/resolved/info.yml`
  shows your artifactId, version and description.
- `ozone ... validate` prints the selection check; `up --print` prints the full
  command with the expected `-f` chain and `--profile` list (compare against
  your old `docker-compose-files.txt`).
- `DB_INIT_LIST` behavior unchanged: databases initialize per the model's
  `db-init` of the selected apps.
