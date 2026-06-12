package slanglsp;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.configuration.TextMateUserBundlesSettings;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;

import org.jspecify.annotations.NonNull;
import slanglsp.multiplexer.SlangMultiplexLanguageClient;
import slanglsp.multiplexer.SlangMultiplexLanguageServer;
import slanglsp.multiplexer.SlangProjectDisposableService;
import slanglsp.utils.NotificationUtil;

import static slanglsp.utils.NotificationUtil.notifyUser;
import static slanglsp.utils.NotificationUtil.runOrNotify;
import static slanglsp.utils.SlangUtils.*;

public class SlangLanguageServerFactory implements LanguageServerFactory
{
    private static final Logger LOG = Logger.getInstance(SlangLanguageServerFactory.class);
    private static boolean IS_FIRST_INIT = true;
    private static final Map<Project, SlangMultiplexLanguageServer> SLANG_MULTIPLEX_SERVERS = new ConcurrentHashMap<>();
    private static final String LANGUAGE_SERVER_ID = "slanglsp.SlangLanguageServer";

    @NotNull
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project)
    {
        tryRunInitLogic(project);

        Optional<String> exePath = findExecutableUsingExplicitSlangdLocation(project);
        if (exePath.isEmpty())
            exePath = findExecutableInPATH();

        if (exePath.isEmpty())
        {
            notifyUser(
                    project,
                    "`slangd`/`slangd.exe` was not found in the `PATH` environment variable. It is preferable to add (once the latest vulkan SDK is installed) `$VK_SDK_PATH/bin` to your `PATH` environment variable (on linux the paths *may* differ slightly) to use `slangd` bundled with the Vulkan SDK. After these steps, restart this IDE.",
                    NotificationType.ERROR);

            stopLanguageServer(project);

            return new SlangNoOpProvider();
        }

        SlangPersistentStateConfig.State state = SlangPersistentStateConfig.getInstance(project).getState();
        boolean strictPerModuleIsolation = state.enableStrictPerModuleIsolation;
        if (strictPerModuleIsolation) {
            SlangMultiplexLanguageServer slangMultiplexLanguageServer = new SlangMultiplexLanguageServer(project, exePath.get());
            SLANG_MULTIPLEX_SERVERS.put(project, slangMultiplexLanguageServer);

            return slangMultiplexLanguageServer;
        }

        return new SlangLanguageServer(project);
    }

    @NotNull
    public LanguageClientImpl createLanguageClient(@NotNull Project project)
    {
        tryRunInitLogic(project);

        SlangMultiplexLanguageServer multiplexProvider = SLANG_MULTIPLEX_SERVERS.get(project);
        if (multiplexProvider == null) {
            return new SlangLanguageClient(project);
        }

        SlangMultiplexLanguageClient client = new SlangMultiplexLanguageClient(project, multiplexProvider);
        Disposer.register(project.getService(SlangProjectDisposableService.class), client);

        return client;
    }

    public static void stopLanguageServer(@NonNull Project project) {
        LanguageServerManager.getInstance(project).stop(LANGUAGE_SERVER_ID);
    }

    public static void restartLanguageServer(@NonNull Project project) {
        LanguageServerManager.getInstance(project).start(
                SlangLanguageServerFactory.LANGUAGE_SERVER_ID,
                new LanguageServerManager.StartOptions().setForceRestart(true));
    }

    private Path getSlangTextMateBundlePath()
    {
        return getPluginDir().resolve("slang-vscode-extension");
    }

    private void loadTextMate(Project project)
    {
        runOrNotify(
                project,
                "The Slang-TextMate-json file is not embedded into the lsp plugin",
                () -> TextMateUserBundlesSettings
                        .getInstance()
                        .addBundle(
                                getSlangTextMateBundlePath().toAbsolutePath().toString(),
                                "slang-vscode-extension"
                        )
        );

        TextMateService.getInstance().reloadEnabledBundles();
    }

    private void updateExtensionVersionCache(Project project)
    {
        // set value of extension version cache
        File versionCacheFile = getVersionCacheFile();

        runOrNotify(
                project,
                "Failed to create to versionCache.txt. requires ability to create+read+write files",
                () -> {
                    SlangVersion cachedVersion = getVersion();
                    SlangVersion.writeSlangVersionFile(
                            versionCacheFile,
                            cachedVersion.getMajor(),
                            cachedVersion.getMinor(),
                            cachedVersion.getPatch()
                    );
                }
        );
    }

    private boolean checkIfVSCodeExtensionRequiresExtraction(Project project)
    {
        // If cache is missing, return true
        File versionCacheFile = getVersionCacheFile();

        if (!versionCacheFile.exists())
            return true;

        // If cache version != current version, return true
        return runOrNotify(
                project,
                "Failed to read extension version cache file",
                () -> {
                    SlangVersion cachedVersion = new SlangVersion(versionCacheFile.toURI().toURL().openStream());
                    return !cachedVersion.equals(getVersion());
                }
        ).orElseGet(() -> {
            updateExtensionVersionCache(project);
            return true;
        });
    }

    private void failedToMakeFolder(Project project)
    {
        notifyUser(
                project,
                "Failed to create folder for Slang LSP extension",
                NotificationType.ERROR
        );
    }

    private void extractZip(File zipFile, Path dstDir, Project project)
    {
        File dir = dstDir.toFile();
        // create output directory if it doesn't exist
        if (!dir.exists())
        {
            if (!dir.mkdirs())
                failedToMakeFolder(project);
        }

        runOrNotify(
                project,
                "Invalid slang-vscode-extension zip file(s)",
                () -> {
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
                }
        );
    }

    private void extractSlangVSCodeExtension(Project project)
    {
        boolean requiresExtraction = checkIfVSCodeExtensionRequiresExtraction(project);
        if (!requiresExtraction)
            return;

        updateExtensionVersionCache(project);

        final File[] tempZipFile = new File[1];
        try
        {
            NotificationUtil.runOrNotify(
                    project,
                    "Missing slang-vscode-extension.zip resource, build.gradle.kts task is not working",
                    () -> {
                        tempZipFile[0] = File.createTempFile("slang_vscode_extension", ".zip");
                        tempZipFile[0].deleteOnExit();

                        // Copy resource to temporary file
                        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream("slang-vscode-extension.zip"))
                        {
                            if (resourceStream == null)
                            {
                                LOG.error("Failed to find slang-vscode-extension.zip resource");
                                throw new IOException("Missing slang-vscode-extension.zip resource");
                            }

                            try (FileOutputStream tempOut = new FileOutputStream(tempZipFile[0]))
                            {
                                copyToFile(resourceStream, tempOut);
                            }
                        }

                        extractZip(tempZipFile[0], getPluginDir(), project);
                    }
            );
        }
        finally
        {
            // Clean up temporary file
            if (tempZipFile[0] != null && tempZipFile[0].exists())
                tempZipFile[0].delete();
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

    private synchronized void tryRunInitLogic(Project project)
    {
        if(IS_FIRST_INIT)
        {
            IS_FIRST_INIT = false;
            extractSlangVSCodeExtension(project);
            loadTextMate(project);
        }
    }
}
