package nl.uiterlix.apigenerator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class ResponseConfig {
    @JsonProperty("responseName")
    private String responseName;

    @JsonProperty("mapping")
    private Map<String, FieldMappingConfig> mapping;

    public String getResponseName() {
        return responseName;
    }

    public void setResponseName(String responseName) {
        this.responseName = responseName;
    }

    public Map<String, FieldMappingConfig> getMapping() {
        return mapping;
    }

    public void setMapping(Map<String, FieldMappingConfig> mapping) {
        this.mapping = mapping;
    }

    public void validate(String endpointPath) {
        if (mapping == null) {
            return;
        }

        for (Map.Entry<String, FieldMappingConfig> entry : mapping.entrySet()) {
            entry.getValue().validate(entry.getKey(), endpointPath);
        }
    }
}