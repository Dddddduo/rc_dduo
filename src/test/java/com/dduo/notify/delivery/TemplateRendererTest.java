package com.dduo.notify.delivery;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateRendererTest {

    private static Map<String, Object> values() {
        Map<String, Object> v = new HashMap<>();
        v.put("userId", "u-100");
        v.put("amount", 42.5);
        v.put("flag", true);
        return v;
    }

    @Test
    void rendersPlaceholders() {
        String out = TemplateRenderer.render("{\"id\":\"{{payload.userId}}\",\"amt\":{{payload.amount}}}", values());
        assertEquals("{\"id\":\"u-100\",\"amt\":42.5}", out);
    }

    @Test
    void rendersRepeatedPlaceholder() {
        assertEquals("u-100/u-100", TemplateRenderer.render("{{payload.userId}}/{{ payload.userId }}", values()));
    }

    @Test
    void nullTemplateReturnsNull() {
        assertEquals(null, TemplateRenderer.render(null, values()));
    }

    @Test
    void missingKeyFailsFast() {
        // 刻意快速失败:把 "null" 字符串发给供应商比报错更难排查
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("{{payload.notExist}}", values()));
    }

    @Test
    void booleanValueRendered() {
        assertEquals("true", TemplateRenderer.render("{{payload.flag}}", values()));
    }
}
