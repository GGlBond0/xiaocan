package io.github.xiaocan.model.vo;

import io.github.xiaocan.model.MinimumPayExtNotifyConfig;
import io.github.xiaocan.model.StoreExtNotifyConfig;
import io.github.xiaocan.model.StoreKeywordExtNotifyConfig;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import lombok.Data;

/**
 * 通知配置
 */
@Data
public class NotifyConfigVO {
    /**
     * id，实现方式为时间戳
     * 由后端生成
     */
    private Integer id;
    /**
     * 提醒规则
     */
    private MonitorTypeEnums type;
    /**
     * 位置信息
     */
    private Long locationId;
    /**
     * 运行时间
     */
    private Integer startHour;
    /**
     * 结束时间
     */
    private Integer endHour;
    /**
     * 运行星期内配置,从1开始，多个以,分隔
     */
    private String weeks;
    /**
     * 自定义 cron 表达式（6位，含秒）
     */
    private String cron;
    /**
     * 门店提醒扩展配置
     */
    private StoreExtNotifyConfig storeExtNotifyConfig;
    /**
     * 金额差提醒扩展配置
     */
    private MinimumPayExtNotifyConfig minimumPayExtNotifyConfig;
    /**
     * 门店关键字提醒扩展配置
     */
    private StoreKeywordExtNotifyConfig storeKeywordExtNotifyConfig;
    /**
     * 状态
     */
    private MonitorConfigStatusEnums status;
    /**
     * 命中后是否自动建立抢单任务
     */
    private Boolean autoGrab;
    /**
     * 自动抢单所用登录态id，指向 login_state.id（单账号兼容字段，多账号时为列表第一个）
     */
    private Integer grabLoginStateId;
    /**
     * 有序抢单账号 id 串，逗号分隔，顺序即账号优先级（如 "12,5,8"）。空 → 回退 grabLoginStateId 单值。
     */
    private String grabLoginStateIds;
    /**
     * 启用抢单的平台集合，逗号分隔 int（1美团/2饿了么/3京东，如 "1,2"）。顺序即平台优先级。空 → 仅美团。
     */
    private String grabPlatforms;
    /**
     * 抢单模式 SINGLE/ALL，空 → SINGLE。
     */
    private io.github.xiaocan.model.enums.GrabModeEnums grabMode;
}
