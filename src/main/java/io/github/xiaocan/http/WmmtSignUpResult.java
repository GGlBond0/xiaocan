package io.github.xiaocan.http;

import lombok.Data;

/**
 * 歪麦抢单（报名）结果。
 * <p>
 * 成功判据：新版 code∈{200,0,"0"} 且 buyOverbearId 非空；老版 code==1。
 * 报名费：payAmount>0 / occupyPayAmount>0 / secKillPayAmount>0 任一 → 需微信支付，自动抢主流程应终止并提示。
 */
@Data
public class WmmtSignUpResult {
    /** 上游业务 code（新版 200/0/"0" 成功；老版 1 成功） */
    private Integer code;
    /** 报名成功后返回的 buyOverbearId（orderId） */
    private String buyOverbearId;
    /** 是否抢单成功 */
    private Boolean success;
    /** 上游消息/失败原因 */
    private String message;
    /** 应付报名费（元）：>0 表示需支付 */
    private java.math.BigDecimal payAmount;
    /** 占用报名费（元）：>0 或 secKillPayAmount>0 → 需支付 */
    private java.math.BigDecimal occupyPayAmount;
    /** 秒杀报名费（元） */
    private java.math.BigDecimal secKillPayAmount;
    /** 是否需手动支付报名费（任一报名费>0） */
    private Boolean needPay;
}