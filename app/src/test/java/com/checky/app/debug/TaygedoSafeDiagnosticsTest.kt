package com.checky.app.debug

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaygedoSafeDiagnosticsTest {
    @Test
    fun taskKeysAreExposedButSensitiveKeyNamesAndValuesAreNot() {
        val json = JSONObject(
            """{"code":0,"data":{"task_list3":[
                {"taskKey":"browse_post_c","title":"Browse","uid":"account-value","roleId":"role-value"},
                {"taskKey":"new_task_v2","title":"Other","token":"secret-value"}
            ]}}"""
        )

        val rendered = observeTaygedoSchema("/tasks", 200, 0, json).render()

        assertTrue(rendered.contains("taskKeys=[browse_post_c, new_task_v2]"))
        assertTrue(rendered.contains("task_list3"))
        assertFalse(rendered.contains("account-value"))
        assertFalse(rendered.contains("role-value"))
        assertFalse(rendered.contains("secret-value"))
        assertFalse(rendered.contains("uid:"))
        assertFalse(rendered.contains("roleId:"))
        assertFalse(rendered.contains("token:"))
    }
}
