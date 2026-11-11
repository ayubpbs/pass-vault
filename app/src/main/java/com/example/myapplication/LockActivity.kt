package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LockActivity : AppCompatActivity() {

    private lateinit var vault: Vault
    private lateinit var tilPw: TextInputLayout
    private lateinit var etPw: TextInputEditText
    private lateinit var tilConfirm: TextInputLayout
    private lateinit var etConfirm: TextInputEditText
    private lateinit var btnUnlock: Button
    private lateinit var btnFinger: Button

    private var setup = false
    private var busy = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_lock)
        vault = Vault(this)

        val tvSub = findViewById<TextView>(R.id.tvSub)
        tilPw = findViewById(R.id.tilPassword)
        etPw = findViewById(R.id.etPassword)
        tilConfirm = findViewById(R.id.tilConfirm)
        etConfirm = findViewById(R.id.etConfirm)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnFinger = findViewById(R.id.btnFinger)

        val state = vault.state()
        setup = state == Vault.State.NONE
        when (state) {
            Vault.State.NONE -> {
                tvSub.text = "Create your app password (at least ${Vault.MIN_PASSWORD} characters)"
                tilConfirm.visibility = View.VISIBLE
                btnUnlock.text = "Set password"
            }
            Vault.State.LEGACY ->
                tvSub.text = "Enter your app password (one-time security upgrade)"
            Vault.State.READY ->
                tvSub.text = "Enter your app password"
        }
        btnFinger.visibility = View.GONE

        btnUnlock.setOnClickListener {
            if (busy) return@setOnClickListener
            val p = etPw.text.toString()
            tilPw.error = null
            tilConfirm.error = null
            if (setup) {
                when {
                    p.length < Vault.MIN_PASSWORD ->
                        tilPw.error = "At least ${Vault.MIN_PASSWORD} characters"
                    p != etConfirm.text.toString() ->
                        tilConfirm.error = "Passwords do not match"
                    else -> work { vault.create(p) }
                }
            } else {
                if (vault.lockRemainingMs() > 0) {
                    refreshLock()
                } else {
                    work { vault.unlock(p) }
                }
            }
        }

        btnFinger.setOnClickListener { showBiometric() }

        if (!setup) {
            refreshLock()
            if (state == Vault.State.READY && vault.bioEnrolled() && canUseBiometric()) {
                btnFinger.visibility = View.VISIBLE
                showBiometric()
            }
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // ---------------- Password unlock ----------------

    /** Key derivation is slow on purpose, so it runs off the main thread. */
    private fun work(block: () -> Vault.Result) {
        busy = true
        btnUnlock.isEnabled = false
        Thread {
            val r = try {
                block()
            } catch (e: Exception) {
                Vault.Result.ERROR
            }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) {
                    Session.lock()
                } else {
                    onResult(r)
                }
            }
        }.start()
    }

    private fun onResult(r: Vault.Result) {
        when (r) {
            Vault.Result.OK -> {
                vault.resetFailures()
                afterUnlock()
            }
            Vault.Result.WRONG -> {
                vault.recordFailure()
                tilPw.error = "Wrong password"
                refreshLock()
            }
            Vault.Result.ERROR -> {
                tilPw.error = "Could not open the vault data"
                btnUnlock.isEnabled = true
            }
        }
    }

    private fun refreshLock() {
        timer?.cancel()
        timer = null
        val ms = vault.lockRemainingMs()
        if (ms <= 0L) {
            btnUnlock.isEnabled = !busy
            return
        }
        btnUnlock.isEnabled = false
        timer = object : CountDownTimer(ms, 1000) {
            override fun onTick(left: Long) {
                tilPw.error = "Too many wrong attempts. Try again in ${(left + 999) / 1000}s"
            }

            override fun onFinish() {
                tilPw.error = null
                btnUnlock.isEnabled = !busy
            }
        }.start()
    }

    private fun afterUnlock() {
        if (canUseBiometric() && !vault.bioEnrolled()) enrollBiometric() else goMain()
    }

    // ---------------- Fingerprint ----------------

    private fun canUseBiometric() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

    /** One-time, optional: bind the data key to the fingerprint. */
    private fun enrollBiometric() {
        val cipher = try {
            Crypto.bioEncryptCipher()
        } catch (e: Exception) {
            goMain()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        result.cryptoObject?.cipher?.let { vault.storeBioWrap(it) }
                    } catch (e: Exception) {
                        vault.clearBio()
                    }
                    goMain()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    goMain()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable fingerprint unlock")
            .setSubtitle("Optional. You can always use your app password.")
            .setNegativeButtonText("Skip")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showBiometric() {
        val cipher = try {
            Crypto.bioDecryptCipher(vault.bioIv())
        } catch (e: Exception) {
            // Fingerprints changed (key invalidated) or key lost: fall back to the password
            vault.clearBio()
            btnFinger.visibility = View.GONE
            Toast.makeText(this, "Fingerprint changed. Use your app password.", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null && vault.unlockWithBio(c)) {
                        vault.resetFailures()
                        goMain()
                    } else {
                        Toast.makeText(this@LockActivity, "Use your app password", Toast.LENGTH_LONG).show()
                    }
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pass Vault")
            .setNegativeButtonText("Use app password")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
