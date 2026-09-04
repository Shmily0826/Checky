package com.checky.app.ui.components

import com.checky.app.domain.model.CheckInStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusVisualsTest {

    @Test
    fun successAndAlreadyCheckedInSharePositiveColorRole() {
        assertEquals(StatusColorRole.POSITIVE, statusColorRole(CheckInStatus.SUCCESS))
        assertEquals(StatusColorRole.POSITIVE, statusColorRole(CheckInStatus.ALREADY_CHECKED_IN))
    }

    @Test
    fun failureNeutralWarningAndRunningRemainDistinct() {
        assertEquals(StatusColorRole.ERROR, statusColorRole(CheckInStatus.FAILED))
        assertEquals(StatusColorRole.NEUTRAL, statusColorRole(CheckInStatus.PENDING))
        assertEquals(StatusColorRole.WARNING, statusColorRole(CheckInStatus.LOGIN_EXPIRED))
        assertEquals(StatusColorRole.WARNING, statusColorRole(CheckInStatus.USER_ACTION_REQUIRED))
        assertEquals(StatusColorRole.ACTIVE, statusColorRole(CheckInStatus.RUNNING))
    }
}
