# 🎵 Music Player - Android App

## Funcionalidades
- ✅ Seleção múltipla de músicas do armazenamento
- ✅ Controles: Play/Pause, Próxima, Anterior
- ✅ Barra de progresso com tempo
- ✅ Controle de volume do sistema
- ✅ **Sound Boost** — amplifica o som além do limite do celular (LoudnessEnhancer, até +10 dB)
- ✅ **Visualizador animado** — barras de equalizer + onda senoidal + partículas + pulse ring
- ✅ Animação com gradiente de cores dinâmico
- ✅ Serviço em background (continua tocando com app minimizado)

## Como abrir no Android Studio

1. Extraia o ZIP do projeto
2. Abra o **Android Studio** (Hedgehog ou superior)
3. **File → Open** → selecione a pasta `MusicPlayer`
4. Aguarde o Gradle sincronizar (~2 min na primeira vez)
5. Conecte um celular Android ou use o emulador
6. Clique em **Run ▶** (Shift+F10)

## Requisitos
- Android Studio **Hedgehog 2023.1+**
- JDK 17 (incluso no Android Studio)
- Dispositivo Android **6.0+** (API 23)
- Para o Sound Boost funcionar bem, use dispositivo físico

## Permissões solicitadas
| Permissão | Para quê |
|-----------|----------|
| `READ_MEDIA_AUDIO` (Android 13+) | Acessar músicas |
| `READ_EXTERNAL_STORAGE` (Android 12-) | Acessar músicas |
| `RECORD_AUDIO` | API Visualizer (análise de áudio) |
| `MODIFY_AUDIO_SETTINGS` | Boost de volume |

## Estrutura do projeto
```
app/src/main/
├── java/com/musicplayer/
│   ├── MainActivity.kt      — Tela principal, lógica de player
│   ├── WaveformView.kt      — View customizada do visualizador
│   └── MusicService.kt      — Serviço foreground para background
├── res/
│   ├── layout/activity_main.xml
│   ├── drawable/            — Ícones e fundos
│   └── values/              — Strings e temas
└── AndroidManifest.xml
```

## Sobre o Sound Boost
Usa a API **LoudnessEnhancer** nativa do Android, que aplica
ganho de amplificação no sinal de áudio após o limite do sistema.
Não é recomendado usar acima de +5 dB por longos períodos para
preservar os alto-falantes do dispositivo.
