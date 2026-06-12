package slanglsp.multiplexer.handlers;

import com.intellij.openapi.diagnostic.Logger;
import slanglsp.multiplexer.handlers.utils.PendingRequestTracker;
import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import static slanglsp.multiplexer.utils.JsonRpc.isResponse;

/**
 * Backend responses are only forwarded to the client if they match a request that was
 * actually sent to that process. Anything else (a response with no id, or one whose request
 * was never tracked) is suppressed so stray backend chatter never reaches LSP4IJ.
 */
public final class UnexpectedResponseHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(UnexpectedResponseHandler.class);

    private final PendingRequestTracker pendingRequests;

    public UnexpectedResponseHandler(PendingRequestTracker pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!isResponse(context.json())) {
            return false;
        }

        BackendRequestKey key = BackendRequestKey.of(context.process(), context.json());
        if (key != null && pendingRequests.complete(key) != null) {
            // Expected response: let it fall through to be forwarded to the client.
            return false;
        }

        LOG.warn("Suppressing unexpected backend response from "
                + context.process().moduleName()
                + ": "
                + context.json());
        return true;
    }
}
