package com.ruleengine.rules.registry;

import com.ruleengine.rules.domain.RuleType;
import com.ruleengine.rules.service.generator.RuleGenerator;
import com.ruleengine.rules.service.validator.RuleValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 規則型態註冊表（計畫書 §6.2）
 *
 * 核心設計原則：新增規則型態時僅需：
 * 1. 新增 payload JSON Schema
 * 2. 新增對應的 Generator handler
 * 3. 新增對應的 Validator handler
 * 4. 兩者都是 Spring Bean，會自動註冊到這裡
 *
 * 核心流程（Generate → Validate → Response）無需修改。
 */
@Component
@Slf4j
public class RuleTypeRegistry {

    private final Map<RuleType, RuleGenerator> generators = new EnumMap<>(RuleType.class);
    private final Map<RuleType, RuleValidator> validators = new EnumMap<>(RuleType.class);

    private final List<RuleGenerator> generatorBeans;
    private final List<RuleValidator> validatorBeans;

    public RuleTypeRegistry(List<RuleGenerator> generatorBeans, List<RuleValidator> validatorBeans) {
        this.generatorBeans = generatorBeans;
        this.validatorBeans = validatorBeans;
    }

    @PostConstruct
    public void init() {
        for (RuleGenerator gen : generatorBeans) {
            generators.put(gen.supportedType(), gen);
            log.info("已註冊 Generator: {}", gen.supportedType().getCode());
        }
        for (RuleValidator val : validatorBeans) {
            validators.put(val.supportedType(), val);
            log.info("已註冊 Validator: {}", val.supportedType().getCode());
        }
        log.info("RuleTypeRegistry 初始化完成，已註冊 {} 種 Generator、{} 種 Validator",
                generators.size(), validators.size());
    }

    /** 取得指定型態的 Generator */
    public Optional<RuleGenerator> getGenerator(RuleType type) {
        return Optional.ofNullable(generators.get(type));
    }

    /** 取得指定型態的 Validator */
    public Optional<RuleValidator> getValidator(RuleType type) {
        return Optional.ofNullable(validators.get(type));
    }

    /** 列出所有已註冊的型態 */
    public Set<RuleType> getRegisteredTypes() {
        // 兩邊都有註冊的才算完整
        return generators.keySet().stream()
                .filter(validators::containsKey)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RuleType.class)));
    }

    /** 檢查型態是否已註冊 */
    public boolean isSupported(RuleType type) {
        return generators.containsKey(type) && validators.containsKey(type);
    }

    /** 取得型態描述（用於 MCP Resource） */
    public List<Map<String, String>> getTypeDescriptions() {
        return getRegisteredTypes().stream()
                .map(t -> Map.of(
                        "code", t.getCode(),
                        "label", t.getLabel(),
                        "description", t.getDescription()
                ))
                .collect(Collectors.toList());
    }
}
