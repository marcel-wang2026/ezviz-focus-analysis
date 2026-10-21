package com.focus.ezviz.ezviz;

/** 萤石开放平台接口异常 */
public class EzvizApiException extends RuntimeException {

    private final String code;

    public EzvizApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EzvizApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = "exception";
    }

    public String getCode() {
        return code;
    }

    public boolean isTokenExpired() {
        return "10002".equals(code);
    }
}
