# ESP32 IoT Monitor & Gateway (BLE/Classic)

Este projeto é uma solução completa de monitoramento IoT, atuando como um **Gateway Móvel** que coleta dados de sensores via Bluetooth (BLE ou Clássico), processa telemetria avançada e encaminha as informações para uma API de monitoramento agrícola.

## 🚀 Novidades e Melhorias Recentes

- **Calibração de Variável Dupla**: Agora o sistema permite o ajuste dinâmico tanto do **Fator N (Ambiente)** quanto do **RSSI de Referência (1m)**, garantindo maior precisão no cálculo de distância em diferentes cenários.
- **Persistência por Sensor**: Os parâmetros de calibração são salvos individualmente por `codsensor` em um arquivo `calibracao.xml` interno, mantendo as configurações mesmo após reiniciar o app ou trocar de sensor.
- **Preview em Tempo Real**: Interface de configurações com cálculo instantâneo da distância prevista conforme os valores de calibração são editados.
- **Correção de Estabilidade**: Resolvido crash de `IllegalStateException` relacionado a contêineres de scroll aninhados no Jetpack Compose.

## 🏗️ Arquitetura MVVM (Model-View-ViewModel)

O projeto segue o padrão de arquitetura **MVVM**, promovendo a separação de responsabilidades e facilitando a manutenção:

-   **Model**: Representado pelas classes de dados e repositórios. O `BluetoothRepository` gerencia a comunicação de baixo nível com o hardware e APIs externas, enquanto o `Esp32PacketDecoder` lida com a lógica de processamento de pacotes brutos.
-   **View**: Implementada com **Jetpack Compose**. A UI é reativa e "observa" as mudanças de estado no ViewModel. Não contém lógica de negócio, apenas exibe os dados e repassa eventos do usuário (toques, entradas de texto).
-   **ViewModel**: O `BluetoothViewModel` é o cérebro da UI. Ele expõe estados (como `temperatura`, `status`, `bleConectado`) usando `mutableStateOf` do Compose e encapsula a lógica de interação entre a View e o Model (ex: disparar uma conexão ou salvar calibração).

## 📡 Comunicação BLE (Kotlin/Android)

Para a comunicação com o ESP32 via BLE, o app implementa os seguintes passos críticos no Android:

1.  **Negociação de MTU**: O app solicita `gatt.requestMtu(64)` logo após a conexão. Este valor é otimizado para o pacote binário de 45 bytes (considerando o overhead do protocolo), evitando o consumo desnecessário de recursos que valores muito altos como 247 causariam.
2.  **Descoberta de Serviços e Características**: Localização do `SERVICE_UUID` e `CHARACTERISTIC_UUID` específicos definidos no firmware do ESP32.
3.  **Habilitação de Notificações (CCCD)**: Para receber dados sem solicitar (push), é necessário configurar o descritor `00002902-0000-1000-8000-00805f9b34fb` como `ENABLE_NOTIFICATION_VALUE`.
4.  **Leitura de RSSI Remoto**: Uso de `gatt.readRemoteRssi()` em um loop de polling (ex: 5s) para atualizar a potência do sinal e recalcular a distância do sensor continuamente.
5.  **Otimização via Protocolo Binário (Compactação, Validação e Segurança)**: 
    A transição do formato JSON (que gerava payloads entre 300 e 380 bytes) para uma codificação binária proprietária foi fundamental para a estabilidade do sistema. O volume de dados anterior excedia os limites ideais do BLE para eficiência energética e causava travamentos na comunicação, além de sobrecarregar a memória RAM do ESP32. Esta codificação binária tem como objetivo **compactar, validar e otimizar o código com segurança e economia de dados de envio**:
    - **Compactação**: Redução drástica do payload para um pacote fixo de apenas 45 bytes, otimizando o tráfego de rádio.
    - **Validação de Integridade**: Uso de um cabeçalho de sincronismo (`0xAA 0x55`) e verificação via `CRC16` para garantir que apenas dados íntegros sejam processados.
    - **Eficiência e Segurança**: Minimização do tempo de rádio ativo e proteção contra pacotes corrompidos, garantindo a escalabilidade e a autonomia do sensor.

## 🛠 Stack Tecnológica

- **Android Stack**: Kotlin 2.0, Jetpack Compose, ViewModel, Material 3.
- **Networking**: OkHttp para persistência de dados na nuvem via REST.
- **Serialização**: XML para configurações locais e JSON para integração com API.

## 📐 Lógica de Telemetria e Distância

O app utiliza o modelo de propagação *Log-Distance Path Loss*:
$$d = 10 ^ {\frac{RSSI_{ref} - RSSI_{medido}}{10 \cdot n}}$$

- **RSSI_ref**: Valor de referência (dBm) medido a 1 metro (configurável).
- **n**: Fator de perda de percurso (ajustável: 2.0 para campo aberto, >3.0 para ambientes com obstáculos).

## 📡 Estrutura da API

Os dados são enviados no seguinte formato JSON:

| Campo | Descrição | Exemplo |
| :--- | :--- | :--- |
| `codsensor` | Identificador único (TAG) | `SENSOR_01` |
| `distcalc_app` | Distância calculada (m) | `3.15` |
| `ref_rssi_dbm` | RSSI de referência usado | `-59.0` |
| `fator_n` | Fator ambiental usado | `2.1` |
| `scomunicacao` | 1: BLE, 2: Classic | `1` |

## 🔗 Repositórios Complementares

Este ecossistema IoT conta com outros componentes essenciais:
*   **API de Monitoramento (Backend)**: [Servidor API BlueSensores](https://github.com/ztechbr/ServidoAPI_Projeto_BlueSensores_UTFPR) - Responsável por receber, validar e armazenar as telemetrias enviadas pelo Gateway.
*   **App de Visualização (Frontend)**: [Leitura API & Gráficos](https://github.com/ztechbr/APPLeituraAPI_Projeto_BlueSensores_UTFPR) - Aplicativo complementar para consulta dos dados históricos e visualização gráfica dos sensores.

## 📋 Como Executar

1. **Permissões**: O app gerencia permissões de `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` e `ACCESS_FINE_LOCATION`.
2. **Firmware**: O ESP32 deve estar configurado com o Service UUID `12345678-1234-1234-1234-1234567890ab`.
3. **Build**: Requer Android Studio Ladybug+ e AGP 8.7.3.

---
**Desenvolvido para:** Pós-Graduação em Programação de Dispositivos Móveis - UTFPR.
