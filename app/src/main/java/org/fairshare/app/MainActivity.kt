package org.fairshare.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.ui.FairshareRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[AppModel::class.java]
        setContent { FairshareRoot(model.repository) }
    }
}

class AppModel(application: Application) : AndroidViewModel(application) {
    val repository = LocalRepository(application)
    override fun onCleared() { repository.close() }
}
