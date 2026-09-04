package com.checky.app.domain.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReliabilityClassifierTest {

    @Test
    fun xiaomiBackgroundRestrictionIsCritical() {
        val report = classify(xiaomi = true, restricted = true, bucket = 10)

        assertEquals(BackgroundReliabilityStatus.CRITICAL_RESTRICTED, report.status)
    }

    @Test
    fun xiaomiDeferredBucketIsWarningNotToggleProof() {
        val report = classify(xiaomi = true, restricted = false, bucket = 40)

        assertEquals(BackgroundReliabilityStatus.MAY_BE_DEFERRED, report.status)
        assertTrue(report.shouldShowInSettings(autoCheckInEnabled = true))
    }

    @Test
    fun xiaomiHealthySignalsStillRequireManualAutostartReview() {
        val report = classify(xiaomi = true, restricted = false, bucket = 10)

        assertEquals(BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW, report.status)
        assertTrue(report.shouldShowInSettings(autoCheckInEnabled = true))
    }

    @Test
    fun nonXiaomiRestrictionIsGenericCritical() {
        val report = classify(xiaomi = false, restricted = true, bucket = 10)

        assertEquals(BackgroundReliabilityStatus.CRITICAL_RESTRICTED, report.status)
        assertTrue(report.shouldShowInSettings(autoCheckInEnabled = true))
    }

    @Test
    fun nonXiaomiHealthySignalsDoNotShowXiaomiNag() {
        val report = classify(xiaomi = false, restricted = false, bucket = 10)

        assertEquals(BackgroundReliabilityStatus.HEALTHY, report.status)
        assertFalse(report.shouldShowInSettings(autoCheckInEnabled = true))
    }

    @Test
    fun unknownSignalsRemainAttentionStateWhenAutoCheckInIsEnabled() {
        val report = classify(xiaomi = false, restricted = null, bucket = null)

        assertEquals(BackgroundReliabilityStatus.UNKNOWN, report.status)
        assertTrue(report.shouldShowInSettings(autoCheckInEnabled = true))
        assertFalse(report.shouldShowInSettings(autoCheckInEnabled = false))
    }

    @Test
    fun exemptedAndActiveBucketsDoNotProveXiaomiToggles() {
        val exempted = classify(xiaomi = true, restricted = false, bucket = 5)
        val active = classify(xiaomi = true, restricted = false, bucket = 10)

        assertEquals(BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW, exempted.status)
        assertEquals(BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW, active.status)
    }

    @Test
    fun xiaomiFamilyDetectionExcludesBlackSharkAndForeignBrands() {
        assertTrue(BackgroundReliabilityClassifier.isXiaomiFamily("Xiaomi"))
        assertTrue(BackgroundReliabilityClassifier.isXiaomiFamily(" Xiaomi "))
        assertFalse(BackgroundReliabilityClassifier.isXiaomiFamily("BlackShark"))
        assertFalse(BackgroundReliabilityClassifier.isXiaomiFamily("Redmi"))
        assertFalse(BackgroundReliabilityClassifier.isXiaomiFamily("Samsung"))
    }

    private fun classify(
        xiaomi: Boolean,
        restricted: Boolean?,
        bucket: Int?
    ): BackgroundReliabilityReport = BackgroundReliabilityClassifier.classify(
        BackgroundReliabilitySignals(
            isXiaomiFamily = xiaomi,
            backgroundRestricted = restricted,
            standbyBucket = bucket
        )
    )
}
