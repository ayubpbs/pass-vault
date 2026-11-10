package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var vault: Vault
    private lateinit var adapter: EntryAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvEmpty: TextView
    private var all = mutableListOf<Entry>()

    // true while the system file picker is open (so the app does not lock itself)
    private var picking = false
    private var pendingBackupPw: String? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val pw = pendingBackupPw
            pendingBackupPw = null
            if (uri != null && pw != null) doExport(uri, pw)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askRestorePassword(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_main)
        vault = Vault(this)

        etSearch = findViewById(R.id.etSearch)
        tvEmpty = findViewById(R.id.tvEmpty)
        val rv = findViewById<RecyclerView>(R.id.rvEntries)

        adapter = EntryAdapter(
            onCopyId = { Clip.copy(this, "ID", it.userId) },
            onCopyPassword = { Clip.copy(this, "Password", it.password) },
            onEdit = { showEditor(it) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etSearch.doAfterTextChanged { applyFilter() }
        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd)
            .setOnClickListener { showEditor(null) }
        findViewById<Button>(R.id.btnBackup).setOnClickListener { askBackupPassword() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            picking = true
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onStart() {
        super.onStart()
        if (!Session.unlocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        picking = false
        all = vault.load()
        applyFilter()
    }

    override fun onStop() {
        super.onStop()
        // Lock whenever the app goes to background
        if (!isChangingConfigurations && !picking) Session.unlocked = false
    }

    private fun applyFilter() {
        val q = etSearch.text.toString().trim().lowercase()
        val shown = all
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.userId.lowercase().contains(q) }
            .sortedBy { it.name.lowercase() }
        adapter.submit(shown)
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- Backup / Restore ----------------

    private fun askBackupPassword() {
        val v = layoutInflater.inflate(R.layout.dialog_backup, null)
        v.findViewById<TextView>(R.id.tvBackupInfo).text =
            "Set a password for the backup file. You need it to restore. " +
                    "Save the file in Google Drive or your PC."
        val tilPw = v.findViewById<TextInputLayout>(R.id.tilBackupPw)
        val tilConfirm = v.findViewById<TextInputLayout>(R.id.tilBackupConfirm)
        val etPw = v.findViewById<EditText>(R.id.etBackupPw)
        val etConfirm = v.findViewById<EditText>(R.id.etBackupConfirm)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Backup")
            .setView(v)
            .setPositiveButton("Save file", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = etPw.text.toString()
            tilPw.error = null
            tilConfirm.error = null
            when {
                p.length < 6 -> tilPw.error = "At least 6 characters"
                p != etConfirm.text.toString() -> tilConfirm.error = "Passwords do not match"
                else -> {
                    pendingBackupPw = p
                    picking = true
                    d.dismiss()
                    exportLauncher.launch("passvault-backup.pvault")
                }
            }
        }
    }

    private fun doExport(uri: Uri, pw: String) {
        try {
            val bytes = Crypto.encryptWithPassword(vault.toJson(all), pw)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "Backup saved (${all.size} entries)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun askRestorePassword(uri: Uri) {
        val v = layoutInflater.inflate(R.layout.dialog_backup, null)
        v.findViewById<TextView>(R.id.tvBackupInfo).text =
            "Enter the password you set when you made this backup."
        v.findViewById<View>(R.id.tilBackupConfirm).visibility = View.GONE
        val tilPw = v.findViewById<TextInputLayout>(R.id.tilBackupPw)
        val etPw = v.findViewById<EditText>(R.id.etBackupPw)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Restore")
            .setView(v)
            .setPositiveButton("Restore", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val json = Crypto.decryptWithPassword(bytes, etPw.text.toString())
                val incoming = vault.fromJson(json)
                val ids = all.map { it.id }.toSet()
                val added = incoming.filter { it.id !in ids }
                all.addAll(added)
                vault.save(all)
                applyFilter()
                Toast.makeText(this, "${added.size} entries restored", Toast.LENGTH_LONG).show()
                d.dismiss()
            } catch (e: Exception) {
                tilPw.error = "Wrong password or invalid file"
            }
        }
    }

    // ---------------- Add / Edit ----------------

    private fun showEditor(existing: Entry?) {
        val v = layoutInflater.inflate(R.layout.dialog_entry, null)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etId = v.findViewById<EditText>(R.id.etId)
        val etPw = v.findViewById<EditText>(R.id.etPw)
        existing?.let {
            etName.setText(it.name)
            etId.setText(it.userId)
            etPw.setText(it.password)
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add password" else "Edit password")
            .setView(v)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) builder.setNeutralButton("Delete", null)

        val d = builder.create()
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString()
            if (name.isEmpty()) {
                etName.error = "Required"
                return@setOnClickListener
            }
            if (existing == null) {
                all.add(Entry(name = name, userId = id, password = pw))
            } else {
                existing.name = name
                existing.userId = id
                existing.password = pw
            }
            vault.save(all)
            applyFilter()
            d.dismiss()
        }

        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete \"${existing!!.name}\"?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    all.removeAll { it.id == existing.id }
                    vault.save(all)
                    applyFilter()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
