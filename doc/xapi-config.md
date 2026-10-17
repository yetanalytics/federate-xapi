## Federate xAPI - xAPI Configuration Reference

The xAPI configuration file controls which HLA events become xAPI statements, how statement templates are filled, how cached HLA object state is queried, and where generated statements are posted.

By default the adapter reads `config/xapi-config.json`. Set `XAPI_CONFIG` to load a different file.

```shell
XAPI_CONFIG=/path/to/xapi-config.json make run-dev
```

At the top level the file supports:

```json
{
  "configVersion": "1.0",
  "statementTriggers": [],
  "lrs": {},
  "objectCache": {}
}
```

`configVersion` identifies the configuration document contract. Version `1.0` is
the current version. For backward compatibility, a missing version is currently
read as `1.0`; new and edited configurations should always include the field.
An explicitly unsupported version prevents startup rather than being interpreted
as the current format.

## Statement Triggers

`statementTriggers` is an array of templates that are processed when matching HLA events arrive.

```json
{
  "type": "Interaction",
  "class": "EntityAte",
  "criteria": [
    ["trigger", ["PredatorId"]],
    "!=",
    ["trigger", ["PreyId"]]
  ],
  "lookups": {
    "predator": {
      "class": "SimEntity",
      "criteria": [["EntityId"], "=", ["trigger", ["PredatorId"]]]
    }
  },
  "statement": {
    "actor": {
      "objectType": "Agent",
      "name": ["lookup", "predator", ["FirstName"]]
    },
    "verb": {
      "id": "http://example.com/verbs/ate",
      "display": {"en-US": "Ate"}
    },
    "object": {
      "id": "http://example.com/activity/eating"
    }
  }
}
```

Fields:

- `type`: One of `Interaction`, `ObjectCreate`, `ObjectUpdate`, or `ObjectDelete`.
- `class`: Local HLA interaction or object class name. Interaction matching is exact. Object lifecycle matching is polymorphic: a trigger configured for an object class also matches instances of every FOM descendant.
- `criteria`: Optional expression evaluated before the statement template is processed. A non-matching trigger is skipped without producing an xAPI statement. A trigger without criteria always matches.
- `lookups`: Optional named cache lookups loaded on first use. A lookup result, including a missing result, is reused for the rest of that trigger attempt.
- `statement`: An xAPI statement template. Any JSON object accepted by the xAPI spec can be used here, with injection expressions inserted where dynamic values are needed.
- `skipValidation`: Optional flag to skip boot validation for xAPI statement template and injections. **NOTE: This may result in invalid statements being sent to LRS!** Only use if startup is throwing unnecessary validation errors for your template. If you encounter validation issues that you believe to be in error, please report them in a Github Issue.

Every matching trigger is processed once for an eligible callback. One trigger failing its criteria, injection rendering, or enqueue does not prevent other matching triggers from being processed.

### Event types and payloads

| Type | Eligible RTI callback | Meaning of `trigger` |
| --- | --- | --- |
| `Interaction` | Every received interaction of the exact class | Parameters in that interaction |
| `ObjectCreate` | First successfully processed non-empty reflection after discovery of the configured class or a descendant | Attributes in that one reflection |
| `ObjectUpdate` | Every successfully processed non-empty reflection for the configured class or a descendant | Attributes in that one reflection |
| `ObjectDelete` | First removal of a known active object of the configured class or a descendant | Last cached attributes for the object |

Object lifecycle trigger classes are polymorphic. For example, an `ObjectUpdate` trigger configured for `SimEntity` matches reflections reported as `SimEntity`, `Carrot`, `Rabbit`, or `Wolf`, while a trigger configured for `Rabbit` matches only `Rabbit` in this FOM. Interaction trigger classes remain exact-match.

Each configured trigger remains an independent rule. If both `SimEntity` and `Rabbit` ObjectUpdate triggers are configured, one Rabbit reflection evaluates each trigger once and can intentionally produce two statements. If only the `SimEntity` trigger is configured, that reflection evaluates it once. Subscribing both an ancestor and descendant, subscribing multiple attributes, or receiving multiple attributes in one callback does not duplicate a configured trigger's execution.

Object trigger templates and criteria are validated against their configured class. A `SimEntity` trigger can use inherited `SimEntity` attributes such as `EntityId`, but it cannot directly reference child-only attributes such as `Rabbit.Hunger` because that path is not valid for every class the trigger matches.

