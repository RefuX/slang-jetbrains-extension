package slanglsp.multiplexer.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jetbrains.annotations.NotNull;
import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.BackendRequestKey;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static slanglsp.multiplexer.utils.JsonRpc.isResponse;
import static slanglsp.utils.JsonUtils.GSON;
import static slanglsp.utils.JsonUtils.toBytes;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_INITIALIZE;

/**
 * Coordinates the LSP initialization handshake across all active slangd backend processes.
 * <p>
 * This handler forwards the client {@code initialize} request to each backend with process-specific
 * workspace settings, waits for matching initialization responses, and returns one representative
 * response to the LSP client because it only expects a single response.
 */
public final class InitializationHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(InitializationHandler.class);

    private final BlockingQueue<byte[]> initResults = new LinkedBlockingQueue<>();
    private final Set<BackendRequestKey> pendingInitializeResponses = ConcurrentHashMap.newKeySet();
    private static final long INITIALIZE_TIMEOUT_SECONDS = 30;

    private volatile JsonObject lastInitParams;

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        if (!METHOD_INITIALIZE.equals(context.method())) {
            return false;
        }

        handleInitialize(context.json(), services);
        return true;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) {
        if (!isInitializeResponse(context)) {
            return false;
        }

        initResults.add(context.body());
        return true;
    }

    /**
     * Params from the first {@code initialize} request, used as a template when a backend
     * process is added after the handshake. {@code null} until the client has initialized.
     */
    public JsonObject lastInitParams() {
        return lastInitParams;
    }

    private boolean isInitializeResponse(MessageContext context) {
        if (!isResponse(context.json())) {
            return false;
        }

        BackendRequestKey key = BackendRequestKey.of(context.process(), context.json());
        return key != null && pendingInitializeResponses.remove(key);
    }

    private void handleInitialize(JsonObject json, RoutingServices services) throws IOException {
        JsonObject params = json.has("params") && json.get("params").isJsonObject()
                ? json.getAsJsonObject("params")
                : new JsonObject();

        json.add("params", params);

        // Keep the original client initialize params so dynamically-created
        // backend processes can be initialized later with the same template.
        lastInitParams = params.deepCopy();

        int expectedResults = 0;

        for (SlangdProcess process : services.processes()) {
            if (!process.isAlive()) {
                continue;
            }

            JsonObject processSpecificRequest = buildInitializeRequestForProcess(json, process, services);

            BackendRequestKey key = BackendRequestKey.of(process, processSpecificRequest);
            if (key == null) {
                LOG.warn("Initialize request for slangd process has no trackable request id");
                continue;
            }

            pendingInitializeResponses.add(key);

            try {
                services.sendToSlangd(process, toBytes(processSpecificRequest));
                expectedResults++;
            } catch (IOException e) {
                LOG.warn("Failed to send initialize request to slangd process", e);
            }
        }

        List<byte[]> results = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(INITIALIZE_TIMEOUT_SECONDS);

        while (results.size() < expectedResults) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }

            try {
                byte[] response = initResults.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (response != null) {
                    results.add(response);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // All backend processes run the same language server binary, so their
        // capabilities should be equivalent. Forward one response and discard the rest.
        if (!results.isEmpty()) {
            services.sendToLsp(results.get(0));
        }

        pendingInitializeResponses.clear();
    }

    private JsonObject buildInitializeRequestForProcess(
            @NotNull JsonObject originalInitializeRequest,
            @NotNull SlangdProcess process,
            @NotNull RoutingServices services
    ) {
        JsonObject request = originalInitializeRequest.deepCopy();
        JsonObject originalParams = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();

        request.add("params", customizeInitializeParamsForProcess(originalParams, process, services));

        return request;
    }

    /**
     * Customises a copy of {@code originalParams} so {@code process} sees exactly one
     * workspace folder: its own module root. Exposed so the server can initialise a backend
     * process that is created after the original handshake.
     */
    public JsonObject customizeInitializeParamsForProcess(
            @NotNull JsonObject originalParams,
            @NotNull SlangdProcess process,
            @NotNull RoutingServices services
    ) {
        JsonObject params = originalParams.deepCopy();

        String rootPath = process.moduleRoot().getPath();
        String rootUri = process.moduleRoot().getUrl();
        String workspaceName = process.moduleName();

        params.addProperty("rootPath", rootPath);
        params.addProperty("rootUri", rootUri);

        WorkspaceFolder workspaceFolder = new WorkspaceFolder(rootUri, workspaceName);
        JsonArray workspaceFolders = new JsonArray();
        workspaceFolders.add(GSON.toJsonTree(workspaceFolder));
        params.add("workspaceFolders", workspaceFolders);

        return params;
    }
}
