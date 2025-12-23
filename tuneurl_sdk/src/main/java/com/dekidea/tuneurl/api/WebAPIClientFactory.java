package com.dekidea.tuneurl.api;

import android.content.Context;

public class WebAPIClientFactory {

    private static WebAPIClient webAPIClient;

    public static WebAPIClient getWebAPIClient(Context context){

        if(webAPIClient == null){

            webAPIClient = new WebAPIClient(context);
        }

        return webAPIClient;
    }
}
