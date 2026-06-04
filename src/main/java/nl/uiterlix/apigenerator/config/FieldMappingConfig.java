package nl.uiterlix.apigenerator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class FieldMappingConfig {
    @JsonProperty("source")
    private String source;

    @JsonProperty("type")
    private String type;

    @JsonProperty("description")
    private String description;

    @JsonProperty("fields")
    private Map<String, FieldMappingConfig> fields;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, FieldMappingConfig> getFields() {
        return fields;
    }

    public void setFields(Map<String, FieldMappingConfig> fields) {
        this.fields = fields;
    }

    public boolean isObject() {
        return fields != null && !fields.isEmpty();
    }

    public void validate(String fieldPath, String endpointPath) {
        boolean objectField = isObject();

        if (objectField) {
            if (type == null || type.isBlank()) {
                type = "object";
            } else if (!"object".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("Invalid object mapping type at '" + fieldPath
                        + "' in endpoint '" + endpointPath + "'. Expected type 'object'.");
            }

            for (Map.Entry<String, FieldMappingConfig> child : fields.entrySet()) {
                child.getValue().validate(fieldPath + "." + child.getKey(), endpointPath);
            }
            return;
        }

        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Leaf mapping at '" + fieldPath + "' in endpoint '"
                    + endpointPath + "' must define 'source'.");
        }
    }
}