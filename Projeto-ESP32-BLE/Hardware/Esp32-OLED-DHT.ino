/*
  ESP32 + DHT22 + OLED + INA219 + BH1750 + GPS GY-NEO6MV2
  BLE + Bluetooth Classico com pacote binario e CRC16

  Ideia do projeto:
  - Coleta amostras a cada 500 ms.
  - Fecha uma janela de 10 segundos.
  - Calcula media das variaveis validas.
  - Envia um pacote binario pequeno para o celular.
  - O celular decodifica o pacote e depois pode montar JSON para API/BD.

  Pinos atuais:
  DHT22: GPIO 19
  I2C OLED/INA219/BH1750: SDA 21 / SCL 22
  LED BLE: GPIO 17
  LED BT Classico: GPIO 18
  GPS GY-NEO6MV2: TX do GPS -> GPIO 16 do ESP32
                  RX do GPS -> GPIO 23 do ESP32 (opcional)

  Bibliotecas Arduino IDE:
  - DHTesp
  - Adafruit SSD1306
  - Adafruit GFX
  - Adafruit INA219
  - BH1750
  - TinyGPSPlus

  BLE Device Name: ESP32_MONITOR_BLE
  Bluetooth Classico Name: ESP32_MONITOR_BT
*/

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "DHTesp.h"
#include <Adafruit_INA219.h>
#include <BH1750.h>
#include <TinyGPSPlus.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "BluetoothSerial.h"
#include "esp_gap_ble_api.h"
#include "esp_bt_defs.h"

/*#include "esp_task_wdt.h"
#include "esp_system.h"
#include "Preferences.h"
*/
// -----------------------------------------------------------------------------
// IDENTIFICACAO DO EQUIPAMENTO
// -----------------------------------------------------------------------------

// Tag com 6 caracteres. Funciona como um endereco logico do equipamento.
// No futuro voce pode usar EQP001, EQP002, EQP003, etc.
const char EQUIPMENT_TAG[7] = "EQP001";

// -----------------------------------------------------------------------------
// OLED
// -----------------------------------------------------------------------------

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_ADDRESS 0x3C

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// -----------------------------------------------------------------------------
// DHT22
// -----------------------------------------------------------------------------

#define DHT_PIN 19
DHTesp dhtSensor;

// -----------------------------------------------------------------------------
// INA219
// -----------------------------------------------------------------------------

Adafruit_INA219 ina219;

// -----------------------------------------------------------------------------
// BH1750 - Lightmeter
// -----------------------------------------------------------------------------

BH1750 lightMeter;

// -----------------------------------------------------------------------------
// GPS
// -----------------------------------------------------------------------------

TinyGPSPlus gps;
HardwareSerial gpsSerial(2);

#define GPS_RX_PIN 16  // ligar no TX do GPS
#define GPS_TX_PIN 23  // ligar no RX do GPS, se for usar. Pode deixar sem ligar.

// -----------------------------------------------------------------------------
// LEDS
// -----------------------------------------------------------------------------

#define LED_BLE 17
#define LED_BT  18

// -----------------------------------------------------------------------------
// BLUETOOTH CLASSICO
// -----------------------------------------------------------------------------

BluetoothSerial SerialBT;
#define BT_CLASSIC_NAME "ESP32_MONITOR_BT"

// -----------------------------------------------------------------------------
// BLE
// -----------------------------------------------------------------------------

#define BLE_DEVICE_NAME "ESP32_MONITOR_BLE"

#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-1234-1234-1234-abcdef123456"

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;

bool bleConnected = false;
bool btConnected = false;

// Endereco do celular conectado via BLE. E necessario para solicitar o RSSI.
esp_bd_addr_t bleRemoteAddress;
bool bleRemoteAddressOk = false;
bool rssiReadPending = false;

// -----------------------------------------------------------------------------
// TEMPORIZACAO
// -----------------------------------------------------------------------------

const unsigned long INTERVALO_AMOSTRA_MS = 500;
const unsigned long INTERVALO_ENVIO_MS = 10000;
const unsigned long INTERVALO_DHT_MS = 2000; // DHT22 - leitura a cada 2000 ms.

unsigned long lastSample = 0;
unsigned long lastPacket = 0;
unsigned long lastDhtRead = 0;
unsigned long lastBlink = 0;
bool blinkState = false;

// -----------------------------------------------------------------------------
// VALORES INSTANTANEOS
// -----------------------------------------------------------------------------

float temp = -9999;
float hum = -9999;
float busVoltage = -9999;
float current_mA = -9999;
float power_mW = -9999;
float lux = -9999;
double latitude = 0;
double longitude = 0;
int rssiEsp32Dbm = 127; // 127 = invalido

// Flags de erro. O ESP32 continua funcionando mesmo se algum sensor falhar.
bool tempErro = true;
bool humErro = true;
bool inaErro = true;
bool oledErro = true;
bool bh1750Erro = true;
bool gpsErro = true;
bool rssiEsp32Erro = true;

// -----------------------------------------------------------------------------
// ACUMULADORES DA JANELA DE 10 SEGUNDOS
// -----------------------------------------------------------------------------

float somaTemp = 0;
float somaHum = 0;
float somaVoltage = 0;
float somaCurrent = 0;
float somaPower = 0;
float somaLux = 0;
float somaRssi = 0;
double somaLat = 0;
double somaLon = 0;

