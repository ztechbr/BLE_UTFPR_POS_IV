# ESP32 IoT Monitor & Gateway (BLE/Classic)

Este projeto é uma solução completa de monitoramento IoT, atuando como um **Gateway Móvel** que coleta dados de sensores via Bluetooth (BLE ou Clássico), processa telemetria avançada e encaminha as informações para uma API de monitoramento agrícola.

## 🚀 Destaques da Versão "Refatorada"

- **Dual-Stack Bluetooth**: 
    - **Classic (RFCOMM)**: Thread dedicada para streaming contínuo via Sockets.
    - **BLE (GATT)**: Arquitetura baseada em eventos com negociação de MTU (100 bytes) para payloads grandes.
- **Inteligência de Borda (Edge Computing)**:
    - **Cálculo de Distância**: Implementação do modelo de propagação *Log-Distance Path Loss* para estimar a distância do sensor com base no RSSI.
    - **Filtragem de Dados**: Agregação de pacotes e validação de integridade (CRC).
- **Interface Material 3**:
    - Dashboard dinâmico que altera o estado visual (nitidez/cinza) conforme a vitalidade da conexão.
    - Tabela de dados alinhada com precisão.

## 🛠 Stack Tecnológica

- **Android Stack**: Kotlin 2.0, Jetpack Compose, ViewModel, LiveData.
- **Networking**: OkHttp para persistência de dados na nuvem.
- **Protocolo**: JSON estruturado para telemetria de solo e ambiente.

## 📐 Lógica de Telemetria e Distância

O app utiliza o RSSI (Received Signal Strength Indicator) para estimar a proximidade do sensor utilizando a fórmula:
$$d = 10 ^ {\frac{RSSI_{ref} - RSSI_{medido}}{10 \cdot n}}$$

Onde:
- **RSSI_ref**: Potência medida a 1 metro de distância.
- **n**: Fator de perda de percurso (ajustável conforme o ambiente, ex: Industrial = 3.0).

## 📡 Estrutura de Integração (API)

Os dados são mapeados pelo `ApiPayloadMapper` e enviados via `ApiDatasource` no seguinte formato:

| Campo | Descrição | Exemplo |
| :--- | :--- | :--- |
| `codsensor` | Identificador único da TAG/ESP32 | `SENSOR_01` |
| `distcalc_app` | Distância calculada em metros | `2.45` |
| `scomunicacao` | Tipo de conexão (1: BLE, 2: Classic) | `1` |
| `stensao` | Leitura de bateria/tensão do sensor | `3.30` |
| `temp_solo` | Temperatura capturada | `25.5` |

## 📋 Como Executar

1. **Permissões**: O app solicita automaticamente permissões de Scan, Connect e Localização (necessária para RSSI em BLE).
2. **Pareamento**: Para Bluetooth Clássico, realize o pareamento nas configurações do Android com o nome `ESP32_MONITOR_BT`.
3. **Build**: Utilize o Android Studio Ladybug ou superior com AGP 8.7.3.

---
**Desenvolvido para:** Pós-Graduação em Engenharia de Software - UTFPR.
