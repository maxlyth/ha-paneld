package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The provisioner refuses to snapshot a database whose `user_version` is above its own ceiling, so a
 * schema bump that forgets the provisioner turns that fail-closed gate against every panel upgraded to
 * the new release. Both values are read from source so a drift names the two files to change together.
 */
class ProvisionerSchemaCeilingContractTest {
    @Test fun provisionerCeilingMatchesTheAppSchemaVersion() {
        val provisioner = "scripts/provision.sh"
        val schema = "app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"
        val ceiling = soleInteger(provisioner, Regex("""(?m)^DB_SUPPORTED_USER_VERSION_MAX=(\d+)$"""))
        val current = soleInteger(schema, Regex("""const\s+val\s+CURRENT_VERSION\s*=\s*(\d+)"""))

        assertEquals(
            "DB_SUPPORTED_USER_VERSION_MAX in $provisioner ($ceiling) must equal " +
                "EntityCatalogSchema.CURRENT_VERSION in $schema ($current); bump them together",
            current,
            ceiling,
        )
    }

    private fun soleInteger(path: String, pattern: Regex): Int {
        val matches = pattern.findAll(TestSources.repoFile(path).readText()).toList()
        assertEquals("expected exactly one ${pattern.pattern} in $path", 1, matches.size)
        return matches.single().groupValues[1].toInt()
    }
}
