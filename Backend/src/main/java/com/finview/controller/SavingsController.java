package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.AssetLinkRequest;
import com.finview.controller.dto.SavingsPlanCandidateResponse;
import com.finview.controller.dto.SavingsPlanRequest;
import com.finview.controller.dto.SavingsPlanResponse;
import com.finview.service.SavingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存钱计划接口。context-path 为 /api，故实际路径是 /api/savings/**。
 *
 * GET  /savings/plans       计划列表
 * GET  /savings/plans/{id}  计划详情（含计划内资产与月度市值走势）
 * POST /savings/plans       新建计划（返回读接口同款的 SavingsPlanResponse，前端拿 id 跳详情）
 *
 * 编辑 / 删除还没做。需要认证，user_id 从 token 解析，不接受前端传入。
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

    /** 新建计划：单字段校验靠 @Valid（见 SavingsPlanRequest），跨字段规则在 service */
    @PostMapping
    public Result<SavingsPlanResponse> create(Authentication authentication,
                                             @Valid @RequestBody SavingsPlanRequest request) {
        return Result.success(savingsService.create(currentUserId(authentication), request));
    }

    /** 计划详情，记录不属于当前用户时同样按 404 返回 */
    @GetMapping("/{id}")
    public Result<SavingsPlanResponse> detail(Authentication authentication,
                                              @PathVariable String id) {
        return Result.success(savingsService.getById(currentUserId(authentication), id));
    }

    /** 「添加资产」面板的候选：该用户全部资产（含现金、含已归档）+ 是否已在本计划里 */
    @GetMapping("/{id}/candidates")
    public Result<List<SavingsPlanCandidateResponse>> candidates(Authentication authentication,
                                                                 @PathVariable String id) {
        return Result.success(savingsService.candidates(currentUserId(authentication), id));
    }

    /**
     * 新建表单用的候选资产（还没有计划 id，linked 恒为 false）。
     *
     * 路径写成 /savings/plans/candidates 而不是 /{id}/candidates：它是字面段，
     * Spring 的路径匹配里字面段优先于 {id} 变量段，不会被详情接口抢走；
     * 「新建」时手里没有计划 id，也没法走 /{id}/candidates。
     */
    @GetMapping("/candidates")
    public Result<List<SavingsPlanCandidateResponse>> candidatesForNew(Authentication authentication) {
        return Result.success(savingsService.candidatesForNew(currentUserId(authentication)));
    }

    /** 给小愿望关联一个资产；总资产计划不用关联（默认全含），传了会 400 */
    @PostMapping("/{id}/assets")
    public Result<SavingsPlanResponse> linkAsset(Authentication authentication,
                                                 @PathVariable String id,
                                                 @Valid @RequestBody AssetLinkRequest request) {
        return Result.success(savingsService.linkAsset(currentUserId(authentication), id,
                request.getAssetId()));
    }

    /** 解除关联，返回更新后的计划详情 */
    @DeleteMapping("/{id}/assets/{assetId}")
    public Result<SavingsPlanResponse> unlinkAsset(Authentication authentication,
                                                   @PathVariable String id,
                                                   @PathVariable String assetId) {
        return Result.success(savingsService.unlinkAsset(currentUserId(authentication), id, assetId));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
