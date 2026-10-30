package ai.closepaw.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates [ClosepawVoiceSession] instances for the system. */
class ClosepawVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        ClosepawVoiceSession(this)
}
