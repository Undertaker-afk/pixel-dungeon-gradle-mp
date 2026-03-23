package com.watabou.pixeldungeon.multiplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CoopEventCodecTest {

	@Test
	public void moveRoundTripIncludesVersionedEnvelope() {
		CoopEvent source = CoopEvent.move( "peer-a", 4, 11, 17 );
		String json = CoopEventCodec.toJson( source );
		CoopEvent decoded = CoopEventCodec.fromJson( json );

		assertEquals( CoopEvent.CURRENT_VERSION, decoded.version );
		assertEquals( CoopEvent.Kind.MOVE, decoded.kind );
		assertEquals( "peer-a", decoded.actorId );
		assertEquals( 4, decoded.floor );
		assertTrue( decoded.tick >= 0 );
		assertEquals( 11, decoded.fromCell );
		assertEquals( 17, decoded.toCell );
		assertNotNull( decoded.payload );
	}

	@Test
	public void unknownKindDecodesAsUnknownForForwardCompatibility() {
		String raw = "{\"version\":1,\"kind\":\"FUTURE_KIND\",\"actorId\":\"peer-f\",\"floor\":3,\"tick\":55,\"payload\":{\"foo\":\"bar\"}}";
		CoopEvent decoded = CoopEventCodec.fromJson( raw );

		assertEquals( CoopEvent.Kind.UNKNOWN, decoded.kind );
		assertEquals( "FUTURE_KIND", decoded.kindRaw );
		assertEquals( "peer-f", decoded.actorId );
		assertEquals( 3, decoded.floor );
		assertEquals( "bar", decoded.payload.optString( "foo" ) );
	}

	@Test
	public void legacyPayloadFormatStillDecodes() {
		String legacy = "{\"kind\":\"MOVE\",\"actor\":\"peer-l\",\"depth\":7,\"from\":1,\"to\":2,\"sentAtMs\":12345}";
		CoopEvent decoded = CoopEventCodec.fromJson( legacy );

		assertEquals( CoopEvent.Kind.MOVE, decoded.kind );
		assertEquals( "peer-l", decoded.actorId );
		assertEquals( 7, decoded.floor );
		assertEquals( 1, decoded.fromCell );
		assertEquals( 2, decoded.toCell );
	}

	@Test(expected = IllegalArgumentException.class)
	public void missingRequiredMoveFieldFailsValidation() {
		JSONObject bad = new JSONObject();
		bad.put( "version", 1 );
		bad.put( "kind", "MOVE" );
		bad.put( "actorId", "peer-a" );
		bad.put( "floor", 2 );
		bad.put( "tick", 1 );
		bad.put( "payload", new JSONObject().put( "from", 5 ) );
		CoopEventCodec.fromJson( bad.toString() );
	}

	@Test(expected = IllegalArgumentException.class)
	public void missingRequiredDoorUnlockFieldFailsValidation() {
		JSONObject bad = new JSONObject();
		bad.put( "version", 1 );
		bad.put( "kind", "DOOR_UNLOCK" );
		bad.put( "actorId", "peer-a" );
		bad.put( "floor", 2 );
		bad.put( "tick", 1 );
		bad.put( "payload", new JSONObject() );
		CoopEventCodec.fromJson( bad.toString() );
	}

	@Test
	public void levelSyncRequiresLevelSeedAndRetainsIt() {
		CoopEvent sync = CoopEvent.levelSync( "peer-s", 8, 98765L );
		CoopEvent decoded = CoopEventCodec.fromJson( CoopEventCodec.toJson( sync ) );

		assertEquals( CoopEvent.Kind.LEVEL_SYNC, decoded.kind );
		assertEquals( Long.valueOf( 98765L ), decoded.levelSeed );
	}
}
