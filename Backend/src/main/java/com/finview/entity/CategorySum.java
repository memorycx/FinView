package com.finview.entity;

import lombok.Data;

/** 按资产大类汇总市值的查询结果行 */
@Data
public class CategorySum {

    /** fund | stock | bond | cash */
    private String category;

    /** 该大类当前市值合计（元） */
    private Long value;
}
