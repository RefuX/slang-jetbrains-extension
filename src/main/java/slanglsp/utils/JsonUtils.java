package slanglsp.utils;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class JsonUtils {
    public static final Gson GSON = new Gson();

    public static JsonElement toJsonElement(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof String s) return new JsonPrimitive(s);

        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list)
                arr.add(toJsonElement(item));
            return arr;
        }

        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet())
                obj.add(entry.getKey().toString(), toJsonElement(entry.getValue()));
            return obj;
        }

        return new JsonPrimitive(value.toString());
    }

    /**
     * Converts a flat-key settings map, for example
     * {@code "slang.inlayHints.deducedTypes" → true}, into the nested JSON object that
     * {@code workspace/didChangeConfiguration} requires, for example:
     * {@code {"slang": {"inlayHints": {"deducedTypes": true}}}}.
     * <p>
     * {@code workspace/configuration} uses dotted {@code section} strings so flat keys work
     * there, but {@code workspace/didChangeConfiguration} carries the full VS Code settings
     * tree which slangd navigates as nested JSON.
     */
    public static JsonObject toNestedJson(Map<String, Object> flatMap) {
        JsonObject root = new JsonObject();

        for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            JsonObject current = root;

            for (int i = 0; i < parts.length - 1; i++) {
                if (!current.has(parts[i]) || !current.get(parts[i]).isJsonObject())
                    current.add(parts[i], new JsonObject());

                current = current.getAsJsonObject(parts[i]);
            }

            current.add(parts[parts.length - 1], toJsonElement(entry.getValue()));
        }

        return root;
    }

    public static String strField(JsonObject json) {
        JsonElement el = json.get("method");
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    public static String nestedStrField(JsonObject json, String... keys) {
        JsonElement cur = json;

        for (String key : keys) {
            if (!cur.isJsonObject()) return null;

            cur = cur.getAsJsonObject().get(key);
            if (cur == null) return null;
        }

        return cur.isJsonPrimitive() ? cur.getAsString() : null;
    }

    public static Object extractId(JsonObject json) {
        JsonElement el = json.get("id");
        if (el == null || el.isJsonNull()) return null;

        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            return p.isNumber() ? p.getAsLong() : p.getAsString();
        }

        return null;
    }

    public static byte[] toBytes(JsonObject message) {
        return GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
    }
}
