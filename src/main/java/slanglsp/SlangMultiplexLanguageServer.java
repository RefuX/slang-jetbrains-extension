package slanglsp;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import slanglsp.utils.JsonRpc;
import slanglsp.utils.JsonUtils;
import slanglsp.utils.ModuleUtils;
import slanglsp.utils.SlangUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static slanglsp.utils.JsonRpc.*;
import static slanglsp.utils.JsonUtils.*;
import static slanglsp.utils.ModuleUtils.findOwningModuleByContentRoot;
import static slanglsp.utils.ModuleUtils.getLogicalModuleRoot;
import static slanglsp.utils.PathUtils.*;
import static slanglsp.utils.ThreadUtils.startDaemonThread;

class SlangMultiplexLanguageServer implements StreamConnectionProvider {
    private static final Logger LOG = Logger.getInstance(SlangMultiplexLanguageServer.class);

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "initialized";
    private static final String METHOD_SHUTDOWN = "shutdown";
    private static final String METHOD_EXIT = "exit";
    private static final String METHOD_WORKSPACE_CONFIGURATION = "workspace/configuration";
    private static final String METHOD_DID_CHANGE_CONFIGURATION = "workspace/didChangeConfiguration";
    private static final String METHOD_DID_CHANGE_WATCHED_FILES = "workspace/didChangeWatchedFiles";
    private static final String METHOD_DID_CHANGE_WORKSPACE_FOLDERS = "workspace/didChangeWorkspaceFolders";
    private static final String METHOD_TEXT_DOCUMENT_DID_CLOSE = "textDocument/didClose";
    private static final String METHOD_TEXT_DOCUMENT_DID_OPEN = "textDocument/didOpen";
    private static final String METHOD_TEXT_DOCUMENT_HOVER = "textDocument/hover";
    private static final String METHOD_SET_TRACE = "$/setTrace";
    private static final String METHOD_CANCEL_REQUEST = "$/cancelRequest";
    private static final String METHOD_PUBLISH_DIAGNOSTICS = "textDocument/publishDiagnostics";

    // Methods broadcast to every slangd process regardless of file scope.
    // initialize is intentionally absent — we intercept it and send a customised
    // per-module request to each process instead.
    //
    // workspace/didChangeConfiguration is intentionally absent — we intercept it and
    // send a customised per-module notification to each process instead.
    //
    // shutdown is intentionally absent too. It is a request, not a notification, so
    // broadcasting it directly would produce multiple JSON-RPC responses with the same id.
    private static final Set<String> BROADCAST_METHODS = Set.of(
            METHOD_INITIALIZED,
            METHOD_EXIT,
            METHOD_DID_CHANGE_WATCHED_FILES,
            METHOD_DID_CHANGE_WORKSPACE_FOLDERS,
            METHOD_SET_TRACE,
            METHOD_CANCEL_REQUEST
    );

    // Large pipe buffer — LSP messages can be several hundred KB.
    private static final int PIPE_BUFFER_BYTES = 1024 * 1024; // 1 MiB

    private static final byte[] STOP_MERGE_WRITER = new byte[0];

    private static final long INITIALIZE_TIMEOUT_SECONDS = 30;

    private final Project project;
    private final String slangdExePath;

    // LSP4IJ reads this stream. It carries messages from slangd processes to LSP4IJ.
    private final PipedInputStream slangdToLspInputStream;
    private final PipedOutputStream slangdToLspOutputStream;

    // LSP4IJ writes this stream. It carries messages from LSP4IJ to slangd processes.
    private final PipedOutputStream lspToSlangdOutputStream;
    private final PipedInputStream lspToSlangdInputStream;

    // Single writer thread serialises all writes to slangdToLspOutputStream.
    private final BlockingQueue<byte[]> outgoingToLsp = new LinkedBlockingQueue<>();

    // Per-module slangd processes
    private final List<SlangdProcess> processes = new CopyOnWriteArrayList<>();

    // During the initialize handshake, slangd responses are captured here.
    private final BlockingQueue<byte[]> initResults = new LinkedBlockingQueue<>();
    private volatile boolean initPhase = true;

