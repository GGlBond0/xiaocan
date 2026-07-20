package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.vo.LotteryDrawResultVO;
import io.github.xiaocan.model.vo.LotteryStepPrizeResultVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 霸王餐刷浏览任务控制器（三个独立按钮：刷任务/开红包/领累计奖励）
 */
@Slf4j
@RestController
@RequestMapping("/api/lottery")
public class LotteryController {

    @Resource
    private LotteryService lotteryService;

    /**
     * 刷任务：完成未完成的浏览类任务，攒抽奖机会（账号内任务间 40s 间隔降风控）。
     */
    @PostMapping("/run")
    public BaseResult<LotteryTaskResultVO> runTask(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.runTask(authId));
    }

    /**
     * 开红包：用攒到的抽奖次数循环执行抽奖，直到抽完或失败（每次抽奖间 10s 间隔）。
     */
    @PostMapping("/draw")
    public BaseResult<LotteryDrawResultVO> draw(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.draw(authId));
    }

    /**
     * 领累计奖励：领取累计抽奖阶梯奖 step1/step2（step 间 10s 间隔）。
     */
    @PostMapping("/claim-step")
    public BaseResult<LotteryStepPrizeResultVO> claimStep(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.claimStep(authId));
    }
}
