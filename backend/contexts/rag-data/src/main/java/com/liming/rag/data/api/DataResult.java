package com.liming.rag.data.api;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 一次数据查询的结果。只有 {@link Status#OK} 才有数据和出处；拒绝和失败时 {@code message} 是给用户看的说明，
 * 调用方如实告诉用户“无权查看”“暂时查不到”，不编造数据、不用旧数据（规范第 6 节）。
 *
 * @param rows      结果行，已去掉范围外的行
 * @param system    来源系统（{@code source.system}）
 * @param dataset   数据集或接口名（{@code source.dataset}）
 * @param query     客户系统实际执行的条件
 * @param asOf      数据截至时间
 * @param queriedAt 客户系统执行查询的时间
 */
public record DataResult(Status status, String message, List<Map<String, Object>> rows, String system, String dataset,
        Map<String, Object> query, @Nullable OffsetDateTime asOf, @Nullable OffsetDateTime queriedAt,
        boolean truncated) {

    public enum Status {
        OK,
        /** 没有工具授权或参数里的站点、区域超出范围，没有发出请求。 */
        DENIED,
        /** 超时、被拒、限流、工具报错、协议错误或返回格式不符。 */
        FAILED
    }

    public DataResult {
        rows = List.copyOf(rows);
        query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
    }

    public static DataResult denied(String message) {
        return new DataResult(Status.DENIED, message, List.of(), "", "", Map.of(), null, null, false);
    }

    public static DataResult failed(String message) {
        return new DataResult(Status.FAILED, message, List.of(), "", "", Map.of(), null, null, false);
    }
}
