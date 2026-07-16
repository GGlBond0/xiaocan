package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单配置
 */
@Data
@TableName("grab_config")
public class GrabConfigEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 用户id
     */
    private Integer userId;
    /**
     * 绑定的登录态id（grab_login_state.id）
     */
    private Integer loginStateId;
    /**
     * 位置信息ID（为空则用入参经纬度）
     */
    private Long locationId;
    /**
     * 要抢的活动id（当天有效）
     */
    private Integer promotionId;
    /**
     * silk_id
     */
    private Integer silkId;
    /**
     * 平台，默认1
     */
    private Integer storePlatform;
    /**
     * 是否预售
     */
    private Boolean ifAdvanceOrder;
    /**
     * 定时抢单cron（6位含秒），空=仅手动/一次性
     */
    private String cron;
    /**
     * 一次性精确执行时间，命中后停用
     */
    private LocalDateTime executeAt;
    /**
     * 提前量（毫秒）
     */
    private Integer leadMs;
    /**
     * code4是否重试
     */
    private Boolean enableRetry;
    /**
     * 最大重试次数
     */
    private Integer maxRetry;
    /**
     * 重试间隔（毫秒）
     */
    private Integer retryIntervalMs;
    /**
     * 状态
     */
    private MonitorConfigStatusEnums status;
    /**
     * 最近一次结果
     */
    private String lastResult;
    /**
     * 最近抢单时间
     */
    private LocalDateTime lastGrabTime;
    /**
     * 抢到的订单id
     */
    private Long promotionOrderId;
    /**
     * 是否监控自动抢单产生：0-否(手动/定时)，1-是(立即抢，不进前端列表、不注册cron)
     */
    private Boolean auto;
    /**
     * 监控自动抢来源 monitor_config.id；手动/定时抢单为 null。
     * 用于到点回调时重读账号/平台优先级与模式，推进降级。
     */
    private Integer monitorConfigId;
    /**
     * 降级游标："平台索引:账号索引"，记录本次监控命中已尝试到的组合/账号位置，
     * 供到点回调从断点继续换号/降级。手动/定时抢单为 null。
     */
    private String grabSeq;
    /**
     * 同门店所有组合快照（JSON），供到点回调/降级时重建组合列表（含 promotionId/storePlatform/
     * startTime/endTime/storeName/promoDetail 等够 doGrab + 时间判断的字段）。手动/定时抢单为 null。
     */
    private String comboSnapshot;
    /**
     * 活动快照：商家名
     */
    private String storeName;
    /**
     * 活动快照：优惠明细，如 满20返15
     */
    private String promoDetail;
    /**
     * 活动快照：活动时段开始 HH:MM
     */
    private String startTime;
    /**
     * 活动快照：活动时段结束 HH:MM
     */
    private String endTime;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
