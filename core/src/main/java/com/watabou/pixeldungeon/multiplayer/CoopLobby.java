package com.watabou.pixeldungeon.multiplayer;

public class CoopLobby {
	public final String roomId;
	public final String hostPeerId;
	public final int playerCount;
	public final int maxPlayers;
	public final boolean acceptingPlayers;
	public final long announcedAtMillis;
	public final String unlockedClassesCsv;
	public final PeerEndpoint hostEndpoint;
	public final String joinKey;
	public final boolean reconnectSupported;
	public final int floor;

	public CoopLobby(
		String roomId,
		String hostPeerId,
		int playerCount,
		int maxPlayers,
		boolean acceptingPlayers,
		long announcedAtMillis,
		String unlockedClassesCsv ) {
		this( roomId, hostPeerId, playerCount, maxPlayers, acceptingPlayers, announcedAtMillis, unlockedClassesCsv, null, "", true, 0 );
	}

	public CoopLobby(
		String roomId,
		String hostPeerId,
		int playerCount,
		int maxPlayers,
		boolean acceptingPlayers,
		long announcedAtMillis,
		String unlockedClassesCsv,
		PeerEndpoint hostEndpoint ) {
		this( roomId, hostPeerId, playerCount, maxPlayers, acceptingPlayers, announcedAtMillis, unlockedClassesCsv, hostEndpoint, "", true, 0 );
	}

	public CoopLobby(
		String roomId,
		String hostPeerId,
		int playerCount,
		int maxPlayers,
		boolean acceptingPlayers,
		long announcedAtMillis,
		String unlockedClassesCsv,
		PeerEndpoint hostEndpoint,
		String joinKey,
		boolean reconnectSupported,
		int floor ) {
		this.roomId = roomId;
		this.hostPeerId = hostPeerId;
		this.playerCount = playerCount;
		this.maxPlayers = maxPlayers;
		this.acceptingPlayers = acceptingPlayers;
		this.announcedAtMillis = announcedAtMillis;
		this.unlockedClassesCsv = unlockedClassesCsv;
		this.hostEndpoint = hostEndpoint;
		this.joinKey = joinKey == null ? "" : joinKey;
		this.reconnectSupported = reconnectSupported;
		this.floor = floor;
	}
}