uint16_t totalAmostras = 0;
uint16_t countTemp = 0;
uint16_t countHum = 0;
uint16_t countIna = 0;
uint16_t countLux = 0;
uint16_t countGps = 0;
uint16_t countRssi = 0;
uint32_t packetSeq = 0;

// -----------------------------------------------------------------------------
// STATUS DOS SENSORES - BITMASK
// -----------------------------------------------------------------------------

#define STATUS_TEMP_ERRO   0x0001
#define STATUS_HUM_ERRO    0x0002
#define STATUS_INA_ERRO    0x0004
#define STATUS_OLED_ERRO   0x0008
#define STATUS_BH1750_ERRO 0x0010
#define STATUS_GPS_ERRO    0x0020
#define STATUS_RSSI_ERRO   0x0040

// -----------------------------------------------------------------------------
// PACOTE BINARIO
// -----------------------------------------------------------------------------

// Pacote pequeno para BLE e BT Classico.
// packed evita bytes extras de alinhamento no meio da struct.
typedef struct __attribute__((packed)) {
  uint8_t header1;          // 0xAA
  uint8_t header2;          // 0x55
  uint8_t version;          // versao do protocolo
  char tag[6];              // exemplo: EQP001
  uint32_t seq;             // contador de pacotes
  uint16_t window_s;        // janela em segundos
  uint16_t samples;         // quantidade de amostras da janela
  int16_t temp_x10;         // temperatura * 10
  uint16_t hum_x10;         // umidade * 10
  uint16_t voltage_mV;      // tensao em mV
  int16_t current_x10_mA;   // corrente em mA * 10
  uint16_t power_x10_mW;    // potencia em mW * 10
  int16_t rssi_x10_dbm;     // RSSI em dBm * 10
  int32_t lat_x1e7;         // latitude * 10.000.000
  int32_t lon_x1e7;         // longitude * 10.000.000
  uint32_t lux_x100;        // lux * 100
  uint16_t status;          // bits de erro dos sensores
  uint16_t crc16;           // CRC16 Modbus do pacote, sem estes 2 bytes finais
} SensorPacket;

// -----------------------------------------------------------------------------
// FUNCOES AUXILIARES
// -----------------------------------------------------------------------------

bool dispositivoI2CExiste(byte endereco) {
  Wire.beginTransmission(endereco);
  byte erro = Wire.endTransmission();
  return (erro == 0);
}

uint16_t crc16Modbus(const uint8_t* data, size_t length) {
  uint16_t crc = 0xFFFF;

  for (size_t i = 0; i < length; i++) {
    crc ^= data[i];
    for (uint8_t bit = 0; bit < 8; bit++) {
      if (crc & 0x0001) {
        crc = (crc >> 1) ^ 0xA001;
      } else {
        crc >>= 1;
      }
    }
  }

  return crc;
}

int16_t scaleInt16(float value, float factor, int16_t invalidValue) {
  if (isnan(value) || value <= -9000) return invalidValue;
  float scaled = value * factor;
  if (scaled > 32767) return 32767;
  if (scaled < -32768) return -32768;
  return (int16_t)round(scaled);
}

uint16_t scaleUInt16(float value, float factor, uint16_t invalidValue) {
  if (isnan(value) || value < 0 || value <= -9000) return invalidValue;
  float scaled = value * factor;
  if (scaled > 65535) return 65535;
  return (uint16_t)round(scaled);
}

uint32_t scaleUInt32(float value, float factor, uint32_t invalidValue) {
  if (isnan(value) || value < 0 || value <= -9000) return invalidValue;
  double scaled = (double)value * factor;
  if (scaled > 4294967295.0) return 4294967295UL;
  return (uint32_t)round(scaled);
}

uint16_t montarStatusSensores() {
  uint16_t status = 0;
  if (tempErro) status |= STATUS_TEMP_ERRO;
  if (humErro) status |= STATUS_HUM_ERRO;
  if (inaErro) status |= STATUS_INA_ERRO;
  if (oledErro) status |= STATUS_OLED_ERRO;
  if (bh1750Erro) status |= STATUS_BH1750_ERRO;
  if (gpsErro) status |= STATUS_GPS_ERRO;
  if (rssiEsp32Erro) status |= STATUS_RSSI_ERRO;
  return status;
}

// -----------------------------------------------------------------------------
// CALLBACK RSSI BLE
// -----------------------------------------------------------------------------

void gapCallback(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
  if (event == ESP_GAP_BLE_READ_RSSI_COMPLETE_EVT) {
    rssiReadPending = false;

    if (param->read_rssi_cmpl.status == ESP_BT_STATUS_SUCCESS) {
      rssiEsp32Dbm = param->read_rssi_cmpl.rssi;
      rssiEsp32Erro = false;
    } else {
      rssiEsp32Dbm = 127;
      rssiEsp32Erro = true;
    }
  }
}

// -----------------------------------------------------------------------------
// CALLBACK BLE
// -----------------------------------------------------------------------------

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer, esp_ble_gatts_cb_param_t *param) {
    bleConnected = true;
    digitalWrite(LED_BLE, HIGH);

    memcpy(bleRemoteAddress, param->connect.remote_bda, sizeof(esp_bd_addr_t));
    bleRemoteAddressOk = true;

    Serial.println("Celular conectado via BLE.");
  }

  void onDisconnect(BLEServer* pServer, esp_ble_gatts_cb_param_t *param) {
    bleConnected = false;
    digitalWrite(LED_BLE, LOW);

    bleRemoteAddressOk = false;
    rssiReadPending = false;
    rssiEsp32Dbm = 127;
    rssiEsp32Erro = true;

    Serial.println("Celular desconectou do BLE.");

    delay(300);
    BLEDevice::startAdvertising();
    Serial.println("BLE advertising reiniciado.");
  }
};

