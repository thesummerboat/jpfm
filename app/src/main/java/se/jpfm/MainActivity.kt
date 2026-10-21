package se.jpfm

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import se.jpfm.ui.AppRoot
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.JpfmTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { JpfmTheme { AppRoot(vm) } }
        askForNotifications()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.player.connect()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            vm.playerOpen = true
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            vm.playFromSearch(query, intent.extras)
            intent.action = null
        }
    }

    /** Android 13+: utan tillstånd syns inte spelaren i notisfältet och på låsskärmen. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "se.jpfm.OPEN_PLAYER"
    }
}
