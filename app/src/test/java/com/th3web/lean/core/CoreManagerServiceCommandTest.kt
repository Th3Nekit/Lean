package com.th3web.lean.core

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CoreManagerServiceCommandTest {
    @Test
    fun `background disconnect rejection does not escape the process`() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startService(service: Intent): ComponentName? {
                throw IllegalStateException("background stop blocked")
            }
        }

        val result = runCatching { CoreManager.disconnect(context) }

        assertTrue(result.isSuccess)
        assertTrue(CoreManager.logs.value.last().contains("остановка сервиса отклонена системой"))
    }
}
