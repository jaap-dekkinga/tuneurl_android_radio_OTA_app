package com.dekidea.tuneurl.util;


import org.joda.time.Instant;

import java.util.Calendar;
import java.util.Locale;


public class TimeUtils implements Constants {


	public static String pad(int c) {
		
		if (c >= 10){
			
			return String.valueOf(c);
		}			
		else{
			
			return "0" + String.valueOf(c);
		}
	}


    public static String getTimestamp(){

        Instant instant = Instant.now();

        return instant.toString();
    }
	
	
	public static String getCurrentTimeAsString(){
		
		String time = "";
		
		try{
		
			Calendar calendar = Calendar.getInstance();
			
			String month_as_string = calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US);
			int day = calendar.get(Calendar.DAY_OF_MONTH);
			int year = calendar.get(Calendar.YEAR);
			
			int hour = 	calendar.get(Calendar.HOUR_OF_DAY);
			int minute = calendar.get(Calendar.MINUTE);		
			
			time = month_as_string + " " + day + ", " + year + " - " + hour + ":" + minute;
		}
		catch(NullPointerException e){
			
			e.printStackTrace();
		}
		catch(IllegalArgumentException e){
			
			e.printStackTrace();
		}
		
		return time;
	}


    public static String getCurrentTimeAsFormattedString(){

        String time = "";

        try{

            Calendar calendar = Calendar.getInstance();

            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);


            int hour = 	calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);

            time = year + "-" + pad(month) + "-" + pad(day) + "T" + pad(hour) + "" + pad(minute);
        }
        catch(NullPointerException e){

            e.printStackTrace();
        }
        catch(IllegalArgumentException e){

            e.printStackTrace();
        }

        return time;
    }


	public static long getCurrentTimeInMillis(){
		
		Calendar calendar = Calendar.getInstance();
		
		return calendar.getTimeInMillis();
	}
}
