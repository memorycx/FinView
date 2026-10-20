package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 存钱计划的类型（内置 + 用户自定义），对应前端 types.ts 的 SavingsPlanTypeItem。
 *
 * 内置三种（asset 总资产 / life 生活保障 / wish 愿望）由后端在首次访问时幂等补种；
 * 自定义类型由用户在新建计划页里建。计划的 `type` 存的是这里的 `code`，
 * 所以改名只改 name、不用碰任何计划。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsPlanTypeResponse {

    /** 类型id，字符串形式（与计划 id 一致，前端进路由 / 请求参数用） */
    private String id;

    /** 类型标识：内置 asset/life/wish，自定义 c+随机串；计划响应的 type 与之对应 */
    private String code;

    /** 类型名称（显示用，可改） */
    private String name;

    /** 是不是内置类型（asset/life/wish）；前端据此显示「内置」标记 */
    private boolean builtin;

    /** 系统类型（只有 asset 为 true）：不能改名、不能删除 */
    private boolean system;
}
