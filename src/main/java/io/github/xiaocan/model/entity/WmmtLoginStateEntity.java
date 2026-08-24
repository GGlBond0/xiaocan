package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 歪麦（waimaimingtang）登录态池（一个系统用户可多组）。
 * 歪麦门店浏览无需账号，账号供未来抢单/监控引用。与小蚕 login_state 独立。
 */
@Data
@TableName("wmmt_login_state")
public class WmmtLoginStateEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 系统用户id
     */
    private Integer userId;
    /**
     * 别名（展示用，如 主账号/小号）
     */
    private String name;
    /**
     * 歪麦 token（header token, 登录返回 data.userToken）
     */
    private String token;
    /**
     * 歪麦用户id（字符串，登录返回 data.userId 如 "20221004140718-e247445360_user"，抢单请求体必填 ≠ token）
     */
    private String wmmtUserId;
    /**
     * 城市（默认长沙市，歪麦当前固定）
     */
    private String city;
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