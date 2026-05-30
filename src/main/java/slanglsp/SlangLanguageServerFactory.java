package slanglsp;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.ServerStatus;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.configuration.TextMateUserBundlesSettings;
import org.jetbrains.plugins.textmate.TextMateService;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.concurrent.LinkedBlockingDeque;

import com.intellij.openapi.Disposable;

public class SlangLanguageServerFactory implements LanguageServerFactory
{
    private static final List<String> SLANG_FILE_EXTENSIONS = List.of(
            ".slang",
            ".slangh",
            ".slang-module"
    );

    Path getSlangTextMateBundlePath()
    {
        return slanglsp.SlangUtils.getPluginDir().resolve("slang-vscode-extension");
    }

    static boolean IS_FIRST_INIT = true;

    void loadTextMate(Project project)
    {
        try
        {
            TextMateUserBundlesSettings.getInstance().addBundle(getSlangTextMateBundlePath().toAbsolutePath().toString(), "slang-vscode-extension");
        } catch (Exception e)
        {
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                    "Slang LSP",
                    "The Slang-TextMate-json file is not embedded into the lsp plugin",
                    NotificationType.ERROR
            ).notify(project);
        }
        TextMateService.getInstance().reloadEnabledBundles();
    }

    void updateExtensionVersionCache(Project project)
    {
        // set value of extension version cache
        File versionCacheFile = SlangUtils.getVersionCacheFile();
        try
        {
            SlangVersion cachedVersion = SlangUtils.getVersion();
            SlangVersion.writeSlangVersionFile(versionCacheFile, cachedVersion.getMajor(), cachedVersion.getMinor(), cachedVersion.getPatch());
        } catch (Exception e)
        {
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                    "Slang LSP",
                    "Failed to create to versionCache.txt. requires ability to create+read+write files",
                    NotificationType.ERROR
            ).notify(project);
        }
    }

    boolean checkIfVSCodeExtensionRequiresExtraction(Project project)
    {
        // If cache is missing, return true
        File versionCacheFile = SlangUtils.getVersionCacheFile();

        if (!versionCacheFile.exists())
            return true;

        // If cache version != current version, return true
        try
        {
            SlangVersion cachedVersion = new SlangVersion(versionCacheFile.toURI().toURL().openStream());
            if (!cachedVersion.equals(SlangUtils.getVersion()))
                return true;
        } catch (Exception e)
        {
            updateExtensionVersionCache(project);
            return true;
        }

        return false;
    }

    void failedToMakeFolder(Project project)
    {
        NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                "Slang LSP",
                "Failed to create folder",
                NotificationType.ERROR
        ).notify(project);
    }

    private void extractZip(File zipFile, Path dstDir, Project project)
    {
        File dir = dstDir.toFile();
        // create output directory if it doesn't exist
        if(!dir.exists())
        {
            if(!dir.mkdirs())
                failedToMakeFolder(project);
        }

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile))
        {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                String fileName = entry.getName().replace("\\", "/");
                Path newFilePath = dstDir.resolve(fileName);
                File newFile = newFilePath.toFile();

                if (entry.isDirectory())
                {
                    // Create directory
                    if (!newFile.mkdirs() && !newFile.exists())
                        failedToMakeFolder(project);
                }
                else
                {
                    // Ensure parent directories exist
                    File parentDir = newFile.getParentFile();
                    if (parentDir != null && !parentDir.exists())
                    {
                        if (!parentDir.mkdirs())
                            failedToMakeFolder(project);
                    }

                    // Extract file
                    try (InputStream entryStream = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(newFile))
                    {
                        copyToFile(entryStream, fos);
                    }
                }
            }
        }
        catch (IOException e)
        {
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                    "Slang LSP",
                    "Invalid slang-vscode-extension zip file(s)",
                    NotificationType.ERROR
            ).notify(project);
            e.printStackTrace();
        }
    }


    void extractSlangVSCodeExtension(Project project)
    {
        boolean requiresExtraction = checkIfVSCodeExtensionRequiresExtraction(project);
        if(!requiresExtraction)
            return;

        updateExtensionVersionCache(project);

        // Create temporary file for the zip resource
        File tempZipFile = null;
        try
        {
            tempZipFile = File.createTempFile("slang_vscode_extension", ".zip");
            tempZipFile.deleteOnExit();

            // Copy resource to temporary file
            try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream("slang-vscode-extension.zip"))
            {
                if (resourceStream == null)
                {
                    NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                            "Slang LSP",
                            "Missing slang-vscode-extension.zip resource, build.gradle.kts task is not working",
                            NotificationType.ERROR
                    ).notify(project);
                    return;
                }

                try (FileOutputStream tempOut = new FileOutputStream(tempZipFile))
                {
                    copyToFile(resourceStream, tempOut);
                }
            }

            extractZip(tempZipFile, slanglsp.SlangUtils.getPluginDir(), project);
        }
        catch (Exception e)
        {
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                    "Slang LSP",
                    "Missing slang-vscode-extension.zip resource, build.gradle.kts task is not working",
                    NotificationType.ERROR
            ).notify(project);
        }
        finally
        {
            // Clean up temporary file
            if (tempZipFile != null && tempZipFile.exists())
            {
                boolean success = tempZipFile.delete();
            }
        }
    }

    private static void copyToFile(InputStream inputStream, FileOutputStream outputStream) throws IOException {
        var buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1)
        {
            outputStream.write(buffer, 0, bytesRead);
        }
    }


    void tryRunInitLogic(Project project)
    {
        if(IS_FIRST_INIT)
        {
            IS_FIRST_INIT = false;
            extractSlangVSCodeExtension(project);
            loadTextMate(project);
        }
    }


    @NotNull
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project)
    {
        tryRunInitLogic(project);
        return new SlangLanguageServer(project);
    }

    @NotNull
    public LanguageClientImpl createLanguageClient(@NotNull Project project)
    {
        tryRunInitLogic(project);
        SlangLanguageClient client = new SlangLanguageClient(project);
        Disposer.register(project.getService(SlangProjectDisposableService.class), client);

        project.getMessageBus().connect(client).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void before(@NotNull List<? extends VFileEvent> events) {
                boolean deletesSlangFiles = events.stream()
                        .anyMatch(event -> event instanceof VFileDeleteEvent
                                && containsSlangFile(((VFileDeleteEvent) event).getFile()));

                if (deletesSlangFiles) {
                    client.triggerChangeConfiguration();
                }
            }

            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                boolean hasSlangChanges = events.stream()
                        .anyMatch(event -> {
                            VirtualFile file = event.getFile();
                            if (file != null && containsSlangFile(file)) {
                                return true;
                            }

                            return isSlangFilePath(event.getPath());
                        });

                if (hasSlangChanges)
                {
                    client.triggerChangeConfiguration();
                }
            }
        });
        return client;
    }

    private static boolean containsSlangFile(@NotNull VirtualFile file)
    {
        return !VfsUtilCore.processFilesRecursively(file, virtualFile ->
            virtualFile.isDirectory() || !isSlangFilePath(virtualFile.getName())
        );
    }

    private static boolean isSlangFilePath(@NotNull String path)
    {
        return SLANG_FILE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }
};

