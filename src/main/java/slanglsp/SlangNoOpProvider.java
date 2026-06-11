package slanglsp;

import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;

import java.io.InputStream;
import java.io.OutputStream;

class SlangNoOpProvider implements StreamConnectionProvider {
    private final InputStream emptyIn = InputStream.nullInputStream();
    private final OutputStream emptyOut = OutputStream.nullOutputStream();

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public InputStream getInputStream() {
        return emptyIn;
    }

    @Override
    public OutputStream getOutputStream() {
        return emptyOut;
    }
}
