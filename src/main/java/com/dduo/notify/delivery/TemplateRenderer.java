package com.dduo.notify.delivery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板渲染器:把 {{payload.xxx}} 占位符替换为业务负载中的字段值。
 *
 * 刻意不支持循环/条件等模板语法——供应商适配只需要"取字段填进去",
 * 引入完整模板引擎(如 Velocity)会带来注入面与依赖成本,MVP 不值得。
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*payload\\.([A-Za-z0-9_.\\-\\[\\]]+)\\s*}}");

    private TemplateRenderer() {
    }

    /**
     * @param template 含 {{payload.xxx}} 占位符的模板
     * @param values   业务负载扁平化后的 key-value(嵌套对象用点号路径,如 user.id)
     * @return 渲染结果;模板中引用了 values 里不存在的 key 时抛 IllegalArgumentException(快速失败,避免把 "null" 发给供应商)
     */
    public static String render(String template, Map<String, Object> values) {
        if (template == null) {
            return null;
        }
        Map<String, Object> safeValues = values == null ? Collections.<String, Object>emptyMap() : values;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            if (!safeValues.containsKey(key)) {
                throw new IllegalArgumentException("placeholder not found in payload: payload." + key);
            }
            String value = String.valueOf(safeValues.get(key));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** header 渲染:对值做同样的模板替换 */
    public static Map<String, String> renderHeaders(Map<String, String> headers, Map<String, Object> values) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> rendered.put(k, render(v, values)));
        }
        return rendered;
    }
}
