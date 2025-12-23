package com.dekidea.tuneurl.api;

import android.os.Parcel;
import android.os.Parcelable;

public class APIData implements Parcelable {

    private long id;
    private String name;
    private String description;
    private String type;
    private String info;
    private int matchPercentage;

    private String date;
    private long date_absolute;

    // --- CONSTRUCTORS ---

    public APIData() { }

    public APIData(long id, String name, String description, String type, String info, int matchPercentage) {

        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.info = info;
        this.matchPercentage = matchPercentage;
    }

    // --- GETTER ---

    public long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getType() { return type; }
    public String getInfo() { return info; }
    public int getMatchPercentage() { return matchPercentage; }

    public String getDate() { return date; }
    public long getDateAbsulote() { return date_absolute; }

    // --- SETTER ---

    public void setId(long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setType(String type) { this.type = type; }
    public void setInfo(String info) { this.info = info; }
    public void setMatchPercentage(int matchPercentage) { this.matchPercentage = matchPercentage; }

    public void setDate(String date) { this.date = date; }
    public void setDateAbsolute(long date_absolute) { this.date_absolute = date_absolute; }


    public APIData(Parcel in){

        try {

            String[] data = new String[8];

            in.readStringArray(data);

            this.id = Integer.valueOf(data[0]);
            this.name = data[1];
            this.description = data[2];
            this.type = data[3];
            this.info = data[4];
            this.matchPercentage = Integer.valueOf(data[5]);
            this.date = data[6];
            this.date_absolute = Long.valueOf(data[7]);
        }
        catch (Exception e){

            e.printStackTrace();
        }
    }

    @Override
    public int describeContents() {

        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {

        parcel.writeStringArray(new String[] {String.valueOf(this.id),
                                                this.name,
                                                this.description,
                                                this.type,
                                                this.info,
                                                String.valueOf(this.matchPercentage),
                                                this.date,
                                                String.valueOf(this.date_absolute)});
    }


    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {

        public APIData createFromParcel(Parcel in) {

            return new APIData(in);
        }

        public APIData[] newArray(int size) {

            return new APIData[size];
        }
    };
}
