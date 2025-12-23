package com.dekidea.tuneurl.api;

public class APIResult {

    private APIData data;

    // --- CONSTRUCTORS ---

    public APIResult() { }

    public APIResult(APIData data) {

        this.data = data;
    }

    // --- GETTER ---

    public APIData getData() { return data; }

    // --- SETTER ---

    public void setData(APIData data) { this.data = data; }
}
