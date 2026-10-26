package org.fairshare.app.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun createIosRepository(databaseName: String = "fairshare.db"): SharedRepository {
    require(databaseName == "fairshare.db" || Regex("fairshare-ui-test-[0-9a-fA-F-]{36}\\.db").matches(databaseName)) {
        "Invalid application database name."
    }
    val directory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )
    val path = requireNotNull(directory?.path) { "Cannot open application storage." } + "/" + databaseName
    val database = Room.databaseBuilder<LocalDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    return SharedRepository(database)
}
