package slanglsp.multiplexer.utils;

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.annotations.NotNull;
import slanglsp.multiplexer.ModuleInfo;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class ModuleUtils {
    /**
     * Finds the module whose logical root most specifically contains the given file.
     * <p>
     * Modules are first normalized so duplicate IntelliJ modules that share the same
     * logical root are treated as a single module. If multiple module roots contain the
     * file, the module with the longest matching root path is returned, which assigns
     * files in nested modules to the nested module rather than to an ancestor.
     *
     * @param file file to locate within the project's module roots
     * @param project project whose modules should be searched
     * @return the best owning module, or {@code null} if no logical module root contains the file
     */
    public static @Nullable Module findOwningModuleByContentRoot(
            @NotNull VirtualFile file,
            @NotNull Project project
    ) {
        Module bestModule = null;
        int bestRootLength = -1;

        for (Module module : normalizeModules(ModuleManager.getInstance(project).getModules())) {
            VirtualFile root = getLogicalModuleRoot(module);
            if (root == null)
                continue;

            if (VfsUtilCore.isAncestor(root, file, false)
                    && root.getPath().length() > bestRootLength) {
                bestModule = module;
                bestRootLength = root.getPath().length();
            }
        }

        return bestModule;
    }

    /**
     * Returns the directory that should be treated as the module's logical workspace root.
     *
     * @param module module whose logical root should be resolved
     * @return the logical module root, or {@code null} if no external project path or content root exists
     */
    public static @Nullable VirtualFile getLogicalModuleRoot(@NotNull Module module)
    {
        String externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module);
        if (externalProjectPath != null)
        {
            VirtualFile externalProjectRoot = LocalFileSystem.getInstance().findFileByPath(externalProjectPath);
            if (externalProjectRoot != null)
                return externalProjectRoot;
        }

        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        return roots.length == 0 ? null : roots[0];
    }

    /**
     * Finds project modules that contain at least one file accepted by the supplied matcher.
     *
     * @param project project whose modules should be searched
     * @param fileMatcher predicate used to identify matching files
     * @return set of matching module/module-root pairs
     */
    public static Set<ModuleInfo> findModulesMatching(
            @NotNull Project project,
            @NotNull Predicate<VirtualFile> fileMatcher
    ) {
        String projectBasePath = project.getBasePath();
        Module[] allModules = normalizeModules(ModuleManager.getInstance(project).getModules());

        // Collect every content root path across all modules.
        // Used as module boundaries: we never recurse past a directory that is
        // itself a content root of another module.
        Set<String> allContentRootPaths = new HashSet<>();
        for (Module m : allModules)
            for (VirtualFile r : ModuleRootManager.getInstance(m).getContentRoots())
                allContentRootPaths.add(r.getPath());

        // Collect candidates: first logical module root of each module that directly
        // contains slang files (not counting files that live in a sub-directory
        // which is owned by a different module's content root).
        Map<String, ModuleInfo> candidates = new LinkedHashMap<>();
        for (Module module : allModules) {
            VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
            if (roots.length == 0) continue;

            boolean hasMatchingFile = false;
            for (VirtualFile root : roots) {
                if (moduleHasMatchingFile(root, allContentRootPaths, fileMatcher)) {
                    hasMatchingFile = true;
                    break;
                }
            }

            if (!hasMatchingFile) continue;

            VirtualFile logicalRoot = getLogicalModuleRoot(module);
            if (logicalRoot == null) continue;

            candidates.put(logicalRoot.getPath(), new ModuleInfo(module, logicalRoot));
        }

        // Deduplicate: Gradle/Maven projects create sub-modules per source set (e.g. `.main`
        // at `src/main`, `.main` at `build/generated/...`).  We want one process per logical
        // subproject directory, not per source-set directory.
        //
        // Rule: sort paths shortest-first; keep a path only when no already-kept path
        // (other than the project root itself) is a proper ancestor of it.
        List<String> sorted = new ArrayList<>(candidates.keySet());
        sorted.sort(Comparator.comparingInt(String::length));

        List<String> kept = new ArrayList<>();
        outer:
        for (String path : sorted) {
            for (String ancestor : kept) {
                if (!ancestor.equals(projectBasePath) && path.startsWith(ancestor + "/"))
                    continue outer; // dominated by a non-root ancestor → skip
            }
            kept.add(path);
        }

        Set<ModuleInfo> result = new HashSet<>();
        for (String path : kept)
            result.add(candidates.get(path));
        return result;
    }

    /**
     * Normalizes a module array so each logical module root is represented once.
     * <p>
     * IntelliJ may create several modules for one logical project directory, especially
     * for external-system imports with source-set modules such as {@code module.main} and
     * {@code module.test}. When several modules share a logical root, the shortest module
     * name is kept because it is usually the parent module name.
     *
     * @param modules full module list
     * @return normalized module list with at most one module per logical root
     */
    private static Module[] normalizeModules(@NotNull Module[] modules) {
        Map<String, Module> modulesByLogicalRoot = new LinkedHashMap<>();

        for (Module module : modules) {
            VirtualFile logicalRoot = getLogicalModuleRoot(module);
            String key = logicalRoot == null ? module.getName() : logicalRoot.getPath();

            Module existing = modulesByLogicalRoot.get(key);
            if (existing == null || module.getName().length() < existing.getName().length()) {
                modulesByLogicalRoot.put(key, module);
            }
        }

        return modulesByLogicalRoot.values().toArray(Module[]::new);
    }

    /**
     * Returns whether the given root contains a file accepted by the matcher.
     *
     * @param root root file or directory to search
     * @param allContentRootPaths content root paths used as child module boundaries
     * @param fileMatcher predicate used to identify matching files
     * @return {@code true} if a matching file was found under this root; otherwise {@code false}
     */
    private static boolean moduleHasMatchingFile(
            @NotNull VirtualFile root,
            @NotNull Set<String> allContentRootPaths,
            @NotNull Predicate<VirtualFile> fileMatcher
    ) {
        if (!root.isDirectory())
            return fileMatcher.test(root);

        final boolean[] found = {false};
        final String rootPath = root.getPath();

        VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if (found[0])
                    return false;

                if (file.isDirectory()) {
                    if (!file.getPath().equals(rootPath) && allContentRootPaths.contains(file.getPath()))
                        return false;

                    return true;
                }

                if (fileMatcher.test(file)) {
                    found[0] = true;
                    return false;
                }

                return true;
            }
        });

        return found[0];
    }
}