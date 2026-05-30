package slanglsp;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.*;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.*;

import javax.print.DocFlavor;
import java.io.*;
import java.util.function.Supplier;


@State(
        name="SlangPersistentStateComponentConfig",
        storages = {
                @Storage("SlangPluginSettings.xml")
        }
)
class SlangPersistentStateConfig implements PersistentStateComponent<SlangPersistentStateConfig.State>
{
    static class State
    {
        // TODO: make a cached state which transforms this State into an efficent to compare state (assumes rare to change settings)
        public List<String> additionalIncludePaths = new ArrayList<>();
        public List<String> predefinedMacros = List.of("__EXAMPLE_MACRO1", "__EXAMPLE_MACRO2=VALUE");

        public String explicitSlangdLocation = "";
//        public String traceServer = "off"; // handled by LSP4IJ's (runtime) debug tool
        public String enableCommitCharactersInAutoCompletion = "membersOnly";

        public Boolean enableInlayHintsForDeducedTypes = true;
        public Boolean enableInlayHintsForParameterNames = true;
        public Boolean enableSearchingSubDirectoriesOfWorkspace = true;

        public void copyValues(State otherState)
        {
            additionalIncludePaths = otherState.additionalIncludePaths;
            predefinedMacros = otherState.predefinedMacros;
            explicitSlangdLocation = otherState.explicitSlangdLocation;
            enableCommitCharactersInAutoCompletion = otherState.enableCommitCharactersInAutoCompletion;
            enableInlayHintsForDeducedTypes = otherState.enableInlayHintsForDeducedTypes;
            enableInlayHintsForParameterNames = otherState.enableInlayHintsForParameterNames;
            enableSearchingSubDirectoriesOfWorkspace = otherState.enableSearchingSubDirectoriesOfWorkspace;
        }
        public boolean equals(State other)
        {
            return true
                    && additionalIncludePaths.equals(other.additionalIncludePaths)
                    && predefinedMacros.equals(other.predefinedMacros)
                    && explicitSlangdLocation.equals(other.explicitSlangdLocation)
                    && enableCommitCharactersInAutoCompletion.equals(other.enableCommitCharactersInAutoCompletion)
                    && enableInlayHintsForDeducedTypes.equals(other.enableInlayHintsForDeducedTypes)
                    && enableInlayHintsForParameterNames.equals(other.enableInlayHintsForParameterNames)
                    && enableSearchingSubDirectoriesOfWorkspace.equals(other.enableSearchingSubDirectoriesOfWorkspace)
                    ;
        }

        Object createJSONFromObject(Project project)
        {
            Map<String, Object> stringMap = new HashMap<>();

            List<String> resolvedPaths = getResolvedPaths(project);

            String additionalIncludePathsKey = "slang.additionalSearchPaths";
            stringMap.put(additionalIncludePathsKey, resolvedPaths);

            String predefinedMacrosKey = "slang.predefinedMacros";
            stringMap.put(predefinedMacrosKey, predefinedMacros);

            String enableCommitCharactersInAutoCompletionKey = "slang.enableCommitCharactersInAutoCompletion";
            stringMap.put(enableCommitCharactersInAutoCompletionKey, enableCommitCharactersInAutoCompletion);

            String enableInlayHintsForDeducedTypesKey = "slang.inlayHints.deducedTypes";
            stringMap.put(enableInlayHintsForDeducedTypesKey, enableInlayHintsForDeducedTypes);

            String enableInlayHintsForParameterNamesKey = "slang.inlayHints.parameterNames";
            stringMap.put(enableInlayHintsForParameterNamesKey, enableInlayHintsForParameterNames);

            String enableSearchingSubDirectoriesOfWorkspaceKey = "slang.searchInAllWorkspaceDirectories";
            stringMap.put(enableSearchingSubDirectoriesOfWorkspaceKey, enableSearchingSubDirectoriesOfWorkspace);

            return stringMap;
        }

        private @NotNull List<String> getResolvedPaths(Project project) {
            List<String> resolvedPaths = new ArrayList<>();
            for (String path : additionalIncludePaths) {
                resolvedPaths.addAll(resolvePath(project, path));
            }
            return resolvedPaths;
        }

        private @NotNull List<String> resolvePath(Project project, String path) {
            if (path.contains("$module")) {
                return resolveModulePath(project, path);
            }
            else if(path.contains("$project")) {
                return resolveProjectPath(project, path);
            }
            return pathExists(path) ? List.of(path) : List.of();
        }

        private @NotNull List<String> resolveModulePath(Project project, String path) {
            return resolvePathVariable("$module", path, () -> {
                List<String> modulePaths = new ArrayList<>();

                Module[] modules = ModuleManager.getInstance(project).getModules();
                for (Module module : modules) {
                    VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

                    for (VirtualFile contentRoot : contentRoots) {
                        modulePaths.add(contentRoot.getPath());
                    }
                }

                return modulePaths;
            });
        }

        private @NotNull List<String> resolveProjectPath(Project project, String path) {
            return resolvePathVariable("$project", path, () -> {
                String basePath = project.getBasePath();
                if (basePath == null) {
                    return List.of();
                }

                return List.of(basePath);
            });
        }

        private @NotNull List<String> resolvePathVariable(
                String variableName,
                String path,
                Supplier<List<String>> replacementValuesSupplier
        ) {
            List<String> resolvedPaths = new ArrayList<>();

            for (String replacementValue : replacementValuesSupplier.get()) {
                String resolvedPath = path.replace(variableName, replacementValue);
                if (pathExists(resolvedPath)) {
                    resolvedPaths.add(resolvedPath);
                }
            }

            return resolvedPaths;
        }

        private boolean pathExists(String path) {
            try {
                return Files.exists(Paths.get(path));
            }
            catch (InvalidPathException ignored) {
                return false;
            }
        }
    }

    @NotNull
    private State state = new State();

    Object createJSONFromObject(Project project)
    {
        return state.createJSONFromObject(project);
    }

    String getExplicitSlangdLocation()
    {
        return state.explicitSlangdLocation;
    }

    void setState(State otherState)
    {
        state.copyValues(otherState);
    }

    @NotNull
    @Override
    public State getState()
    {
        return state;
    }

    @Override
    public void loadState(@NotNull State config)
    {
        state = config;
    }


    @Nullable
    public static SlangPersistentStateConfig getInstance(Project project)
    {
        return project.getService(SlangPersistentStateConfig.class);
    }
}
