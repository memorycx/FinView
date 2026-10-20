package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.PanoramaResponse;
import com.finview.service.PanoramaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「资金全景」（3D 塔视图）接口。context-path 为 /api，故实际路径是 /api/visualization/panorama。
 *
 * GET /visualization/panorama  按展示桶分组的资产 + 未来 12 个自然月的累计投入（「演变」动画的数据）
 *
 * 只读；需要认证，user_id 从 token 解析。
 */
@RestController
@RequestMapping("/visualization")
@RequiredArgsConstructor
public class PanoramaController {

    private final PanoramaService panoramaService;

    /** 资金全景：桶 / 桶内资产 / 每只资产的逐月累计投入，口径见 {@link PanoramaService} */
    @GetMapping("/panorama")
    public Result<PanoramaResponse> panorama(Authentication authentication) {
        return Result.success(panoramaService.panorama(currentUserId(authentication)));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
