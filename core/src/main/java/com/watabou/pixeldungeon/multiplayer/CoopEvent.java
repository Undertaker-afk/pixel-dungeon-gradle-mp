package com.watabou.pixeldungeon.multiplayer;

public class CoopEvent {

	public enum Kind {
		MOVE,
		ATTACK
	}

	public final Kind kind;
	public final String actorId;
	public final int depth;
	public final int fromCell;
	public final int toCell;
	public final long sentAtMs;

	public CoopEvent( Kind kind, String actorId, int depth, int fromCell, int toCell, long sentAtMs ) {
		this.kind = kind;
		this.actorId = actorId;
		this.depth = depth;
		this.fromCell = fromCell;
		this.toCell = toCell;
		this.sentAtMs = sentAtMs;
	}

	public static CoopEvent move( String actorId, int depth, int fromCell, int toCell ) {
		return new CoopEvent( Kind.MOVE, actorId, depth, fromCell, toCell, System.currentTimeMillis() );
	}

	public static CoopEvent attack( String actorId, int depth, int fromCell, int toCell ) {
		return new CoopEvent( Kind.ATTACK, actorId, depth, fromCell, toCell, System.currentTimeMillis() );
	}
}
