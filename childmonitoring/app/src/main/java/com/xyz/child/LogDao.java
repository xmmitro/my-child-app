package com.xyz.child;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LogDao {
    @Insert
    void insert(LocalLog log);

    @Query("SELECT * FROM logs WHERE dataType = :dataType")
    List<LocalLog> getLogs(String dataType);
}
