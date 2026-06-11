package slanglsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;
import slanglsp.utils.SlangUtils;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.stream.Stream;

import static slanglsp.utils.SlangUtils.isSlangFile;

class SlangLanguageClient extends LanguageClientImpl implements Disposable {
    private static final Logger LOG = Logger.getInstance(SlangLanguageClient.class);

    private final Project project;
    private final SlangMultiplexProvider multiplexProvider;

    SlangLanguageClient(Project project, SlangMultiplexProvider multiplexProvider) {
        super(project);
        this.project = project;
        this.multiplexProvider = multiplexProvider;
        trackSlangFileChanges();
    }

    private static VirtualFile resolveVirtualFile(String uri) {
        if (uri == null || uri.isEmpty())
            return null;
        try {
            return LocalFileSystem.getInstance().findFileByIoFile(new File(URI.create(uri)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void trackSlangFileChanges() {
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void before(@NotNull List<? extends VFileEvent> events) {
                // Might only be an event for the top level dir, need to look inside
                List<VirtualFile> deletedSlangFiles = events.stream()
                        .filter(VFileDeleteEvent.class::isInstance)
                        .map(VFileDeleteEvent.class::cast)
                        .map(VFileDeleteEvent::getFile)
                        .flatMap(SlangLanguageClient::findSlangFiles)
                        .toList();

                if (!deletedSlangFiles.isEmpty())
                    multiplexProvider.slangFilesRemoved(deletedSlangFiles, getOpenSlangFiles());
            }

            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                // Might only be an event for the top level dir, need to look inside
                List<VirtualFile> newSlangFiles = events.stream()
                        .map(VFileEvent::getFile)
                        .filter(Objects::nonNull)
                        .flatMap(SlangLanguageClient::findSlangFiles)
                        .toList();

                if (!newSlangFiles.isEmpty())
                    multiplexProvider.slangFilesAdded(newSlangFiles, getOpenSlangFiles());
            }
        });
    }

    private List<VirtualFile> getOpenSlangFiles() {
        return Arrays.stream(FileEditorManager.getInstance(project).getOpenFiles())
                .filter(Objects::nonNull)
                .filter(VirtualFile::isValid)
                .filter(file -> !file.isDirectory())
                .filter(SlangUtils::isSlangFile)
                .toList();
    }

    private static Stream<VirtualFile> findSlangFiles(@NotNull VirtualFile file) {
        if (!file.isDirectory())
            return isSlangFile(file) ? Stream.of(file) : Stream.empty();

        return findSlangFilesRecursively(file).stream();
    }

    private static List<VirtualFile> findSlangFilesRecursively(@NotNull VirtualFile root)
    {
        List<VirtualFile> slangFiles = new ArrayList<>();

        if (!root.isValid())
            return slangFiles;

        VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
            public VirtualFileVisitor.@NotNull Result visitFileEx(@NotNull VirtualFile file) {
                if (file.isValid() && !file.isDirectory() && isSlangFile(file)) {
                    slangFiles.add(file);
                }

                return CONTINUE;
            }
        });

        return slangFiles;
    }

    @Override
    public void dispose() {
        // nothing to do
    }
}
