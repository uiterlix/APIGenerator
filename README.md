# APIGenerator

APIGenerator is a lightweight, configuration-driven API server built with Java and Spark.

It lets you define endpoints in YAML, execute SQL queries against a database, shape the JSON response with nested mappings, and expose an OpenAPI 3 spec automatically.

## Goal

The goal of this project is to generate useful read endpoints from configuration instead of hand-writing controllers and DTOs for every route.

From one config file, the app can:
- register HTTP GET endpoints,
- resolve JDBC connection settings from config,
- bind request parameters into prepared SQL statements,
- map database rows into nested JSON payloads,
- include field-level documentation in schema definitions,
- generate an OpenAPI spec at runtime.

## How It Works

1. The app loads configuration with this precedence:
  CLI path argument, then `APIGENERATOR_CONFIG_PATH`, then `/config/config.yaml`, then bundled [src/main/resources/config.yaml](src/main/resources/config.yaml).
2. JDBC driver, URL, and credentials are resolved from `database` config.
3. Each endpoint definition registers a Spark route.
4. Params are read from path/query and bound with typed JDBC setters.
5. SQL results are mapped into response objects using `response.mapping`.
6. OpenAPI JSON is served at `/openapi` with reusable schemas under `components.schemas`.

## Health Endpoints

The service exposes two probe-friendly endpoints:
- `GET /health/live`: returns `200` when the process is running.
- `GET /health/ready`: returns `200` when the latest database readiness check is healthy, otherwise `503`.

Readiness checks are cached to reduce probe load on the database:
- The service performs a real DB validation check at most once per interval (default `10000ms`).
- Requests inside that interval return the cached readiness result.
- Response payload includes `checkedAtEpochMs` for visibility into cache freshness.

Tune readiness behavior with environment variables:
- `APIGENERATOR_READINESS_CHECK_INTERVAL_MS` (default `10000`)
- `APIGENERATOR_READINESS_DB_TIMEOUT_MS` (default `2000`)

These are intended for Kubernetes liveness and readiness probes.

## Configuration Overview

Top-level structure:

```yaml
api:
  name: "APIGenerator Service"
  version: "1.0.0"

database:
  driverClassName: "org.h2.Driver"
  url: "jdbc:h2:./testdb"
  username: "sa"
  password: ""
  usernameEnv: "DB_USERNAME"
  passwordEnv: "DB_PASSWORD"

endpoints:
  - path: "/users/:id"
    method: "GET"
    description: "Fetch a user by ID"
    query: "SELECT id, name FROM users WHERE id = ?"
    params:
      - name: "id"
        in: "path"
        sqlType: "INTEGER"
        description: "Unique user identifier"
    response:
      responseName: "UserResponse"
      mapping:
        user:
          type: "object"
          description: "User payload"
          fields:
            id:
              source: "id"
              type: "Integer"
              description: "Unique user identifier"
            profile:
              type: "object"
              description: "Profile information"
              fields:
                name:
                  source: "name"
                  type: "String"
                  description: "Display name of the user"
```

## Endpoint Config Fields

- `database.driverClassName`: JDBC driver class to load.
- `database.url`: JDBC connection URL.
- `database.username`: fallback username for local runs.
- `database.password`: fallback password for local runs.
- `database.usernameEnv`: environment variable name to read username from.
- `database.passwordEnv`: environment variable name to read password from.
- `path`: Spark route path. Use `:paramName` for path params.
- `method`: currently `GET`.
- `description`: optional endpoint documentation shown in OpenAPI for this method.
- `query`: SQL query executed for the endpoint.
- `params`: ordered list matching `?` placeholders in `query`.
- `response.responseName`: schema name used in OpenAPI `components.schemas`.
- `response.mapping`: nested object mapping for output JSON and schema generation.

## Parameter Configuration

Each parameter requires:
- `name`: request parameter name.
- `in`: `path` or `query`.
- `sqlType`: JDBC-style type name.

Optional:
- `description`: parameter documentation shown in OpenAPI.

Example:

```yaml
params:
  - name: "id"
    in: "query"
    sqlType: "INTEGER"
    description: "Unique user identifier"
```

### Supported `sqlType` Values

The implementation resolves JDBC types via `java.sql.JDBCType` and supports common values such as:
- `INTEGER`
- `BIGINT`
- `DECIMAL` / `NUMERIC`
- `DOUBLE` / `FLOAT` / `REAL`
- `BOOLEAN` / `BIT`
- `VARCHAR`
- `DATE`
- `TIME`
- `TIMESTAMP`
- `BINARY` / `VARBINARY` / `LONGVARBINARY`
- `UUID` (special-cased)

If an unknown type is used, binding falls back to string.

## Response Mapping

- Object nodes: `type: object` with nested `fields`.
- Leaf nodes: `source`, `type`, and optional `description`.

This mapping drives both:
- runtime JSON response shape,
- OpenAPI schema generation including field descriptions.

## Database Configuration

Database connectivity is now fully config-driven. The application loads the JDBC driver class declared in `database.driverClassName` and opens connections with the configured URL and credentials.

