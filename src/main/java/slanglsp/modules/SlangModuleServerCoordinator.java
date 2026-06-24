package slanglsp.modules;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.Alarm;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import com.redhat.devtools.lsp4ij.server.definition.ServerFileNamePatternMapping;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import slanglsp.modules.utils.ModuleUtils;
import slanglsp.utils.SlangUtils;

/**
 * Registers one real, independent {@code slangd} connection per module with lsp4ij's
 * {@code LanguageServersRegistry}, instead of multiplexing many backend processes
 * behind a single fake connection.
 * <p>
 * lsp4ij already supports running several concurrent {@code LanguageServerDefinition}s
 * within one project and routing each file to whichever definition's
 * {@link SlangModuleFileAssociation} matches it (see {@code LanguageServiceAccessor}).
 * It also already handles starting/stopping the underlying process lazily, and
 * sending didOpen/didClose for currently-open files when a definition is
 * added/removed (see {@code LanguageServiceAccessor.checkCurrentlyOpenFiles}, invoked
 * from its {@code LanguageServerDefinitionListener}). None of that needs to be
 * reimplemented here.
 */
public final class SlangModuleServerCoordinator implements Disposable {
    private static final Logger LOG = Logger.getInstance(SlangModuleServerCoordinator.class);

    private final Project project;
    private final String slangdExePath;

    // Module root path -> the definition currently registered for it.
    private final Map<String, SlangModuleLanguageServerDefinition> registeredDefinitions = new HashMap<>();

    private boolean started = false;

    // Debounces the resync below so a burst of VFS events (e.g. a directory delete that
    // fires many child events, or a VCS checkout) triggers it once rather than once per
    // event batch.
    private final Alarm resyncAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private static final int RESYNC_DEBOUNCE_MS = 300;

    public SlangModuleServerCoordinator(@NotNull Project project, @NotNull String slangdExePath) {
        this.project = project;
        this.slangdExePath = slangdExePath;
    }

