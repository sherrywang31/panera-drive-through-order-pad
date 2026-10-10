package com.driveorderpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.driveorderpad.bagels.BagelApp
import com.driveorderpad.bagels.BagelViewModel
import com.driveorderpad.ui.OrderPadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OrderPadApplication).container
        val factory = viewModelFactory {
            initializer { BagelViewModel(createSavedStateHandle(), container.bagelRepository, container::bagelCatalog) }
        }
        setContent { OrderPadTheme { BagelApp(viewModel(factory = factory)) } }
    }
}
