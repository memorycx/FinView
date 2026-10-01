package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.SavingsPlanResponse;
import com.finview.service.SavingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存钱计划接口。context-path 为 /api，故实际路径是 /api/savings/**。
 *
 * GET /savings/plans       计划列表
 * GET /savings/plans/{id}  计划详情（含计划内资产与月度市值走势）
 *
 * 目前只有读接口——前端还没有新建/编辑计划的表单，详情页的「新建计划」会带 id=new 过来，
 * 这里按「计划不存在」返回 404。需要认证，user_id 从 token 解析。
 */
@RestController
@RequestMapping("/savings/plans")
@RequiredArgsConstructor
public class SavingsController {

    private final SavingsService savingsService;

    /** 当前用户的计划列表 */
    @GetMapping
    public Result<List<SavingsPlanResponse>> list(Authentication authentication) {
        return Result.success(savingsService.list(currentUserId(authentication)));
    }

    /** 计划详情，记录不属于当前用户时同样按 404 返回 */
    @GetMapping("/{id}")
    public Result<SavingsPlanResponse> detail(Authentication authentication,
                                              @PathVariable String id) {
        return Result.success(savingsService.getById(currentUserId(authentication), id));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
