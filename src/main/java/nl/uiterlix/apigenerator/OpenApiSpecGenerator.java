package nl.uiterlix.apigenerator;

import nl.uiterlix.apigenerator.config.ApiConfig;
import nl.uiterlix.apigenerator.config.EndpointConfig;
import nl.uiterlix.apigenerator.config.FieldMappingConfig;

import java.sql.JDBCType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class OpenApiSpecGenerator {
    private OpenApiSpecGenerator() {
    }

    public static Map<String, Object> generate(ApiConfig apiConfig) {
        Map<String, Object> componentSchemas = buildOpenApiComponentSchemas(apiConfig);
        Map<String, Object> openApiInfo = buildOpenApiInfo(apiConfig);

        return Map.of(
                "openapi", "3.0.0",
                "info", openApiInfo,
                "components", Map.of(
                        "schemas", componentSchemas
                ),
                "paths", apiConfig.getEndpoints().stream().collect(Collectors.toMap(
                        endpoint -> openApiPath(endpoint.getPath()),
                        endpoint -> Map.of(
                                endpoint.getMethod().toLowerCase(), Map.of(
                                        "parameters", buildOpenApiParameters(endpoint),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "description", "Response",
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "$ref", "#/components/schemas/" + schemaNameFor(endpoint)
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ))
        );
    }

    private static String openApiPath(String sparkPath) {
        if (sparkPath == null) {
            return "/";
        }
        return sparkPath.replaceAll(":([A-Za-z0-9_]+)", "{$1}");
    }

    private static List<Map<String, Object>> buildOpenApiParameters(EndpointConfig endpoint) {
        if (endpoint.getParams() == null) {
            return List.of();
        }

        return endpoint.getParams().stream()
                .map(param -> {
                    String paramIn = "path".equalsIgnoreCase(param.getIn()) ? "path" : "query";
                    Map<String, Object> schema = openApiParamSchema(param.getSqlType());
                    return Map.of(
                            "name", param.getName(),
                            "in", paramIn,
                            "required", "path".equals(paramIn),
                            "schema", schema
                    );
                })
                .collect(Collectors.toList());
    }

    private static Map<String, Object> openApiParamSchema(String sqlType) {
        JDBCType jdbcType = resolveJdbcType(sqlType);
        Map<String, Object> schema = new LinkedHashMap<>();

        switch (jdbcType) {
            case INTEGER:
                schema.put("type", "integer");
                schema.put("format", "int32");
                return schema;
            case BIGINT:
                schema.put("type", "integer");
                schema.put("format", "int64");
                return schema;
            case DECIMAL:
            case NUMERIC:
                schema.put("type", "number");
                return schema;
            case DOUBLE:
            case FLOAT:
            case REAL:
                schema.put("type", "number");
                schema.put("format", "double");
                return schema;
            case BOOLEAN:
            case BIT:
                schema.put("type", "boolean");
                return schema;
            case DATE:
                schema.put("type", "string");
                schema.put("format", "date");
                return schema;
            case TIME:
                schema.put("type", "string");
                schema.put("format", "time");
                return schema;
            case TIMESTAMP:
                schema.put("type", "string");
                schema.put("format", "date-time");
                return schema;
            case BINARY:
            case VARBINARY:
            case LONGVARBINARY:
                schema.put("type", "string");
                schema.put("format", "byte");
                return schema;
            default:
                if ("UUID".equalsIgnoreCase(sqlType)) {
                    schema.put("type", "string");
                    schema.put("format", "uuid");
                    return schema;
                }
                schema.put("type", "string");
                return schema;
        }
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

    private static Map<String, Object> buildOpenApiSchema(EndpointConfig endpoint) {
        Map<String, Object> properties = new LinkedHashMap<>();

        if (endpoint.getResponse() != null && endpoint.getResponse().getMapping() != null) {
            endpoint.getResponse().getMapping().forEach((propertyName, mapping) -> {
                properties.put(propertyName, buildOpenApiFieldSchema(mapping));
            });
        }

        return Map.of(
                "type", "object",
                "properties", properties
        );
    }

    private static Map<String, Object> buildOpenApiFieldSchema(FieldMappingConfig fieldMapping) {
        Map<String, Object> schema = new LinkedHashMap<>();

        if (fieldMapping.getDescription() != null && !fieldMapping.getDescription().isBlank()) {
            schema.put("description", fieldMapping.getDescription());
        }

        if (fieldMapping.isObject()) {
            Map<String, Object> nestedProperties = new LinkedHashMap<>();
            for (Map.Entry<String, FieldMappingConfig> child : fieldMapping.getFields().entrySet()) {
                nestedProperties.put(child.getKey(), buildOpenApiFieldSchema(child.getValue()));
            }
            schema.put("type", "object");
            schema.put("properties", nestedProperties);
            return schema;
        }

        schema.put("type", toOpenApiType(fieldMapping.getType()));
        return schema;
    }

    private static Map<String, Object> buildOpenApiComponentSchemas(ApiConfig apiConfig) {
        Map<String, Object> schemas = new LinkedHashMap<>();

        for (EndpointConfig endpoint : apiConfig.getEndpoints()) {
            schemas.put(schemaNameFor(endpoint), buildOpenApiSchema(endpoint));
        }

        return schemas;
    }

    private static Map<String, Object> buildOpenApiInfo(ApiConfig apiConfig) {
        String defaultName = "Dynamic API";
        String defaultVersion = "1.0.0";

        if (apiConfig == null || apiConfig.getApi() == null) {
            return Map.of(
                    "title", defaultName,
                    "version", defaultVersion
            );
        }

        String configuredName = apiConfig.getApi().getName();
        String configuredVersion = apiConfig.getApi().getVersion();

        String title = configuredName == null || configuredName.isBlank() ? defaultName : configuredName;
        String version = configuredVersion == null || configuredVersion.isBlank() ? defaultVersion : configuredVersion;

        return Map.of(
                "title", title,
                "version", version
        );
    }

    private static String schemaNameFor(EndpointConfig endpoint) {
        if (endpoint.getResponse() != null
                && endpoint.getResponse().getResponseName() != null
                && !endpoint.getResponse().getResponseName().isBlank()) {
            return endpoint.getResponse().getResponseName();
        }

        String methodPart = endpoint.getMethod() == null ? "get" : endpoint.getMethod().toLowerCase();
        String pathPart = endpoint.getPath() == null ? "root" : endpoint.getPath().replaceAll("[^A-Za-z0-9]+", "_");
        return methodPart + "_" + pathPart + "_response";
    }

    private static String toOpenApiType(String configType) {
        if (configType == null) {
            return "string";
        }

        switch (configType.toLowerCase()) {
            case "integer":
            case "long":
                return "integer";
            case "double":
                return "number";
            case "boolean":
                return "boolean";
            case "string":
            default:
                return "string";
        }
    }
}