package io.github.xiaocan.service;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.MonitorConfigEntity;

/**
 * 监控命中后自动建立抢单任务。
 * 监控执行体(StoreTask / MinimumPayService)命中门店活动时调用，
 * 按监控配置的 autoGrab 开关与绑定的登录态自动组装 grab_config 并注册调度。
 */
public interface AutoGrabService {

    /**
     * 监控命中后尝试自动建立抢单任务（多账号多平台优先级轮询）。
     *
     * @param config          命中的监控配置（含 autoGrab / grabLoginStateIds / grabPlatforms / grabMode / locationId / userId）
     * @param sameStoreCombos 同一次命中里同门店的所有 (活动,平台) 组合（按 storeId 分组后传入）。
     *                        降级在这些组合间按平台优先级进行；不同门店由调用方分组、互不降级。
     * @return 首个建成的 grab_config.id；未建返回 null
     */
    Long tryCreateFromMonitor(MonitorConfigEntity config, java.util.List<StoreInfo> sameStoreCombos);

    /**
     * 监控自动抢来源的定时任务（grab_config.monitorConfigId 非空）到点触发回调：
     * 从 comboSnapshot 重建同门店组合、重读 monitor_config 账号/平台优先级/模式，
     * 按 grabSeq 游标从断点继续换号/降级。非监控来源(scheduled.monitorConfigId==null)回退 doGrab。
     *
     * @param scheduled 到点触发的 grab_config（含 monitorConfigId / grabSeq / comboSnapshot）
     */
    void onScheduledFire(io.github.xiaocan.model.entity.GrabConfigEntity scheduled);
}