class SlangLanguageServer extends ProcessStreamConnectionProvider
{
    Project project;
    SlangLanguageServer(Project project)
    {
        this.project = project;

        // First try to get EXE from the project settings
        var exePath = findExecutableUsingExplicitSlangdLocation();
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
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                    "Slang LSP",
                    "`slangd`/`slangd.exe` was not found in the `PATH` environment variable. It is preferable to add (once the latest vulkan SDK is installed) `$VK_SDK_PATH/bin` to your `PATH` environment variable (on linux the paths *may* differ slightly) to use `slangd` bundled with the Vulkan SDK. After these steps, restart this IDE.",
                    NotificationType.ERROR
            ).notify(project);
            LanguageServerManager.getInstance(project).stop("slangLanguageServer");
        }
    }

    static String getLspExeName()
    {
        if (SystemInfo.isWindows)
            return "slangd.exe";
        return "slangd";
    }
    static class FindLspExeFilter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            return dir.canExecute() && name.contentEquals(getLspExeName());
        }
    }

    private Optional<String> findExecutableUsingExplicitSlangdLocation()
    {
        var state = SlangPersistentStateConfig.getInstance(project);
        if (state != null && !state.getExplicitSlangdLocation().isEmpty())
        {
            var dirFiles = Paths.get(state.getExplicitSlangdLocation()).toFile().listFiles(new FindLspExeFilter());
            if(dirFiles == null)
                return Optional.empty();
            for(var i : dirFiles)
                return Optional.of(i.getAbsolutePath());
        }

        return Optional.empty();
    }

    private Optional<String> findExecutableInPATH()
    {
        var path = EnvironmentUtil.getValue("PATH");
        if (path != null && !path.isEmpty()) {
            String[] paths = path.split(File.pathSeparator);
            for (var pathString : paths) {
                var dirFiles = Paths.get(pathString).toFile().listFiles(new FindLspExeFilter());
                if (dirFiles == null)
                    continue;
                for (var i : dirFiles)
                    return Optional.of(i.getAbsolutePath());
            }
        }
        return Optional.empty();
    }
}

class SlangLanguageClient extends LanguageClientImpl implements Disposable
{
    static LinkedBlockingDeque<SlangLanguageClient> maybeAliveClients = new LinkedBlockingDeque<>();

    Project project;
    SlangLanguageClient(Project project)
    {
        super(project);
        this.project = project;
        maybeAliveClients.add(this);
    }

    public Object createSettings()
    {
        var state = SlangPersistentStateConfig.getInstance(project).getState();
        return state.createJSONFromObject(project);
    }

    public void triggerChangeConfiguration()
    {
        super.triggerChangeConfiguration();
    }

    private void triggerChangeConfigurationWhenProjectIsReady()
    {
        StartupManager.getInstance(project).runAfterOpened(() ->
                DumbService.getInstance(project).runWhenSmart(() ->
                        ApplicationManager.getApplication().invokeLater(
                                this::triggerChangeConfiguration,
                                ModalityState.nonModal(),
                                project.getDisposed()
                        )
                )
        );
    }

    public void handleServerStatusChanged(@NotNull ServerStatus serverStatus)
    {
        if (serverStatus == ServerStatus.started)
        {
            triggerChangeConfigurationWhenProjectIsReady();
        }
        if(serverStatus == ServerStatus.stopped)
        {
            Disposer.dispose(this);
        }
    }

    @Override
    public void dispose() {
        maybeAliveClients.remove(this);
    }
}
