package com.finview.controller;

import com.finview.common.Result;
import com.finview.entity.Fund;
import com.finview.service.FundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class FundController {

    private final FundService fundService;

    /** GET /funds?status=active|archived|all */
    @GetMapping
    public Result<List<Fund>> list(@RequestParam(name = "status", required = false) String status) {
        System.out.println("status:" + status);
        return Result.success(fundService.list(status));
    }

    /** GET /funds/leaderboard —— 静态路由，须优先于 /funds/{id} 匹配 */
    @GetMapping("/leaderboard")
    public Result<List<Fund>> leaderboard() {
        return Result.success(fundService.leaderboard());
    }

    /** GET /funds/{id} */
    @GetMapping("/{id}")
    public Result<Fund> detail(@PathVariable String id) {
        return Result.success(fundService.getById(id));
    }
}
