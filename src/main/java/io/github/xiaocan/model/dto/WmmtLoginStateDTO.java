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
     * 歪麦 token（必填）
     */
    @NotBlank(message = "歪麦 token 不能为空")
    private String token;
    /**
     * 城市（可空，默认长沙市）
     */
    private String city;
}