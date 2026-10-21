# Credential migration for existing local installations

**Do not delete, reset, prune, or recreate Docker volumes.** This change removes
credential literals from tracked files, but it does not automatically rotate
accounts already stored in PostgreSQL or SQL LRS.

## Before applying

1. Stop the simulation and adapter through their normal lifecycle controls.
2. Record the current Docker Compose service names, container status, and
   relevant volume names. Take and verify a protected database backup.
3. Choose fresh, unique values through an approved secret manager.
4. Avoid shell tracing, command-line passwords, storing values in source files
   or logs, and posting environment dumps to support tickets.
5. Verify that the running application and all processes consuming a credential
   will be updated together: Observer bridge, adapter, LRS, LRS database, and
   any enabled broker and secondary database.

## Migrate existing database users

PostgreSQL's `POSTGRES_PASSWORD` only configures the role on **first
initialization** of an empty volume. Merely changing the Compose environment
does **not** change a role password already stored in a volume.

Use an authorized administrative session to PostgreSQL to rotate the existing
roles and verify that application access works with the new value. The
interactive `psql` command `\\password <role>` can prompt for the new
password rather than embedding it in SQL, history, or the process command
line. Use the actual PostgreSQL role name from the running service and do
not share the entered value. Make changes one service at a time with backup
and rollback plans.

Separately rotate the SQL LRS API credentials and administrative password
using its supported configuration/account-management procedure. Review the
installed SQL LRS version before choosing a method; these may be persisted
rather than replaced by setting environment variables. Do not claim rotation
until a newly authenticated request succeeds **and** the prior credential
fails.

## Cutover

1. Export matching values for `LRS_KEY` and `LRS_SECRET` to the adapter,
   SQL LRS, and Federate Observer environment. Also provide required
   `LRSQL_DB_PASSWORD`, administrative credentials, and any optional service
   credentials referenced by Compose.
2. Verify `docker compose config --quiet` succeeds without displaying or
   writing a resolved credential-bearing Compose file. Do not print the
   resolved configuration into logs.
3. Bring up only the required services with `docker compose up -d lrs`
   once passwords have been migrated. Preserve all existing volumes.
4. Restart the adapter and Observer using the normal port-ownership and
   process-shutdown checks. The Observer ingestion token is generated in
   memory and must be shared only among its subprocesses.
5. Verify authorized LRS reads, valid adapter xAPI writes, Observer ingest,
   simulation Stop/Reset/Start, retained historical records, and failed
   access using revoked credentials. Validate normal error logs contain no
   authentication values.

## Legacy files and history

Inspect ignored files in the local `upstream/`, `run/`, logs and user
configuration directories. Earlier Federate Observer versions created
`run/observer-token`; once confirmed obsolete, remove **that file alone**.
Never erase `run/` or its SQLite database, WAL/SHM files, or logs
indiscriminately.

Current-file cleanup does not remove material from Git history, pull-request
diffs, caches, forks or prior clones. Audit repository history and rotate
any real or reused credential. Rewriting history changes clone identities
and requires explicit project-wide authorization and coordination.

Use `python3 scripts/check_secrets.py` to check current tracked files. It
does not scan runtime, history or third-party images.
