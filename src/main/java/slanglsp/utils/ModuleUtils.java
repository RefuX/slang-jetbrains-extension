package slanglsp.utils;

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
import slanglsp.ModuleInfo;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class ModuleUtils {
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
     * There can often be multiple modules with the same logical root.
     * - module1
     * - module1.test
     * - module1.main
     * <p>
     * We want just moudle1
     *
     * @param modules Full module list
     * @return The normalized module list
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
     * Find a file in a module that matches a given predicate.
     * TODO: Could pass in all modules instead of content roots
     *
     * @param root The root of the search path
     * @param allContentRootPaths Stops recursing into a submodule if it is a child of one of these paths
     * @param fileMatcher The function to match files against
     * @return True if a matching file was found, false otherwise
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
