# LecteurOPUS Fork

Android app to read Montreal-area transit cards — **OPUS** (MIFARE Desfire / Calypso) and **temp tickets** (MIFARE Ultralight).

Fork of [etiennedub/LecteurOPUS](https://github.com/etiennedub/LecteurOPUS) with extended capabilities.

## Features

- **Deep scan** — probes ~400 file IDs on OPUS cards, decodes Spirtech contract fields, auth signatures, multi-contract display
- **Debug output** — raw card data dump with page-by-page hex views, XML export, JSON export
- **History** — persistent local database of all scanned cards, with rename, duplicate, compare, and delete
- **Fare summary** — see all contracts at a glance with trip counts, expiry status, and operator names
- **Bus line enrichment** — GTFS-based names for metro lines, REM, and bus routes
- **Contribution system** — submit unknown product codes and bus line corrections to a community server
- **Export / Import** — batch export all cards as JSON, reimport with full fidelity

## Installation

Download the latest APK from **[Releases](https://github.com/jerji/LecteurOPUS-Fork/releases)**.

For automatic updates, add the repo to [Obtainium](https://obtainium.imranr.dev/):

[<img src="https://img.shields.io/badge/Obtainium-Get%20app%20updates-blue?style=for-the-badge&logo=obtainium" height="40">](obtainium://add/https://github.com/jerji/LecteurOPUS-Fork)

## Usage

Tap a compatible card to the back of your device and the card info appears.

If a bus line or product code is unknown, tap the ✏ or + button next to it to submit a correction.

## Supported networks

- STM (Société de transport de Montréal)
- RTC (Réseau de transport de la Capitale)
- RTL (Réseau de transport de Longueuil)
- REM (Réseau express métropolitain)
- Others via community contributions

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17. CI builds are signed with a debug keystore; release builds use secrets.