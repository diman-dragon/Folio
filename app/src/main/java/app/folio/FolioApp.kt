package app.folio

import android.app.Application
import app.folio.data.Graph

class FolioApp : Application() {
    override fun onCreate() { super.onCreate(); Graph.init(this) }
}
