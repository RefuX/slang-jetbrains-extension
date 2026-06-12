package slanglsp.multiplexer.utils;

public class LspUtils {
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_INITIALIZED = "initialized";
    public static final String METHOD_SHUTDOWN = "shutdown";
    public static final String METHOD_EXIT = "exit";
    public static final String METHOD_WORKSPACE_CONFIGURATION = "workspace/configuration";
    public static final String METHOD_DID_CHANGE_CONFIGURATION = "workspace/didChangeConfiguration";
    public static final String METHOD_DID_CHANGE_WATCHED_FILES = "workspace/didChangeWatchedFiles";
    public static final String METHOD_DID_CHANGE_WORKSPACE_FOLDERS = "workspace/didChangeWorkspaceFolders";
    public static final String METHOD_TEXT_DOCUMENT_DID_CLOSE = "textDocument/didClose";
    public static final String METHOD_TEXT_DOCUMENT_DID_OPEN = "textDocument/didOpen";
    public static final String METHOD_TEXT_DOCUMENT_HOVER = "textDocument/hover";
    public static final String METHOD_SET_TRACE = "$/setTrace";
    public static final String METHOD_CANCEL_REQUEST = "$/cancelRequest";
    public static final String METHOD_PUBLISH_DIAGNOSTICS = "textDocument/publishDiagnostics";
}
