package org.fairshare.app.data

import androidx.room.ConstructedBy
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "app_state")
data class StateRow(@PrimaryKey val id: Int = 1, val json: String)

/** Immutable revisions preserve the complete before/after values behind the visible activity. */
@Entity(tableName = "audit_revisions")
data class AuditRevision(
    @PrimaryKey(autoGenerate = true) val revision: Long = 0,
    val action: String,
    val at: Long,
    val beforeJson: String,
    val afterJson: String,
)

@Dao
interface StateDao {
    @Query("SELECT * FROM app_state WHERE id = 1") suspend fun read(): StateRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(row: StateRow)
    @Insert suspend fun append(revision: AuditRevision)
    /** Compare and write in one Room transaction so another repository cannot be overwritten. */
    @Transaction
    suspend fun commitIfUnchanged(expectedJson: String?, state: StateRow, revision: AuditRevision) {
        check(read()?.json == expectedJson) { "Saved data changed in another session. Refresh and try again." }
        write(state)
        append(revision)
    }

    @Query("SELECT * FROM audit_revisions WHERE action IN ('save_expense', 'delete_expense', 'restore_expense', 'save_settlement', 'delete_settlement') ORDER BY revision")
    suspend fun transactionRevisions(): List<AuditRevision>

    @Query("SELECT COUNT(*) FROM audit_revisions") suspend fun revisionCount(): Int
}

@Database(entities = [StateRow::class, AuditRevision::class], version = 1, exportSchema = true)
@ConstructedBy(LocalDatabaseConstructor::class)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun stateDao(): StateDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LocalDatabaseConstructor : RoomDatabaseConstructor<LocalDatabase> {
    override fun initialize(): LocalDatabase
}
