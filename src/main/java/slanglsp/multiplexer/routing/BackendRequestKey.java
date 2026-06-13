package slanglsp.multiplexer.routing;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import slanglsp.multiplexer.SlangdProcess;

import static slanglsp.utils.JsonUtils.extractId;

/**
 * Since we have multiple slangd processes, we need to identify requests and responses by
 * their process and id.
 *
 * @param process
 * @param id
 */
public record BackendRequestKey(@NotNull SlangdProcess process, @NotNull String id) {

    /**
     * Builds a key identifying a request/response on a specific backend process. Returns
     * {@code null} when the message has no usable id (notifications, or ids of an
     * unexpected JSON type).
     */
    public static BackendRequestKey of(@NotNull SlangdProcess process, @NotNull JsonObject json) {
        String id = idKey(extractId(json));
        return id == null ? null : new BackendRequestKey(process, id);
    }

    private static String idKey(Object id) {
        if (id instanceof Number n) return "n:" + n.longValue();
        if (id instanceof String s) return "s:" + s;
        return null;
    }
}