    public void start() {
        if (started)
            return;
        started = true;

        reconcile();

        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                if (touchesSlangFiles(events)) {
                    reconcile();
                    scheduleEditorResync();
                }
            }
        });
    }

    /**
     * Whether any event in this batch could plausibly change which {@code .slang}
     * files exist or are reachable — i.e. anything other than an in-place content edit
     * of an already-known file. Excludes {@link VFileContentChangeEvent} so normal
     * typing/saving never triggers a reconcile or an editor resync.
     */
    private static boolean touchesSlangFiles(@NotNull List<? extends VFileEvent> events) {
        for (VFileEvent event : events) {
            if (event instanceof VFileContentChangeEvent)
                continue;

            VirtualFile file = event.getFile();
            if (file == null)
                continue;

            // A deleted/moved/created directory may contain (or have contained) .slang
            // files; a created/deleted/moved file might itself be one.
            if (file.isDirectory() || SlangUtils.isSlangFile(file))
                return true;
        }
        return false;
    }

    /**
     * Nudges {@code slangd} to re-resolve imports/includes for every currently-open
     * {@code .slang} document.
     * <p>
     * {@code slangd} does not appear to revalidate already-open documents' resolved
     * imports purely from a {@code workspace/didChangeWatchedFiles} notification or
     * {@code workspace/didChangeConfiguration} — only a fresh didClose/didOpen for the
     * affected document reliably does. This is most visible with files reached only via
     * the "Additional Include/Import paths" setting (not a workspace content root, and
     * not covered by "search subdirectories of workspace"): deleting or recreating such
     * a path leaves an already-open file that imports it showing stale resolution until
     * it gets a fresh didOpen.
     * <p>
     * Prefers {@link ReflectiveDocumentReconnect}, which tears down and rebuilds
     * lsp4ij's internal connection state for the document directly (real didClose then
     * real didOpen, no editor involved, no visible flicker). Falls back to a real
     * {@link FileEditorManager} close/reopen wherever that isn't available — sending
     * didClose/didOpen straight over the lsp4j stub without going through lsp4ij's own
     * teardown/rebuild was tried first and rejected: it left lsp4ij's bookkeeping
     * (the document's version counter, its document-listener registration) out of sync
     * with {@code slangd}'s view, which corrupted later hover responses
     * (ambiguous-Either deserialization failures) until a real editor reopen repaired
     * it. {@code ReflectiveDocumentReconnect} avoids that because it calls the same
     * internal methods a real editor reopen calls into, just without the editor.
     * <p>
     * Not scoped to a particular module's root: an include/import path can point
     * anywhere on disk, so there is no reliable way to know which open documents are
     * actually affected without parsing their import graphs. Resyncing every open
     * {@code .slang} document is simpler and correct, at the cost of also resyncing
     * documents that didn't need it.
     */
    private void scheduleEditorResync() {
        resyncAlarm.cancelAllRequests();
        resyncAlarm.addRequest(this::resyncOpenSlangEditors, RESYNC_DEBOUNCE_MS);
    }

    private void resyncOpenSlangEditors() {
        if (project.isDisposed())
            return;

        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        for (VirtualFile file : editorManager.getOpenFiles()) {
            if (file.isValid() && !file.isDirectory() && SlangUtils.isSlangFile(file))
                resyncDocument(editorManager, file);
        }
    }

    private void resyncDocument(@NotNull FileEditorManager editorManager, @NotNull VirtualFile file) {
        if (!ReflectiveDocumentReconnect.isAvailable()) {
            reopenPreservingCaret(editorManager, file);
            return;
        }

        ApplicationManager.getApplication().runReadAction(() -> {
            if (project.isDisposed() || !file.isValid())
                return;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (psiFile == null || document == null)
                return;

            String documentText = document.getText();

            // ReflectiveDocumentReconnect.reconnect() ultimately calls
            // LanguageServerWrapper.disconnect()/connect(), which mutate the Document's
            // listener list directly (DocumentImpl.removeDocumentListener/
            // addDocumentListener) — not safe to do concurrently with the real
            // editor-driven document lifecycle, which runs on the EDT. getLanguageServers
            // resolves this future on a background pool thread, so it must be dispatched
            // to the EDT before touching the wrapper.
            LanguageServiceAccessor.getInstance(project)
                    .getLanguageServers(psiFile, f -> true, f -> true)
                    .thenAccept(servers -> ApplicationManager.getApplication().invokeLater(() -> {
                        for (LanguageServerItem server : servers) {
                            boolean reconnected = ReflectiveDocumentReconnect.reconnect(
                                    server.getServerWrapper(), file, document, documentText, "slang");

                            if (!reconnected)
                                reopenPreservingCaret(FileEditorManager.getInstance(project), file);
                        }
                    }, ModalityState.any()));
        });
    }

    private void reopenPreservingCaret(@NotNull FileEditorManager editorManager, @NotNull VirtualFile file) {
        int caretOffset = -1;
        for (FileEditor editor : editorManager.getEditors(file)) {
            if (editor instanceof TextEditor textEditor) {
                caretOffset = textEditor.getEditor().getCaretModel().getOffset();
                break;
            }
        }

        boolean wasSelected = Arrays.asList(editorManager.getSelectedFiles()).contains(file);

        editorManager.closeFile(file);
        FileEditor[] reopened = editorManager.openFile(file, wasSelected);

        for (FileEditor editor : reopened) {
            if (!(editor instanceof TextEditor textEditor))
                continue;

            Editor reopenedEditor = textEditor.getEditor();

            if (caretOffset >= 0) {
                int safeOffset = Math.min(caretOffset, reopenedEditor.getDocument().getTextLength());
                reopenedEditor.getCaretModel().moveToOffset(safeOffset);
            }

            // openFile(file, focusEditor=true) restores tab selection but, since this
            // runs from a debounced background callback rather than direct user input,
            // doesn't reliably restore actual editor focus — the caret stays at the
            // right offset but its blinking caret doesn't render until the editor is
            // clicked back into. Request focus explicitly for whichever editor was
            // actually selected/focused before the resync.
            if (wasSelected)
                IdeFocusManager.getInstance(project).requestFocus(reopenedEditor.getContentComponent(), true);

            break;
        }
    }

    /**
     * Recomputes which modules currently contain {@code .slang} files and brings the
     * set of registered {@code LanguageServerDefinition}s in line with it: registers
     * definitions for newly-matching modules, unregisters definitions for modules that
     * no longer have any matching file.
     * <p>
     * Registration/unregistration are both cheap, non-blocking calls into lsp4ij's
     * registry — they do not start or stop a process themselves, so this never blocks
     * on backend I/O. They must still run on the EDT: {@code addServerDefinition}/
     * {@code removeServerDefinition} notify lsp4ij's own {@code LanguageServerExplorer}
     * (the LSP console tool window's tree view), which mutates Swing tree state directly
     * and asserts it's only ever touched from the EDT. {@code reconcile()} itself can be
     * called from a background thread — lsp4ij invokes {@code createConnectionProvider}
     * off-EDT, and {@code BulkFileListener.after} isn't guaranteed to run on the EDT
     * either — so only the read-only computation of {@code wantedByRoot} happens on the
     * calling thread; the actual registry mutation is dispatched separately.
     */
    private void reconcile() {
        Set<ModuleInfo> modulesWithSlangFiles = ApplicationManager.getApplication().runReadAction(
                (Computable<Set<ModuleInfo>>) () ->
                        ModuleUtils.findModulesMatching(project, SlangUtils::isSlangFile)
        );

        Map<String, ModuleInfo> wantedByRoot = new HashMap<>();
        for (ModuleInfo info : modulesWithSlangFiles)
            wantedByRoot.put(info.moduleRoot().getPath(), info);

        ApplicationManager.getApplication().invokeLater(
                () -> applyReconciliation(wantedByRoot), ModalityState.any());
    }

    /**
     * Diffs {@code wantedByRoot} against {@link #registeredDefinitions} and applies the
     * difference. Must run on the EDT (see {@link #reconcile()}). Reads/writes
     * {@code registeredDefinitions} only from here and from {@link #dispose()} (also
     * EDT-dispatched), so the two can never race even though {@code reconcile()} may be
     * called concurrently from multiple threads.
     */
    private void applyReconciliation(@NotNull Map<String, ModuleInfo> wantedByRoot) {
        Deque<ModuleInfo> toRegister = new ArrayDeque<>();
        for (Map.Entry<String, ModuleInfo> entry : wantedByRoot.entrySet())
            if (!registeredDefinitions.containsKey(entry.getKey()))
                toRegister.add(entry.getValue());

        Deque<String> toUnregister = new ArrayDeque<>();
        for (String rootPath : registeredDefinitions.keySet())
            if (!wantedByRoot.containsKey(rootPath))
                toUnregister.add(rootPath);

        processNextRegistrationChange(toRegister, toUnregister);
    }

    /**
     * Registers or unregisters one module's definition per EDT dispatch, rather than
     * looping through all pending changes in a single call.
     * <p>
     * {@code addServerDefinition} triggers an <em>asynchronous</em> background task
     * (lsp4ij refreshing already-open editors for the new definition) that iterates its
     * internal {@code fileAssociations} list — a plain, unsynchronized {@code ArrayList}.
     * Calling {@code registerDefinitionForModule}/{@code unregisterDefinitionForModuleRoot}
     * back-to-back for several modules in one call can mutate that same list (via
     * {@code registerAssociation}/{@code removeAssociationsFor}) while an earlier
     * registration's background task is still iterating it, throwing a
     * {@code ConcurrentModificationException} inside lsp4ij itself. Spacing each change
     * across a separate EDT tick substantially reduces — though, since the underlying
     * list isn't synchronized on lsp4ij's side, can't fully guarantee against — that
     * collision.
     */
    private void processNextRegistrationChange(
            @NotNull Deque<ModuleInfo> toRegister,
            @NotNull Deque<String> toUnregister
    ) {
        LanguageServersRegistry registry = LanguageServersRegistry.getInstance();

        if (!toRegister.isEmpty()) {
            ModuleInfo info = toRegister.poll();
            registerDefinitionForModule(registry, info.module(), info.moduleRoot());
        } else if (!toUnregister.isEmpty()) {
            unregisterDefinitionForModuleRoot(registry, toUnregister.poll());
        } else {
            return;
        }

        ApplicationManager.getApplication().invokeLater(
                () -> processNextRegistrationChange(toRegister, toUnregister), ModalityState.any());
    }

    private void registerDefinitionForModule(
            @NotNull LanguageServersRegistry registry,
            @NotNull Module module,
            @NotNull VirtualFile moduleRoot
    ) {
        SlangModuleLanguageServerDefinition definition =
                new SlangModuleLanguageServerDefinition(module, moduleRoot, slangdExePath);

        registry.addServerDefinition(project, definition, List.of());
        registry.registerAssociation(
                definition,
                new ServerFileNamePatternMapping(
                        List.of("*.slang"),
                        definition.getId(),
                        "slang",
                        new SlangModuleFileAssociation(moduleRoot)
                )
        );

        registeredDefinitions.put(moduleRoot.getPath(), definition);
        LOG.info("Registered Slang language server for module '" + module.getName() + "' at " + moduleRoot.getPath());
    }

    private void unregisterDefinitionForModuleRoot(@NotNull LanguageServersRegistry registry, @NotNull String rootPath) {
        LanguageServerDefinition definition = registeredDefinitions.remove(rootPath);
        if (definition == null)
            return;

        registry.removeServerDefinition(project, definition);
        LOG.info("Unregistered Slang language server for module root " + rootPath);
    }

    @Override
    public void dispose() {
        // Same EDT requirement, and same one-at-a-time staggering, as
        // applyReconciliation()/processNextRegistrationChange(); see their javadoc.
        Deque<String> toUnregister = new ArrayDeque<>(registeredDefinitions.keySet());
        ApplicationManager.getApplication().invokeLater(
                () -> processNextRegistrationChange(new ArrayDeque<>(), toUnregister), ModalityState.any());
    }
}
