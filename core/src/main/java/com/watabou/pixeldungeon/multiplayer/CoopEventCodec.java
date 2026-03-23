package com.watabou.pixeldungeon.multiplayer;

import java.io.UnsupportedEncodingException;

import org.json.JSONObject;

public final class CoopEventCodec {

	private CoopEventCodec() {
	}

	public static String toJson( CoopEvent event ) {
		JSONObject json = new JSONObject();
		json.put( "kind", event.kind.name() );
		json.put( "actor", event.actorId );
		json.put( "depth", event.depth );
		json.put( "from", event.fromCell );
		json.put( "to", event.toCell );
		if (event.levelSeed != null) {
			json.put( "levelSeed", event.levelSeed.longValue() );
		}
		if (event.levelHash != null) {
			json.put( "levelHash", event.levelHash );
		}
		json.put( "sentAtMs", event.sentAtMs );
		return json.toString();
	}

	public static CoopEvent fromJson( String text ) {
		JSONObject json = new JSONObject( text );
		return new CoopEvent(
			CoopEvent.Kind.valueOf( json.getString( "kind" ) ),
			json.getString( "actor" ),
			json.getInt( "depth" ),
			json.optInt( "from", -1 ),
			json.optInt( "to", -1 ),
			json.has( "levelSeed" ) ? Long.valueOf( json.getLong( "levelSeed" ) ) : null,
			json.optString( "levelHash", null ),
			json.optLong( "sentAtMs", System.currentTimeMillis() ) );
	}

	public static byte[] toUtf8( CoopEvent event ) throws UnsupportedEncodingException {
		return toJson( event ).getBytes( "UTF-8" );
	}

	public static CoopEvent fromUtf8( byte[] bytes, int length ) throws UnsupportedEncodingException {
		return fromJson( new String( bytes, 0, length, "UTF-8" ) );
	}
}
