package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    private final Map<String, Object> map = new LinkedHashMap<String, Object>();

    public JSONObject() {}

    public JSONObject(String json) throws JSONException {
        if (json == null) throw new JSONException("Null JSON string");
        JSONObject parsed = new JSONParser(json).parseObject();
        this.map.putAll(parsed.map);
    }

    void putRaw(String key, Object val) {
        map.put(key, val);
    }

    public int length() { return map.size(); }

    public Iterator<String> keys() { return map.keySet().iterator(); }

    public boolean has(String name) {
        return map.containsKey(name) && map.get(name) != NULL;
    }

    public boolean isNull(String name) {
        Object v = map.get(name);
        return v == null || v == NULL;
    }

    public Object remove(String name) {
        return map.remove(name);
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, Boolean.valueOf(value));
        return this;
    }

    public JSONObject put(String name, int value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, Integer.valueOf(value));
        return this;
    }

    public JSONObject put(String name, long value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, Long.valueOf(value));
        return this;
    }

    public JSONObject put(String name, double value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, Double.valueOf(value));
        return this;
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        if (value == null) {
            map.remove(name);
        } else {
            map.put(name, value);
        }
        return this;
    }

    public Object get(String name) throws JSONException {
        Object v = map.get(name);
        if (v == null) throw new JSONException("No value for " + name);
        return v;
    }

    public Object opt(String name) {
        return map.get(name);
    }

    public String getString(String name) throws JSONException {
        Object v = get(name);
        if (v == NULL) throw new JSONException("Null value for " + name);
        return String.valueOf(v);
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return (v == null || v == NULL) ? fallback : String.valueOf(v);
    }

    public boolean getBoolean(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) {
            if ("true".equalsIgnoreCase((String) v)) return true;
            if ("false".equalsIgnoreCase((String) v)) return false;
        }
        throw new JSONException("Not a boolean: " + name);
    }

    public boolean optBoolean(String name) {
        return optBoolean(name, false);
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) {
            if ("true".equalsIgnoreCase((String) v)) return true;
            if ("false".equalsIgnoreCase((String) v)) return false;
        }
        return fallback;
    }

    public int getInt(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception e) { throw new JSONException("Not an int: " + name); }
    }

    public int optInt(String name) { return optInt(name, 0); }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception ignored) {}
        }
        return fallback;
    }

    public long getLong(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); }
        catch (Exception e) { throw new JSONException("Not a long: " + name); }
    }

    public long optLong(String name) { return optLong(name, 0L); }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (Exception ignored) {}
        }
        return fallback;
    }

    public double optDouble(String name, double fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Exception ignored) {}
        }
        return fallback;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject: " + name);
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not a JSONArray: " + name);
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public static String quote(String data) {
        if (data == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    @Override
    public String toString() {
        return writeIndent(0, 0);
    }

    public String toString(int indentSpaces) throws JSONException {
        return writeIndent(indentSpaces, 0);
    }

    String writeIndent(int indentFactor, int depth) {
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean pretty = indentFactor > 0;
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (i++ > 0) sb.append(',');
            if (pretty) {
                sb.append('\n');
                JSONParser.appendSpaces(sb, (depth + 1) * indentFactor);
            }
            sb.append(quote(e.getKey())).append(pretty ? ": " : ":");
            sb.append(JSONParser.serializeValue(e.getValue(), indentFactor, depth + 1));
        }
        if (pretty) {
            sb.append('\n');
            JSONParser.appendSpaces(sb, depth * indentFactor);
        }
        sb.append('}');
        return sb.toString();
    }
}
