package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 存钱计划的类型（内置 + 用户自定义），对应 `saving_plan_types` 表（2026-10-13 加）。
 *
 * 拆成两层是为了「改名不碰计划」：`code` 是稳定标识（计划表 `saving_plans.type` 存的就是它），
 * `name` 只是显示名、随时可改。内置三种 code = asset / life / wish，
 * 由 SavingsService 首次访问时幂等补种；自定义类型的 code = 'c' + 随机串。
 * 只有 asset 是系统类型（不能改名 / 删除）。
 */
@Data
@NoArgsConstructor
public class SavingPlanType {

    /** 自增主键 */
    private Long id;

    /** 所属用户id */
    private Long userId;

    /** 类型标识：内置 asset / life / wish，自定义 c+随机串；saving_plans.type 与之对应 */
    private String code;

    /** 类型名称（显示用，可改） */
    private String name;

    /** 排序：内置 0/1/2，自定义往后排 */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
