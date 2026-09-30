package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.AppConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 用户配置 Mapper。 */
@Mapper
public interface AppConfigMapper extends BaseMapper<AppConfig> {

    @Select("SELECT * FROM configs WHERE user_id=#{owner}")
    List<AppConfig> selectOwned(@Param("owner") UUID owner);

    @Insert("INSERT INTO configs(id,user_id,config_key,config_value,updated_at) "
            + "VALUES(#{id},#{owner},#{key},CAST(#{value} AS jsonb),now()) "
            + "ON CONFLICT (user_id,config_key) DO UPDATE SET "
            + "config_value=excluded.config_value,updated_at=now()")
    int upsert(@Param("id") UUID id,
               @Param("owner") UUID owner,
               @Param("key") String key,
               @Param("value") String value);
}
