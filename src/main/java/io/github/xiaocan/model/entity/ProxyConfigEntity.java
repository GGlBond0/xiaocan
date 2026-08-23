package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代理IP池全局配置（全局单份，约定 id=1）
 */
@Data
@TableName("proxy_config")
public class ProxyConfigEntity {

    /**
     * 主键ID，固定 1（全局单份）
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 是否启用代理
     */
    private Boolean enabled;

    /**
     * 代理 API 地址
     */
    private String apiUrl;

    /**
     * 代理缓存有效期(秒)
     */
    private Integer ttl;

    /**
     * 失败换代理重试次数
     */
    private Integer retry;

    /**
     * 上游请求超时(毫秒)
     */
    private Integer requestTimeout;

    /**
     * 多隧道池组号列表(逗号分隔,如 "51,82,57,61,62,76");为空=单池不轮换
     */
    private String poolList;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
