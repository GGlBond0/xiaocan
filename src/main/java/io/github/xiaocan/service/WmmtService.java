package io.github.xiaocan.service;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.dto.WmmtShopListDTO;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.enums.StoreTypeEnum;
import io.github.xiaocan.model.vo.WmPageVO;

import java.util.List;

/**
 * 歪麦（waimaimingtang）门店服务
 */
public interface WmmtService {

    /**
     * 获取歪麦门店列表（支持游标翻页）
     *
     * @param dto 请求参数
     * @return 门店列表 + 下一页游标
     */
    WmPageVO getShopList(WmmtShopListDTO dto);

    /**
     * 获取歪麦门店活动信息，列表混合了满减和美团赏金，按门店类型过滤
     *
     * @param keyword 门店名模糊搜索，为空时拉取全量列表
     */
    List<StoreInfo> fetchWmStoreInfos(StoreTypeEnum storeType, LocationEntity location, String keyword);

    /**
     * 按指定歪麦 token 抓取门店活动（监控/抢单用，逐步替代单账号 user.waimai_token 路径）。
     * storeType 可为 null：null 表示不过滤业态（满减+美团赏金都返回），供监控「活动语义」过滤。
     *
     * @param token    歪麦 token（来自 wmmt_login_state）
     * @param storeType 门店业态过滤，null 不过滤
     */
    List<StoreInfo> fetchWmStoreInfos(String token, StoreTypeEnum storeType, LocationEntity location, String keyword);
}
