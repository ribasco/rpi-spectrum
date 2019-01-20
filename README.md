# Spectrum Analyzer Demo Project

This is a simple Spectrum Analyzer project that will playback mp3 and draw the spectrum on a Graphics LCD display using Java. This also showcase the capabilites of the [GLCD Simulator](https://github.com/ribasco/glcd-emulator) and [UCGDisplay](https://github.com/ribasco/ucgdisplay) libraries. Audio samples are processed and analyzed with FFT algorithm using [minim](https://github.com/ddf/Minim) library.

## YouTube Demo

[![YouTube Demo](https://img.youtube.com/vi/9dm9MVK1kKI/0.jpg?)](https://www.youtube.com/watch?v=9dm9MVK1kKI)

## Installation

1. Clone

```bash
git clone https://github.com/ribasco/rpi-spectrum.git
```

2. Compile

```bash
mvn clean compile
```

## Usage

### Simulation Mode

#### Prerequisites

- Maven 3.x
- GLCD Simulator (Download from https://github.com/ribasco/glcd-emulator/releases)

#### Command Line Syntax

> Pass `-simulation` argument to activate simulation mode. 
> Pass `-ip` argument to specify the host ip

```bash
mvn exec:java -Dexec.args="-simulation -ip <host ip address>"
```
### GLCD Mode

> This project use the `ST7920` display controller in Hardware SPI mode setting. If you have a different display or you need to change the bus interface settings etc, you can easily change this by modifying the code section `createDriver(GlcdConfig config)` under `SpectrumAnalyzer.java` class.

#### Prerequisites

- Maven 3.x
- Graphics LCD (e.g. ST7920)
- Single Board Computer device with Linux kernel 4.8x above (e.g. Raspberry Pi 3)

#### Command Line Syntax

```bash
mvn exec:java
```
