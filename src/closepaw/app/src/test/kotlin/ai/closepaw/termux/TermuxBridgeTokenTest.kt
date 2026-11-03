package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import org.junit.After
import org.junit.Test

/**
 * B2 (review 6555767212): the token that gates the bridge shell endpoint.
 *
 * Two properties matter and both are tested here: a token is long, random and confined to the
 * base64url alphabet (it is interpolated into a shell command and an HTTP header), and the same
 * token is reused across calls so a daemon started earlier stays reachable.
 */
class TermuxBridgeTokenTest {

    @After
    fun tearDown() {
        TermuxBridgeToken.clear()
    }

    @Test
    fun `generated token is base64url and long enough to be unusable by guessing`() {
        val token = TermuxBridgeToken.generate(SecureRandom())

        assertThat(token).hasLength(43) // 32 bytes, base64url without padding
        assertThat(TermuxBridgeToken.isUsable(token)).isTrue()
        assertThat(token).matches("^[A-Za-z0-9_-]+$")
    }

    @Test
    fun `two generated tokens differ`() {
        assertThat(TermuxBridgeToken.generate()).isNotEqualTo(TermuxBridgeToken.generate())
    }

    @Test
    fun `ensure creates a token once and reuses the stored one`() {
        val store = InMemoryTokenStore()

        val first = TermuxBridgeToken.ensure(store)
        val second = TermuxBridgeToken.ensure(store)

        assertThat(second).isEqualTo(first)
        assertThat(store.read()).isEqualTo(first)
        assertThat(TermuxBridgeToken.current()).isEqualTo(first)
    }

    @Test
    fun `ensure replaces a stored value that is not a usable token`() {
        val store = InMemoryTokenStore("short")

        val token = TermuxBridgeToken.ensure(store)

        assertThat(token).isNotEqualTo("short")
        assertThat(TermuxBridgeToken.isUsable(token)).isTrue()
    }

    @Test
    fun `token install command quotes the secret and keeps it private`() {
        val command = TermuxBridgeToken.tokenInstallCommand("unit-test-token-0123456789abcdef0123456789")

        assertThat(command).contains("> ~/.closepaw/token")
        assertThat(command).contains("chmod 600 ~/.closepaw/token")
        assertThat(command).contains("umask 077")
    }

    @Test
    fun `token install command refuses a value that is not base64url`() {
        listOf("short", "has'quote", "has space", "semi;colon", "abc\u0000def").forEach { bad ->
            var thrown: Throwable? = null
            try {
                TermuxBridgeToken.tokenInstallCommand(bad)
            } catch (e: IllegalArgumentException) {
                thrown = e
            }
            assertThat(thrown).isNotNull()
        }
    }
}
