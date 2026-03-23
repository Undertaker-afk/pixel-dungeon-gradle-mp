package com.watabou.pixeldungeon.coop.gameplay;

import com.watabou.pixeldungeon.actors.Char;

public class RemoteHero extends Char {

	public final String peerId;
	public final String displayName;
	public int hpSnapshot;
	public int lastKnownPos;

	public RemoteHero( String peerId, String displayName, int startPos ) {
		this.peerId = peerId;
		this.displayName = displayName;
		this.name = displayName;
		this.pos = startPos;
		this.lastKnownPos = startPos;
		this.HT = 1;
		this.HP = 1;
		this.hpSnapshot = this.HP;
		diactivate();
	}

	@Override
	protected boolean act() {
		return false;
	}

	public void snapshotPosition( int newPos ) {
		this.pos = newPos;
		this.lastKnownPos = newPos;
	}

	public void snapshotHp( int newHp ) {
		hpSnapshot = Math.max( 0, newHp );
		HP = hpSnapshot;
	}
}
