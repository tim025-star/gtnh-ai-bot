package dev.nottambok.gtnhaibot.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class BotControlServer {

    private static final int PORT = 8246;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final ClientBotController controller;
    private final PairingManager pairing;
    private HttpServer httpServer;

    public BotControlServer(ClientBotController controller, PairingManager pairing) {
        this.controller = controller;
        this.pairing = pairing;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
        httpServer.createContext("/v1/health", new Handler());
        httpServer.createContext("/v1/pair", new Handler());
        httpServer.createContext("/v1/snapshot", new Handler());
        httpServer.createContext("/v1/actions", new Handler());
        httpServer.createContext("/v1/stop", new Handler());
        httpServer.setExecutor(null);
        httpServer.start();
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        httpServer = null;
    }

    private class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRemoteAddress()
                    .getAddress()
                    .isLoopbackAddress()) {
                    writeError(exchange, 403, "Loopback access only");
                    return;
                }
                String path = exchange.getRequestURI()
                    .getPath();
                if ("/v1/health".equals(path)) {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        writeError(exchange, 405, "GET required");
                        return;
                    }
                    writeJson(exchange, 200, "{\"ok\":true,\"name\":\"GTNH AI Bot\",\"version\":\"0.1.0\"}");
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeError(exchange, 405, "Unsupported method");
                    return;
                }
                if ("/v1/pair".equals(path)) {
                    requireJsonPost(exchange);
                    JsonObject body = readJson(exchange);
                    String token = pairing.exchange(string(body, "code"));
                    if (token == null) {
                        writeError(exchange, 401, "Invalid or expired pairing code");
                    } else {
                        writeJson(exchange, 200, "{\"token\":\"" + escape(token) + "\"}");
                    }
                    return;
                }
                if (!authenticate(exchange)) {
                    writeError(exchange, 401, "Authentication required");
                    return;
                }
                if ("/v1/snapshot".equals(path)) {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) throw new BadRequest(405, "GET required");
                    writeJson(exchange, 200, controller.callOnClientThread(new Callable<String>() {

                        @Override
                        public String call() {
                            return controller.snapshotJson();
                        }
                    }));
                    return;
                }
                if (path.startsWith("/v1/actions/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    final String actionId = path.substring("/v1/actions/".length());
                    if (actionId.length() < 8 || actionId.length() > 100) throw new BadRequest(400, "Invalid actionId");
                    String status = controller.callOnClientThread(new Callable<String>() {

                        @Override
                        public String call() {
                            return controller.trackedActionJson(actionId);
                        }
                    });
                    if (status == null) writeError(exchange, 404, "Action not found");
                    else writeJson(exchange, 200, status);
                    return;
                }
                requireJsonPost(exchange);
                if ("/v1/stop".equals(path)) {
                    writeJson(exchange, 200, controller.callOnClientThread(new Callable<String>() {

                        @Override
                        public String call() {
                            controller.stopAll();
                            return "{\"ok\":true}";
                        }
                    }));
                    return;
                }
                if ("/v1/actions".equals(path)) {
                    final JsonObject body = readJson(exchange);
                    final String actionId = string(body, "actionId");
                    if (actionId.length() < 8 || actionId.length() > 100) throw new BadRequest(400, "Invalid actionId");
                    final JsonObject action = object(body, "action");
                    String result = controller.callOnClientThread(new Callable<String>() {

                        @Override
                        public String call() {
                            return executeAction(actionId, action);
                        }
                    });
                    writeJson(exchange, 200, result);
                    return;
                }
                writeError(exchange, 404, "Not found");
            } catch (BadRequest ex) {
                writeError(exchange, ex.status, ex.getMessage());
            } catch (Exception ex) {
                writeError(exchange, 500, safe(ex.getMessage()));
            }
        }
    }

    String executeAction(String actionId, JsonObject action) {
        if (!actionId.matches("[A-Za-z0-9-]{8,100}")) throw new BadRequest(400, "Invalid actionId");
        String existing = controller.trackedActionJson(actionId);
        if (existing != null) return existing;
        if (action.has("expectedControlRevision")) {
            long revision;
            try {
                revision = action.get("expectedControlRevision")
                    .getAsBigDecimal()
                    .longValueExact();
            } catch (Exception ex) {
                throw new BadRequest(400, "Invalid control revision");
            }
            if (!controller.hasControlRevision(revision))
                throw new BadRequest(409, "Minecraft control changed while planning");
        }
        String type = string(action, "type");
        String result;
        if ("goto".equals(type) || "break".equals(type)) {
            result = controller.enqueueFromWeb(
                actionId,
                type,
                type + " " + coordinate(action, "x") + " " + y(action) + " " + coordinate(action, "z"));
        } else if ("follow".equals(type)) {
            result = controller.enqueueFromWeb(actionId, type, "follow " + limitedString(action, "player", 32));
        } else if ("use".equals(type) || "place".equals(type)) {
            result = controller.enqueueFromWeb(
                actionId,
                type,
                type + " "
                    + coordinate(action, "x")
                    + " "
                    + y(action)
                    + " "
                    + coordinate(action, "z")
                    + " "
                    + boundedInt(action, "side", 0, 5));
        } else if ("craft".equals(type)) {
            result = controller.enqueueFromWeb(
                actionId,
                type,
                "craft " + limitedString(action, "item", 160) + " " + boundedInt(action, "count", 1, 64));
        } else if ("selectItem".equals(type)) {
            result = controller
                .completeImmediateAction(actionId, type, controller.holdItem(limitedString(action, "item", 160)));
        } else if ("diagnose".equals(type)) {
            String query = string(action, "query");
            if (query.length() > 160 || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0)
                throw new BadRequest(400, "query is invalid");
            result = controller.completeImmediateAction(actionId, type, controller.diagnose(query));
        } else {
            throw new BadRequest(400, "Unknown action type");
        }
        return result;
    }

    private boolean authenticate(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders()
            .getFirst("Authorization");
        return authorization != null && authorization.startsWith("Bearer ")
            && pairing.authenticate(authorization.substring(7));
    }

    private void requireJsonPost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) throw new BadRequest(405, "POST required");
        String contentType = exchange.getRequestHeaders()
            .getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase()
            .startsWith("application/json")) throw new BadRequest(415, "application/json required");
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) throw new BadRequest(413, "Request body too large");
            output.write(buffer, 0, read);
        }
        JsonElement parsed = new JsonParser().parse(new String(output.toByteArray(), "UTF-8"));
        if (!parsed.isJsonObject()) throw new BadRequest(400, "JSON object required");
        return parsed.getAsJsonObject();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) throw new BadRequest(400, key + " must be an object");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new BadRequest(400, key + " is required");
        return value.getAsString()
            .trim();
    }

    private static String limitedString(JsonObject object, String key, int max) {
        String value = string(object, key);
        if (value.length() == 0 || value.length() > max || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new BadRequest(400, key + " is invalid");
        return value;
    }

    private static int coordinate(JsonObject object, String key) {
        return boundedInt(object, key, -30000000, 30000000);
    }

    private static int y(JsonObject object) {
        return boundedInt(object, "y", 0, 255);
    }

    private static int boundedInt(JsonObject object, String key, int min, int max) {
        try {
            JsonElement input = object.get(key);
            if (input == null || !input.isJsonPrimitive()
                || !input.getAsJsonPrimitive()
                    .isNumber())
                throw new BadRequest(400, key + " must be an integer");
            int value = input.getAsBigDecimal()
                .intValueExact();
            if (value < min || value > max) throw new BadRequest(400, key + " is out of range");
            return value;
        } catch (BadRequest ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequest(400, key + " must be an integer");
        }
    }

    private static String safe(String value) {
        return value == null ? "Internal error"
            : value.replace('\r', ' ')
                .replace('\n', ' ')
                .substring(0, Math.min(300, value.length()));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ");
    }

    private static void writeError(HttpExchange exchange, int status, String message) throws IOException {
        writeJson(exchange, status, "{\"error\":\"" + escape(message) + "\"}");
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        output.write(bytes);
        output.close();
    }

    private static class BadRequest extends IllegalArgumentException {

        private final int status;

        private BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
