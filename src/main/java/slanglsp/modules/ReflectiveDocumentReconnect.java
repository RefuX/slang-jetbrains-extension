package slanglsp.modules;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Forces {@code slangd} to re-resolve a document's imports/includes by tearing down
 * and rebuilding its lsp4ij-side connection state for that document, without touching
 * any editor.
 * <p>
 * This calls two package-private methods on lsp4ij's {@code LanguageServerWrapper} via
 * reflection: {@code disconnect(VirtualFile, boolean)} followed by
 * {@code connect(VirtualFile, LSPFileConnectionInfo)}. Bytecode inspection of
 * lsp4ij 0.14.2 confirms these are exactly the methods a real editor close/open
 * eventually calls into: {@code disconnect} removes the document from
 * {@code openedDocuments}, unregisters its {@code DocumentContentSynchronizer} as a
 * document listener, disposes it (which sends the real {@code didClose}), and clears
 * tracked diagnostics; {@code connect} creates a fresh synchronizer (resetting its
 * version counter), re-registers it, and sends a real {@code didOpen}. Passing
 * {@code false} to {@code disconnect} skips its {@code maybeShutdown()} call so the
 * underlying {@code slangd} process isn't stopped just because one document was
 * momentarily not in {@code openedDocuments}.
 * <p>
 * There is no public lsp4ij API for this — {@link #isAvailable()} reports whether
 * these internals still have the expected shape on the lsp4ij version actually
 * loaded, so callers can fall back to a real editor close/reopen if a future lsp4ij
 * update renames or removes them.
 */
final class ReflectiveDocumentReconnect {
    private static final Logger LOG = Logger.getInstance(ReflectiveDocumentReconnect.class);

    private static volatile boolean resolved = false;
    private static volatile boolean available = false;

    private static Constructor<?> connectionInfoConstructor;
    private static Method connectMethod;
    private static Method disconnectMethod;

    private ReflectiveDocumentReconnect() {
    }

    static synchronized boolean isAvailable() {
        if (!resolved)
            resolve();
        return available;
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> connectionInfoClass = Class.forName(
                    "com.redhat.devtools.lsp4ij.LanguageServerWrapper$LSPFileConnectionInfo");

            connectionInfoConstructor = connectionInfoClass.getDeclaredConstructor(
                    Document.class, String.class, String.class, boolean.class);
            connectionInfoConstructor.setAccessible(true);

            connectMethod = LanguageServerWrapper.class.getDeclaredMethod(
                    "connect", VirtualFile.class, connectionInfoClass);
            connectMethod.setAccessible(true);

            disconnectMethod = LanguageServerWrapper.class.getDeclaredMethod(
                    "disconnect", VirtualFile.class, boolean.class);
            disconnectMethod.setAccessible(true);

            available = true;
        } catch (ReflectiveOperationException e) {
            LOG.warn("lsp4ij's internal LanguageServerWrapper connect/disconnect shape has "
                    + "changed; falling back to editor-based resync", e);
            available = false;
        }
    }

    /**
     * Reconnects {@code file} on {@code wrapper}: disconnects its current document (if
     * any) without stopping the underlying server, then connects a fresh one seeded
     * with {@code documentText}. Sends a real didClose followed by a real didOpen.
     *
     * @return {@code true} if the reconnect was attempted (regardless of whether the
     * server accepted it asynchronously); {@code false} if reflection isn't available,
     * in which case the caller should fall back to a real editor close/reopen.
     */
    static boolean reconnect(
            @NotNull LanguageServerWrapper wrapper,
            @NotNull VirtualFile file,
            @NotNull Document document,
            @NotNull String documentText,
            @NotNull String languageId
    ) {
        if (!isAvailable())
            return false;

        try {
            disconnectMethod.invoke(wrapper, file, false);

            Object connectionInfo = connectionInfoConstructor.newInstance(document, documentText, languageId, false);
            connectMethod.invoke(wrapper, file, connectionInfo);

            return true;
        } catch (ReflectiveOperationException e) {
            LOG.warn("Failed to reconnect " + file.getName() + " via lsp4ij internals", e);
            return false;
        }
    }
}
