package com.agentic.platform.mcp;

import java.io.IOException;

public class McpException extends IOException {
    private final int code;
    public McpException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
}
