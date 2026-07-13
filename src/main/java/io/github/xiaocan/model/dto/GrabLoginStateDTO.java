package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 小蚕登录态录入（粘贴抓包 header 原文）
 */
@Data
public class GrabLoginStateDTO {
    /**
     * 抓包请求头原文，多行 "Key: Value" 或抓包 JSON
     */
    @NotBlank
    private String rawHeaders;
}
