package io.github.xiaocan.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 监控自动抢单模式。
 * SINGLE：抢一个名额，组合内按账号优先级换号、组合间降级，成功即停。
 * ALL：每个勾选账号各自独立、不换号，在所有命中门店按平台优先级抢，各拿各的名额。
 */
@Getter
@AllArgsConstructor
public enum GrabModeEnums {
    SINGLE("抢一个名额"),
    ALL("每账号各抢一个"),
    ;

    private final String desc;

    /** 解析字符串为枚举，null/空/非法回退 SINGLE（存量兼容）。 */
    public static GrabModeEnums parse(String s) {
        if (s == null || s.isBlank()) return SINGLE;
        try {
            return GrabModeEnums.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SINGLE;
        }
    }
}
