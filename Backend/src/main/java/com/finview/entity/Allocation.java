package com.finview.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 资产大类配置项 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Allocation {

    /** fund | stock | bond | cash */
    private String category;

    /** 大类中文名：基金 / 股票 / 债券 / 现金 */
    private String label;

    /** 当前市值（元） */
    private long value;
}
