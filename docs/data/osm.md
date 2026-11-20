# OpenStreetMap road data

The `routing` context builds its road graph from an OpenStreetMap (OSM) XML file for one city.
The city is Hanoi. The file is not in git: it is large, and it changes whenever we download it again.
Ordinary tests never read it. They use small hand-written files under `app/src/testFixtures/resources/graphs/`.
The benchmarks tagged `slow` read it: run `./gradlew slowTest` after the download. They skip with a message when the
file is missing, and `check` never runs them.

## Download the Hanoi extract

Run this from the repository root. It asks the public Overpass API for every drivable road in the
bounding box and writes `data/osm/hanoi.osm`, which `.gitignore` excludes.

```bash
mkdir -p data/osm
```

```bash
curl --fail --show-error --user-agent 'rts-osm-download/1.0' --output data/osm/hanoi.osm \
  --data-urlencode 'data=[out:xml][timeout:180];way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$"](20.95,105.75,21.10,105.90);(._;>;);out body;' \
  https://overpass-api.de/api/interpreter
```

The query has three parts:

1. `way["highway"~"..."](20.95,105.75,21.10,105.90)` selects the road ways in the box. Overpass
   writes a box as south, west, north, east. This box covers the inner districts: Hoan Kiem, Ba Dinh,
   Dong Da, Hai Ba Trung, Tay Ho, Cau Giay, Thanh Xuan and the west of Long Bien.
2. `(._;>;)` adds every node that those ways use. A way holds only node ids, so without this step
   the file has no coordinates.
3. `out body` prints the elements with their tags. Overpass prints nodes before ways.

Expect a file of about 30 MB: the download on 2026-11-20 was 30,088,231 bytes. Overpass answered 406 to a
request without `--user-agent`, so keep that flag. If it answers 429, 502 or 504, wait a minute and run the
command again, or use another public instance such as `https://overpass.kumi.systems/api/interpreter`.

## What the loader reads

`OsmXmlGraphLoader` (`routing/adapter/out/osm`) streams the file with StAX, so it never holds the
XML tree in memory. It reads:

| Element or tag | Use |
|---|---|
| `<node id lat lon>` | Node position. Nodes that no kept way uses are dropped. |
| `<way>` with `<nd ref>` | The node chain of a road. Each pair of neighbouring nodes becomes one or two directed edges. |
| `highway` | Keeps the way only for the types in the query above, with or without `_link`. Sets the default speed. Other types, such as `footway`, are skipped. |
| `oneway` | `yes`, `true` or `1` gives forward edges only. `-1` gives reverse edges only. `no`, `false` or `0` gives both. |
| `junction` | `roundabout` or `circular` without a `oneway` tag gives forward edges only. |

A way with `highway=motorway` or `motorway_link` and no `oneway` tag is also one-way, because OSM
defines motorways as one-way by default. An explicit `oneway` tag always wins.

Default speeds, in km/h:

| `highway` | Speed |
|---|---|
| `motorway` | 80 |
| `trunk` | 70 |
| `primary` | 50 |
| `secondary` | 45 |
| `tertiary` | 40 |
| `residential`, `unclassified` | 30 |
| `service`, `living_street` | 15 |

After building the graph, `RoadGraphBuilder` keeps only the largest strongly connected component.
Every node left can then reach every other node, so a route search between two snapped points
never fails because one of them sits on a one-way dead end or on a separate island.

## Where the server reads it

The server reads the file named by `routing.graphPath` in `base.conf`, `data/osm/hanoi.osm` by default.
Set the `ROUTING_GRAPHPATH` environment variable to use another file. A relative path is resolved from the
process's working directory, so run the server from the repository root or give an absolute path.

The graph loads in the background after startup. Until it has loaded, `/health/ready` answers 503 with the
`graph` check DOWN, and route requests answer 503 ROUTING_NOT_READY. A missing or unreadable file is logged at
ERROR and leaves `graph` DOWN; the process keeps running.

## The binary graph cache

Parsing the XML takes seconds on a fast machine and tens of seconds on the staging laptop. So the server keeps the
built graph in a binary file and reads that on later starts. `CachedGraphSource` (`routing/adapter/out/osm`) does
this on every load:

1. Hash the OSM file with SHA-256.
2. Open `<routing.graphCacheDir>/<name>.graph`, so `data/osm/cache/hanoi.graph` for `hanoi.osm`. When its header has
   the same hash and format version, read the arrays from it and return the graph.
3. Otherwise parse the XML and build the graph as before, then write the cache.

The file holds the final `RoadGraph` arrays, after SCC pruning, so a read needs no builder and no pruning. The
header is a magic number (`RTSG`), the format version, the SHA-256 of the OSM file and the node and edge counts.
A CRC32C of the arrays ends the file. `RoadGraphCacheFile` writes each array in one bulk copy through a `FileChannel`
and reads it back into a plain array, without memory mapping. The grid index is rebuilt after every load, which
takes a few tens of milliseconds.

The cache is only an optimisation, so nothing about it can stop the graph from loading:

| Case | What happens |
|---|---|
| No cache file yet | Parse the XML, write the cache. Logged at INFO. |
| New OSM file, or a cache of another format version | Parse the XML, write a new cache. Logged at INFO. |
| Truncated or corrupt cache, or a file that is not a cache | Parse the XML, write a new cache. Logged at WARN. |
| The cache directory cannot be created or written | Parse the XML and use the graph. Logged at WARN. Every start parses again. |

The server writes the cache to a temporary file in the same directory, then renames it over the old one in one
step. A crash during the write leaves the old cache or none, never half a file.

The hash only proves that the input file is the same. When a code change would build a different graph from the
same file, such as new default speeds or new build rules, raise `RoadGraphCacheFile.FORMAT_VERSION`. Every server
then rebuilds its cache on the next start.

`routing.graphCacheDir` is `data/osm/cache` by default, inside the gitignored `data/osm/`. Set the
`ROUTING_GRAPHCACHEDIR` environment variable to move it. An empty value turns the cache off. Staging mounts a
small Docker volume there and keeps the OSM file read-only (`deploy/staging/compose.yml`).

The log says which path a load took: "Read the road graph from the cache" or "Parsed the road graph from the OSM
XML", each with the time it took. The measured times are in "As built (6d)" of
[phase-06-routing.md](../plan/phase-06-routing.md).

## Known limits

- Turn restrictions (`type=restriction` relations) are ignored.
- Access tags such as `access=no`, `motor_vehicle=no` and `maxspeed` are ignored.
- Closed `highway=service` ways with `area=yes`, such as car parks, are read as roads around
  their outline.
- Roads that cross the edge of the box are cut there. Overpass still returns the whole way, so
  the graph has short stubs outside the box. Most of them are removed by SCC pruning.

## Licence

OSM data is © OpenStreetMap contributors and licensed under the Open Database License (ODbL).
Any screen or document that shows routes built from this data must credit
"© OpenStreetMap contributors".
