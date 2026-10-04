package com.driveorderpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.driveorderpad.ui.OrderPadApp
import com.driveorderpad.ui.OrderPadTheme
import com.driveorderpad.ui.OrderPadViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OrderPadApplication).container
        val factory = viewModelFactory {
            initializer { OrderPadViewModel(createSavedStateHandle(), container::dependencies) }
        }
        setContent { OrderPadTheme { OrderPadApp(viewModel(factory = factory)) } }
    }
}
