package slanglsp.multiplexer;

import com.google.gson.JsonObject;
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

import slanglsp.multiplexer.handlers.BroadcastHandler;
import slanglsp.multiplexer.handlers.ConfigurationHandler;
import slanglsp.multiplexer.handlers.DiagnosticsHandler;
import slanglsp.multiplexer.handlers.HoverHandler;
import slanglsp.multiplexer.handlers.InitializationHandler;
import slanglsp.multiplexer.handlers.ShutdownHandler;
import slanglsp.multiplexer.handlers.TextDocumentHandler;
import slanglsp.multiplexer.handlers.UnexpectedResponseHandler;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;
import slanglsp.utils.SlangUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static slanglsp.multiplexer.utils.JsonRpc.*;
import static slanglsp.utils.JsonUtils.*;
import static slanglsp.multiplexer.utils.LspUtils.*;
import static slanglsp.multiplexer.utils.ModuleUtils.*;
import static slanglsp.multiplexer.utils.PathUtils.*;
import static slanglsp.multiplexer.utils.ThreadUtils.*;

/**
 * Stream connection provider that multiplexes one LSP4IJ language-server connection
 * across multiple {@code slangd} backend processes.
 * <p>
 * A separate {@code slangd} process is started for each module that contains Slang
 * files. Messages received from LSP4IJ are routed to the appropriate backend process
 * or broadcast when no more specific route applies. Messages received from backend
 * processes are merged back into the single stream consumed by LSP4IJ.
 */
public class SlangMultiplexLanguageServer implements StreamConnectionProvider {
    private static final Logger LOG = Logger.getInstance(SlangMultiplexLanguageServer.class);

    // Large pipe buffer — LSP messages can be several hundred KB.
    private static final int PIPE_BUFFER_BYTES = 1024 * 1024; // 1 MiB

    // The message send to stop the writer
    private static final byte[] STOP_MERGE_WRITER = new byte[0];

    // The IDE project
    private final Project project;

    // Path to slangd
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

    // Reuse the original initialize params template.
    private final InitializationHandler initializationHandler = new InitializationHandler();

    // Ordered chain of handlers.
    private final List<RoutingHandler> handlers;

    // Provided to the handlers
    private final RoutingServices routingServices = new MultiplexRoutingServices();

    private volatile boolean stopped = false;

    public SlangMultiplexLanguageServer(Project project, String slangdExePath) {
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

        this.handlers = List.of(
                initializationHandler,
                new ShutdownHandler(),
                new ConfigurationHandler(project),
                new HoverHandler(),
                new TextDocumentHandler(),
                new BroadcastHandler(),
                new DiagnosticsHandler(),
                new UnexpectedResponseHandler()
        );
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
                (Computable<Set<ModuleInfo>>) () -> findModulesMatching(project, SlangUtils::isSlangFile)
        );

        processes.addAll(
            modulesWithSlangFiles.stream()
                .map(info -> startProcess(info.module(), info.moduleRoot()))
                .toList()
        );

        startDaemonThread("slang-merge-writer", this::mergeThenWriteToLsp);

        for (SlangdProcess process : processes) {
            String name = "slang-proc-" + process.moduleName();
            startDaemonThread(name, () -> routeFromSlangdProcess(process));
        }

