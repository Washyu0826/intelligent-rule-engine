package com.ruleengine.rules.exception;

/**
 * LLM 服務不可用時拋出。
 * 例如 API key 未配置、LLM 服務逾時、回傳格式錯誤等。
 */
public class LlmUnavailableException extends RuleException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
