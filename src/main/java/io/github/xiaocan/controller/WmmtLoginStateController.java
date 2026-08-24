package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.WmmtLoginStateDTO;
import io.github.xiaocan.model.vo.WmmtLoginStateVO;
import io.github.xiaocan.service.WmmtLoginStateService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 歪麦登录态池管理（多账号）。歪麦门店浏览无需账号，账号供未来抢单/监控引用。
 */
@Slf4j
@RestController
@RequestMapping("/api/wmmt-login-state")
public class WmmtLoginStateController {

    @Resource
    private WmmtLoginStateService wmmtLoginStateService;

    /**
     * 新增歪麦登录态
     */
    @PostMapping
    public BaseResult<Integer> save(@Valid @RequestBody WmmtLoginStateDTO dto) {
        return BaseResult.ok(wmmtLoginStateService.save(dto));
    }

    /**
     * 当前用户歪麦账号列表（token 掩码）
     */
    @GetMapping("/list")
    public BaseResult<List<WmmtLoginStateVO>> list() {
        return BaseResult.ok(wmmtLoginStateService.list());
    }

    /**
     * 删除当前用户歪麦账号
     */
    @DeleteMapping("/{id}")
    public BaseResult<Void> delete(@PathVariable Integer id) {
        wmmtLoginStateService.delete(id);
        return BaseResult.ok();
    }
}