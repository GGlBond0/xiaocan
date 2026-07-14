package io.github.xiaocan.model.vo;

import lombok.Data;

/**
 * 商家黑名单配置返回
 */
@Data
public class MerchantBlacklistVO {

    /**
     * 是否启用黑名单过滤
     */
    private Boolean enabled;

    /**
     * 关键字规则文本
     */
    private String keywords;
}
