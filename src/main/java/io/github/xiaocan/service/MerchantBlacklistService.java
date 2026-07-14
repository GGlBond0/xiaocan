package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.MerchantBlacklistDTO;
import io.github.xiaocan.model.entity.MerchantBlacklistEntity;
import io.github.xiaocan.model.vo.MerchantBlacklistVO;

/**
 * 商家名称关键字黑名单全局配置服务
 */
public interface MerchantBlacklistService extends IService<MerchantBlacklistEntity> {

    /**
     * 读取全局黑名单配置（表空则以默认值初始化一行）
     */
    MerchantBlacklistVO getConfig();

    /**
     * 更新全局黑名单配置，保存后使 MerchantBlacklistHolder 缓存失效（即时生效）
     */
    void updateConfig(MerchantBlacklistDTO dto);

    /**
     * 供 MerchantBlacklistHolder 运行时读取原始配置，永不返回 null
     */
    MerchantBlacklistEntity getEntity();
}