Object event subscriptions expand across the configured class and every FOM descendant. Each class is subscribed to all of its own top-level attributes, including inherited attributes, so a child-only update such as `Rabbit.Hunger` or `Carrot.Age` is eligible for a `SimEntity` trigger. These event subscriptions are merged and deduplicated with attributes required by queries, lookups, `previous`, and `objectCache.trackedObjects`.

`ObjectCreate` means first observed by this adapter, not necessarily created in the federation at that moment. It also fires for pre-existing objects discovered after a late join and can fire again after the adapter restarts. Discovery marks an object as pending creation and requests its subscribed values; the trigger waits for the first non-empty reflection. That reflection is independently eligible for both `ObjectCreate` and `ObjectUpdate`.

Create payloads are not aggregated across callbacks. If the first reflection contains only `EntityId`, another attribute arriving in a later reflection is missing from the Create payload. A successful first reflection consumes the pending-create marker even when a particular Create trigger is skipped by criteria or a required injection. A cache failure retains the marker for the next successful reflection. Removal before the first reflection clears it without emitting Create.

Update payloads are also callback-local. A `SimEntity` trigger activated by a Rabbit reflection containing only `Hunger` has no incoming `EntityId`, even if `EntityId` was cached earlier. A required `trigger` injection for `EntityId` suppresses that statement, while an optional injection renders `null`. Use `previous`, `query`, or `lookup` when the desired value should come from cached state.

`ObjectDelete` reports that an object disappeared, not why it disappeared. It uses the final state retained by this adapter and therefore subscribes and caches the configured class's complete object state. An object removed after discovery but before receiving attributes can still produce a static Delete statement; required missing values suppress a statement and optional values render `null`. Unknown, already removed, or duplicate removals are skipped.

Discovery and removal can race. If the object disappears before the adapter's bootstrap `requestAttributeValueUpdate` reaches the RTI, `ObjectInstanceNotKnown` is treated as expected and logged at debug level. Cached discovery metadata remains available to the removal callback.

### Object event ordering

For Create and Update, matching statements are rendered against the incoming reflection and the pre-reflection cache. The adapter then commits the complete reflection as one cache transaction and enqueues staged statements only after a successful commit. If caching fails, no statements from that reflection are enqueued. An LRS enqueue failure does not roll back an already committed reflection.

For Delete, statements and their queries/lookups are rendered while the object is still current. The adapter then marks the object removed and enqueues only after that mutation succeeds.

For ObjectUpdate, one update means one RTI `reflectAttributeValues` callback. A callback carrying several attributes is still one update and evaluates each applicable configured trigger once. Repeated callbacks and an RTI or publisher splitting attributes across callbacks are separate updates and can each emit statements.

This ordering relies on the RTI delivering callbacks serially, as Portico's immediate callback dispatcher currently does. The adapter does not promise pre-update snapshot semantics if callbacks are invoked concurrently.

## Targets

A target is a JSON array path into an HLA parameter or cached object attribute:

```json
["EntityId"]
["Position", "X"]
["LocationHistory", 0, "Y"]
```

String parts name parameters, attributes, or fixed-record fields. Integer parts index into arrays. For cached object values, paths are stored as FOM-derived keys such as `Position.X` and `LocationHistory[0].Y`; array index lookups can also match wildcard array metadata in the cache.

## Criteria Expressions

Criteria are JSON arrays:

```json
[leftExpression, "operator", rightExpression]
```

Supported comparison operators:

- `=`
- `!=`
- `<`
- `>`
- `<=`
- `>=`

The left or right side may be a target, primitive value, nested criterion, or an expression that reads from `trigger`, `previous`, `query`, or `lookup`.

```json
[["Hunger"], ">", 50]
[["EntityId"], "=", ["trigger", ["PredatorId"]]]
[["Position", "X"], "<=", ["trigger", ["ToPosition", "X"]]]
```

Logical expressions use `and` or `or` between criteria:

```json
[
  [["Hunger"], ">", 50],
  "and",
  [["Position", "X"], "<", 15]
]
```

Use a single logical operator at a given array level. If mixed `and`/`or` logic is needed, prefer nested expressions.

