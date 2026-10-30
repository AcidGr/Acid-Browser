package org.json;

final class JSONParser {
    private final String src;
    private int pos = 0;

    JSONParser(String src) {
        this.src = src != null ? src : "";
    }

    Object nextValue() throws JSONException {
        skipWs();
        if (pos >= src.length()) throw new JSONException("Unexpected end of JSON");
        char c = src.charAt(pos);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"' || c == '\'') return parseString(c);
        return parseLiteralOrNumber();
    }

    JSONObject parseObject() throws JSONException {
        expect('{');
        JSONObject obj = new JSONObject();
        skipWs();
        if (peek('}')) {
            pos++;
            return obj;
        }
        while (true) {
            skipWs();
            if (pos >= src.length()) throw new JSONException("Unterminated JSONObject");
            char q = src.charAt(pos);
            if (q != '"' && q != '\'') throw new JSONException("Expected key string at " + pos);
            String key = parseString(q);
            skipWs();
            expect(':');
            Object val = nextValue();
            obj.putRaw(key, val);
            skipWs();
            if (peek('}')) {
                pos++;
                return obj;
            }
            expect(',');
            skipWs();
            if (peek('}')) {
                pos++;
                return obj;
            }
        }
    }

    JSONArray parseArray() throws JSONException {
        expect('[');
        JSONArray arr = new JSONArray();
        skipWs();
        if (peek(']')) {
            pos++;
            return arr;
        }
        while (true) {
            Object val = nextValue();
            arr.put(val);
            skipWs();
            if (peek(']')) {
                pos++;
                return arr;
            }
            expect(',');
            skipWs();
            if (peek(']')) {
                pos++;
                return arr;
            }
        }
    }

    private String parseString(char quote) throws JSONException {
        expect(quote);
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == quote) return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new JSONException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw new JSONException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        throw new JSONException("Unterminated string");
    }

    private Object parseLiteralOrNumber() throws JSONException {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (",]} \t\r\n".indexOf(c) >= 0) break;
            pos++;
        }
        String tok = src.substring(start, pos).trim();
        if (tok.isEmpty()) throw new JSONException("Empty token at " + start);
        if ("true".equalsIgnoreCase(tok)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(tok)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(tok)) return JSONObject.NULL;
        try {
            if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) {
                return Double.valueOf(tok);
            }
            long lv = Long.parseLong(tok);
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) lv);
            }
            return Long.valueOf(lv);
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid value: " + tok);
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char expected) {
        return pos < src.length() && src.charAt(pos) == expected;
    }

    private void expect(char expected) throws JSONException {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != expected) {
            throw new JSONException("Expected '" + expected + "' at " + pos);
        }
        pos++;
    }

    static String serializeValue(Object v, int indentFactor, int depth) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) return ((JSONObject) v).writeIndent(indentFactor, depth);
        if (v instanceof JSONArray) return ((JSONArray) v).writeIndent(indentFactor, depth);
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        return JSONObject.quote(String.valueOf(v));
    }

    static void appendSpaces(StringBuilder sb, int count) {
        for (int i = 0; i < count; i++) sb.append(' ');
    }
}
