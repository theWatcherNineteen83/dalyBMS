package de.dalybms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SwaggerHandler implements HttpHandler {

    private static final Logger logger = Logger.getLogger(SwaggerHandler.class.getName());

    private final int httpPort;
    private String cachedYaml;

    public SwaggerHandler(int httpPort) {
        this.httpPort = httpPort;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        switch (path) {
            case "/swagger":
            case "/swagger/":
                sendResponse(exchange, 200, "text/html", getSwaggerUiHtml());
                break;
            case "/swagger/openapi.yaml":
                sendResponse(exchange, 200, "text/yaml", getOpenApiYaml());
                break;
            default:
                sendResponse(exchange, 404, "text/plain", "Not Found");
        }
    }

    private void sendResponse(HttpExchange exchange, int code,
                              String contentType, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, response.length);
        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }

    /**
     * Lädt die openapi.yaml aus dem Classpath oder Dateisystem.
     * Ersetzt den Port-Platzhalter dynamisch.
     */
    private String getOpenApiYaml() {
        if (cachedYaml != null) {
            return cachedYaml;
        }

        String yaml = null;

        // 1. Versuche aus Classpath zu laden (wenn in JAR verpackt)
        InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.yaml");

        // 2. Versuche aus dem Arbeitsverzeichnis
        if (is == null) {
            try {
                is = new FileInputStream("resources/openapi.yaml");
            } catch (FileNotFoundException e) {
                // ignore
            }
        }

        // 3. Versuche aus dem übergeordneten resources-Ordner
        if (is == null) {
            try {
                is = new FileInputStream("openapi.yaml");
            } catch (FileNotFoundException e) {
                // ignore
            }
        }

        if (is != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                yaml = reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                logger.warning("Fehler beim Laden der openapi.yaml: " + e.getMessage());
            }
        }

        if (yaml == null) {
            logger.severe("openapi.yaml nicht gefunden!");
            yaml = "openapi: 3.0.3\ninfo:\n  title: Error\n  version: '0'\n"
                + "  description: openapi.yaml nicht gefunden\npaths: {}";
        }

        // Port dynamisch ersetzen
        yaml = yaml.replace("localhost:8080", "localhost:" + httpPort);

        cachedYaml = yaml;
        return yaml;
    }

    private String getSwaggerUiHtml() {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"de\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <title>Daly BMS - Swagger UI</title>\n"
            + "  <link rel=\"stylesheet\" "
            + "href=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui.css\">\n"
            + "  <style>\n"
            + "    body { margin: 0; padding: 0; }\n"
            + "    .swagger-ui .topbar { display: none; }\n"
            + "    .custom-header {\n"
            + "      background: #1a1a2e;\n"
            + "      color: #00d4ff;\n"
            + "      padding: 15px 20px;\n"
            + "      font-family: monospace;\n"
            + "      font-size: 1.3em;\n"
            + "      border-bottom: 2px solid #00d4ff;\n"
            + "      display: flex;\n"
            + "      align-items: center;\n"
            + "      gap: 20px;\n"
            + "    }\n"
            + "    .custom-header a {\n"
            + "      color: #00ff88;\n"
            + "      text-decoration: none;\n"
            + "      font-size: 0.75em;\n"
            + "      padding: 5px 12px;\n"
            + "      border: 1px solid #00ff88;\n"
            + "      border-radius: 4px;\n"
            + "    }\n"
            + "    .custom-header a:hover {\n"
            + "      background: #00ff88;\n"
            + "      color: #1a1a2e;\n"
            + "    }\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"custom-header\">\n"
            + "    <span>&#9889; Daly BMS API</span>\n"
            + "    <a href=\"/\">&#127968; Dashboard</a>\n"
            + "    <a href=\"/swagger/openapi.yaml\">&#128196; OpenAPI YAML</a>\n"
            + "  </div>\n"
            + "  <div id=\"swagger-ui\"></div>\n"
            + "  <script src=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js\">"
            + "</script>\n"
            + "  <script>\n"
            + "    SwaggerUIBundle({\n"
            + "      url: '/swagger/openapi.yaml',\n"
            + "      dom_id: '#swagger-ui',\n"
            + "      deepLinking: true,\n"
            + "      defaultModelsExpandDepth: 2,\n"
            + "      defaultModelExpandDepth: 2,\n"
            + "      docExpansion: 'list',\n"
            + "      tryItOutEnabled: true,\n"
            + "      filter: true,\n"
            + "      supportedSubmitMethods: ['get', 'put'],\n"
            + "      layout: 'BaseLayout',\n"
            + "      syntaxHighlight: { activated: true, theme: 'monokai' }\n"
            + "    });\n"
            + "  </script>\n"
            + "</body>\n"
            + "</html>";
    }
}