package pt.smartaccess.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pt.smartaccess.app.ui.AppSmartAccess
import pt.smartaccess.app.ui.TemaSmartAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaSmartAccess {
                AppSmartAccess()
            }
        }
    }
}
