package slanglsp.multiplexer.handlers;

import com.google.gson.JsonNull;
import com.intellij.openapi.diagnostic.Logger;
import slanglsp.multiplexer.handlers.utils.PendingRequestTracker;
import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;

import static slanglsp.multiplexer.utils.JsonRpc.isResponse;
import static slanglsp.multiplexer.utils.JsonRpc.successResponse;
import static slanglsp.utils.JsonUtils.extractId;
import static slanglsp.utils.JsonUtils.toBytes;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_SHUTDOWN;

/**
 * shutdown is a request, not a notification, so broadcasting it directly would produce
 * multiple JSON-RPC responses sharing the same id. Instead we answer the client ourselves
 * with a single response, then forward shutdown to every backend and suppress their responses.
 * <p>
 * The client reply is sent <em>before</em> forwarding to the backends. The client (LSP4IJ)
 * only waits a few seconds for the shutdown response, and forwarding writes to each backend's
 * stdin — a blocking call if a backend is wedged and not draining its input. Replying first
 * guarantees the client gets its response promptly regardless of backend health, instead of
 * timing out behind a stuck backend write.
 */
public final class ShutdownHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(ShutdownHandler.class);

    private final PendingRequestTracker pendingRequests;

    public ShutdownHandler(PendingRequestTracker pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) {
        if (!METHOD_SHUTDOWN.equals(context.method())) {
            return false;
        }

        // Answer the client first so its shutdown request never times out, even if a backend
        // write below blocks. The backend responses we trigger next are suppressed, so this
        // remains the single response the client sees.
        services.sendToLsp(toBytes(successResponse(extractId(context.json()), JsonNull.INSTANCE)));

        for (SlangdProcess process : services.processes()) {
            if (!process.isAlive()) {
                continue;
            }

            BackendRequestKey key = BackendRequestKey.of(process, context.json());
            if (key != null) {
                pendingRequests.suppress(key);
            }

            try {
                services.sendToSlangd(process, context.body());
            } catch (IOException ioe) {
                if (!services.isStopped()) LOG.warn("Failed to broadcast shutdown to slangd", ioe);
            }
        }

        return true;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!isResponse(context.json())) {
            return false;
        }

        BackendRequestKey key = BackendRequestKey.of(context.process(), context.json());
        return key != null && pendingRequests.consumeSuppressed(key);
    }
}