// -----------------------------------------------------------------------------
// setup()
// -----------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);

  pinMode(LED_BLE, OUTPUT);
  pinMode(LED_BT, OUTPUT);

  digitalWrite(LED_BLE, LOW);
  digitalWrite(LED_BT, LOW);

  Wire.begin(21, 22);

  // DHT22
  dhtSensor.setup(DHT_PIN, DHTesp::DHT22);

  // OLED
  // INICIO ALTERACAO - Não travar o ESP32 se o OLED falhar
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS)) {
    Serial.println("ERRO: OLED nao encontrado. Sistema continua transmitindo.");
    oledErro = true;
  } else {
    oledErro = false;
  }
  // FIM ALTERACAO

  // INA219
/*
  if (!ina219.begin()) {
    Serial.println("Erro ao iniciar INA219");
    display.clearDisplay();
    display.setCursor(0, 0);
    display.setTextColor(SSD1306_WHITE);
    display.println("Erro INA219");
    display.display();
    while (true);
  }
*/

if (!ina219.begin()) {
    Serial.println("ERRO: INA219 nao iniciou. O sistema continua, mas marca erro no pacote.");
    inaErro = true;
  } else {
    inaErro = false;
  }

  // BH1750
  if (!lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE)) {
    Serial.println("ERRO: BH1750 nao iniciou. Verifique o I2C/endereco.");
    bh1750Erro = true;
  } else {
    bh1750Erro = false;
  }
  
  // GPS GY-GPS6MV2
  gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  // Bluetooth Classico
  SerialBT.begin(BT_CLASSIC_NAME);

  // BLE
  BLEDevice::init(BLE_DEVICE_NAME);

  // Aumenta a potencia de transmissao BLE para facilitar os testes iniciais.
  BLEDevice::setPower(ESP_PWR_LVL_P9);

  // O pacote binario tem 45 bytes.
  // MTU 247 deixa folga suficiente para notificacoes BLE.
  BLEDevice::setMTU(247);

  /*Essa linha implementa o registro do callback GAP BLE responsável pelo tratamento assíncrono
   dos eventos de RSSI e demais eventos da camada GAP do Bluetooth Low Energy.  */
  esp_ble_gap_register_callback(gapCallback);

  // Cria o servidor BLE do ESP32.
  // O ESP32 será o "servidor" e o celular será o "cliente".
  pServer = BLEDevice::createServer();

  // Liga os callbacks de conexao/desconexao BLE.
  // Quando o celular conectar ou desconectar, as funcoes da classe
  // MyServerCallbacks() serao chamadas automaticamente.
  pServer->setCallbacks(new MyServerCallbacks());

  // -----------------------------------------------------------------------------
  // CRIACAO DO SERVICO BLE
  // -----------------------------------------------------------------------------

  // Cria o servico BLE principal do projeto.
  // O SERVICE_UUID funciona como identificacao unica do servico de telemetria.
  BLEService* pService = pServer->createService(SERVICE_UUID);

  // -----------------------------------------------------------------------------
  // CRIACAO DA CHARACTERISTIC BLE
  // -----------------------------------------------------------------------------

  // Cria a characteristic BLE.
  // A characteristic sera usada para enviar os pacotes binarios para o celular.
  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,

    // PROPERTY_READ:
    // Permite que o celular leia o valor atual da characteristic.

    // PROPERTY_NOTIFY:
    // Permite que o ESP32 envie dados automaticamente para o celular
    // sem o app precisar ficar perguntando toda hora.
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  // -----------------------------------------------------------------------------
  // DESCRIPTOR BLE2902
  // -----------------------------------------------------------------------------

  // Adiciona o descriptor BLE2902.
  // Esse descriptor habilita oficialmente o notify no BLE.
  // Sem ele, muitos celulares Android/iPhone nao recebem notificacoes corretamente.
  pCharacteristic->addDescriptor(new BLE2902());

  // -----------------------------------------------------------------------------
  // INICIA O SERVICO BLE
  // -----------------------------------------------------------------------------

  // Ativa o servico BLE.
  // A partir daqui o servico ja existe e pode ser usado.
  pService->start();

  // -----------------------------------------------------------------------------
  // CONFIGURACAO DO ADVERTISING BLE
  // -----------------------------------------------------------------------------

  // Pega o objeto responsavel pelo advertising BLE.
  // Advertising = ESP32 anunciando que existe para o celular.
  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();

  // ****************
  BLEAdvertisementData advertisementData;
  advertisementData.setName(BLE_DEVICE_NAME);
  advertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));

  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(BLE_DEVICE_NAME);

  pAdvertising->setAdvertisementData(advertisementData);
  pAdvertising->setScanResponseData(scanResponseData);
  // ****************

  // Coloca o UUID do servico no advertising.
  // O app Android pode usar esse UUID para identificar o ESP32,
  // mesmo quando o nome nao aparece no scan.
  pAdvertising->addServiceUUID(SERVICE_UUID);

  // Em alguns celulares Android, deixar scan response como true
  // pode fazer o nome nao aparecer corretamente.
  // Para este teste, deixamos false.
  pAdvertising->setScanResponse(true); // **************** alterado de false para true **********************

  // Parametros de compatibilidade BLE.
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMaxPreferred(0x12);

  // Inicia advertising.
  BLEDevice::startAdvertising();

  Serial.println("BLE advertising iniciado.");
  Serial.print("Nome BLE: ");
  Serial.println(BLE_DEVICE_NAME);
  Serial.println("SERVICE_UUID: " SERVICE_UUID);

  // INICIO ALTERACAO - Só escreve no OLED se ele estiver funcionando
  if (!oledErro) {
    display.clearDisplay();
    display.setTextColor(SSD1306_WHITE);
    display.setTextSize(1);
    display.setCursor(0, 0);
    display.println("ESP32 Telemetria");
    display.print("TAG: ");
    display.println(EQUIPMENT_TAG);
    display.println("BLE + BT Classico");
    display.display();
    delay(2000);
  }
  Serial.println("Sistema iniciado.");
  Serial.print("Tamanho do pacote binario: ");
  Serial.print(sizeof(SensorPacket));
  Serial.println(" bytes");
}

