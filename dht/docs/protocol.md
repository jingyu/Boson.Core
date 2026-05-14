# Boson Kademlia Protocol

This document describes the RPC protocol used in the Boson Kademlia DHT network. The protocol is designed for security, efficiency, and interoperability.

---

## Transport

Messages are exchanged over **UDP**. The default port is `39001`. Each node can participate on both IPv4 and IPv6 simultaneously.

The wire serialization is **CBOR** (binary). A JSON encoding of the same schema is also supported for debugging and testing. All field names are identical between the two formats; data types differ where noted below.

Minimum valid message size: **10 bytes**.

---

## Message Envelope

Every message is a top-level map/object with the following fields.

| Key | Name | JSON Type | CBOR Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`y`** | Type & Method | `Number` | `Integer` | Yes | Composite field encoding message type and RPC method (see below). |
| **`t`** | Transaction ID | `Number` | `Integer` | Yes | Non-zero unsigned integer used to match requests with responses. |
| **`q`** | Request Body | `Object` | `Map` | Conditional | Present only in **Request** messages. |
| **`r`** | Response Body | `Object` | `Map` | Conditional | Present only in **Response** messages. |
| **`e`** | Error Body | `Object` | `Map` | Conditional | Present only in **Error** messages. |
| **`v`** | Version | `Number` | `Integer` | No | Node software version. Omitted when zero. |

> **Note:** The `y` field **must** appear before `q`, `r`, or `e` in the encoded stream. The deserializer uses `y` to select the body class before reading the body field.

The sender's node ID is **not** included in the wire message. It is resolved from the network context (source IP/port matched to the routing table) on the receiving side.

### Message Type & Method Encoding (`y`)

The `y` field packs message type and RPC method into a single integer using a bitmask.

| Bits | Mask | Field | Values |
| :--- | :--- | :--- | :--- |
| 4–0 | `0x1F` | **Method** | `PING(1)`, `FIND_NODE(2)`, `ANNOUNCE_PEER(3)`, `FIND_PEER(4)`, `STORE_VALUE(5)`, `FIND_VALUE(6)` |
| 7–5 | `0xE0` | **Type** | `ERROR(0x00)`, `REQUEST(0x20)`, `RESPONSE(0x40)` |

**Example computation:**
- `FIND_NODE` request: `0x02 | 0x20 = 0x22` (34)
- `FIND_NODE` response: `0x02 | 0x40 = 0x42` (66)
- `FIND_NODE` error: `0x02 | 0x00 = 0x02` (2)

---

## Data Representations

### Binary fields
- **JSON**: URL-safe Base64, no padding.
- **CBOR**: Raw byte strings.

### Identifiers (`Id`)
A 256-bit value derived from an Ed25519 public key.
- **JSON**: Base58-encoded string.
- **CBOR**: 32-byte raw binary.

### NodeInfo
Encoded as a compact **3-element array** `[id, host, port]`, not a map.

| Index | Field | JSON | CBOR |
| :--- | :--- | :--- | :--- |
| 0 | Node ID | Base58 string | 32-byte binary |
| 1 | Host | IP address string (IPv4 or IPv6) or hostname | Raw binary IP address |
| 2 | Port | Number | Number |

Example (JSON): `["HZXXs9LTfNQjrDKvvexRhuMk8TTJhYCfrHwaj3jUzuhZ", "155.138.245.211", 39001]`

### PeerInfo
Encoded as a **map/object**. The peer ID (`id`) may be omitted by the serializer when it is already known from context (e.g., inside an `ANNOUNCE_PEER` request where `k` carries it).

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `id` | Peer ID | `Id` | Conditional | Public key of the service peer. Omitted when the receiver already knows it. |
| `n` | Nonce | `Binary` | Yes | 24-byte random nonce. |
| `seq` | Sequence | `Number` | No | Version number. Omitted when zero. |
| `o` | Node ID | `Id` | No | ID of the DHT node hosting the peer (authenticated peers only). |
| `os` | Node Signature | `Binary` | No | Node's Ed25519 signature over the peer record. Required if `o` is present. |
| `sig` | Peer Signature | `Binary` | Yes | Owner's Ed25519 signature over the peer record. |
| `f` | Fingerprint | `Number` | No | Unique `long` fingerprint for this peer instance. Omitted when zero. |
| `e` | Endpoint | `String` | Yes | Service endpoint URI (e.g., `https://...`). |
| `ex` | Extra Data | `Binary` | No | Opaque extension bytes. |

