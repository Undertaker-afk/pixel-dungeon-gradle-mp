package com.watabou.pixeldungeon.multiplayer;

import org.json.JSONObject;

public class CoopEvent {

	public static final int CURRENT_VERSION = 1;

	public enum Kind {
		UNKNOWN,
		MOVE,
		ATTACK,
		USE,
		DESCEND,
		ASCEND,
		ITEM_PICKUP,
		ITEM_DROP,
		ITEM_USE,
		DOOR_UNLOCK,
		DEATH,
		BUFF,
		CHAT,
		LEVEL_SYNC,
		FULL_STATE_SYNC,
		LEVEL_HASH,
		TURN_OUTCOME,
		DESPAWN,
		WORLD_DIFF,
		SNAPSHOT_REQUEST,
		JOIN_REQUEST,
		JOIN_RESULT
	}

	public final int version;
	public final Kind kind;
	public final String kindRaw;
	public final String actorId;
	public final int floor;
	public final long tick;
	public final JSONObject payload;
	public final long sentAtMs;

	// Backwards-compatible aliases used by existing gameplay code.
	public final int depth;
	public final int fromCell;
	public final int toCell;
	public final Long levelSeed;
	public final String levelHash;
	public final String payloadText;

	public CoopEvent( int version, Kind kind, String kindRaw, String actorId, int floor, long tick, JSONObject payload, long sentAtMs ) {
		this.version = version;
		this.kind = kind == null ? Kind.UNKNOWN : kind;
		this.kindRaw = kindRaw;
		this.actorId = actorId;
		this.floor = floor;
		this.tick = tick;
		this.payload = payload == null ? new JSONObject() : payload;
		this.sentAtMs = sentAtMs;

		this.depth = floor;
		this.fromCell = this.payload.optInt( "from", -1 );
		this.toCell = this.payload.optInt( "to", -1 );
		this.levelSeed = this.payload.has( "levelSeed" ) ? Long.valueOf( this.payload.optLong( "levelSeed", 0L ) ) : null;
		this.levelHash = this.payload.has( "levelHash" ) ? this.payload.optString( "levelHash", null ) : null;
		this.payloadText = this.payload.length() == 0 ? null : this.payload.toString();
	}

	private static CoopEvent of( Kind kind, String actorId, int floor, JSONObject payload ) {
		return new CoopEvent(
			CURRENT_VERSION,
			kind,
			kind == null ? null : kind.name(),
			actorId,
			floor,
			System.currentTimeMillis(),
			payload,
			System.currentTimeMillis() );
	}

	private static JSONObject withFromTo( int fromCell, int toCell ) {
		JSONObject payload = new JSONObject();
		payload.put( "from", fromCell );
		payload.put( "to", toCell );
		return payload;
	}

	public static CoopEvent move( String actorId, int floor, int fromCell, int toCell ) {
		return of( Kind.MOVE, actorId, floor, withFromTo( fromCell, toCell ) );
	}

	public static CoopEvent attack( String actorId, int floor, int fromCell, int toCell ) {
		return of( Kind.ATTACK, actorId, floor, withFromTo( fromCell, toCell ) );
	}

	public static CoopEvent use( String actorId, int floor, int fromCell, int toCell, String payloadText ) {
		JSONObject payload = withFromTo( fromCell, toCell );
		if (payloadText != null && payloadText.length() > 0) {
			payload.put( "details", payloadText );
		}
		return of( Kind.USE, actorId, floor, payload );
	}

	public static CoopEvent itemPickup( String actorId, int floor, String itemName, int cell ) {
		JSONObject payload = new JSONObject();
		payload.put( "item", itemName == null ? "unknown" : itemName );
		payload.put( "cell", cell );
		return of( Kind.ITEM_PICKUP, actorId, floor, payload );
	}

	public static CoopEvent itemDrop( String actorId, int floor, String itemName, int cell ) {
		JSONObject payload = new JSONObject();
		payload.put( "item", itemName == null ? "unknown" : itemName );
		payload.put( "cell", cell );
		return of( Kind.ITEM_DROP, actorId, floor, payload );
	}

	public static CoopEvent itemUse( String actorId, int floor, String itemName ) {
		JSONObject payload = new JSONObject();
		payload.put( "item", itemName == null ? "unknown" : itemName );
		return of( Kind.ITEM_USE, actorId, floor, payload );
	}

