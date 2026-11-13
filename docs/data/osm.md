# OpenStreetMap road data

The `routing` context builds its road graph from an OpenStreetMap (OSM) XML file for one city.
The city is Hanoi. The file is not in git: it is large, and it changes whenever we download it again.
Tests never read it. They use small hand-written files under `app/src/testFixtures/resources/graphs/`.

## Download the Hanoi extract

Run this from the repository root. It asks the public Overpass API for every drivable road in the
bounding box and writes `data/osm/hanoi.osm`, which `.gitignore` excludes.

```bash
mkdir -p data/osm
```

```bash
curl --fail --show-error --output data/osm/hanoi.osm \
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

Expect a file of roughly 100 to 200 MB. If Overpass answers 429 or 504, wait a minute and run the
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
