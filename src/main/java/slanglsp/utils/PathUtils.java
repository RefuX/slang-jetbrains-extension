package slanglsp.utils;

import com.intellij.openapi.vfs.VirtualFile;

import java.net.URI;
import java.nio.file.Paths;

public class PathUtils {
    private PathUtils() {
    }

    public static String normalizedPathFromUri(String uri) {
        return normalizePath(Paths.get(URI.create(uri)).toString());
    }

    public static String normalizedVirtualFilePath(VirtualFile file) {
        return normalizePath(file.getPath());
    }

    static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    public static boolean isSameOrUnder(String filePath, String rootPath) {
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + "/");
    }
}