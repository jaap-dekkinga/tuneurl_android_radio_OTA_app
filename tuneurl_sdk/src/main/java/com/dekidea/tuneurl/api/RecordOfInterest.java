package com.dekidea.tuneurl.api;

public class RecordOfInterest {

    private String UserID;

    private String Date;

    private String TuneURL_ID;

    private String Interest_action;

    public RecordOfInterest(){}

    public RecordOfInterest(String UserID, String Date, String TuneURL_ID, String Interest_action){

        this.UserID = UserID;
        this.Date = Date;
        this.TuneURL_ID = TuneURL_ID;
        this.Interest_action = Interest_action;
    }

    public void setUserID(String UserID) {

        this.UserID = UserID;
    }

    public void setDate(String Date){

        this.Date = Date;
    }

    public void setTuneURL_ID(String TuneURL_ID){

        this.TuneURL_ID = TuneURL_ID;
    }

    public void setInterestAction(String Interest_action){

        this.Interest_action = Interest_action;
    }

    public String getUserID(){

        return UserID;
    }

    public String getDate(){

        return Date;
    }

    public String getTuneURL_ID(){

        return TuneURL_ID;
    }

    public String getInterestAction(){

        return Interest_action;
    }
}
