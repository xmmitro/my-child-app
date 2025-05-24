package com.xyz.child;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "logs")
public class LocalLog {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String deviceId;
    public String dataType;
    public String data;
    public long timestamp;

    public LocalLog(String deviceId, String dataType, String data, long timestamp) {
        this.deviceId = deviceId;
        this.dataType = dataType;
        this.data = data;
        this.timestamp = timestamp;
    }
}
