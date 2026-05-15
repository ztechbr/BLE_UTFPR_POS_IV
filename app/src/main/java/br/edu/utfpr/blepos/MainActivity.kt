package br.edu.utfpr.blepos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: BluetoothViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BluetoothScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothScreen(viewModel: BluetoothViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }
    var mostrarDiagnostico by remember { mutableStateOf(false) }

    var mostrarDiagnosticoApi by remember { mutableStateOf(false) }

    var mostrarConfiguracoes by remember { mutableStateOf(false) }

    var menuAberto by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ESP32 IoT Monitor", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Navegação entre subtelas: reseta os outros estados ao abrir uma nova
                    IconButton(onClick = { menuAberto = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu"
                        )
                    }

                    DropdownMenu(
                        expanded = menuAberto,
                        onDismissRequest = { menuAberto = false }
                    ) {
                        // Chama tela de diagnóstico do ESP32
                        DropdownMenuItem(
                            text = { Text("Diagnóstico") },
                            leadingIcon = {
                                Icon(Icons.Default.Info, contentDescription = null)
                            },
                            onClick = {
                                menuAberto = false
                                mostrarDiagnosticoApi = false
                                mostrarConfiguracoes = false
                                mostrarDiagnostico = true
                            }
                        )
                        // Chama tela de diagnostico da API
                        if (viewModel.apiEnvioAtivo)
                            DropdownMenuItem(
                            text = { Text("Diagnóstico da API") },
                            leadingIcon = {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                            },
                            onClick = {
                                menuAberto = false
                                mostrarDiagnostico = false
                                mostrarConfiguracoes = false
                                mostrarDiagnosticoApi = true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Configurações") },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            },
                            onClick = {
                                menuAberto = false
                                mostrarDiagnostico = false
                                mostrarDiagnosticoApi = false
                                mostrarConfiguracoes = true
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ================= DIAGNÓSTICOS (SUBTELAS) =================

            // Tela de diagnóstico dos sensores
            if (mostrarDiagnostico) {
                TelaDiagnostico(
                    viewModel = viewModel,
                    onVoltar = { mostrarDiagnostico = false }
                )
                return@Column
            }

            // Tela de diagnóstico da API
            if (mostrarDiagnosticoApi) {
                TelaDiagnosticoApi(
                    viewModel = viewModel,
                    onVoltar = { mostrarDiagnosticoApi = false }
                )
                return@Column
            }

            // Tela de Configurações
            if (mostrarConfiguracoes) {
                TelaConfiguracoes(
                    viewModel = viewModel,
                    onVoltar = { mostrarConfiguracoes = false }
                )
                return@Column
            }
            // Seção de Conexões
            Text(
                text = "Comunicação com ESP32",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionButton(
                    label = "BLE",
                    isConnected = viewModel.bleConectado,
                    onClick = {
                        if (hasPermissions) viewModel.toggleBleConnection()
                        else permissionLauncher.launch(requiredPermissions)
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.BluetoothAudio
                )

                ConnectionButton(
                    label = "Clássico",
                    isConnected = viewModel.btClassicoConectado,
                    onClick = {
                        if (hasPermissions) viewModel.toggleClassicConnection()
                        else permissionLauncher.launch(requiredPermissions)
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SettingsBluetooth
                )
            }

            // Status Badge
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = viewModel.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))

            // Seção de Dados (Tabela)
            Text(
                text = "Leituras do Sensor",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        //.fillMaxSize()
                        //.verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val contentColor = if (viewModel.estaComunicando) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

                    DataRow(label = "Temperatura", value = viewModel.temperatura, color = contentColor)
                    DataRow(label = "Umidade", value = viewModel.umidade, color = contentColor)
                    DataRow(label = "Tensão", value = viewModel.tensao, color = contentColor)
                    DataRow(label = "Corrente", value = viewModel.corrente, color = contentColor)
                    DataRow(label = "Potência", value = viewModel.potencia, color = contentColor)
                    DataRow(label = "Lux", value = viewModel.luximetro, color = contentColor)
                    DataRow(label = "Latitude", value = viewModel.latitude, color = contentColor)
                    DataRow(label = "Longitude", value = viewModel.longitude, color = contentColor)
                    DataRow(label = "RSSI ESP32", value = viewModel.rssiBle, color = contentColor)
                    DataRow(label = "RSSI Celular", value = viewModel.rssiBleCelular, color = contentColor)
                    DataRow(label = "Distância APP", value = viewModel.distanciaApp, color = contentColor)
                    DataRow(label = "Diagnóstico", value = viewModel.diagnosticoSensores, color = contentColor)
                }
            }
            //  Última atualização do ESP32
            AnimatedVisibility(
                visible = viewModel.ultimaAtualizacaoEsp32.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = viewModel.ultimaAtualizacaoEsp32,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            // Seção API
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Conexão API",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ConnectionButton(
                label = "API",
                isConnected = viewModel.apiEnvioAtivo,
                onClick = { viewModel.toggleEnvioApi() },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.CloudUpload
            )

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = viewModel.statusApi,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            AnimatedVisibility(
                visible = viewModel.ultimaAtualizacaoApi.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = viewModel.ultimaAtualizacaoApi,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }
    }
}
@Composable
fun TelaDiagnostico(
    viewModel: BluetoothViewModel,
    onVoltar: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Diagnóstico dos Sensores",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataRow(
                    "Temperatura",
                    if (viewModel.tempFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "Umidade",
                    if (viewModel.humFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "INA219",
                    if (viewModel.inaFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "OLED",
                    if (viewModel.oledFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "BH1750",
                    if (viewModel.bh1750Falha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "GPS",
                    if (viewModel.gpsFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    "RSSI BLE",
                    if (viewModel.rssiFalha) "FALHA" else "OK",
                    MaterialTheme.colorScheme.onSurface
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataRow("BLE", viewModel.status, MaterialTheme.colorScheme.onSurface)
                DataRow("API", viewModel.statusApi, MaterialTheme.colorScheme.onSurface)
            }
        }

        Button(
            onClick = onVoltar,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Voltar")
        }
    }
}

@Composable
fun TelaDiagnosticoApi(
    viewModel: BluetoothViewModel,
    onVoltar: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Diagnóstico da API",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataRow("Envio API", if (viewModel.apiEnvioAtivo) "Habilitado" else "Desabilitado", MaterialTheme.colorScheme.onSurface)
                DataRow("Status", viewModel.statusApi, MaterialTheme.colorScheme.onSurface)
                DataRow("HTTP", viewModel.ultimoCodigoHttpApi, MaterialTheme.colorScheme.onSurface)
                DataRow("Último envio", viewModel.ultimaAtualizacaoApi, MaterialTheme.colorScheme.onSurface)
            }
        }

        Text(
            text = "Último JSON enviado",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = viewModel.ultimoJsonApi,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = onVoltar,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Voltar")
        }
    }
}
@Composable
fun TelaConfiguracoes(
    viewModel: BluetoothViewModel,
    onVoltar: () -> Unit
) {
    var fatorInput by remember { mutableStateOf(viewModel.fatorNLocal.toString()) }
    var rssiRefInput by remember { mutableStateOf(viewModel.rssiRefLocal.toString()) }
    var previewDistancia by remember { mutableStateOf<String?>(null) }

    // Sincroniza campos quando o sensor muda
    LaunchedEffect(viewModel.tagAtual) {
        fatorInput = viewModel.fatorNLocal.toString()
        rssiRefInput = viewModel.rssiRefLocal.toString()
        previewDistancia = null
    }
    // Função para atualizar o preview em tempo real
    fun atualizarPreview() {
        val n = fatorInput.toDoubleOrNull()
        val r = rssiRefInput.toDoubleOrNull()
        val rssiLido = viewModel.getUltimoRssiApp()
        if (n != null && r != null && rssiLido != null) {
            val dist = viewModel.calcularDistancia(rssiLido, n, r)
            previewDistancia = if (dist != null) "%.2f m".format(Locale.US, dist) else "Erro"
        } else {
            previewDistancia = null
        }
    }

    var mostrarConfirmacao by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (mostrarConfirmacao) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacao = false },
            title = { Text("Confirmar Alteração") },
            text = { Text("Deseja salvar o novo Fator N (${fatorInput}) e o novo RSSI de Referência (${rssiRefInput}) no arquivo de configuração?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val novoFator = fatorInput.toDoubleOrNull()
                        val novoRssiRef = rssiRefInput.toDoubleOrNull()
                        if (novoFator != null && novoRssiRef != null) {
                            viewModel.salvarCalibracao(novoFator, novoRssiRef)
                        }
                        mostrarConfirmacao = false
                    }
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarConfirmacao = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configurações de Calibração",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Calibração de Distância",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Sensor Atual: ${viewModel.tagAtual}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                HorizontalDivider()
                // 1. Valores Atuais
                DataRow(
                    label = "Fator N Atual",
                    value = "%.2f".format(Locale.US, viewModel.fatorNLocal),
                    color = MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    label = "RSSI Ref. Atual (1m)",
                    value = "%.1f dBm".format(Locale.US, viewModel.rssiRefLocal),
                    color = MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    label = "RSSI Recebido (rec)",
                    value = viewModel.rssiBleCelular,
                    color = MaterialTheme.colorScheme.onSurface
                )
                DataRow(
                    label = "Distância Atual",
                    value = viewModel.distanciaApp,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider()

                // 2. Campo para editar RSSI de Referência
                OutlinedTextField(
                    value = rssiRefInput,
                    onValueChange = { 
                        rssiRefInput = it
                        atualizarPreview()
                    },
                    label = { Text("Novo RSSI Ref em 1m (dBm)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )
                // 3. Campo para editar Fator N
                OutlinedTextField(
                    value = fatorInput,
                    onValueChange = { 
                        fatorInput = it
                        atualizarPreview()
                    },
                    label = { Text("Novo Fator n") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            atualizarPreview()
                            focusManager.clearFocus()
                        }
                    )
                )
                // 4. Preview da nova distância
                AnimatedVisibility(visible = previewDistancia != null) {
                    Column {
                        DataRow(
                            label = "Nova Distância Prevista",
                            value = previewDistancia ?: "",
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                // 5. Botão de Salvar no XML
                Button(
                    onClick = { mostrarConfirmacao = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = viewModel.tagAtual != "--" && 
                             fatorInput.toDoubleOrNull() != null && 
                             rssiRefInput.toDoubleOrNull() != null
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Salvar no XML")
                }
            }
        }

        if (viewModel.tagAtual == "--") {
            Text(
                text = "Conecte-se ao ESP32 para identificar o sensor antes de calibrar.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onVoltar,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Voltar")
        }
    }
}
@Composable
fun DataRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun ConnectionButton(
    label: String,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector
) {
    if (isConnected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.LinkOff, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Parar $label")
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(label)
        }
    }
}
