package slanglsp.utils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.vfs.VirtualFile;
import slanglsp.SlangVersion;

import static java.util.List.of;

public class SlangUtils
{
    private static final List<String> SLANG_FILE_EXTENSIONS = List.of(
            ".slang",
            ".slangh",
            ".slang-module"
    );

    public static Path getPluginDir()
    {
        String jarPath = PathManager.getJarPathForClass(SlangUtils.class);
        if(jarPath == null)
            throw new RuntimeException("Invalid 'getJarPathForClass'");
        return Paths.get(jarPath).getParent().toAbsolutePath();
    }
    static Path getVersionCacheLocation()
    {
        return SlangUtils.getPluginDir().resolve("versionCache.txt");
    }
    public static File getVersionCacheFile()
    {
        return getVersionCacheLocation().toFile();
    }

    public static SlangVersion getVersion()
    {
        var fileStream = SlangUtils.class.getClassLoader().getResourceAsStream("version.txt");
        if(fileStream == null)
        {
            System.out.println("Missing the resource version.txt");
            return new SlangVersion("0.0");
        }
        return new SlangVersion(fileStream);
    }

    static boolean isSlangFile(String path) {
        return SLANG_FILE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    public static boolean isSlangFile(VirtualFile file) {
        return isSlangFile(file.getName());
    }
}