// -----------------------------------------------------------------------------
// LOOP PRINCIPAL
// -----------------------------------------------------------------------------

void loop() {
  // Guarda o tempo atual desde que o ESP32 ligou.
  // Usamos millis() para controlar os tempos sem travar o programa com delay().
  unsigned long agora = millis();

  // ---------------------------------------------------------------------------
  // LEITURA CONTINUA DO GPS
  // ---------------------------------------------------------------------------
  // O modulo GPS envia dados o tempo todo pela serial em formato NMEA.
  // Por isso precisamos ler sempre que houver bytes disponiveis.
  // Se deixar para ler apenas a cada 10 segundos, podemos perder informacoes.
  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }

  // ---------------------------------------------------------------------------
  // PISCA DO DISPLAY / INDICADOR DE VIDA
  // ---------------------------------------------------------------------------
  // Alterna o estado da bolinha/indicador a cada 1 segundo.
  // Serve apenas para mostrar no display que o programa esta rodando.
  if (agora - lastBlink >= 1000) {
    lastBlink = agora;
    blinkState = !blinkState;
  }

  // ---------------------------------------------------------------------------
  // COLETA DAS AMOSTRAS
  // ---------------------------------------------------------------------------
  // A cada INTERVALO_AMOSTRA_MS, normalmente 500 ms,
  // o ESP32 le os sensores e acumula os valores.
  //
  // Exemplo:
  // INTERVALO_AMOSTRA_MS = 500 ms
  // INTERVALO_ENVIO_MS   = 10000 ms
  //
  // Resultado:
  // 10 segundos / 0,5 segundo = aproximadamente 20 amostras.
  if (agora - lastSample >= INTERVALO_AMOSTRA_MS) {
    lastSample = agora;

    // Le DHT22, INA219, BH1750, GPS e atualiza flags de erro.
    lerSensores(agora);

    // Atualiza o estado das conexoes Bluetooth/BLE.
    // Mantem informacoes como conectado/desconectado.
    atualizarBluetooth();

    // Solicita ao ESP32 a leitura do RSSI do celular conectado via BLE.
    // A resposta nao vem imediatamente; ela chega depois no gapCallback().
    solicitarRssiEsp32();

    // Soma os valores validos lidos nesta amostra.
    // Esses acumuladores serao usados para calcular a media de 10 segundos.
    acumularAmostra();

    // Atualiza o display OLED com os valores atuais ou mensagens de erro.
    atualizarDisplay();
  }

  // ---------------------------------------------------------------------------
  // ENVIO DO PACOTE MEDIO
  // ---------------------------------------------------------------------------
  // A cada INTERVALO_ENVIO_MS, normalmente 10 segundos,
  // o ESP32 calcula a media das amostras acumuladas e envia um pacote binario.
  if (agora - lastPacket >= INTERVALO_ENVIO_MS) {
    lastPacket = agora;

    // Monta o pacote binario com:
    // - tag do equipamento
    // - contador sequencial
    // - medias dos sensores
    // - RSSI medio
    // - latitude/longitude
    // - lux medio
    // - status de erro dos sensores
    // - CRC16 para validacao de integridade
    //
    // Depois envia por BLE notify e por Bluetooth Classico.
    enviarPacoteBinarioMedio();

    // Depois do envio, limpa as somas e contadores
    // para iniciar uma nova janela de 10 segundos.
    zerarAcumuladores();
  }
}
// -----------------------------------------------------------------------------
// VERIFICA SE DISPOSITIVO I2C EXISTE
// -----------------------------------------------------------------------------
/*
bool dispositivoI2CExiste(byte endereco) {
  Wire.beginTransmission(endereco);
  byte erro = Wire.endTransmission();

  return (erro == 0);
}
*/
// -----------------------------------------------------------------------------
// LEITURA DOS SENSORES
// -----------------------------------------------------------------------------

