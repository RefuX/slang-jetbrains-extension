package slanglsp;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;

import java.util.List;

import static slanglsp.utils.NotificationUtil.notifyUser;
import static slanglsp.utils.SlangUtils.findExecutableInPATH;
import static slanglsp.utils.SlangUtils.findExecutableUsingExplicitSlangdLocation;

class SlangLanguageServer extends ProcessStreamConnectionProvider
{
    SlangLanguageServer(Project project)
    {
        // First try to get EXE from the project settings
        var exePath = findExecutableUsingExplicitSlangdLocation(project);
        if(exePath.isEmpty())
        {
            // Next try to get EXE from PATH
            exePath = findExecutableInPATH();
        }
        if (exePath.isPresent())
        {
            super.setCommands(List.of(exePath.get(), ""));
            super.setWorkingDirectory(project.getBasePath());
        }
        else
        {
            notifyUser(
                project,
                "`slangd`/`slangd.exe` was not found in the `PATH` environment variable. It is preferable to add (once the latest vulkan SDK is installed) `$VK_SDK_PATH/bin` to your `PATH` environment variable (on linux the paths *may* differ slightly) to use `slangd` bundled with the Vulkan SDK. After these steps, restart this IDE.",
                NotificationType.ERROR
            );
            LanguageServerManager.getInstance(project).stop("slangLanguageServer");
        }
    }
}
