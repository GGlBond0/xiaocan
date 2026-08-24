package io.github.xiaocan.http;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 歪麦（waimaimingtang）外卖霸王餐抢单（报名）请求体。
 * <p>
 * 逆向来源：反编译小程序 wx5762f23d920ad9e5 — storeDetails/info/info.js「抢单data」，
 * 契约见 .trellis/spec/backend/wmmt-grab-contract.md。
 * <p>
 * 双轨：newSignUpFlag 为真 → 新版 encryptedRequest(RSA+AES, wmapp-api-v2)；
 * 为假 → 老版 request(LEGACY_AES, fz-gateway)。两轨 body 字段相同（新版本仅多 orderSourcePage）。
 * <p>
 * 字段来源：
 *  - businessId      ← 门店详情参数 ops.id
 *  - overbearfoodId  ← 门店列表/详情活动项 overBearFoodId（String）
 *  - userId          ← 登录返回 data.userId（字符串，≠ token；wmmt_login_state.wmmt_user_id）
 *  - redIds          ← 选中红包 id 数组（maxUserRedPackage.id 等），无则空数组
 *  - province/city/area ← 地址 addres.prov/city/area
 */
@Data
@Builder
public class WmmtSignUpRequest {
    /** 门店id */
    private String businessId;
    /** 歪麦活动/商品id（String） */
    private String overbearfoodId;
    /** 服务名枚举 */
    private String serviceNoStr;
    /** 歪麦用户id（字符串，登录返回 data.userId 如 "2022..._user"） */
    private String userId;
    /** 购买渠道，固定 "autonomy" */
    private String buyChannel;
    /** application，固定 "overbear_one" */
    private String type;
    /** 分享记录id（默认空串） */
    private String shareRecordId;
    private String shareLatitude;
    private String shareLongitude;
    private String shareUserId;
    /** 红包id数组 */
    private List<Long> redIds;
    /** 省 */
    private String province;
    /** 市 */
    private String city;
    /** 区 */
    private String area;
    /** 抢单来源页（仅新版；老版不传） */
    private String orderSourcePage;
}