void lerSensores(unsigned long agora) {

  // ---------------------------------------------------------------------------
  // LEITURA DO DHT22
  // ---------------------------------------------------------------------------

  // O DHT22 nao gosta de leitura muito rapida.
  // Se tentar ler a cada 500 ms ele pode retornar NaN ou travar.
  // Por isso fazemos leitura somente a cada 2 segundos.
  //
  // Nas amostras intermediarias continuamos usando o ultimo valor valido.
  if (agora - lastDhtRead >= INTERVALO_DHT_MS) {

    // Atualiza o timestamp da ultima leitura do DHT.
    lastDhtRead = agora;

    // Le temperatura e umidade do DHT22.
    TempAndHumidity data = dhtSensor.getTempAndHumidity();

    // Verifica se veio NaN (Not a Number).
    // Se vier NaN significa erro na leitura.
    tempErro = isnan(data.temperature);
    humErro = isnan(data.humidity);

    // Se leitura da temperatura estiver OK, salva valor.
    if (!tempErro)
      temp = data.temperature;
    else
      temp = -9999; // valor padrao de erro

    // Se leitura da umidade estiver OK, salva valor.
    if (!humErro)
      hum = data.humidity;
    else
      hum = -9999; // valor padrao de erro
  }


  // ---------------------------------------------------------------------------
  // VERIFICACAO DO OLED
  // ---------------------------------------------------------------------------

  // Testa se o display OLED esta respondendo no barramento I2C.
  // Se nao responder, oledErro = true.
  oledErro = !dispositivoI2CExiste(OLED_ADDRESS);


  // ---------------------------------------------------------------------------
  // LEITURA DO INA219
  // ---------------------------------------------------------------------------

  // O INA219 normalmente usa endereco I2C 0x40.
  // Primeiro verificamos se o sensor esta presente.
  bool inaPresente = dispositivoI2CExiste(0x40);

  // Se o sensor nao estiver presente:
  if (!inaPresente) {

    // Marca erro.
    inaErro = true;

    // Coloca valores invalidos padrao.
    busVoltage = -9999;
    current_mA = -9999;
    power_mW = -9999;

  } else {

    // Le tensao do barramento em Volts.
    busVoltage = ina219.getBusVoltage_V();

    // Le corrente em miliampere.
    current_mA = ina219.getCurrent_mA();

    // Le potencia em miliwatts.
    power_mW = ina219.getPower_mW();

    // Verifica se alguma leitura veio invalida.
    inaErro =
      isnan(busVoltage) ||
      isnan(current_mA) ||
      isnan(power_mW);

    // Se tiver erro, coloca valores invalidos padrao.
    if (inaErro) {
      busVoltage = -9999;
      current_mA = -9999;
      power_mW = -9999;
    }
  }


  // ---------------------------------------------------------------------------
  // LEITURA DO BH1750 (LUXIMETRO)
  // ---------------------------------------------------------------------------

  // Alguns modulos BH1750 usam endereco 0x23.
  // Outros usam 0x5C.
  // Testamos os dois.
  bool luxPresente =
    dispositivoI2CExiste(0x23) ||
    dispositivoI2CExiste(0x5C);

  // Se nao encontrou o BH1750:
  if (!luxPresente) {

    // Marca erro.
    bh1750Erro = true;

    // Valor invalido padrao.
    lux = -9999;

  } else {

    // Le nivel de luminosidade em lux.
    lux = lightMeter.readLightLevel();

    // Verifica se veio valor invalido.
    bh1750Erro = isnan(lux) || lux < 0;

    // Se deu erro, coloca valor padrao.
    if (bh1750Erro)
      lux = -9999;
  }


  // ---------------------------------------------------------------------------
  // LEITURA DO GPS
  // ---------------------------------------------------------------------------

  // Verifica se o GPS possui localizacao valida
  // e se os dados nao sao antigos.
  //
  // age() retorna a idade do ultimo dado GPS em milissegundos.
  // Aqui aceitamos no maximo 10 segundos de idade.
  if (
    gps.location.isValid() &&
    gps.location.age() < 10000
  ) {

    // Salva latitude e longitude.
    latitude = gps.location.lat();
    longitude = gps.location.lng();

    // GPS OK.
    gpsErro = false;

  } else {

    // GPS invalido ou sem sinal.
    gpsErro = true;
  }


  // ---------------------------------------------------------------------------
  // LED DE STATUS BLE
  // ---------------------------------------------------------------------------

  // Acende LED BLE se houver conexao BLE ativa.
  digitalWrite(
    LED_BLE,
    bleConnected ? HIGH : LOW
  );


  // ---------------------------------------------------------------------------
  // VERIFICA CONEXAO BLUETOOTH CLASSICO
  // ---------------------------------------------------------------------------

  // hasClient() verifica se existe algum celular conectado
  // no Bluetooth Classico.
  btConnected = SerialBT.hasClient();


  // ---------------------------------------------------------------------------
  // LED DE STATUS BLUETOOTH CLASSICO
  // ---------------------------------------------------------------------------

  // Acende LED BT se houver conexao Bluetooth Classico.
  digitalWrite(
    LED_BT,
    btConnected ? HIGH : LOW
  );
}


// -----------------------------------------------------------------------------
// ATUALIZA ESTADO DO BLUETOOTH CLASSICO
// -----------------------------------------------------------------------------

void atualizarBluetooth() {

  // Atualiza a variavel indicando se existe cliente conectado
  // via Bluetooth Classico.
  btConnected = SerialBT.hasClient();
}


// -----------------------------------------------------------------------------
// SOLICITA RSSI BLE
// -----------------------------------------------------------------------------

void solicitarRssiEsp32() {

  // Se BLE nao estiver conectado
  // ou o endereco remoto nao estiver valido:
  if (!bleConnected || !bleRemoteAddressOk) {

    // Marca RSSI como invalido.
    rssiEsp32Dbm = 127;

    // Marca erro de RSSI.
    rssiEsp32Erro = true;

    return;
  }


  // Evita pedir varias leituras RSSI simultaneamente.
  // So faz nova leitura se nao existir uma leitura pendente.
  if (!rssiReadPending) {

    // Solicita ao BLE interno do ESP32 a leitura do RSSI
    // do celular conectado.
    esp_err_t ret =
      esp_ble_gap_read_rssi(bleRemoteAddress);

    // Se a solicitacao foi aceita:
    if (ret == ESP_OK) {

      // Marca que existe leitura pendente.
      // O valor real do RSSI chegara depois no gapCallback().
      rssiReadPending = true;

    } else {

      // Se falhar:
      // marca RSSI invalido e erro.
      rssiEsp32Dbm = 127;
      rssiEsp32Erro = true;
    }
  }
}

