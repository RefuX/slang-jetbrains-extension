package slanglsp.multiplexer.handlers.utils;

import slanglsp.multiplexer.routing.BackendRequestKey;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks requests sent to backend language-server processes until their responses
 * are received.
 * <p>
 * The tracker records the method associated with each backend request key so routing
 * handlers can identify and complete pending requests when responses arrive. It also
 * keeps a set of backend response keys that should be suppressed, allowing handlers
 * to discard responses for internally generated or otherwise non-forwarded requests.
 */
public final class PendingRequestTracker {
    private final Map<BackendRequestKey, String> pendingBackendRequests = new ConcurrentHashMap<>();
    private final Set<BackendRequestKey> suppressedBackendResponseKeys = ConcurrentHashMap.newKeySet();

    public void track(BackendRequestKey key, String method) {
        pendingBackendRequests.put(key, method);
    }

    public String complete(BackendRequestKey key) {
        return pendingBackendRequests.remove(key);
    }

    public void suppress(BackendRequestKey key) {
        suppressedBackendResponseKeys.add(key);
    }

    public boolean consumeSuppressed(BackendRequestKey key) {
        return suppressedBackendResponseKeys.remove(key);
    }
}
