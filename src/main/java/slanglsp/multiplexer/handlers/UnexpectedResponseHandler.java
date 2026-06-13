package slanglsp.multiplexer.handlers;

import com.intellij.openapi.diagnostic.Logger;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import static slanglsp.multiplexer.utils.JsonRpc.isResponse;

/**
 * Suppresses backend responses not claimed by a more specific handler.
 * <p>
 * Expected responses should be completed and forwarded by the handler that originally
 * routed the matching request. If a response reaches this handler, it is unexpected
 * and should not be forwarded to LSP4IJ.
 */
public final class UnexpectedResponseHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(UnexpectedResponseHandler.class);

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!isResponse(context.json())) {
            return false;
        }

        LOG.warn("Suppressing unexpected backend response from "
                + context.process().moduleName()
                + ": "
                + context.json());
        return true;
    }
}