package com.xyz.child;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {LocalLog.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract LogDao logDao();
}
