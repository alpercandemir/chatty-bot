package com.chatty.api;

public class Problem extends RuntimeException {

    public final int status;
    public final String code;

    public Problem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static Problem missing() {
        return new Problem(
                404,
                "RESOURCE_NOT_AVAILABLE",
                "This resource is not available. Check the reference or request support.");
    }
}
