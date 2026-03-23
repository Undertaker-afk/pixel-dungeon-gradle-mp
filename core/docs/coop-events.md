# Co-op realtime event schema

`CoopEvent` now uses a versioned envelope for all multiplayer events:

```json
{
  "version": 1,
  "kind": "MOVE",
  "actorId": "hero-123",
  "floor": 5,
  "tick": 171000111222,
  "payload": {
    "from": 11,
    "to": 17
  },
  "sentAtMs": 171000111222
}
```

## Envelope fields

- `version` *(int, required)*: wire contract version. Current is `1`.
- `kind` *(string, required)*: event type enum name.
- `actorId` *(string, required)*: sender peer ID.
- `floor` *(int, required)*: dungeon depth/floor number.
- `tick` *(long, required)*: sender tick/time marker.
- `payload` *(object, required)*: kind-specific data.
- `sentAtMs` *(long, optional)*: sender wall-clock timestamp.

## Kind list

The runtime recognizes these kinds:

- Existing flow: `MOVE`, `ATTACK`, `USE`, `LEVEL_HASH`, `TURN_OUTCOME`, `DESPAWN`
- Added kinds: `DESCEND`, `ASCEND`, `ITEM_PICKUP`, `ITEM_DROP`, `ITEM_USE`, `DOOR_UNLOCK`, `DEATH`, `BUFF`, `CHAT`, `LEVEL_SYNC`, `FULL_STATE_SYNC`
- Forward compatibility sentinel: `UNKNOWN`

Unknown future kinds decode to `UNKNOWN` and are ignored by gameplay intent processing.

## Required payload fields by kind

- `MOVE`, `ATTACK`, `USE`: `from`, `to`
- `LEVEL_SYNC`: `levelSeed`
- `LEVEL_HASH`: `levelHash`
- `TURN_OUTCOME`: `actorId`
- `ITEM_PICKUP`, `ITEM_DROP`, `ITEM_USE`: `item`
- `DOOR_UNLOCK`, `DESCEND`, `ASCEND`: `cell`
- `DEATH`: `cause`
- `BUFF`: `buff`, `op`
- `CHAT`: `message`
- `FULL_STATE_SYNC`: `state`
- `DESPAWN`: no required payload keys

## Validation rules

Encode and decode both run strict validation:

1. Envelope required fields must exist and have valid values.
2. Required payload keys must be present for each known `kind`.
3. Missing required fields throw `IllegalArgumentException`.
4. Legacy pre-versioned packets (`actor/depth/from/to/...`) still decode via compatibility mapping.

## Sender call sites

Current sender integration emits additional events from local gameplay actions:

- `Hero.move` -> `MOVE`
- `Hero.onAttackComplete` -> `ATTACK`
- `Hero.actPickUp` -> `ITEM_PICKUP`
- `Item.doDrop` / `Item.execute` -> `ITEM_DROP` / `ITEM_USE`
- `Hero.onOperateComplete` unlock branch -> `DOOR_UNLOCK`
- `Hero.actDescend` / `Hero.actAscend` -> `DESCEND` / `ASCEND`
- `Hero.die` -> `DEATH`
- `Hero.add` / `Hero.remove` buff lifecycle -> `BUFF`
