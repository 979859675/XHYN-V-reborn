// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader and writer. Objects are Maps, arrays are Lists, numbers are Long or Double. */
final class Json {
    private final String text;
    private int at;

    private Json(String text) { this.text = text; }

    static Object parse(String text) {
        Json reader = new Json(text);
        Object value = reader.value(0);
        reader.space();
        if (reader.at != text.length()) throw reader.bad();
        return value;
    }

    /** Parses a document whose top level must be an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) value;
    }

    private IllegalArgumentException bad() { return new IllegalArgumentException("Invalid JSON at " + at); }

    private void space() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
    }

    private Object value(int depth) {
        if (depth > 32) throw bad();
        space();
        if (at >= text.length()) throw bad();
        char c = text.charAt(at);
        if (c == '{') {
            at++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            space();
            if (peek('}')) { at++; return map; }
            while (true) {
                space();
                if (!peek('"')) throw bad();
                String key = string();
                space();
                expect(':');
                map.put(key, value(depth + 1));
                space();
                if (peek(',')) { at++; continue; }
                expect('}');
                return map;
            }
        }
        if (c == '[') {
            at++;
            List<Object> list = new ArrayList<Object>();
            space();
            if (peek(']')) { at++; return list; }
            while (true) {
                list.add(value(depth + 1));
                space();
                if (peek(',')) { at++; continue; }
                expect(']');
                return list;
            }
        }
        if (c == '"') return string();
        if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (text.startsWith("null", at)) { at += 4; return null; }
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw bad();
        String number = text.substring(start, at);
        try {
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) return Long.valueOf(number);
            return Double.valueOf(number);
        } catch (NumberFormatException e) {
            at = start;
            throw bad();
        }
    }

    private boolean peek(char c) { return at < text.length() && text.charAt(at) == c; }

    private void expect(char c) {
        if (!peek(c)) throw bad();
        at++;
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw bad();
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw bad();
            char e = text.charAt(at++);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (at + 4 > text.length()) throw bad();
                    try { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                    catch (NumberFormatException x) { throw bad(); }
                    at += 4;
                    break;
                case '"': case '\\': case '/': out.append(e); break;
                default: throw bad();
            }
        }
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            out.append(String.valueOf(value));
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                write(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            String s = value.toString();
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c == '\n') out.append("\\n");
                else if (c == '\r') out.append("\\r");
                else if (c == '\t') out.append("\\t");
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            out.append('"');
        }
    }

    /** Builds an object from alternating keys and values. */
    static Map<String, Object> of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> from, String key) {
        Object value = from == null ? null : from.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> from, String key) {
        Object value = from == null ? null : from.get(key);
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    static String string(Map<String, Object> from, String key) {
        Object value = from == null ? null : from.get(key);
        return value instanceof String ? (String) value : "";
    }

    static long number(Map<String, Object> from, String key) {
        Object value = from == null ? null : from.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    static boolean flag(Map<String, Object> from, String key) {
        return from != null && Boolean.TRUE.equals(from.get(key));
    }
}
