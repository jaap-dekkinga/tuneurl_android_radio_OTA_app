package com.dekidea.tuneurl.service;

import android.app.IntentService;
import android.content.Intent;

import com.dekidea.tuneurl.api.WebAPIClient;
import com.dekidea.tuneurl.api.WebAPIClientFactory;
import com.dekidea.tuneurl.util.Constants;
import com.dekidea.tuneurl.util.JsonUtils;
import com.dekidea.tuneurl.util.WakeLocker;


public class APIService extends IntentService implements Constants {
	
	public APIService() {
		
		super("TuneURL.APIService");
	}

	
	public APIService(String name) {
		
		super(name);
	}


    @Override
    public void onCreate() {

        super.onCreate();
    }


    @Override
	protected void onHandleIntent(Intent intent) {
		
		WakeLocker.acquirePartialWakeLock(this.getApplicationContext());
		
		if(intent != null){

			try {

				WebAPIClient webAPIClient = WebAPIClientFactory.getWebAPIClient(this);

				int action = intent.getIntExtra(TUNEURL_ACTION, -1);

				System.out.println("APIService.onHandleIntent(): action = " + action);

				if (action == ACTION_SEARCH_FINGERPRINT) {

					String fingerprint_string = intent.getStringExtra(FINGERPRINT);

					webAPIClient.searchFingerprint(this, JsonUtils.getFingerprintJson(fingerprint_string));
				}
				else if (action == ACTION_ADD_RECORD_OF_INTEREST) {

					String TuneURL_ID = intent.getStringExtra(ID);
					String interest_action = intent.getStringExtra(INTEREST_ACTION);
					String date = intent.getStringExtra(DATE);

					webAPIClient.addRecordOfInterest(this, TuneURL_ID, interest_action, date);
				}
				else if (action == ACTION_POST_POLL_ANSWER) {

					String user_response = intent.getStringExtra(USER_RESPONSE);
					String poll_name = intent.getStringExtra(POLL_NAME);
					String timestamp = intent.getStringExtra(TIMESTAMP);

					webAPIClient.postPollAnswer(this, poll_name, user_response, timestamp);
				}
				else if (action == ACTION_GET_CYOA) {

					String tuneurl_id = intent.getStringExtra(TUNEURL_ID);
					String default_mp3_url = intent.getStringExtra(DEFAULT_MP3_URL);

					webAPIClient.getCYOA(this, tuneurl_id, default_mp3_url);
				}
			}
			catch (Exception e){

				e.printStackTrace();
			}
		}
		
		WakeLocker.release();
	}
}