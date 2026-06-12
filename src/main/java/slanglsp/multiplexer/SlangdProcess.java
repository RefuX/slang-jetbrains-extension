package slanglsp.multiplexer;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;

import javax.annotation.Nonnull;

public record SlangdProcess(@Nonnull Module module, @Nonnull VirtualFile moduleRoot, @Nonnull Process process) {

    public boolean isAlive() {
        return process.isAlive();
    }

    public void destroy() {
        process.destroyForcibly();
    }

    public String moduleName() {
        return module.getName();
    }
}
