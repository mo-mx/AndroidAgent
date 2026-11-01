package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * B2 (review 6555767212): setup must install the shared secret into Termux-private storage before
 * the daemon is started, and the same secret must be published for the exec tool to present.
 */
class TermuxBridgeDeployTokenTest {

    private val token = "unit-test-token-0123456789abcdef0123456789"

    @After
    fun tearDown() {
        TermuxBridgeToken.clear()
    }

    @Test
    fun `deploy writes the token before the payload and keeps it private`() = runTest {
        val runner = RecordingRunner()

        manager(runner).setup()

        val deploy = runner.commands.single { it.contains("CLOSEPAW_DEPLOY=ok") }
        assertThat(deploy).contains("> ~/.closepaw/token")
        assertThat(deploy).contains("chmod 600 ~/.closepaw/token")
        assertThat(deploy).contains(token)
        assertThat(deploy.indexOf("~/.closepaw/token"))
            .isLessThan(deploy.indexOf("base64 -d"))
    }

    @Test
    fun `setup publishes the token the exec tool needs`() = runTest {
        TermuxBridgeToken.clear()

        manager(RecordingRunner()).setup()

        assertThat(TermuxBridgeToken.current()).isEqualTo(token)
    }

    @Test
    fun `a stored token is reused instead of rotating on every setup`() = runTest {
        val store = InMemoryTokenStore(token)

        val first = manager(RecordingRunner(), store)
        first.setup()

        assertThat(store.read()).isEqualTo(token)
        assertThat(TermuxBridgeToken.current()).isEqualTo(token)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        runner: TermuxCommandRunner,
        store: TermuxTokenStore = InMemoryTokenStore(token)
    ): TermuxBridgeManager =
        TermuxBridgeManager(
            commandRunner = runner,
            healthProbe = TermuxHealthProbe { HealthProbe.Ready },
            termuxInstallProbe = TermuxInstallProbe { TermuxInstallState.Available },
            bridgePayloadBase64 = { "cHJpbnQoJ29rJykK" },
            managerScope = backgroundScope,
            tokenStore = store
        )

    private class RecordingRunner : TermuxCommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun runShell(
            command: String,
            stdinBase64: String?,
            timeoutMs: Long
        ): RunCommandResult {
            commands += command
            return when {
                "CLOSEPAW_PROBE=ok" in command -> RunCommandResult("CLOSEPAW_PROBE=ok\n", "", 0)
                "apt install" in command ->
                    RunCommandResult(
                        "/data/data/com.termux/files/usr/bin/python3\n" +
                            "/data/data/com.termux/files/usr/bin/git\n" +
                            "/data/data/com.termux/files/usr/bin/rg\n",
                        "",
                        0
                    )
                "CLOSEPAW_DEPLOY=ok" in command ->
                    RunCommandResult("CLOSEPAW_DEPLOY=ok\n", "", 0)
                "CLOSEPAW_START=ok" in command ->
                    RunCommandResult("CLOSEPAW_START=ok\n", "", 0)
                else -> RunCommandResult("", "", 0)
            }
        }
    }
}
