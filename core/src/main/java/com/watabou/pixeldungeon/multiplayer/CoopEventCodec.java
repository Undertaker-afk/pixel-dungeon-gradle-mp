package com.watabou.pixeldungeon.multiplayer;

import java.io.UnsupportedEncodingException;

import org.json.JSONObject;

public final class CoopEventCodec {

	private CoopEventCodec() {
	}

	public static String toJson( CoopEvent event ) {
		if (event == null) {
			throw new IllegalArgumentException( "event must not be null" );
		}
		validate( event );

		JSONObject json = new JSONObject();
		json.put( "version", event.version );
		json.put( "kind", event.kindRaw == null ? event.kind.name() : event.kindRaw );
		json.put( "actorId", event.actorId );
		json.put( "floor", event.floor );
		json.put( "tick", event.tick );
		json.put( "payload", event.payload == null ? new JSONObject() : event.payload );
		json.put( "sentAtMs", event.sentAtMs );
		return json.toString();
	}

	public static CoopEvent fromJson( String text ) {
		JSONObject json = new JSONObject( text );
		int version = json.optInt( "version", 1 );
		String kindRaw = json.optString( "kind", null );
		CoopEvent.Kind kind = parseKind( kindRaw );
		String actorId = json.optString( "actorId", null );
		if (actorId == null || actorId.length() == 0) {
			// Legacy fallback support.
			actorId = json.optString( "actor", null );
		}
		int floor = json.has( "floor" ) ? json.optInt( "floor", -1 ) : json.optInt( "depth", -1 );
		long tick = json.optLong( "tick", json.optLong( "sentAtMs", System.currentTimeMillis() ) );
		JSONObject payload = json.optJSONObject( "payload" );
		if (payload == null) {
			payload = new JSONObject();
			mergeLegacyFields( json, payload );
		}
		CoopEvent event = new CoopEvent(
			version,
			kind,
			kindRaw,
			actorId,
			floor,
			tick,
			payload,
			json.optLong( "sentAtMs", System.currentTimeMillis() ) );
		validate( event );
		return event;
	}

	private static CoopEvent.Kind parseKind( String kindRaw ) {
		if (kindRaw == null || kindRaw.length() == 0) {
			return CoopEvent.Kind.UNKNOWN;
		}
		try {
			return CoopEvent.Kind.valueOf( kindRaw );
		} catch (IllegalArgumentException e) {
			return CoopEvent.Kind.UNKNOWN;
		}
	}

	private static void mergeLegacyFields( JSONObject source, JSONObject payload ) {
		if (source.has( "from" )) {
			payload.put( "from", source.optInt( "from", -1 ) );
		}
		if (source.has( "to" )) {
			payload.put( "to", source.optInt( "to", -1 ) );
		}
		if (source.has( "levelSeed" )) {
			payload.put( "levelSeed", source.optLong( "levelSeed" ) );
		}
		if (source.has( "levelHash" )) {
			payload.put( "levelHash", source.optString( "levelHash", "" ) );
		}
		if (source.has( "payload" ) && !(source.opt( "payload" ) instanceof JSONObject)) {
			String payloadText = source.optString( "payload", null );
			if (payloadText != null && payloadText.length() > 0) {
				try {
					JSONObject nested = new JSONObject( payloadText );
					String[] names = JSONObject.getNames( nested );
					if (names != null) {
						for (String key : names) {
							payload.put( key, nested.get( key ) );
						}
					}
				} catch (Exception ignored) {
					payload.put( "details", payloadText );
				}
			}
		}
	}

	private static void validate( CoopEvent event ) {
		if (event.version <= 0) {
			throw new IllegalArgumentException( "event.version must be > 0" );
		}
		if (event.actorId == null || event.actorId.length() == 0) {
			throw new IllegalArgumentException( "event.actorId is required" );
		}
		if (event.floor <= 0) {
			throw new IllegalArgumentException( "event.floor must be > 0" );
		}
		if (event.tick < 0) {
			throw new IllegalArgumentException( "event.tick must be >= 0" );
		}
		if (event.payload == null) {
			throw new IllegalArgumentException( "event.payload is required" );
		}
		if (event.kind == CoopEvent.Kind.UNKNOWN) {
			return;
		}
		switch (event.kind) {
		case MOVE:
		case ATTACK:
		case USE:
			requireInt( event.payload, "from" );
			requireInt( event.payload, "to" );
			break;
		case LEVEL_SYNC:
			requireLong( event.payload, "levelSeed" );
			break;
		case LEVEL_HASH:
			requireString( event.payload, "levelHash" );
			break;
		case TURN_OUTCOME:
			requireString( event.payload, "actorId" );
			break;
		case ITEM_PICKUP:
		case ITEM_DROP:
		case ITEM_USE:
			requireString( event.payload, "item" );
			break;
		case DOOR_UNLOCK:
		case DESCEND:
		case ASCEND:
			requireInt( event.payload, "cell" );
			break;
		case DEATH:
			requireString( event.payload, "cause" );
			break;
		case BUFF:
			requireString( event.payload, "buff" );
			requireString( event.payload, "op" );
			break;
		case CHAT:
			requireString( event.payload, "message" );
			break;
		case FULL_STATE_SYNC:
			if (!event.payload.has( "state" )) {
				throw new IllegalArgumentException( "payload.state is required for FULL_STATE_SYNC" );
			}
			break;
		case DESPAWN:
		default:
			break;
		}
	}

	private static void requireInt( JSONObject payload, String key ) {
		if (!payload.has( key )) {
			throw new IllegalArgumentException( "payload." + key + " is required" );
		}
		payload.optInt( key );
	}

	private static void requireLong( JSONObject payload, String key ) {
		if (!payload.has( key )) {
			throw new IllegalArgumentException( "payload." + key + " is required" );
		}
		payload.optLong( key );
	}

	private static void requireString( JSONObject payload, String key ) {
		String value = payload.optString( key, null );
		if (value == null || value.length() == 0) {
			throw new IllegalArgumentException( "payload." + key + " is required" );
		}
	}

	public static byte[] toUtf8( CoopEvent event ) throws UnsupportedEncodingException {
		return toJson( event ).getBytes( "UTF-8" );
	}

	public static CoopEvent fromUtf8( byte[] bytes, int length ) throws UnsupportedEncodingException {
		return fromJson( new String( bytes, 0, length, "UTF-8" ) );
	}
}