	public static CoopEvent descend( String actorId, int floor, int cell ) {
		JSONObject payload = new JSONObject();
		payload.put( "cell", cell );
		return of( Kind.DESCEND, actorId, floor, payload );
	}

	public static CoopEvent ascend( String actorId, int floor, int cell ) {
		JSONObject payload = new JSONObject();
		payload.put( "cell", cell );
		return of( Kind.ASCEND, actorId, floor, payload );
	}

	public static CoopEvent doorUnlock( String actorId, int floor, int cell ) {
		JSONObject payload = new JSONObject();
		payload.put( "cell", cell );
		return of( Kind.DOOR_UNLOCK, actorId, floor, payload );
	}

	public static CoopEvent death( String actorId, int floor, String cause ) {
		JSONObject payload = new JSONObject();
		payload.put( "cause", cause == null ? "unknown" : cause );
		return of( Kind.DEATH, actorId, floor, payload );
	}

	public static CoopEvent buff( String actorId, int floor, String buffName, String operation ) {
		JSONObject payload = new JSONObject();
		payload.put( "buff", buffName == null ? "unknown" : buffName );
		payload.put( "op", operation == null ? "ADD" : operation );
		return of( Kind.BUFF, actorId, floor, payload );
	}

	public static CoopEvent chat( String actorId, int floor, String message ) {
		JSONObject payload = new JSONObject();
		payload.put( "message", message == null ? "" : message );
		return of( Kind.CHAT, actorId, floor, payload );
	}

	public static CoopEvent levelSync( String actorId, int floor, long levelSeed ) {
		JSONObject payload = new JSONObject();
		payload.put( "levelSeed", levelSeed );
		return of( Kind.LEVEL_SYNC, actorId, floor, payload );
	}

	public static CoopEvent fullStateSync( String actorId, int floor, JSONObject state ) {
		JSONObject payload = new JSONObject();
		payload.put( "state", state == null ? new JSONObject() : state );
		return of( Kind.FULL_STATE_SYNC, actorId, floor, payload );
	}

	public static CoopEvent levelHash( String actorId, int floor, String levelHash ) {
		JSONObject payload = new JSONObject();
		payload.put( "levelHash", levelHash == null ? "" : levelHash );
		return of( Kind.LEVEL_HASH, actorId, floor, payload );
	}

	public static CoopEvent turnOutcome( String actorId, int floor, String payloadText ) {
		JSONObject payload;
		if (payloadText == null || payloadText.length() == 0) {
			payload = new JSONObject();
		} else {
			payload = new JSONObject( payloadText );
		}
		return of( Kind.TURN_OUTCOME, actorId, floor, payload );
	}

	public static CoopEvent despawn( String actorId, int floor ) {
		return of( Kind.DESPAWN, actorId, floor, new JSONObject() );
	}

	public static CoopEvent worldDiff( String actorId, int floor, JSONObject diff ) {
		JSONObject payload = new JSONObject();
		payload.put( "diff", diff == null ? new JSONObject() : diff );
		return of( Kind.WORLD_DIFF, actorId, floor, payload );
	}

	public static CoopEvent snapshotRequest( String actorId, int floor, String reason ) {
		JSONObject payload = new JSONObject();
		payload.put( "reason", reason == null ? "unspecified" : reason );
		return of( Kind.SNAPSHOT_REQUEST, actorId, floor, payload );
	}

	public static CoopEvent joinRequest( String actorId, int floor, String joinKey, String sessionToken, int clientDepth, boolean deadCharacter ) {
		JSONObject payload = new JSONObject();
		payload.put( "joinKey", joinKey == null ? "" : joinKey );
		payload.put( "sessionToken", sessionToken == null ? "" : sessionToken );
		payload.put( "clientDepth", clientDepth );
		payload.put( "deadCharacter", deadCharacter );
		return of( Kind.JOIN_REQUEST, actorId, Math.max( 1, floor ), payload );
	}

	public static CoopEvent joinResult( String actorId, int floor, String targetPlayerId, boolean accepted, String reason, String sessionToken ) {
		JSONObject payload = new JSONObject();
		payload.put( "targetPlayerId", targetPlayerId == null ? "" : targetPlayerId );
		payload.put( "accepted", accepted );
		payload.put( "reason", reason == null ? "" : reason );
		payload.put( "sessionToken", sessionToken == null ? "" : sessionToken );
		payload.put( "hostDepth", floor );
		return of( Kind.JOIN_RESULT, actorId, Math.max( 1, floor ), payload );
	}
}
