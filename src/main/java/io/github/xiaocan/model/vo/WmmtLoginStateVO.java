package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 歪麦登录态（列表展示用，token 掩码返回，不回显明文）。
 */
@Data
public class WmmtLoginStateVO {
    private Integer id;
    /**
     * 别名
     */
    private String name;
    /**
     * 掩码后的歪麦 token（前4+****+后4，不足则 ****）
     */
    private String maskedToken;
    /**
     * 歪麦用户id（字符串，登录返回 data.userId）
     */
    private String wmmtUserId;
    /**
     * 城市
     */
    private String city;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}