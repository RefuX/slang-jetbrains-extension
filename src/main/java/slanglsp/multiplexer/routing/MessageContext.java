package slanglsp.multiplexer.routing;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import slanglsp.multiplexer.SlangdProcess;

/**
 * If process is null the MessageContext is from the Language Server.
 * If the process is not null the MessageContext is from a SlangdProcess.
 *
 * @param project
 * @param process
 * @param body
 * @param json
 * @param method
 */
public record MessageContext(Project project, SlangdProcess process, byte[] body, JsonObject json, String method) {
}
