package ch.tunio.app

import android.app.Application
import ch.tunio.app.data.Library
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.Engine
import ch.tunio.app.engine.PyWorker
import ch.tunio.app.engine.SpotifyAccount
import ch.tunio.app.player.PlayerController

class TunioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        AudioStore.init(this)
        SpotifyAccount.init(this)
        AudioStore.onFailure = { PlayerController.messages.tryEmit(it) }
        PlayerController.init(this)
        PyWorker.init(this)
        Engine.start(this)
    }
}
