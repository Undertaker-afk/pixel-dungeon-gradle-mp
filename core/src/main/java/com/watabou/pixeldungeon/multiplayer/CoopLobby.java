package com.watabou.pixeldungeon.multiplayer;

public class CoopLobby {
	public final String roomId;
	public final String hostPeerId;
	public final int playerCount;
	public final int maxPlayers;
	public final boolean acceptingPlayers;
	public final long announcedAtMillis;
	public final String unlockedClassesCsv;

	public CoopLobby(
		String roomId,
		String hostPeerId,
		int playerCount,
		int maxPlayers,
		boolean acceptingPlayers,
		long announcedAtMillis,
		String unlockedClassesCsv ) {
		this.roomId = roomId;
		this.hostPeerId = hostPeerId;
		this.playerCount = playerCount;
		this.maxPlayers = maxPlayers;
		this.acceptingPlayers = acceptingPlayers;
		this.announcedAtMillis = announcedAtMillis;
		this.unlockedClassesCsv = unlockedClassesCsv;
	}
}
