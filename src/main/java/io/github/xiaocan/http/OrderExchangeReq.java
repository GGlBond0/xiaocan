package io.github.xiaocan.http;

import lombok.Builder;
import lombok.Data;

/**
 * 饿了么/京东抢单请求体（SilkwormMobileCommunityService.OrderExchange）。
 *
 * 与美团 {@code GrabPromotionQuota} 是两套独立接口契约，字段完全不同。
 * 来源见 prd.md V2 / design.md D2（HAR 2026-07-17，flow f30e26fd）。
 *
 * 字段来源：
 *  - silk_id / new_level / is_plus / ingot         ← 登录态 + GetClientUserInfo.vip_level_info（new_level/is_plus 已确认；ingot 待补抓，先样本占位）
 *  - store_id / promotion_type / city_code         ← 活动详情 promotion_detail
 *  - store_category_sub_type                       ← store.store_category_sub_type
 *  - promotion_silk_amount                         ← tp_promotion.tp_user_rebate
 *  - store_platform_order_money                     ← tp_promotion.tp_order_money
 *  - bwc_platform                                  ← tp_promotion.store_platform（1美团/2饿了么/3京东）
 *  - store_type / store_category_type / is_super_brand / bwc_type / promotion_event_type ← 来源未完全定位，先样本占位
 */
@Data
@Builder
public class OrderExchangeReq {
    private Integer silkId;
    private Integer newLevel;
    private Boolean isPlus;
    private Integer ingot;
    private Integer storeId;
    private Integer storeType;
    private Integer promotionId;
    private Integer promotionType;
    private Integer cityCode;
    private Boolean isSuperBrand;
    private Integer promotionSilkAmount;
    private Integer storeCategorySubType;
    private Integer storeCategoryType;
    private Integer bwcType;
    private Integer storePlatformOrderMoney;
    private Integer bwcPlatform;
    private Object promotionEventType;
}
