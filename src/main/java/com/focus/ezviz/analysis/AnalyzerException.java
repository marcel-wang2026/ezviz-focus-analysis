package com.focus.ezviz.analysis;

/** 算法执行异常 */
public class AnalyzerException extends RuntimeException {

    public AnalyzerException(String message) {
        super(message);
    }

    public AnalyzerException(String message, Throwable cause) {
        super(message, cause);
    }
}
