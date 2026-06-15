package slanglsp.multiplexer.handlers;

import com.intellij.openapi.diagnostic.Logger;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;
import slanglsp.multiplexer.utils.JsonRpcMessageKind;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static slanglsp.multiplexer.utils.JsonRpc.classify;
import static slanglsp.utils.JsonUtils.extractId;

/**
 * If it hasn't been handled yet, just forward to slangd/lsp
 */
public final class FallbackRoutingHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(FallbackRoutingHandler.class);

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        JsonRpcMessageKind classification = classify(context.json());
        if (classification == JsonRpcMessageKind.ERROR) {
            if (context.json().has("error")) {
                LOG.warn("Received JSON-RPC error response from "
                        + context.process().moduleName()
                        + ": "
                        + context.json());
                return true;
            }
        }
        else if (classification == JsonRpcMessageKind.UNKNOWN) {
            LOG.warn("Received unexpected JSON-RPC message from "
                    + context.process().moduleName()
                    + ": "
                    + context.json());
            return true;
        }

        services.sendToLsp(context.body());

        return true;
    }

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        services.sendToSlangd(context.process(), context.body());

        return true;
    }
}