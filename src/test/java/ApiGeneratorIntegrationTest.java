import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.uiterlix.apigenerator.ApiGenerator;
import nl.uiterlix.apigenerator.config.ApiConfig;
import nl.uiterlix.apigenerator.config.DatabaseConfig;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spark.Spark;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApiGeneratorIntegrationTest {
    private static Server h2Server;
    private static int apiPort;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        h2Server = Server.createTcpServer("-tcpPort", "9123", "-tcpDaemon", "-ifNotExists").start();
        String dbUrl = "jdbc:h2:tcp://localhost:9123/mem:apigenerator;DB_CLOSE_DELAY=-1";

        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255))");
            statement.execute("CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(255))");
            statement.execute("INSERT INTO users (id, name) VALUES (1, 'Alice')");
            statement.execute("INSERT INTO products (id, name) VALUES (2, 'Keyboard')");
        }

        apiPort = findFreePort();
        ApiConfig config = ApiGenerator.loadConfig(new String[0]);
        DatabaseConfig databaseConfig = new DatabaseConfig();
        databaseConfig.setDriverClassName("org.h2.Driver");
        databaseConfig.setUrl(dbUrl);
        databaseConfig.setUsername("sa");
        databaseConfig.setPassword("");
        config.setDatabase(databaseConfig);
        ApiGenerator.start(config, apiPort);
        Spark.awaitInitialization();
    }

    @AfterAll
    static void teardown() {
        Spark.stop();
        if (h2Server != null) {
            h2Server.stop();
        }
    }

    @Test
    void usersEndpointUsesPathParamAndResponseMapping() throws Exception {
        Map<String, Object> body = getJson("/users/1");
        Map<String, Object> user = JSON.convertValue(body.get("user"), new TypeReference<>() {});
        Map<String, Object> profile = JSON.convertValue(user.get("profile"), new TypeReference<>() {});
        assertEquals(1, user.get("id"));
        assertEquals("Alice", profile.get("name"));
    }

    @Test
    void productsEndpointUsesQueryParamAndResponseMapping() throws Exception {
        Map<String, Object> body = getJson("/products?id=2");
        Map<String, Object> product = JSON.convertValue(body.get("product"), new TypeReference<>() {});
        Map<String, Object> details = JSON.convertValue(product.get("details"), new TypeReference<>() {});
        assertEquals(2, product.get("id"));
        assertEquals("Keyboard", details.get("name"));
    }

    @Test
    void openApiEndpointReflectsConfiguredPathsAndParameters() throws Exception {
        Map<String, Object> openApi = getJson("/openapi");
        Map<String, Object> info = JSON.convertValue(openApi.get("info"), new TypeReference<>() {});
        Map<String, Object> paths = JSON.convertValue(openApi.get("paths"), new TypeReference<>() {});
        Map<String, Object> components = JSON.convertValue(openApi.get("components"), new TypeReference<>() {});
        Map<String, Object> schemas = JSON.convertValue(components.get("schemas"), new TypeReference<>() {});
        assertEquals("APIGenerator Service", info.get("title"));
        assertEquals("1.0.0", info.get("version"));
        assertEquals(true, paths.containsKey("/users/{id}"));
        assertEquals(true, paths.containsKey("/products"));
        assertEquals(true, schemas.containsKey("UserResponse"));
        assertEquals(true, schemas.containsKey("ProductResponse"));

        Map<String, Object> usersPath = JSON.convertValue(paths.get("/users/{id}"), new TypeReference<>() {});
        Map<String, Object> usersGet = JSON.convertValue(usersPath.get("get"), new TypeReference<>() {});
        assertEquals("Fetch a user by ID", usersGet.get("description"));
        java.util.List<Map<String, Object>> usersParameters = JSON.convertValue(usersGet.get("parameters"), new TypeReference<>() {});
        Map<String, Object> userIdParam = usersParameters.get(0);
        Map<String, Object> userIdParamSchema = JSON.convertValue(userIdParam.get("schema"), new TypeReference<>() {});
        assertEquals("integer", userIdParamSchema.get("type"));
        assertEquals("int32", userIdParamSchema.get("format"));
        assertEquals("Unique user identifier", userIdParam.get("description"));

        Map<String, Object> productsPath = JSON.convertValue(paths.get("/products"), new TypeReference<>() {});
        Map<String, Object> productsGet = JSON.convertValue(productsPath.get("get"), new TypeReference<>() {});
        assertEquals("Fetch a product by ID", productsGet.get("description"));
        java.util.List<Map<String, Object>> productsParameters = JSON.convertValue(productsGet.get("parameters"), new TypeReference<>() {});
        Map<String, Object> productIdParam = productsParameters.get(0);
        Map<String, Object> productIdParamSchema = JSON.convertValue(productIdParam.get("schema"), new TypeReference<>() {});
        assertEquals("integer", productIdParamSchema.get("type"));
        assertEquals("int32", productIdParamSchema.get("format"));
        assertEquals("Unique product identifier", productIdParam.get("description"));

        Map<String, Object> responses = JSON.convertValue(usersGet.get("responses"), new TypeReference<>() {});
        Map<String, Object> response200 = JSON.convertValue(responses.get("200"), new TypeReference<>() {});
        Map<String, Object> content = JSON.convertValue(response200.get("content"), new TypeReference<>() {});
        Map<String, Object> appJson = JSON.convertValue(content.get("application/json"), new TypeReference<>() {});
        Map<String, Object> schemaRef = JSON.convertValue(appJson.get("schema"), new TypeReference<>() {});
        assertEquals("#/components/schemas/UserResponse", schemaRef.get("$ref"));

        Map<String, Object> userSchema = JSON.convertValue(schemas.get("UserResponse"), new TypeReference<>() {});
        Map<String, Object> userSchemaProperties = JSON.convertValue(userSchema.get("properties"), new TypeReference<>() {});
        Map<String, Object> userObject = JSON.convertValue(userSchemaProperties.get("user"), new TypeReference<>() {});
        Map<String, Object> userObjectProperties = JSON.convertValue(userObject.get("properties"), new TypeReference<>() {});
        Map<String, Object> userProfile = JSON.convertValue(userObjectProperties.get("profile"), new TypeReference<>() {});
        Map<String, Object> userProfileProperties = JSON.convertValue(userProfile.get("properties"), new TypeReference<>() {});
        assertEquals("User payload", userObject.get("description"));
        assertEquals("Profile information", userProfile.get("description"));
        Map<String, Object> userIdSchema = JSON.convertValue(userObjectProperties.get("id"), new TypeReference<>() {});
        Map<String, Object> userNameSchema = JSON.convertValue(userProfileProperties.get("name"), new TypeReference<>() {});
        assertEquals("integer", userIdSchema.get("type"));
        assertEquals("Unique user identifier", userIdSchema.get("description"));
        assertEquals("string", userNameSchema.get("type"));
        assertEquals("Display name of the user", userNameSchema.get("description"));
    }

    @Test
    void healthEndpointsReportLiveAndReady() throws Exception {
        Map<String, Object> live = getJson("/health/live");
        Map<String, Object> ready = getJson("/health/ready");
        Map<String, Object> readySecond = getJson("/health/ready");

        assertEquals("UP", live.get("status"));
        assertEquals("UP", ready.get("status"));
        assertEquals("UP", readySecond.get("status"));
        assertEquals(ready.get("checkedAtEpochMs"), readySecond.get("checkedAtEpochMs"));
    }

    private static Map<String, Object> getJson(String path) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + apiPort + path))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "Expected successful response but got " + response.statusCode());
        return JSON.readValue(response.body(), new TypeReference<>() {});
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
