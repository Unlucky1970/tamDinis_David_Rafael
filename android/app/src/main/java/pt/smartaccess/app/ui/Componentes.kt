package pt.smartaccess.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cores de cada perfil (fundo, texto), usadas nos avatares e nas etiquetas
@Composable
fun coresPerfil(perfil: String?): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (perfil) {
        "admin" -> c.primaryContainer to c.onPrimaryContainer
        "funcionario" -> c.tertiaryContainer to c.onTertiaryContainer
        "docente" -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.surfaceVariant to c.onSurfaceVariant
    }
}

// "Ana Ferreira" -> "AF"
fun iniciais(nome: String?): String =
    nome.orEmpty().split(' ').filter { it.isNotBlank() }.let { partes ->
        when {
            partes.isEmpty() -> "?"
            partes.size == 1 -> partes[0].take(2)
            else -> "${partes.first().first()}${partes.last().first()}"
        }
    }.uppercase()

@Composable
fun Avatar(nome: String?, perfil: String?, tamanho: Dp = 44.dp) {
    val (fundo, texto) = coresPerfil(perfil)
    Box(
        modifier = Modifier.size(tamanho).background(fundo, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            iniciais(nome),
            color = texto,
            fontWeight = FontWeight.Bold,
            fontSize = (tamanho.value * 0.36f).sp,
        )
    }
}

// Etiqueta pequena e arredondada (estado, perfil...)
@Composable
fun Etiqueta(texto: String, fundo: Color, cor: Color) {
    Surface(color = fundo, contentColor = cor, shape = CircleShape) {
        Text(
            texto,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Versao curta do perfil para caber ao lado do nome ("Admin" em vez de "Docente administrador")
@Composable
fun EtiquetaPerfil(perfil: String?) {
    val (fundo, cor) = coresPerfil(perfil)
    Etiqueta(if (perfil == "admin") "Admin" else textoPerfil(perfil), fundo, cor)
}

// Ponto colorido + texto (online/offline, aberta/trancada)
@Composable
fun Indicador(texto: String, cor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(cor, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(texto, color = cor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

val Verde = Color(0xFF16A34A)

// Cartao de lista com o estilo da app (fundo branco/elevado, cantos grandes)
@Composable
fun CartaoLista(
    modifier: Modifier = Modifier,
    destaque: Color? = null,
    conteudo: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerLowest
    Card(
        modifier = modifier.fillMaxWidth(),
        // Cor solida: um fundo semitransparente deixava ver a sombra do cartao por baixo
        colors = CardDefaults.cardColors(containerColor = destaque?.compositeOver(base) ?: base),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) { conteudo() }
    }
}

@Composable
fun EstadoVazio(icone: ImageVector, titulo: String, texto: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(titulo, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        texto?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun CampoPesquisa(valor: String, aoMudar: (String) -> Unit, dica: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        placeholder = { Text(dica) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (valor.isNotEmpty()) {
                IconButton(onClick = { aoMudar("") }) { Icon(Icons.Default.Clear, contentDescription = "Limpar") }
            }
        },
        singleLine = true,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth(),
    )
}

// Pesquisa sem acentos nem maiusculas: "joao" encontra "Joao" e "João"
fun contem(texto: String?, pesquisa: String): Boolean {
    fun normalizar(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().trim()
    return normalizar(texto.orEmpty()).contains(normalizar(pesquisa))
}