// -----------------------------------------------------------------------------
// ACUMULO E MEDIA
// -----------------------------------------------------------------------------

void acumularAmostra() {
  // Conta todas as tentativas de amostragem dentro da janela.
  // Mesmo que algum sensor esteja com erro, a tentativa é contabilizada.
  totalAmostras++;


  // ---------------------------------------------------------------------------
  // ACUMULO DA TEMPERATURA
  // ---------------------------------------------------------------------------

  // Só acumula temperatura se não houver erro e se o valor for coerente.
  // A condição temp > -100 evita somar valores inválidos como -9999.
  if (!tempErro && temp > -100) {
    somaTemp += temp;
    countTemp++;
  }


  // ---------------------------------------------------------------------------
  // ACUMULO DA UMIDADE
  // ---------------------------------------------------------------------------

  // Umidade não deve ser negativa.
  // Se estiver OK, soma para depois calcular a média.
  if (!humErro && hum >= 0) {
    somaHum += hum;
    countHum++;
  }


  // ---------------------------------------------------------------------------
  // ACUMULO DO INA219
  // ---------------------------------------------------------------------------

  // Se o INA219 estiver OK, acumula tensão, corrente e potência.
  // Usamos o mesmo contador countIna para as três grandezas.
  if (!inaErro) {
    somaVoltage += busVoltage;
    somaCurrent += current_mA;
    somaPower += power_mW;
    countIna++;
  }


  // ---------------------------------------------------------------------------
  // ACUMULO DO LUXIMETRO BH1750
  // ---------------------------------------------------------------------------

  // O valor de lux não deve ser negativo.
  // Se o sensor estiver OK, acumula a luminosidade.
  if (!bh1750Erro && lux >= 0) {
    somaLux += lux;
    countLux++;
  }


  // ---------------------------------------------------------------------------
  // ACUMULO DO GPS
  // ---------------------------------------------------------------------------

  // Se o GPS estiver com posição válida, acumula latitude e longitude.
  // A média ajuda a suavizar pequenas variações do GPS parado.
  if (!gpsErro) {
    somaLat += latitude;
    somaLon += longitude;
    countGps++;
  }


  // ---------------------------------------------------------------------------
  // ACUMULO DO RSSI MEDIDO PELO ESP32
  // ---------------------------------------------------------------------------

  // Só acumula RSSI se a leitura estiver OK.
  // O valor 127 é usado como RSSI inválido.
  if (!rssiEsp32Erro && rssiEsp32Dbm != 127) {
    somaRssi += rssiEsp32Dbm;
    countRssi++;
  }
}


// -----------------------------------------------------------------------------
// ZERAR ACUMULADORES
// -----------------------------------------------------------------------------

void zerarAcumuladores() {
  // Limpa as somas das variáveis analógicas/digitais.
  // Isso inicia uma nova janela de média de 10 segundos.
  somaTemp = 0;
  somaHum = 0;
  somaVoltage = 0;
  somaCurrent = 0;
  somaPower = 0;
  somaLux = 0;
  somaRssi = 0;
  somaLat = 0;
  somaLon = 0;


  // Zera a quantidade total de amostras tentadas.
  totalAmostras = 0;


  // Zera os contadores individuais de amostras válidas.
  // Cada sensor tem seu próprio contador porque pode falhar separadamente.
  countTemp = 0;
  countHum = 0;
  countIna = 0;
  countLux = 0;
  countGps = 0;
  countRssi = 0;
}
// -----------------------------------------------------------------------------
// ENVIO BINARIO COM CRC16
// -----------------------------------------------------------------------------

