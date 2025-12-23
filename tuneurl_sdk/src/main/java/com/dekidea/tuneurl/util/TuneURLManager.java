package com.dekidea.tuneurl.util;

import static android.content.Context.MODE_PRIVATE;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;

import com.dekidea.tuneurl.api.APIData;
import com.dekidea.tuneurl.service.APIService;
import com.dekidea.tuneurl.service.TuneURLService;

public class TuneURLManager implements Constants{

    private static APIData currentAPIData;

    public static void setCurrentAPIData(APIData apiData){

        currentAPIData = apiData;
    }


    public static APIData getCurrentAPIData(){

        return currentAPIData;
    }


    public static boolean isWiredHeadsetOn(Context context){

        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        return  audioManager.isWiredHeadsetOn();
    }


    public static void startTuneURLService(Context context) {

        System.out.println("TuneURLManager.startTuneURLService()");

        try {

            Intent i = new Intent(context, TuneURLService.class);

            if (Build.VERSION.SDK_INT > 25) {

                context.startForegroundService(i);
            }
            else{

                context.startService(i);
            }
        }
        catch(Exception e){

            e.printStackTrace();
        }
    }


    public static void stopTuneURLService(Context context) {

        System.out.println("TuneURLManager.stopTuneURLService()");

        try {

            Intent i = new Intent(context, TuneURLService.class);

            context.stopService(i);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }


    public static void startScanning(Context context, String path, long positionUs) {

        System.out.println("TuneURLManager.startScanning()");

        if(!TuneURLService.isRunning()){

            try {

                Intent i = new Intent(context, TuneURLService.class);

                i.putExtra(TUNEURL_ACTION, ACTION_START_SCANNING);
                i.putExtra("path", path);
                i.putExtra("positionUs", positionUs);

                if (Build.VERSION.SDK_INT > 25) {

                    context.startForegroundService(i);
                }
                else{

                    context.startService(i);
                }
            }
            catch(Exception e){

                e.printStackTrace();
            }
        }
        else {

            try {

                Intent i = new Intent();
                i.setAction(LISTENING_ACTION);
                i.putExtra(TUNEURL_ACTION, ACTION_START_SCANNING);
                i.putExtra("path", path);
                i.putExtra("positionUs", positionUs);
                context.sendBroadcast(i);
            } catch (Exception e) {

                e.printStackTrace();
            }
        }
    }


    public static void stopScanning(Context context) {

        System.out.println("TuneURLManager.stopScanning()");

        try {

            Intent i = new Intent();
            i.setAction(LISTENING_ACTION);
            i.putExtra(TUNEURL_ACTION, ACTION_STOP_SCANNING);

            context.sendBroadcast(i);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }


    public static void stopRecorder(Context context) {

        try {

            Intent i = new Intent();
            i.setAction(LISTENING_ACTION);
            i.putExtra(TUNEURL_ACTION, ACTION_STOP_RECORDER);

            context.sendBroadcast(i);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }


    public static void addRecordOfInterest(Context context, String TuneURL_ID, String interest_action, String date) {

        try {

            Intent i = new Intent(context, APIService.class);

            i.putExtra(TUNEURL_ACTION, ACTION_ADD_RECORD_OF_INTEREST);

            i.putExtra(ID, TuneURL_ID);
            i.putExtra(INTEREST_ACTION, interest_action);
            i.putExtra(DATE, date);

            context.startService(i);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }


    public static void postPollAnswer(Context context, String poll_name, String user_response, String timestamp) {

        try {

            Intent i = new Intent(context, APIService.class);

            i.putExtra(TUNEURL_ACTION, ACTION_POST_POLL_ANSWER);

            i.putExtra(POLL_NAME, poll_name);
            i.putExtra(USER_RESPONSE, user_response);
            i.putExtra(TIMESTAMP, timestamp);

            context.startService(i);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }


    public static int fetchIntSetting(Context context, String setting, int default_value){

        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
        return (sp.getInt(setting, default_value));
    }


    public static void updateIntSetting(Context context, String setting, int value){

        try {

            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            editor.putInt(setting, value);

            editor.commit();
        }
        catch(Exception e){

            e.printStackTrace();
        }
    }

    public static String fetchStringSetting(Context context, String setting, String default_value){

        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
        return (sp.getString(setting, default_value));
    }

    public static void updateStringSetting(Context context, String setting, String value){

        try {

            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            editor.putString(setting, value);

            editor.commit();
        }
        catch(Exception e){

            e.printStackTrace();
        }
    }


    private static boolean isHeadsetConnected(Context context){

        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(audioManager.isWiredHeadsetOn()){

            return true;
        }

        return false;
    }
}
