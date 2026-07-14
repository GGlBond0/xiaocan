package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.NotifyHistoryQueryDTO;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.vo.StorePushedHistoryVO;

public interface StorePushedHistoryService extends IService<StorePushedHistoryEntity> {

    /**
     * 分页查询通知历史记录（当前用户）
     * @param dto 分页参数
     * @return 分页结果
     */
    Page<StorePushedHistoryVO> pageByUser(NotifyHistoryQueryDTO dto);


    StorePushedHistoryEntity findByNotifyIdAndStoreIdToday(Integer notifyId, Integer storeId);

    StorePushedHistoryEntity findByNotifyIdAndStoreIdAll(Integer notifyId, Integer storeId);

    /**
     * 查询某监控配置下，某门店在最近 N 分钟内是否已推送过。
     * 用于按分钟数去重（替代永久去重）。
     */
    StorePushedHistoryEntity findByNotifyIdAndStoreIdWithinMinutes(Integer notifyId, Integer storeId, int minutes);

    /**
     * 删除某监控配置下、创建时间早于 now-N 分钟的推送记录（物理删除）。
     * @return 删除的记录数
     */
    int deleteByNotifyIdOlderThanMinutes(Integer notifyId, int minutes);
}
