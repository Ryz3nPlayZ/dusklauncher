package dev.dusk.client.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The game's Gson (2.2.4) has no generic map reading worth trusting, so JSON is turned into plain values here. */
public final class Json {
    private Json() {}

    public static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, JsonElement> en : ((JsonObject) e).entrySet()) m.put(en.getKey(), plain(en.getValue()));
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<Object>();
            for (JsonElement c : (JsonArray) e) l.add(plain(c));
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }
}
