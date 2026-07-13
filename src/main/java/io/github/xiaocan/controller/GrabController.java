package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.dto.GrabLoginStateDTO;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;
import io.github.xiaocan.service.GrabService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 抢单控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/grab")
public class GrabController {

    @Resource
    private GrabService grabService;

    /**
     * 录入/更新小蚕登录态（粘贴抓包 header 原文）
     */
    @PostMapping("/login-state")
    public BaseResult<GrabResultVO> saveLoginState(@Valid @RequestBody GrabLoginStateDTO dto) {
        return BaseResult.ok(grabService.saveLoginState(dto));
    }

    /**
     * 查询登录态
     */
    @GetMapping("/login-state")
    public BaseResult<GrabResultVO> getLoginState() {
        return BaseResult.ok(grabService.getLoginState());
    }

    /**
     * 保存/更新抢单配置
     */
    @PostMapping("/config")
    public BaseResult<Void> addUpdateConfig(@Valid @RequestBody GrabConfigDTO dto) {
        grabService.addUpdateConfig(dto);
        return BaseResult.ok();
    }

    /**
     * 抢单配置列表
     */
    @GetMapping("/config/list")
    public BaseResult<List<GrabConfigVO>> listConfig() {
        return BaseResult.ok(grabService.listByUserId());
    }

    /**
     * 删除抢单配置
     */
    @DeleteMapping("/config/{configId}")
    public BaseResult<Void> deleteConfig(@PathVariable Integer configId) {
        grabService.deleteById(configId);
        return BaseResult.ok();
    }

    /**
     * 启用/停用抢单配置
     */
    @PutMapping("/config/{configId}/status")
    public BaseResult<Void> toggleStatus(@PathVariable Integer configId,
                                          @RequestParam MonitorConfigStatusEnums status) {
        grabService.toggleStatus(configId, status);
        return BaseResult.ok();
    }

    /**
     * 手动立即抢一次
     */
    @PostMapping("/config/{configId}/execute")
    public BaseResult<GrabResultVO> executeManual(@PathVariable Integer configId) {
        return BaseResult.ok(grabService.executeManual(configId));
    }

    /**
     * 抢单历史记录
     */
    @GetMapping("/history/list")
    public BaseResult<List<GrabHistoryVO>> listHistory(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return BaseResult.ok(grabService.listHistoryByUserId(limit));
    }
}
