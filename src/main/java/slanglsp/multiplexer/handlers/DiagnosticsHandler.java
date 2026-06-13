package slanglsp.multiplexer.handlers;

import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import static slanglsp.utils.JsonUtils.nestedStrField;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_PUBLISH_DIAGNOSTICS;
import static slanglsp.multiplexer.utils.PathUtils.isSameOrUnder;
import static slanglsp.multiplexer.utils.PathUtils.normalizedPathFromUri;
import static slanglsp.multiplexer.utils.PathUtils.normalizedVirtualFilePath;

/**
 * Each backend process emits diagnostics for every file it sees, including files owned by a
 * different module's process. Only forward diagnostics for files that live under the
 * emitting process's module root.
 */
public final class DiagnosticsHandler implements RoutingHandler {
    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!METHOD_PUBLISH_DIAGNOSTICS.equals(context.method())) {
            return false;
        }

        String uri = nestedStrField(context.json(), "params", "uri");
        if (uriBelongsToProcess(uri, context.process())) {
            services.sendToLsp(context.body());
        }

        return true;
    }

    private boolean uriBelongsToProcess(String uri, SlangdProcess process) {
        if (uri == null) return true;

        try {
            String filePath = normalizedPathFromUri(uri);
            String rootPath = normalizedVirtualFilePath(process.moduleRoot());
            return isSameOrUnder(filePath, rootPath);
        } catch (Exception e) {
            return true;
        }
    }
}
