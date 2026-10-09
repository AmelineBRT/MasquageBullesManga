package fr.amelinebrt.masquagebullesmanga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { MangaMaskTestScreen() }
            }
        }
    }
}

@Composable
private fun MangaMaskTestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Choisis une page de manga pour commencer.") }
    var isBusy by remember { mutableStateOf(false) }
    var bubbleCount by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    val liveCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(context, LiveMaskService::class.java).apply {
                putExtra(LiveMaskService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(LiveMaskService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
            status = "Masquage en direct lancé. Retourne dans ton application de manga et fais défiler ; le masque se met à jour après une courte pause."
        } else {
            status = "Capture annulée. Aucun écran n'a été capturé."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            resultBitmap = null
            status = "Chargement de l'image…"
            scope.launch {
                sourceBitmap = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (_: Exception) { null }
                }
                status = if (sourceBitmap != null) "Image chargée. Appuie sur « Détecter et masquer »."
                else "Impossible de lire cette image. Essaie un fichier JPG ou PNG."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Masquage Bulles Manga", style = MaterialTheme.typography.headlineSmall)
        Text("Banc d'essai indépendant : segmentation locale des bulles et remplissage blanc opaque. Aucun OCR, aucune traduction.")
        Button(
            onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    status = "Autorise « Afficher par-dessus les autres applications », puis reviens ici et relance le mode lecture."
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } else {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    liveCaptureLauncher.launch(manager.createScreenCaptureIntent())
                }
            },
            enabled = !isBusy
        ) {
            Text("Tester en lecture réelle")
        }
        Text(
            "Mode expérimental : autorise la capture d'écran Android, puis retourne dans ton lecteur de manga. Le masquage attend une courte pause du défilement ; le traitement peut prendre un peu de temps.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !isBusy) {
            Text("Choisir une page (test fixe)")
        }
        if (sourceBitmap != null) {
            Button(
                onClick = {
                    val input = sourceBitmap ?: return@Button
                    isBusy = true
                    status = "Préparation du modèle… au premier lancement, téléchargement d'environ 12 Mo."
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.Default) { BubbleMaskDetector.run(context, input) }
                            resultBitmap = result.bitmap
                            bubbleCount = result.bubbleCount
                            elapsedMs = result.elapsedMs
                            status = "Terminé : ${result.bubbleCount} régions masquées en ${result.elapsedMs} ms."
                        } catch (e: Exception) {
                            status = "Échec : ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            isBusy = false
                        }
                    }
                },
                enabled = !isBusy
            ) { Text("Détecter et masquer") }
        }
        if (isBusy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Détection en cours… le modèle est conservé sur le téléphone après le premier téléchargement.")
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Image source", style = MaterialTheme.typography.titleMedium)
                if (sourceBitmap == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).background(Color(0xFFF1F1F1)),
                        contentAlignment = Alignment.Center
                    ) { Text("Aucune image sélectionnée") }
                } else {
                    Image(
                        bitmap = sourceBitmap!!.asImageBitmap(),
                        contentDescription = "Page de manga sélectionnée",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Résultat du masquage", style = MaterialTheme.typography.titleMedium)
                if (resultBitmap == null) Text("Le résultat apparaîtra ici après la détection.")
                else {
                    Text("$bubbleCount régions détectées · $elapsedMs ms")
                    Image(
                        bitmap = resultBitmap!!.asImageBitmap(),
                        contentDescription = "Résultat avec masquage blanc opaque",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text("Vérifie surtout les bulles oubliées et les zones de dessin masquées par erreur.")
                }
            }
        }
        Text("Le modèle vise les bulles de dialogue. Les bulles sombres, les trames complexes et les formes inhabituelles peuvent encore être manquées ; le résultat doit être vérifié sur de vraies pages.", style = MaterialTheme.typography.bodySmall)
    }
}
