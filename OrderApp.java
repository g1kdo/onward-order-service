import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Onward Logistics order system: a single full stack application.
 * The same process serves the user interface (HTML) and the order logic.
 *
 * Routes:
 *   GET  /        order page, showing who built it and which server answered
 *   POST /orders  validates an order and prices the delivery
 *   GET  /health  load balancer health check
 *
 * Configuration comes from environment variables set in the ECS task definition (taskdef.json).
 * Run locally with: java OrderApp.java   (Java 21, no dependencies)
 */
public class OrderApp {

    static final String FULL_NAME = env("FULL_NAME", "Onward engineer");
    static final String LAB_NAME = env("LAB_NAME", "Onward Logistics Order System");
    static final String APP_VERSION = env("APP_VERSION", "local");
    static final int PORT = Integer.parseInt(env("PORT", "8080"));

    // Which task answered: read once from the ECS task metadata endpoint at startup
    static final Map<String, String> SERVER = loadServerIdentity();

    // Recent orders taken by this server only (in memory, lost when the server is replaced)
    static final Deque<String> RECENT_ORDERS = new ConcurrentLinkedDeque<>();
    static final AtomicLong ORDER_SEQUENCE = new AtomicLong(1000);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", OrderApp::handleHome);
        server.createContext("/orders", OrderApp::handleOrder);
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "ok"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("Onward order app listening on port %d as task %s, version %s%n", PORT, SERVER.get("taskId"), APP_VERSION);
    }

    // ------------------------------------------------------------------ user interface

    static void handleHome(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            respond(exchange, 404, "text/plain", "Not found");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", page(""));
    }

    static String page(String message) {
        StringBuilder orders = new StringBuilder();
        for (String order : RECENT_ORDERS) {
            orders.append("<li>").append(order).append("</li>");
        }
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              <style>
                body { font-family: system-ui, sans-serif; max-width: 720px; margin: 40px auto; padding: 0 16px; color: #232f3e; }
                .card { border: 1px solid #d5dbdb; border-radius: 8px; padding: 16px 20px; margin: 16px 0; }
                .server { background: #f2f6e8; border-color: #7aa116; }
                .message { background: #e8f3fa; border-color: #147eba; }
                label { display: block; margin-top: 10px; }
                input, select, button { font-size: 1rem; padding: 6px; margin-top: 4px; }
                button { background: #ff9900; border: 0; border-radius: 4px; padding: 8px 16px; cursor: pointer; }
              </style>
            </head>
            <body>
              <h1>%s</h1>
              <h2>%s</h2>
              <div class="card server">
                <strong>Served by task</strong> %s in %s (private IP %s)<br>
                <strong>Version</strong> %s<br>
                <small>Refresh to see the load balancer share requests across tasks.</small>
              </div>
              %s
              <div class="card">
                <h3>Place an order</h3>
                <form method="post" action="/orders">
                  <label>Merchant name <input name="merchant" required maxlength="60"></label>
                  <label>Number of parcels <input name="parcels" type="number" min="1" max="50" value="1" required></label>
                  <label>Delivery zone
                    <select name="zone">
                      <option value="city">Same city</option>
                      <option value="regional">Regional</option>
                      <option value="cross-border">Cross-border</option>
                    </select>
                  </label>
                  <p><button type="submit">Place order</button></p>
                </form>
              </div>
              <div class="card">
                <h3>Recent orders on this server</h3>
                <ul>%s</ul>
              </div>
            </body>
            </html>
            """.formatted(
                escape(LAB_NAME), escape(FULL_NAME), escape(LAB_NAME),
                escape(SERVER.get("taskId")), escape(SERVER.get("availabilityZone")), escape(SERVER.get("privateIp")),
                escape(APP_VERSION),
                message.isEmpty() ? "" : "<div class=\"card message\">" + message + "</div>",
                orders.isEmpty() ? "<li>No orders yet</li>" : orders);
    }

    // ------------------------------------------------------------------ order logic

    static void handleOrder(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().add("Location", "/");
            respond(exchange, 303, "text/plain", "");
            return;
        }
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        String merchant = form.getOrDefault("merchant", "").trim();
        String zone = form.getOrDefault("zone", "");
        int parcels;
        try {
            parcels = Integer.parseInt(form.getOrDefault("parcels", ""));
        } catch (NumberFormatException e) {
            parcels = 0;
        }

        String error = validate(merchant, parcels, zone);
        if (error != null) {
            respond(exchange, 400, "text/html; charset=utf-8", page("<strong>Order rejected:</strong> " + escape(error)));
            return;
        }

        BigDecimal fee = deliveryFee(parcels, zone);
        long orderId = ORDER_SEQUENCE.incrementAndGet();
        String summary = "#%d · %s · %d parcel(s) · %s · %s USD".formatted(orderId, escape(merchant), parcels, zone, fee);
        RECENT_ORDERS.addFirst(summary);
        while (RECENT_ORDERS.size() > 10) {
            RECENT_ORDERS.removeLast();
        }
        respond(exchange, 200, "text/html; charset=utf-8",
                page("<strong>Order confirmed:</strong> " + summary + " (taken by task " + escape(SERVER.get("taskId")) + ")"));
    }

    static String validate(String merchant, int parcels, String zone) {
        if (merchant.isEmpty() || merchant.length() > 60) return "merchant name must be 1 to 60 characters";
        if (parcels < 1 || parcels > 50) return "parcels must be between 1 and 50";
        if (!zone.equals("city") && !zone.equals("regional") && !zone.equals("cross-border")) return "unknown delivery zone";
        return null;
    }

    /** Base fee plus a per-parcel charge, scaled by how far the parcels travel. */
    static BigDecimal deliveryFee(int parcels, String zone) {
        BigDecimal multiplier = switch (zone) {
            case "regional" -> new BigDecimal("1.5");
            case "cross-border" -> new BigDecimal("2.5");
            default -> BigDecimal.ONE;
        };
        BigDecimal subtotal = new BigDecimal("2.50").add(new BigDecimal("0.75").multiply(BigDecimal.valueOf(parcels)));
        return subtotal.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ helpers

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Reads the task ID, Availability Zone and private IP from the ECS task metadata endpoint (v4). */
    static Map<String, String> loadServerIdentity() {
        Map<String, String> identity = new HashMap<>(Map.of(
                "taskId", "local-" + Instant.now().getEpochSecond(),
                "availabilityZone", "local",
                "privateIp", "127.0.0.1"));
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (metadataUri == null) {
            System.out.println("ECS task metadata not available, running locally");
            return identity;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String task = client.send(HttpRequest.newBuilder(URI.create(metadataUri + "/task")).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            String taskArn = jsonValue(task, "TaskARN");
            identity.put("taskId", taskArn.substring(taskArn.lastIndexOf('/') + 1));
            identity.put("availabilityZone", jsonValue(task, "AvailabilityZone"));
            Matcher ip = Pattern.compile("\"IPv4Addresses\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").matcher(task);
            if (ip.find()) {
                identity.put("privateIp", ip.group(1));
            }
        } catch (Exception e) {
            System.out.println("Could not read ECS task metadata: " + e.getMessage());
        }
        return identity;
    }

    /** Minimal lookup of a top-level string field in a JSON document, enough for the metadata response. */
    static String jsonValue(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "unknown";
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
