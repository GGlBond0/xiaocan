package io.github.xiaocan.model;

import io.github.xiaocan.model.enums.StoreTypeEnum;
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

    /**
     * 是否复购活动（活动类型标记，非「当前账号是否有资格」）。
     * 来源：promotion_detail.if_repurchase_promotion 或 promotion_condition.rp。
     * true 表示须在该店有过订单才能参加；账号无历史单时 GrabPromotionQuota 返回 code=107。
     * 本字段不单独阻断抢单（有历史单的账号仍可能成功）。
     */
    private Boolean ifRepurchasePromotion;

    // ====== 上游合并新增字段（L0 地基，仅加法，不动现有字段） ======

    /**
     * 门店唯一id（storeId 或 wm_poi_id）
     */
    private String uniqId;
    /**
     * 门店类型枚举（小蚕满减/美团赏金/歪卖满减/歪卖美团赏金）
     */
    private StoreTypeEnum storeTypeEnum;
    /**
     * 带单位距离，如 1.2km
     */
    private String distanceStr;
    /**
     * 返现百分比（美团赏金）
     */
    private BigDecimal rebateRatio;
    /**
     * 最高返现金额（美团赏金）
     */
    private BigDecimal rebateMax;
    /**
     * 返现条件（字符串）
     */
    private String rebateConditionStr;
    /**
     * 收藏id
     */
    private Long favoriteId;
    /**
     * 是否存在
     */
    private Boolean exists;

    /**
     * 带单位距离 setter，与数值 distance(米) 互转同步（与上游一致）。
     * 解析 "500m"/"1.5km" 到 distance(米)。
     */
    public void setDistanceStr(String distanceStr) {
        this.distanceStr = distanceStr;
        if (distanceStr != null && this.distance == null) {
            String lower = distanceStr.trim().toLowerCase();
            if (lower.endsWith("km")) {
                this.distance = (int) (Double.parseDouble(lower.replace("km", "")) * 1000);
            } else if (lower.endsWith("m")) {
                this.distance = (int) Double.parseDouble(lower.replace("m", ""));
            }
        }
    }

}
