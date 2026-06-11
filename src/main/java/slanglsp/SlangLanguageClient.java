package slanglsp;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.ServerStatus;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import static slanglsp.utils.JsonUtils.toNestedJson;

class SlangLanguageClient extends LanguageClientImpl
{
    static LinkedBlockingDeque<SlangLanguageClient> maybeAliveClients = new LinkedBlockingDeque<>();

    Project project;
    SlangLanguageClient(Project project)
    {
        super(project);
        this.project = project;
        maybeAliveClients.add(this);
    }

    @Override
    public Object createSettings()
    {
        SlangPersistentStateConfig.State state = SlangPersistentStateConfig.getInstance(project).getState();
        Map<String, Object> settings = state.toSettings();

        return toNestedJson(settings);
    }

    @Override
    public void triggerChangeConfiguration()
    {
        super.triggerChangeConfiguration();
    }

    @Override
    public void handleServerStatusChanged(@NonNull ServerStatus serverStatus)
    {
        if (serverStatus == ServerStatus.started)
        {
            triggerChangeConfiguration();
        }
        if(serverStatus == ServerStatus.stopped)
        {
            maybeAliveClients.remove(this);
        }
    }
}