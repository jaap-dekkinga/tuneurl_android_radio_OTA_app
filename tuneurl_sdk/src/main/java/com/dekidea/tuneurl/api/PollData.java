package com.dekidea.tuneurl.api;

import com.google.gson.annotations.SerializedName;

public class PollData {

    @SerializedName("Name")
    private String name;

    @SerializedName("Response")
    private String response;

    @SerializedName("ResponseTime")
    private String responseTime;

    public PollData(){};

    public PollData(String name, String response, String responseTime){

        this.name = name;
        this.response = response;
        this.responseTime = responseTime;
    };

    public void setName(String name){

        this.name = name;
    }

    public void setResponse(String response){

        this.response = response;
    }

    public void setResponseTime(String responseTime){

        this.responseTime = responseTime;
    }

    public String getName(){

        return this.name;
    }

    public String getResponse(){

        return this.response;
    }

    public String getResponseTime(){

        return this.responseTime;
    }
}