In cache queries, `=` compares numbers numerically when both sides are numeric; otherwise it uses normal equality. Ordered comparisons compare numbers numerically, comparable values of the same class directly, and otherwise fall back to string comparison.

### Trigger criteria

Statement-trigger criteria require an explicit value source. Use `trigger` to read the current event, `previous` to read pre-reflection state in an ObjectUpdate trigger, `query` to read the first matching cached object, or `lookup` to read a named lookup. Bare targets remain reserved for the cached object being tested inside query and lookup filters.

```json
{
  "lookups": {
    "predator": {
      "class": "SimEntity",
      "criteria": [["EntityId"], "=", ["trigger", ["PredatorId"]]]
    }
  },
  "criteria": [
    ["trigger", ["PredatorId"]],
    "=",
    ["lookup", "predator", ["EntityId"]]
  ]
}
```

A query is also a value expression in trigger criteria:

```json
[
  ["query", "World", ["Size"], [["WorldId"], "=", ["trigger", ["WorldId"]]]],
  ">",
  0
]
```

ObjectUpdate change detection can compare the incoming and prior values directly:

```json
[
  ["previous", ["Hunger"]],
  ">",
  ["trigger", ["Hunger"]]
]
```

`previous` is rejected in Interaction, ObjectCreate, and ObjectDelete triggers. Query and lookup filters may contain cached targets, literals, nested comparisons/logical expressions, and `trigger` expressions. Nested queries and lookups are not allowed in cache filters. Injection rendering options such as `required` and `nullable` do not apply inside criteria.

Logical expressions short-circuit. A missing query object, lookup object, or target value resolves to `null` for comparison purposes. Other resolution errors fail trigger processing rather than being treated as a non-match.

## Statement Injections

An injection can appear as a whole JSON value:

```json
"name": ["trigger", ["EntityId"]]
```

or inside a JSON string using `<<...>>` with an escaped JSON injection array:

```json
"name": "Rabbit <<[\"trigger\", [\"EntityId\"]]>>"
```

Whole-value injections preserve the replacement's JSON type. Inline injections are always rendered as text inside the containing string.

All injection types accept an optional final options object:

```json
["trigger", ["OptionalField"], {"required": false}]
```

The available options are:

- `required`: Defaults to `true`. When `false`, a missing object, missing value, or resolved `null` renders as JSON `null` (or as the text `null` inline) and statement processing continues.
- `nullable`: Defaults to `false`. When `required` is `true`, this allows an explicitly resolved `null` to render, but still treats a missing object or value as an error.

A failed required injection aborts the statement, and the trigger returns no xAPI statement. `nullable` is redundant when `required` is `false`. For example, an optional inline injection renders `Rabbit null` when the target cannot be resolved:

```json
"name": "Rabbit <<[\"query\", \"Rabbit\", [\"Nickname\"], null, {\"required\": false}]>>"
```

### `trigger`

`trigger` reads a value from the current event context.

```json
["trigger", ["PredatorId"]]
["trigger", ["FromPosition", "X"]]
```

For Interaction triggers, `trigger` reads the interaction parameter map. For ObjectCreate and ObjectUpdate, it reads only attributes present in the current reflection payload, never an older cached value. For ObjectDelete, it reads the final cached attributes from the removal snapshot.

All event contexts use the FOM to decode primitive values, fixed-record fields, and array elements. An absent attribute, out-of-range array element, cached null, or malformed HLA value follows the normal missing/null handling described above.

### `previous`

`previous` reads the value cached for the same object handle before the current ObjectUpdate reflection is committed.

```json
["previous", ["Hunger"]]
["previous", ["Position", "X"], {"required": false}]
```

It supports primitive, fixed-record, and array paths. On the first observation, or when that attribute has not previously been reflected, it resolves as a missing value. Use `{"required": false}` to render `null` on that first observation. A cached null is distinct from a missing value and can be accepted with `{"nullable": true}`.

Any `previous` reference adds the referenced top-level attribute to the cache subscription requirements. All matching triggers in one reflection see the same pre-reflection state.

### `query`

`query` searches the object cache for the first current object of a class that matches criteria, then returns one target value from that object.

```json
["query", "Rabbit", ["EntityId"], [["Hunger"], ">", 50]]
```

Arguments:

- Class name: HLA object class to search, using the local FOM class name.
- Target: cached value to return from the matched object.
- Criteria: expression evaluated against cached values for each current object.
- Options: optional `{"required": false}` and/or `{"nullable": true}`.

