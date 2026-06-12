package slanglsp.multiplexer;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;

import javax.annotation.Nonnull;

public record ModuleInfo(
        @Nonnull Module module,
        @Nonnull VirtualFile moduleRoot
) {
}