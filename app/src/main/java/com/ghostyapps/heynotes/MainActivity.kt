package com.ghostyapps.heynotes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope // <--- BU ÇOK ÖNEMLİ (PİL TASARRUFU İÇİN)
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Collections
import androidx.lifecycle.lifecycleScope


class MainActivity : AppCompatActivity() {

    // Helpers
    private var driveServiceHelper: DriveServiceHelper? = null
    private lateinit var localServiceHelper: LocalServiceHelper
    private lateinit var notesAdapter: NotesAdapter
    private lateinit var folderAdapter: FolderPillAdapter
    private lateinit var colorStorage: ColorStorage
    private lateinit var securityStorage: SecurityStorage

    // State
    private var isDriveMode = false
    private var isSelectionMode = false
    private var isGridMode = false
    private var isFabMenuOpen = false
    private var userName: String = "User"

    // Data Holders
    private var currentNotes = mutableListOf<NoteItem>()
    private var currentFolders = mutableListOf<NoteItem>()

    // Navigation State
    private var currentLocalDir: File? = null
    private var currentDriveId: String? = null
    private var rootDriveId: String? = null
    private val driveBreadcrumbs = mutableListOf("Main")

    // UI Variables
    private lateinit var tvAppTitle: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var recyclerNotes: RecyclerView
    private lateinit var recyclerFolders: RecyclerView
    private lateinit var fabCreate: FloatingActionButton

    // FAB Menu Variables
    private lateinit var fabMenuContainer: LinearLayout
    private lateinit var fabOverlay: View
    private lateinit var btnNewNote: View
    private lateinit var btnNewFolder: View

    override fun onCreate(savedInstanceState: Bundle?) {

        // --- TEMA YÜKLEME (EN ÜSTE) ---
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode)
        // ------------------------------

        super.onCreate(savedInstanceState)

        // Status Bar Optimization
        window.statusBarColor = resources.getColor(R.color.header_background, theme)
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        setContentView(R.layout.activity_main)

        // Init Helpers
        localServiceHelper = LocalServiceHelper(this)
        colorStorage = ColorStorage(this)
        securityStorage = SecurityStorage(this)
        currentLocalDir = localServiceHelper.getRootFolder()

        checkOnboarding()

        setupUI()
        setupAdapters()
        setupBackNavigation()

        loadContent()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        userName = prefs.getString("user_name", "User") ?: "User"

