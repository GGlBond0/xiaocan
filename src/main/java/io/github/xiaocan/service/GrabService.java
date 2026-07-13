package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.dto.GrabLoginStateDTO;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.entity.GrabHistoryEntity;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;

import java.util.List;

public interface GrabService extends IService<GrabConfigEntity> {

    /**
     * 录入/更新登录态（解析抓包 header 原文）
     */
    GrabResultVO saveLoginState(GrabLoginStateDTO dto);

    /**
     * 查询当前登录态摘要
     */
    GrabResultVO getLoginState();

    /**
     * 保存/更新抢单配置
     */
    void addUpdateConfig(GrabConfigDTO dto);

    /**
     * 当前用户抢单配置列表
     */
    List<GrabConfigVO> listByUserId();

    /**
     * 删除抢单配置
     */
    void deleteById(Integer configId);

    /**
     * 启用/停用
     */
    void toggleStatus(Integer configId, io.github.xiaocan.model.enums.MonitorConfigStatusEnums status);

    /**
     * 手动立即抢一次（按配置id）
     */
    GrabResultVO executeManual(Integer configId);

    /**
     * 执行一次抢单（手动/定时共用），含重试。返回最终结果。
     *
     * @param config      抢单配置
     * @param triggerType MANUAL/CRON/ONESHOT
     */
    GrabResultVO doGrab(GrabConfigEntity config, String triggerType);

    /**
     * 抢单历史记录查询
     */
    List<GrabHistoryVO> listHistoryByUserId(Integer limit);
}
