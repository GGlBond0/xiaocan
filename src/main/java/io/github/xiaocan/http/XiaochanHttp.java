package io.github.xiaocan.http;

import cn.hutool.crypto.digest.MD5;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.vo.AddressVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

@Slf4j
public class XiaochanHttp {


    private static final String BASE_URL = "https://gw.xiaocantech.com/rpc";
    private static final String SERVER_NAME = "SilkwormRec";
    private static final String METHOD_NAME = "RecService.GetStorePromotionList";


    private static final int PAGE_SIZE = 30;

    /**
     * 获取Ashe
     * @param timeMillis X-Garen
     * @return
     */
    private static String getAshe(Long timeMillis, String serverName, String methodName, String nami) {
        String x = MD5.create().digestHex((serverName + "." + methodName).toLowerCase());
        return MD5.create().digestHex(x + timeMillis + nami);
    }


    public List<StoreInfo> getList(Integer cityCode, String longitude, String latitude, int offset){
        String reqBody = getBody(cityCode, longitude, latitude, offset, 0, 0);
        String resBody = postWithRes(BASE_URL, reqBody, cityCode, SERVER_NAME, METHOD_NAME);
        return parseListBody(resBody);
    }



    public List<StoreInfo> searchList(String keyword, Integer cityCode, String longitude, String latitude, int offset, Integer number) {
        HashMap<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("silk_id", 0);
        bodyMap.put("latitude", new BigDecimal(latitude));
        bodyMap.put("longitude", new BigDecimal(longitude));
        bodyMap.put("promotion_sort", 1);
        bodyMap.put("store_platform", 0);
        bodyMap.put("store_type", 99);
        bodyMap.put("offset",offset);
        bodyMap.put("number",number);
        bodyMap.put("keyword", keyword);
        bodyMap.put("promotion_category", 0);
        bodyMap.put("app_id",20);
        String resBody = postWithRes(BASE_URL, JSONObject.toJSONString(bodyMap), cityCode, "SilkwormRec", "RecService.SearchStorePromotionList");
        return parseListBody(resBody);

    }

    private String postWithRes(String url, String body, Integer cityCode, String serverName, String methodName) {
        Long timeMillis = System.currentTimeMillis();
        String nami = getNami();
        String ashe = getAshe(timeMillis, serverName, methodName,nami);
        HttpResponse response = executeWithProxy(proxy -> HttpUtil.createPost(url)
                .headerMap(getHeaders(timeMillis, ashe, cityCode, serverName, methodName, nami), true)
                .timeout(ProxyHolder.requestTimeout())
                .body(body), "postWithRes");
        if (response == null || !response.isOk()) {
            int status = response == null ? -1 : response.getStatus();
            log.error("状态码错误: {}, body: {}", status, response == null ? "" : response.body());
            throw new BusinessException("状态码错误:" + status);
        }
        String resBody = response.body();
        response.close();
        return resBody;
    }

    /**
     * 经代理执行上游 HTTP 请求；代理未启用则直连。
     * 遇 403 或网络异常（SocketTimeout/Connection reset 等）失效当前代理并换代理重试，
     * 最多 {@link ProxyHolder#retry()} 次；全部失败返回 null 由调用方处理。
     * @param reqFn 接收 proxy（[ip,port]，直连时为 null），返回待执行的 HttpRequest
     * @param tag   日志标识（方法名）
     */
    private HttpResponse executeWithProxy(Function<String[], HttpRequest> reqFn, String tag) {
        if (!ProxyHolder.enabled()) {
            return reqFn.apply(null).execute();
        }
        int retry = ProxyHolder.retry();
        for (int i = 0; i < retry; i++) {
            String[] proxy = ProxyHolder.getProxy(i > 0);
            if (proxy == null) {
                throw new BusinessException("代理不可用，无法请求小蚕网关");
            }
            HttpRequest req = reqFn.apply(proxy);
            req.setHttpProxy(proxy[0], Integer.parseInt(proxy[1]));
            HttpResponse response;
            try {
                response = req.execute();
            } catch (Exception e) {
                // SocketTimeoutException / Connection reset 等网络异常：失效当前代理并换代理重试，
                // 避免坏代理被命中后整轮重试都用同一坏代理（首页慢、抢单超时的根因）。
                log.warn("{} 经代理 {}:{} 请求异常，换代理重试({}/{}): {}", tag, proxy[0], proxy[1], i + 1, retry, e.getMessage());
                ProxyHolder.invalidate();
                continue;
            }
            if (response.getStatus() == 403) {
                log.warn("{} 经代理 {}:{} 返回 403，换代理重试({}/{})", tag, proxy[0], proxy[1], i + 1, retry);
                response.close();
                ProxyHolder.invalidate();
                continue;
            }
            return response;
        }
        return null;
    }


