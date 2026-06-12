package slanglsp.multiplexer;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;

@Service(Service.Level.PROJECT)
public final class SlangProjectDisposableService implements Disposable {
    @Override
    public void dispose() {
    }
}
