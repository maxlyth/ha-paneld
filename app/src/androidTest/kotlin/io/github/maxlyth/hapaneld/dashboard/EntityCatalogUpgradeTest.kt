package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android SQLite's actual upgrade path; schema-plan string tests cannot prove this. */
@RunWith(AndroidJUnit4::class)
class EntityCatalogUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun v8UpgradeRetainsCatalogRowsAndCreatesStateProximityAndAmbientSchemas() {
        legacyDatabase(8).use { db ->
            db.execSQL("CREATE TABLE entity(instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(instance,entity_id))")
            db.execSQL("INSERT INTO entity(instance,entity_id,state) VALUES('home','sensor.room','21.5')")
            db.version = 8
        }

        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, db.version)
            assertEquals("21.5", scalar(db, "SELECT state FROM entity WHERE instance='home' AND entity_id='sensor.room'"))
            assertTrue(tableExists(db, "app_state"))
            assertTrue(tableExists(db, "proximity_model"))
            assertTrue(tableExists(db, "ambient_lux_minute"))
            assertTrue(indexExists(db, "proximity_rollup_age"))
            assertTrue(indexExists(db, "ambient_lux_minute_age"))
        }
    }

    @Test fun v10UpgradePreservesProximityRowsAndAddsAmbientHistory() {
        legacyDatabase(10).use { db ->
            db.execSQL(EntityCatalogStore.PROXIMITY_MODEL_TABLE_SQL)
            db.execSQL(
                "INSERT INTO proximity_model(fingerprint,algorithm_version,behavior_signature,snapshot_json,ready,updated_at) " +
                    "VALUES('fingerprint',3,'behaviour','{}',1,123)",
            )
            db.version = 10
        }

        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, db.version)
            assertEquals("fingerprint", scalar(db, "SELECT fingerprint FROM proximity_model"))
            assertTrue(tableExists(db, "ambient_lux_minute"))
            assertTrue(indexExists(db, "ambient_lux_minute_age"))
        }
    }

    private fun legacyDatabase(version: Int): SQLiteDatabase {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        return context.openOrCreateDatabase(EntityCatalogStore.DATABASE_NAME, Context.MODE_PRIVATE, null).also { it.version = version }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        scalar(db, "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'") == table

    private fun indexExists(db: SQLiteDatabase, index: String): Boolean =
        scalar(db, "SELECT name FROM sqlite_master WHERE type='index' AND name='$index'") == index

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        context.deleteDatabase(EntityCatalogStore.LEGACY_DATABASE_NAME)
    }
}
