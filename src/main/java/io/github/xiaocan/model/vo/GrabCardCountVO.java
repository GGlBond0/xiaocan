package io.github.xiaocan.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 卡券数量汇总（按 cardId 聚合计数）。饭票 = cardId==1。
 */
@Data
public class GrabCardCountVO {
    /**
     * 饭票数量（cardId==1 的条数）
     */
    private Integer ticketCount;
    /**
     * 各类卡券数量明细
     */
    private List<CardCountDetail> details;

    @Data
    public static class CardCountDetail {
        /**
         * 卡券定义id
         */
        private Integer cardId;
        /**
         * 卡券名称
         */
        private String name;
        /**
         * 数量
         */
        private Integer count;
    }
}