    /**
     * 搜索地址
     */
    public List<AddressVO> searchAddress(Integer cityCode, String keyword){
        final String serverName = "SilkwormLbs";
        final String methodName = "SilkwormLbsService.Suggestion";
        Map<String, Object> bodyMap = Map.of("silk_id", 0, "keyword", keyword,
                "region", "", "page_size", 20, "page", 1, "app_id", 20);
        Long timeMillis = System.currentTimeMillis();
        String nami = getNami();
        String ashe = getAshe(timeMillis, serverName, methodName,nami);
        try {
            HttpResponse response = executeWithProxy(proxy -> HttpUtil.createPost(BASE_URL)
                    .headerMap(getHeaders(timeMillis, ashe, cityCode, serverName, methodName,nami), true)
                    .timeout(ProxyHolder.requestTimeout())
                    .body(JSONObject.toJSONString(bodyMap)), "searchAddress");
            if (response == null || !response.isOk()) {
                int status = response == null ? -1 : response.getStatus();
                throw new BusinessException("状态码错误:" + status);
            }
            return parseBodyToAddress(response.body());
        } catch (Exception e) {
            log.error("{} error", methodName, e);
            throw e;
        }
    }


    /**
     * 获取活动详情
     * 内容比较丰富，可按需索取
     * @param promotionId
     * @return
     */
    public StoreInfo getStorePromotionDetail(Integer promotionId){
        Map<String, Integer> reqMap = Map.of("silk_id", 0,
                "promotion_id", promotionId,
                "app_id", 20);
        String resBody = postWithRes(BASE_URL, JSONObject.toJSONString(reqMap), null, "Silkworm", "SilkwormService.GetStorePromotionDetail");
        JSONObject jsonObject = checkResult(resBody);
        List<StoreInfo> storeInfos = parsePromotion(jsonObject.getJSONObject("promotion_detail"));
        return storeInfos.get(0);
    }

    /**
     * 获取活动详情中指定平台的快照。
     * 多平台活动 detail 会解析出多个 StoreInfo（美团/饿了么/京东），按 targetPlatform(1/2/3) 匹配；
     * 找不到时回退到第一个（兼容单平台活动）。
     *
     * @param promotionId    活动 id
     * @param targetPlatform 目标平台（1美团/2饿了么/3京东）
     */
    public StoreInfo getStorePromotionDetail(Integer promotionId, Integer targetPlatform) {
        Map<String, Integer> reqMap = Map.of("silk_id", 0,
                "promotion_id", promotionId,
                "app_id", 20);
        String resBody = postWithRes(BASE_URL, JSONObject.toJSONString(reqMap), null, "Silkworm", "SilkwormService.GetStorePromotionDetail");
        JSONObject jsonObject = checkResult(resBody);
        List<StoreInfo> storeInfos = parsePromotion(jsonObject.getJSONObject("promotion_detail"));
        if (targetPlatform != null) {
            for (StoreInfo s : storeInfos) {
                if (targetPlatform.equals(s.getType())) return s;
            }
        }
        return storeInfos.get(0);
    }
    /**
     * 抢单接口服务名/方法名
     */
    private static final String GRAB_SERVER_NAME = "Silkworm";
    private static final String GRAB_METHOD_NAME = "SilkwormService.GrabPromotionQuota";

    /**
     * 饿了么/京东抢单接口（与美团 GrabPromotionQuota 是两套独立契约）。
     * servername=SilkwormCommunity, methodname=SilkwormMobileCommunityService.OrderExchange。
     */
    private static final String EXCHANGE_SERVER_NAME = "SilkwormCommunity";
    private static final String EXCHANGE_METHOD_NAME = "SilkwormMobileCommunityService.OrderExchange";
    /** 用户信息接口（取会员等级 new_level/is_plus，用于组装 OrderExchange 请求体） */
    private static final String USERINFO_SERVER_NAME = "Silkworm";
    private static final String USERINFO_METHOD_NAME = "SilkwormService.GetClientUserInfo";

