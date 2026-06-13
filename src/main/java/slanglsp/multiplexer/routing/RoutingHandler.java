package slanglsp.multiplexer.routing;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * Implement and register to process messages from the LSP and Slangd processes.
 */
public interface RoutingHandler {
    Logger LOG = Logger.getInstance(RoutingHandler.class);

    /**
     * Handles a message received from the IDE/LSP client before it is routed to backend
     * {@code slangd} processes.
     * <p>
     * Implementations should inspect the supplied message context and either route,
     * transform, suppress, or otherwise handle the message. Returning {@code true}
     * signals that the message was claimed and no later routing handlers should process
     * it. Returning {@code false} leaves the message available for the next handler, or
     * for the multiplexer fallback behavior if no handler claims it.
     *
     * @param context parsed message and routing metadata for the client-originated message
     * @param services services for sending messages to backend processes or back to the LSP client
     * @return {@code true} if this handler fully handled the message; otherwise {@code false}
     * @throws IOException if sending or reading routed message data fails
     */
    default boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        return false;
    }

    /**
     * Handles a message received from a backend {@code slangd} process before it is
     * routed back to the IDE/LSP client.
     * <p>
     * Implementations should inspect the supplied message context and either route,
     * transform, suppress, or otherwise handle the message. Returning {@code true}
     * signals that the message was claimed and no later routing handlers should process
     * it. Returning {@code false} leaves the message available for the next handler, or
     * for the multiplexer fallback behavior if no handler claims it.
     *
     * @param context parsed message and routing metadata for the backend-originated message
     * @param services services for sending messages to backend processes or back to the LSP client
     * @return {@code true} if this handler fully handled the message; otherwise {@code false}
     * @throws IOException if sending or reading routed message data fails
     */
    default boolean fromSlangd(MessageContext context, RoutingServices services) throws IOException {
        return false;
    }
}