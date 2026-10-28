package com.liusongboyy.chatdata.common.core.result;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 分页响应
 */
@Data
public class PageResult<T> implements Serializable {
    private Integer code = 200;
    private String message = "success";
    private List<T> data;
    private long total;
    private long page;

    private long pageSize;

    public static <T> PageResult<T> of(List<T> data, long total, long page, long pageSize) {
        PageResult<T> result = new PageResult<>();
        result.data = data;
        result.total = total;
        result.page = page;
        result.pageSize = pageSize;
        return result;
    }
}