    /**
     * 卡券查询接口服务名/方法名
     */
    private static final String CARD_SERVER_NAME = "SilkwormCard";
    private static final String CARD_METHOD_NAME = "SilkwormCardService.GetUserCardList";

    /**
     * 卡券查询：调用 SilkwormCardService.GetUserCardList。
     * 复用 X-Ashe 加密 + 登录态 + 代理（与抢单同模式）。
     *
     * @param auth   登录态
     * @param number 每页数量
     * @param offset 偏移
     * @param status 卡券状态（0=全部）
     * @return 小蚕原始响应 JSON（含 status.code / list[]）
     */
    public JSONObject getUserCardList(GrabAuth auth, int number, int offset, int status) {
        Integer silkId = auth.getSilkId() == null ? 0 : auth.getSilkId();
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("offset", offset);
        body.put("silk_id", silkId);
        body.put("status", status);
        // 卡券查询无 cityCode，传 null
        String resBody = postWithResAuth(BASE_URL, JSONObject.toJSONString(body), null,
                CARD_SERVER_NAME, CARD_METHOD_NAME, auth);
        return JSONObject.parseObject(resBody);
    }

    /**
     * 抢单：调用 SilkwormService.GrabPromotionQuota。
     * 复用 X-Ashe 加密算法与代理机制；header 带 Android 登录态。
     * silk_id 取自 auth（登录态记录，X-Teemo）。
     *
     * @param auth        登录态（X-Sivir/X-Teemo/X-Session-Id，Nami 为空则随机）
     * @param cityCode    城市编码
     * @param latitude    纬度
     * @param longitude   经度
     * @param promotionId 活动 id（当天有效）
     * @return 小蚕原始响应 JSON（含 status.code / promotion_order_id / timeout）
     */
    public JSONObject grabPromotionQuota(GrabAuth auth, Integer cityCode, String latitude, String longitude,
                                         Integer promotionId) {
        Integer silkId = auth.getSilkId() == null ? 0 : auth.getSilkId();
        Map<String, Object> body = new HashMap<>();
        body.put("latitude", new BigDecimal(latitude));
        body.put("longitude", new BigDecimal(longitude));
        body.put("city_code", cityCode);
        body.put("store_platform", 1);
        body.put("if_advance_order", false);
        body.put("promotion_id", promotionId);
        body.put("silk_id", silkId);
        String resBody = postWithResAuth(BASE_URL, JSONObject.toJSONString(body), cityCode,
                GRAB_SERVER_NAME, GRAB_METHOD_NAME, auth);
        return JSONObject.parseObject(resBody);
    }

    /**
     * 饿了么/京东抢单：调用 SilkwormMobileCommunityService.OrderExchange。
     * 请求体字段与美团 GrabPromotionQuota 完全不同（见 {@link OrderExchangeReq}），
     * header/加密/代理机制相同（复用 postWithResAuth）。
     *
     * @param auth 登录态（X-Sivir/X-Teemo/X-Session-Id）
     * @param req  OrderExchange 请求体（由 {@link #buildOrderExchangeReq} 组装）
     * @return 小蚕原始响应 JSON，成功判据 status.code == 0
     */
    public JSONObject orderExchange(GrabAuth auth, OrderExchangeReq req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("silk_id", req.getSilkId());
        body.put("new_level", req.getNewLevel());
        body.put("is_plus", req.getIsPlus());
        body.put("ingot", req.getIngot());
        body.put("store_id", req.getStoreId());
        body.put("store_type", req.getStoreType());
        body.put("promotion_id", req.getPromotionId());
        body.put("promotion_type", req.getPromotionType());
        body.put("city_code", req.getCityCode());
        body.put("is_super_brand", req.getIsSuperBrand());
        body.put("promotion_silk_amount", req.getPromotionSilkAmount());
        body.put("store_category_sub_type", req.getStoreCategorySubType());
        body.put("store_category_type", req.getStoreCategoryType());
        body.put("bwc_type", req.getBwcType());
        body.put("store_platform_order_money", req.getStorePlatformOrderMoney());
        body.put("bwc_platform", req.getBwcPlatform());
        body.put("promotion_event_type", req.getPromotionEventType());
        String resBody = postWithResAuth(BASE_URL, JSONObject.toJSONString(body), req.getCityCode(),
                EXCHANGE_SERVER_NAME, EXCHANGE_METHOD_NAME, auth);
        return JSONObject.parseObject(resBody);
    }

