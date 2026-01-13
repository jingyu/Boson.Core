# Boson Kademlia Protocol

This document describes the RPC protocol used in the Boson Kademlia DHT network. The protocol is designed for security, efficiency and interoperability, supporting both **JSON** (text) and **CBOR** (binary) serialization formats.

---

## Message Envelope

Every message in the Boson DHT follows a common envelope structure.

### Fields

| Key | Name | JSON Type | CBOR Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| **`y`** | Type & Method | `Number` | `Integer` | Composite field encoding message type and RPC method. |
| **`t`** | Transaction ID | `Number` | `Integer` | A non-zero unsigned integer used to match requests and responses. |
| **`q`** | Request Body | `Object` | `Map` | Payload for **Request** messages. |
| **`r`** | Response Body | `Object` | `Map` | Payload for **Response** messages. |
| **`e`** | Error Body | `Object` | `Map` | Payload for **Error** messages. |
| **`v`** | Version | `Number` | `Integer` | (Optional) Node software version. |

### Message Type & Method Encoding (`y`)

The `y` field is a bitmask that combines the message type and the method identifier.

| Bits | Mask | Field | Values |
| :--- | :--- | :--- | :--- |
| 0-4 | `0x1F` | **Method** | PING(1), FIND_NODE(2), ANNOUNCE_PEER(3), FIND_PEER(4), STORE_VALUE(5), FIND_VALUE(6) |
| 5-7 | `0xE0` | **Type** | ERROR(0x00), REQUEST(0x20), RESPONSE(0x40) |

**Example Computation:**
- `FIND_NODE` (2) + `REQUEST` (0x20) = `0x22` (34)
- `FIND_NODE` (2) + `RESPONSE` (0x40) = `0x42` (66)

---

## Data Representations

### Binary Data
- **JSON**: Encoded as **Base64 URL-safe** strings without padding.
- **CBOR**: Encoded as **Byte Strings**.

### Identifiers (`Id`)
- **JSON**: Base58 encoded string (default) or W3C DID.
- **CBOR**: **32 bytes** of raw binary data.

---

## RPC Methods

### PING (1)
Verifies node liveness.

- **Request (`q`)**: *Empty object*
- **Response (`r`)**: *Empty object*

---

### FIND_NODE (2)
Iterative lookup for the closest nodes to a target.

- **Request (`q`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`t`** | Target | `Id` | The 256-bit identifier to look up. |
  | **`w`** | Want | `Integer` | Bitmask: `1`=IPv4, `2`=IPv6, `4`=Return token. |

- **Response (`r`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`n4`** | Nodes IPv4 | `List<NodeInfo>`| Closest IPv4 nodes. |
  | **`n6`** | Nodes IPv6 | `List<NodeInfo>`| Closest IPv6 nodes. |
  | **`tok`**| Token | `Integer` | (Optional) Opaque token for storage writes. |

---

### FIND_VALUE (6)
Retrieves a stored value associated with an ID.

- **Request (`q`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`t`** | Target | `Id` | Identifier of the value. |
  | **`w`** | Want | `Integer` | Same as `FIND_NODE`. |
  | **`cas`**| CAS | `Integer` | (Optional) Only return value if `seq` > `cas`. |

- **Response (`r`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`n4`** / **`n6`** | Nodes | `List<NodeInfo>`| Closest nodes if value is not found. |
  | **`k`** | Public Key | `Id` | (Mutable) Public key of the owner. |
  | **`rec`**| Recipient | `Id` | (Encrypted) Public key of the recipient. |
  | **`n`** | Nonce | `Binary` | Nonce used for the value. |
  | **`seq`**| Sequence | `Integer` | Version number of the value. |
  | **`sig`**| Signature | `Binary` | Signature of the value. |
  | **`v`** | Value | `Binary` | The value data. |

---

### STORE_VALUE (5)
Publishes a value to the network. Requires a valid token.

- **Request (`q`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`tok`**| Token | `Integer` | Token from a previous `FIND_VALUE` or `FIND_NODE`. |
  | **`cas`**| CAS | `Integer` | (Optional) Atomic update: only store if `seq` matches `cas`. |
  | **`k`** | Public Key | `Id` | (Mutable) Public key of the owner. |
  | **`rec`**| Recipient | `Id` | (Encrypted) Public key of the recipient. |
  | **`n`** | Nonce | `Binary` | Nonce used for the value. |
  | **`seq`**| Sequence | `Integer` | Version number of the value. |
  | **`sig`**| Signature | `Binary` | Signature of the value. |
  | **`v`** | Value | `Binary` | The value data. |
- **Response (`r`)**: *Empty object*

---

### FIND_PEER (4)
Discovers service endpoints for a service ID.

- **Request (`q`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`t`** | Target | `Id` | Service identifier. |
  | **`w`** | Want | `Integer` | Same as `FIND_NODE`. |
  | **`cas`**| Sequence | `Integer` | (Optional) Only return results if `seq` > `cas`. |
  | **`e`** | Count | `Integer` | (Optional) Desired number of peers to return. |

- **Response (`r`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`n4`** / **`n6`** | Nodes | `List<NodeInfo>`| Closest nodes. |
  | **`p`** | Peers | `List<PeerInfo>`| List of matching service peers. |

---

### ANNOUNCE_PEER (3)
Registers a service endpoint. Requires a valid token.

- **Request (`q`)**:

  | Key | Name | Type | Description |
  | :--- | :--- | :--- | :--- |
  | **`tok`**| Token | `Integer` | Valid token from `FIND_PEER` or `FIND_NODE`. |
  | **`cas`**| CAS | `Integer` | (Optional) Atomic update: only store if `seq` matches `cas`. |
  | **`k`** | Peer ID | `Id` | Public key of the service peer. |
  | **`n`** | Nonce | `Binary` | 24-byte nonce. |
  | **`seq`**| Sequence | `Integer` | Current sequence number. |
  | **`sig`**| Signature | `Binary` | Signature from the peer owner. |
  | **`f`** | Fingerprint| `Long` | Unique fingerprint for the peer. |
  | **`e`** | Endpoint | `String` | Service URI (e.g., `https://...`). |
  | **`o`** | Node ID | `Id` | (Authenticated) ID of the hosting node. |
  | **`os`**| Node Sig | `Binary` | (Authenticated) Signature from the hosting node. |
  | **`ex`**| Extra | `Binary` | (Optional) Opaque extension data. |

- **Response (`r`)**: *Empty object*

---

## Errors

Errors are communicated using the `e` field in the envelope.

### Error Body (`e`)

| Key | Name | Type | Description |
| :--- | :--- | :--- | :--- |
| **`c`** | Code | `Integer` | Error code identifying the failure. |
| **`m`** | Message | `String` | Human-readable explanation. |

### Standard Error Codes

| Code | Label | Description |
| :--- | :--- | :--- |
| **201** | Generic Error | General failure. |
| **203** | Protocol Error | Malformed packet, invalid arguments, or bad token. |
| **204** | Method Unknown | RPC method not supported. |
| **205** | Message Too Big | Packet exceeds MTU or internal limits. |
| **206** | Invalid Signature | Cryptographic verification failed. |
| **301** | CAS Fail | Sequence number mismatch for atomic update. |
| **302** | Sequence Not Monotonic | New sequence number is not greater than current. |
| **400** | Invalid Token | The provided write token is incorrect or expired. |