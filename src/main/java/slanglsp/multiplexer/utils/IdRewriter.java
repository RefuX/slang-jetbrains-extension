package slanglsp.multiplexer.utils;

import org.jetbrains.annotations.NotNull;
import slanglsp.multiplexer.routing.BackendRequestKey;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Multiple Slangd processes will generate clashing ids
 */
public class IdRewriter {
    // BackendRequestKey to LspId
    private final Map<BackendRequestKey, Integer> idMap = new HashMap<>();
    private int nextLspId = 0;

    public @NotNull String fromSlangd(@NotNull BackendRequestKey backendRequestKey) {
        Integer keyInt = idMap.get(backendRequestKey);
        if (keyInt == null) {
            keyInt = nextLspId++;
            idMap.put(backendRequestKey, keyInt);
        }

        return backendRequestKey.process().moduleName() + ":" + keyInt;
    }

    public @Nullable BackendRequestKey fromLsp(@NotNull Object lspId) {
        String lspIdString = lspId.toString();
        int separatorIndex = lspIdString.lastIndexOf(':');
        if (separatorIndex < 0 || separatorIndex == lspIdString.length() - 1) {
            return null;
        }

        String moduleName = lspIdString.substring(0, separatorIndex);
        int keyInt;
        try {
            keyInt = Integer.parseInt(lspIdString.substring(separatorIndex + 1));
        } catch (NumberFormatException e) {
            return null;
        }

        for (Map.Entry<BackendRequestKey, Integer> entry : idMap.entrySet()) {
            BackendRequestKey backendRequestKey = entry.getKey();
            if (entry.getValue() == keyInt && backendRequestKey.process().moduleName().equals(moduleName)) {
                return backendRequestKey;
            }
        }

        return null;
    }

    public static boolean isRewrittenId(@Nullable Object id) {
        if (id == null) {
            return false;
        }

        return id.toString().contains(":");
    }
}
