package org.fairshare.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

class LocalRepository(context: Context, databaseName: String = "fairshare.db") : SharedRepository(
    Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, databaseName)
        // Read large snapshot/audit TEXT directly through SQLite statements rather
        // than Android's size-limited CursorWindow. Existing SQLite files remain compatible.
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
)
