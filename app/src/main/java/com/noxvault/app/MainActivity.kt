package com.noxvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Единственная Activity приложения.
 *
 * FragmentActivity нужна для BiometricPrompt (AndroidX Biometric).
 * FLAG_SECURE: содержимое нельзя снять скриншотом, в переключателе задач
 * Android показывает пустой снимок окна — секреты там не светятся.
 * ProcessLifecycleOwner отслеживает уход приложения в фон для автоблокировки.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                viewModel.onAppBackgrounded()
            }

            override fun onStart(owner: LifecycleOwner) {
                viewModel.onAppForegrounded()
            }
        })

        setContent {
            NoxVaultApp(viewModel = viewModel)
        }
    }
}
