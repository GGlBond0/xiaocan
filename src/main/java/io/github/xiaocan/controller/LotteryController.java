package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.vo.LotteryDrawResultVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 霸王餐刷浏览任务控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/lottery")
public class LotteryController {

    @Resource
    private LotteryService lotteryService;

    /**
     * 一键刷霸王餐浏览任务（完成未完成的浏览类任务，攒抽奖机会）。
     */
    @PostMapping("/run")
    public BaseResult<LotteryTaskResultVO> runTask(@RequestParam Integer authId) {
        // 2026-07-21：183 账号 gwh 端点被 WAF 风控拦截（runTask 大量 addLotteryTimes/onAdViewed 触发），
        // 暂停 gwh 调用避免加重封禁、连累其他账号。详见 .trellis/tasks/07-21-lottery-waf-guard/。
        return BaseResult.error("抽奖刷任务服务暂停（账号 WAF 风控规避中）");
    }

    /**
     * 开红包：用攒到的抽奖次数循环执行抽奖，直到抽完或失败。
     */
    @PostMapping("/draw")
    public BaseResult<LotteryDrawResultVO> draw(@RequestParam Integer authId) {
        // 2026-07-21：同 runTask，暂停 gwh 调用（WAF 风控规避中）。
        return BaseResult.error("开红包服务暂停（账号 WAF 风控规避中）");
    }
}