        startDaemonThread("slang-lsp-router", this::routeFromLsp);
    }

    @Override
    public void stop() {
        shutdown();
    }

    /**
     * Call if a set of new Slang files has been added to the project.
     * @param newSlangFiles The new Slang files that have been added.
     * @param openSlangFiles Files that are open in the IDE.
     */
    public void slangFilesAdded(@NotNull List<VirtualFile> newSlangFiles, List<VirtualFile> openSlangFiles) {
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

    /**
     * Call if a set of Slang files has been removed from the project.
     * @param deletedFiles The Slang files that have been removed.
     * @param openSlangFiles Files that are open in the IDE.
     */
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

    /**
     * didChangeConfiguration doesn't seem enough to nudge slangd for new files,
     * so we send a close and open request for all open Slang files in the affected modules.
     */
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

    /**
     * Creates a new slangd process for {@code module} if no existing
     * process already covers that module. The new process is initialised with the same
     * original {@code initialize} params template that were used for the original processes,
     * but customised so this backend process sees exactly one workspace folder: its module root.
     */
    private void addProcessForModuleIfMissing(@NotNull Module module, @NotNull VirtualFile moduleRoot) {
        String rootPath = moduleRoot.getPath();
        for (SlangdProcess process : processes)
            if (process.moduleRoot().getPath().equals(rootPath))
                return;

        startDaemonThread("slang-init-" + module.getName(), () -> {
            SlangdProcess newProc = startProcess(module, moduleRoot);

            JsonObject initParams = initializationHandler.lastInitParams();
            if (initParams != null) {
                // Send initialize with a synthetic id that we won't forward to LSP4IJ.
                JsonObject response = request(
                        Integer.MAX_VALUE,
                        METHOD_INITIALIZE,
                        initializationHandler.customizeInitializeParamsForProcess(initParams, newProc, routingServices)
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
                byte[] body = readMessage(lspToSlangdInputStream);
                if (body == null) break;

                try {
                    routeFromLspMessage(body);
                } catch (Exception e) {
                    // Per-message error — log and keep routing; do NOT exit the loop.
                    if (!stopped) LOG.warn("Failed to route LSP message", e);
                }
            }
        } catch (IOException e) {
            processIOException(e);
        } finally {
            shutdown();
        }
    }

    private void routeFromLspMessage(byte[] body) throws IOException {
        JsonObject json = parse(body);
        String method = strField(json);

        if (method == null) {
            return;
        }

        MessageContext context = new MessageContext(null, body, json, method);

        for (RoutingHandler handler : handlers) {
            if (handler.fromLsp(context, routingServices)) {
                return;
            }
        }

        fallbackFromLsp(context);
    }

    /** Unclaimed client messages are broadcast to every backend process. */
    private void fallbackFromLsp(MessageContext context) {
        broadcastToAllProcesses(context.body());
    }

    private void processIOException(IOException e) {
        if (!stopped) {
            if (isExpectedClosedPipe(e)) {
                LOG.debug("LSP4IJ input stream closed", e);
            } else {
                LOG.warn("LSP4IJ input stream closed or broken", e);
            }
        }
    }

    private void routeFromSlangdProcess(SlangdProcess process) {
        try {
            while (!stopped) {
                byte[] body = readMessage(process.process().getInputStream());
                if (body == null) break;

                routeFromSlangdMessage(process, body);
            }
        } catch (IOException e) {
            processIOException(e);
        } finally {
            if (!process.isAlive()) {
                try {
                    LOG.warn("slangd process exited for module "
                            + process.moduleName()
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

    private void routeFromSlangdMessage(SlangdProcess process, byte[] body) throws IOException {
        JsonObject json = parse(body);
        String method = strField(json);

        MessageContext context = new MessageContext(process, body, json, method);

        for (RoutingHandler handler : handlers) {
            if (handler.fromSlangd(context, routingServices)) {
                return;
            }
        }

        routingServices.sendToLsp(body);
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

            return new SlangdProcess(module, root, pb.start());
        } catch (IOException e) {
            throw new RuntimeException("Failed to start slangd process", e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Ignore cleanup failures.
        }
    }

    private static boolean isExpectedClosedPipe(@NotNull IOException e) {
        String message = e.getMessage();
        return message != null && (
                message.contains("Pipe broken")
                        || message.contains("Pipe closed")
                        || message.contains("Write end dead")
                        || message.contains("Read end dead")
                        || message.contains("Stream closed")
        );
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
        } catch (IOException e) {
            processIOException(e);
        }
        finally {
            shutdown();
        }
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

    /**
     * Bridges the handler chain to the multiplexer's process/stream plumbing.
     */
    private final class MultiplexRoutingServices implements RoutingServices {
        @Override
        public List<SlangdProcess> processes() {
            return processes;
        }

        @Override
        public void sendToSlangd(SlangdProcess process, byte[] body) throws IOException {
            writeToSlangdProcess(process, body);
        }

        @Override
        public void sendToLsp(byte[] body) {
            outgoingToLsp.add(body);
        }

        @Override
        public void broadcastToSlangd(byte[] body) {
            broadcastToAllProcesses(body);
        }

        @Override
        public SlangdProcess findProcessForUri(String uri) {
            return SlangMultiplexLanguageServer.this.findProcessForUri(uri);
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }
    }
}
