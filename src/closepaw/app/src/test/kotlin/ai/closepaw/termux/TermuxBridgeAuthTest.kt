package ai.closepaw.termux

import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.impl.TermuxShellTool
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * B2 (review 6555767212): the bridge endpoint runs a shell on loopback, which every app on the
 * device can reach. The app must prove it is the caller, and must not send an unauthenticated
 * request when it has no token to prove it with.
 */
class TermuxBridgeAuthTest {

    private val token = "unit-test-token-0123456789abcdef0123456789"

    @After
    fun tearDown() {
        TermuxBridgeToken.clear()
    }

    @Test
    fun `exec carries the stored token as a bearer header`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(200, """{"exit_code":0,"stdout":"ok\n"}"""))

            val result = execute(server, tokenProvider = { token })

            val recorded = server.takeRequest()
            assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer $token")
            assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        }
    }

    @Test
    fun `exec refuses to reach the bridge when no token is loaded`() = runTest {
        MockWebServer().use { server ->
            val tool = TermuxShellTool(
                bridgeBaseUrl = server.url("/").toString(),
                tokenProvider = { null }
            )

            val result = tool.createInvocation(JSONObject().put("command", "echo hi"))
                .execute(testContext())

            assertThat(failureReason(result)).isEqualTo("bridge_auth_unavailable")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `a 401 from the daemon is reported as an auth rejection not a generic error`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(401, """{"error":"unauthorized"}"""))

            val result = execute(server, tokenProvider = { token })

            assertThat(failureReason(result)).isEqualTo("bridge_auth_rejected")
        }
    }

    @Test
    fun `health probe sends the token and reports unauthorized separately`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(401, """{"error":"unauthorized"}"""))
            val probe = HttpTermuxHealthProbe(
                healthUrl = server.url("/v1/health").toString(),
                expectedIdentity = "closepaw-bridge",
                expectedVersion = "2",
                tokenProvider = { token }
            )

            val health = probe.fetch()

            assertThat(health).isEqualTo(HealthProbe.Unauthorized)
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer $token")
        }
    }

    @Test
    fun `health probe does not call the daemon without a token`() = runTest {
        MockWebServer().use { server ->
            val probe = HttpTermuxHealthProbe(
                healthUrl = server.url("/v1/health").toString(),
                expectedIdentity = "closepaw-bridge",
                expectedVersion = "2",
                tokenProvider = { null }
            )

            assertThat(probe.fetch()).isEqualTo(HealthProbe.Unavailable)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `health probe still reports an outdated daemon`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                json(200, """{"status":"ok","identity":"closepaw-bridge","version":"1"}""")
            )
            val probe = HttpTermuxHealthProbe(
                healthUrl = server.url("/v1/health").toString(),
                expectedIdentity = "closepaw-bridge",
                expectedVersion = "2",
                tokenProvider = { token }
            )

            assertThat(probe.fetch()).isEqualTo(HealthProbe.BridgeOutdated)
        }
    }

    private suspend fun execute(
        server: MockWebServer,
        tokenProvider: () -> String?
    ): ToolExecutionResult =
        TermuxShellTool(
            bridgeBaseUrl = server.url("/").toString(),
            tokenProvider = tokenProvider
        ).createInvocation(JSONObject().put("command", "echo hi")).execute(testContext())

    private fun json(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun testContext(): ToolExecutionContext =
        object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }

    private fun failureReason(result: ToolExecutionResult): String {
        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        return JSONObject((result as ToolExecutionResult.Failure).error).getString("reason")
    }
}
