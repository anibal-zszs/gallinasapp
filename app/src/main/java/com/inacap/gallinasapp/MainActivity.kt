package com.inacap.gallinasapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inacap.gallinasapp.ui.theme.GallinasAppTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertService.createChannel(this)
        setContent {
            GallinasAppTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    // La sesión se conserva sola: Firebase la guarda en el teléfono.
    var loggedIn by remember { mutableStateOf(AuthRepository.isLoggedIn()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            runCatching { CoopRepository.saveFcmToken() }
        }
    }

    if (loggedIn) HomeScreen(onLogout = { AuthRepository.logout(); loggedIn = false })
    else AuthScreen(onLoggedIn = { loggedIn = true })
}

// ---------------------------------------------------------------- LOGIN / REGISTRO

@Composable
fun AuthScreen(onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🐔 Gallinero", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            email, { email = it }, label = { Text("Correo") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Contraseña (mín. 8 caracteres)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        if (isRegister) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass2, { pass2 = it }, label = { Text("Repetir contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !loading && email.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    loading = true
                    message = null
                    if (isRegister) {
                        when {
                            pass.length < AuthRepository.MIN_PASSWORD ->
                                message = "La contraseña debe tener al menos 8 caracteres"
                            pass != pass2 -> message = "Las contraseñas no coinciden"
                            else -> AuthRepository.register(email, pass)
                                .onSuccess {
                                    message = "Cuenta creada. Revisa tu correo (y spam) y activa tu cuenta; luego inicia sesión."
                                    isRegister = false
                                }
                                .onFailure { message = it.message }
                        }
                    } else {
                        AuthRepository.login(email, pass)
                            .onSuccess { onLoggedIn() }
                            .onFailure { message = it.message }
                    }
                    loading = false
                }
            }
        ) { Text(if (isRegister) "Registrarme" else "Iniciar sesión") }

        TextButton(onClick = { isRegister = !isRegister; message = null }) {
            Text(if (isRegister) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Regístrate")
        }
        if (!isRegister) {
            TextButton(onClick = {
                scope.launch {
                    AuthRepository.resendVerification(email, pass)
                        .onSuccess { message = "Correo de activación reenviado" }
                        .onFailure { message = it.message }
                }
            }) { Text("Reenviar correo de activación") }
            TextButton(onClick = {
                scope.launch {
                    if (email.isBlank()) message = "Escribe tu correo primero"
                    else AuthRepository.resetPassword(email)
                        .onSuccess { message = "Te enviamos un correo para restablecer la contraseña" }
                        .onFailure { message = it.message }
                }
            }) { Text("Olvidé mi contraseña") }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------------------------------------------------------- PANTALLA PRINCIPAL

@Composable
fun HomeScreen(onLogout: () -> Unit) {
    var coopId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coopId = runCatching { CoopRepository.myCoopId() }.getOrNull()
        loaded = true
    }

    when {
        !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        coopId == null -> CoopSetupScreen(onReady = { coopId = it }, onLogout = onLogout)
        else -> CoopScreen(coopId!!, onLogout)
    }
}

@Composable
fun CoopSetupScreen(onReady: (String) -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Tu gallinero", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Nombre del nuevo gallinero") }, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            onClick = { scope.launch { onReady(CoopRepository.createCoop(name)) } }
        ) { Text("Crear gallinero") }

        Spacer(Modifier.height(24.dp))
        Text("…o únete a uno con un código")
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Código") }, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = code.length >= 6, modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    CoopRepository.joinCoop(code).onSuccess(onReady).onFailure { error = it.message }
                }
            }
        ) { Text("Unirme") }
        error?.let { Text(it, color = Color.Red) }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onLogout) { Text("Cerrar sesión") }
    }
}

@Composable
fun CoopScreen(coopId: String, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val coop by CoopRepository.observeCoop(coopId).collectAsState(initial = null)
    val history by CoopRepository.observeHistory(coopId).collectAsState(initial = emptyList())
    var hensInput by remember { mutableStateOf("") }
    val c = coop

    LaunchedEffect(c?.expectedHens) {
        if (hensInput.isEmpty() && c != null) hensInput = c.expectedHens.toString()
    }

    if (c == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(c.name, style = MaterialTheme.typography.headlineSmall)
            Text("Código para compartir: ${c.code}", style = MaterialTheme.typography.bodyMedium)
        }

        // Estado de la puerta en tiempo real
        item {
            val (label, color) = when (c.doorState) {
                "open" -> "Puerta ABIERTA" to Color(0xFF2E7D32)
                "closed" -> "Puerta CERRADA" to Color(0xFFC62828)
                else -> "Puerta en movimiento…" to Color(0xFFF9A825)
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
                Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { scope.launch { CoopRepository.sendDoorCommand(coopId, "open") } }) { Text("Abrir") }
                Button(modifier = Modifier.weight(1f), onClick = { scope.launch { CoopRepository.sendDoorCommand(coopId, "close") } }) { Text("Cerrar") }
            }
        }

        // Cantidad de gallinas
        item {
            Text("Cantidad de gallinas", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    hensInput, { hensInput = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = hensInput.isNotEmpty(),
                    onClick = { scope.launch { CoopRepository.setExpectedHens(coopId, hensInput.toInt()) } }
                ) { Text("Guardar") }
            }
            Text("Esperadas: ${c.expectedHens}  ·  Última cuenta: ${c.scannedHens}")
            Spacer(Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !c.scanRequest,
                onClick = { scope.launch { CoopRepository.requestScan(coopId) } }
            ) { Text(if (c.scanRequest) "Escaneando…" else "Escanear gallinas") }
        }

        // Historial
        item { Text("Historial de alertas", style = MaterialTheme.typography.titleMedium) }
        if (history.isEmpty()) item { Text("Sin alertas todavía") }
        items(history, key = { it.id }) { h ->
            val date = h.createdAt?.toDate()?.let {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it)
            } ?: ""
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(h.message, style = MaterialTheme.typography.bodyLarge)
                    Text(date, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { TextButton(onClick = onLogout) { Text("Cerrar sesión") } }
    }
}
