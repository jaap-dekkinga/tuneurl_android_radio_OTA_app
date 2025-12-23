package com.dekidea.tuneurl.api;

public class CYOA {

    private long tuneurl_id;
    private String option;
    private String mp3_url;

    public CYOA(long tuneurl_id, String option, String mp3_url) {

        this.tuneurl_id = tuneurl_id;
        this.option = option;
        this.mp3_url = mp3_url;
    }

    public long getTuneurlId(){

        return tuneurl_id;
    }

    public String getOption(){

        return option;
    }

    public String getMp3Url(){

        return mp3_url;
    }
}
