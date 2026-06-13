package slanglsp.multiplexer;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public record ModuleInfo(
        @NotNull Module module,
        @NotNull VirtualFile moduleRoot
) {
}