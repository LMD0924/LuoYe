package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.MemorySuppression;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 遗忘抑制 Mapper。
 *
 * <p>只存被删除/纠正事实的正文哈希，不保存敏感原文。
 */
@Mapper
public interface MemorySuppressionMapper extends BaseMapper<MemorySuppression> {

    /**
     * @param hash 事实正文哈希
     * @return 用户是否曾删除或纠正过该事实；隐式抽取不得恢复它
     */
    @Select("SELECT count(*) FROM memory_suppression "
            + "WHERE user_id=#{owner} AND content_hash=#{hash}")
    long countSuppressed(@Param("owner") UUID owner, @Param("hash") String hash);

    /**
     * 登记抑制哈希，重复登记由 ON CONFLICT 忽略。
     */
    @Insert("INSERT INTO memory_suppression(user_id,content_hash) VALUES(#{owner},#{hash}) "
            + "ON CONFLICT DO NOTHING")
    int suppress(@Param("owner") UUID owner, @Param("hash") String hash);
}
