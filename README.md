# heatpump-json

## Introduction

A JSON HTTP REST facade server to the Luxtronik 2 heat pump controller for reading (not writing!) information. 
It acquires data from the heat pump through their WebSocket interface and offers it for download in JSON format in this form:

```
...
"data": [
    {
        "category": "temperature",
        "id": "flow",
        "name": "Vorlauf",
        "numeric": 23.8,
        "unit": "°C"
    },
    ...

```

I use it to fuel my [Grafana](https://grafana.com/) dashboard (via [Telegraf](https://www.influxdata.com/time-series-platform/telegraf/) and [InfluxDB](https://github.com/influxdata/influxdb)).

## Quick start

Just run a new container with the image and let it point to your heat pump address (e.g. `10.1.2.3`) like this:

```
$ docker run --rm \
    -p 8080:8080 \
    -e HEATPUMP_LANGUAGE=de \
    -e HEATPUMP_ADDRESS=10.1.2.3 \
    ghcr.io/nwaldispuehl/heatpump-json:latest
```

⚠️ Note that your Luxtronik 2 heat pump needs to be set to the German language (specified with `HEATPUMP_LANGUAGE=de`)  as this is the only language currently supported. Feel free to provide [localization](src/main/resources/keyTranslation_de.properties) in other languages.

## Design

The 'Luxtronik 2' control module is used by a number of heat pump manufacturers (e.g. alpha innotec, Buderus, CTA, Elco, etc.) and offers an undocumented binary API at port `8889` and the apparently newer WebSocket interface at port `8214` which this project is using (as it is easily observable).

This (poorly designed) WebSocket interface sends data items like this as initialization:

```
...
<item id='0x43458b3c'>
    <name>Vorlauf</name>
    <value>23.8°C</value>
</item>
...
```
but only the item `id` in subsequent polling calls, and this `id` attributes changes in every session:

```
...
<item id='0x43458b3c'>
    <value>23.8°C</value>
</item>
...
```
We thus have to use this localized `name` field for field matching, which we do with a [translation bundle](src/main/resources/keyTranslation_de.properties):
```
...
temperature = Temperaturen
temperature.flow = Vorlauf
temperature.return_flow = Rücklauf
...
```
To translate the project to other operating languages just add another language bundle file with translated values.

This server now creates a WebSocket connection to the heat pump and converts the values with the translation bundle every 5 seconds (setting `heatpump.fetch.cron`) to a JSON object structure which can be fetched via HTTP on this servers address:

```
{
  "metadata": {
    "commit": "CURRENT_COMMIT",
    "version": "CURRENT_VERSION",
    "timestamp": 1721466485
  },
  "data": [
    {
      "category": "temperature",
      "id": "flow",
      "name": "Vorlauf",
      "numeric": 23.8,
      "unit": "°C"
    },
    {
      "category": "temperature",
      "id": "return_flow",
      "name": "Rücklauf",
      "numeric": 23.8,
      "unit": "°C"
    },
    ...
  ]
}
```

## How to use

### Configuration

The server needs to be configured with these two mandatory environment variables:

```
HEATPUMP_LANGUAGE=de
HEATPUMP_ADDRESS=10.1.2.3
```

Additionally, the fetching interval could be affected with this env variable, but better leave it as is:
```
# Default for the HEATPUMP_FETCH_CRON environment variable. Fetches every 5 seconds.
HEATPUMP_FETCH_CRON = */5 * * * * ?
```


### Run with Docker

To run a docker container of the project you can use this statement:

```
$ docker run --rm \
    -p 8080:8080 \
    -e HEATPUMP_LANGUAGE=de \
    -e HEATPUMP_ADDRESS=10.1.2.3 \
    ghcr.io/nwaldispuehl/heatpump-json:latest
```

### Run with Docker compose

To run the project with Docker compose, you can use this sample docker-compose.yaml file:
```
services:
  heatpump-json:
    image: ghcr.io/nwaldispuehl/heatpump-json:latest
    ports:
      - 8080:8080
    environment:
      - HEATPUMP_LANGUAGE=de
      - HEATPUMP_ADDRESS=10.1.2.3
    restart: unless-stopped
```

### Telegraf

We use the following Telegraf config to fetch the data for our InfluxDB every minute. We assume this server runs on `10.1.2.4`

```
# Heatpump Data
[[inputs.http]]
  interval = "1m"
  
  urls = [
    "http://10.1.2.4:8080/"
  ]
  
  data_format = "json_v2"
  tagexclude = ["url", "host"]
  
  [inputs.http.tags]
    custom_bucket = "heatpump"
	
  [[inputs.http.json_v2]]
    measurement_name = "heatpump"
    measurement_name_path = "data.#.category"
  
    timestamp_path = "metadata.timestamp"
    timestamp_format = "unix"
    timestamp_timezone = "Europe/Zurich"
       
    [[inputs.http.json_v2.object]]
        path = "data"
	    tags = ["category", "id"]
	    excluded_keys = ["name", "unit"]
```

### Grafana

In Grafana we use this Flux language query for e.g. showing the `flow`:

```
from(bucket: "heatpump")
  |> range(start: v.timeRangeStart, stop:v.timeRangeStop)
  |> filter(fn: (r) =>
    r._measurement == "heatpump" and
    r.category == "temperature" and
    r.id == "flow" and
    r._field == "numeric"
  )
  |> aggregateWindow(every: v.windowPeriod, fn: mean)
  |> map(fn: (r) => ({ _value:r._value, _time:r._time, _field:r.id }))
```

## How to build

This project uses Quarkus, the Supersonic Subatomic Java Framework.
If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .


### Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./gradlew quarkusDev
```
### Packaging and running the application

The application can be packaged using:
```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `HEATPUMP_ADDRESS=10.1.2.3 java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```
The application, packaged as an _über-jar_, is now runnable using `HEATPUMP_ADDRESS=10.1.2.3 java -jar build/*-runner.jar`.

### Create docker image

See [Dockerfile.jvm](src/main/docker/Dockerfile.jvm) for directions. It boils down to:

```
docker build -f src/main/docker/Dockerfile.jvm -t heatpump-json .
```

### Creating a native executable

⚠️ Note that this does not yet work; 

You can create a native executable using:
```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:
```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/heatpump-json-0.0.1-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/gradle-tooling.










.