    /**
     * 用户信息：调用 SilkwormService.GetClientUserInfo，取 vip_level_info.new_level/is_plus。
     * 用于组装 OrderExchange 请求体（抢单前需拿到当前会员等级）。
     *
     * @return user_info.vip_level_info JSON；不存在返回 null
     */
    public JSONObject getClientUserInfoVipLevel(GrabAuth auth) {
        Integer silkId = auth.getSilkId() == null ? 0 : auth.getSilkId();
        Map<String, Object> body = new HashMap<>();
        body.put("silk_id", silkId);
        String resBody = postWithResAuth(BASE_URL, JSONObject.toJSONString(body), null,
                USERINFO_SERVER_NAME, USERINFO_METHOD_NAME, auth);
        JSONObject resp = JSONObject.parseObject(resBody);
        JSONObject userInfo = resp.getJSONObject("user_info");
        if (userInfo == null) return null;
        return userInfo.getJSONObject("vip_level_info");
    }

    /**
     * 用活动详情快照 + 登录态 + 会员等级 组装 OrderExchange 请求体。
     * 待定字段（来源未完全定位，先以 HAR 样本占位，TODO 标注需按账号/活动验证后替换）：
     *  - ingot（元宝余额，样本 600）：来源未定位，先固定 600 TODO
     *  - store_type（样本 = bwc_platform）：暂按平台值占位 TODO
     *  - store_category_type（样本 = store_category_sub_type）：暂同 sub_type TODO
     *  - is_super_brand（样本 false）：先固定 false TODO
     *  - bwc_type（样本 0）：先固定 0 TODO
     *  - promotion_event_type（样本 null）：先固定 null
     */
    public OrderExchangeReq buildOrderExchangeReq(GrabAuth auth, StoreInfo store) {
        Integer silkId = auth.getSilkId() == null ? 0 : auth.getSilkId();
        // 抢单前取会员等级；取不到则用样本默认值占位
        Integer newLevel = 2;
        Boolean isPlus = false;
        try {
            JSONObject vip = getClientUserInfoVipLevel(auth);
            if (vip != null) {
                newLevel = vip.getInteger("new_level");
                isPlus = vip.getBoolean("is_plus");
                if (newLevel == null) newLevel = 2;
                if (isPlus == null) isPlus = false;
            }
        } catch (Exception e) {
            log.warn("取会员等级失败，用样本默认值占位 promotionId={}: {}", store.getPromotionId(), e.getMessage());
        }
        Integer bwcPlatform = store.getTpStorePlatform();
        // 活动金额取原始整数（tp_order_money/tp_user_rebate 在响应里即为整数，如 2500/1000）
        int platformOrderMoney = store.getStorePlatformOrderMoney() == null ? 0 : store.getStorePlatformOrderMoney().intValue();
        int silkAmount = store.getPromotionSilkAmount() == null ? 0 : store.getPromotionSilkAmount().intValue();
        return OrderExchangeReq.builder()
                .silkId(silkId)
                .newLevel(newLevel)
                .isPlus(isPlus)
                .ingot(600) // TODO 来源未定位，样本占位（HAR flow f30e26fd），需按账号验证
                .storeId(store.getStoreId())
                // store_type：HAR 京东样本 store_type=2=sub_type，饿了么样本 store_type=12=sub_type，三者相等，取 storeCategorySubType（非 bwc_platform）
                .storeType(store.getStoreCategorySubType())
                .promotionId(store.getPromotionId())
                .promotionType(store.getPromotionType())
                .cityCode(store.getCityCode())
                .isSuperBrand(false) // TODO 来源未定位，样本占位（HAR 均为 false）
                .promotionSilkAmount(silkAmount)
                .storeCategorySubType(store.getStoreCategorySubType())
                .storeCategoryType(store.getStoreCategorySubType()) // HAR：store_category_type = sub_type
                .bwcType(0) // TODO 来源未定位，样本占位（HAR 均为 0）
                .storePlatformOrderMoney(platformOrderMoney)
                .bwcPlatform(bwcPlatform)
                .promotionEventType(null)
                .build();
    }

