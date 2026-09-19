package de.j4velin.smarthome.proximityturnon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.j4velin.smarthome.proximityturnon.ui.DashboardScreen
import de.j4velin.smarthome.proximityturnon.ui.DashboardViewModel
import de.j4velin.smarthome.proximityturnon.ui.theme.ProximityTurnOnTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProximityTurnOnTheme {
                DashboardScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume(this)
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause(this)
    }
}
