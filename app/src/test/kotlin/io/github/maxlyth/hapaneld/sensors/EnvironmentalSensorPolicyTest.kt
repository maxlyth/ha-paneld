package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentalSensorPolicyTest {
    @Test fun cht8305SensorsAreActivatedWithoutPublishingLegacyValues() {
        assertEquals(
            EnvironmentalSensorUse.ACTIVATE_ONLY,
            environmentalSensorUse(hasCht8305 = true, sensorPresent = true),
        )
    }

    @Test fun standardEnvironmentalSensorsRemainPublished() {
        assertEquals(
            EnvironmentalSensorUse.PUBLISH,
            environmentalSensorUse(hasCht8305 = false, sensorPresent = true),
        )
    }

    @Test fun absentEnvironmentalSensorsRemainAbsent() {
        listOf(false, true).forEach { hasCht8305 ->
            assertEquals(
                EnvironmentalSensorUse.ABSENT,
                environmentalSensorUse(hasCht8305 = hasCht8305, sensorPresent = false),
            )
        }
    }
}
