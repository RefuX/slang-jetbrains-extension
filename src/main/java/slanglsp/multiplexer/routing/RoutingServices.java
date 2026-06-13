package slanglsp.multiplexer.routing;

import slanglsp.multiplexer.SlangdProcess;

import java.io.IOException;
import java.util.List;

/**
 * Provides services for routing messages between the LSP and Slangd processes.
 */
public interface RoutingServices {
    List<SlangdProcess> processes();

    void sendToSlangd(SlangdProcess process, byte[] body) throws IOException;

    void sendToLsp(byte[] body);

    void broadcastToSlangd(byte[] body);

    SlangdProcess findProcessForUri(String uri);

    boolean isStopped();
}
