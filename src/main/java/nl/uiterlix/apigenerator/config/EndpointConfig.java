package nl.uiterlix.apigenerator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class EndpointConfig {
    @JsonProperty("path")
    private String path;

    @JsonProperty("method")
    private String method;

    @JsonProperty("response")
    private ResponseConfig response;

    @JsonProperty("query")
    private String query;

    @JsonProperty("params")
    private List<ParamConfig> params;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<ParamConfig> getParams() {
        return params;
    }

    public void setParams(List<ParamConfig> params) {
        this.params = params;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public ResponseConfig getResponse() {
        return response;
    }

    public void setResponse(ResponseConfig response) {
        this.response = response;
    }

    public void validate() {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Endpoint path is required.");
        }

        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Endpoint method is required for path '" + path + "'.");
        }

        if (!"GET".equalsIgnoreCase(method)) {
            throw new IllegalArgumentException("Only GET requests are supported. Found: " + method);
        }

        if (params != null) {
            for (ParamConfig param : params) {
                param.validate(path);
            }
        }

        if (response != null) {
            response.validate(path);
        }
    }
}