        // Sadece initialized ise ve ekrana dönüldüyse yükle
        // Bu gereksiz yüklemeleri engeller
        if (::localServiceHelper.isInitialized) loadContent()
    }

    // --- ONBOARDING ---
    private fun checkOnboarding() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("user_name", null)

        if (savedName == null) {
            val intent = Intent(this, WelcomeActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            userName = savedName
            checkStoragePermission()
        }
    }

    // --- UI SETUP ---
    private fun setupUI() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        fabCreate = findViewById(R.id.fabCreate)
        fabMenuContainer = findViewById(R.id.fabMenuContainer)
        fabOverlay = findViewById(R.id.fabOverlay)
        btnNewNote = findViewById(R.id.btnNewNote)
        btnNewFolder = findViewById(R.id.btnNewFolder)

        // About Page YERİNE Settings Menu
        findViewById<ImageView>(R.id.ivHeaderGraphic).setOnClickListener {
            showSettingsMenu(it) // <--- DEĞİŞTİ
        }

        findViewById<ImageView>(R.id.btnToggleView).setOnClickListener {
            isGridMode = !isGridMode
            updateLayoutManager()
        }

        tvAppTitle.setOnClickListener {
            val isRoot = (!isDriveMode && currentLocalDir?.name == "HeyNotes") || (isDriveMode && currentDriveId == rootDriveId)
            if (isRoot) {
                val intent = Intent(this, WelcomeActivity::class.java)
                intent.putExtra("IS_EDIT_MODE", true)
                startActivity(intent)
            }
        }

        fabCreate.setOnClickListener {
            if (isSelectionMode) showDeleteConfirmation() else toggleFabMenu()
        }

        fabOverlay.setOnClickListener { if (isFabMenuOpen) toggleFabMenu() }
        btnNewNote.setOnClickListener { toggleFabMenu(); openEditor(null) }
        btnNewFolder.setOnClickListener { toggleFabMenu(); showCreateFolderDialog() }
    }

    private fun toggleFabMenu() {
        isFabMenuOpen = !isFabMenuOpen

        if (isFabMenuOpen) {
            fabMenuContainer.visibility = View.VISIBLE
            fabOverlay.visibility = View.VISIBLE
            fabMenuContainer.alpha = 0f
            fabMenuContainer.translationY = 100f
            fabMenuContainer.animate().alpha(1f).translationY(0f).setDuration(250).start()
            fabCreate.animate().rotation(45f).setDuration(250).start()
        } else {
            fabMenuContainer.animate().alpha(0f).translationY(100f).setDuration(200)
                .withEndAction {
                    fabMenuContainer.visibility = View.GONE
                    fabOverlay.visibility = View.GONE
                }.start()
            fabCreate.animate().rotation(0f).setDuration(250).start()
        }
    }

    private fun setupAdapters() {
        folderAdapter = FolderPillAdapter(
            onItemClick = { folder, view ->
                if (isSelectionMode) {
                    toggleFolderSelection(folder)
                } else {
                    if (folder.isActive && folder.id != "ROOT") {
                        showColorPopup(folder, view)
                    } else {
                        handleNavigation(folder)
                    }
                }
            },
            onItemLongClick = { folder ->
                if (folder.id == "ROOT" || folder.isActive) {
                    Toast.makeText(this, "Cannot delete current folder", Toast.LENGTH_SHORT).show()
                } else {
                    if (!isSelectionMode) { isSelectionMode = true; updateFabIcon() }
                    toggleFolderSelection(folder)
                }
            }
        )

        recyclerFolders = findViewById(R.id.recyclerFolders)
        recyclerFolders.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerFolders.adapter = folderAdapter

        notesAdapter = NotesAdapter(
            onItemClick = { item ->
                if (isSelectionMode) toggleSelection(item)
                else openEditor(item)
            },
            onItemLongClick = { item ->
                if (!isSelectionMode) { isSelectionMode = true; updateFabIcon(); toggleSelection(item) }
            },
            onIconClick = { item, view ->
                if (!isSelectionMode) showColorPopup(item, view)
                else toggleSelection(item)
            }
        )
        recyclerNotes = findViewById(R.id.recyclerNotes)
        updateLayoutManager()
        recyclerNotes.adapter = notesAdapter
    }

    private fun updateLayoutManager() {
        notesAdapter.isGridMode = isGridMode
        val toggleBtn = findViewById<ImageView>(R.id.btnToggleView)

        if (isGridMode) {
            recyclerNotes.layoutManager = GridLayoutManager(this, 2)
            toggleBtn.setImageResource(R.drawable.ic_view_list)
        } else {
            recyclerNotes.layoutManager = LinearLayoutManager(this)
            toggleBtn.setImageResource(R.drawable.ic_view_grid)
        }
        notesAdapter.notifyDataSetChanged()
    }

    private fun handleNavigation(item: NoteItem) {
        if (item.id == "ROOT") {
            if (isDriveMode) {
                currentDriveId = rootDriveId
                driveBreadcrumbs.clear(); driveBreadcrumbs.add("Main")
            } else {
                currentLocalDir = localServiceHelper.getRootFolder()
            }
            loadContent()
            return
        }

        if (item.isActive) return

        if (item.isLocked) {
            showUnlockDialog(item)
            return
        }

        if (isDriveMode) {
            driveBreadcrumbs.add(item.name)
            currentDriveId = item.id
            loadContent()
        } else {
            currentLocalDir = File(item.id)
            loadContent()
        }
    }

    // --- OPTIMIZED CONTENT LOADING ---
    private fun loadContent() {
        isSelectionMode = false
        updateFabIcon()

        val isRoot = (!isDriveMode && currentLocalDir?.name == "HeyNotes") || (isDriveMode && currentDriveId == rootDriveId)

        // HEADER UPDATE (UI THREAD - HIZLI)
        if (isRoot) {
            val greeting = getGreeting()
            tvAppTitle.text = "Hey, $userName"
            tvGreeting.text = greeting
            tvGreeting.visibility = View.VISIBLE
            // Alt başlık veriler gelince güncellenecek
            tvSubtitle.text = "Loading..."
        } else {
            val name = if (isDriveMode) driveBreadcrumbs.last() else currentLocalDir?.name ?: ""
            tvAppTitle.text = name
            tvGreeting.visibility = View.GONE
            tvSubtitle.text = if (isDriveMode) "Drive Folder" else "Local Folder"
        }

        // BACKGROUND TASK START (lifecycleScope: Pil Tasarrufu)
        lifecycleScope.launch(Dispatchers.IO) {

            // 1. DATA FETCHING (HEAVY WORK)
            val rawItems: List<NoteItem>
            var totalNoteCount = 0

            if (isDriveMode) {
                if (currentDriveId == null) return@launch
                val files = driveServiceHelper?.listFiles(currentDriveId!!) ?: emptyList()
                rawItems = files.map { NoteItem(it.name, it.mimeType.contains("folder"), it.id) }
                // Drive Recursive count is hard, keeping it simple for now
            } else {
                if (currentLocalDir == null) return@launch

                // Suspend function call (Optimized in LocalServiceHelper)
                rawItems = localServiceHelper.listItems(currentLocalDir!!)

                // Only count total if at root (Heavy Operation)
                if (isRoot) {
                    totalNoteCount = localServiceHelper.getTotalNoteCount()
                }
            }

            // 2. DATA PROCESSING (Colors, Security)
            val processedItems = rawItems.map {
                it.copy(
                    color = colorStorage.getColor(it.id),
                    isLocked = securityStorage.isLocked(it.id)
                )
            }

            val newSubFolders = processedItems.filter { it.isFolder }.toMutableList()
            val newNotes = processedItems.filter { !it.isFolder }.toMutableList()

            // 3. UI UPDATE (Switch back to Main Thread)
            withContext(Dispatchers.Main) {
                // Build Pills
                val pillList = mutableListOf<NoteItem>()
                pillList.add(NoteItem("Main", true, "ROOT", color = Color.parseColor("#BDBDBD"), isActive = isRoot))

                if (!isRoot) {
                    val currentName = if (isDriveMode) driveBreadcrumbs.last() else currentLocalDir?.name ?: "Folder"
                    val currentId = if (isDriveMode) currentDriveId!! else currentLocalDir!!.absolutePath
                    val currentColor = colorStorage.getColor(currentId) ?: Color.parseColor("#616161")
                    pillList.add(NoteItem(currentName, true, currentId, color = currentColor, isActive = true))
                }

                newSubFolders.forEach { it.isActive = false }
                pillList.addAll(newSubFolders)

                // Update Lists
                currentFolders = pillList
                currentNotes = newNotes
                folderAdapter.submitList(currentFolders)
                notesAdapter.submitList(currentNotes)

                // Update Subtitle with Calculated Count
                if (isRoot) {
                    if (!isDriveMode) tvSubtitle.text = "You have $totalNoteCount Notes in total."
                    else tvSubtitle.text = "Google Drive Storage"
                } else {
                    tvSubtitle.text = "${currentNotes.size} Notes"
                }
            }
        }
    }

    private fun getGreeting(): String {
        val c = Calendar.getInstance()
        val timeOfDay = c.get(Calendar.HOUR_OF_DAY)
        return when (timeOfDay) {
            in 0..11 -> "Good morning."
            in 12..17 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    // --- SELECTION & DELETE ---
    private fun toggleSelection(item: NoteItem) {
        item.isSelected = !item.isSelected
        checkSelectionState()
        notesAdapter.notifyDataSetChanged()
    }

    private fun toggleFolderSelection(item: NoteItem) {
        if (item.id == "ROOT" || item.isActive) return
        item.isSelected = !item.isSelected
        checkSelectionState()
        folderAdapter.notifyDataSetChanged()
    }

    private fun checkSelectionState() {
        val anyNotes = currentNotes.any { it.isSelected }
        val anyFolders = currentFolders.any { it.isSelected }
        if (!anyNotes && !anyFolders) {
            isSelectionMode = false
            updateFabIcon()
        }
    }

    private fun updateFabIcon() {
        if (isFabMenuOpen) toggleFabMenu()

        if (isSelectionMode) {
            fabCreate.setImageResource(android.R.drawable.ic_menu_delete)
            fabCreate.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
            fabCreate.setColorFilter(android.graphics.Color.WHITE)
            fabCreate.rotation = 0f
        } else {
            fabCreate.setImageResource(android.R.drawable.ic_input_add)
            fabCreate.backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.text_color, theme))
            fabCreate.setColorFilter(android.graphics.Color.WHITE)
            fabCreate.rotation = 0f
        }
    }

    private fun showDeleteConfirmation() {
        val totalCount = currentNotes.count { it.isSelected } + currentFolders.count { it.isSelected }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_confirm, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnDelete = dialogView.findViewById<TextView>(R.id.btnDelete)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        tvMessage.text = "Are you sure you want to delete $totalCount item(s)?\nFolders will be deleted with their contents."

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDelete.setOnClickListener {
            val lockedFolder = currentFolders.find { it.isSelected && it.isLocked }
            if (lockedFolder != null) {
                dialog.dismiss()
                showPinDialogForDeletion(lockedFolder)
            } else {
                deleteSelectedItems()
                dialog.dismiss()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPinDialogForDeletion(lockedItem: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        tvTitle.text = "Security Check"
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = "Enter PIN for '${lockedItem.name}' to confirm deletion."
        btnConfirm.text = "Delete"
        btnConfirm.setTextColor(Color.parseColor("#D32F2F"))
        etPin.hint = "PIN"

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val pin = etPin.text.toString()
            if (securityStorage.checkPassword(lockedItem.id, pin)) {
                deleteSelectedItems()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Wrong Password", Toast.LENGTH_SHORT).show()
                etPin.setText("")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            etPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etPin, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun deleteSelectedItems() {
        val notesToDelete = currentNotes.filter { it.isSelected }
        val foldersToDelete = currentFolders.filter { it.isSelected }
        val allItems = notesToDelete + foldersToDelete

        // DÜZELTME: lifecycleScope kullanıldı
        lifecycleScope.launch(Dispatchers.IO) {
            allItems.forEach { item ->
                if (isDriveMode) {
                    driveServiceHelper?.deleteFile(item.id)
                } else {
                    localServiceHelper.deleteFile(item.id)
                }
            }
            // UI güncellemesi için Ana Thread
            withContext(Dispatchers.Main) {
                loadContent()
                Toast.makeText(this@MainActivity, "Deleted.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // --- DIALOGS (Create, Color, Lock, Unlock) ---
    private fun showCreateFolderDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_folder, null)
        val etFolderName = dialogView.findViewById<EditText>(R.id.etFolderName)
        val colorContainer = dialogView.findViewById<LinearLayout>(R.id.colorContainer)
        val btnCreate = dialogView.findViewById<TextView>(R.id.btnCreate)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        var selectedColorHex: String? = null
        var selectedDotView: ImageView? = null

        val colors = listOf("#000000", "#616161", "#EF5350", "#FFA726", "#FFEE58", "#66BB6A", "#42A5F5", "#AB47BC", "#EC407A")

        for (hex in colors) {
            val dot = ImageView(this)
            val size = (32 * resources.displayMetrics.density).toInt()
            val margin = (8 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            dot.layoutParams = params
            dot.setPadding(16, 16, 16, 16)

            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate()
            bg?.setTint(Color.parseColor(hex))
            dot.background = bg

            dot.setColorFilter(Color.WHITE)
            dot.tag = hex

            if (hex == "#000000") {
                dot.setImageResource(R.drawable.ic_lock_closed)
                dot.imageAlpha = 150
            } else {
                dot.setImageResource(R.drawable.ic_check_tick)
                dot.imageAlpha = 0
            }

            dot.setOnClickListener {
                if (selectedDotView != null) {
                    val prevHex = selectedDotView!!.tag as String
                    if (prevHex == "#000000") selectedDotView!!.imageAlpha = 150 else selectedDotView!!.imageAlpha = 0
                }
                selectedDotView = dot
                selectedColorHex = hex
                dot.imageAlpha = 255
            }
            colorContainer.addView(dot)
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCreate.setOnClickListener {
            val name = etFolderName.text.toString().trim()
            if (name.isNotEmpty()) {
                if (selectedColorHex == "#000000") {
                    dialog.dismiss()
                    showPinDialogForCreation(name)
                } else {
                    createFolder(name, selectedColorHex)
                    dialog.dismiss()
                }
            } else {
                Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPinDialogForCreation(folderName: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        tvTitle.text = "Secure Folder Setup"
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = "Set a PIN for '$folderName'.\nThis folder will be hidden from file managers."
        btnConfirm.text = "Create"
        etPin.hint = "PIN"

        etPin.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                btnConfirm.performClick()
                true
            } else false
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val pin = etPin.text.toString()
            if (pin.length >= 4) {
                createLockedFolder(folderName, pin)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            etPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etPin, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun createLockedFolder(name: String, pin: String) {
        val finishLocking = { id: String ->
            securityStorage.setPassword(id, pin)
            colorStorage.saveColor(id, "#000000")

            if (!isDriveMode) {
                val newPath = localServiceHelper.moveFolderToPrivate(name)
                if (newPath != null) {
                    securityStorage.setPassword(newPath, pin)
                    colorStorage.saveColor(newPath, "#000000")
                    securityStorage.setPassword(id, "")
                }
            }

            runOnUiThread {
                loadContent()
                Toast.makeText(this, "Secure Folder Created", Toast.LENGTH_SHORT).show()
            }
        }

        // DÜZELTME: lifecycleScope kullanıldı
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                val newId = driveServiceHelper?.createFolder(currentDriveId!!, name)
                if (newId != null) finishLocking(newId)
            } else {
                localServiceHelper.createFolder(currentLocalDir!!, name)
                val newFolder = File(currentLocalDir, name)
                finishLocking(newFolder.absolutePath)
            }
        }
    }
    private fun createFolder(name: String, colorHex: String?) {
        val finishCreation = { id: String ->
            if (colorHex != null) colorStorage.saveColor(id, colorHex)
            runOnUiThread { loadContent() }
        }

        // DÜZELTME: lifecycleScope kullanıldı
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                val newId = driveServiceHelper?.createFolder(currentDriveId!!, name)
                if (newId != null) finishCreation(newId)
            } else {
                localServiceHelper.createFolder(currentLocalDir!!, name)
                val newFolder = File(currentLocalDir, name)
                finishCreation(newFolder.absolutePath)
            }
        }
    }
    private fun showColorPopup(item: NoteItem, anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_color_picker, null)
        val container = popupView.findViewById<LinearLayout>(R.id.colorContainer)

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f

        if (item.isFolder && item.id != "ROOT") {
            val lockBtn = ImageView(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            lockBtn.layoutParams = params

            val isLocked = securityStorage.isLocked(item.id)
            lockBtn.setImageResource(if (isLocked) R.drawable.ic_lock_open else R.drawable.ic_lock_closed)
            lockBtn.setBackgroundResource(R.drawable.shape_circle)
            lockBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.LTGRAY)
            lockBtn.setPadding(8,8,8,8)

            lockBtn.setOnClickListener {
                popupWindow.dismiss()
                showLockSetupDialog(item)
            }
            container.addView(lockBtn)
        }

        val colors = listOf("#BDBDBD", "#616161", "#EF5350", "#FFA726", "#FFEE58", "#66BB6A", "#42A5F5", "#AB47BC", "#EC407A")

        for (colorHex in colors) {
            val dot = View(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            dot.layoutParams = params

            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate()
            bg?.setTint(Color.parseColor(colorHex))
            dot.background = bg

            dot.setOnClickListener {
                colorStorage.saveColor(item.id, colorHex)
                loadContent()
                popupWindow.dismiss()
            }
            container.addView(dot)
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = popupView.measuredHeight
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val x = location[0] + anchorView.width / 2
        val y = location[1] - (popupHeight / 2) + (anchorView.height / 2)
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y)
    }

    private fun showLockSetupDialog(item: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        tvTitle.text = "Protect Folder"
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = "Set a PIN for '${item.name}'.\nLeave empty to remove protection."
        btnConfirm.text = "Save"
        etPin.hint = "PIN"

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val pin = etPin.text.toString()

            if (pin.isNotEmpty()) {
                if (pin.length >= 4) {
                    // Lock Logic
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isDriveMode) {
                            val newPath = localServiceHelper.moveFolderToPrivate(item.name)
                            if (newPath != null) {
                                securityStorage.setPassword(newPath, pin)
                                securityStorage.setPassword(item.id, "")
                            } else {
                                securityStorage.setPassword(item.id, pin)
                            }
                        } else {
                            securityStorage.setPassword(item.id, pin)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Folder Locked", Toast.LENGTH_SHORT).show()
                            loadContent()
                            dialog.dismiss()
                        }
                    }
                } else {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Unlock Logic
                lifecycleScope.launch(Dispatchers.IO) {
                    if (!isDriveMode) {
                        val newPath = localServiceHelper.moveFolderToPublic(item.name)
                    }
                    securityStorage.setPassword(item.id, "")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Folder Unlocked", Toast.LENGTH_SHORT).show()
                        loadContent()
                        dialog.dismiss()
                    }
                }
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            etPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etPin, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun showUnlockDialog(item: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        tvTitle.text = "Locked Folder"
        tvMessage.visibility = View.GONE
        btnConfirm.text = "Unlock"
        etPin.hint = "PIN"

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val pin = etPin.text.toString()
            if (securityStorage.checkPassword(item.id, pin)) {
                if (isDriveMode) {
                    driveBreadcrumbs.add(item.name)
                    currentDriveId = item.id
                    loadContent()
                } else {
                    currentLocalDir = File(item.id)
                    loadContent()
                }
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Wrong Password", Toast.LENGTH_SHORT).show()
                etPin.setText("")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            etPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etPin, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    // --- STANDARD UI LOGIC ---
    private fun openEditor(item: NoteItem?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (item != null) {
            intent.putExtra("NOTE_TITLE", item.name)
            intent.putExtra("NOTE_ID", item.id)

            // File reading should be in background
            lifecycleScope.launch(Dispatchers.IO) {
                val content = if (!isDriveMode) {
                    localServiceHelper.readFile(item.id)
                } else {
                    driveServiceHelper?.readFile(item.id) ?: ""
                }
                withContext(Dispatchers.Main) {
                    intent.putExtra("NOTE_CONTENT", content)
                    editorLauncher.launch(intent)
                }
            }
        } else {
            editorLauncher.launch(intent)
        }
    }

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val originalId = data.getStringExtra("NOTE_ID")
            val shouldDelete = data.getBooleanExtra("REQUEST_DELETE", false)

            if (shouldDelete && originalId != null) {
                deleteSingleFile(originalId)
            } else {
                val title = data.getStringExtra("NOTE_TITLE") ?: "Untitled"
                val content = data.getStringExtra("NOTE_CONTENT") ?: ""
                val colorHex = data.getStringExtra("NOTE_COLOR")
                saveNote(title, content, originalId, colorHex)
            }
        }
    }

    private fun deleteSingleFile(id: String) {
        // CoroutineScope yerine lifecycleScope kullanıyoruz
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                driveServiceHelper?.deleteFile(id)
            } else {
                localServiceHelper.deleteFile(id)
            }

            // UI güncellemesi için Ana Thread'e dön
            withContext(Dispatchers.Main) {
                loadContent()
                Toast.makeText(this@MainActivity, "Discarded.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun saveNote(title: String, content: String, originalId: String?, colorHex: String?) {
        // CoroutineScope yerine lifecycleScope kullanıyoruz
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                if (originalId != null) {
                    // Güncelleme
                    driveServiceHelper?.updateFile(originalId, title, content)
                    if (colorHex != null) colorStorage.saveColor(originalId, colorHex)
                } else {
                    // Yeni Oluşturma
                    val newId = driveServiceHelper?.createNote(currentDriveId!!, title, content)
                    if (newId != null && colorHex != null) colorStorage.saveColor(newId, colorHex)
                }
            } else {
                if (originalId != null) {
                    // Güncelleme
                    localServiceHelper.updateNote(originalId, title, content)
                    if (colorHex != null) colorStorage.saveColor(originalId, colorHex)
                } else {
                    // Yeni Oluşturma
                    localServiceHelper.saveNote(currentLocalDir!!, title, content)
                    val safeTitle = if (title.endsWith(".md")) title else "$title.md"
                    val newPath = File(currentLocalDir, safeTitle).absolutePath
                    if (colorHex != null) colorStorage.saveColor(newPath, colorHex)
                }
            }

            // İşlem bitince listeyi yenile
            withContext(Dispatchers.Main) {
                loadContent()
            }
        }
    }
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    isSelectionMode = false
                    currentNotes.forEach { it.isSelected = false }
                    currentFolders.forEach { it.isSelected = false }
                    notesAdapter.notifyDataSetChanged()
                    folderAdapter.notifyDataSetChanged()
                    updateFabIcon()
                    return
                }
                if (isDriveMode) {
                    if (driveBreadcrumbs.size > 1) {
                        currentDriveId = rootDriveId
                        driveBreadcrumbs.clear(); driveBreadcrumbs.add("Main")
                        loadContent()
                    } else {
                        finish()
                    }
                } else {
                    if (currentLocalDir != null && currentLocalDir!!.name != "HeyNotes") {
                        currentLocalDir = localServiceHelper.getRootFolder()
                        loadContent()
                    } else {
                        finish()
                    }
                }
            }
        })
    }

    // --- GOOGLE AUTH ---
    private fun requestSignIn() {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveScopes.DRIVE)).build()
        val client = GoogleSignIn.getClient(this, signInOptions)
        signInLauncher.launch(client.signInIntent)
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .addOnSuccessListener { account -> initializeDriveService(account) }
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(this, Collections.singleton(DriveScopes.DRIVE))
        credential.selectedAccount = account.account
        val googleDriveService = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
            .setApplicationName("HeyNotes").build()

        driveServiceHelper = DriveServiceHelper(googleDriveService)

        // DÜZELTME: lifecycleScope kullanıldı
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Arka plan işlerini withContext(IO) içine alıyoruz
                withContext(Dispatchers.IO) {
                    rootDriveId = driveServiceHelper?.getOrCreateRootFolder()
                }
                currentDriveId = rootDriveId
                isDriveMode = true
                driveBreadcrumbs.clear(); driveBreadcrumbs.add("Main")
                loadContent()
                Toast.makeText(this@MainActivity, "Connected: ${account.email}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }

    // --- SETTINGS & THEME MENU ---
    private fun showSettingsMenu(anchorView: View) {
        // 1. Layout'u Yükle
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_settings_menu, null)

        // 2. Pencereyi Oluştur
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 10f

        // 3. Elemanları Bul (TextView olduklarından emin oluyoruz)
        val btnSystem = popupView.findViewById<TextView>(R.id.menuThemeSystem)
        val btnLight = popupView.findViewById<TextView>(R.id.menuThemeLight)
        val btnDark = popupView.findViewById<TextView>(R.id.menuThemeDark)
        val btnAbout = popupView.findViewById<TextView>(R.id.menuAbout)

        // 4. Tıklama Olayları
        btnSystem.setOnClickListener {
            updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            popupWindow.dismiss()
        }

        btnLight.setOnClickListener {
            updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            popupWindow.dismiss()
        }

        btnDark.setOnClickListener {
            updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            popupWindow.dismiss()
        }

        btnAbout.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 5. Menüyü Göster
        popupWindow.showAsDropDown(anchorView, 0, 0)
    }
    private fun updateTheme(mode: Int) {
        // 1. Ayarı Kaydet
        getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", mode).apply()

        // 2. Temayı Uygula (Bu işlem Activity'i yeniden başlatır)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }
}