    /**
     * 带登录态 header 的 POST（代理/403 重试逻辑同 {@link #postWithRes}）。
     * x-Teemo = silk_id，X-Vayne = 用户id（见抓包 favorites1.json）。
     */
    private String postWithResAuth(String url, String body, Integer cityCode, String serverName, String methodName, GrabAuth auth) {
        Long timeMillis = System.currentTimeMillis();
        String nami = (auth.getNami() != null && !auth.getNami().isEmpty()) ? auth.getNami() : getNami();
        String ashe = getAshe(timeMillis, serverName, methodName, nami);
        HttpResponse response = executeWithProxy(proxy -> HttpUtil.createPost(url)
                .headerMap(getGrabHeaders(timeMillis, ashe, cityCode, serverName, methodName, nami, auth), true)
                .timeout(ProxyHolder.requestTimeout())
                .body(body), "grabPromotionQuota");
        if (response == null || !response.isOk()) {
            int status = response == null ? -1 : response.getStatus();
            log.error("抢单状态码错误: {}, body: {}", status, response == null ? "" : response.body());
            throw new BusinessException("状态码错误:" + status);
        }
        String resBody = response.body();
        response.close();
        return resBody;
    }

    /**
     * 抢单请求头（Android 登录态）。x-Teemo=silk_id，X-Vayne=用户id。
     */
    private Map<String, String> getGrabHeaders(Long timeMillis, String ashe, Integer cityCode,
                                               String serverName, String methodName, String nami, GrabAuth auth) {
        Integer silkId = auth.getSilkId() == null ? 0 : auth.getSilkId();
        Map<String, String> headers = new HashMap<>();
        headers.put("servername", serverName);
        headers.put("methodname", methodName);
        headers.put("X-Ashe", ashe);
        headers.put("X-Nami", nami);
        headers.put("X-Garen", String.valueOf(timeMillis));
        headers.put("X-Platform", "Android");
        headers.put("x-Annie", "XC");
        headers.put("X-Session-Id", auth.getSessionId());
        headers.put("User-Agent", "XC;Android;3.18.3;");
        headers.put("X-Vayne", String.valueOf(auth.getUserId()));
        headers.put("x-Teemo", String.valueOf(silkId));
        headers.put("X-Sivir", auth.getSivir());
        headers.put("X-Version", "3.18.3.3");
        if (cityCode != null) {
            headers.put("X-CityCode", String.valueOf(cityCode));
            headers.put("X-City", String.valueOf(cityCode));
        }
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept-Encoding", "gzip");
        headers.put("Connection", "Keep-Alive");
        return headers;
    }

