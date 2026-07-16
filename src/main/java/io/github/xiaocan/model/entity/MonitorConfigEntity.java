package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.xiaocan.model.enums.GrabModeEnums;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import lombok.Data;

import java.time.LocalDateTime;

/**
 *监控配置
 */
@Data
@TableName("monitor_config")
public class MonitorConfigEntity {
    /**
     * id
     */
    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 提醒规则
     */
    private MonitorTypeEnums type;
    /**
     * 用户id
     */
    private Integer userId;
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
    private String extConfig;
    /**
     * 状态
     */
    private MonitorConfigStatusEnums status;
    /**
     * 备注
     */
    private String remark;
    /**
     * 命中后是否自动建立抢单任务
     */
    private Boolean autoGrab;
    /**
     * 自动抢单所用登录态id，指向 login_state.id。
     * 语义已升级：单账号场景仍用此字段（向后兼容存量配置）；
     * 多账号优先级场景优先读 grabLoginStateIds，此字段在保存时回填为列表第一个。
     */
    private Integer grabLoginStateId;
    /**
     * 有序抢单账号 id 串，逗号分隔，顺序即账号优先级（如 "12,5,8"=12 最优先）。
     * null/空 → 回退使用 grabLoginStateId 单值（向后兼容）。
     * 仅 autoGrab=true 时有意义。
     */
    private String grabLoginStateIds;
    /**
     * 启用抢单的平台集合，逗号分隔 int（1美团/2饿了么/3京东，如 "1,2"）。
     * 语义升级：顺序即平台优先级（串内先后=优先级高到低）。
     * null/空 → 仅美团（向后兼容存量配置）。
     */
    private String grabPlatforms;
    /**
     * 抢单模式：SINGLE 抢一个名额（组合内换号、组合间降级）/ ALL 每账号各抢一个（不换号）。
     * null → SINGLE（向后兼容存量配置的单账号单次行为）。
     */
    private GrabModeEnums grabMode;
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