### Value
Values can be **immutable**, **mutable**, or **encrypted** (mutable + recipient).

| Type | Required fields | Description |
| :--- | :--- | :--- |
| Immutable | `v` only | ID = SHA-256(`v`). Content is fixed. |
| Mutable | `k`, `n`, `seq`, `sig`, `v` | ID = `k`. Owner updates by incrementing `seq`. |
| Encrypted | `k`, `rec`, `n`, `seq`, `sig`, `v` | Mutable value whose payload is encrypted for the recipient `rec`. |

Value fields as they appear on the wire:

| Key | Name | Type | Description |
| :--- | :--- | :--- | :--- |
| `k` | Public Key | `Id` | Owner's public key (mutable/encrypted only). |
| `rec` | Recipient | `Id` | Recipient's public key (encrypted only). |
| `n` | Nonce | `Binary` | 24-byte nonce (mutable/encrypted only). |
| `seq` | Sequence | `Number` | Version number (mutable/encrypted only). |
| `sig` | Signature | `Binary` | Owner's Ed25519 signature (mutable/encrypted only). |
| `v` | Data | `Binary` | The value payload (all types). |

---

## Write Tokens

STORE_VALUE and ANNOUNCE_PEER require a valid **write token** obtained from a prior lookup. Tokens are short-lived and opaque integers generated by the receiving node.

Tokens can be acquired from:
- A `FIND_NODE` response, when the request has bit 2 (`wantToken`) set in `w`.
- A `FIND_VALUE` response.
- A `FIND_PEER` response.

---

## RPC Methods

### PING (1)
Verifies node liveness.

- **Request (`q`)**: *(empty — no fields)*
- **Response (`r`)**: *(empty — no fields)*

---

### FIND_NODE (2)
Iterative lookup returning the K closest nodes to a target ID.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | The 256-bit identifier to look up. |
| `w` | Want | `Number` | Yes | Bitmask: bit 0 = want IPv4 nodes, bit 1 = want IPv6 nodes, bit 2 = want a write token. |

