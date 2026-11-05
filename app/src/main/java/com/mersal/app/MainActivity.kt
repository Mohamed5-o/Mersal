package com.mersal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dot: View
    private lateinit var txtStatus: TextView
    private lateinit var btnEnable: TextView
    private lateinit var txtHelp: TextView
    private lateinit var etPhone: EditText
    private lateinit var etMessage: EditText
    private lateinit var etCount: EditText
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnQuick: Button
    private lateinit var progress: ProgressBar
    private lateinit var txtState: TextView
    private lateinit var txtPicked: TextView

    private val prefs by lazy { getSharedPreferences("mersal", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dot = findViewById(R.id.dot)
        txtStatus = findViewById(R.id.txtStatus)
        btnEnable = findViewById(R.id.btnEnable)
        txtHelp = findViewById(R.id.txtHelp)
        etPhone = findViewById(R.id.etPhone)
        etMessage = findViewById(R.id.etMessage)
        etCount = findViewById(R.id.etCount)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnQuick = findViewById(R.id.btnQuick)
        progress = findViewById(R.id.progress)
        txtState = findViewById(R.id.txtState)
        txtPicked = findViewById(R.id.txtPicked)
        findViewById<TextView>(R.id.btnPick).setOnClickListener { showPicker() }

        if (savedInstanceState == null) {
            etPhone.setText(prefs.getString("phone", ""))
            etMessage.setText(prefs.getString("message", ""))
            etCount.setText(prefs.getString("count", "5"))
            etInterval.setText(prefs.getString("interval", "5"))
        }

        findViewById<TextView>(R.id.btnLang).setOnClickListener {
            val cur = resources.configuration.locales[0].language
            val next = if (cur == "ar") "en" else "ar"
            saveFields()
            prefs.edit().putString("lang", next).apply()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        }

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { start(false) }
        btnQuick.setOnClickListener { start(true) }
        btnStop.setOnClickListener { SendEngine.stop() }
    }

    override fun onStart() {
        super.onStart()
        SendEngine.listener = { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        SendEngine.listener = null
        saveFields()
    }


    // ---------- WhatsApp contacts picker ----------
    private data class Contact(val name: String, val number: String)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPicker() else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
    }

    private fun parseNumbers(raw: String): List<String> =
        raw.split(Regex("[\\n,;،]+"))
            .map { it.filter { ch -> ch.isDigit() }.trimStart('0') }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun loadWhatsAppContacts(): List<Contact> {
        val out = LinkedHashMap<String, Contact>()
        val mimes = arrayOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
        )
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (?,?)",
            mimes,
            "${ContactsContract.Data.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val jid = c.getString(1) ?: continue
                if (!jid.contains("@s.whatsapp.net")) continue
                val num = jid.substringBefore("@").filter { it.isDigit() }
                if (num.length in 8..15 && num !in out) out[num] = Contact(name, num)
            }
        }
        return out.values.toList()
    }

    private fun showPicker() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        val contacts = loadWhatsAppContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, R.string.no_contacts, Toast.LENGTH_LONG).show()
            return
        }
        val known = contacts.map { it.number }.toSet()
        val existing = parseNumbers(etPhone.text.toString())
        val selected = LinkedHashSet(existing.filter { it in known })
        val extras = existing.filter { it !in known }
        val dp = resources.displayMetrics.density

        val search = EditText(this).apply {
            hint = getString(R.string.search)
            setBackgroundResource(R.drawable.bg_input)
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.sub_dim))
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        val shown = ArrayList(contacts)
        val adapter = object : ArrayAdapter<Contact>(this, android.R.layout.simple_list_item_multiple_choice, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as CheckedTextView
                val c = getItem(position)!!
                v.text = c.name + "\n+" + c.number
                v.isChecked = c.number in selected
                return v
            }
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (340 * dp).toInt())
            setOnItemClickListener { _, _, pos, _ ->
                val c = adapter.getItem(pos) ?: return@setOnItemClickListener
                if (c.number in selected) {
                    selected.remove(c.number)
                } else if (selected.size + extras.size >= SendEngine.MAX_NUMBERS) {
                    Toast.makeText(this@MainActivity, R.string.max_numbers, Toast.LENGTH_SHORT).show()
                } else {
                    selected.add(c.number)
                }
                adapter.notifyDataSetChanged()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                adapter.clear()
                adapter.addAll(contacts.filter { it.name.contains(q, true) || it.number.contains(q) })
                adapter.notifyDataSetChanged()
            }
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
            addView(search)
            addView(list)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                etPhone.setText((extras + selected).joinToString("\n"))
                val byNum = contacts.associate { it.number to it.name }
                if (selected.isEmpty()) {
                    txtPicked.visibility = View.GONE
                } else {
                    txtPicked.text = selected.joinToString("\n") { byNum[it] + "  •  +" + it }
                    txtPicked.visibility = View.VISIBLE
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveFields() {
        prefs.edit()
            .putString("phone", etPhone.text.toString())
            .putString("message", etMessage.text.toString())
            .putString("count", etCount.text.toString())
            .putString("interval", etInterval.text.toString())
            .apply()
    }

    private fun start(quick: Boolean) {
        val allPhones = etPhone.text.toString()
            .split(Regex("[\\n,;،]+"))
            .map { it.filter { ch -> ch.isDigit() }.trimStart('0') }
            .filter { it.isNotEmpty() }
            .distinct()
        val phones = if (quick) allPhones.take(1) else allPhones
        if (phones.isEmpty() || phones.size > SendEngine.MAX_NUMBERS || phones.any { it.length !in 8..15 }) {
            etPhone.error = getString(R.string.err_phone); etPhone.requestFocus(); return
        }
        val msg = etMessage.text.toString().trim()
        if (msg.isEmpty()) {
            etMessage.error = getString(R.string.err_message); etMessage.requestFocus(); return
        }
        val count = etCount.text.toString().toIntOrNull() ?: 0
        val maxCount = if (quick) SendEngine.MAX_QUICK else SendEngine.MAX_COUNT
        if (count !in 1..maxCount) {
            etCount.error = getString(if (quick) R.string.err_quick else R.string.err_count); etCount.requestFocus(); return
        }
        if (count * phones.size > SendEngine.MAX_TOTAL) {
            etCount.error = getString(R.string.err_total); etCount.requestFocus(); return
        }
        val gap = if (quick) 0 else (etInterval.text.toString().toIntOrNull() ?: 0)
        if (!quick && gap < SendEngine.MIN_GAP_SECONDS) {
            etInterval.error = getString(R.string.err_interval); etInterval.requestFocus(); return
        }
        val svc = MersalService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.err_service, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        saveFields()
        svc.begin(SendConfig(phones, msg, count, gap * 1000L))
    }

    private fun refresh() {
        val on = MersalService.instance != null
        if (!on && SendEngine.state == SendEngine.State.RUNNING) {
            SendEngine.update(SendEngine.State.IDLE, 0, 0)
        }
        val running = SendEngine.state == SendEngine.State.RUNNING

        dot.setBackgroundResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        txtStatus.setText(if (on) R.string.status_on else R.string.status_off)
        btnEnable.visibility = if (on) View.GONE else View.VISIBLE
        txtHelp.visibility = if (on) View.GONE else View.VISIBLE

        btnStart.isEnabled = !running
        btnStart.alpha = if (running) 0.4f else 1f
        btnQuick.isEnabled = !running
        btnQuick.alpha = if (running) 0.4f else 1f
        btnStop.isEnabled = running
        btnStop.alpha = if (running) 1f else 0.4f
        listOf(etPhone, etMessage, etCount, etInterval).forEach { it.isEnabled = !running }

        val sent = SendEngine.sent
        val total = SendEngine.total
        progress.max = if (total > 0) total else 1
        progress.progress = sent

        txtState.text = when (SendEngine.state) {
            SendEngine.State.IDLE -> getString(R.string.state_idle)
            SendEngine.State.RUNNING -> getString(R.string.state_running, sent, total)
            SendEngine.State.DONE -> getString(R.string.state_done, sent)
            SendEngine.State.STOPPED -> getString(R.string.state_stopped, sent, total)
            SendEngine.State.TIMEOUT -> getString(R.string.state_timeout)
            SendEngine.State.NO_WHATSAPP -> getString(R.string.state_no_whatsapp)
        }
    }
}
