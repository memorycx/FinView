package com.finview.mapper;

import com.finview.entity.CategorySum;
import com.finview.entity.DistributionItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AssetMapper {

    /** 仅汇总进行中持仓，按大类累加当前市值 */
    List<CategorySum> selectActiveCategorySums();

    /** 按维度查询分布项（industry / region / currency），按 sort_order 正序 */
    List<DistributionItem> selectDistribution(@Param("dim") String dim);
}
