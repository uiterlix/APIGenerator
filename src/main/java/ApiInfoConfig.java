import com.fasterxml.jackson.annotation.JsonProperty;

public class ApiInfoConfig {
    @JsonProperty("name")
    private String name;

    @JsonProperty("version")
    private String version;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
