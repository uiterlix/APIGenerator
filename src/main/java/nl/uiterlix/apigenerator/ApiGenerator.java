package nl.uiterlix.apigenerator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import nl.uiterlix.apigenerator.config.ApiConfig;
import nl.uiterlix.apigenerator.config.DatabaseConfig;
import nl.uiterlix.apigenerator.config.FieldMappingConfig;
import nl.uiterlix.apigenerator.config.ParamConfig;
import nl.uiterlix.apigenerator.config.ResponseConfig;
import spark.Spark;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

public class ApiGenerator {
    private static final String CONFIG_PATH_ENV = "APIGENERATOR_CONFIG_PATH";
    private static final String READINESS_CHECK_INTERVAL_MS_ENV = "APIGENERATOR_READINESS_CHECK_INTERVAL_MS";
    private static final String READINESS_DB_TIMEOUT_MS_ENV = "APIGENERATOR_READINESS_DB_TIMEOUT_MS";
    private static final String DB_POOL_MAX_SIZE_ENV = "APIGENERATOR_DB_POOL_MAX_SIZE";
    private static final String DB_POOL_MIN_IDLE_ENV = "APIGENERATOR_DB_POOL_MIN_IDLE";
    private static final String DB_POOL_CONNECTION_TIMEOUT_MS_ENV = "APIGENERATOR_DB_POOL_CONNECTION_TIMEOUT_MS";
    private static final Path DEFAULT_EXTERNAL_CONFIG_PATH = Path.of("/config/config.yaml");
    private static final long DEFAULT_READINESS_CHECK_INTERVAL_MS = 10_000;
    private static final long DEFAULT_READINESS_DB_TIMEOUT_MS = 2_000;
    private static final int DEFAULT_DB_POOL_MAX_SIZE = 10;
    private static final int DEFAULT_DB_POOL_MIN_IDLE = 2;
    private static final long DEFAULT_DB_POOL_CONNECTION_TIMEOUT_MS = 30_000;

    public static void main(String[] args) throws IOException {
        ApiConfig apiConfig = loadConfig(args);
        int port = resolvePort();
        start(apiConfig, port);
    }

    public static ApiConfig loadConfig(String[] args) throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        Path externalConfigPath = resolveExternalConfigPath(args);
        if (externalConfigPath != null) {
            return yamlMapper.readValue(externalConfigPath.toFile(), ApiConfig.class);
        }

