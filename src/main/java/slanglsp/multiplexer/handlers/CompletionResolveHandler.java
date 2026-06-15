package slanglsp.multiplexer.handlers;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import slanglsp.multiplexer.SlangdProcess;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;
import java.util.Optional;

import static slanglsp.multiplexer.utils.LspUtils.METHOD_COMPLETION_ITEM_RESOLVE;
import static slanglsp.multiplexer.utils.ModuleUtils.findOwningModuleByContentRoot;

/**
 * Handles completion item resolve requests from the LSP.
 */
public class CompletionResolveHandler  implements RoutingHandler {
    private final Project project;

    public CompletionResolveHandler(Project project) {
        this.project = project;
    }

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) throws IOException {
        if (!METHOD_COMPLETION_ITEM_RESOLVE.equals(context.method())) {
            return false;
        }

        Editor editor = FileEditorManager.getInstance(context.project()).getSelectedTextEditor();
        if (editor != null) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());

            if (file != null) {
                Module module = findOwningModuleByContentRoot(file, project);

                Optional<SlangdProcess> process = services.processes().stream()
                        .filter(p -> p.module().equals(module))
                        .findFirst();

                if (process.isPresent()) {
                    services.sendToSlangd(process.get(), context.body());
                    return true;
                }
            }
        }

        return false;
    }
}