void enviarPacoteBinarioMedio() {

  // ---------------------------------------------------------------------------
  // CALCULO DAS MEDIAS DA JANELA
  // ---------------------------------------------------------------------------

  // Calcula a media de cada variavel usando somente amostras validas.
  // Se nao houver nenhuma amostra valida, usa valor padrao de erro.
  float mediaTemp    = countTemp > 0 ? somaTemp / countTemp : -9999;
  float mediaHum     = countHum > 0 ? somaHum / countHum : -9999;
  float mediaVoltage = countIna > 0 ? somaVoltage / countIna : -9999;
  float mediaCurrent = countIna > 0 ? somaCurrent / countIna : -9999;
  float mediaPower   = countIna > 0 ? somaPower / countIna : -9999;
  float mediaLux     = countLux > 0 ? somaLux / countLux : -9999;
  float mediaRssi    = countRssi > 0 ? somaRssi / countRssi : 127;

  // GPS usa double para preservar melhor a precisao da latitude/longitude.
  double mediaLat = countGps > 0 ? somaLat / countGps : 0;
  double mediaLon = countGps > 0 ? somaLon / countGps : 0;


  // ---------------------------------------------------------------------------
  // ATUALIZA FLAGS DE ERRO DA JANELA
  // ---------------------------------------------------------------------------

  // Se o contador de amostras validas for zero, considera erro naquele sensor
  // durante esta janela de 10 segundos.
  tempErro = (countTemp == 0);
  humErro = (countHum == 0);
  inaErro = (countIna == 0);
  bh1750Erro = (countLux == 0);
  gpsErro = (countGps == 0);
  rssiEsp32Erro = (countRssi == 0);


  // ---------------------------------------------------------------------------
  // MONTA O PACOTE BINARIO
  // ---------------------------------------------------------------------------

  // Cria a estrutura do pacote.
  SensorPacket pkt;

  // Limpa todos os bytes antes de preencher.
  // Isso evita lixo de memoria dentro do pacote.
  memset(&pkt, 0, sizeof(pkt));


  // ---------------------------------------------------------------------------
  // CABECALHO E IDENTIFICACAO
  // ---------------------------------------------------------------------------

  // Bytes fixos usados para o app reconhecer o inicio do pacote.
  pkt.header1 = 0xAA;
  pkt.header2 = 0x55;

  // Versao do protocolo. Se no futuro mudar o formato do pacote,
  // essa versao pode ser incrementada.
  pkt.version = 1;

  // Copia a TAG do equipamento para o pacote.
  // Exemplo: EQP001.
  memcpy(pkt.tag, EQUIPMENT_TAG, 6);

  // Numero sequencial do pacote.
  // Ajuda o app a detectar perda de pacotes.
  pkt.seq = packetSeq++;

  // Janela usada para calculo da media, em segundos.
  pkt.window_s = INTERVALO_ENVIO_MS / 1000;

  // Quantidade total de tentativas de amostragem feitas na janela.
  pkt.samples = totalAmostras;


  // ---------------------------------------------------------------------------
  // CONVERSAO DOS VALORES PARA FORMATO BINARIO COMPACTO
  // ---------------------------------------------------------------------------

  // Temperatura multiplicada por 10.
  // Exemplo: 25.4 C vira 254.
  pkt.temp_x10 = scaleInt16(mediaTemp, 10.0, INT16_MIN);

  // Umidade multiplicada por 10.
  // Exemplo: 61.7 % vira 617.
  pkt.hum_x10 = scaleUInt16(mediaHum, 10.0, 0xFFFF);

  // Tensao em Volts convertida para milivolts.
  // Exemplo: 5.01 V vira 5010 mV.
  pkt.voltage_mV = scaleUInt16(mediaVoltage, 1000.0, 0xFFFF);

  // Corrente em mA multiplicada por 10.
  // Exemplo: 83.2 mA vira 832.
  pkt.current_x10_mA = scaleInt16(mediaCurrent, 10.0, INT16_MIN);

  // Potencia em mW multiplicada por 10.
  // Exemplo: 417.3 mW vira 4173.
  pkt.power_x10_mW = scaleUInt16(mediaPower, 10.0, 0xFFFF);

  // RSSI em dBm multiplicado por 10.
  // Exemplo: -64.8 dBm vira -648.
  pkt.rssi_x10_dbm = scaleInt16(mediaRssi, 10.0, INT16_MIN);

  // Latitude e longitude multiplicadas por 10.000.000.
  // Exemplo: -25.4295967 vira -254295967.
  // Se o GPS estiver com erro, envia INT32_MIN como valor invalido.
  pkt.lat_x1e7 = gpsErro ? INT32_MIN : (int32_t)round(mediaLat * 10000000.0);
  pkt.lon_x1e7 = gpsErro ? INT32_MIN : (int32_t)round(mediaLon * 10000000.0);

  // Luminosidade em lux multiplicada por 100.
  // Exemplo: 123.45 lux vira 12345.
  pkt.lux_x100 = scaleUInt32(mediaLux, 100.0, 0xFFFFFFFFUL);

  // Monta o campo de status com os bits de erro dos sensores.
  pkt.status = montarStatusSensores();


  // ---------------------------------------------------------------------------
  // CALCULO DO CRC16
  // ---------------------------------------------------------------------------

  // Calcula o CRC16 Modbus do pacote.
  // O CRC e calculado sobre todo o pacote, exceto os 2 bytes finais do proprio CRC.
  // O app Android deve recalcular e comparar para validar a integridade.
  pkt.crc16 = crc16Modbus(
    (uint8_t*)&pkt,
    sizeof(SensorPacket) - sizeof(pkt.crc16)
  );


  // ---------------------------------------------------------------------------
  // DEBUG NO MONITOR SERIAL
  // ---------------------------------------------------------------------------

  // Mostra dados tecnicos do pacote enviado.
  Serial.print("Enviando pacote binario, seq=");
  Serial.print(pkt.seq);
  Serial.print(", bytes=");
  Serial.print(sizeof(pkt));
  Serial.print(", crc=0x");
  Serial.println(pkt.crc16, HEX);


  // Mostra valores em formato legivel para facilitar testes.
  // Isso e apenas debug no Monitor Serial.
  // Estes textos NAO sao enviados para o celular.
  Serial.print("TAG=");
  Serial.write((uint8_t*)pkt.tag, 6);

  Serial.print(" T=");
  Serial.print(mediaTemp, 1);

  Serial.print(" H=");
  Serial.print(mediaHum, 1);

  Serial.print(" V=");
  Serial.print(mediaVoltage, 2);

  Serial.print(" I=");
  Serial.print(mediaCurrent, 1);

  Serial.print(" P=");
  Serial.print(mediaPower, 1);

  Serial.print(" RSSI=");
  Serial.print(mediaRssi, 1);

  Serial.print(" Lux=");
  Serial.print(mediaLux, 1);

  Serial.print(" Lat=");
  Serial.print(mediaLat, 6);

  Serial.print(" Lon=");
  Serial.println(mediaLon, 6);


  // ---------------------------------------------------------------------------
  // ENVIO PELO BLUETOOTH CLASSICO
  // ---------------------------------------------------------------------------

  // Se houver celular conectado no Bluetooth Classico,
  // envia o pacote binario bruto.
  //
  // Importante:
  // Aqui nao usamos println(), porque o pacote nao e texto.
  if (btConnected) {
    SerialBT.write((uint8_t*)&pkt, sizeof(pkt));
  }


  // ---------------------------------------------------------------------------
  // ENVIO PELO BLE
  // ---------------------------------------------------------------------------

  // Se houver celular conectado via BLE,
  // grava os bytes na characteristic e envia notify.
  if (bleConnected) {
    pCharacteristic->setValue((uint8_t*)&pkt, sizeof(pkt));
    pCharacteristic->notify();
  }
}
// -----------------------------------------------------------------------------
// DISPLAY OLED
// -----------------------------------------------------------------------------

