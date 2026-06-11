package slanglsp.utils;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;
import slanglsp.SlangPersistentStateConfig;
import slanglsp.SlangVersion;

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

    public static Optional<String> findExecutableUsingExplicitSlangdLocation(Project project)
    {
        var state = SlangPersistentStateConfig.getInstance(project);
        if (state != null && !state.getExplicitSlangdLocation().isEmpty())
        {
            var dirFiles = Paths.get(state.getExplicitSlangdLocation()).toFile().listFiles(new FindLspExeFilter());
            if (dirFiles == null)
                return Optional.empty();
            for (var f : dirFiles)
                return Optional.of(f.getAbsolutePath());
        }
        return Optional.empty();
    }

    public static Optional<String> findExecutableInPATH()
    {
        var path = EnvironmentUtil.getValue("PATH");
        if (path != null && !path.isEmpty()) {
            for (var pathString : path.split(File.pathSeparator)) {
                var dirFiles = Paths.get(pathString).toFile().listFiles(new FindLspExeFilter());
                if (dirFiles == null) continue;
                for (var f : dirFiles)
                    return Optional.of(f.getAbsolutePath());
            }
        }
        return Optional.empty();
    }

    private static String getLspExeName()
    {
        return SystemInfo.isWindows ? "slangd.exe" : "slangd";
    }

    private static class FindLspExeFilter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            return dir.canExecute() && name.contentEquals(getLspExeName());
        }
    }

    static boolean isSlangFile(String path) {
        return SLANG_FILE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    public static boolean isSlangFile(VirtualFile file) {
        return isSlangFile(file.getName());
    }
}
