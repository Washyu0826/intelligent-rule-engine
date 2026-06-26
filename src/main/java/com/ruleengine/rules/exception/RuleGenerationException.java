package com.ruleengine.rules.exception;

/**
 * 規則生成失敗時拋出。
 * 對應 /tools/generate 端點的錯誤情境。
 */
public class RuleGenerationException extends RuleException {

    public RuleGenerationException(String message) {
        super(message);
    }

    public RuleGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
