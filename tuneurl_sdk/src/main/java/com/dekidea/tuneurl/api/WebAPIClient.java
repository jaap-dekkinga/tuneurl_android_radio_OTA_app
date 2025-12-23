package com.dekidea.tuneurl.api;

import android.content.Context;
import android.content.Intent;

import com.dekidea.tuneurl.util.Constants;
import com.dekidea.tuneurl.util.TuneURLManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class WebAPIClient implements Constants {

    private final WebAPI webservice;
    private final ExecutorService executor;
    private String TUNEURL_API_BASE_URL;
    private String SEARCH_FINGERPRINT_URL;
    private String POLL_API_URL;
    private String INTERESTS_API_URL;
    private String GET_CYOA_API_URL;


    public WebAPIClient(Context context) {

        TUNEURL_API_BASE_URL = TuneURLManager.fetchStringSetting(context, SETTING_TUNEURL_API_BASE_URL, "");
        SEARCH_FINGERPRINT_URL = TuneURLManager.fetchStringSetting(context, SETTING_SEARCH_FINGERPRINT_URL, "");
        POLL_API_URL = TuneURLManager.fetchStringSetting(context, SETTING_POLL_API_URL, "");
        INTERESTS_API_URL = TuneURLManager.fetchStringSetting(context, SETTING_INTERESTS_API_URL, "");
        GET_CYOA_API_URL = TuneURLManager.fetchStringSetting(context, SETTING_GET_CYOA_API_URL, "");

        Gson gson = provideGson();

        Retrofit restAdapter = provideRetrofit(gson);

        this.webservice = provideApiWebservice(restAdapter);

        this.executor = Executors.newSingleThreadExecutor();
    }


    private Gson provideGson() { return new GsonBuilder().create(); }

    private Retrofit provideRetrofit(Gson gson) {
        Retrofit retrofit = new Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(TUNEURL_API_BASE_URL)
                .build();
        return retrofit;
    }

    private WebAPI provideApiWebservice(Retrofit restAdapter) {

        return restAdapter.create(WebAPI.class);
    }


    public void searchFingerprint(final Context context, JsonObject fingerprint) {

        try {

            System.out.println("WebAPIClient.searchFingerprint() called");
            System.out.println("  URL: " + SEARCH_FINGERPRINT_URL);
            System.out.println("  Fingerprint: " + fingerprint.toString().substring(0, Math.min(100, fingerprint.toString().length())) + "...");

            executor.execute(() -> {

                webservice.searchFingerprint(SEARCH_FINGERPRINT_URL, fingerprint).enqueue(new Callback<JsonArray>() {

                    @Override
                    public void onResponse(Call<JsonArray> call, Response<JsonArray> response) {

                        executor.execute(() -> {

                            try {

                                if (response.code() == 200) {

                                    JsonArray result = response.body();

                                    System.out.println(" Search fingerprint SUCCESS");
                                    System.out.println("  Response: " + result.toString());

                                    JsonObject jsonResult = new JsonObject();

                                    jsonResult.add("result", result);

                                    System.out.println(" Broadcasting result with action: " + SEARCH_FINGERPRINT_RESULT_RECEIVED);
                                    broadcastResult(context, SEARCH_FINGERPRINT_RESULT_RECEIVED, jsonResult);
                                }
                                else {

                                    System.out.println(" Search fingerprint FAILED");
                                    System.out.println("  Code: " + response.code());
                                    System.out.println("  Message: " + response.message());

                                    JsonObject error = new JsonObject();

                                    error.addProperty("code", response.code());
                                    error.addProperty("message", response.message());

                                    broadcastError(context, SEARCH_FINGERPRINT_RESULT_ERROR, error.toString());
                                }
                            }
                            catch (Exception e) {

                                e.printStackTrace();

                                JsonObject error = new JsonObject();
                                error.addProperty("message", e.getMessage());
                                broadcastError(context, SEARCH_FINGERPRINT_RESULT_ERROR, error.toString());
                            }
                        });
                    }

                    @Override
                    public void onFailure(Call<JsonArray> call, Throwable t) {

                        System.out.println(" Search fingerprint NETWORK FAILURE");
                        System.out.println("  Error: " + t.getMessage());
                        t.printStackTrace();

                        JsonObject error = new JsonObject();
                        error.addProperty("message", t.getMessage());
                        broadcastError(context, SEARCH_FINGERPRINT_RESULT_ERROR, error.toString());
                    }
                });
            });
        }
        catch (Exception e) {

            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("message", e.getMessage());
            broadcastError(context, SEARCH_FINGERPRINT_RESULT_ERROR, error.toString());
        }
    }


    public void addRecordOfInterest(Context context, String TuneURL_ID, String interest_action, String date) {

        try{

            executor.execute(() -> {

                String UserID = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

                RecordOfInterest rof = new RecordOfInterest(UserID, date, TuneURL_ID, interest_action);
                String json_rof = new Gson().toJson(rof);
                JsonObject jsonObject = new JsonParser().parse(json_rof).getAsJsonObject();
                JsonArray json_array = new JsonArray();
                json_array.add(jsonObject);

                executor.execute(() -> {

                    webservice.addRecordOfInterest(INTERESTS_API_URL, json_array).enqueue(new Callback<JsonObject>() {

                        @Override
                        public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {

                            executor.execute(() -> {

                                try {

                                    if (response.code() == 200) {

                                        JsonObject result = response.body();

                                        broadcastResult(context, ADD_RECORD_OF_INTEREST_RESULT_RECEIVED, result);
                                    }
                                    else {

                                        JsonObject error = new JsonObject();

                                        error.addProperty("code", response.code());
                                        error.addProperty("message", response.message());

                                        broadcastError(context, ADD_RECORD_OF_INTEREST_RESULT_ERROR, error.toString());
                                    }
                                }
                                catch (Exception e) {

                                    e.printStackTrace();

                                    JsonObject error = new JsonObject();
                                    error.addProperty("message", e.getMessage());
                                    broadcastError(context, ADD_RECORD_OF_INTEREST_RESULT_ERROR, error.toString());
                                }
                            });
                        }

                        @Override
                        public void onFailure(Call<JsonObject> call, Throwable t) {

                            t.printStackTrace();

                            JsonObject error = new JsonObject();
                            error.addProperty("message", t.getMessage());
                            broadcastError(context, ADD_RECORD_OF_INTEREST_RESULT_ERROR, error.toString());
                        }
                    });
                });
            });
        }
        catch(Exception e){

            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("message", e.getMessage());
            broadcastError(context, ADD_RECORD_OF_INTEREST_RESULT_ERROR, error.toString());
        }
    }


    public void postPollAnswer(Context context, String poll_name, String user_response, String timestamp) {

        try{

            executor.execute(() -> {

                PollData pollData = new PollData(poll_name, user_response, timestamp);

                webservice.postPollAnswer(POLL_API_URL, pollData).enqueue(new Callback<JsonObject>() {

                    @Override
                    public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {

                        executor.execute(() -> {

                            try {

                                if (response.code() == 200) {

                                    JsonObject result = response.body();

                                    broadcastResult(context, POST_POLL_ANSWER_RESULT_RECEIVED, result);
                                }
                                else {

                                    JsonObject error = new JsonObject();

                                    error.addProperty("code", response.code());
                                    error.addProperty("message", response.message());

                                    broadcastError(context, POST_POLL_ANSWER_RESULT_ERROR, error.toString());
                                }
                            }
                            catch (Exception e) {

                                e.printStackTrace();

                                JsonObject error = new JsonObject();
                                error.addProperty("message", e.getMessage());
                                broadcastError(context, POST_POLL_ANSWER_RESULT_ERROR, error.toString());
                            }
                        });
                    }

                    @Override
                    public void onFailure(Call<JsonObject> call, Throwable t) {

                        t.printStackTrace();

                        JsonObject error = new JsonObject();
                        error.addProperty("message", t.getMessage());
                        broadcastError(context, POST_POLL_ANSWER_RESULT_ERROR, error.toString());
                    }
                });
            });
        }
        catch(Exception e){

            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("message", e.getMessage());
            broadcastError(context, POST_POLL_ANSWER_RESULT_ERROR, error.toString());
        }
    }


    public void getCYOA(final Context context, String tuneurl_id, String default_mp3_url) {

        try {

            executor.execute(() -> {

                webservice.getCYOA(GET_CYOA_API_URL, tuneurl_id).enqueue(new Callback<JsonArray>() {

                    @Override
                    public void onResponse(Call<JsonArray> call, Response<JsonArray> response) {

                        executor.execute(() -> {

                            try {

                                if (response.code() == 200) {

                                    JsonArray result = response.body();

                                    System.out.println(result.toString());

                                    JsonObject jsonResult = new JsonObject();

                                    jsonResult.addProperty(TUNEURL_ID, tuneurl_id);
                                    jsonResult.addProperty(DEFAULT_MP3_URL, default_mp3_url);
                                    jsonResult.add("result", result);

                                    broadcastResult(context, GET_CYOA_RESULT_RECEIVED, jsonResult);
                                }
                                else {

                                    JsonObject error = new JsonObject();

                                    System.out.println("code" + response.code());
                                    System.out.println("message" + response.message());

                                    error.addProperty("code", response.code());
                                    error.addProperty("message", response.message());

                                    broadcastError(context, GET_CYOA_RESULT_ERROR, error.toString());
                                }
                            }
                            catch (Exception e) {

                                e.printStackTrace();

                                JsonObject error = new JsonObject();
                                error.addProperty("message", e.getMessage());
                                broadcastError(context, GET_CYOA_RESULT_ERROR, error.toString());
                            }
                        });
                    }

                    @Override
                    public void onFailure(Call<JsonArray> call, Throwable t) {

                        t.printStackTrace();

                        JsonObject error = new JsonObject();
                        error.addProperty("message", t.getMessage());
                        broadcastError(context, GET_CYOA_RESULT_ERROR, error.toString());
                    }
                });
            });
        }
        catch (Exception e) {

            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("message", e.getMessage());
            broadcastError(context, GET_CYOA_RESULT_ERROR, error.toString());
        }
    }


    private void broadcastResult(Context context, String action, JsonObject result){

        System.out.println(" broadcastResult() called");
        System.out.println("  Action: " + action);
        System.out.println("  Result: " + result.toString().substring(0, Math.min(200, result.toString().length())) + "...");

        Intent i = new Intent();

        i.setAction(action);
        i.putExtra(TUNEURL_RESULT, result.toString());
        
        // Make broadcast explicit for Android 8.0+
        i.setPackage(context.getPackageName());

        context.sendBroadcast(i);
        
        System.out.println(" Broadcast sent to package: " + context.getPackageName());
    }


    private void broadcastError(Context context, String action, String error){

        System.out.println("❌ broadcastError() called");
        System.out.println("  Action: " + action);
        System.out.println("  Error: " + error);

        JsonObject result = new JsonObject();

        result.addProperty("error", error);

        Intent i = new Intent();

        i.setAction(action);
        i.putExtra(TUNEURL_RESULT, result.toString());
        
        // Make broadcast explicit for Android 8.0+
        i.setPackage(context.getPackageName());

        context.sendBroadcast(i);
        
        System.out.println("✅ Error broadcast sent to package: " + context.getPackageName());
    }
}
