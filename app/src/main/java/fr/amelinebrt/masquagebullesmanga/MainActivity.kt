package fr.amelinebrt.masquagebullesmanga

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MangaMaskTestScreen()
                }
            }
        }
    }
}

@Composable
private fun MangaMaskTestScreen() {
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Choisis une page de manga pour commencer.") }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            status = "Chargement de l'image…"
            // The image is decoded off the UI thread by the effect below.
            sourceUriState.value = uri
        }
    }

    val uriState = sourceUriState
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uriState.value) {
        val uri = uriState.value ?: return@LaunchedEffect
        sourceBitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (_: Exception) {
                null
            }
        }
        status = if (sourceBitmap != null) {
            "Image chargée. Le moteur de détection sera branché ici ; aucun texte n'est reconnu ni traduit dans cette application."
        } else {
            "Impossible de lire cette image. Essaie un fichier JPG ou PNG."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Masquage Bulles Manga", style = MaterialTheme.typography.headlineSmall)
        Text("Banc d'essai indépendant : détection des bulles et remplissage blanc opaque, sans OCR ni traduction.")
        Button(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
            Text("Choisir une page")
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Image source", style = MaterialTheme.typography.titleMedium)
                if (sourceBitmap == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).background(Color(0xFFF1F1F1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Aucune image sélectionnée")
                    }
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
                Text("En attente d'un modèle de détection validé.")
                Text("Objectif : blanc 100 % opaque, sans laisser transparaître les caractères d'origine.")
            }
        }
        Text("Étape actuelle : interface de test. La détection automatique n'est pas encore activée.", style = MaterialTheme.typography.bodySmall)
    }
}

private val sourceUriState = mutableStateOf<Uri?>(null)
