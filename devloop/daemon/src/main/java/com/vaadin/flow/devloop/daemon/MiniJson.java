/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.devloop.daemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete but minimal JSON reader - objects become {@link LinkedHashMap},
 * arrays {@link ArrayList}, numbers {@link Double} or {@link Long}. The daemon
 * stays JDK-only, and {@link Json#stringField} is not enough for a request
 * whose payload is a whole source file that may itself contain the key being
 * looked for.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object value = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String json) {
        Object parsed = parse(json);
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("not a JSON object");
    }

    private Object value() {
        ws();
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(i);
        return switch (c) {
        case '{' -> obj();
        case '[' -> arr();
        case '"' -> str();
        case 't' -> lit("true", Boolean.TRUE);
        case 'f' -> lit("false", Boolean.FALSE);
        case 'n' -> lit("null", null);
        default -> num();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return map;
        }
        while (true) {
            ws();
            String key = str();
            ws();
            expect(':');
            map.put(key, value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> arr() {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            list.add(value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String str() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                i += 4;
            }
            default -> sb.append(e);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Object num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("unexpected char at " + start);
        }
        return n.contains(".") || n.contains("e") || n.contains("E")
                ? (Object) Double.parseDouble(n)
                : (Object) Long.parseLong(n);
    }

    private Object lit(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("bad literal at " + i);
        }
        i += word.length();
        return value;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("expected " + c + " at " + i);
        }
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