    private List<AddressVO> parseBodyToAddress(String body) {
        JSONObject jsonObject = JSONObject.parseObject(body);
        if (jsonObject.getJSONObject("status").getInteger("code") != 0) {
            log.error("parseBodyToAddress error body: {} ", body);
            throw new BusinessException("状态码错误:" + jsonObject.getJSONObject("status").getInteger("code"));
        }
        JSONArray jsonArray = jsonObject.getJSONArray("result");
        List<AddressVO> result = new ArrayList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject obj = jsonArray.getJSONObject(i);
            AddressVO addressVO = AddressVO.builder()
                    .id(obj.getString("id"))
                    .title(obj.getString("title"))
                    .address(obj.getString("address"))
                    .latitude(obj.getJSONObject("location").getString("lat"))
                    .longitude(obj.getJSONObject("location").getString("lng"))
                    .cityCode(obj.getInteger("adcode"))
                    .province(obj.getString("province"))
                    .city(obj.getString("city"))
                    .district(obj.getString("district"))
                    .build();
            result.add(addressVO);
        }
        return result;
    }


    private static String getNami(){
        String uuid = generateUuid();
        uuid = uuid.replace("-", "");
        String silkId = "0";
        return uuid.substring(0, 4) + silkId + uuid.substring(4, 20 - silkId.length() - 4);
    }

    private static String getBody(Integer cityCode, String longitude, String latitude, int offset, int promotionCategory, int storeCategory) {
        Map<String, Object> body = new HashMap<>();
        body.put("latitude", new BigDecimal(latitude));
        body.put("longitude", new BigDecimal(longitude));
        body.put("promotion_sort", 3);
        body.put("store_type", 0);
        body.put("offset", offset);
        body.put("number", PAGE_SIZE);
        body.put("silk_id", 0);
        body.put("promotion_filter", 0);
        body.put("promotion_category", promotionCategory);
        body.put("city_code", cityCode);
        body.put("store_category", storeCategory);
        body.put("store_platform", 0);
        body.put("app_id", 20);
        return JSONObject.toJSONString(body);
    }


    private Map<String, String> getHeaders(Long timeMillis, String ashe, Integer cityCode, String serverName, String methodName, String nami){
        Map<String, String> headers = new HashMap<>();
        headers.put("x-City", String.valueOf(cityCode));
        headers.put("X-Garen", String.valueOf(timeMillis));
        headers.put("X-Nami",nami);
        headers.put("X-Platform","mini");
        headers.put("version", "3.15.9.10");
        headers.put("X-Version", "3.15.9.10");
        headers.put("appid", "20");
        headers.put("X-Model", "microsoft microsoft");
        headers.put("x-Annie", "XC");
        headers.put("xweb_xhr", "1");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) UnifiedPCWindowsWechat(0xf254173b) XWEB/19027");
        headers.put("servername", serverName);
        headers.put("methodname", methodName);
        headers.put("X-Ashe", ashe);
        headers.put("Referer", "https://servicewechat.com/wx52ae84595214/965/page-frame.html");
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private List<StoreInfo> parsePromotion(JSONObject jsonObject){
        List<StoreInfo> result = new ArrayList<>();
        StoreInfo storeInfo = new StoreInfo();
        JSONObject store = jsonObject.getJSONObject("store");
        storeInfo.setName(store.getString("name"));
        storeInfo.setOpenHours(store.getString("opening_hours"));
        storeInfo.setPromotionId(jsonObject.getInteger("promotion_id"));
        storeInfo.setRebateCondition(jsonObject.getInteger("rebate_condition"));
        storeInfo.setStartTime(formatStartEndTime(jsonObject.getInteger("start_time_hour"), jsonObject.getInteger("start_time_minute")));
        storeInfo.setEndTime(formatStartEndTime(jsonObject.getInteger("end_time_hour") ,jsonObject.getInteger("end_time_minute")));
        storeInfo.setDistance(jsonObject.getInteger("distance") );
        storeInfo.setIcon(store.getString("icon") );
        storeInfo.setStoreId(store.getInteger("store_id") );
        // 饿了么/京东 OrderExchange 所需活动属性（美团也可填充，仅饿了么/京东分支使用）
        storeInfo.setPromotionType(jsonObject.getInteger("promotion_type"));
        storeInfo.setCityCode(store.getInteger("city_code"));
        storeInfo.setStoreCategorySubType(store.getInteger("store_category_sub_type"));
        //美团
        if (jsonObject.getInteger("meituan_status") == 1) {
            StoreInfo meituanStoreInfo = new StoreInfo();
            BeanUtils.copyProperties(storeInfo, meituanStoreInfo);
            meituanStoreInfo.setType(1);
            meituanStoreInfo.setLeftNumber(jsonObject.getInteger("meituan_left_number"));
            meituanStoreInfo.setPrice(safeDivide(jsonObject.getBigDecimal("meituan_order_money"), BigDecimal.valueOf(100)));
            meituanStoreInfo.setRebatePrice(safeDivide(jsonObject.getBigDecimal("meituan_user_rebate"), BigDecimal.valueOf(100)));
            result.add(meituanStoreInfo);
        }
        //饿了么（独立分支：eleme_status，无 tp_promotion 字段；OrderExchange 所需金额取 eleme_*）
        if (jsonObject.getInteger("eleme_status") == 1) {
            StoreInfo eleStoreInfo = new StoreInfo();
            BeanUtils.copyProperties(storeInfo, eleStoreInfo);
            eleStoreInfo.setType(2);
            eleStoreInfo.setLeftNumber(jsonObject.getInteger("eleme_left_number"));
            eleStoreInfo.setPrice(safeDivide(jsonObject.getBigDecimal("eleme_order_money"), BigDecimal.valueOf(100)));
            eleStoreInfo.setRebatePrice(safeDivide(jsonObject.getBigDecimal("eleme_user_rebate"),BigDecimal.valueOf(100)));
            // OrderExchange 所需字段（饿了么 bwc_platform=2）
            eleStoreInfo.setTpStorePlatform(2);
            eleStoreInfo.setStorePlatformOrderMoney(jsonObject.getBigDecimal("eleme_order_money"));
            eleStoreInfo.setPromotionSilkAmount(jsonObject.getBigDecimal("eleme_user_rebate"));
            result.add(eleStoreInfo);
        }
        // 京东（tp_promotion：第三方平台促销，store_platform 2=饿了么/3=京东）
        if (jsonObject.containsKey("tp_promotion")) {
            JSONObject tpPromotion = jsonObject.getJSONObject("tp_promotion");
            if (tpPromotion.getInteger("tp_status") == 1) {
                StoreInfo eleStoreInfo = new StoreInfo();
                BeanUtils.copyProperties(storeInfo, eleStoreInfo);
                Integer tpPlatform = tpPromotion.getInteger("store_platform");
                eleStoreInfo.setType(tpPlatform); // 2 饿了么 / 3 京东
                eleStoreInfo.setLeftNumber(tpPromotion.getInteger("tp_left_number"));
                eleStoreInfo.setPrice(safeDivide(tpPromotion.getBigDecimal("tp_order_money"), BigDecimal.valueOf(100)));
                eleStoreInfo.setRebatePrice(safeDivide(tpPromotion.getBigDecimal("tp_user_rebate"),BigDecimal.valueOf(100)));
                // OrderExchange 所需的 tp_promotion 字段
                eleStoreInfo.setTpStorePlatform(tpPlatform);
                eleStoreInfo.setStorePlatformOrderMoney(tpPromotion.getBigDecimal("tp_order_money"));
                eleStoreInfo.setPromotionSilkAmount(tpPromotion.getBigDecimal("tp_user_rebate"));
                result.add(eleStoreInfo);
            }
        }
        return result;
    }

    private JSONObject checkResult(String body){
        JSONObject jsonBody = JSONObject.parseObject(body);
        if (jsonBody.getJSONObject("status").getInteger("code") != 0) {
            String msg = jsonBody.getJSONObject("status").getString("msg");
            log.error("请求失败: {}", body);
            throw new BusinessException("请求失败:" + msg);
        }
        return jsonBody;
    }
    private List<StoreInfo> parseListBody(String body){
        JSONObject jsonBody = checkResult(body);
        List<StoreInfo> result = new ArrayList<>();
        JSONArray promotionList = jsonBody.getJSONArray("promotion_list");
        if (promotionList == null) {
            return result;
        }
        for (int i = 0; i < promotionList.size(); i++) {
            JSONObject jsonObject =  promotionList.getJSONObject(i);
            List<StoreInfo> storeInfos = parsePromotion(jsonObject);
            result.addAll(storeInfos);
        }
        return result;
    }

    /**
     * 生成UUID字符串，模仿原始JavaScript版本的行为
     * @return UUID字符串
     */
    public static String generateUuid() {
        char[] chars = new char[36];
        String hexChars = "0123456789abcdef";
        Random random = new Random();
        // 填充随机十六进制字符
        for (int i = 0; i < 36; i++) {
            chars[i] = hexChars.charAt(random.nextInt(16));
        }
        // 设置特定位置确保UUID格式正确
        chars[14] = '4';  // UUID版本
        chars[19] = hexChars.charAt((chars[19] & 0x3) | 0x8);  // UUID变体
        chars[8] = chars[13] = chars[18] = chars[23] = '-';   // 分隔符
        return new String(chars);
    }

    private String formatStartEndTime(Integer hour, Integer minute){
        return String.format("%02d", hour) + ":" + String.format("%02d", minute);
    }

    private BigDecimal safeDivide(BigDecimal b1, BigDecimal b2){
        if (b1 == null || b2 == null) {
            return BigDecimal.ZERO;
        }
        if (b2.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return b1.divide(b2, 2, RoundingMode.DOWN);
    }
}
