package com.dekidea.tuneurl.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.dekidea.tuneurl.NativeResampler;
import com.dekidea.tuneurl.R;
import com.dekidea.tuneurl.util.Constants;
import com.dekidea.tuneurl.util.TuneURLManager;
import com.dekidea.tuneurl.util.WakeLocker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class TuneURLService extends Service implements Constants {

	private static final int NOTIFICATION_ID = 1521;
	private static final long TIMEOUT_US = 100000L;
	private static final float SIMILARITY_THRESHOLD = 0.98f;
	private static final int SCANNING_PERIOD_IN_FRAMES = 5;
	private static final int CHANNEL_BPS = 16;
	private static final int FINGERPRINT_BPS = 16;
	private static final int FINGERPRINT_SAMPLE_RATE = 10240;
	private static final int TRIGGER_SIZE_MILLIS = 1400;
	private static final int TUNE_URL_SIZE_MILLIS = 3500;
	private static final int FINGERPRINT_TRIGGER_BUFFER_SIZE = (int)(2 * (double)FINGERPRINT_SAMPLE_RATE * ((double)FINGERPRINT_BPS / 8d) * ((double)TRIGGER_SIZE_MILLIS / 1000d));

	private Context mContext;

	private byte[] referenceTriggerArray = null;
	private ByteBuffer referenceTriggerByteBuffer = null;

	private byte[] windowByteArray = null;
	private ByteBuffer triggerByteBuffer = null;
	private ByteBuffer tuneUrlByteBuffer = null;

	private ByteBuffer resampledTriggerByteBuffer = null;
	private ByteBuffer resampledTuneUrlByteBuffer = null;
	private int tuneUrlWaveLenght;
	private float mSimilarity;
	private float mLastSimilarity;


	private boolean recordTuneUrl = false;
	private boolean isPlaying = false;

	private int sampleRate;
	private int numChannels;

	private MediaExtractor mediaExtractor;
	private MediaCodec mediaCodec;

	private ListenerActionReceiver mListenerActionReceiver;
	private IntentFilter mListenerActionFilter;

	private ExecutorService mExecutorService;

	private boolean startCheckingTrigger = false;
	private int start_position_write = 0;
	private int start_position_read = 0;
	private int frameCounter = 0;
	private long lastSearchTime = 0;

	private static boolean isRunning = false;

	static {

		System.loadLibrary("native-lib");
	}
	

	@Override
	public void onCreate() {

		super.onCreate();

		mContext = this;

		initializeResources();
	}


	private void initializeResources(){

		System.out.println("TuneURLService.initializeResources()");

		InputStream inputStream = null;
		ReadableByteChannel channel = null;

		try {

			mExecutorService = Executors.newFixedThreadPool(1);

			String reference_trigger_file_path = TuneURLManager.fetchStringSetting(this, SETTING_TRIGGER_FILE_PATH, "");

			inputStream = new FileInputStream(reference_trigger_file_path);

			referenceTriggerByteBuffer = ByteBuffer.allocateDirect(FINGERPRINT_TRIGGER_BUFFER_SIZE);
			referenceTriggerByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
			channel = Channels.newChannel(inputStream);
			channel.read(referenceTriggerByteBuffer);

			referenceTriggerByteBuffer.rewind();
			referenceTriggerArray = new byte[referenceTriggerByteBuffer.remaining()];
			referenceTriggerByteBuffer.get(referenceTriggerArray);

			resampledTriggerByteBuffer = ByteBuffer.allocateDirect(FINGERPRINT_TRIGGER_BUFFER_SIZE);
			resampledTriggerByteBuffer.order(ByteOrder.LITTLE_ENDIAN);

			mListenerActionReceiver = new ListenerActionReceiver();
			mListenerActionFilter = new IntentFilter(LISTENING_ACTION);

			registerReceiver(mListenerActionReceiver, mListenerActionFilter);

			mSimilarity = 0;
			mLastSimilarity = 0;
		}
		catch (Exception e){

			e.printStackTrace();
		}
		finally {

			if(channel != null){

				try{
					channel.close();
				}
				catch (Exception e){


				}
			}
			if(inputStream != null){

				try{
					inputStream.close();
				}
				catch (Exception e){


				}
			}
		}
	}


	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub

		return null;
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId){

		super.onStartCommand(intent, flags, startId);

		System.out.println("TuneURLService.onStartCommand()");

		WakeLocker.acquirePartialWakeLock(this.getApplicationContext());

		startService();

		try {

			int action = intent.getIntExtra(TUNEURL_ACTION, -1);

			if (action == ACTION_START_SCANNING) {

				String path = intent.getStringExtra("path");
				long positionUs = intent.getLongExtra("positionUs", 0);

				startScanning(path, positionUs);
			}
		}
		catch (Exception e){

			e.printStackTrace();
		}

		isRunning = true;

		return Service.START_STICKY;
	}


	@Override
	public void onDestroy(){

		super.onDestroy();

		isRunning = false;

		stopService();
	}


	private void startService() {

		runAsForeground();
	}


	private void runAsForeground(){

		Intent i = new Intent();

		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		// Create the Foreground Service
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? createNotificationChannel(notificationManager) : "";
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
		Notification notification = notificationBuilder
				.setContentIntent(pendingIntent)
				.setOngoing(true)
				.setSmallIcon(com.google.android.material.R.drawable.abc_ic_star_black_16dp)
				.setContentText("TuneURL Service")
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.build();

		startForeground(NOTIFICATION_ID, notification);
	}


	private String createNotificationChannel(NotificationManager notificationManager){
		String channelId = "tune_url_sound_listener_service";
		String channelName = "TuneURL Service";
		NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);

		channel.setImportance(NotificationManager.IMPORTANCE_NONE);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		notificationManager.createNotificationChannel(channel);
		return channelId;
	}


	private void stopService() {

		releaseResources();

		WakeLocker.release();
	}


	private void startScanning(final String path, final long positionUs){

		try {

			startCheckingTrigger = false;
			start_position_write = 0;
			start_position_read = 0;
			frameCounter = 0;
			lastSearchTime = 0;
			recordTuneUrl = false;

			isPlaying = true;

			mExecutorService.execute(new Runnable() {
				@RequiresApi(api = Build.VERSION_CODES.P)
				@Override
				public void run() {

					searchForTrigger(path, positionUs);
				}
			});
		}
		catch (Exception e){

			e.printStackTrace();
		}
	}


	public void initializeMediaExtractor(String path, long positionUs){

		if(path != null) {

			try {

				this.mediaExtractor = new MediaExtractor();

				this.mediaExtractor.setDataSource(path);

				boolean isAudioTrackFound = false;

				for (int i = 0; !isAudioTrackFound && i < this.mediaExtractor.getTrackCount(); i++) {

					MediaFormat format = this.mediaExtractor.getTrackFormat(i);
					String mime = format.getString(MediaFormat.KEY_MIME);

					if (mime.startsWith("audio/")) {

						isAudioTrackFound = true;

						try {

							sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
						} catch (Exception e) {

							e.printStackTrace();
						}

						try {

							numChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
						} catch (Exception e) {

							e.printStackTrace();
						}

						initializeBuffers(sampleRate, numChannels);

						this.mediaExtractor.selectTrack(i);

						if (this.mediaCodec == null) {

							this.mediaCodec = MediaCodec.createDecoderByType(mime);
							this.mediaCodec.configure(format, null, null, 0);

							this.mediaCodec.start();
						}
					}
				}

				if (positionUs > 0) {

					this.mediaExtractor.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
				}
			} catch (Exception e) {

				e.printStackTrace();
			}
		}
	}


	private void initializeBuffers(int sampleRate, int numChannels){

		int triggerByteBufferSize = (int)((double)sampleRate * ((double)CHANNEL_BPS / 8d) * 2 * (double)(TRIGGER_SIZE_MILLIS / 1000d));

		triggerByteBuffer = ByteBuffer.allocateDirect(triggerByteBufferSize);
		triggerByteBuffer.order(ByteOrder.LITTLE_ENDIAN);

		windowByteArray = new byte[triggerByteBufferSize * 3];

		//int tuneUrlByteBufferSize = (int)((double)sampleRate * ((double)CHANNEL_BPS / 8d) * numChannels * (double)(TUNE_URL_SIZE_MILLIS / 1000d));
		int tuneUrlByteBufferSize = (int)((double)sampleRate * ((double)CHANNEL_BPS / 8d) * 2 * (double)(TUNE_URL_SIZE_MILLIS / 1000d));

		tuneUrlByteBuffer = ByteBuffer.allocateDirect(tuneUrlByteBufferSize);
		tuneUrlByteBuffer.order(ByteOrder.LITTLE_ENDIAN);

		int resampledTuneUrlByteBufferSize = (int)((double)FINGERPRINT_SAMPLE_RATE * ((double)CHANNEL_BPS / 8d) * 2 * ((double)TUNE_URL_SIZE_MILLIS / 1000d));
		tuneUrlWaveLenght = (int)((double)resampledTuneUrlByteBufferSize / 2d);
		resampledTuneUrlByteBuffer = ByteBuffer.allocateDirect(resampledTuneUrlByteBufferSize);
		resampledTuneUrlByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
	}


	public void releaseMediaExtractor(){

		if(this.mediaCodec != null) {

			try {

				this.mediaCodec.stop();

			} catch (Exception e) {

				e.printStackTrace();
			}

			try {

				this.mediaCodec.release();

			} catch (Exception e) {

				e.printStackTrace();
			}

			this.mediaCodec = null;
		}

		if(this.mediaExtractor != null) {

			try {

				this.mediaExtractor.release();

			} catch (Exception e) {

				e.printStackTrace();
			}

			this.mediaExtractor = null;
		}
	}


	private void searchForTrigger(String path, long positionUs){

		if(mediaExtractor == null){

			initializeMediaExtractor(path, positionUs);
		}

		if(mediaExtractor != null) {

			long startTime = Calendar.getInstance().getTimeInMillis();
			long startPlayTime = (long) ((double) mediaExtractor.getSampleTime() / 1000d);

			while (isPlaying) {

				MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

				int inIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US);

				if (inIndex >= 0) {

					ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inIndex);

					int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);

					if (sampleSize < 0) {

						mediaCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					} else {

						mediaCodec.queueInputBuffer(inIndex, 0, sampleSize, mediaExtractor.getSampleTime(), 0);
					}

					if (sampleSize >= 0) {

						int outIndex = mediaCodec.dequeueOutputBuffer(info, TIMEOUT_US);

						switch (outIndex) {

							case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:

								//System.out.println("INFO_OUTPUT_FORMAT_CHANGED");

								break;

							case MediaCodec.INFO_TRY_AGAIN_LATER:

								//System.out.println("INFO_TRY_AGAIN_LATER");

								break;

							default:

								ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outIndex);

								writeData(outputBuffer);

								mediaCodec.releaseOutputBuffer(outIndex, false);

								mediaExtractor.advance();

								break;
						}
					}
				}

				long currentTime = Calendar.getInstance().getTimeInMillis();
				long currentPlayTime = (long) ((double) mediaExtractor.getSampleTime() / 1000d);

				long timeSpent = currentTime - startTime;
				long timePlaySpent = currentPlayTime - startPlayTime;

				if (!recordTuneUrl && (timePlaySpent > (timeSpent + 500))) {

					try {

						Thread.sleep(timePlaySpent - timeSpent);
					}
					catch (Exception e) {

						e.printStackTrace();
					}
				}
			}

			releaseMediaExtractor();
		}
	}
	

	private void writeData(ByteBuffer outputBuffer){

		if(numChannels == 1) {

			byte[] stereo = convertToStereo(outputBuffer);

			ByteBuffer newOutputBuffer = ByteBuffer.allocateDirect(stereo.length);
			newOutputBuffer.order(ByteOrder.LITTLE_ENDIAN);

			newOutputBuffer.put(stereo);
			newOutputBuffer.rewind();

			checkData(newOutputBuffer);
		}
		else{

			checkData(outputBuffer);
		}
	}


	private void checkData(ByteBuffer outputBuffer){

		if (recordTuneUrl) {

			if (outputBuffer.remaining() <= tuneUrlByteBuffer.remaining()) {

				tuneUrlByteBuffer.put(outputBuffer);
			}
			else {

				int remaining = tuneUrlByteBuffer.remaining();

				for(int i=0; i<remaining; i++){

					tuneUrlByteBuffer.put(outputBuffer.get(i));
				}

				searchTuneUrlFingerprint();
			}
		}
		else {

			int freeBytes = windowByteArray.length - start_position_write;
			int frameSize = outputBuffer.remaining();

			if (frameSize < freeBytes) {

				int length = frameSize;

				outputBuffer.get(windowByteArray, start_position_write, length);

				start_position_write = start_position_write + length;
			}
			else {

				int length = frameSize - freeBytes;

				for(int i=0; i<freeBytes; i++){

					windowByteArray[start_position_write + i] = outputBuffer.get(i);
				}

				for(int i=0; i<length; i++){

					windowByteArray[i] = outputBuffer.get(freeBytes + i);
				}

				start_position_write = length;
			}

			if(!startCheckingTrigger){

				if(start_position_write >= triggerByteBuffer.capacity()){

					startCheckingTrigger = true;
				}
			}

			if(startCheckingTrigger){

				if(frameCounter % SCANNING_PERIOD_IN_FRAMES == 0) {

					triggerByteBuffer.clear();

					if(windowByteArray.length - start_position_read > triggerByteBuffer.capacity()){

						triggerByteBuffer.put(windowByteArray, start_position_read, triggerByteBuffer.capacity());
					}
					else {

						int first_leg_length = windowByteArray.length - start_position_read;
						int second_leg_length = triggerByteBuffer.capacity() - first_leg_length;

						triggerByteBuffer.put(windowByteArray, start_position_read, first_leg_length);
						triggerByteBuffer.put(windowByteArray, 0, second_leg_length);
					}

					start_position_read = start_position_read + SCANNING_PERIOD_IN_FRAMES * frameSize;
					if(start_position_read >= windowByteArray.length){

						start_position_read = start_position_read - windowByteArray.length;
					}

					long currentTime = Calendar.getInstance().getTimeInMillis();

					if((currentTime - lastSearchTime) > 5 * 1000){

						checkTriggerFingerprint();
					}
				}

				frameCounter = frameCounter + 1;
			}
		}
	}


	private byte[] convertToStereo(ByteBuffer outputBuffer){

		byte[] mono = new byte[outputBuffer.remaining()];
		byte[] stereo =  new byte[mono.length * 2];

		outputBuffer.get(mono);

		int indexStereo = 0;
		for(int i=0;i<mono.length; i = i + 2){

			stereo[indexStereo] = mono[i];
			stereo[indexStereo + 1] = mono[i + 1];
			stereo[indexStereo + 2] = mono[i];
			stereo[indexStereo + 3] = mono[i + 1];

			indexStereo = indexStereo + 4;
		}

		return stereo;
	}


	private void stopScanning(){

		System.out.println("TuneURLService.stopScanning()");

		isPlaying = false;
	}


	private void releaseResources(){

		System.out.println("TuneURLService.releaseResources()");

		isPlaying = false;

		try {

			if (mListenerActionReceiver != null) {

				unregisterReceiver(mListenerActionReceiver);
			}
		}
		catch (Exception e){

			e.printStackTrace();
		}
	}
	

	private void checkTriggerFingerprint() {

		NativeResampler resample = null;

		try {

			triggerByteBuffer.rewind();

			resampledTriggerByteBuffer.clear();

			referenceTriggerByteBuffer.clear();
			referenceTriggerByteBuffer.put(referenceTriggerArray);
			referenceTriggerByteBuffer.rewind();

			resample = new NativeResampler();
			resample.create(sampleRate, FINGERPRINT_SAMPLE_RATE, 2048, 1);			
			
			int output_len = resample.resampleEx(triggerByteBuffer, resampledTriggerByteBuffer, triggerByteBuffer.remaining());

			if(output_len > 0) {

				mSimilarity = getSimilarity(referenceTriggerByteBuffer, (int)(FINGERPRINT_TRIGGER_BUFFER_SIZE/2), resampledTriggerByteBuffer, (int)(FINGERPRINT_TRIGGER_BUFFER_SIZE/2));

				System.out.println("TuneURL - similarity: " + String.format("%.2f",mSimilarity));

				if (mSimilarity > SIMILARITY_THRESHOLD && mSimilarity <= mLastSimilarity) {

					mLastSimilarity = 0;
					recordTuneUrl = true;
				}
				else{

					mLastSimilarity = mSimilarity;
				}
			}
		}
		catch (Exception e) {

			e.printStackTrace();
		}
		finally {

			if(resample != null){

				try {

					resample.destroy();
				}
				catch (Exception e){

					e.printStackTrace();
				}
			}
		}
	}


	private void searchTuneUrlFingerprint(){

		NativeResampler resample = null;

		try {

			tuneUrlByteBuffer.rewind();
			resampledTuneUrlByteBuffer.clear();

			resample = new NativeResampler();
			resample.create(sampleRate, FINGERPRINT_SAMPLE_RATE, 2048, 1);
			
			int output_len = resample.resampleEx(tuneUrlByteBuffer, resampledTuneUrlByteBuffer, tuneUrlByteBuffer.remaining());

			if(output_len > 0) {

				resampledTuneUrlByteBuffer.rewind();

				byte[] monoAudio = convertToMono(resampledTuneUrlByteBuffer);

				if(monoAudio != null) {

					ByteBuffer byteBuffer = ByteBuffer.allocateDirect(monoAudio.length);
					byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
					byteBuffer.put(monoAudio);
					byteBuffer.rewind();

					//String fingerprint_string = extractFingerprintFromByteBuffer(byteBuffer, (int) ((double) monoAudio.length / 2d));
					String fingerprint_string = extractFingerprintFromByteBuffer(byteBuffer, monoAudio.length);

					byteBuffer.clear();

					byteBuffer = null;

					monoAudio = null;

					/*
					try {

						System.out.println("fingerprint_string_1 = " + fingerprint_string.substring(0, 3500));
						System.out.println("fingerprint_string_2 = " + fingerprint_string.substring(3500));
					}
					catch (Exception e) {

						e.printStackTrace();
					}

					 */

					searchFingerprint(fingerprint_string);
				}
			}
		}
		catch (Exception e){

			e.printStackTrace();
		}
		finally {

			if(resample != null){

				try {

					resample.destroy();
				}
				catch (Exception e){

					e.printStackTrace();
				}
			}
		}

		try {

			tuneUrlByteBuffer.clear();
		}
		catch (Exception e){

			e.printStackTrace();
		}

		recordTuneUrl = false;
	}


	private byte[] convertToMono(ByteBuffer byteBuffer){

		byte[] result = null;
		byte[] original = null;

		try {

			byteBuffer.rewind();

			original = new byte[byteBuffer.remaining()];

			byteBuffer.get(original);

			int resultLenght = (int) ((double) original.length / 2d);

			if ( (resultLenght & 1) != 0 ){

				resultLenght = resultLenght - 1;
			}

			result = new byte[resultLenght];

			int dstIndex = 0;

			for (int i = 0; i < resultLenght; i = i + 2) {

				result[i] = original[dstIndex];
				result[i + 1] = original[dstIndex + 1];

				dstIndex = dstIndex + 4;
			}
		}
		catch (Exception e){

			e.printStackTrace();
		}

		original = null;

		return result;
	}


	private String extractFingerprintFromByteBuffer(ByteBuffer byteBuffer, int waveLength) {

		String fingerprint = "";

		try {

			byte[] result_raw = extractFingerprint(byteBuffer, waveLength);

			String result = "";

			for(int i=0; i<result_raw.length; i++){

				result = result + (result_raw[i] & 0xff);

				if(i < result_raw.length - 1){

					result = result + ",";
				}
			}

			fingerprint = result;
		}
		catch (Exception e) {

			e.printStackTrace();
		}

		return fingerprint;
	}


	private void searchFingerprint(String fingerprint_string){

		Intent i = new Intent(this.getApplicationContext(), APIService.class);
		i.putExtra(TUNEURL_ACTION, ACTION_SEARCH_FINGERPRINT);
		i.putExtra(FINGERPRINT, fingerprint_string);
		startService(i);

		lastSearchTime = Calendar.getInstance().getTimeInMillis();
	}


	public static boolean isRunning(){

		return isRunning;
	}


	class ListenerActionReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {

			if (intent != null) {

				int action = intent.getIntExtra(TUNEURL_ACTION, -1);

				if(action == ACTION_START_SCANNING){

					String path = intent.getStringExtra("path");
					long positionUs = intent.getLongExtra("positionUs", 0);

					startScanning(path, positionUs);
				}
				else if(action == ACTION_STOP_SCANNING){

					stopScanning();
				}
			}
		}
	}


	public native byte[] extractFingerprint(ByteBuffer byteBuffer, int waveLength);

	public native float getSimilarity(ByteBuffer byteBuffer1, int waveLength1, ByteBuffer byteBuffer2, int waveLength2);
}