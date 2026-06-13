package slanglsp.multiplexer.routing;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * Implement and register to process messages from the LSP and Slangd processes.
 */
public interface RoutingHandler {
    Logger LOG = Logger.getInstance(RoutingHandler.class);

    default boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        return false;
    }

    default boolean fromSlangd(MessageContext context, RoutingServices services) throws IOException {
        return false;
    }
}