Credential resolution order:
- If `database.usernameEnv` is set and the environment variable exists, that value is used.
- Otherwise `database.username` is used.
- If `database.passwordEnv` is set and the environment variable exists, that value is used.
- Otherwise `database.password` is used.

This makes local development simple while still supporting Kubernetes secret injection.

### Kubernetes Secret Usage

Recommended pattern:
- Store database credentials in a Kubernetes `Secret`.
- Expose them to the container as `DB_USERNAME` and `DB_PASSWORD` environment variables.
- Keep the secret names out of source control and only reference the env var names in config.

Example secret-driven config:

```yaml
database:
  driverClassName: "com.ibm.db2.jcc.DB2Driver"
  url: "jdbc:db2://db2-host:50000/MYDB"
  usernameEnv: "DB_USERNAME"
  passwordEnv: "DB_PASSWORD"
```

## Kubernetes Deployment

Example manifests are included under [k8s/configmap.yaml](k8s/configmap.yaml), [k8s/secret.example.yaml](k8s/secret.example.yaml), [k8s/deployment.yaml](k8s/deployment.yaml), and [k8s/service.yaml](k8s/service.yaml).

The deployment uses:
- a `ConfigMap` to mount `config.yaml` at `/config/config.yaml`,
- a `Secret` to provide `DB_USERNAME` and `DB_PASSWORD`,
- liveness probe on `/health/live`,
- readiness probe on `/health/ready`.

Because the application auto-detects `/config/config.yaml`, the container does not need an explicit startup argument in Kubernetes.

Apply the manifests:

```bash
kubectl apply -f k8s/secret.example.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

Before applying in a real cluster, replace the example secret values and set the container image in [k8s/deployment.yaml](k8s/deployment.yaml) to a pushed image reference.

## DB2 Support

The project includes the IBM DB2 JDBC driver at runtime, so the same generator can target DB2 as long as the correct JDBC URL and driver class are configured.

Common DB2 settings:
- `driverClassName`: `com.ibm.db2.jcc.DB2Driver`
- `url`: `jdbc:db2://<host>:<port>/<database>`

## Docker

A production-oriented multi-stage Docker build is included in [Dockerfile](Dockerfile).

Build the image:

```bash
docker build -t apigenerator:latest .
```

Run locally with env-based credentials:

```bash
docker run --rm -p 8080:8080 \
  -e DB_USERNAME=sa \
  -e DB_PASSWORD='' \
  apigenerator:latest
```

The container listens on port `8080`. You can also provide an alternate config file path as the first container argument or through `APIGENERATOR_CONFIG_PATH`.

## OpenAPI Output

The server exposes OpenAPI at:

- `GET /openapi`

The generated spec includes:
- `info.title` and `info.version` from `api.name` and `api.version`,
- endpoint method descriptions from `endpoints[].description`,
- endpoint parameter definitions with inferred OpenAPI types/formats,
- endpoint parameter descriptions from `params[].description`,
- `$ref` responses to `#/components/schemas/<responseName>`.

## Run the Project

From the repository root:

```bash
./gradlew run
```

## Run Tests

```bash
./gradlew test --tests ApiGeneratorIntegrationTest
```

The integration test:
- starts an H2 TCP server,
- creates/seeds test tables,
- starts the API server,
- validates endpoint responses and generated OpenAPI content.

## Current Scope and Limitations

- Only `GET` endpoints are supported.
- Query placeholders use positional parameter binding.
- Response mapping is required for each endpoint.
- The project includes runtime support for H2 and DB2; other JDBC drivers can be added in the build as needed.

## Project Structure

- [src/main/java/nl/uiterlix/apigenerator/ApiGenerator.java](src/main/java/nl/uiterlix/apigenerator/ApiGenerator.java): server startup, route registration, SQL execution.
- [src/main/java/nl/uiterlix/apigenerator/OpenApiSpecGenerator.java](src/main/java/nl/uiterlix/apigenerator/OpenApiSpecGenerator.java): OpenAPI generation.
- [src/main/java/nl/uiterlix/apigenerator/config/ApiConfig.java](src/main/java/nl/uiterlix/apigenerator/config/ApiConfig.java): root config model.
- [src/main/java/nl/uiterlix/apigenerator/config/EndpointConfig.java](src/main/java/nl/uiterlix/apigenerator/config/EndpointConfig.java): endpoint config model.
- [src/main/java/nl/uiterlix/apigenerator/config/ParamConfig.java](src/main/java/nl/uiterlix/apigenerator/config/ParamConfig.java): request parameter config model.
- [src/main/java/nl/uiterlix/apigenerator/config/ResponseConfig.java](src/main/java/nl/uiterlix/apigenerator/config/ResponseConfig.java): response config model.
- [src/main/java/nl/uiterlix/apigenerator/config/FieldMappingConfig.java](src/main/java/nl/uiterlix/apigenerator/config/FieldMappingConfig.java): nested field mapping model.
- [src/main/resources/config.yaml](src/main/resources/config.yaml): API configuration.
- [src/test/java/ApiGeneratorIntegrationTest.java](src/test/java/ApiGeneratorIntegrationTest.java): end-to-end integration test.
