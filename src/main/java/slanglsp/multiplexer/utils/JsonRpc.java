package slanglsp.multiplexer.utils;

import com.google.gson.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class JsonRpc {
    private JsonRpc() {
    }

    public static JsonObject notification(String method, JsonElement params) {
        JsonObject message = baseMessage();
        message.addProperty("method", method);
        if (params != null)
            message.add("params", params);
        return message;
    }

    public static JsonObject request(Object id, String method, JsonElement params) {
        JsonObject message = baseMessage();
        addId(message, id);
        message.addProperty("method", method);
        if (params != null)
            message.add("params", params);
        return message;
    }

    public static JsonObject successResponse(Object id, JsonElement result) {
        JsonObject message = baseMessage();
        addId(message, id);
        message.add("result", result != null ? result : JsonNull.INSTANCE);
        return message;
    }

    public static JsonObject parse(byte[] body) {
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject baseMessage() {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        return message;
    }

    private static void addId(JsonObject json, Object id) {
        if (id instanceof Number n) json.addProperty("id", n.longValue());
        else if (id instanceof String s) json.addProperty("id", s);
    }

    /**
     * Reads one JSON-RPC message body from {@code in}.
     * Returns {@code null} when the stream is closed.
     */
    public static byte[] readMessage(InputStream in) throws IOException {
        int contentLength = -1;
        int prev = -1;
        StringBuilder line = new StringBuilder();

        while (true) {
            int c = in.read();
            if (c == -1) return null;

            if (c == '\n' && prev == '\r') {
                String header = line.toString().trim();
                if (header.isEmpty()) break; // blank line = end of headers

                if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid Content-Length header: " + header, e);
                    }
                }

                line.setLength(0);
            } else {
                line.append((char) c);
            }

            prev = c;
        }

        if (contentLength < 0)
            throw new IOException("LSP message missing Content-Length header");

        byte[] body = in.readNBytes(contentLength);
        return (body.length == contentLength) ? body : null;
    }

    public static void writeMessage(OutputStream out, byte[] body) throws IOException {
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        synchronized (out) {
            out.write(header);
            out.write(body);
            out.flush();
        }
    }

    /**
     * Message shapes:
     * 1. Request has "method" and "id"
     * 2. Notification has "method" and no id.
     * 3. Response has "id" and either "result" or "error" and no method.
     * 4. Error has "error" and no method/id.
     *
     * @param json input json
     * @return classification of the json rpc message
     */
    public static JsonRpcMessageKind classify(JsonObject json) {
        boolean hasMethod = json.has("method");
        boolean hasId = json.has("id");
        boolean hasResult = json.has("result");
        boolean hasError = json.has("error");

        if (hasMethod && hasId) {
            return JsonRpcMessageKind.REQUEST;
        }

        if (hasMethod) {
            return JsonRpcMessageKind.NOTIFICATION;
        }

        if (hasId && (hasResult || hasError)) {
            return JsonRpcMessageKind.RESPONSE;
        }

        if (hasError) {
            return JsonRpcMessageKind.ERROR;
        }

        return JsonRpcMessageKind.UNKNOWN;
    }

    public static boolean isResponse(JsonObject json) {
        return !json.has("method") && (json.has("result") || json.has("error"));
    }
}
