package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteStatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val legacyName = "sqlite-state-test-legacy"
    private val namespace = "sqlite-state-test"

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun legacyImportIsAtomicTypedAndNeverOverwritesNewerSqliteState() {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        assertTrue(
            legacy.edit()
                .putString("name", "legacy")
                .putInt("count", 7)
                .putLong("time", 123L)
                .putFloat("ratio", 1.5f)
                .putBoolean("enabled", true)
                .putStringSet("labels", setOf("one", "two"))
                .commit(),
        )

        EntityCatalogStore(context).use { helper ->
            val state = SqliteStatePreferences(helper, namespace, legacyName, legacy)
            assertEquals("legacy", state.getString("name", null))
            assertEquals(7, state.getInt("count", 0))
            assertEquals(123L, state.getLong("time", 0))
            assertEquals(1.5f, state.getFloat("ratio", 0f))
            assertTrue(state.getBoolean("enabled", false))
            assertEquals(setOf("one", "two"), state.getStringSet("labels", emptySet()))

            assertTrue(state.edit().putString("name", "sqlite").remove("enabled").commit())
            assertFalse(state.contains("enabled"))
        }

        assertTrue(legacy.edit().putString("name", "stale-legacy").commit())
        EntityCatalogStore(context).use { helper ->
            val reopened = SqliteStatePreferences(helper, namespace, legacyName, legacy)
            assertEquals("sqlite", reopened.getString("name", null))
            assertFalse(reopened.contains("enabled"))
            val revisions = helper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM app_state_revision WHERE namespace=?",
                arrayOf(namespace),
            ).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(2, revisions)
        }
    }

    private fun clean() {
        context.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        context.deleteDatabase(EntityCatalogStore.LEGACY_DATABASE_NAME)
    }
}
