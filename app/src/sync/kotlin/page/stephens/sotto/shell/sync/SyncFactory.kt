package page.stephens.sotto.shell.sync

import android.content.Context
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.shell.service.NarrationCoordinatorProvider

internal object SyncFactory {
    fun create(context: Context): SyncCoordinator {
        val app = context.applicationContext
        return RealSyncCoordinator(
            db = SottoDatabase.open(app),
            filesDir = app.filesDir,
            api = SottoApi(),
            credentials = EncryptedCredentialStore(app),
            narrationFacts = NarrationCoordinatorProvider.get(app).facts,
            publication = NarrationCoordinatorProvider.publication(app),
        )
    }
}
