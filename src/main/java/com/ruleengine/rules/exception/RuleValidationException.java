package com.ruleengine.rules.exception;

/**
 * 規則驗證過程本身發生錯誤時拋出（非業務驗證失敗）。
 * 例如 JSON 解析失敗、不支援的規則型態等。
 */
public class RuleValidationException extends RuleException {

    public RuleValidationException(String message) {
        super(message);
    }

    public RuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
