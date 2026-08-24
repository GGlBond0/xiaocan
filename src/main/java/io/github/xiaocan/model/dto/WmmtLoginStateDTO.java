package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 歪麦登录态录入（多账号池）。歪麦门店浏览无需账号，账号供未来抢单/监控引用。
 */
@Data
public class WmmtLoginStateDTO {
    /**
     * 别名（可空，空则默认"账号N"）
     */
    private String name;
    /**
     * 歪麦 token（必填，登录返回 data.userToken）
     */
    @NotBlank(message = "歪麦 token 不能为空")
    private String token;
    /**
     * 歪麦用户id（字符串，登录返回 data.userId 如 "2022..._user"；抢单请求体必填。可空=仅浏览/监控，可抢需填）
     */
    private String wmmtUserId;
    /**
     * 城市（可空，默认长沙市）
     */
    private String city;
}