package pt.smartaccess.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import pt.smartaccess.app.data.AlteracaoCartao
import pt.smartaccess.app.data.NovoCartao
import pt.smartaccess.app.data.NovoUtilizador
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.Utilizador

// A quem fica o cartao: uma pessoa que ja existe ou uma nova (criada so com o nome)
sealed interface EscolhaPessoa {
    val nome: String

    data class Existente(val pessoa: Utilizador) : EscolhaPessoa {
        override val nome get() = pessoa.nome
    }

    data class Nova(override val nome: String, val perfil: String) : EscolhaPessoa
}

// Cria a pessoa (se for nova) e associa-lhe o cartao: aprova o pendente ou adiciona-o pelo UID
suspend fun associarCartao(api: SmartAccessApi, uid: String, escolha: EscolhaPessoa, cartaoPendenteId: Long?) {
    val pessoa = when (escolha) {
        is EscolhaPessoa.Existente -> escolha.pessoa
        is EscolhaPessoa.Nova -> api.criarUtilizador(NovoUtilizador(escolha.nome, escolha.perfil))
    }
    if (cartaoPendenteId != null) {
        api.alterarCartao(cartaoPendenteId, AlteracaoCartao(estado = "ativo", utilizadorId = pessoa.id))
    } else {
        api.criarCartao(NovoCartao(uid, pessoa.id))
    }
}

private val FORMATO_UID = Regex("^[0-9A-F]{8,20}$")
private const val MAX_RESULTADOS = 6

// uidFixo: aprovar um cartao lido numa porta (o UID ja e conhecido); null = escrever o UID
@Composable
fun DialogoAssociarCartao(
    titulo: String,
    uidFixo: String?,
    pessoas: List<Utilizador>,
    pessoaInicial: Utilizador?,
    aoFechar: () -> Unit,
    aoConfirmar: (uid: String, escolha: EscolhaPessoa) -> Unit,
) {
    // O campo guarda o que foi escrito; o UID normalizado ("8d f3 6e 69" -> "8DF36E69") e calculado a parte.
    // Mudar o texto dentro do onValueChange baralha o teclado e faz perder teclas.
    var uidEscrito by remember { mutableStateOf(uidFixo.orEmpty()) }
    val uid = uidEscrito.uppercase().filter { !it.isWhitespace() && it != ':' && it != '-' }
    var escolha by remember { mutableStateOf<EscolhaPessoa?>(pessoaInicial?.let { EscolhaPessoa.Existente(it) }) }
    val uidValido = FORMATO_UID.matches(uid)

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (uidFixo == null) {
                    OutlinedTextField(
                        value = uidEscrito,
                        onValueChange = { if (it.length <= 40) uidEscrito = it },
                        label = { Text("UID do cartao") },
                        placeholder = { Text("ex.: 8DF36E69") },
                        supportingText = {
                            Text(
                                when {
                                    uid.isEmpty() -> "Aparece no Monitor Serie ao passar o cartao"
                                    !uidValido -> "So 0-9 e A-F, entre 8 e 20 caracteres"
                                    uid != uidEscrito -> "Fica guardado como $uid"
                                    else -> "UID valido"
                                }
                            )
                        },
                        isError = uid.isNotEmpty() && !uidValido,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Text(
                            "Cartao $uidFixo",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Pertence a", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SeletorPessoa(pessoas, escolha) { escolha = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { escolha?.let { aoConfirmar(uid, it) } },
                enabled = uidValido && escolha != null,
            ) { Text("Associar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

// Pesquisa pelo nome; se nao houver ninguem com esse nome, oferece criar a pessoa logo ali
@Composable
private fun SeletorPessoa(pessoas: List<Utilizador>, escolha: EscolhaPessoa?, aoEscolher: (EscolhaPessoa?) -> Unit) {
    if (escolha != null) {
        PessoaEscolhida(escolha, aoMudarPerfil = { perfil ->
            if (escolha is EscolhaPessoa.Nova) aoEscolher(escolha.copy(perfil = perfil))
        }, aoTrocar = { aoEscolher(null) })
        return
    }

    var pesquisa by remember { mutableStateOf("") }
    val encontradas = pessoas.filter { contem(it.nome, pesquisa) }
    val nomeExato = pessoas.any { it.nome.trim().equals(pesquisa.trim(), ignoreCase = true) }

    CampoPesquisa(pesquisa, { pesquisa = it }, "Pesquisar pelo nome")
    Spacer(Modifier.height(4.dp))
    encontradas.take(MAX_RESULTADOS).forEach { p ->
        LinhaPessoa(p.nome, p.perfil, Modifier.clickable { aoEscolher(EscolhaPessoa.Existente(p)) })
    }
    if (encontradas.size > MAX_RESULTADOS) {
        Text(
            "+${encontradas.size - MAX_RESULTADOS} pessoas: escreva mais do nome",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }
    if (pesquisa.isNotBlank() && !nomeExato) {
        // Ninguem com este nome: criar como docente (o perfil pode mudar-se a seguir)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { aoEscolher(EscolhaPessoa.Nova(pesquisa.trim(), "docente")) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Criar \"${pesquisa.trim()}\"", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (encontradas.isEmpty()) "Ninguem com este nome. Criar uma pessoa nova"
                    else "Criar uma pessoa nova com este nome",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else if (pesquisa.isBlank() && pessoas.isEmpty()) {
        Text(
            "Ainda nao ha pessoas: escreva um nome para criar a primeira.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PessoaEscolhida(escolha: EscolhaPessoa, aoMudarPerfil: (String) -> Unit, aoTrocar: () -> Unit) {
    val perfil = when (escolha) {
        is EscolhaPessoa.Existente -> escolha.pessoa.perfil
        is EscolhaPessoa.Nova -> escolha.perfil
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(escolha.nome, perfil)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(escolha.nome, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (escolha is EscolhaPessoa.Nova) "Pessoa nova (so cartao)" else textoPerfil(perfil),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = aoTrocar) { Text("Trocar") }
        }
        // Uma pessoa nova pode ser docente ou funcionario (um administrador precisa de conta: criar em Pessoas)
        if (escolha is EscolhaPessoa.Nova) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("docente", "funcionario").forEach { p ->
                    FilterChip(
                        selected = escolha.perfil == p,
                        onClick = { aoMudarPerfil(p) },
                        label = { Text(textoPerfil(p)) },
                    )
                }
            }
        }
    }
}

@Composable
fun LinhaPessoa(nome: String, perfil: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(nome, perfil, tamanho = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nome, style = MaterialTheme.typography.bodyLarge)
            Text(textoPerfil(perfil), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
