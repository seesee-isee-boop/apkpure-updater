package fr.caperuisseau.apkpureupdater

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class Row(val pkg: String, val label: String, val installed: String?)

    private enum class Mode { ALL, WATCHED, UPDATES }

    private lateinit var store: Store
    private val io = Executors.newSingleThreadExecutor()
    private val iconIo = Executors.newSingleThreadExecutor()

    private var allRows: List<Row> = emptyList()
    private var shownRows: List<Row> = emptyList()
    private val results = mutableMapOf<String, CheckResult>()
    private var checking = false
    private var mode = Mode.ALL
    private var showSystem = false

    // Icônes des applis : chargées en tâche de fond, gardées en mémoire (thread UI uniquement).
    private val icons = HashMap<String, Drawable>()
    private val iconsLoading = HashSet<String>()
    private lateinit var defaultIcon: Drawable

    private lateinit var status: TextView
    private lateinit var filter: EditText
    private lateinit var chips: Map<Mode, TextView>
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var checkBtn: Button
    private val adapter = RowAdapter()

    // ---------------------------------------------------------------- cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        showSystem = store.showSystem
        defaultIcon = packageManager.defaultActivityIcon
        setContentView(buildUi())
        UpdateWorker.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Diffusé par le DownloadManager (process système) -> EXPORTED obligatoire depuis Android 14.
        registerReceiver(dlReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        // Aussi au retour de l'installateur : les versions installées ont pu changer.
        // On garde le résumé de la dernière vérification affiché tant que rien n'a bougé.
        loadApps(keepStatus = allRows.isNotEmpty())
    }

    override fun onDestroy() {
        unregisterReceiver(dlReceiver)
        io.shutdownNow()
        iconIo.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- petits outils d'UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    /** Fond arrondi avec effet d'onde au toucher. */
    private fun pressable(color: Int, radiusDp: Int): Drawable {
        val shape = rounded(color, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), shape, shape)
    }

    private fun themeDrawable(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    private fun text(sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(getColor(colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Bouton en forme de pilule, sans les majuscules ni l'ombre du style système. */
    private fun pillButton(label: String, bgRes: Int, fgRes: Int, radiusDp: Int, sizeSp: Float) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = sizeSp
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(fgRes))
        background = pressable(getColor(bgRes), radiusDp)
        stateListAnimator = null
    }

    private fun headerAction(glyph: String, sizeSp: Float, description: String, onClick: (View) -> Unit) =
        text(sizeSp, R.color.on_surface).apply {
            text = glyph
            gravity = Gravity.CENTER
            contentDescription = description
            setBackgroundResource(themeDrawable(android.R.attr.selectableItemBackgroundBorderless))
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun margins(lp: ViewGroup.MarginLayoutParams, l: Int, t: Int, r: Int, b: Int) =
        lp.apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    // ---------------------------------------------------------------- UI (en code, pas de XML)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // targetSdk 35+ = edge-to-edge forcé : on garde le contenu hors des barres système et du clavier.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        // En-tête : titre + état, actions à droite
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(8), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(24f, R.color.on_surface, bold = true).apply { text = "APKPure Updater" })
        status = text(13f, R.color.on_surface_variant).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerAction("+", 28f, "Ajouter un package") { askPackage() })
        header.addView(headerAction("⋮", 22f, "Options") { showMenu(it) })
        root.addView(header)

        if (!store.warningSeen) root.addView(buildWarning())

        // Recherche
        filter = EditText(this).apply {
            hint = "Rechercher une appli ou un package"
            textSize = 15f
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
            background = rounded(getColor(R.color.surface), 26, getColor(R.color.outline))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshList()
            })
        }
        root.addView(
            filter,
            margins(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 16, 4, 16, 0),
        )

        // Filtres
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        chips = Mode.values().associateWith { m ->
            text(13f, R.color.on_surface).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { setMode(m) }
                chipRow.addView(
                    this,
                    margins(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 8, 0),
                )
            }
        }
        root.addView(chipRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
            visibility = View.GONE
        }
        root.addView(
            progress,
            margins(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 16, 0, 16, 0),
        )

        // Liste + message quand elle est vide
        val listArea = FrameLayout(this)
        empty = text(14f, R.color.on_surface_variant).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(24), dp(40), dp(24))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(0)
            clipToPadding = false
            setPadding(dp(12), dp(2), dp(12), dp(8))
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        listArea.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listArea.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list.emptyView = empty
        root.addView(listArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Action principale
        checkBtn = pillButton("Vérifier les mises à jour", R.color.primary, R.color.on_primary, 26, 15f).apply {
            setOnClickListener { checkAll() }
        }
        root.addView(
            checkBtn,
            margins(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)), 16, 6, 16, 12),
        )

        updateChips()
        return root
    }

    private fun buildWarning(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(getColor(R.color.warn_container), 16)
            setPadding(dp(16), dp(12), dp(8), dp(4))
            layoutParams = margins(
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 16, 0, 16, 10,
            )
        }
        box.addView(text(13f, R.color.on_warn_container).apply {
            text = WARNING
            setPadding(0, 0, dp(8), 0)
        })
        box.addView(
            text(14f, R.color.on_warn_container, bold = true).apply {
                text = "Compris"
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(themeDrawable(android.R.attr.selectableItemBackground))
                setOnClickListener {
                    store.warningSeen = true
                    box.visibility = View.GONE
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            },
        )
        return box
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Vérif auto toutes les 12 h").setCheckable(true).setChecked(store.autoCheck)
            menu.add(0, 2, 1, "Afficher les applis système").setCheckable(true).setChecked(showSystem)
            menu.add(0, 3, 2, "À propos")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val on = !store.autoCheck
                        store.autoCheck = on
                        UpdateWorker.schedule(this@MainActivity, on)
                        toast(if (on) "Vérif périodique activée" else "Vérif périodique coupée")
                    }
                    2 -> {
                        showSystem = !showSystem
                        store.showSystem = showSystem
                        loadApps()
                    }
                    3 -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("APKPure Updater")
                        .setMessage(
                            "$WARNING\n\nLes fichiers sont enregistrés dans Téléchargements/APKPureUpdater/. " +
                                "Les XAPK s'installent avec SAI (Split APKs Installer)."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                true
            }
            show()
        }
    }

    /** Ajout manuel d'un package (appli pas encore installée, par exemple). */
    private fun askPackage() {
        val input = EditText(this).apply {
            hint = "com.exemple.appli"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Surveiller un package")
            .setView(holder)
            .setPositiveButton("Ajouter") { _, _ ->
                val pkg = input.text.toString().trim()
                if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                    toast("Nom de package invalide")
                } else {
                    store.setWatched(pkg, true)
                    setMode(Mode.WATCHED)
                    loadApps()
                    toast("$pkg surveillé")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun setMode(m: Mode) {
        mode = m
        refreshList()
    }

    private fun updateChips() {
        val watched = store.watched.size
        val updates = results.values.count { it.status == Status.UPDATE }
        for ((m, chip) in chips) {
            chip.text = when (m) {
                Mode.ALL -> "Toutes"
                Mode.WATCHED -> if (watched > 0) "Surveillées · $watched" else "Surveillées"
                Mode.UPDATES -> if (updates > 0) "Mises à jour · $updates" else "Mises à jour"
            }
            val selected = m == mode
            chip.background =
                if (selected) pressable(getColor(R.color.primary_container), 18)
                else RippleDrawable(
                    ColorStateList.valueOf(getColor(R.color.ripple)),
                    rounded(0, 18, getColor(R.color.outline)),
                    rounded(-1, 18),
                )
            chip.setTextColor(getColor(if (selected) R.color.on_primary_container else R.color.on_surface_variant))
            chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /**
     * Tâche de fond. onDestroy() interrompt le thread (shutdownNow) : sans ce try/catch,
     * l'InterruptedException d'un Thread.sleep en cours ferait planter l'appli.
     */
    private fun background(task: () -> Unit) {
        if (io.isShutdown) return
        io.execute {
            try {
                task()
            } catch (_: InterruptedException) {
                // activité fermée pendant le travail : on abandonne en silence
            }
        }
    }

    private fun updateCount() {
        status.text = "${allRows.size} applis · ${store.watched.size} surveillée(s)"
    }

    // ---------------------------------------------------------------- données

    private fun loadApps(keepStatus: Boolean = false) {
        if (allRows.isEmpty()) status.text = "Chargement des applis…"
        val withSystem = showSystem
        background {
            val pm = packageManager
            val installed = pm.getInstalledPackages(0)
                .filter { p ->
                    val ai = p.applicationInfo ?: return@filter false
                    withSystem || (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        p.packageName in store.watched
                }
                .map { p -> Row(p.packageName, p.applicationInfo!!.loadLabel(pm).toString(), p.versionName ?: "?") }
            val known = installed.map { it.pkg }.toSet()
            val manualOnly = store.watched.filter { it !in known && installedVersion(this, it) == null }
                .map { Row(it, it, null) }
            val rows = installed + manualOnly
            runOnUiThread {
                // Un résultat calculé pour une autre version installée n'est plus valable (MAJ faite entre-temps).
                val now = rows.associate { it.pkg to it.installed }
                val stale = results.values.any { now[it.pkg] != it.installed }
                results.values.removeAll { now[it.pkg] != it.installed }
                allRows = rows
                if (!checking && (!keepStatus || stale)) updateCount()
                refreshList()
            }
        }
    }

    private fun refreshList() {
        val q = filter.text.toString().trim().lowercase()
        val watched = store.watched
        shownRows = allRows
            .filter {
                when (mode) {
                    Mode.ALL -> true
                    Mode.WATCHED -> it.pkg in watched
                    Mode.UPDATES -> results[it.pkg]?.status == Status.UPDATE
                }
            }
            .filter { q.isEmpty() || it.pkg.lowercase().contains(q) || it.label.lowercase().contains(q) }
            .sortedWith(
                compareBy<Row>(
                    { if (results[it.pkg]?.status == Status.UPDATE) 0 else 1 },
                    { if (it.pkg in watched) 0 else 1 },
                    { it.label.lowercase() },
                )
            )
        empty.text = when {
            allRows.isEmpty() -> "Chargement…"
            q.isNotEmpty() -> "Aucun résultat pour « $q »."
            mode == Mode.WATCHED -> "Aucune appli surveillée.\nCoche des applis dans l'onglet « Toutes », ou ajoute un package avec +."
            mode == Mode.UPDATES ->
                if (results.isEmpty()) "Lance une vérification pour voir les mises à jour disponibles."
                else "Aucune mise à jour disponible."
            else -> "Aucune appli à afficher."
        }
        updateChips()
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- vérification

    private fun setChecking(on: Boolean, total: Int = 0) {
        checking = on
        checkBtn.isEnabled = !on
        checkBtn.alpha = if (on) 0.5f else 1f
        checkBtn.text = if (on) "Vérification en cours…" else "Vérifier les mises à jour"
        progress.max = total
        progress.progress = 0
        progress.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun checkAll() {
        if (checking) return
        val pkgs = store.watched.sorted()
        if (pkgs.isEmpty()) {
            toast("Coche d'abord des applis à surveiller")
            return
        }
        setChecking(true, pkgs.size)
        if (mode == Mode.UPDATES) mode = Mode.WATCHED
        refreshList()
        background {
            pkgs.forEachIndexed { i, pkg ->
                runOnUiThread { status.text = "Vérification ${i + 1}/${pkgs.size} · $pkg" }
                val r = checkPackage(this, pkg)
                runOnUiThread {
                    results[pkg] = r
                    progress.progress = i + 1
                    refreshList()
                }
                if (i < pkgs.size - 1) Thread.sleep(1000) // pas de mitraillage de l'API
            }
            runOnUiThread {
                setChecking(false)
                val ups = results.values.count { it.pkg in pkgs && it.status == Status.UPDATE }
                val errs = results.values.count { it.pkg in pkgs && it.status == Status.ERROR }
                val errText = if (errs > 0) " · $errs erreur(s)" else ""
                status.text = when (ups) {
                    0 -> "Tout est à jour$errText"
                    1 -> "1 mise à jour disponible$errText"
                    else -> "$ups mises à jour disponibles$errText"
                }
                // S'il y a des erreurs, on reste sur « Surveillées » pour qu'elles soient visibles.
                mode = if (ups > 0 && errs == 0) Mode.UPDATES else Mode.WATCHED
                refreshList()
            }
        }
    }

    // ---------------------------------------------------------------- téléchargement / install

    private fun download(r: CheckResult) {
        val latest = r.latest ?: return
        val ext = if (latest.isXapk) "xapk" else "apk"
        val ver = (latest.versionName ?: "latest").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = "${r.pkg}@$ver@${deviceAbis.first()}.$ext"
        val req = DownloadManager.Request(Uri.parse(latest.downloadUrl))
            .setTitle(name)
            .setDescription("Téléchargement depuis APKPure")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "APKPureUpdater/$name")
        if (!latest.isXapk) req.setMimeType("application/vnd.android.package-archive")
        val dm = getSystemService(DownloadManager::class.java)
        try {
            val id = dm.enqueue(req)
            store.rememberDownload(id, r.pkg, latest.isXapk)
            toast("Téléchargement lancé → Téléchargements/APKPureUpdater/")
        } catch (e: RuntimeException) {
            // URL refusée par le DownloadManager, stockage indisponible…
            toast("Téléchargement impossible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val (pkg, isXapk) = store.downloadInfo(id) ?: return
            store.forgetDownload(id)
            val dm = getSystemService(DownloadManager::class.java)
            val ok = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL
            } ?: false
            if (!ok) { toast("Échec du téléchargement de $pkg"); return }
            val uri = dm.getUriForDownloadedFile(id) ?: return
            if (isFinishing) return
            val name = allRows.firstOrNull { it.pkg == pkg }?.label ?: pkg
            AlertDialog.Builder(this@MainActivity)
                .setTitle("$name téléchargé")
                .setMessage(
                    if (isXapk) "C'est un XAPK : il faut l'ouvrir avec SAI (Split APKs Installer)."
                    else "Lancer l'installation maintenant ?"
                )
                .setPositiveButton(if (isXapk) "Ouvrir…" else "Installer") { _, _ -> install(uri, isXapk) }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }

    private fun install(uri: Uri, isXapk: Boolean) {
        if (!isXapk && !packageManager.canRequestPackageInstalls()) {
            toast("Autorise cette appli à installer des applis, puis relance")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (isXapk) "application/octet-stream" else "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(if (isXapk) Intent.createChooser(view, "Ouvrir avec SAI") else view)
        } catch (_: ActivityNotFoundException) {
            toast("Aucune appli pour l'ouvrir. Ouvre-le depuis SAI → Téléchargements/APKPureUpdater/")
        }
    }

    // ---------------------------------------------------------------- liste

    /** Une carte de la liste. Créée une fois puis réutilisée par le ListView (bind). */
    private inner class RowHolder {
        private var pkg = ""
        private val icon = ImageView(this@MainActivity)
        private val label = text(15f, R.color.on_surface, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pkgText = text(12f, R.color.on_surface_variant).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        private val version = text(12f, R.color.on_surface_variant).apply { maxLines = 1 }
        private val check = CheckBox(this@MainActivity)
        private val badge = text(12f, R.color.on_surface).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        private val dl = pillButton("Télécharger", R.color.primary, R.color.on_primary, 18, 13f).apply {
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        private val footer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(52), dp(8), 0, 0)
        }
        val view: View

        init {
            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(icon, margins(LinearLayout.LayoutParams(dp(40), dp(40)), 0, 0, 12, 0))
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label)
            texts.addView(pkgText)
            texts.addView(version)
            top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(check)

            val badgeArea = FrameLayout(this@MainActivity)
            badgeArea.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            footer.addView(badgeArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            footer.addView(dl, margins(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 0, 0))

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pressable(getColor(R.color.surface), 16)
                setPadding(dp(14), dp(12), dp(6), dp(12))
                setOnClickListener { check.toggle() } // toute la carte coche/décoche
            }
            card.addView(top)
            card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })

            // Le ListView ignore les marges de ses lignes : l'espace entre cartes passe par un conteneur.
            view = FrameLayout(this@MainActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
                tag = this@RowHolder
            }
        }

        fun bind(row: Row) {
            pkg = row.pkg
            label.text = row.label
            pkgText.text = row.pkg
            pkgText.visibility = if (row.label == row.pkg) View.GONE else View.VISIBLE
            version.text = if (row.installed == null) "Non installée" else "Version ${row.installed}"

            check.setOnCheckedChangeListener(null) // sinon le recyclage déclencherait l'écouteur de l'ancienne ligne
            check.isChecked = row.pkg in store.watched
            check.contentDescription = "Surveiller ${row.label}"
            check.setOnCheckedChangeListener { _, on ->
                store.setWatched(row.pkg, on)
                updateChips()
                if (!checking) updateCount()
            }

            bindResult(row, results[row.pkg])
            bindIcon(row)
        }

        private fun bindResult(row: Row, r: CheckResult?) {
            footer.visibility = if (r == null) View.GONE else View.VISIBLE
            if (r == null) return
            val latest = r.latest
            val kind = if (latest?.isXapk == true) "XAPK" else "APK"
            val (msg, bg, fg) = when (r.status) {
                Status.UPDATE -> Triple(
                    (if (row.installed == null) "Disponible : " else "Nouvelle version : ") +
                        "${latest?.versionName ?: "?"} · $kind",
                    R.color.primary_container, R.color.on_primary_container,
                )
                Status.UP_TO_DATE -> Triple("✓ À jour", R.color.neutral_container, R.color.on_surface_variant)
                Status.DIFFERENT -> Triple(
                    "APKPure : ${latest?.versionName ?: "version inconnue"} · à comparer",
                    R.color.warn_container, R.color.on_warn_container,
                )
                Status.ERROR -> Triple(r.error ?: "Erreur", R.color.error_container, R.color.error)
            }
            badge.text = msg
            badge.background = rounded(getColor(bg), 12)
            badge.setTextColor(getColor(fg))

            val canDownload = latest != null && (r.status == Status.UPDATE || r.status == Status.DIFFERENT)
            dl.visibility = if (canDownload) View.VISIBLE else View.GONE
            dl.setOnClickListener { download(r) }
        }

        private fun bindIcon(row: Row) {
            val cached = icons[row.pkg]
            icon.setImageDrawable(cached ?: defaultIcon)
            if (cached != null || row.installed == null || iconIo.isShutdown || !iconsLoading.add(row.pkg)) return
            val wanted = row.pkg
            iconIo.execute {
                val d = try {
                    packageManager.getApplicationIcon(wanted)
                } catch (_: Exception) {
                    defaultIcon
                }
                runOnUiThread {
                    iconsLoading.remove(wanted)
                    icons[wanted] = d
                    if (pkg == wanted) icon.setImageDrawable(d) // la carte a pu être recyclée entre-temps
                }
            }
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shownRows.size
        override fun getItem(position: Int) = shownRows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = convertView?.tag as? RowHolder ?: RowHolder()
            holder.bind(shownRows[position])
            return holder.view
        }
    }

    private companion object {
        const val WARNING = "APKPure n'est pas une source officielle : les fichiers peuvent être modifiés, " +
            "en retard ou différents de ceux du Play Store. Vérifie ce que tu installes."
    }
}
