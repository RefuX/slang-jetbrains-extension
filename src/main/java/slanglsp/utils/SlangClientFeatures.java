package slanglsp.utils;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature;

/**
 * Builds {@code LSPClientFeatures} with pull diagnostics ({@code textDocument/diagnostic})
 * disabled for {@code slangd} connections.
 * <p>
 * {@code slangd}'s pull-diagnostics responses are a list of
 * {@code Either<FullDocumentDiagnosticReport, UnchangedDocumentDiagnosticReport>}
 * (one entry per document, for a workspace pull). The two report shapes share enough
 * optional fields that lsp4j's gson-based {@code EitherTypeAdapter} sometimes can't
 * structurally tell them apart, throwing {@code JsonParseException: Ambiguous Either
 * type: token BEGIN_OBJECT matches both alternatives} — a known lsp4j limitation, not
 * something fixable from a client. lsp4ij decides whether to pull diagnostics purely
 * from {@code LSPDiagnosticFeature.isDiagnosticSupported(...)}
 * ({@code DocumentContentSynchronizer.isPullDiagnosticsSupported()} calls it directly),
 * so disabling it here stops lsp4ij from ever sending the pull request that triggers
 * this. It does not affect diagnostics delivered the normal way, via
 * {@code textDocument/publishDiagnostics} push notifications, which {@code slangd}
 * already sends and which go through a completely different, unaffected code path.
 */
public final class SlangClientFeatures {
    private SlangClientFeatures() {
    }

    public static LSPClientFeatures withoutPullDiagnostics() {
        return new LSPClientFeatures().setDiagnosticFeature(new LSPDiagnosticFeature() {
            @Override
            public boolean isDiagnosticSupported(VirtualFile file) {
                return false;
            }

            @Override
            public boolean isDiagnosticSupported(PsiFile file) {
                return false;
            }
        });
    }
}
