package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.FundResponse;
import com.finview.service.FundService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定投计划接口。context-path 为 /api，故实际路径是 /api/funds。
 *
 * GET /funds?status=active|archived|all   列表，缺省 all；active = 未归档（持有中），archived = 已归档
 * GET /funds/{id}                          详情，id 就是资产编码
 * GET /funds/leaderboard                   收益排行榜，按收益率降序（含已归档）
 * POST /funds/{id}/series                  重新生成该计划的每日序列（资产总览/走势图的数据源）
 * POST /funds/series                       一键更新全部计划的每日序列（组合总览页的按钮）
 *
 * 计划本身不落表，是 FundService 把 adjustments 事件流 fold 出来的，所以除最后一个外都是读接口；
 * 增删改都走 AdjustmentController，写完之后由 AdjustmentService 刷新 asset 表快照。
 *
 * 全部需要认证（SecurityConfig 里 anyRequest().authenticated()），
 * 计划归属由后端从 token 解析，前端不传 userId。
 */
@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class FundController {

    private final FundService fundService;

    /** 当前用户的定投计划列表，status 可选 */
    @GetMapping
    public Result<List<FundResponse>> list(Authentication authentication,
                                          @RequestParam(required = false) String status) {
        return Result.success(fundService.list(currentUserId(authentication), status));
    }

    /**
     * 计划详情，id 是资产编码（FundResponse 的 id 与 code 同值）。
     * /funds/leaderboard 不会误进这里：Spring MVC 的路径匹配中字面量段优先于路径变量，
     * 前端 mock 那句「静态路由必须排在动态路由之前」只是 mock 自研路由器的限制。
     */
    @GetMapping("/{id}")
    public Result<FundResponse> detail(Authentication authentication,
                                      @PathVariable String id) {
        return Result.success(fundService.getById(currentUserId(authentication), id));
    }

    /** 收益排行榜，含已归档计划，前端不做排序 */
    @GetMapping("/leaderboard")
    public Result<List<FundResponse>> leaderboard(Authentication authentication) {
        return Result.success(fundService.leaderboard(currentUserId(authentication)));
    }

    /**
     * 重新生成该计划的每日序列（定投详情页的「生成序列」按钮），返回生成后的行数。
     * 整段重算 asset_series（净值由后台异步补，不阻塞这次响应），
     * 返回 0 表示该 code 没有调整记录、没有可生成的序列。
     */
    @PostMapping("/{id}/series")
    public Result<Integer> generateSeries(Authentication authentication,
                                         @PathVariable String id) {
        return Result.success(fundService.generateSeries(currentUserId(authentication), id));
    }

    /**
     * 一键更新全部计划的每日序列（组合总览页的按钮），返回更新的资产数（含已归档，不含现金）。
     * 路由不冲突：/funds/series 是两段字面量路径，/{id}/series 是三段，段数与优先级都不同。
     */
    @PostMapping("/series")
    public Result<Integer> generateAllSeries(Authentication authentication) {
        return Result.success(fundService.generateAllSeries(currentUserId(authentication)));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
