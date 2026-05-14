# Boson Core

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red.svg)](https://maven.apache.org/)

Boson Core is the foundational library for the [Boson Network](https://github.com/bosonnetwork) — a decentralized, encrypted peer-to-peer communication framework. It provides the common APIs, a secure Kademlia DHT implementation, and an interactive developer shell.

---

## Table of Contents

- [About Boson](#about-boson)
- [About Boson Core](#about-boson-core)
  - [Common APIs (`api`)](#common-apis-api)
  - [Secure Kademlia DHT (`dht`)](#secure-kademlia-dht-dht)
  - [DHT Shell (`shell`)](#dht-shell-shell)
- [Prerequisites](#prerequisites)
- [Build Instructions](#build-instructions)
- [Running the DHT Shell](#running-the-dht-shell)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

---

## About Boson

Boson is a decentralized and encrypted peer-to-peer communication framework designed for secure, censorship-resistant networking. It uses a two-layer architecture:

- **Bottom layer** — A unified DHT (Distributed Hash Table) network that handles node discovery, peer routing, and distributed storage.
- **Top layer** — Application-oriented services (messaging, active proxy, etc.) built on top of the DHT network.

Every node in the Boson network has a cryptographic identity derived from an Ed25519 key pair. Node IDs, peer announcements, and stored values are all signed and verified, making the network resistant to Sybil attacks and data tampering.

---

## About Boson Core

This repository is organized into three Maven modules:

### Common APIs (`api`)

The `api` module defines the contracts and shared utilities used by the DHT implementation and all higher-level Boson services.

- Core interfaces
- Crypto primitives
- Decentralized identifier system
  Boson implements a self-sovereign identity layer that is fully compatible with the [W3C DID Core](https://www.w3.org/TR/did-core/) and [W3C Verifiable Credentials](https://www.w3.org/TR/vc-data-model/) specifications. Every Boson node or user has an identity anchored to an Ed25519 key pair, and that identity can be discovered, resolved, and verified entirely through the DHT — with no central registry. 
  The dual-format design means every object can be serialized as a full W3C JSON-LD document (for interoperability with standards-compliant verifiers) or as the Boson compact format (for efficiency inside the P2P network).
- Service framework
  Defines the plugin API for Boson services, and federation support.

---

### Secure Kademlia DHT (`dht`)

The `dht` module implements a hardened Kademlia DHT node. It extends standard Kademlia with several security features:

- **Cryptographic node identity** — every node is identified by its Ed25519 public key; all RPC messages are authenticated.
- **Signed values** — mutable values stored in the DHT carry an Ed25519 signature that any node can verify.
- **Security blacklist** — misbehaving nodes are tracked and banned automatically.
- **Dual-stack networking** — simultaneous IPv4 and IPv6 support on a single port.
- **Dual serialization** — RPC messages support both JSON (text) and CBOR (binary) formats.

**RPC protocol**

| Method | Description |
|---|---|
| `PING` | Verify node liveness |
| `FIND_NODE` | Iterative lookup for the closest nodes to a target ID |
| `FIND_PEER` | Look up peer announcements by service ID |
| `ANNOUNCE_PEER` | Publish a peer record to the network |
| `FIND_VALUE` | Look up a signed or unsigned value by key |
| `STORE_VALUE` | Publish a value to the network |

See [`dht/docs/protocol.md`](dht/docs/protocol.md) for the full protocol specification.

---

### DHT Shell (`shell`)

The `shell` module provides an interactive command-line shell that starts a local DHT node and lets developers interact with the network in real time. It is intended as a development and debugging tool.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 17 or later |
| Apache Maven | 3.8 or later |
| libsodium | 1.0.16 or later |

**Installing libsodium**

- **macOS**: `brew install libsodium`
- **Ubuntu / Debian**: `sudo apt-get install libsodium-dev`
- **Fedora / RHEL**: `sudo dnf install libsodium-devel`
- **Windows**: Download from the [libsodium releases page](https://download.libsodium.org/libsodium/releases/) and add the DLL directory to `PATH`.

---

## Build Instructions

### 1. Clone the repository

```bash
git clone https://github.com/bosonnetwork/Boson.Core.git
cd Boson.Core
```

### 2. Install the parent POM

The parent module must be installed into the local Maven repository before building the submodules.

```bash
# From the parent repository root (Boson.Java)
mvn install -f parent/pom.xml
```

### 3. Build all modules

```bash
./mvnw clean package
```

To skip tests:

```bash
./mvnw clean package -DskipTests
```

---

## Running the DHT Shell

The DHT shell is bundled as an executable JAR. Also create an easy-to-use shell script:

```bash
cd shell/target/dist
./bin/dht-shell [OPTIONS]
```

**Example — start a local shell node and bootstrap into the network:**

```bash
./bin/dht-shell \
  -4 192.168.8.1 \
  -p 39001 \
  -d ./data \
  --developerMode \
  -b "HZXXs9LTfNQjrDKvvexRhuMk8TTJhYCfrHwaj3jUzuhZ:155.138.245.211:39001"
```

Once started, type `help` at the `Boson $` prompt to list all available commands.

---

## Configuration

The node can be configured via a YAML file (loaded with `-c <file>`). Below is an annotated example:

```yaml
ipv4: true
ipv6: false
address4: "203.0.113.42"   # Must be a specific public unicast IP address
port: 39001

dataDir: "/var/lib/boson"

bootstraps:
  - id: "HZXXs9LTfNQjrDKvvexRhuMk8TTJhYCfrHwaj3jUzuhZ"
    address: "155.138.245.211"
    port: 39001
  - id: "6o6LkHgLyD5sYyW9iN5LNRYnUoX29jiYauQ5cDjhCpWQ"
    address: "45.32.138.246"
    port: 39001
```

> **Important:** `address4` and `address6` must be specific **public unicast** IP addresses. Wildcard addresses (`0.0.0.0`, `::`) and loopback addresses (`127.0.0.1`, `::1`) are not valid — the DHT embeds this address in node announcements so that other peers can reach you. If you omit the field entirely, the node will auto-detect its public IP via the default network route.

---

## Contributing

We welcome contributions from the open-source community. To get started:

1. Fork this repository and create a feature branch.
2. Make your changes and add tests where applicable.
3. Ensure `./mvnw clean verify` passes.
4. Open a pull request with a clear description of the change.

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

---

## License

This project is licensed under the [MIT License](LICENSE).

---

## Acknowledgments

Boson Core builds on the shoulders of great open-source projects, including [libsodium](https://libsodium.org/), [Eclipse Vert.x](https://vertx.io/), [JLine](https://github.com/jline/jline3), [picocli](https://picocli.info/), and many others. We are grateful to the open-source community for making these tools available.