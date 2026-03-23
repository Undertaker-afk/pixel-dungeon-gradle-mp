package com.watabou.pixeldungeon.multiplayer;

public class CoopEvent {

	public enum Kind {
		MOVE,
		ATTACK,
		USE,
		LEVEL_SYNC,
		LEVEL_HASH,
		TURN_OUTCOME,
		DESPAWN
	}

	public final Kind kind;
	public final String actorId;
	public final int depth;
	public final int fromCell;
	public final int toCell;
	public final Long levelSeed;
	public final String levelHash;
	public final String payload;
	public final long sentAtMs;

	public CoopEvent( Kind kind, String actorId, int depth, int fromCell, int toCell, Long levelSeed, String levelHash, String payload, long sentAtMs ) {
		this.kind = kind;
		this.actorId = actorId;
		this.depth = depth;
		this.fromCell = fromCell;
		this.toCell = toCell;
		this.levelSeed = levelSeed;
		this.levelHash = levelHash;
		this.payload = payload;
		this.sentAtMs = sentAtMs;
	}

	public static CoopEvent move( String actorId, int depth, int fromCell, int toCell ) {
		return new CoopEvent( Kind.MOVE, actorId, depth, fromCell, toCell, null, null, null, System.currentTimeMillis() );
	}

	public static CoopEvent attack( String actorId, int depth, int fromCell, int toCell ) {
		return new CoopEvent( Kind.ATTACK, actorId, depth, fromCell, toCell, null, null, null, System.currentTimeMillis() );
	}

	public static CoopEvent use( String actorId, int depth, int fromCell, int toCell, String payload ) {
		return new CoopEvent( Kind.USE, actorId, depth, fromCell, toCell, null, null, payload, System.currentTimeMillis() );
	}

	public static CoopEvent levelSync( String actorId, int depth, long levelSeed ) {
		return new CoopEvent( Kind.LEVEL_SYNC, actorId, depth, -1, -1, Long.valueOf( levelSeed ), null, null, System.currentTimeMillis() );
	}

	public static CoopEvent levelHash( String actorId, int depth, String levelHash ) {
		return new CoopEvent( Kind.LEVEL_HASH, actorId, depth, -1, -1, null, levelHash, null, System.currentTimeMillis() );
	}

	public static CoopEvent turnOutcome( String actorId, int depth, String payload ) {
		return new CoopEvent( Kind.TURN_OUTCOME, actorId, depth, -1, -1, null, null, payload, System.currentTimeMillis() );
	}

	public static CoopEvent despawn( String actorId, int depth ) {
		return new CoopEvent( Kind.DESPAWN, actorId, depth, -1, -1, null, null, null, System.currentTimeMillis() );
	}
}
