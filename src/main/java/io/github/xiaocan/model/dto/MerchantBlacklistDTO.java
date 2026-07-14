package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家黑名单配置入参
 */
@Data
public class MerchantBlacklistDTO {

    /**
     * 是否启用黑名单过滤
     */
    private Boolean enabled;

    /**
     * 关键字规则文本（一行一条；行内 & 表示 AND；多行为 OR；可空）
     */
    @Size(max = 4000, message = "关键字规则总长度不能超过 4000 字符")
    private String keywords;
}
