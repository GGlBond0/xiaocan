package io.github.xiaocan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class StoreInfo {

    /**
     * 门店名称
     */
    private String name;
    /**
     * 门店id
     */
    private Integer storeId;
    /**
     * 是否是新店
     */
    private Boolean ifNew;
    /**
     * 营业时间 10:00-22:00
     */
    private String openHours;
    /**
     * 活动id
     * 同一个门店，这个活动id每天都是不一样的
     */
    private Integer promotionId;
    /**
     * 平台类型 1:美团，2：饿了么，3京东
     */
    private Integer type;
    /**
     * 活动开始时间 格式08:00
     */
    private String startTime;

    /**
     * 活动结束时间 格式21:00
     */
    private String endTime;
    /**
     * 剩余数量
     */
    private Integer leftNumber;

    /**
     * 距离，单位米
     */
    private Integer distance;
    /**
     * 满多少返
     */
    private BigDecimal price;
    /**
     * 返的金额
     */
    private BigDecimal rebatePrice;
    /**
     * 好评条件
     * 99：无需评价
     * 2：图文评价
     */
    private Integer rebateCondition;
    /**
     * 门店图片
     */
    private String icon;

    // ====== 饿了么/京东 OrderExchange 抢单接口所需的活动详情字段 ======
    // 现有美团走 GrabPromotionQuota（仅需 lat/lng/promotion_id），饿了么/京东走
    // SilkwormMobileCommunityService.OrderExchange，请求体需要下列活动属性。
    // 来源见 prd.md V2 / design.md D2（HAR 2026-07-17，flow f30e26fd / 5b0383d5 / 2b8c1da7）。
    // 部分字段来源未完全定位，先以样本占位（见 XiaochanHttp.buildOrderExchangeReq）。

    /**
     * 活动 promotion_type（promotion_detail.promotion_type）
     */
    private Integer promotionType;
    /**
     * 城市 city_code（promotion_detail.store.city_code）
     */
    private Integer cityCode;
    /**
     * 第三方平台 store_platform（tp_promotion.store_platform；1美团/2饿了么/3京东）。
     * 仅饿了么/京东活动有 tp_promotion 时填充，美团为 null。
     */
    private Integer tpStorePlatform;
    /**
     * 平台订单金额（tp_promotion.tp_order_money，单位分）
     */
    private BigDecimal storePlatformOrderMoney;
    /**
     * 平台返利金额（tp_promotion.tp_user_rebate，单位分）
     */
    private BigDecimal promotionSilkAmount;
    /**
     * 门店类目子类型（store.store_category_sub_type）
     */
    private Integer storeCategorySubType;

}
