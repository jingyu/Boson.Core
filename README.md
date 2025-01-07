# Boson Java Core

|GitHub CI|
|:-:|
|[![CI](https://github.com/trinity-tech-io/Boson.Java/actions/workflows/maven.yml/badge.svg)](https://github.com/trinity-tech-io/Boson.Java/actions/workflows/maven.yml)|

We use Bugsnag for error tracking. thanks for [![Bugsnag](https://images.typeform.com/images/QKuaAssrFCq7/image/default)](http://www.bugsnag.com/)

Boson is a decentralized and encrypted peer-to-peer (P2P)  communication framework that facilitates network traffic routing between virtual machines and decentralized Applications (dApps).  Boson Java is a Java distribution designed to run on VPS servers with a public IP address, serving as a super Boson Node service.

Boson is a new two-layered architecture that features a unified DHT network as the bottom layer and facilitates various application-oriented services on top of the DHT network, where a list of services includes, but is not limited to:

- An active proxy service forwards the service entries from third-parties originally located within a LAN network, making them accessible from the public;
- A federal-based decentralized communication system provides great efficiency and security, including similar features to Boson V1;
- A content addressing based storage system allows the distribtion of data among peers for the application scenarios like P2P file sharing.

**Notice**:  *the later two features have not been developed yet, but they are already included in the TODO List*.

## Guide to compiling and building to Boson Java

### Dependencies

- Java Virtual Machine (JVM) >= Java 11
- sodium (libsodium) >= 1.0.16

### Build instructions

Download this repository using Git:

```shell
git clone https://github.com/trinity-tech-io/Boson.Java
```

Then navigate to the directory with the source code downloaded:

```shell
./mvnw
```

If you want to skip the test cases, use the following command instead of the command mentioned above:

```shell
./mvnw -Dmaven.test.skip=true 
```

If you want to run build and run all test cases, using the following command:

```shell
MAVEN_OPTS="-Xmx20480m -Xms10240m" ./mvnw -Dio.bosonnetwork.enviroment=development
```

Or you can run specific test with the following command, for example `NodeTests`:

```shell
MAVEN_OPTS="-Xmx20480m -Xms10240m" ./mvnw -Dio.bosonnetwork.enviroment=development -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NodeTests
```

## Contribution

We welcome contributions from passionate developers from open-source community who aspire to create a secure, decentralized communication platform and help expand the capabilities of Boson to achieve wider adoption.

## Acknowledgments

A sincere thank you goes out to all the projects that we rely on directly or indirectly, for their contributions to the development of Boson Project. We value the collaborative nature of the open-source community and recognize the importance of working together to create innovative, reliable software solutions.

## License

This project is licensed under the terms of the [MIT License](https://github.com/trinity-tech-io/Boson.Java/blob/master/LICENSE). We believe that open-source licensing  promotes transparency, collaboration, and innovation, and we encourage others to contribute to the project in accordance with the terms of the license.
