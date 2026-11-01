package com.skylane.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void unexpectedErrorsBecomeA500WithTheUnifiedBody() {
        var res = new ApiExceptionHandler().handleUnexpected(new IllegalStateException("db exploded"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().toString()).contains("INTERNAL_ERROR").doesNotContain("db exploded");
    }
}
