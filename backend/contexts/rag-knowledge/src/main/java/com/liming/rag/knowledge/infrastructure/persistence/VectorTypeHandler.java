package com.liming.rag.knowledge.infrastructure.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * float[] 与 pgvector 的 vector 类型互转。写入用文本格式 {@code [0.1,0.2,…]}，避免引入额外依赖。
 */
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    /** 转成 pgvector 文本格式，也供检索 SQL 传参使用。 */
    public static String format(float[] vector) {
        var text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, float[] vector, JdbcType jdbcType)
            throws SQLException {
        var value = new PGobject();
        value.setType("vector");
        value.setValue(format(vector));
        statement.setObject(index, value);
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, String column) throws SQLException {
        return parse(resultSet.getString(column));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, int index) throws SQLException {
        return parse(resultSet.getString(index));
    }

    @Override
    public float[] getNullableResult(CallableStatement statement, int index) throws SQLException {
        return parse(statement.getString(index));
    }

    private static float[] parse(String text) {
        if (text == null) {
            return null;
        }
        var parts = text.substring(1, text.length() - 1).split(",");
        var vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
