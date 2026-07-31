package io.github.xiaocan.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 霸王餐开红包（执行抽奖）结果。
 * <p>
 * 用刷任务攒到的剩余次数（LotteryInfo.day_num）循环调 SilkwormLotteryMobile.Lottery，把次数抽完。
 * 注意：day_num 才是剩余可抽次数；GetLotteryProgress.lottery_count 是阶梯进度，勿混用。
 */
@Data
public class LotteryDrawResultVO {
    /**
     * 登录态别名
     */
    private String authName;
    /**
     * 开前剩余抽奖次数（LotteryInfo.lottery_info.day_num）
     */
    private Integer beforeCount;
    /**
     * 开后剩余抽奖次数（day_num）
     */
    private Integer afterCount;
    /**
     * 每次抽奖明细（成功记录 prize，失败记录 msg）
     */
    private List<DrawItem> prizes;
    /**
     * 整体失败信息（如中途某次抽奖失败/异常）
     */
    private String error;

    @Data
    public static class DrawItem {
        /**
         * 奖品名称（如 滴滴5折打车/单号修改券/小蚕红包）
         */
        private String name;
        /**
         * 奖品图标 URL
         */
        private String icon;
        /**
         * 奖品一级类型（first_type）
         */
        private Integer firstType;
        /**
         * 奖品二级类型（second_type）
         */
        private Integer secondType;
        /**
         * 卡券 id（first_type=1 类奖品有值）
         */
        private Integer cardId;
        /**
         * 本次抽奖是否成功（= status.code == 0）
         */
        private Boolean ok;
        /**
         * 失败原因（成功留空）
         */
        private String msg;
    }
}
