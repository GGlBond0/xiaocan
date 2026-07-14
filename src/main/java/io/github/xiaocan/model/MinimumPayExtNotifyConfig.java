package io.github.xiaocan.model;

import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 最大金额差提醒
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class MinimumPayExtNotifyConfig extends AbstractExtNotifyConfig{

    /**
     * 最小实付，大于等于1
     * 最小实付=返现门槛-返现金额
     */
    @Min(value = 1)
    private BigDecimal minimumPay;

    /**
     * 是否仅命中 3km 内（距离 <= 3000 米）的门店，默认 false
     */
    private Boolean within3km = false;

    /**
     * 去重/过期分钟数，默认 60。
     * 含义：同店 N 分钟内不重复通知；超过 N 分钟的旧推送记录自动删除，
     * 通知记录页仅显示最近 N 分钟内的记录。
     */
    @Min(value = 1)
    private Integer dedupMinutes = 60;
}