**Response (`r`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | No | Up to K closest IPv4 nodes. Omitted if empty. |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | No | Up to K closest IPv6 nodes. Omitted if empty. |
| `tok` | Token | `Number` | No | Write token. Included only when `w` bit 2 was set. |

---

### FIND_VALUE (6)
Retrieves a stored value by its ID. Returns the value when found, or the closest nodes otherwise.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | ID of the value to look up. |
| `w` | Want | `Number` | Yes | Same bitmask as `FIND_NODE`. |
| `cas` | Expected Seq | `Number` | No | If present, only return the value if its stored `seq` is greater than this number. |

**Response (`r`):**

When the value is **found**, the response contains value fields. When the value is **not found**, it contains closest nodes instead. A write token is always included.

| Key | Name | Type | Condition | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | Value not found | Closest IPv4 nodes. |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | Value not found | Closest IPv6 nodes. |
| `k` | Public Key | `Id` | Mutable/encrypted | Owner's public key. |
| `rec` | Recipient | `Id` | Encrypted | Recipient's public key. |
| `n` | Nonce | `Binary` | Mutable/encrypted | 24-byte nonce. |
| `seq` | Sequence | `Number` | Mutable/encrypted | Version number. Omitted when zero. |
| `sig` | Signature | `Binary` | Mutable/encrypted | Owner's Ed25519 signature. |
| `v` | Data | `Binary` | Value found | The value payload. |
| `tok` | Token | `Number` | Always | Write token for subsequent `STORE_VALUE`. |

> The `tok` field in `FIND_VALUE` responses is serialized as part of the response body (unlike `FIND_NODE` where it is optional). It is always present when the responder holds a valid token for the requester.

---

### STORE_VALUE (5)
Publishes a value to a node. Requires a write token from a prior `FIND_VALUE` or `FIND_NODE`.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tok` | Token | `Number` | Yes | Write token from a prior lookup. |
| `cas` | Expected Seq | `Number` | No | Atomic update: only store if the currently stored `seq` equals this value. |
| `k` | Public Key | `Id` | Mutable/encrypted | Owner's public key. |
| `rec` | Recipient | `Id` | Encrypted | Recipient's public key. |
| `n` | Nonce | `Binary` | Mutable/encrypted | 24-byte nonce. |
| `seq` | Sequence | `Number` | Mutable/encrypted | New version number. Omitted when zero. |
| `sig` | Signature | `Binary` | Mutable/encrypted | Owner's Ed25519 signature. |
| `v` | Data | `Binary` | Yes | The value payload. |

**Response (`r`):** *(empty — no fields)*

---

### FIND_PEER (4)
Discovers service endpoints registered under a service ID. Returns matching peers when found, or closest nodes otherwise.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | Service identifier. |
| `w` | Want | `Number` | Yes | Same bitmask as `FIND_NODE`. |
| `cas` | Expected Seq | `Number` | No | If present, only return peers if their stored `seq` is greater than this number. |
| `e` | Count | `Number` | No | Desired number of peer results. |

**Response (`r`):**

| Key | Name | Type | Condition | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | Peers not found | Closest IPv4 nodes. |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | Peers not found | Closest IPv6 nodes. |
| `p` | Peers | `Array<PeerInfo>` | Peers found | List of matching service peer records. |

---

### ANNOUNCE_PEER (3)
Registers a service endpoint with a node. Requires a write token from a prior `FIND_PEER` or `FIND_NODE`.

**Request (`q`)** — field order on wire: `tok`, `cas`, `k`, `n`, `seq`, `o`, `os`, `sig`, `f`, `e`, `ex`:

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tok` | Token | `Number` | Yes | Write token from a prior lookup. |
| `cas` | Expected Seq | `Number` | No | Atomic update: only store if the currently stored `seq` equals this value. |
| `k` | Peer ID | `Id` | Yes | Public key of the service peer (the peer owner's key). |
| `n` | Nonce | `Binary` | Yes | 24-byte nonce. |
| `seq` | Sequence | `Number` | No | Current sequence number. Omitted when zero. |
| `o` | Node ID | `Id` | No | ID of the DHT node hosting the peer (authenticated mode only). |
| `os` | Node Signature | `Binary` | No | The hosting node's Ed25519 signature over the peer record. Required if `o` is present. |
| `sig` | Peer Signature | `Binary` | Yes | Peer owner's Ed25519 signature over the peer record. |
| `f` | Fingerprint | `Number` | Yes | Unique `long` fingerprint distinguishing peer instances with the same `k`. |
| `e` | Endpoint | `String` | Yes | Service URI (e.g., `https://example.com:8080`). |
| `ex` | Extra Data | `Binary` | No | Opaque extension bytes. |

**Response (`r`):** *(empty — no fields)*

---

## Errors

Error messages use `y` type bits = `0x00`. The body is carried in the `e` envelope field.

### Error Body (`e`)

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `c` | Code | `Number` | Yes | Numeric error code. |
| `m` | Message | `String` | No | Human-readable description. |

### Error Codes

| Code | Name | Description |
| :--- | :--- | :--- |
| **201** | Generic Error | General unclassified failure. |
| **202** | Server Error | Internal server-side failure. |
| **203** | Protocol Error | Malformed packet, invalid arguments, or bad token. |
| **204** | Method Unknown | RPC method not supported by this node. |
| **205** | Message Too Big | Packet exceeds the allowed size limit. |
| **206** | Invalid Signature | Ed25519 signature verification failed. |
| **207** | Salt Too Big | The salt value exceeds the allowed length. |
| **301** | CAS Fail | The `cas` check failed: stored sequence number does not match the expected value. |
| **302** | Sequence Not Monotonic | The new sequence number is not greater than the currently stored one. |
| **303** | Immutable Substitution Fail | Attempted to replace an immutable value with a different one. |
| **400** | Invalid Token | The write token is missing, incorrect, or expired. |
| **401** | Invalid Value | The value is malformed or fails validation. |
| **402** | Invalid Peer | The peer record is malformed or fails validation. |