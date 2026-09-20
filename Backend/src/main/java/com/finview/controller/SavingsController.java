package com.finview.controller;

import com.finview.common.Result;
import com.finview.entity.SavingsPlan;
import com.finview.service.SavingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/savings/plans")
@RequiredArgsConstructor
public class SavingsController {

    private final SavingsService savingsService;

    /** GET /savings/plans */
    @GetMapping
    public Result<List<SavingsPlan>> list() {
        return Result.success(savingsService.list());
    }

    /** GET /savings/plans/{id} */
    @GetMapping("/{id}")
    public Result<SavingsPlan> detail(@PathVariable String id) {
        return Result.success(savingsService.getById(id));
    }
}
