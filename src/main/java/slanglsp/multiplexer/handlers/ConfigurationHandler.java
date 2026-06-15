package slanglsp.multiplexer.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import slanglsp.SlangPersistentStateConfig;
import slanglsp.multiplexer.routing.MessageContext;
import slanglsp.multiplexer.routing.RoutingHandler;
import slanglsp.multiplexer.routing.RoutingServices;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static slanglsp.multiplexer.utils.JsonRpc.notification;
import static slanglsp.multiplexer.utils.JsonRpc.successResponse;
import static slanglsp.utils.JsonUtils.GSON;
import static slanglsp.utils.JsonUtils.extractId;
import static slanglsp.utils.JsonUtils.toBytes;
import static slanglsp.utils.JsonUtils.toJsonElement;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_DID_CHANGE_CONFIGURATION;
import static slanglsp.multiplexer.utils.LspUtils.METHOD_WORKSPACE_CONFIGURATION;

/**
 * Handles configuration-related Language Server Protocol messages between the IDE and slangd processes.
 * <p>
 * This handler forwards IDE configuration change notifications to all active slangd processes and responds
 * to {@code workspace/configuration} requests from slangd using the current project-level persistent settings.
 */
public final class ConfigurationHandler implements RoutingHandler {
    private static final Logger LOG = Logger.getInstance(ConfigurationHandler.class);

    private final Project project;

    public ConfigurationHandler(Project project) {
        this.project = project;
    }

    @Override
    public boolean fromLsp(MessageContext context, RoutingServices services) {
        if (!METHOD_DID_CHANGE_CONFIGURATION.equals(context.method())) {
            return false;
        }

        // TODO: Only send if settings have changed?
        Map<String, Object> settings = SlangPersistentStateConfig.getInstance(project).getState().toSettings();

        DidChangeConfigurationParams changeParams = new DidChangeConfigurationParams(settings);
        JsonObject params = GSON.toJsonTree(changeParams).getAsJsonObject();
        byte[] body = toBytes(notification(METHOD_DID_CHANGE_CONFIGURATION, params));

        services.broadcastToSlangd(body);

        return true;
    }

    @Override
    public boolean fromSlangd(MessageContext context, RoutingServices services) throws IOException {
        if (!METHOD_WORKSPACE_CONFIGURATION.equals(context.method())) {
            return false;
        }

        JsonObject response = buildConfigResponse(context.json());
        services.sendToSlangd(context.process(), toBytes(response));

        return true;
    }

    /**
     * We already know the configuration, and the lsp is already configured, so we respond directly to slangd.
     */
    private JsonObject buildConfigResponse(JsonObject requestJson) {
        Object id = extractId(requestJson);
        JsonObject rawParams = requestJson.has("params") && requestJson.get("params").isJsonObject()
                ? requestJson.getAsJsonObject("params")
                : new JsonObject();

        ConfigurationParams params = GSON.fromJson(rawParams, ConfigurationParams.class);

        Map<String, Object> settingsMap = SlangPersistentStateConfig.getInstance(project).getState().toSettings();

        JsonArray result = new JsonArray();
        List<ConfigurationItem> items = params != null && params.getItems() != null
                ? params.getItems()
                : List.of();

        for (ConfigurationItem item : items) {
            String section = item.getSection();
            result.add(toJsonElement(section != null ? settingsMap.get(section) : null));
        }

        return successResponse(id, result);
    }
}
