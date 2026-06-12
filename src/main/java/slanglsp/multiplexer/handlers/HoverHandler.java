package slanglsp.multiplexer.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static slanglsp.multiplexer.handlers.TextDocumentHandler.findTextDocumentTarget;
import static slanglsp.multiplexer.utils.JsonRpc.isResponse;
import static slanglsp.utils.JsonUtils.toBytes;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_TEXT_DOCUMENT_HOVER;

/**
 * Cleans up hover requests that can cause LSP errors due to bad formatting.
 */
public final class HoverHandler implements RoutingHandler {
    private final Set<BackendRequestKey> pendingHoverRequests = ConcurrentHashMap.newKeySet();

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        if (!METHOD_TEXT_DOCUMENT_HOVER.equals(context.method())) {
            return false;
        }

        SlangdProcess target = findTextDocumentTarget(context, services);
        if (target == null) {
            return true;
        }

        BackendRequestKey key = BackendRequestKey.of(target, context.json());
        if (key != null) {
            pendingHoverRequests.add(key);
        }

        services.sendToSlangd(target, context.body());
        return true;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!isResponse(context.json())) {
            return false;
        }

        BackendRequestKey key = BackendRequestKey.of(context.process(), context.json());
        if (key == null || !pendingHoverRequests.remove(key)) {
            return false;
        }

        services.sendToLsp(cleanupHoverResponse(context.json(), context.body()));
        return true;
    }

    private byte[] cleanupHoverResponse(@NotNull JsonObject json, byte[] originalBody) {
        if (!json.has("result"))
            return originalBody;

        JsonElement result = json.get("result");
        if (result == null || result.isJsonNull())
            return originalBody;

        if (!result.isJsonObject()) {
            json.add("result", JsonNull.INSTANCE);
            return toBytes(json);
        }

        JsonObject hover = result.getAsJsonObject();
        JsonElement contents = hover.get("contents");
        if (contents == null || contents.isJsonNull()) {
            json.add("result", JsonNull.INSTANCE);
            return toBytes(json);
        }

        return originalBody;
    }
}
