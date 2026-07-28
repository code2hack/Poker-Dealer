package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostConnectionProfileStoreTest {
    @Test
    fun connectionProfileSurvivesStoreRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostId = "profile-recreation-test"
        val config = DealerHostConnectionConfig(
            hostId = hostId,
            lanHost = "192.0.2.1",
            tailnetHost = "",
            sshUser = "dealer",
        )
        val privateKey = "private-key".toByteArray()
        val knownHosts = "known-hosts".toByteArray()

        DealerHostConnectionProfileStore(context).save(config, privateKey, knownHosts)
        val restored = DealerHostConnectionProfileStore(context).load(hostId)

        assertEquals(config, restored.config)
        assertTrue(privateKey.contentEquals(restored.privateKey))
        assertTrue(knownHosts.contentEquals(restored.knownHosts))
        restored.privateKey.fill(0)
        restored.knownHosts.fill(0)
    }
}