    // Backend responses with these process/id pairs are intentionally swallowed. This is used
    // for broadcast requests such as shutdown, where several slangd processes can
    // legitimately answer with the same JSON-RPC id but LSP4IJ expects one response.
    private final Set<BackendRequestKey> suppressedBackendResponseKeys = ConcurrentHashMap.newKeySet();

    // Initialize is sent to every backend process with the same client id. Track
    // process+id so each backend response is matched independently.
    private final Set<BackendRequestKey> pendingInitializeResponses = ConcurrentHashMap.newKeySet();

    // Hover responses are normalized defensively because LSP4J complains if Hover.contents is null.
    private final Set<BackendRequestKey> pendingHoverRequests = ConcurrentHashMap.newKeySet();

    // Every client request with an id that is forwarded to a backend process is tracked
    // by process+id. A backend response is only forwarded if it matches one of these.
    private final Map<BackendRequestKey, String> pendingBackendRequests = new ConcurrentHashMap<>();

    // Params from the first initialize request — used as a template when a new
    // process is added, then customised for that process/module.
    private volatile JsonObject lastInitParams = null;

    private volatile boolean stopped = false;

    private record BackendRequestKey(@NotNull SlangdProcess process, @NotNull String id) { }

    SlangMultiplexLanguageServer(Project project, String slangdExePath) {
        this.project = project;
        this.slangdExePath = slangdExePath;
        try {
            slangdToLspInputStream = new PipedInputStream(PIPE_BUFFER_BYTES);
            slangdToLspOutputStream = new PipedOutputStream(slangdToLspInputStream);
            lspToSlangdOutputStream = new PipedOutputStream();
            lspToSlangdInputStream = new PipedInputStream(lspToSlangdOutputStream, PIPE_BUFFER_BYTES);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create LSP pipe streams", e);
        }
    }

    @Override
    public InputStream getInputStream() {
        return slangdToLspInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return lspToSlangdOutputStream;
    }

    @Override
    public void start() {
        Set<ModuleInfo> modulesWithSlangFiles = ApplicationManager.getApplication().runReadAction(
                (Computable<Set<ModuleInfo>>) this::detectModulesWithSlangFiles
        );

        processes.addAll(
            modulesWithSlangFiles.stream()
                .map(info -> startProcess(info.module(), info.moduleRoot()))
                .toList()
        );

        startDaemonThread("slang-merge-writer", this::mergeThenWriteToLsp);

        for (SlangdProcess process : processes) {
            String name = "slang-proc-" + moduleLogName(process);
            startDaemonThread(name, () -> routeFromSlangdProcess(process));
        }

        startDaemonThread("slang-lsp-router", this::routeFromLsp);
    }

    @Override
    public void stop() {
        shutdown();
    }

    void slangFilesAdded(@NotNull List<VirtualFile> newSlangFiles, List<VirtualFile> openSlangFiles) {
        if (newSlangFiles.isEmpty())
            return;

        Set<Module> affectedModules = ApplicationManager.getApplication().runReadAction(
                (Computable<Set<Module>>) () -> {
                    Set<Module> modules = new HashSet<>();

                    for (VirtualFile file : newSlangFiles) {
                        Module module = findOwningModuleByContentRoot(file, project);
                        if (module == null)
                            continue;

                        VirtualFile moduleRoot = getLogicalModuleRoot(module);
                        if (moduleRoot == null)
                            continue;

                        addProcessForModuleIfMissing(module, moduleRoot);
                        modules.add(module);
                    }

                    return modules;
                }
        );

        reopenOpenSlangFilesForModules(affectedModules, openSlangFiles);
    }