Queries use the adapter's current object cache, not arbitrary SQL provided in the config. A class query includes active instances of that class and its FOM descendants. Removed objects are excluded. If more than one object matches, the first cached object is used.

`trigger` may be used inside query criteria. It is resolved from the triggering event before the cache query runs:

```json
[
  "query",
  "SimEntity",
  ["EntityType"],
  [["EntityId"], "=", ["trigger", ["PredatorId"]]]
]
```

### `lookup`

`lookup` reads a value from a named object resolved by the trigger's `lookups`
section.

```json
{
  "lookups": {
    "predator": {
      "class": "SimEntity",
      "criteria": [["EntityId"], "=", ["trigger", ["PredatorId"]]]
    },
    "prey": {
      "class": "SimEntity",
      "criteria": [["EntityId"], "=", ["trigger", ["PreyId"]]]
    }
  },
  "statement": {
    "actor": {
      "name": "<<[\"lookup\", \"predator\", [\"FirstName\"]]>> <<[\"lookup\", \"predator\", [\"LastName\"]]>>"
    },
    "object": {
      "name": "<<[\"lookup\", \"prey\", [\"FirstName\"]]>> <<[\"lookup\", \"prey\", [\"LastName\"]]>>"
    }
  }
}
```

A lookup definition contains:

- `class`: HLA object class to search in the cache.
- `criteria`: cache criteria used to select the object. `trigger` expressions are allowed and are resolved against the triggering event.

A lookup injection has this shape:

```json
["lookup", "predator", ["EntityId"]]
```

The alias must exist in the trigger's `lookups` map. An alias is resolved only when criteria evaluation or statement rendering first reads it. All later reads during that trigger attempt reuse the same cached object; a missing result is memoized as well.

## Object Cache

The object cache stores the latest reflected values for subscribed HLA object attributes in SQLite or PostgreSQL. Its backing store is initialized whenever the adapter starts, even when the xAPI configuration does not require any object subscriptions. In that case the database contains the cache schema and current FOM metadata but no simulation objects.

The adapter adds cache-specific object subscriptions when any of these are configured:

- an ObjectUpdate trigger uses `previous`,
- a statement template or trigger criterion contains a `query`,
- a trigger defines `lookups` or uses `lookup` expressions that reference cached object attributes, or
- an ObjectDelete trigger exists for a known FOM class, or
- `objectCache.trackedObjects` explicitly requests tracked attributes.

ObjectCreate and ObjectUpdate triggers also create event subscriptions for the configured class and every descendant, using each class's complete inherited and declared top-level attribute set. Reflections received through these event subscriptions are persisted because the backing store is always available, even when the trigger does not use cache-specific expressions.

The adapter subscribes to the top-level object attributes required by lifecycle events, `previous`, query targets, query criteria, lookup targets, lookup criteria, ObjectDelete snapshots, and explicit tracked objects. A requirement configured on a FOM class is expanded to that class and its descendants. Referenced attribute lists are copied to every descendant, while lifecycle events, ObjectDelete snapshots, and `allAttributes` requirements use each descendant's complete inherited and declared top-level attribute set. Requirements configured on an ancestor and a discovered child are combined for the bootstrap attribute request. Use the `trackedObjects` array to force caching of simulation objects that are not otherwise required by a trigger:

```json
{
  "objectCache": {
    "trackedObjects": [
      {"class": "Rabbit", "attributes": ["EntityId", "Hunger"]},
      {"class": "World", "allAttributes": true},
      {"class": "*", "allAttributes": true}
    ]
  }
}
```

Tracked object fields:

- `class`: Local HLA object class name. Use `*` with `allAttributes: true` to subscribe to all top-level attributes for every FOM object class with attributes.
- `attributes`: Top-level attribute names to subscribe to for the class and every descendant.
- `allAttributes`: When `true`, expands to each matching class's complete inherited and declared top-level attribute set.

`HLA_OBJECT_CACHE_BACKEND` selects `sqlite` or `postgresql` case-insensitively. It defaults to `sqlite`.
Backend and connection settings are runtime configuration and cannot be set in the xAPI JSON file.
Invalid settings or a failure to connect to the selected backend prevent adapter startup, regardless of which cache features appear in the xAPI configuration.