void atualizarDisplay() {

  // ---------------------------------------------------------------------------
  // VERIFICA SE O OLED ESTA FUNCIONANDO
  // ---------------------------------------------------------------------------

  // Se o display OLED nao estiver respondendo no I2C,
  // sai da funcao para evitar travamento.
  if (oledErro) return;


  // ---------------------------------------------------------------------------
  // LIMPA O DISPLAY E CONFIGURA TEXTO
  // ---------------------------------------------------------------------------

  // Limpa toda a tela antes de desenhar novamente.
  display.clearDisplay();

  // Define cor branca para os textos/desenhos.
  display.setTextColor(SSD1306_WHITE);

  // Define tamanho pequeno da fonte.
  display.setTextSize(1);


  // ---------------------------------------------------------------------------
  // LINHA SUPERIOR - STATUS DAS CONEXOES
  // ---------------------------------------------------------------------------

  // Posiciona cursor no canto superior esquerdo.
  display.setCursor(0, 0);

  // Mostra TAG do equipamento.
  // Exemplo: EQP001
  display.print(EQUIPMENT_TAG);

  // Mostra status da conexao BLE.
  display.print(" BLE:");
  display.print(bleConnected ? "ON" : "OFF");

  // Mostra status do Bluetooth Classico.
  display.print(" BT:");
  display.print(btConnected ? "ON" : "OFF");


  // ---------------------------------------------------------------------------
  // INDICADOR DE VIDA (BLINK)
  // ---------------------------------------------------------------------------

  // Pisca uma bolinha no canto superior direito.
  // Serve apenas para mostrar que o programa esta executando normalmente.
  if (blinkState)

    // Bolinha preenchida.
    display.fillCircle(124, 5, 3, SSD1306_WHITE);

  else

    // Bolinha vazada.
    display.drawCircle(124, 5, 3, SSD1306_WHITE);


  // ---------------------------------------------------------------------------
  // LINHA DIVISORIA
  // ---------------------------------------------------------------------------

  // Desenha uma linha horizontal separando cabecalho e dados.
  display.drawLine(0, 11, 127, 11, SSD1306_WHITE);


  // ---------------------------------------------------------------------------
  // TEMPERATURA E UMIDADE
  // ---------------------------------------------------------------------------

  // Posiciona cursor na linha da temperatura/umidade.
  display.setCursor(0, 16);

  display.print("T:");

  // Se temperatura estiver com erro, mostra "---".
  if (tempErro)
    display.print("---");
  else
    display.print(temp, 1);

  display.print("C H:");

  // Se umidade estiver com erro, mostra "---".
  if (humErro)
    display.print("---");
  else
    display.print(hum, 0);

  display.print("%");


  // ---------------------------------------------------------------------------
  // CORRENTE E POTENCIA
  // ---------------------------------------------------------------------------

  // Linha dos dados do INA219.
  display.setCursor(0, 28);

  display.print("I:");

  // Corrente em mA.
  if (inaErro)
    display.print("---");
  else
    display.print(current_mA, 1);

  display.print("mA P:");

  // Potencia em mW.
  if (inaErro)
    display.print("---");
  else
    display.print(power_mW, 0);


  // ---------------------------------------------------------------------------
  // LUX E RSSI
  // ---------------------------------------------------------------------------

  // Linha do luximetro e RSSI.
  display.setCursor(0, 40);

  display.print("Lux:");

  // Mostra luminosidade em lux.
  if (bh1750Erro)
    display.print("---");
  else
    display.print(lux, 0);

  display.print(" R:");

  // Mostra RSSI medido pelo ESP32 em dBm.
  if (rssiEsp32Erro)
    display.print("---");
  else
    display.print(rssiEsp32Dbm);

  // ---------------------------------------------------------------------------
  // STATUS DO GPS
  // ---------------------------------------------------------------------------

  // Linha do GPS.
  display.setCursor(0, 52);

  display.print("GPS:");

  // Mostra se o GPS possui FIX valido.
  //
  // SEM FIX:
  // sem satelites suficientes ou sem coordenadas validas.
  //
  // OK:
  // latitude/longitude validas.
  display.print(gpsErro ? "SEM FIX" : "OK");
  // ---------------------------------------------------------------------------
  // ATUALIZA O DISPLAY
  // ---------------------------------------------------------------------------

  // Envia tudo que foi desenhado para o OLED.
  // Sem isso nada aparece na tela.
  display.display();
}

