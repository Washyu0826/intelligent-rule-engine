package com.ruleengine.rules.exception;

/**
 * 規則服務的基礎例外。
 * 所有業務相關例外都繼承此類別。
 */
public class RuleException extends RuntimeException {

    public RuleException(String message) {
        super(message);
    }

    public RuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
