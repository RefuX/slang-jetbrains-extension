package slanglsp;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.configuration.TextMateUserBundlesSettings;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;

import slanglsp.modules.SlangModuleServerCoordinator;
import slanglsp.modules.SlangProjectDisposableService;
import slanglsp.utils.NotificationUtil;
import slanglsp.utils.SlangClientFeatures;

import static slanglsp.utils.NotificationUtil.notifyUser;
import static slanglsp.utils.NotificationUtil.runOrNotify;
import static slanglsp.utils.SlangUtils.*;

public class SlangLanguageServerFactory implements LanguageServerFactory
{
    private static final Logger LOG = Logger.getInstance(SlangLanguageServerFactory.class);
    private static boolean IS_FIRST_INIT = true;

    // Per-project coordinator that registers one real lsp4ij server definition per
    // module when strict per-module isolation is enabled. Tracked here (rather than only
    // via Disposer) so toggling the setting back off can tear it down deterministically.
    private static final Map<Project, SlangModuleServerCoordinator> MODULE_SERVER_COORDINATORS = new ConcurrentHashMap<>();

    public static final String LANGUAGE_SERVER_ID = "slanglsp.SlangLanguageServer";

    @NotNull
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project)
    {
        tryRunInitLogic(project);

        Optional<String> exePath = resolveSlangdExePath(project);
        if (exePath.isEmpty())
        {
            notifyMissingSlangd(project);

            stopLanguageServer(project);
            stopModuleServerCoordinator(project);

            return new SlangNoOpProvider();
        }

        SlangPersistentStateConfig.State state = SlangPersistentStateConfig.getInstance(project).getState();
        boolean strictPerModuleIsolation = state.enableStrictPerModuleIsolation;
        if (strictPerModuleIsolation) {
            // The real work happens out-of-band: one independent lsp4ij server
            // definition per module, each with its own `slangd` process, registered
            // with LanguageServersRegistry and scoped to that module's files. This
            // static "slanglsp.SlangLanguageServer" definition is disabled for the
            // duration so it doesn't also claim every *.slang file via the global
            // fileNamePatternMapping in plugin.xml; it returns a no-op connection.
            //
            // This branch is only a defensive fallback for races — the normal path
            // disables the static definition *before* lsp4ij ever decides to start it
            // (see syncWithStrictModeSetting), since by the time createConnectionProvider
            // is called lsp4ij has already committed to starting this wrapper. Returning
            // a no-op connection here doesn't undo that: lsp4ij still considers the
            // wrapper "started" against fake streams, and later stopping/restarting it
            // sends a graceful LSP shutdown request that can never get a response,
            // timing out (see SlangConfigurableGUI.apply()/the startup activity, which
            // call setStaticServerDefinitionEnabled(false) ahead of time to avoid this
            // ever being reached in practice).
            startModuleServerCoordinatorIfAbsent(project, exePath.get());
            setStaticServerDefinitionEnabled(project, false);

            return new SlangNoOpProvider();
        }

        stopModuleServerCoordinator(project);
        setStaticServerDefinitionEnabled(project, true);

        return new SlangLanguageServer(project);
    }

    /**
     * Brings the static "slanglsp.SlangLanguageServer" definition and the per-module
     * coordinator in line with the current {@code enableStrictPerModuleIsolation}
     * setting, proactively rather than reactively.
     * <p>
     * Must be called <em>before</em> anything could cause lsp4ij to lazily start the
     * static definition's wrapper (a file matching {@code *.slang} being opened) — once
     * that happens it's too late to cleanly back out: {@code createConnectionProvider}
     * would have already returned a connection (real or {@link SlangNoOpProvider}) that
     * lsp4ij considers "started," and disabling the definition afterward doesn't stop
     * that wrapper. If it's backed by {@link SlangNoOpProvider} (because strict mode was
     * already on), later stopping/restarting it sends a graceful LSP shutdown request
     * over fake streams that can never respond, timing out.
     * <p>
     * Called from {@link SlangStrictModeStartupActivity} on project open (covers
     * "strict mode already saved as on when the project opens") and from
     * {@code SlangConfigurableGUI.apply()} (covers toggling the setting at runtime).
     */
    public static void syncWithStrictModeSetting(@NotNull Project project)
    {
        SlangPersistentStateConfig.State state = SlangPersistentStateConfig.getInstance(project).getState();
        boolean strictPerModuleIsolation = state.enableStrictPerModuleIsolation;

        if (strictPerModuleIsolation)
        {
            // Disable first so the static definition can never again be the one lsp4ij
            // lazily starts for a matching file, then stop it — safe to call even if no
            // wrapper exists yet (no-op), and if one does exist it's necessarily backed
            // by a real connection from before this toggle (non-strict mode), which can
            // shut down gracefully without issue.
            setStaticServerDefinitionEnabled(project, false);
            stopLanguageServer(project);

            Optional<String> exePath = resolveSlangdExePath(project);
            if (exePath.isEmpty())
            {
                notifyMissingSlangd(project);
                return;
            }

            startModuleServerCoordinatorIfAbsent(project, exePath.get());
        }
        else
        {
            stopModuleServerCoordinator(project);
            setStaticServerDefinitionEnabled(project, true);
            restartLanguageServer(project);
        }
    }

    private static Optional<String> resolveSlangdExePath(@NotNull Project project)
    {
        Optional<String> exePath = findExecutableUsingExplicitSlangdLocation(project);
        if (exePath.isEmpty())
            exePath = findExecutableInPATH();
        return exePath;
    }

    private static void notifyMissingSlangd(@NotNull Project project)
    {
        notifyUser(
                project,
                "`slangd`/`slangd.exe` was not found in the `PATH` environment variable. It is preferable to add (once the latest vulkan SDK is installed) `$VK_SDK_PATH/bin` to your `PATH` environment variable (on linux the paths *may* differ slightly) to use `slangd` bundled with the Vulkan SDK. After these steps, restart this IDE.",
                NotificationType.ERROR);
    }

    private static void startModuleServerCoordinatorIfAbsent(@NotNull Project project, @NotNull String slangdExePath)
    {
        MODULE_SERVER_COORDINATORS.computeIfAbsent(project, p -> {
            SlangModuleServerCoordinator coordinator = new SlangModuleServerCoordinator(p, slangdExePath);
            Disposer.register(p.getService(SlangProjectDisposableService.class), coordinator);
            coordinator.start();
            return coordinator;
        });
    }

    @NotNull
    public LanguageClientImpl createLanguageClient(@NotNull Project project)
    {
        tryRunInitLogic(project);

        return new SlangLanguageClient(project);
    }

    @NotNull
    public LSPClientFeatures createClientFeatures()
    {
        return SlangClientFeatures.withoutPullDiagnostics();
    }

    private static void stopModuleServerCoordinator(@NotNull Project project) {
        SlangModuleServerCoordinator coordinator = MODULE_SERVER_COORDINATORS.remove(project);
        if (coordinator != null)
            Disposer.dispose(coordinator);
    }

    private static void setStaticServerDefinitionEnabled(@NotNull Project project, boolean enabled) {
        var definition = LanguageServersRegistry.getInstance().getServerDefinition(LANGUAGE_SERVER_ID);
        if (definition != null)
            definition.setEnabled(enabled, project);
    }

    public static void stopLanguageServer(@NotNull Project project) {
        LanguageServerManager.getInstance(project).stop(LANGUAGE_SERVER_ID);
    }

    public static void restartLanguageServer(@NotNull Project project) {
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
