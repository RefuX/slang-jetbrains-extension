package slanglsp.multiplexer.handlers.utils;

import slanglsp.multiplexer.routing.BackendRequestKey;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
