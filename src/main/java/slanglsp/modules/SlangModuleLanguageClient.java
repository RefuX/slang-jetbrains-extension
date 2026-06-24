package slanglsp.modules;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;

import java.util.Map;

import slanglsp.SlangPersistentStateConfig;

import static slanglsp.utils.JsonUtils.toNestedJson;

/**
 * Language client for a single module's {@code slangd} connection.
 * <p>
 * Settings are shared project-wide ({@link SlangPersistentStateConfig}), so this is a
 * thin pass-through; the only thing scoped per module is the underlying connection
 * itself (see {@link SlangModuleLanguageServerDefinition}).
 */
final class SlangModuleLanguageClient extends LanguageClientImpl {
    private final Project project;

    SlangModuleLanguageClient(Project project) {
        super(project);
        this.project = project;
    }

    @Override
    public Object createSettings() {
        SlangPersistentStateConfig.State state = SlangPersistentStateConfig.getInstance(project).getState();
        Map<String, Object> settings = state.toSettings();

        return toNestedJson(settings);
    }
}
