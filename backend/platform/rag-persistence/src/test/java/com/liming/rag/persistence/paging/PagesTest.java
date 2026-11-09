package com.liming.rag.persistence.paging;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liming.rag.shared.Paged;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PagesTest {

    @Test
    @DisplayName("分页参数沿用归一后的页码和条数；查到的一页换成 Paged，带总数")
    void convertsBothWays() {
        var request = new Paged.Request(0, 500);
        Page<Integer> page = Pages.of(request);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(Paged.MAX_SIZE);

        page.setRecords(List.of(1, 2));
        page.setTotal(42);
        assertThat(Pages.toPaged(page, request, String::valueOf))
                .isEqualTo(new Paged<>(List.of("1", "2"), 42, 1, Paged.MAX_SIZE));
    }
}
