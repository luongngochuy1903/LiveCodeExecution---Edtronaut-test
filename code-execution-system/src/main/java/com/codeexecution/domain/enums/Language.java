package com.codeexecution.domain.enums;

public enum Language {
    PYTHON("python3", ".py"),
    JAVASCRIPT("node", ".js");

    private final String runtime;
    private final String extension;

    Language(String runtime, String extension) {
        this.runtime = runtime;
        this.extension = extension;
    }

    public String getRuntime() { return runtime; }
    public String getExtension() { return extension; }
}
