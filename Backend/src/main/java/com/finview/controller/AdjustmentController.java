package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.AdjustmentRequest;
import com.finview.entity.Adjustment;
import com.finview.service.AdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 资产调整记录接口。context-path 为 /api，故实际路径是 /api/adjustments。
 *
 * POST   /adjustments              新建（前端 EntryView 的录入表单走这条）
 * GET    /adjustments              列表，支持 code / action / startDate / endDate 过滤，按日期倒序
 * GET    /adjustments/{id}         详情
 * PUT    /adjustments/{id}         整条更新
 * DELETE /adjustments/{id}         删除
 *
 * 全部需要认证（SecurityConfig 里 anyRequest().authenticated()），
 * 记录归属由后端从 token 解析，前端不传 userId。
 */
@RestController
@RequestMapping("/adjustments")
@RequiredArgsConstructor
public class AdjustmentController {

    private final AdjustmentService adjustmentService;


    /** 新建调整记录，返回落库后的记录（含 adjustmentId / userId） */
    @PostMapping
    public Result<Adjustment> create(Authentication authentication,
                                     @Valid @RequestBody AdjustmentRequest request) {
        return Result.success(adjustmentService.create(currentUserId(authentication), request));
    }

    /**
     * 当前用户的调整记录列表。
     * code / action / startDate / endDate 均可选，日期用 ISO 格式，如 startDate=2024-01-01。
     */
    @GetMapping
    public Result<List<Adjustment>> list(
            Authentication authentication,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) Integer action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(adjustmentService.list(
                currentUserId(authentication), code, action, startDate, endDate));
    }

    /** 查单条记录，记录不属于当前用户时同样按 404 返回 */
    @GetMapping("/{adjustmentId}")
    public Result<Adjustment> detail(Authentication authentication,
                                     @PathVariable Long adjustmentId) {
        return Result.success(adjustmentService.getById(currentUserId(authentication), adjustmentId));
    }

    /** 整条更新，请求体结构与新建一致（未传的可选字段会被置空） */
    @PutMapping("/{adjustmentId}")
    public Result<Adjustment> update(Authentication authentication,
                                     @PathVariable Long adjustmentId,
                                     @Valid @RequestBody AdjustmentRequest request) {
        return Result.success(adjustmentService.update(
                currentUserId(authentication), adjustmentId, request));
    }

    /** 删除记录，成功时 data 为 null */
    @DeleteMapping("/{adjustmentId}")
    public Result<Void> delete(Authentication authentication,
                               @PathVariable Long adjustmentId) {
        adjustmentService.delete(currentUserId(authentication), adjustmentId);
        return Result.success(null);
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
