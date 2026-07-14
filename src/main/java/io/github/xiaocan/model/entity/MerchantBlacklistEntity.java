package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家名称关键字黑名单全局配置（全局单份，约定 id=1）
 */
@Data
@TableName("merchant_blacklist_config")
public class MerchantBlacklistEntity {

    /**
     * 主键ID，固定 1（全局单份）
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 是否启用黑名单过滤
     */
    private Boolean enabled;

    /**
     * 关键字规则文本（一行一条；行内 & 表示 AND；多行为 OR；大小写不敏感子串包含）
     */
    private String keywords;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
