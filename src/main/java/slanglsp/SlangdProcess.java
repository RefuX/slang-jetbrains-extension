package slanglsp;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;

import javax.annotation.Nonnull;

record SlangdProcess(@Nonnull Module module, @Nonnull VirtualFile moduleRoot, @Nonnull Process process) {

    boolean isAlive() {
        return process.isAlive();
    }

    void destroy() {
        process.destroyForcibly();
    }
}
