package slanglsp.multiplexer.routing;

import com.google.gson.JsonObject;
import slanglsp.multiplexer.SlangdProcess;

public record MessageContext(SlangdProcess process, byte[] body, JsonObject json, String method) {
}
