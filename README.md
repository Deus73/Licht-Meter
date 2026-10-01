<p align="center">
  <img src="docs/images/app-icon.webp" width="240" alt="Licht-Meter appicoon">
</p>

<h1 align="center">Licht-Meter</h1>

<h3 align="center">Meet. Kies. Groei.</h3>

<p align="center">
  Verander je Android-telefoon in een slimme lichtmeter voor planten.<br>
  Meet <strong>lux</strong>, <strong>foot-candles</strong>, <strong>lumen</strong> en geschatte <strong>PPFD</strong> met de camera.
</p>

<p align="center">
  <a href="https://github.com/Deus73/Licht-Meter/releases/download/v1.4.0/Licht-Meter-v1.4.0.apk">
    <img src="https://img.shields.io/badge/DOWNLOAD-ANDROID%20APK-D8FF47?style=for-the-badge&logo=android&logoColor=06130D&labelColor=075F3B" alt="Download Licht-Meter v1.4.0">
  </a>
</p>

<p align="center">
  <strong>Versie 1.4.0</strong> · Android 8.0+ · Geen account · Geen advertenties · Meten werkt offline
</p>

## Waarom Licht-Meter?

| Slim meten | Lamp selecteren | Direct groeiadvies |
|---|---|---|
| Lux, fc, lumen en PPFD in één scherm | LED, blurple, zon, HPS en TL/CFL | Stekken, groei, bloei of te sterk |

- Front- en achtercamera met een eigen kalibratieprofiel.
- Handmatige lampkeuze met duidelijke logoknoppen en passende PPFD-factor.
- Lampvisualisatie waarvan de gloed met de gemeten lichtsterkte meeverandert.
- Gestabiliseerd drie-secondenvenster met minimum, gemiddelde, maximum en spreiding.
- Geprojecteerde DLI op basis van een instelbare dagelijkse lichtduur.
- Waarschuwingen voor onrustige, te felle en te donkere metingen.
- Hoogcontrastscherm voor gebruik bij fel licht.
- Metingen opslaan per lamp of locatie met datum en tijd.
- Uitgebreide lichtgids met PPFD-, lux- en PPF-richtwaarden voor LED, HPS en TL/CFL.
- Wekelijkse updatecontrole met automatische APK-download.
- Nederlands, Engels, Frans en Duits.

## Bekijk de app

<p align="center">
  <img src="docs/images/main-screen.png" width="265" alt="Licht-Meter hoofdscherm">
  <img src="docs/images/settings-popup.png" width="265" alt="Licht-Meter instellingen">
  <img src="docs/images/help-guide.png" width="265" alt="Help en uitgebreide lichtgids">
</p>

## Lichtadvies in één oogopslag

| Groeifase | Aanbevolen PPFD |
|---|---:|
| Stekken en zaailingen | 100-300 µmol/m²/s |
| Vegetatieve groei | 300-600 µmol/m²/s |
| Bloei | 600-1.000 µmol/m²/s |
| Intensieve bloei met CO₂ | 1.000-1.500 µmol/m²/s |

## Download

**[Download Licht-Meter v1.4.0 voor Android](https://github.com/Deus73/Licht-Meter/releases/download/v1.4.0/Licht-Meter-v1.4.0.apk)**

Android kan bij handmatige APK-installatie vragen om installatie uit deze bron tijdelijk toe
te staan. De camera wordt gebruikt voor metingen; internet en meldingen worden alleen voor
de wekelijkse updatecontrole en APK-download gebruikt. Android vraagt altijd om bevestiging
voordat een update wordt geïnstalleerd.

<details>
<summary><strong>Hoe meet ik?</strong></summary>

1. Kies onder de lampillustratie het logo van jouw lichtbron.
2. Open het draaiende tandwiel om camera, meetprofiel en kalibratie in te stellen.
3. Druk op `Camera richten` en richt de cirkel vanaf bladhoogte naar de lamp.
4. Wacht tot de status `Stabiel` toont en controleer minimum, gemiddelde en maximum.
5. Lees PPFD, geprojecteerde DLI en het automatische groeifaseadvies af.
6. Geef de lamp een naam en sla de meting op.

</details>

<details>
<summary><strong>Nauwkeurigheid en kalibratie</strong></summary>

Een telefooncamera is geen gekalibreerde lux- of PAR-meter. Camera, witbalans en
lampspectrum verschillen per toestel. De resultaten zijn daarom indicatieve schattingen.

Kalibreer voor betere herhaalbaarheid tegen een betrouwbare meter met:

`kalibratiefactor = referentiewaarde / getoonde luxwaarde`

Gebruik voor professionele teeltbeslissingen een gekalibreerde quantum- of PAR-meter.

</details>

<details>
<summary><strong>Privacy</strong></summary>

- Camerabeelden worden alleen lokaal verwerkt.
- Beelden en metingen worden niet geüpload.
- De historiek staat in een lokale SQLite-database.
- Geen account, tracking of advertenties.
- Alleen de wekelijkse updatecontrole maakt verbinding met de publieke GitHub Releases-API.

</details>

<details>
<summary><strong>Voor ontwikkelaars</strong></summary>

Native Android-app in Java 17 met Camera2, WorkManager en SQLite. Vereist JDK 17 en Android
SDK 34.

```bash
./gradlew clean test assembleDebug lintDebug
```

De GitHub Actions-workflow voert tests, APK-build en lint automatisch uit.

</details>

---

<p align="center">
  <strong>Licht-Meter v1.4.0</strong><br>
  Slimmer licht meten voor sterkere planten.
</p>
