package io.github.xiaocan.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 颸王餐领取累计抽奖阶梯奖结果（独立按钮触发）。
 * 调 SilkwormLotteryMobile.ReceiveExtraLottery step=1/2。
 * 详见 .trellis/tasks/07-21-lottery-pace-and-separate/。
 */
@Data
public class LotteryStepPrizeResultVO {
    /** 登录态别名 */
    private String authName;
    /** 阶梯奖明细（step1/step2） */
    private List<StepPrizeItem> items;
    /** 整体失败信息 */
    private String error;

    @Data
    public static class StepPrizeItem {
        /** 阶梯 1=first, 2=second */
        private Integer step;
        /** 是否成功领取 */
        private Boolean ok;
        /** SKIPPED/失败原因（成功留空） */
        private String msg;
    }
}
