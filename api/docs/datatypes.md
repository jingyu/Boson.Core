# Boson DHT Datatypes

This document describes the core datatypes used in the Boson DHT network, including their purpose and serialization formats for JSON and CBOR.

---

## Id

The `Id` is a 256-bit identifier used to uniquely identify nodes, values, peers, and other objects in the Boson network. It is based on Ed25519 public keys and serves as the address space for the DHT XOR metric.

### Serialization Format

| Format | Representation | Description |
| :--- | :--- | :--- |
| **JSON** | `String` | Base58 encoded string (default) or W3C DID format (`did:boson:<base58>`). |
| **CBOR** | `Byte String` | 32 bytes of raw binary data. |

---

## NodeInfo

`NodeInfo` contains basic network information about a DHT node, allowing other nodes to establish communication. It consists of a node's `Id`, IP address, and port number.

### Serialization Format

`NodeInfo` is serialized as a **fixed-order array** of three elements: `[Id, Address, Port]`.

| Index | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| 0 | `id` | `Id` | The node's 256-bit identifier. |
| 1 | `host` | `String` / `Binary` | The IP address or hostname. |
| 2 | `port` | `Integer` | The UDP port number. |

#### Format Details

| Format | id | host | port |
| :--- | :--- | :--- | :--- |
| **JSON** | Base58 String | IP Address String | Number |
| **CBOR** | 32-byte Binary | Raw IP Byte Array (4 or 16 bytes) | Number |

---

## PeerInfo

`PeerInfo` describes a service published over the Boson DHT. It can be **Authenticated** (includes node signature) or **Regular**. A `PeerInfo` record is uniquely identified by the combination of its Peer ID (`id`) and Fingerprint (`f`).

### Serialization Format

`PeerInfo` is serialized as a JSON **object** with the following fields:

| Field | Key | Type | Description |
| :--- | :--- | :--- | :--- |
| Peer ID | `id` | `Id` | Public key of the service peer. |
| Nonce | `n` | `Binary` | 24-byte nonce for signing. |
| Sequence | `seq` | `Integer` | Incremental version number. |
| Origin Node | `o` | `Id` | (Optional) Node ID providing the peer. |
| Node Sig | `os` | `Binary` | (Optional) Signature from the origin node. |
| Signature | `sig` | `Binary` | Signature of the peer owner. |
| Fingerprint | `f` | `Long` | Unique number for disambiguating peers. |
| Endpoint | `e` | `String` | Service URI (e.g., `http://...`). |
| Extra Data | `ex` | `Binary` | (Optional) Additional service-specific data. |

#### Format Details

| Format | Binary Fields (`n`, `sig`, `os`, `ex`) | Id Fields (`id`, `o`) |
| :--- | :--- | :--- |
| **JSON** | Base64 (URL-safe, no padding) | Base58 String |
| **CBOR** | Raw Byte String | 32-byte Binary |

---

## Value

`Value` represents data stored in the DHT. It supports three types: **Immutable** (content-hashed), **Mutable** (signed), and **Encrypted** (signed & encrypted for a recipient).

### Serialization Format

`Value` is serialized as a JSON **object** with the following fields:

| Field | Key | Type | Description |
| :--- | :--- | :--- | :--- |
| Public Key | `k` | `Id` | (Mutable/Encrypted) Owner's public key. |
| Recipient | `rec` | `Id` | (Optional) Recipient's public key for encrypted values. |
| Nonce | `n` | `Binary` | 24-byte nonce for signing. |
| Sequence | `seq` | `Integer` | (Mutable/Encrypted) Incremental version number. |
| Signature | `sig` | `Binary` | (Mutable/Encrypted) Signature of the data. |
| Data | `v` | `Binary` | The actual value data (encrypted if `rec` is present). |

#### Format Details

| Format | Binary Fields (`n`, `sig`, `v`) | Id Fields (`k`, `rec`) |
| :--- | :--- | :--- |
| **JSON** | Base64 (URL-safe, no padding) | Base58 String |
| **CBOR** | Raw Byte String | 32-byte Binary |

> [!NOTE]
> Immutable values only contain the `v` (data) field and are identified by the SHA-256 hash of that data.