        try (InputStream stream = ApiGenerator.class.getClassLoader().getResourceAsStream("config.yaml")) {
            if (stream == null) {
                throw new IOException("Could not load config.yaml from classpath");
            }
            return yamlMapper.readValue(stream, ApiConfig.class);
        }
    }

    private static Path resolveExternalConfigPath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]);
        }

        String envPath = System.getenv(CONFIG_PATH_ENV);
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }

        if (Files.exists(DEFAULT_EXTERNAL_CONFIG_PATH)) {
            return DEFAULT_EXTERNAL_CONFIG_PATH;
        }

        return null;
    }

    public static void start(ApiConfig apiConfig, int port) {
        Spark.port(port);
        apiConfig.validate();

        DatabaseConnectionInfo databaseConnectionInfo = resolveDatabaseConnectionInfo(apiConfig.getDatabase());
        loadJdbcDriver(databaseConnectionInfo.driverClassName());
        HikariDataSource dataSource = createDataSource(databaseConnectionInfo);
        Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close));
        ReadinessProbe readinessProbe = new ReadinessProbe(
            dataSource,
            resolvePositiveLongEnv(READINESS_CHECK_INTERVAL_MS_ENV, DEFAULT_READINESS_CHECK_INTERVAL_MS),
            resolvePositiveLongEnv(READINESS_DB_TIMEOUT_MS_ENV, DEFAULT_READINESS_DB_TIMEOUT_MS)
        );

        Spark.get("/openapi", (req, res) -> {
            res.type("application/json");
            return OpenApiSpecGenerator.generate(apiConfig);
        }, new ObjectMapper()::writeValueAsString);

        Spark.get("/health/live", (req, res) -> {
            res.type("application/json");
            return Map.of("status", "UP");
        }, new ObjectMapper()::writeValueAsString);

        Spark.get("/health/ready", (req, res) -> {
            res.type("application/json");

            ReadinessResult readiness = readinessProbe.getStatus();
            if (!readiness.up()) {
                res.status(503);
            }
            return readiness.toResponse();
        }, new ObjectMapper()::writeValueAsString);

        apiConfig.getEndpoints().forEach(endpoint -> {
            Spark.get(endpoint.getPath(), (req, res) -> {
                res.status(200);

                try (Connection connection = dataSource.getConnection()) {
                    String query = endpoint.getQuery();
                    PreparedStatement statement = connection.prepareStatement(query);
                    bindParameters(statement, endpoint.getParams(), req);
                    ResultSet resultSet = statement.executeQuery();

                    List<Map<String, Object>> results = new ArrayList<>();
                    while (resultSet.next()) {
                        results.add(mapRow(resultSet, endpoint.getResponse()));
                    }

                    if (results.isEmpty()) {
                        res.status(404);
                        return Map.of("message", "No result found");
                    }

                    if (results.size() == 1) {
                        return results.get(0);
                    }

                    return results;
                } catch (SQLException ex) {
                    res.status(500);
                    return Map.of("message", "Database error", "details", ex.getMessage());
                }
            }, new ObjectMapper()::writeValueAsString);
        });

        Spark.init();
    }

    private static int resolvePort() {
        String rawPort = System.getenv("PORT");
        if (rawPort == null || rawPort.isBlank()) {
            return 8080;
        }
        return Integer.parseInt(rawPort);
    }

    private static long resolvePositiveLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int resolvePositiveIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static HikariDataSource createDataSource(DatabaseConnectionInfo connectionInfo) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(connectionInfo.driverClassName());
        config.setJdbcUrl(connectionInfo.url());
        config.setUsername(connectionInfo.username());
        config.setPassword(connectionInfo.password());
        config.setMaximumPoolSize(resolvePositiveIntEnv(DB_POOL_MAX_SIZE_ENV, DEFAULT_DB_POOL_MAX_SIZE));
        config.setMinimumIdle(resolvePositiveIntEnv(DB_POOL_MIN_IDLE_ENV, DEFAULT_DB_POOL_MIN_IDLE));
        config.setConnectionTimeout(resolvePositiveLongEnv(DB_POOL_CONNECTION_TIMEOUT_MS_ENV, DEFAULT_DB_POOL_CONNECTION_TIMEOUT_MS));
        config.setPoolName("apigenerator-db-pool");
        return new HikariDataSource(config);
    }

    private static DatabaseConnectionInfo resolveDatabaseConnectionInfo(DatabaseConfig databaseConfig) {
        String username = databaseConfig.resolveUsername();
        String password = databaseConfig.resolvePassword();
        return new DatabaseConnectionInfo(
                databaseConfig.getDriverClassName(),
                databaseConfig.getUrl(),
                username,
                password
        );
    }

    private static void loadJdbcDriver(String driverClassName) {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("JDBC driver not found: " + driverClassName, ex);
        }
    }

    private static void bindParameters(PreparedStatement statement, List<ParamConfig> params, spark.Request request) throws SQLException {
        if (params == null) {
            return;
        }

        for (int i = 0; i < params.size(); i++) {
            ParamConfig param = params.get(i);
            String paramLocation = param.getIn();
            String rawValue;

            if ("path".equalsIgnoreCase(paramLocation)) {
                rawValue = request.params(param.getName());
            } else if ("query".equalsIgnoreCase(paramLocation)) {
                rawValue = request.queryParams(param.getName());
            } else {
                throw new IllegalArgumentException("Unsupported param location: " + paramLocation);
            }

            if (rawValue == null) {
                throw new IllegalArgumentException("Missing required parameter: " + param.getName());
            }

            setTypedParameter(statement, i + 1, rawValue, param.getSqlType());
        }
    }

    private static void setTypedParameter(PreparedStatement statement, int index, String rawValue, String sqlType) throws SQLException {
        JDBCType jdbcType = resolveJdbcType(sqlType);

        switch (jdbcType) {
            case INTEGER:
                statement.setInt(index, Integer.parseInt(rawValue));
                return;
            case BIGINT:
                statement.setLong(index, Long.parseLong(rawValue));
                return;
            case DECIMAL:
            case NUMERIC:
                statement.setBigDecimal(index, new BigDecimal(rawValue));
                return;
            case DOUBLE:
            case FLOAT:
            case REAL:
                statement.setDouble(index, Double.parseDouble(rawValue));
                return;
            case BOOLEAN:
            case BIT:
                statement.setBoolean(index, Boolean.parseBoolean(rawValue));
                return;
            case DATE:
                statement.setDate(index, Date.valueOf(rawValue));
                return;
            case TIME:
                statement.setTime(index, Time.valueOf(rawValue));
                return;
            case TIMESTAMP:
                statement.setTimestamp(index, parseTimestamp(rawValue));
                return;
            case BINARY:
            case VARBINARY:
            case LONGVARBINARY:
                statement.setBytes(index, Base64.getDecoder().decode(rawValue));
                return;
            default:
                if ("UUID".equalsIgnoreCase(sqlType)) {
                    statement.setObject(index, UUID.fromString(rawValue));
                    return;
                }
                statement.setString(index, rawValue);
        }
    }

    private static Timestamp parseTimestamp(String rawValue) {
        if (rawValue.contains("T")) {
            return Timestamp.valueOf(rawValue.replace("T", " "));
        }
        return Timestamp.valueOf(rawValue);
    }

    private static JDBCType resolveJdbcType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return JDBCType.VARCHAR;
        }

        try {
            return JDBCType.valueOf(sqlType.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return JDBCType.VARCHAR;
        }
    }

    private static Map<String, Object> mapRow(ResultSet resultSet, ResponseConfig responseConfig) throws SQLException {
        if (responseConfig == null || responseConfig.getMapping() == null || responseConfig.getMapping().isEmpty()) {
            throw new IllegalArgumentException("Response mapping is required for each endpoint");
        }

        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, FieldMappingConfig> entry : responseConfig.getMapping().entrySet()) {
            String outputProperty = entry.getKey();
            FieldMappingConfig fieldMapping = entry.getValue();
            Object value = mapFieldValue(resultSet, fieldMapping);
            mapped.put(outputProperty, value);
        }
        return mapped;
    }

    private static Object mapFieldValue(ResultSet resultSet, FieldMappingConfig fieldMapping) throws SQLException {
        if (fieldMapping.isObject()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, FieldMappingConfig> child : fieldMapping.getFields().entrySet()) {
                nested.put(child.getKey(), mapFieldValue(resultSet, child.getValue()));
            }
            return nested;
        }

        String source = fieldMapping.getSource();
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Leaf field mapping must define 'source'");
        }

        return readTypedValue(resultSet, source, fieldMapping.getType());
    }

    private static Object readTypedValue(ResultSet resultSet, String sourceColumn, String type) throws SQLException {
        if (type == null) {
            return resultSet.getObject(sourceColumn);
        }

        switch (type.toLowerCase()) {
            case "integer":
                return resultSet.getInt(sourceColumn);
            case "long":
                return resultSet.getLong(sourceColumn);
            case "double":
                return resultSet.getDouble(sourceColumn);
            case "boolean":
                return resultSet.getBoolean(sourceColumn);
            case "string":
            default:
                return resultSet.getString(sourceColumn);
        }
    }

    private static final class ReadinessProbe {
        private final DataSource dataSource;
        private final long checkIntervalMs;
        private final int dbTimeoutSeconds;

        private final Object lock = new Object();
        private volatile ReadinessResult lastResult = new ReadinessResult(false, "Readiness has not been checked yet", 0L);

        private ReadinessProbe(DataSource dataSource, long checkIntervalMs, long dbTimeoutMs) {
            this.dataSource = dataSource;
            this.checkIntervalMs = checkIntervalMs;
            this.dbTimeoutSeconds = Math.max(1, (int) Math.ceil(dbTimeoutMs / 1000.0));
        }

        private ReadinessResult getStatus() {
            long now = System.currentTimeMillis();
            ReadinessResult snapshot = lastResult;
            if (snapshot.checkedAtEpochMs() > 0 && now - snapshot.checkedAtEpochMs() < checkIntervalMs) {
                return snapshot;
            }

            synchronized (lock) {
                now = System.currentTimeMillis();
                snapshot = lastResult;
                if (snapshot.checkedAtEpochMs() > 0 && now - snapshot.checkedAtEpochMs() < checkIntervalMs) {
                    return snapshot;
                }

                try (Connection connection = dataSource.getConnection()) {
                    if (connection.isValid(dbTimeoutSeconds)) {
                        lastResult = new ReadinessResult(true, null, now);
                    } else {
                        lastResult = new ReadinessResult(false, "Database connection validation failed", now);
                    }
                } catch (SQLException ex) {
                    lastResult = new ReadinessResult(false, ex.getMessage(), now);
                }
                return lastResult;
            }
        }
    }

    private record ReadinessResult(boolean up, String details, long checkedAtEpochMs) {
        private Map<String, Object> toResponse() {
            if (up) {
                return Map.of(
                        "status", "UP",
                        "checkedAtEpochMs", checkedAtEpochMs
                );
            }
            return Map.of(
                    "status", "DOWN",
                    "details", details,
                    "checkedAtEpochMs", checkedAtEpochMs
            );
        }
    }

    private record DatabaseConnectionInfo(String driverClassName, String url, String username, String password) {}
}