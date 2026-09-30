package com.luoye.dao.handler;

import com.pgvector.PGvector;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * pgvector {@code vector} 列与 Java {@code float[]} 的类型转换器。
 *
 * <p>写入时包装为 {@link PGvector}；读取时兼容驱动已注册 vector 类型
 * （返回 PGvector）与未注册（返回通用 PGobject）两种情况。
 * 通过实体字段 {@code @TableField(typeHandler = VectorTypeHandler.class)} 启用。
 */
@MappedTypes(float[].class)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    float[] parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, new PGvector(parameter));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getObject(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getObject(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getObject(columnIndex));
    }

    /**
     * 将 JDBC 返回值统一转为 float[]。
     *
     * <p>驱动已注册 vector 类型时返回 {@link PGvector}；未注册时返回通用
     * PGobject，其 {@code toString()} 即向量文本，统一交给 PGvector 解析。
     *
     * @param value 列值，可能是 PGvector、PGobject 或 null
     * @return 向量数组；输入为 null 时返回 null
     */
    private float[] toArray(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof PGvector vector) {
            return vector.toArray();
        }
        return new PGvector(value.toString()).toArray();
    }
}
