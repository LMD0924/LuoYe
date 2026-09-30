package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.ToolCall;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/** 工具调用审计 Mapper。 */
@Mapper
public interface ToolCallMapper extends BaseMapper<ToolCall> {

    @Insert("INSERT INTO tool_calls(id,user_id,session_id,tool_name,input,output,status,"
            + "latency_ms,called_at) VALUES(#{id},#{owner},#{sessionId},#{tool},"
            + "CAST(#{input} AS jsonb),CAST(#{output} AS jsonb),#{status},#{latency},now())")
    int insertLog(@Param("id") UUID id,
                  @Param("owner") UUID owner,
                  @Param("sessionId") UUID sessionId,
                  @Param("tool") String tool,
                  @Param("input") String input,
                  @Param("output") String output,
                  @Param("status") String status,
                  @Param("latency") Integer latency);
}
