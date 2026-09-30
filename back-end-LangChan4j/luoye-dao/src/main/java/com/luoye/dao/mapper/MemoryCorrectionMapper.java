package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.MemoryCorrection;
import org.apache.ibatis.annotations.Mapper;

/**
 * 记忆纠偏记录 Mapper。
 *
 * <p>纠偏留痕统一通过 {@link BaseMapper#insert} 写入；
 * 清理随 LongTermMemoryMapper 的 stale 物理删除一并完成。
 */
@Mapper
public interface MemoryCorrectionMapper extends BaseMapper<MemoryCorrection> {
}
