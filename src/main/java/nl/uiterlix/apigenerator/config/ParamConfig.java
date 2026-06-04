package nl.uiterlix.apigenerator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ParamConfig {
    @JsonProperty("name")
    private String name;

    @JsonProperty("in")
    private String in;

    @JsonProperty("sqlType")
    private String sqlType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIn() {
        return in;
    }

    public void setIn(String in) {
        this.in = in;
    }

    public String getSqlType() {
        return sqlType;
    }

    public void setSqlType(String sqlType) {
        this.sqlType = sqlType;
    }

    public void validate(String endpointPath) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name is required for endpoint '" + endpointPath + "'.");
        }

        if (in == null || in.isBlank()) {
            throw new IllegalArgumentException("Parameter '" + name + "' in endpoint '" + endpointPath + "' must define 'in'.");
        }

        if (!"path".equalsIgnoreCase(in) && !"query".equalsIgnoreCase(in)) {
            throw new IllegalArgumentException("Parameter '" + name + "' in endpoint '" + endpointPath + "' has unsupported 'in': " + in);
        }

        if (sqlType == null || sqlType.isBlank()) {
            throw new IllegalArgumentException("Parameter '" + name + "' in endpoint '" + endpointPath + "' must define 'sqlType'.");
        }
    }
}