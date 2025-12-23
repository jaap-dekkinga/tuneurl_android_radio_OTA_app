package com.dekidea.tuneurl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class JsonUtils implements Constants{

    public static JsonObject getFingerprintJson(String fingerprint_string){

        JsonObject fingerprint = new JsonObject();

        try {

            JsonParser jsonParser = new JsonParser();
            JsonArray data = (JsonArray) jsonParser.parse("[" + fingerprint_string + "]");

            JsonObject buffer = new JsonObject();

            buffer.addProperty("type", "Buffer");
            buffer.add("data", data);

            fingerprint.add("fingerprint", buffer);
            fingerprint.addProperty("fingerprint_version", "1");
        }
        catch (Exception e){

            e.printStackTrace();
        }

        return fingerprint;
    }
}
