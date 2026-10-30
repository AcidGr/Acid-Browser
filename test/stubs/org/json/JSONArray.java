package org.json;

import java.util.ArrayList;
import java.util.List;

public class JSONArray {
    private final List<Object> list = new ArrayList<Object>();

    public JSONArray() {}

    public JSONArray(String json) throws JSONException {
        if (json == null) throw new JSONException("Null JSONArray string");
        JSONArray parsed = new JSONParser(json).parseArray();
        this.list.addAll(parsed.list);
    }

    public int length() { return list.size(); }

    public JSONArray put(boolean value) { list.add(Boolean.valueOf(value)); return this; }
    public JSONArray put(int value) { list.add(Integer.valueOf(value)); return this; }
    public JSONArray put(long value) { list.add(Long.valueOf(value)); return this; }
    public JSONArray put(double value) throws JSONException { list.add(Double.valueOf(value)); return this; }
    public JSONArray put(Object value) { list.add(value != null ? value : JSONObject.NULL); return this; }

    public Object get(int index) throws JSONException {
        if (index < 0 || index >= list.size()) throw new JSONException("Index " + index + " out of range");
        return list.get(index);
    }

    public Object opt(int index) {
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    public String getString(int index) throws JSONException {
        Object v = get(index);
        if (v == JSONObject.NULL) throw new JSONException("Null at " + index);
        return String.valueOf(v);
    }

    public String optString(int index) { return optString(index, ""); }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        return (v == null || v == JSONObject.NULL) ? fallback : String.valueOf(v);
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object v = get(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject at " + index);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    @Override
    public String toString() {
        return writeIndent(0, 0);
    }

    public String toString(int indentSpaces) throws JSONException {
        return writeIndent(indentSpaces, 0);
    }

    String writeIndent(int indentFactor, int depth) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean pretty = indentFactor > 0;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            if (pretty) {
                sb.append('\n');
                JSONParser.appendSpaces(sb, (depth + 1) * indentFactor);
            }
            sb.append(JSONParser.serializeValue(list.get(i), indentFactor, depth + 1));
        }
        if (pretty) {
            sb.append('\n');
            JSONParser.appendSpaces(sb, depth * indentFactor);
        }
        sb.append(']');
        return sb.toString();
    }
}
