package com.finview.controller;

import com.finview.common.Result;
import com.finview.entity.AssetSummary;
import com.finview.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /** GET /assets/summary */
    @GetMapping("/summary")
    public Result<AssetSummary> summary() {
        return Result.success(assetService.summary());
    }

    /** GET /assets/distribution 或 GET /assets/distribution?dim=industry */
    @GetMapping("/distribution")
    public Result<Object> distribution(@RequestParam(name = "dim", required = false) String dim) {
        return Result.success(assetService.distribution(dim));
    }
}
