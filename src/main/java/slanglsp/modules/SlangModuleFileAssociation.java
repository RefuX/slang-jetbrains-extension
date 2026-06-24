package slanglsp.modules;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.DocumentMatcher;
import org.jetbrains.annotations.NotNull;

/**
 * Matches files that live under a single module's logical root.
 * <p>
 * Used to scope a per-module {@link SlangModuleLanguageServerDefinition} to exactly the
 * files that module owns, so that lsp4ij routes each file to the one {@code slangd}
 * process responsible for its module instead of broadcasting to every backend.
 */
final class SlangModuleFileAssociation implements DocumentMatcher {
    private final VirtualFile moduleRoot;

    SlangModuleFileAssociation(@NotNull VirtualFile moduleRoot) {
        this.moduleRoot = moduleRoot;
    }

    @Override
    public boolean match(@NotNull VirtualFile file, @NotNull Project project) {
        return moduleRoot.isValid() && VfsUtilCore.isAncestor(moduleRoot, file, false);
    }
}
