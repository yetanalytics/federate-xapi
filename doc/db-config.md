## Federate xAPI - Database Configuration

The Federate maintains a cache of Object states in order to facilitate object queries in statement creation. The database can be configured in a few ways.

### SQLite

By default SQLite uses `hla-object-cache.sqlite` in the working directory. It can be changed with:

```shell
HLA_OBJECT_CACHE_DB=/path/to/cache.sqlite make run-dev
HLA_OBJECT_CACHE_JDBC_URL=jdbc:sqlite:/path/to/cache.sqlite make run-dev
```

### PostgreSQL

For local development, start the included PostgreSQL 17 service:

```shell
docker compose up -d --wait postgres
```

The service listens on `localhost:5432` and uses a named volume with database `hla_xapi`, username `hla_xapi`, and
password `hla_xapi_dev`. Stop it with `docker compose down`. To also delete its data and start with an empty database,
run `docker compose down -v`.

Then provide the connection settings at runtime:

```shell
HLA_OBJECT_CACHE_BACKEND=postgresql \
HLA_OBJECT_CACHE_JDBC_URL=jdbc:postgresql://localhost:5432/hla_xapi \
HLA_OBJECT_CACHE_USERNAME=hla_xapi \
HLA_OBJECT_CACHE_PASSWORD=hla_xapi_dev \
HLA_OBJECT_CACHE_SCHEMA=hla_object_cache \
make run-dev
```

`HLA_OBJECT_CACHE_BACKEND=postgresql` selects PostgreSQL, and `HLA_OBJECT_CACHE_JDBC_URL` is then required. Username and password are optional when authentication is already present in the JDBC URL or supplied by the driver, but they must be supplied together through these variables when used. The schema defaults to `hla_object_cache` and must be a simple unquoted SQL identifier.

The PostgreSQL account must be able to create and use the configured schema and create, drop, read, and write the cache tables. Assign a separate schema to each running adapter process; concurrent writers must not share one cache schema.

Both backends start fresh on initialization. The cache drops and recreates only its five owned tables, then seeds the current FOM metadata. PostgreSQL does not drop the configured schema or any unrelated tables in it.