package slanglsp.multiplexer.handlers;

import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static slanglsp.multiplexer.utils.LspUtils.METHOD_CANCEL_REQUEST;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_DID_CHANGE_WATCHED_FILES;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_DID_CHANGE_WORKSPACE_FOLDERS;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_EXIT;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_INITIALIZED;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_SET_TRACE;
import static slanglsp.utils.JsonUtils.extractId;

/**
 * Methods broadcast to every slangd process regardless of file scope.
 * <p>
 * initialize is intentionally absent — it is intercepted so each process receives a
 * customised per-module request instead.
 * <p>
 * workspace/didChangeConfiguration is intentionally absent — it is intercepted so each
 * process receives a customised per-module notification instead.
 * <p>
 * shutdown is intentionally absent too. It is a request, not a notification, so broadcasting
 * it directly would produce multiple JSON-RPC responses with the same id.
 */
public final class BroadcastHandler implements RoutingHandler {
    private static final Set<String> BROADCAST_METHODS = Set.of(
            METHOD_INITIALIZED,
            METHOD_EXIT,
            METHOD_DID_CHANGE_WATCHED_FILES,
            METHOD_DID_CHANGE_WORKSPACE_FOLDERS,
            METHOD_SET_TRACE,
            METHOD_CANCEL_REQUEST
    );

    private final Set<Object> pending = ConcurrentHashMap.newKeySet();

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) {
        if (!BROADCAST_METHODS.contains(context.method())) {
            return false;
        }

        Object id = extractId(context.json());
        if (id != null) {
            pending.add(id);
        }

        services.broadcastToSlangd(context.body());

        return true;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        Object id = extractId(context.json());

        if (id == null || !pending.remove(id)) {
            return false;
        }

        // we can get multiple responses, take just one and discard the rest
        services.sendToLsp(context.body());

        return true;
    }
}
