package slanglsp.multiplexer;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static slanglsp.multiplexer.utils.ThreadUtils.startDaemonThread;

public record SlangdProcess(@NotNull Module module, @NotNull VirtualFile moduleRoot, @NotNull Process process) {
    private static final Logger LOG = Logger.getInstance(SlangdProcess.class);

    public SlangdProcess {
        pipeErrorsToLog(module.getName(), process);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void destroy() {
        process.destroyForcibly();
    }

    public String moduleName() {
        return module.getName();
    }

    private static void pipeErrorsToLog(@NotNull String moduleName, @NotNull Process process) {
        startDaemonThread("slang-stderr-" + moduleName, () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.warn("slangd stderr [" + moduleName + "]: " + line);
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    LOG.warn("Failed reading slangd stderr for module " + moduleName, e);
                }
            }
        });
    }
}