    public void slangFilesRemoved(@NotNull List<VirtualFile> deletedFiles, List<VirtualFile> openSlangFiles) {
        if (deletedFiles.isEmpty())
            return;

        // TODO: Remove modules processes that no longer have slang files?

        Set<Module> affectedModules = ApplicationManager.getApplication().runReadAction(
                (Computable<Set<Module>>) () -> {
                    Set<Module> modules = new HashSet<>();

                    for (VirtualFile file : deletedFiles) {
                        Module module = findOwningModuleByContentRoot(file, project);
                        if (module == null)
                            continue;

                        modules.add(module);
                    }

                    return modules;
                }
        );

        reopenOpenSlangFilesForModules(affectedModules, openSlangFiles);
    }

    // didChangeConfiguration doesn't seem enough to nudge slangd for new files
    private void reopenOpenSlangFilesForModules(@NotNull Set<Module> affectedModules, List<VirtualFile> openSlangFiles) {
        if (affectedModules.isEmpty() || openSlangFiles == null || openSlangFiles.isEmpty())
            return;

        ApplicationManager.getApplication().runReadAction(() -> {
            for (VirtualFile file : openSlangFiles) {
                if (file == null || !file.isValid() || file.isDirectory())
                    continue;

                Module module = findOwningModuleByContentRoot(file, project);
                if (module == null || !affectedModules.contains(module))
                    continue;

                SlangdProcess process = findProcessForUri(file.getUrl());
                if (process == null || !process.isAlive())
                    continue;

                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null)
                    continue;

                try {
                    sendDidClose(process, file);
                    sendDidOpen(process, file, document);
                } catch (IOException e) {
                    if (!stopped) LOG.error("Failed to reopen Slang document in slangd process", e);
                }
            }
        });
    }

    private void sendDidClose(@NotNull SlangdProcess process, @NotNull VirtualFile file) throws IOException {
        DidCloseTextDocumentParams closeParams =
                new DidCloseTextDocumentParams(
                        new TextDocumentIdentifier(file.getUrl())
                );

        JsonObject params = GSON.toJsonTree(closeParams).getAsJsonObject();

        JsonObject notification = notification(METHOD_TEXT_DOCUMENT_DID_CLOSE, params);
        writeToSlangdProcess(process, toBytes(notification));
    }

    private void sendDidOpen(@NotNull SlangdProcess process, @NotNull VirtualFile file, @NotNull Document document) throws IOException {
        DidOpenTextDocumentParams openParams =
                new DidOpenTextDocumentParams(
                        new TextDocumentItem(
                                file.getUrl(),
                                "slang",
                                0,
                                document.getText()
                        )
                );

        JsonObject params = GSON.toJsonTree(openParams).getAsJsonObject();

        JsonObject notification = notification(METHOD_TEXT_DOCUMENT_DID_OPEN, params);
        writeToSlangdProcess(process, toBytes(notification));
    }

    private void sendDidChangeConfiguration(Module module) {
        SlangPersistentStateConfig config = SlangPersistentStateConfig.getInstance(project);
        assert config != null;

        processes.stream()
                .filter(Objects::nonNull)
                .filter(process -> process.module().equals(module))
                .forEach(process -> {
                    // TODO: Only send if settings have changed?
                    Map<String, Object> settings = config.getState().toSettings();

                    DidChangeConfigurationParams changeParams = new DidChangeConfigurationParams(settings);
                    JsonObject params = GSON.toJsonTree(changeParams).getAsJsonObject();

                    JsonObject notification = notification(METHOD_DID_CHANGE_CONFIGURATION, params);
                    try {
                        writeToSlangdProcess(process, toBytes(notification));
                    } catch (IOException e) {
                        LOG.error("Failed to send didChangeConfiguration notification to slangd process", e);
                    }
                });
    }

    /**
     * Creates a new slangd process for {@code module}/{@code moduleRoot} if no existing
     * process already covers that root. The new process is initialised with the same
     * original {@code initialize} params template that was used for the original processes,
     * but customised so this backend process sees exactly one workspace folder: its module root.
     */
    private void addProcessForModuleIfMissing(@NotNull Module module, @NotNull VirtualFile moduleRoot) {
        String rootPath = moduleRoot.getPath();
        for (SlangdProcess process : processes)
            if (process.moduleRoot().getPath().equals(rootPath))
                return;

        startDaemonThread("slang-init-" + module.getName(), () -> {
            SlangdProcess newProc = startProcess(module, moduleRoot);

            JsonObject initParams = lastInitParams;
            if (initParams != null) {
                // Send initialize with a synthetic id that we won't forward to LSP4IJ.
                JsonObject response = request(
                        Integer.MAX_VALUE,
                        METHOD_INITIALIZE,
                        customizeInitializeParamsForProcess(initParams, newProc)
                );

                writeToSlangdProcess(newProc, toBytes(response));

                // Block until the initializeResult arrives, then discard it.
                readMessage(newProc.process().getInputStream());

                InitializedParams initializedParams = new InitializedParams();
                JsonObject params = GSON.toJsonTree(initializedParams).getAsJsonObject();

                JsonObject notification = notification(METHOD_INITIALIZED, params);
                writeToSlangdProcess(newProc, toBytes(notification));
            }

            processes.add(newProc);
            startDaemonThread("slang-proc-" + module.getName(), () -> routeFromSlangdProcess(newProc));
        });
    }

    private void routeFromLsp() {
        try {
            while (!stopped) {
                byte[] body = JsonRpc.readMessage(lspToSlangdInputStream);
                if (body == null) break;

                try {
                    JsonObject json = parse(body);
                    String method = strField(json);
                    if (method == null) {
                        continue;
                    }

                    if (METHOD_INITIALIZE.equals(method)) {
                        handleInitialize(json);
                    } else if (METHOD_SHUTDOWN.equals(method)) {
                        handleShutdown(body, json);
                    } else if (METHOD_DID_CHANGE_CONFIGURATION.equals(method)) {
                        // Update all modules with new settings.
                        processes.stream().map(SlangdProcess::module).forEach(this::sendDidChangeConfiguration);
                    } else if (shouldBroadcastToAllProcesses(method)) {
                        broadcastToAllProcesses(body);
                    } else if (method.startsWith("textDocument/")) {
                        routeTextDocumentMessage(body, json);
                    } else {
                        broadcastToAllProcesses(body);
                    }
                } catch (Exception e) {
                    // Per-message error — log and keep routing; do NOT exit the loop.
                    if (!stopped) LOG.warn("Failed to route LSP message", e);
                }
            }
        } catch (IOException e) {
            if (!stopped) LOG.warn("LSP4IJ input stream closed or broken", e);
        } finally {
            shutdown();
        }
    }

    private void routeFromSlangdProcess(SlangdProcess process) {
        try {
            while (!stopped) {
                byte[] slangdOutputBody = JsonRpc.readMessage(process.process().getInputStream());
                if (slangdOutputBody == null) break;

                JsonObject json = JsonRpc.parse(slangdOutputBody);
                String method = JsonUtils.strField(json);

                if (METHOD_WORKSPACE_CONFIGURATION.equals(method)) {
                    // Intercept: answer with per-module settings, never forward to LSP4IJ.
                    JsonObject response = buildConfigResponse(json);
                    writeToSlangdProcess(process, toBytes(response));
                } else if (isSuppressedBackendResponse(process, json)) {
                    // Suppress duplicate responses from broadcast backend requests.
                } else if (isInitializeResponse(process, json)) {
                    // Capture initializeResult during handshake.
                    initResults.add(slangdOutputBody);
                } else if (isHoverResponse(process, json)) {
                    outgoingToLsp.add(normalizeHoverResponse(json, slangdOutputBody));
                } else if (isUnexpectedBackendResponse(process, json)) {
                    // Suppress responses that do not correspond to a request sent to this process.
                    LOG.warn("Suppressing unexpected backend response from "
                            + moduleLogName(process)
                            + ": "
                            + json);
                } else if (METHOD_PUBLISH_DIAGNOSTICS.equals(method) && !diagnosticsBelongToProcess(json, process)) {
                    // Ignore diagnostics emitted by a process that does not own the file.
                } else {
                    outgoingToLsp.add(slangdOutputBody);
                }
            }
        } catch (IOException e) {
            if (!stopped) LOG.warn("slangd process stream closed or broken", e);
        } finally {
            if (!process.isAlive()) {
                try {
                    LOG.warn("slangd process exited for module "
                            + moduleLogName(process)
                            + " with exit code "
                            + process.process().exitValue());
                } catch (IllegalThreadStateException ignored) {
                    // Process is still alive despite the stream ending.
                }
            }

            if (processes.stream().noneMatch(SlangdProcess::isAlive)) {
                shutdown();
            }
        }
    }

    private void shutdown() {
        if (stopped) return;
        stopped = true;

        for (SlangdProcess process : processes) {
            process.destroy();
        }

        closeQuietly(lspToSlangdInputStream);
        closeQuietly(lspToSlangdOutputStream);
        closeQuietly(slangdToLspInputStream);
        closeQuietly(slangdToLspOutputStream);

        outgoingToLsp.offer(STOP_MERGE_WRITER);
    }

    private SlangdProcess startProcess(Module module, @NotNull VirtualFile root) {
        try {
            ProcessBuilder pb = new ProcessBuilder(slangdExePath);
            pb.directory(new File(root.getPath()));

            SlangdProcess process = new SlangdProcess(module, root, pb.start());
            pipeSlangdErrorsToLog(process);

            return process;
        } catch (IOException e) {
            throw new RuntimeException("Failed to start slangd process", e);
        }
    }

    private void pipeSlangdErrorsToLog(@NotNull SlangdProcess process) {
        String moduleName = moduleLogName(process);

        startDaemonThread("slang-stderr-" + moduleName, () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.process().getErrorStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.warn("slangd stderr [" + moduleName + "]: " + line);
                }
            } catch (IOException e) {
                if (!stopped && process.isAlive()) {
                    LOG.warn("Failed reading slangd stderr for module " + moduleName, e);
                }
            }
        });
    }

    private static String moduleLogName(@NotNull SlangdProcess process) {
        Module module = process.module();
        return module == null ? "<fallback>" : module.getName();
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Ignore cleanup failures.
        }
    }

    private Set<ModuleInfo> detectModulesWithSlangFiles() {
        return ModuleUtils.findModulesMatching(project, SlangUtils::isSlangFile);
    }

    private void routeTextDocumentMessage(byte[] body, JsonObject json) {
        String uri = nestedStrField(json, "params", "textDocument", "uri");
        SlangdProcess target = (uri != null) ? findProcessForUri(uri) : null;

        if (target == null) {
            LOG.error("Was unable to route document to slangd process. URI:" + uri);
            return;
        }

        String method = JsonUtils.strField(json);
        BackendRequestKey key = backendRequestKey(target, json);
        if (key != null)
            pendingBackendRequests.put(key, method);

        if (METHOD_TEXT_DOCUMENT_HOVER.equals(method) && key != null)
            pendingHoverRequests.add(key);

        try {
            writeToSlangdProcess(target, body);
        } catch (IOException e) {
            if (!stopped) LOG.error("Failed to write LSP message to slangd", e);
        }
    }

    private void handleInitialize(JsonObject json) {
        JsonObject params = json.has("params") && json.get("params").isJsonObject()
                ? json.getAsJsonObject("params")
                : new JsonObject();

        params.addProperty("trace", "on");
        json.add("params", params);

        // Stash original client params so we can replay a process-specific
        // initialize handshake for processes added later.
        lastInitParams = params.deepCopy();

        int expectedResults = 0;
        for (SlangdProcess process : processes) {
            if (!process.isAlive()) continue;

            try {
                JsonObject processSpecificRequest = buildInitializeRequestForProcess(json, process);
                BackendRequestKey key = backendRequestKey(process, processSpecificRequest);
                if (key != null)
                    pendingInitializeResponses.add(key);

                writeToSlangdProcess(process, toBytes(processSpecificRequest));
                expectedResults++;
            } catch (IOException e) {
                if (!stopped) LOG.warn("Failed to send initialize request to slangd process", e);
            }
        }

        // Collect initializeResult responses. Use a global timeout so projects
        // with many modules do not wait INITIALIZE_TIMEOUT_SECONDS per failed process.
        List<byte[]> results = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(INITIALIZE_TIMEOUT_SECONDS);

        while (results.size() < expectedResults) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) break;

            try {
                byte[] response = initResults.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (response != null) results.add(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // All processes run the same slangd binary — capabilities are identical.
        // Forward the first response; discard the rest.
        if (!results.isEmpty()) // For debugging: JsonRpc.parse(results.get(0))
            outgoingToLsp.add(results.get(0));

        initPhase = false;
        pendingInitializeResponses.clear();
    }

    private JsonObject buildInitializeRequestForProcess(JsonObject originalInitializeRequest, SlangdProcess process) {
        JsonObject request = originalInitializeRequest.deepCopy();
        JsonObject originalParams = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();

        request.add("params", customizeInitializeParamsForProcess(originalParams, process));

        return request;
    }

    private JsonObject customizeInitializeParamsForProcess(JsonObject originalParams, SlangdProcess process) {
        JsonObject params = originalParams.deepCopy();

        String rootPath = process.moduleRoot().getPath();
        String rootUri = process.moduleRoot().getUrl();
        String workspaceName = moduleLogName(process);

        params.addProperty("rootPath", rootPath);
        params.addProperty("rootUri", rootUri);

        WorkspaceFolder workspaceFolder = new WorkspaceFolder(rootUri, workspaceName);
        JsonArray workspaceFolders = new JsonArray();
        workspaceFolders.add(GSON.toJsonTree(workspaceFolder));
        params.add("workspaceFolders", workspaceFolders);

        return params;
    }

    private void handleShutdown(byte[] body, JsonObject json) {
        for (SlangdProcess process : processes) {
            if (!process.isAlive())
                continue;

            BackendRequestKey key = backendRequestKey(process, json);
            if (key != null)
                suppressedBackendResponseKeys.add(key);

            try {
                writeToSlangdProcess(process, body);
            } catch (IOException ioe) {
                if (!stopped) LOG.warn("Failed to broadcast shutdown to slangd", ioe);
            }
        }

        JsonObject response = successResponse(extractId(json), JsonNull.INSTANCE);
        outgoingToLsp.add(toBytes(response));
    }

    private void mergeThenWriteToLsp() {
        try {
            while (!stopped) {
                byte[] body = outgoingToLsp.poll(1, TimeUnit.SECONDS);
                if (body == STOP_MERGE_WRITER) break;
                if (body != null)
                    writeMessage(slangdToLspOutputStream, body);
            }
        } catch (InterruptedException e) {
            LOG.error("Failed to write message back to LSP", e);
            Thread.currentThread().interrupt();
            shutdown();
        } catch (IOException e) {
            if (!stopped) LOG.warn("Failed to write message back to LSP", e);
            shutdown();
        }
    }

    private JsonObject buildConfigResponse(JsonObject requestJson) {
        Object id = extractId(requestJson);
        JsonObject rawParams = requestJson.has("params") && requestJson.get("params").isJsonObject()
                ? requestJson.getAsJsonObject("params")
                : new JsonObject();

        ConfigurationParams params = GSON.fromJson(rawParams, ConfigurationParams.class);

        SlangPersistentStateConfig config = SlangPersistentStateConfig.getInstance(project);
        assert config != null;

        Map<String, Object> settingsMap = config.getState().toSettings();

        JsonArray result = new JsonArray();
        List<ConfigurationItem> items = params != null && params.getItems() != null
                ? params.getItems()
                : List.of();

        for (ConfigurationItem item : items) {
            String section = item.getSection();
            result.add(toJsonElement(section != null ? settingsMap.get(section) : null));
        }

        return successResponse(id, result);
    }

    private SlangdProcess findProcessForUri(String uri) {
        if (processes.isEmpty()) return null;

        try {
            // Use the longest-prefix path match so source-set sub-modules still
            // route to the correct subproject's slangd process.
            String filePath = normalizedPathFromUri(uri);

            SlangdProcess best = null;
            int bestLen = -1;

            for (SlangdProcess process : processes) {
                String rootPath = normalizedVirtualFilePath(process.moduleRoot());
                if (isSameOrUnder(filePath, rootPath) && rootPath.length() > bestLen) {
                    best = process;
                    bestLen = rootPath.length();
                }
            }

            return best != null ? best : processes.get(0);
        } catch (Exception e) {
            return processes.get(0);
        }
    }

    private static boolean uriBelongsToProcess(String uri, SlangdProcess p) {
        if (uri == null) return true;

        try {
            String filePath = normalizedPathFromUri(uri);
            String rootPath = normalizedVirtualFilePath(p.moduleRoot());
            return isSameOrUnder(filePath, rootPath);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean diagnosticsBelongToProcess(JsonObject json, SlangdProcess process) {
        String uri = JsonUtils.nestedStrField(json, "params", "uri");
        return uriBelongsToProcess(uri, process);
    }

    private boolean isInitializeResponse(@NotNull SlangdProcess process, @NotNull JsonObject json) {
        if (!initPhase || !isResponse(json))
            return false;

        BackendRequestKey key = backendResponseKey(process, json);
        return key != null && pendingInitializeResponses.remove(key);
    }

    private boolean isHoverResponse(@NotNull SlangdProcess process, @NotNull JsonObject json) {
        if (!isResponse(json))
            return false;

        BackendRequestKey key = backendResponseKey(process, json);
        if (key == null || !pendingHoverRequests.remove(key))
            return false;

        pendingBackendRequests.remove(key);
        return true;
    }

    private boolean isUnexpectedBackendResponse(@NotNull SlangdProcess process, @NotNull JsonObject json) {
        if (!isResponse(json))
            return false;

        BackendRequestKey key = backendResponseKey(process, json);
        if (key == null)
            return false;

        return pendingBackendRequests.remove(key) == null;
    }

    private byte[] normalizeHoverResponse(@NotNull JsonObject json, byte[] originalBody) {
        if (!json.has("result"))
            return originalBody;

        JsonElement result = json.get("result");
        if (result == null || result.isJsonNull())
            return originalBody;

        if (!result.isJsonObject()) {
            json.add("result", JsonNull.INSTANCE);
            return toBytes(json);
        }

        JsonObject hover = result.getAsJsonObject();
        JsonElement contents = hover.get("contents");
        if (contents == null || contents.isJsonNull()) {
            json.add("result", JsonNull.INSTANCE);
            return toBytes(json);
        }

        return originalBody;
    }

    private void broadcastToAllProcesses(byte[] body) {
        for (SlangdProcess process : processes) {
            if (!process.isAlive()) continue;

            try {
                writeToSlangdProcess(process, body);
            } catch (IOException ioe) {
                if (!stopped) LOG.warn("Failed to broadcast LSP message to slangd", ioe);
            }
        }
    }

    private static void writeToSlangdProcess(SlangdProcess p, byte[] body) throws IOException {
        writeMessage(p.process().getOutputStream(), body);
    }

    private static boolean shouldBroadcastToAllProcesses(String method) {
        return BROADCAST_METHODS.contains(method);
    }

    private static BackendRequestKey backendRequestKey(@NotNull SlangdProcess process, @NotNull JsonObject requestJson) {
        String id = idKey(extractId(requestJson));
        return id == null ? null : new BackendRequestKey(process, id);
    }

    private static BackendRequestKey backendResponseKey(@NotNull SlangdProcess process, @NotNull JsonObject responseJson) {
        String id = idKey(extractId(responseJson));
        return id == null ? null : new BackendRequestKey(process, id);
    }

    private static String idKey(Object id) {
        if (id instanceof Number n) return "n:" + n.longValue();
        if (id instanceof String s) return "s:" + s;
        return null;
    }

    private boolean isSuppressedBackendResponse(@NotNull SlangdProcess process, @NotNull JsonObject json) {
        if (!isResponse(json))
            return false;

        BackendRequestKey key = backendResponseKey(process, json);
        return key != null && suppressedBackendResponseKeys.remove(key);
    }
}