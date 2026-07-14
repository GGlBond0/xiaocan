package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.MerchantBlacklistDTO;
import io.github.xiaocan.model.vo.MerchantBlacklistVO;
import io.github.xiaocan.service.MerchantBlacklistService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 商家名称关键字黑名单全局配置读写接口。
 * 全局单份配置，所有登录用户均可读可改。
 */
@RestController
@RequestMapping(value = "/api/blacklist")
public class MerchantBlacklistController {

    @Resource
    private MerchantBlacklistService merchantBlacklistService;
    @Resource
    private UserService userService;

    /**
     * 读取全局商家黑名单配置
     */
    @GetMapping("/config")
    public BaseResult<MerchantBlacklistVO> get() {
        userService.getByCurrentRequest();
        return BaseResult.ok(merchantBlacklistService.getConfig());
    }

    /**
     * 更新全局商家黑名单配置（保存后即时生效，无需重启）
     */
    @PutMapping("/config")
    public BaseResult<Void> update(@Valid @RequestBody MerchantBlacklistDTO dto) {
        userService.getByCurrentRequest();
        merchantBlacklistService.updateConfig(dto);
        return BaseResult.ok();
    }
}
