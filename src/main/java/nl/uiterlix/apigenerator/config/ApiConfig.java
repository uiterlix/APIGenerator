package nl.uiterlix.apigenerator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ApiConfig {
    @JsonProperty("api")
    private ApiInfoConfig api;

    @JsonProperty("database")
    private DatabaseConfig database;

    @JsonProperty("endpoints")
    private List<EndpointConfig> endpoints;

    public ApiInfoConfig getApi() {
        return api;
    }

    public void setApi(ApiInfoConfig api) {
        this.api = api;
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public List<EndpointConfig> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<EndpointConfig> endpoints) {
        this.endpoints = endpoints;
    }

    public void validate() {
        if (database == null) {
            throw new IllegalArgumentException("Top-level database configuration is required.");
        }
        database.validate();

        if (endpoints == null) {
            return;
        }

        for (EndpointConfig endpoint : endpoints) {
            endpoint.validate();
        }
    }
}