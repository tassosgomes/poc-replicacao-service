package br.org.ecad.poc.replicacao.exportador;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON codec for the dependency-free PoC HTTP adapter (DT-02). */
final class Json {
    private final String input;
    private int position;

    private Json(String input) {
        this.input = input;
    }

    static Object read(String input) {
        Json parser = new Json(input);
        Object value = parser.value();
        parser.space();
        if (parser.position != input.length()) throw new IllegalArgumentException("Invalid JSON response");
        return value;
    }

    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (char ch : text.toCharArray()) {
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (ch < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                        else out.append(ch);
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return "{"
                    + String.join(
                            ",",
                            map.entrySet().stream()
                                    .map(e -> write(e.getKey().toString()) + ":" + write(e.getValue()))
                                    .toList())
                    + "}";
        }
        if (value instanceof List<?> list)
            return "[" + String.join(",", list.stream().map(Json::write).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private Object value() {
        space();
        if (position >= input.length()) throw new IllegalArgumentException("Invalid JSON response");
        char ch = input.charAt(position);
        if (ch == '"') return string();
        if (ch == '{') return object();
        if (ch == '[') return array();
        for (String token : List.of("true", "false", "null")) {
            if (input.startsWith(token, position)) {
                position += token.length();
                return token.equals("null") ? null : Boolean.valueOf(token);
            }
        }
        int start = position;
        while (position < input.length() && "-+0123456789.eE".indexOf(input.charAt(position)) >= 0) position++;
        if (start == position) throw new IllegalArgumentException("Invalid JSON response");
        return new java.math.BigDecimal(input.substring(start, position));
    }

    private Map<String, Object> object() {
        position++;
        Map<String, Object> map = new LinkedHashMap<>();
        space();
        if (take('}')) return map;
        do {
            space();
            String key = string();
            space();
            if (!take(':')) throw new IllegalArgumentException("Invalid JSON response");
            map.put(key, value());
            space();
        } while (take(','));
        if (!take('}')) throw new IllegalArgumentException("Invalid JSON response");
        return map;
    }

    private List<Object> array() {
        position++;
        List<Object> list = new ArrayList<>();
        space();
        if (take(']')) return list;
        do {
            list.add(value());
            space();
        } while (take(','));
        if (!take(']')) throw new IllegalArgumentException("Invalid JSON response");
        return list;
    }

    private String string() {
        if (!take('"')) throw new IllegalArgumentException("Invalid JSON response");
        StringBuilder out = new StringBuilder();
        while (position < input.length()) {
            char ch = input.charAt(position++);
            if (ch == '"') return out.toString();
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            if (position >= input.length()) break;
            char escaped = input.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (position + 4 > input.length()) throw new IllegalArgumentException("Invalid JSON response");
                    out.append((char) Integer.parseInt(input.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Invalid JSON response");
            }
        }
        throw new IllegalArgumentException("Invalid JSON response");
    }

    private void space() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
    }

    private boolean take(char ch) {
        if (position < input.length() && input.charAt(position) == ch) {
            position++;
            return true;
        }
        return false;
    }
}
