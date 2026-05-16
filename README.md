# Gateway IoT para Monitoramento de Sensores (ESP32)

Este projeto foi desenvolvido como uma solução de monitoramento para agricultura inteligente, funcionando como um Gateway Móvel que integra sensores via Bluetooth (BLE e Clássico) e encaminha os dados para uma central de análise.

## Novidades e Melhorias do Projeto

- **Calibração de Variável Dupla**: O sistema agora permite o ajuste dinâmico do Fator N (ambiente) e do RSSI de Referência (1m), trazendo maior precisão no cálculo de distância.
- **Persistência por Sensor**: As configurações de calibração são salvas individualmente por dispositivo no arquivo `calibracao.xml`, mantendo os ajustes mesmo após reiniciar o aplicativo.
- **Interface em Tempo Real**: Telas de configuração com pré-visualização instantânea do cálculo de distância conforme os parâmetros são editados.
- **Estabilidade da Interface**: Correção de falhas de renderização em listas e contêineres de scroll no Jetpack Compose.

## Arquitetura de Software (MVVM)

O desenvolvimento segue o padrão MVVM para garantir um código organizado e de fácil manutenção:

- **Model**: Responsável pelos dados e lógica de baixo nível. Inclui o `BluetoothRepository` para comunicação de hardware e o `Esp32PacketDecoder` para o processamento de pacotes.
- **View**: Construída com Jetpack Compose, garantindo uma interface reativa que reflete automaticamente o estado dos sensores.
- **ViewModel**: O `BluetoothViewModel` gerencia o estado da aplicação e serve como ponte entre a interface e os repositórios de dados.

## Implementação da Comunicação BLE

A comunicação com o ESP32 foi otimizada para garantir estabilidade e baixo consumo:

1.  **Negociação de MTU**: O aplicativo solicita 64 bytes logo após a conexão, o que é ideal para o nosso pacote de 45 bytes, evitando desperdício de recursos.
2.  **Descoberta de Serviços**: Localização precisa dos canais de comunicação (UUIDs) definidos no firmware.
3.  **Sistema de Notificações**: Configuração do descritor CCCD para que o sensor envie dados proativamente, eliminando a necessidade de consultas constantes.
4.  **Monitoramento de Sinal (RSSI)**: Leitura periódica da potência do sinal para atualização contínua da distância calculada.
5.  **Otimização via Protocolo Binário**: Substituímos o uso de JSON (que gerava pacotes pesados de até 380 bytes) por uma estrutura binária compacta de apenas 45 bytes. Isso resolveu problemas de memória no ESP32 e aumentou a autonomia da bateria.

## Tecnologias Utilizadas

- **Plataforma Android**: Kotlin 2.0, Jetpack Compose, Material Design 3.
- **Comunicação**: Bluetooth Low Energy (BLE) e Bluetooth Classic.
- **Networking e Persistência**: OkHttp para API REST e XML para configurações locais.

## Cálculo de Telemetria e Distância

O sistema utiliza o modelo matemático Log-Distance Path Loss:
$$d = 10 ^ {\frac{RSSI_{ref} - RSSI_{medido}}{10 \cdot n}}$$

Onde o **RSSI_ref** é a potência a 1 metro e o **n** é o fator de perda ambiental, ambos ajustáveis pelo usuário para melhor calibração em campo.

## Materiais de Workshop e Publicação

Abaixo estão os arquivos utilizados na apresentação do Workshop e o artigo técnico detalhando a solução:

- [Artigo Técnico: BLE aplicado a IoT em Agricultura Inteligente](https://github.com/ztechbr/BLE_UTFPR_POS_IV/blob/main/Projeto-ESP32-BLE/Apresentacao/Artigo-BLE%20aplicado%20a%20IoT%20em%20Agricultura%20Inteligente.pdf)
- [Apresentação do Projeto (PDF)](https://github.com/ztechbr/BLE_UTFPR_POS_IV/blob/main/Projeto-ESP32-BLE/Apresentacao/Apresenta%C3%A7%C3%A3o%20BLE%20na%20Agricultura%20Inteligente.pdf)
- [Demonstração em Vídeo: Aplicação em Campo](https://github.com/ztechbr/BLE_UTFPR_POS_IV/blob/main/Projeto-ESP32-BLE/Apresentacao/Aplica%C3%A7%C3%A3o%20BLE.mp4)
- [API BlueSensores (Monitoramento em Tempo Real)](https://api-sensores.ztechnologies.io/)

## Repositórios Complementares

Este ecossistema IoT conta com outros componentes essenciais:
*   **API de Monitoramento (Backend)**: [Servidor API BlueSensores](https://github.com/ztechbr/ServidoAPI_Projeto_BlueSensores_UTFPR) - Recebe e armazena as telemetrias.
*   **App de Visualização (Frontend)**: [Leitura API & Gráficos](https://github.com/ztechbr/APPLeituraAPI_Projeto_BlueSensores_UTFPR) - Consulta histórica e gráficos.

## Instruções de Execução

1. **Permissões**: Certifique-se de conceder acesso ao Bluetooth e Localização.
2. **Firmware**: O ESP32 deve estar com o Service UUID `12345678-1234-1234-1234-1234567890ab`.
3. **Ambiente**: Requer Android Studio Ladybug ou superior e AGP 8.7.3.

---
**Desenvolvido para:** Pós-Graduação em Programação de Dispositivos Móveis - UTFPR.
