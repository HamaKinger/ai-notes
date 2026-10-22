package com.ainotes.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 分页响应包装,避免把 Spring Data 的 Page 直接序列化给前端。
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
