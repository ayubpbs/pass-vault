package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LockActivity : AppCompatActivity() {

    private lateinit var vault: Vault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_lock)
        vault = Vault(this)

        val tvSub = findViewById<TextView>(R.id.tvSub)
        val tilPw = findViewById<TextInputLayout>(R.id.tilPassword)
        val etPw = findViewById<TextInputEditText>(R.id.etPassword)
        val tilConfirm = findViewById<TextInputLayout>(R.id.tilConfirm)
        val etConfirm = findViewById<TextInputEditText>(R.id.etConfirm)
        val btnUnlock = findViewById<Button>(R.id.btnUnlock)
        val btnFinger = findViewById<Button>(R.id.btnFinger)

        val setup = !vault.hasAppPassword()
        if (setup) {
            tvSub.text = "Create your app password"
            tilConfirm.visibility = View.VISIBLE
            btnUnlock.text = "Set password"
            btnFinger.visibility = View.GONE
        } else {
            tvSub.text = "Enter your app password"
        }

        btnUnlock.setOnClickListener {
            val p = etPw.text.toString()
            tilPw.error = null
            tilConfirm.error = null
            if (setup) {
                when {
                    p.length < 6 -> tilPw.error = "At least 6 characters"
                    p != etConfirm.text.toString() -> tilConfirm.error = "Passwords do not match"
                    else -> {
                        vault.setAppPassword(p)
                        unlock()
                    }
                }
            } else {
                if (vault.verify(p)) unlock() else tilPw.error = "Wrong password"
            }
        }

        btnFinger.setOnClickListener { showBiometric() }

        if (!setup) {
            if (canUseBiometric()) {
                btnFinger.visibility = View.VISIBLE
                showBiometric()
            } else {
                btnFinger.visibility = View.GONE
            }
        }
    }

    private fun canUseBiometric() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pass Vault")
            .setNegativeButtonText("Use app password")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun unlock() {
        Session.unlocked = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