The cache decodes reflected values using the FOM and stores both top-level values and flattened nested values for fixed records and arrays. For example, reflecting `Position` can make `Position`, `Position.X`, and `Position.Y` available to query and lookup targets.

One RTI reflection is one cache transaction: every reflected attribute is replaced with a shared observation timestamp and sequence, or the transaction rolls back without changing any of them. A partial reflection updates only the attributes it contains; it does not erase other cached attributes. Empty reflections are ignored.

Known object classes are subscribed most-specific-first, with deterministic name ordering among classes at the same FOM depth. This allows a late-joining adapter to discover pre-existing objects at the most-concrete subscribed FOM class before subscribing their ancestors. Discovery and reflection cache the class reported by the RTI, and a base-class query such as `SimEntity` still includes cached descendants.

Here, "concrete" means the FOM class under which the publishing federate registered the object and which the RTI makes known to this adapter. An object actually registered as `SimEntity` remains `SimEntity`; the adapter does not reinterpret its `EntityType` attribute to reclassify it as `Carrot`, `Rabbit`, or `Wolf`.

### Cache Database

See [Database Config](db-config.md) for instructions on configuring the database that the cache resides on.

## Object Lifecycle Recipes

These examples use attributes from `config/HlaFedereplFOM.xml`. The full sample configuration in `config/xapi-config.json` also includes Interaction, ObjectUpdate, ObjectCreate, and ObjectDelete statements.

### Rabbit first observed

This statement represents a rabbit first observed by this adapter. It may represent a birth, an object that existed before a late join, or an object rediscovered after restart.

```json
{
  "type": "ObjectCreate",
  "class": "Rabbit",
  "statement": {
    "actor": {
      "objectType": "Agent",
      "account": {
        "homePage": "https://hla-federepl.example/adapters",
        "name": "lifecycle-monitor"
      }
    },
    "verb": {
      "id": "https://hla-federepl.example/verbs/appeared",
      "display": {"en-US": "appeared"}
    },
    "object": {
      "objectType": "Activity",
      "id": "https://hla-federepl.example/simulation/entities/rabbit"
    },
    "context": {
      "extensions": {
        "https://hla-federepl.example/extensions/entity-id": [
          "trigger",
          ["EntityId"],
          {"required": false}
        ],
        "https://hla-federepl.example/extensions/hunger": [
          "trigger",
          ["Hunger"],
          {"required": false}
        ]
      }
    }
  }
}
```

Because Create uses only the first reflection, either optional value can be `null` even if it arrives in a later callback.

### Carrot reaches an age

This ObjectUpdate statement fires only when a carrot crosses age 10. It does not fire repeatedly while the carrot remains older than 10.

```json
{
  "type": "ObjectUpdate",
  "class": "Carrot",
  "criteria": [
    [
      ["previous", ["Age"]],
      "<",
      10
    ],
    "and",
    [
      ["trigger", ["Age"]],
      ">=",
      10
    ]
  ],
  "statement": {
    "actor": {
      "objectType": "Agent",
      "account": {
        "homePage": "https://hla-federepl.example/adapters",
        "name": "lifecycle-monitor"
      }
    },
    "verb": {
      "id": "https://hla-federepl.example/verbs/aged",
      "display": {"en-US": "aged"}
    },
    "object": {
      "objectType": "Activity",
      "id": "https://hla-federepl.example/simulation/entities/carrot"
    },
    "context": {
      "extensions": {
        "https://hla-federepl.example/extensions/previous-age": [
          "previous",
          ["Age"]
        ],
        "https://hla-federepl.example/extensions/current-age": [
          "trigger",
          ["Age"]
        ]
      }
    }
  }
}
```

The first Age reflection has no `previous` value, so the ordered criterion is false and no statement is emitted.

### Wolf becomes more full

In this FOM, Hunger is the number of steps since the wolf last ate. A decrease therefore represents feeding:

```json
{
  "type": "ObjectUpdate",
  "class": "Wolf",
  "criteria": [
    ["previous", ["Hunger"]],
    ">",
    ["trigger", ["Hunger"]]
  ],
  "statement": {
    "actor": {
      "objectType": "Agent",
      "account": {
        "homePage": "https://hla-federepl.example/adapters",
        "name": "lifecycle-monitor"
      }
    },
    "verb": {
      "id": "https://hla-federepl.example/verbs/fed",
      "display": {"en-US": "fed"}
    },
    "object": {
      "objectType": "Activity",
      "id": "https://hla-federepl.example/simulation/entities/wolf"
    },
    "context": {
      "extensions": {
        "https://hla-federepl.example/extensions/previous-hunger": [
          "previous",
          ["Hunger"]
        ],
        "https://hla-federepl.example/extensions/current-hunger": [
          "trigger",
          ["Hunger"]
        ]
      }
    }
  }
}
```

A starvation threshold uses the same crossing pattern as carrot age: require `previous Hunger < threshold` and incoming `Hunger >= threshold`. That can describe the simulation's starvation condition. ObjectDelete alone cannot establish starvation as the cause.

### Wolf disappears

This Delete statement reports the wolf's final observed state:

```json
{
  "type": "ObjectDelete",
  "class": "Wolf",
  "statement": {
    "actor": {
      "objectType": "Agent",
      "account": {
        "homePage": "https://hla-federepl.example/adapters",
        "name": "lifecycle-monitor"
      }
    },
    "verb": {
      "id": "https://hla-federepl.example/verbs/disappeared",
      "display": {"en-US": "disappeared"}
    },
    "object": {
      "objectType": "Activity",
      "id": "https://hla-federepl.example/simulation/entities/wolf"
    },
    "context": {
      "extensions": {
        "https://hla-federepl.example/extensions/entity-id": [
          "trigger",
          ["EntityId"],
          {"required": false}
        ],
        "https://hla-federepl.example/extensions/final-hunger": [
          "trigger",
          ["Hunger"],
          {"required": false}
        ]
      }
    }
  }
}
```

This means only that the wolf disappeared from the adapter's known object set. Pair it with a threshold-crossing ObjectUpdate statement if the semantic cause matters.

## LRS Configuration

The `lrs` section configures the xAPI client.

```json
{
  "lrs": {
    "host": "http://localhost:8080/xapi",
    "key": "my_key",
    "secret": "my_secret",
    "batch": 35,
    "maxRetries": 3
  }
}
```

Fields:

- `host`: LRS xAPI endpoint.
- `key`: LRS basic auth username or key.
- `secret`: LRS basic auth password or secret.
- `batch`: Max xAPI Statement batch size passed to the LRS in one POST.
- `maxRetries`: Number of scheduled retry attempts before the current queue of statements is sent to dead letter queue after repeated LRS post failures.

See the [queue docs](queue-config.md) for more information on statement queue behavior.

### Example LRS

You can run an example LRS with the proper configuration for the default `xapi-config.json` by running the included docker-compose.

```shell
docker compose up lrs
```

## Complete Example

```json
{
  "configVersion": "1.0",
  "statementTriggers": [
    {
      "type": "Interaction",
      "class": "EntityAte",
      "lookups": {
        "predator": {
          "class": "SimEntity",
          "criteria": [["EntityId"], "=", ["trigger", ["PredatorId"]]]
        },
        "prey": {
          "class": "SimEntity",
          "criteria": [["EntityId"], "=", ["trigger", ["PreyId"]]]
        }
      },
      "statement": {
        "actor": {
          "objectType": "Agent",
          "name": "<<[\"lookup\", \"predator\", [\"FirstName\"]]>> <<[\"lookup\", \"predator\", [\"LastName\"]]>>",
          "account": {
            "homePage": "https://hla-federepl.example/entities",
            "name": ["trigger", ["PredatorId"]]
          }
        },
        "verb": {
          "id": "http://example.com/verbs/ate",
          "display": {"en-US": "Ate"}
        },
        "object": {
          "objectType": "Agent",
          "name": "<<[\"lookup\", \"prey\", [\"FirstName\"]]>> <<[\"lookup\", \"prey\", [\"LastName\"]]>>",
          "account": {
            "homePage": "https://hla-federepl.example/entities",
            "name": ["trigger", ["PreyId"]]
          }
        }
      }
    }
  ],
  "objectCache": {
    "trackedObjects": [
      {"class": "SimEntity", "allAttributes": true}
    ]
  },
  "lrs": {
    "host": "http://localhost:8080/xapi",
    "key": "my_key",
    "secret": "my_secret",
    "batch": 35,
    "maxRetries": 3
  }
}
```
