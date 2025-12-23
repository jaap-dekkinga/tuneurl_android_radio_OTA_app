package com.dekidea.tuneurl.util;

import android.content.Context;
import android.os.PowerManager;

public abstract class WakeLocker {
   
	private static PowerManager.WakeLock wakeLock;
	
	private static int lock_count = 0;

	public static void acquirePartialWakeLock(Context ctx) {
    	
    	if(lock_count == 0){
    		
    		lock_count = lock_count + 1;

	        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
	       
	        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tuneurl:wakelocker");
	        
	        wakeLock.acquire();
    	}
    	else{
    		
    		lock_count = lock_count + 1;
    	}
    }
    

    public static void release() {
    	
    	if(lock_count == 1){
    	
		    if (wakeLock != null){
		         	
		         try {
		         		
		         	if(wakeLock.isHeld()){
		                 	
		             	wakeLock.release();
		             		
		             	wakeLock = null;
		             }
		         } 
		         catch (Throwable th) {
		                
		         	th.printStackTrace();        	
		        }
    		}
    	}
    	else{
    		
    		lock_count = lock_count - 1;
    	}
    }
}