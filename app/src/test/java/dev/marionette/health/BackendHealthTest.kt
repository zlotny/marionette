package dev.marionette.health

import dev.marionette.llm.LlmException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class BackendHealthTest {
    @Test
    fun noErrorIsOk() {
        assertEquals(BackendHealth.Ok, BackendHealth.classify(null, online = true))
    }

    @Test
    fun rejectedKey() {
        assertEquals(BackendHealth.Rejected, BackendHealth.classify(LlmException(401, "bad key"), online = true))
        assertEquals(BackendHealth.Rejected, BackendHealth.classify(LlmException(403, "forbidden"), online = true))
    }

    @Test
    fun missingModelsEndpointStillMeansReachable() {
        assertEquals(BackendHealth.Ok, BackendHealth.classify(LlmException(404, "not found"), online = true))
    }

    @Test
    fun serverErrorIsFailing() {
        assertEquals(BackendHealth.Failing("boom"), BackendHealth.classify(LlmException(502, "boom"), online = true))
    }

    @Test
    fun networkErrorDependsOnConnectivity() {
        assertEquals(BackendHealth.Unreachable, BackendHealth.classify(IOException("refused"), online = true))
        assertEquals(BackendHealth.Offline, BackendHealth.classify(IOException("refused"), online = false))
    }
}
