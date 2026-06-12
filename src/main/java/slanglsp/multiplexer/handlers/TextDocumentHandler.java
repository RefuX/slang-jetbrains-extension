package slanglsp.multiplexer.handlers;

import com.intellij.openapi.diagnostic.Logger;
import slanglsp.multiplexer.handlers.utils.PendingRequestTracker;
import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;

import static slanglsp.utils.JsonUtils.nestedStrField;

/**
 * Routes {@code textDocument/*} messages to the single slangd process that owns the
 * referenced document, and records requests that carry an id so the matching backend
 * response can be recognised as expected.
 */
public final class TextDocumentHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(TextDocumentHandler.class);
    private static final String TEXT_DOCUMENT_PREFIX = "textDocument/";

    private final PendingRequestTracker pendingRequests;

    public TextDocumentHandler(PendingRequestTracker pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) {
        String method = context.method();
        if (method == null || !method.startsWith(TEXT_DOCUMENT_PREFIX)) {
            return false;
        }

        SlangdProcess target = findTextDocumentTarget(context, services);
        if (target == null) {
            return true;
        }

        BackendRequestKey key = BackendRequestKey.of(target, context.json());
        if (key != null) {
            pendingRequests.track(key, method);
        }

        try {
            services.sendToSlangd(target, context.body());
        } catch (IOException e) {
            if (!services.isStopped()) LOG.error("Failed to write LSP message to slangd", e);
        }

        return true;
    }

    /**
     * Resolves the single slangd process that owns the {@code textDocument} referenced by a
     * message. Returns {@code null} (and logs) when no owning process can be found, in which
     * case the caller should consume the message rather than letting it broadcast.
     */
    static SlangdProcess findTextDocumentTarget(MessageContext context, RoutingServices services) {
        String uri = nestedStrField(context.json(), "params", "textDocument", "uri");
        SlangdProcess target = uri != null ? services.findProcessForUri(uri) : null;

        if (target == null) {
            LOG.error("Was unable to route document to slangd process. URI:" + uri);
        }

        return target;
    }

}
