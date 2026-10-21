package se.jpfm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import se.jpfm.ui.JpfmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JpfmTheme { Text("JPFM") } }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "se.jpfm.OPEN_PLAYER"
    }
}
