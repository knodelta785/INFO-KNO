package id.ongarand.flightradarkno

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.channel(this)
        Notif.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App()
            }
        }
    }
}

private val Red = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var intl by remember { mutableStateOf(false) }
    var arr by remember { mutableStateOf(listOf<Flight>()) }
    var dep by remember { mutableStateOf(listOf<Flight>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(Store.rules(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        try {
            val a = withContext(Dispatchers.IO) { Fr24.fetch(true) }
            val d = withContext(Dispatchers.IO) { Fr24.fetch(false) }
            arr = a; dep = d; error = null
            updated = Fmt.time(System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            error = "Gagal memuat data: ${e.message}"
        }
        loading = false
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(60_000) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FlightRadar KNO", fontWeight = FontWeight.Bold)
                        Text("Bandara Kualanamu · developed by Ongarand", fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("🛬") }, label = { Text("Kedatangan") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("🛫") }, label = { Text("Keberangkatan") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Text("🔔") }, label = { Text("Notifikasi") })
            }
        },
        floatingActionButton = {
            if (tab == 2) FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Tambah") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (tab < 2) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!intl, { intl = false }, { Text("Semua (Domestik + Int'l)") })
                    FilterChip(intl, { intl = true }, { Text("Internasional saja") })
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val list = (if (tab == 0) arr else dep).filter { !intl || it.international }.sortedBy { it.sched }
                Text(
                    error ?: "${list.size} flight · diperbarui $updated WIB",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp, color = if (error != null) Red else MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.number + it.sched + it.arrival }) { FlightCard(it) }
                }
            } else {
                Text(
                    "Notifikasi dicek di latar belakang tiap ±15 menit (batas minimum Android).",
                    Modifier.padding(16.dp), fontSize = 12.sp
                )
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (r.isFlight) {
                                        Text("Flight ${r.flight}", fontWeight = FontWeight.Bold)
                                        Text("Notif saat status / delay berubah", fontSize = 12.sp)
                                    } else {
                                        Text("Rentang ${hm(r.start)}–${hm(r.end)} WIB", fontWeight = FontWeight.Bold)
                                        val d = when (r.dir) { "ARR" -> "Kedatangan"; "DEP" -> "Keberangkatan"; else -> "Kedatangan & Keberangkatan" }
                                        Text(d + if (r.intl) " · Internasional saja" else " · Semua", fontSize = 12.sp)
                                    }
                                }
                                IconButton(onClick = {
                                    rules = rules.filter { it.id != r.id }; Store.save(ctx, rules)
                                }) { Icon(Icons.Default.Delete, "Hapus") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddRuleDialog(
        onDismiss = { showAdd = false },
        onAdd = { r -> rules = rules + r; Store.save(ctx, rules); Notif.schedule(ctx); showAdd = false }
    )
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun parseHm(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

@Composable
fun FlightCard(f: Flight) {
    val bg = if (f.alert) Red.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = if (f.alert) BorderStroke(1.5.dp, Red) else null
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${f.number} · ${f.airline}", fontWeight = FontWeight.Bold)
                Text("${if (f.arrival) "Dari" else "Ke"} ${f.city} (${f.iata})", fontSize = 14.sp)
                Text(
                    (if (f.international) "INTERNASIONAL" else "DOMESTIK") + if (f.country.isNotEmpty()) " · ${f.country}" else "",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val st = when {
                    f.cancelled -> "DIBATALKAN"
                    f.delayMin >= 10 -> "DELAY +${f.delayMin} mnt"
                    else -> f.status
                }
                if (st.isNotEmpty()) Text(st, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (f.alert) Red else Color.Unspecified)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.time(f.sched), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textDecoration = if (f.delayMin >= 10) TextDecoration.LineThrough else null
                )
                if (f.delayMin >= 10) Text("Est ${Fmt.time(f.actual)}", color = Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (Rule) -> Unit) {
    var byFlight by remember { mutableStateOf(true) }
    var flight by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("06:00") }
    var end by remember { mutableStateOf("12:00") }
    var dir by remember { mutableStateOf("BOTH") }
    var intl by remember { mutableStateOf(false) }
    val s = parseHm(start); val e = parseHm(end)
    val valid = if (byFlight) flight.isNotBlank() else (s != null && e != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifikasi baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(byFlight, { byFlight = true }, { Text("Flight tertentu") })
                    FilterChip(!byFlight, { byFlight = false }, { Text("Rentang waktu") })
                }
                if (byFlight) {
                    OutlinedTextField(flight, { flight = it.uppercase() }, label = { Text("Nomor flight, mis. GA123") }, singleLine = true)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Mulai (HH:mm)") }, singleLine = true)
                        OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("Selesai (HH:mm)") }, singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(dir == "ARR", { dir = "ARR" }, { Text("Datang") })
                        FilterChip(dir == "DEP", { dir = "DEP" }, { Text("Berangkat") })
                        FilterChip(dir == "BOTH", { dir = "BOTH" }, { Text("Keduanya") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(intl, { intl = it }); Spacer(Modifier.width(8.dp)); Text("Internasional saja")
                    }
                    Text("Waktu dalam WIB (jadwal flight).", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onAdd(Rule(System.currentTimeMillis(), if (byFlight) flight.replace(" ", "") else "", s ?: 0, e ?: 0, dir, intl))
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
