package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.SavingsPlanTypeRequest;
import com.finview.controller.dto.SavingsPlanTypeResponse;
import com.finview.service.SavingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存钱计划的类型接口（内置 + 用户自定义）。context-path 为 /api，故实际路径是 /api/savings/plan-types/**。
 *
 * GET    /savings/plan-types       类型列表（内置 asset/life/wish 幂等补种后返回）
 * POST   /savings/plan-types       新建自定义类型
 * PUT    /savings/plan-types/{id}  改类型名（只改显示名，计划里存的 code 不变）
 * DELETE /savings/plan-types/{id}  删除类型（系统类型 asset 不能删；还有计划在用也不能删）
 *
 * 单独一个控制器而不是塞进 SavingsController：那边类级 @RequestMapping 是 /savings/plans，
 * 路径对不上（/savings/plan-types 是另一个前缀）。
 * 需要认证，user_id 从 token 解析，不接受前端传入。
 */
@RestController
@RequestMapping("/savings/plan-types")
@RequiredArgsConstructor
public class SavingsPlanTypeController {

    private final SavingsService savingsService;

    /** 类型列表，新建计划的类型选择用它 */
    @GetMapping
    public Result<List<SavingsPlanTypeResponse>> list(Authentication authentication) {
        return Result.success(savingsService.listTypes(currentUserId(authentication)));
    }

    /** 新建自定义类型：单字段校验（非空、长度）靠 @Valid，重名规则在 service */
    @PostMapping
    public Result<SavingsPlanTypeResponse> create(Authentication authentication,
                                                 @Valid @RequestBody SavingsPlanTypeRequest request) {
        return Result.success(savingsService.createType(currentUserId(authentication), request.getName()));
    }

    /** 改类型名；总资产类型是系统类型，不能改名 */
    @PutMapping("/{id}")
    public Result<SavingsPlanTypeResponse> rename(Authentication authentication,
                                                 @PathVariable String id,
                                                 @Valid @RequestBody SavingsPlanTypeRequest request) {
        return Result.success(savingsService.renameType(currentUserId(authentication), id, request.getName()));
    }

    /** 删除类型；总资产类型不能删，还有计划在用也不能删 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(Authentication authentication, @PathVariable String id) {
        savingsService.deleteType(currentUserId(authentication), id);
        return Result.success(null);
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
