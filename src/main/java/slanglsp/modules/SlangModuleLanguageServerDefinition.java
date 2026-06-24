package slanglsp.modules;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import slanglsp.utils.SlangClientFeatures;

/**
 * Defines one real, independent {@code slangd} connection scoped to a single module.
 * <p>
 * Each instance is registered with lsp4ij's {@code LanguageServersRegistry} alongside a
 * {@link SlangModuleFileAssociation} that restricts it to files under {@code moduleRoot}.
 * lsp4ij then owns the connection's full lifecycle (start, stop, didOpen/didClose
 * fan-out for matching files) the same way it would for any other registered server —
 * there is no custom routing or broadcasting code involved.
 */
final class SlangModuleLanguageServerDefinition extends LanguageServerDefinition {
    private final VirtualFile moduleRoot;
    private final String slangdExePath;

    SlangModuleLanguageServerDefinition(
            @NotNull Module module,
            @NotNull VirtualFile moduleRoot,
            @NotNull String slangdExePath
    ) {
        super(idFor(moduleRoot), "Slang Language Server (" + module.getName() + ")", null, false, null, false);
        this.moduleRoot = moduleRoot;
        this.slangdExePath = slangdExePath;
    }

    /**
     * Builds a stable id from the module root path so the same module is re-associated
     * with the same definition id across registration/unregistration cycles.
     */
    static String idFor(@NotNull VirtualFile moduleRoot) {
        return "slanglsp.SlangLanguageServer.module." + moduleRoot.getPath();
    }

    @NotNull
    VirtualFile moduleRoot() {
        return moduleRoot;
    }

    @NotNull
    @Override
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        ProcessStreamConnectionProvider provider = new ProcessStreamConnectionProvider() {
        };
        provider.setCommands(List.of(slangdExePath, ""));
        provider.setWorkingDirectory(moduleRoot.getPath());
        return provider;
    }

    @NotNull
    @Override
    public LanguageClientImpl createLanguageClient(@NotNull Project project) {
        return new SlangModuleLanguageClient(project);
    }

    @NotNull
    @Override
    public LSPClientFeatures createClientFeatures() {
        return SlangClientFeatures.withoutPullDiagnostics();
    }
}
