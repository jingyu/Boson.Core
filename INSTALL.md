# Setting Up the Boson Service: A Beginner's Guide

***Notice**: It is recommended to install the Boson daemon on **Ubuntu Linux version 22.04** or later, and bind it to a public IP address.*

### 1. Installation of Runtime Dependencies

The Boson daemon has dependencies on the following runtime components:

- Java Virtual Machine (JVM) >= Java 11
- sodium (libsodium) >= 1.0.16

To install these dependencies, please run the following commands:

```bash
$ sudo apt install openjdk-11-jre-headless libsodium23
```

### 2. Building Your Debian Package

Please ensure that JDK-11 has been installed on your building machine before building the Boson deamon debian package. 

Use the following command to carry out the whole building process:

```bash
$ git clone git@github.com:trinity-tech-io/Boson.Java.git  Boson.Java
$ cd Boson.Java
$ ./mvnw -Dmaven.test.skip=true
```

Once the build process finishes, the debian package will be generated under the directory `launcher/target`

with the name like ***boson-launcher-<version>-<timestamp>.deb***

After the build process completes, a Debian package will be generated in the `launcher/target`directory with a name following the format ***boson-launcher-<version>-<timestamp>.deb***. Please note that  `<version>` and `<timestamp>` will vary depending on the specific version of the package being built.

### 3. Installing the Boson Service

After uploading the Debian Package to the target VPS server, run the following command to install the Boson Service:

```bash
$ sudo dpkg -i *boson-launcher-<version>-<timestamp>.deb*
```

<aside>
💡 The Boson daemon installation includes several directories and files, which are organized as follows:
- `/usr/lib/boson`: Contains the runtime libraries, including jar packages
- `/etc/boson`: Contains the configuration file `default.conf`
- `/var/lib/boson`: Contains the runtime data store
- `/var/log/boson`: Contains the output log file `boson.log`
- `/var/run/boson`: Contains the runtime directory.

The data cached under `/var/lib/boson` is organized into the following structure:
- `/var/lib/boson/key`:  Contains a randomly generated private key
- `/var/lib/boson/id`:  Contains the node ID
- `/var/lib/boson/dht4.cache`:  Contains the routing table information for IPv4 addresses
- `/var/lib/boson/dht6.cache`:  Contains the routing table information for IPv6 addresses if IPv6 is enabled
- `/var/lib/boson/node.db`: Contains the information about Value and PeerInfo

</aside>

Once the Boson Service has been installed as a service, it is necessary to open the designated port for usage (the default is `39001`):

```bash
$ sudo ufw allow 39001/udp
```

To check if the port is accessible, use the following command. Additionally, you can review the log file for more detailed information on the current running status:

```bash
$ sudo ufw status verbose
$ tail -f /var/log/boson/boson.log
```

We would also recommend using the '`systemctl`' command to check the status of the Boson daemon service or to start/stop the service:

```bash
$ systemctl status boson
$ sudo systemctl start boson
$ sudo systemctl stop boson
```

### 4. An example of config file

To officially launch the Boson Service and improve the health of the Boson network, the service config file should be updated to reference the following configuration file:

```json
{
  "ipv4": true,
  "ipv6": false,
  "address4": "your-ipv4-address",
  "address6": "your-ipv6-address",
  "port": 39001,
  "dataDir": "/var/lib/boson",

  "bootstraps": [
    // boson-node1
    {
      "id": "HZXXs9LTfNQjrDKvvexRhuMk8TTJhYCfrHwaj3jUzuhZ",
      "address": "155.138.245.211",
      "port": 39001
    },
    // boson-node2
    {
      "id": "6o6LkHgLyD5sYyW9iN5LNRYnUoX29jiYauQ5cDjhCpWQ",
      "address": "45.32.138.246",
      "port": 39001
    },
    // boson-node3
    {
      "id": "8grFdb2f6LLJajHwARvXC95y73WXEanNS1rbBAZYbC5L",
      "address": "140.82.57.197",
      "port": 39001
    },
    // boson-node4
    {
      "id": "4A6UDpARbKBJZmW5s6CmGDgeNmTxWFoGUi2Z5C4z7E41",
      "address": "66.42.74.13",
      "port": 39001
    }
  ] 
}
```
