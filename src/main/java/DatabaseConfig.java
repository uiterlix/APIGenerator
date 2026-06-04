import com.fasterxml.jackson.annotation.JsonProperty;

public class DatabaseConfig {
    @JsonProperty("driverClassName")
    private String driverClassName;

    @JsonProperty("url")
    private String url;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("usernameEnv")
    private String usernameEnv;

    @JsonProperty("passwordEnv")
    private String passwordEnv;

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsernameEnv() {
        return usernameEnv;
    }

    public void setUsernameEnv(String usernameEnv) {
        this.usernameEnv = usernameEnv;
    }

    public String getPasswordEnv() {
        return passwordEnv;
    }

    public void setPasswordEnv(String passwordEnv) {
        this.passwordEnv = passwordEnv;
    }

    public void validate() {
        if (driverClassName == null || driverClassName.isBlank()) {
            throw new IllegalArgumentException("database.driverClassName is required.");
        }

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("database.url is required.");
        }

        if (resolveUsername() == null) {
            throw new IllegalArgumentException("Database username is required. Configure database.username or database.usernameEnv.");
        }

        if (resolvePassword() == null) {
            throw new IllegalArgumentException("Database password is required. Configure database.password or database.passwordEnv.");
        }
    }

    public String resolveUsername() {
        return resolveConfiguredValue(usernameEnv, username);
    }

    public String resolvePassword() {
        return resolveConfiguredValue(passwordEnv, password);
    }

    private String resolveConfiguredValue(String envVariableName, String fallbackValue) {
        if (envVariableName != null && !envVariableName.isBlank()) {
            String envValue = System.getenv(envVariableName);
            if (envValue != null) {
                return envValue;
            }
        }
        return fallbackValue;
    }
}
