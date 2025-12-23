package com.dekidea.tuneurl.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.Url;


public interface WebAPI {

    @POST
    Call<JsonObject> postPollAnswer(@Url String url, @Body PollData pollData);

    @POST
    Call<JsonObject> addRecordOfInterest(@Url String url, @Body JsonArray records);

    @POST
    Call<JsonArray> searchFingerprint(@Url String url, @Body JsonObject fingerprint);

    @GET
    Call<JsonArray> getCYOA(@Url String url, @Query("tuneurl_id") String tuneurl_id);
}
