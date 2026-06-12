package slanglsp;

import java.util.*;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name="SlangPersistentStateComponentConfig",
        storages = {
                @Storage("SlangPluginSettings.xml")
        }
)
public class SlangPersistentStateConfig implements PersistentStateComponent<SlangPersistentStateConfig.State>
{
    public static final String SLANG_ADDITIONAL_SEARCH_PATHS = "slang.additionalSearchPaths";
    public static final String SLANG_PREDEFINED_MACROS = "slang.predefinedMacros";
    public static final String SLANG_ENABLE_COMMIT_CHARACTERS_IN_AUTO_COMPLETION = "slang.enableCommitCharactersInAutoCompletion";
    public static final String SLANG_INLAY_HINTS_DEDUCED_TYPES = "slang.inlayHints.deducedTypes";
    public static final String SLANG_INLAY_HINTS_PARAMETER_NAMES = "slang.inlayHints.parameterNames";
    public static final String SLANG_SEARCH_IN_ALL_WORKSPACE_DIRECTORIES = "slang.searchInAllWorkspaceDirectories";
    public static final String SLANG_ENABLE_STRICT_PER_MODULE_ISOLATION = "slang.enableStrictPerModuleIsolation";

    public static class State
    {
        // TODO: make a cached state which transforms this State into an efficient to compare state (assumes rare to change settings)
        public List<String> additionalIncludePaths = new ArrayList<>();
        public List<String> predefinedMacros = List.of("__EXAMPLE_MACRO1", "__EXAMPLE_MACRO2=VALUE");

        public String explicitSlangdLocation = "";
//        public String traceServer = "off"; // handled by LSP4IJ's (runtime) debug tool
        public String enableCommitCharactersInAutoCompletion = "membersOnly";

        public Boolean enableStrictPerModuleIsolation = false;
        public Boolean enableInlayHintsForDeducedTypes = true;
        public Boolean enableInlayHintsForParameterNames = true;
        public Boolean enableSearchingSubDirectoriesOfWorkspace = true;

        public void copyValues(State otherState)
        {
            additionalIncludePaths = otherState.additionalIncludePaths;
            predefinedMacros = otherState.predefinedMacros;
            explicitSlangdLocation = otherState.explicitSlangdLocation;
            enableCommitCharactersInAutoCompletion = otherState.enableCommitCharactersInAutoCompletion;
            enableStrictPerModuleIsolation = otherState.enableStrictPerModuleIsolation;
            enableInlayHintsForDeducedTypes = otherState.enableInlayHintsForDeducedTypes;
            enableInlayHintsForParameterNames = otherState.enableInlayHintsForParameterNames;
            enableSearchingSubDirectoriesOfWorkspace = otherState.enableSearchingSubDirectoriesOfWorkspace;
        }
        public boolean equals(State other)
        {
            return additionalIncludePaths.equals(other.additionalIncludePaths)
                    && predefinedMacros.equals(other.predefinedMacros)
                    && explicitSlangdLocation.equals(other.explicitSlangdLocation)
                    && enableCommitCharactersInAutoCompletion.equals(other.enableCommitCharactersInAutoCompletion)
                    && enableStrictPerModuleIsolation.equals(other.enableStrictPerModuleIsolation)
                    && enableInlayHintsForDeducedTypes.equals(other.enableInlayHintsForDeducedTypes)
                    && enableInlayHintsForParameterNames.equals(other.enableInlayHintsForParameterNames)
                    && enableSearchingSubDirectoriesOfWorkspace.equals(other.enableSearchingSubDirectoriesOfWorkspace);
        }

        public Map<String, Object> toSettings()
        {
            Map<String, Object> settings = new HashMap<>();

            settings.put(SLANG_ADDITIONAL_SEARCH_PATHS, additionalIncludePaths);
            settings.put(SLANG_PREDEFINED_MACROS, predefinedMacros);
            settings.put(SLANG_ENABLE_COMMIT_CHARACTERS_IN_AUTO_COMPLETION, enableCommitCharactersInAutoCompletion);
            settings.put(SLANG_ENABLE_STRICT_PER_MODULE_ISOLATION, enableStrictPerModuleIsolation);
            settings.put(SLANG_INLAY_HINTS_DEDUCED_TYPES, enableInlayHintsForDeducedTypes);
            settings.put(SLANG_INLAY_HINTS_PARAMETER_NAMES, enableInlayHintsForParameterNames);
            settings.put(SLANG_SEARCH_IN_ALL_WORKSPACE_DIRECTORIES, enableSearchingSubDirectoriesOfWorkspace);

            return settings;
        }
    }

    @NotNull
    private State state = new State();

    public String getExplicitSlangdLocation()
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
