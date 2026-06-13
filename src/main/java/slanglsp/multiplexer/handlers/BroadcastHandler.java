package slanglsp.multiplexer.handlers;

import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.util.Set;

import static slanglsp.multiplexer.utils.LspUtils.METHOD_CANCEL_REQUEST;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_DID_CHANGE_WATCHED_FILES;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_DID_CHANGE_WORKSPACE_FOLDERS;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_EXIT;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_INITIALIZED;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_SET_TRACE;

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

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) {
        if (!BROADCAST_METHODS.contains(context.method())) {
            return false;
        }

        services.broadcastToSlangd(context.body());

        return true;
    }
}
